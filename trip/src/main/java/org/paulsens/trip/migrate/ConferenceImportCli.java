package org.paulsens.trip.migrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;

/**
 * The conference import's command line (docs/migrations/conference-import.md); the account's script
 * ({@code ../medjugorje/scripts/conf-import.sh}) supplies the classpath, the profile and the region.
 *
 * <pre>
 * plan     --export DIR --mapping FILE --out DIR --actor EMAIL
 *                                                     reads only: writes plan.json, snapshot.json, plan.txt,
 *                                                     people.tsv, review.tsv
 * rehearse --export DIR --mapping FILE --out DIR --actor EMAIL
 *                                                     LOCAL MODE ONLY: seeds an in-memory store from snapshot.json,
 *                                                     then applies plan.json, verifies, re-applies (nothing new may
 *                                                     be written) and rolls back; results in DIR/rehearsal
 * apply    --export DIR --mapping FILE --out DIR --actor EMAIL --yes
 *                                                     writes plan.json exactly, then verifies; report.json and
 *                                                     rollback-deletes.sh land in --out
 * verify   --export DIR --mapping FILE --out DIR      re-checks the target against plan.json and the export
 * rollback --mapping FILE --out DIR --actor EMAIL --yes  undoes report.json (then run rollback-deletes.sh)
 * apply    --patch-legacy-pricing --export DIR --mapping FILE --out DIR --actor EMAIL [--yes]
 *                                                     carries the legacy pricing onto trips and registrations an
 *                                                     earlier run imported without it; a DRY RUN without --yes
 *                                                     (patch-dry-run.txt); with it, patch-report.json and a verify
 * rollback --patch-legacy-pricing --mapping FILE --out DIR --actor EMAIL --yes
 *                                                     restores the rows patch-report.json rewrote
 * </pre>
 * Exit status: 0 done, 1 usage or failure, 2 blockers or failed checks.
 */
public final class ConferenceImportCli {
    static final String PATCH_FLAG = "patch-legacy-pricing";
    static final String PATCH_REPORT = "patch-report.json";

    private final ObjectMapper mapper;
    private final PrintStream out;

    ConferenceImportCli(final ObjectMapper mapper, final PrintStream out) {
        this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.out = out;
    }

    public static void main(final String[] args) {
        System.exit(new ConferenceImportCli(DAO.getInstance().getMapper(), System.out).run(args));
    }

    int run(final String[] args) {
        if (args.length == 0) {
            out.println("usage: plan|rehearse|apply|verify|rollback --export DIR --mapping FILE --out DIR "
                    + "[--actor EMAIL] [--yes] [--patch-legacy-pricing]");
            return 1;
        }
        final Map<String, String> opts = options(args);
        final boolean patch = opts.containsKey(PATCH_FLAG);
        try {
            return switch (args[0]) {
                case "plan" -> plan(opts);
                case "rehearse" -> rehearse(opts);
                case "apply" -> patch ? patchPricing(opts) : apply(opts);
                case "verify" -> verify(opts);
                case "rollback" -> patch ? rollbackPatch(opts) : rollback(opts);
                default -> {
                    out.println("Unknown command: " + args[0]);
                    yield 1;
                }
            };
        } catch (final IOException | IllegalArgumentException | IllegalStateException ex) {
            out.println("ERROR: " + ex.getMessage());
            return 1;
        }
    }

    // ------------------------------------------------------------------ commands

    private int plan(final Map<String, String> opts) throws IOException {
        final ImportMapping mapping = ImportMapping.read(path(opts, "mapping"), mapper);
        final LegacyExport export = LegacyExport.read(path(opts, "export"), mapper);
        final ImportPlan plan = new ConferenceImportPlanner(mapper, mapping, new DaoTarget()).plan(export);
        final Path dir = Files.createDirectories(path(opts, "out"));
        mapper.writeValue(dir.resolve("plan.json").toFile(), plan);
        mapper.writeValue(dir.resolve("snapshot.json").toFile(),
                ImportSnapshot.of(plan, mapping.targetOrgId(), operator(opts.get("actor"))));
        final ConferenceImportVerifier verifier = new ConferenceImportVerifier(mapper, mapping);
        verifier.checkPlan(export, plan);
        final List<String> summary = summary(export, plan, verifier);
        Files.write(dir.resolve("plan.txt"), summary);
        Files.write(dir.resolve("people.tsv"), peopleTable(plan));
        Files.write(dir.resolve("review.tsv"), plan.review());
        summary.forEach(out::println);
        out.println("Wrote " + dir.resolve("plan.json") + " (apply runs exactly this file).");
        return plan.blockers().isEmpty() && verifier.failures() == 0 ? 0 : 2;
    }

    private int apply(final Map<String, String> opts) throws IOException {
        requireYes(opts);
        final ImportMapping mapping = ImportMapping.read(path(opts, "mapping"), mapper);
        final LegacyExport export = LegacyExport.read(path(opts, "export"), mapper);
        final Path dir = path(opts, "out");
        final ImportPlan plan = mapper.readValue(dir.resolve("plan.json").toFile(), ImportPlan.class);
        final Caller caller = siteAdmin(opts.get("actor"));
        final ImportReport report = new ConferenceImportApplier(mapper, caller, done -> saveReport(dir, done))
                .apply(plan, mapping.targetOrgId());
        Files.writeString(dir.resolve("rollback-deletes.sh"), ConferenceImportRollback.deleteScript(report));
        out.println("Applied: " + report.getCreatedPeople().size() + " people created, "
                + report.getMergedPeople().size() + " merged, " + report.getMemberships().size() + " memberships, "
                + report.getTrips().size() + " trips, " + report.getRegistrations().size() + " registrations, "
                + report.getTransactions().size() + " ledger rows, " + report.getPrivileges().size() + " roles; "
                + report.getSkipped().size() + " already present, " + report.getFailures().size() + " failures.");
        report.getFailures().forEach(failure -> out.println("  FAILURE " + failure));
        final int verified = verify(export, mapping, plan, dir);
        return report.getFailures().isEmpty() ? verified : 2;
    }

    /** The real applier and verifier on the real plan, in memory: what an apply would do, without doing it. */
    private int rehearse(final Map<String, String> opts) throws IOException {
        final ImportMapping mapping = ImportMapping.read(path(opts, "mapping"), mapper);
        final LegacyExport export = LegacyExport.read(path(opts, "export"), mapper);
        final Path dir = path(opts, "out");
        final Path rehearsal = Files.createDirectories(dir.resolve("rehearsal"));
        final ImportPlan plan = mapper.readValue(dir.resolve("plan.json").toFile(), ImportPlan.class);
        mapper.readValue(dir.resolve("snapshot.json").toFile(), ImportSnapshot.class).seed(plan);
        final Caller caller = siteAdmin(opts.get("actor"));
        final ImportReport first = new ConferenceImportApplier(mapper, caller, done -> saveReport(rehearsal, done))
                .apply(plan, mapping.targetOrgId());
        out.println("Rehearsal apply: " + first.getCreatedPeople().size() + " created, "
                + first.getMergedPeople().size() + " merged, " + first.getRegistrations().size() + " registrations, "
                + first.getTransactions().size() + " ledger rows, " + first.getFailures().size() + " failures");
        first.getFailures().forEach(failure -> out.println("  FAILURE " + failure));
        final int verified = verify(export, mapping, plan, rehearsal);
        final ImportReport second = new ConferenceImportApplier(mapper, caller, done -> { })
                .apply(plan, mapping.targetOrgId());
        final int rewrites = second.getCreatedPeople().size() + second.getMergedPeople().size()
                + second.getMemberships().size() + second.getTrips().size() + second.getRegistrations().size()
                + second.getTransactions().size() + second.getPrivileges().size();
        out.println("Rehearsal re-apply: " + rewrites + " rows written again (must be 0: "
                + second.getCreatedPeople().size() + " people, "
                + second.getMergedPeople().size() + " merges, "
                + second.getMemberships().size() + " memberships, "
                + second.getRegistrations().size() + " registrations, "
                + second.getTransactions().size() + " ledger rows, "
                + second.getPrivileges().size() + " roles), "
                + second.getFailures().size() + " failures");
        final ConferenceImportRollback rollback = new ConferenceImportRollback(mapper, caller.auditActor());
        rollback.undo(first);
        Files.write(rehearsal.resolve("rollback.txt"), rollback.lines());
        final long undoFailures = rollback.lines().stream().filter(line -> line.startsWith("FAILED")).count();
        out.println("Rehearsal rollback: " + rollback.lines().size() + " rows undone, " + undoFailures + " failed");
        final boolean ok = first.getFailures().isEmpty() && verified == 0 && rewrites == 0
                && second.getFailures().isEmpty() && undoFailures == 0;
        out.println(ok ? "REHEARSAL PASSED" : "REHEARSAL FAILED");
        return ok ? 0 : 2;
    }

    private int verify(final Map<String, String> opts) throws IOException {
        final ImportMapping mapping = ImportMapping.read(path(opts, "mapping"), mapper);
        final LegacyExport export = LegacyExport.read(path(opts, "export"), mapper);
        final Path dir = path(opts, "out");
        final ImportPlan plan = mapper.readValue(dir.resolve("plan.json").toFile(), ImportPlan.class);
        return verify(export, mapping, plan, dir);
    }

    private int verify(final LegacyExport export, final ImportMapping mapping, final ImportPlan plan, final Path dir)
            throws IOException {
        final ConferenceImportVerifier verifier = new ConferenceImportVerifier(mapper, mapping);
        verifier.checkTarget(export, plan);
        Files.write(dir.resolve("verify.txt"), verifier.lines());
        verifier.lines().forEach(out::println);
        out.println(verifier.failures() == 0 ? "VERIFIED" : verifier.failures() + " CHECKS FAILED");
        return verifier.failures() == 0 ? 0 : 2;
    }

    private int rollback(final Map<String, String> opts) throws IOException {
        requireYes(opts);
        final Path dir = path(opts, "out");
        final ImportReport report = mapper.readValue(dir.resolve("report.json").toFile(), ImportReport.class);
        final ConferenceImportRollback rollback = new ConferenceImportRollback(mapper,
                siteAdmin(opts.get("actor")).auditActor());
        rollback.undo(report);
        Files.write(dir.resolve("rollback.txt"), rollback.lines());
        Files.writeString(dir.resolve("rollback-deletes.sh"), ConferenceImportRollback.deleteScript(report));
        rollback.lines().forEach(out::println);
        out.println("Now review and run " + dir.resolve("rollback-deletes.sh") + " for the created people and rows.");
        return rollback.lines().stream().anyMatch(line -> line.startsWith("FAILED")) ? 2 : 0;
    }

    // ------------------------------------------------------------------ the legacy-pricing patch

    /**
     * Plans afresh against the target (the plan is where the pricing lives, and the 2026-11-01 plan.json predates
     * it), then patches the present rows: a dry run without {@code --yes}, written and verified with it. The
     * report goes to its own file so the import's own report.json, and its rollback, stay what they were.
     */
    private int patchPricing(final Map<String, String> opts) throws IOException {
        final boolean write = opts.containsKey("yes");
        final ImportMapping mapping = ImportMapping.read(path(opts, "mapping"), mapper);
        final LegacyExport export = LegacyExport.read(path(opts, "export"), mapper);
        final Path dir = Files.createDirectories(path(opts, "out"));
        final ImportPlan plan = new ConferenceImportPlanner(mapper, mapping, new DaoTarget()).plan(export);
        if (!plan.blockers().isEmpty()) {
            out.println("Blockers (" + plan.blockers().size() + "): the patch needs a clean plan.");
            plan.blockers().forEach(blocker -> out.println("  " + blocker));
            return 2;
        }
        final Caller caller = siteAdmin(opts.get("actor"));
        final ConferenceImportApplier applier = new ConferenceImportApplier(mapper, caller,
                done -> saveReportTo(dir.resolve(PATCH_REPORT), done));
        final ImportReport report = applier.patchLegacyPricing(plan, mapping.targetOrgId(), write);
        Files.write(dir.resolve(write ? "patch.txt" : "patch-dry-run.txt"), applier.patchLines());
        applier.patchLines().forEach(out::println);
        report.getFailures().forEach(failure -> out.println("  FAILURE " + failure));
        if (!write) {
            out.println("DRY RUN: " + count(applier.patchLines(), "would patch trip") + " trips and "
                    + count(applier.patchLines(), "would patch registration") + " registrations would be patched, "
                    + report.getSkipped().size() + " rows skipped; nothing written. Add --yes to apply.");
            return report.getFailures().isEmpty() ? 0 : 2;
        }
        out.println("Patched " + report.getPatchedTrips().size() + " trips and "
                + report.getPatchedRegistrations().size() + " registrations; " + report.getSkipped().size()
                + " skipped, " + report.getFailures().size() + " failures.");
        final int verified = verify(export, mapping, plan, dir);
        return report.getFailures().isEmpty() ? verified : 2;
    }

    private int rollbackPatch(final Map<String, String> opts) throws IOException {
        requireYes(opts);
        final Path dir = path(opts, "out");
        final ImportReport report = mapper.readValue(dir.resolve(PATCH_REPORT).toFile(), ImportReport.class);
        final ConferenceImportRollback rollback = new ConferenceImportRollback(mapper,
                siteAdmin(opts.get("actor")).auditActor());
        rollback.undo(report);
        Files.write(dir.resolve("patch-rollback.txt"), rollback.lines());
        rollback.lines().forEach(out::println);
        return rollback.lines().stream().anyMatch(line -> line.startsWith("FAILED")) ? 2 : 0;
    }

    private static long count(final List<String> lines, final String prefix) {
        return lines.stream().filter(line -> line.startsWith(prefix)).count();
    }

    // ------------------------------------------------------------------ the plan's summary

    private List<String> summary(final LegacyExport export, final ImportPlan plan,
            final ConferenceImportVerifier verifier) {
        final List<String> lines = new ArrayList<>();
        lines.add("Conference import plan");
        lines.add("  export: " + export.trips().size() + " trips, " + export.people().size() + " people, "
                + export.registrations().size() + " registrations, " + export.transactions().size()
                + " ledger rows, " + export.privileges().size() + " roles");
        for (final ImportPlan.Action action : ImportPlan.Action.values()) {
            lines.add("  people " + action + ": " + plan.count(action));
        }
        lines.add("  family links: " + plan.families().size() + "; ambiguous (review.tsv): " + plan.review().size());
        lines.add("  trips: " + plan.trips().size() + "; registrations: " + plan.registrations().size()
                + "; ledger rows: " + plan.transactions().size() + "; roles: " + plan.roles().size());
        lines.add("Checks:");
        verifier.lines().forEach(line -> lines.add("  " + line));
        lines.add("Blockers (" + plan.blockers().size() + "):");
        plan.blockers().forEach(blocker -> lines.add("  " + blocker));
        lines.add("Notes (" + plan.notes().size() + "):");
        plan.notes().forEach(note -> lines.add("  " + note));
        return lines;
    }

    /** One line per old person: what happens to them and why. Review this before an apply. */
    private static List<String> peopleTable(final ImportPlan plan) {
        final List<String> rows = new ArrayList<>();
        rows.add("oldId\taction\ttargetId\tname\temail\tfilled\tkept\twhy");
        for (final ImportPlan.PersonStep step : plan.people()) {
            final Person person = step.person();
            rows.add(String.join("\t", step.oldId(), step.action().name(),
                    step.targetId() == null ? "" : step.targetId().getValue(),
                    person == null ? "" : person.getFirst() + " " + person.getLast(),
                    person == null || person.getEmail() == null ? "" : person.getEmail(),
                    String.join(",", step.filled()), String.join(",", step.kept()), step.note()));
        }
        return rows;
    }

    // ------------------------------------------------------------------ helpers

    /** The operator as a site-admin caller: the membership and family writers require one. */
    private static Caller siteAdmin(final String email) {
        final Person operator = operator(email);
        return new Caller(operator.getId(), true, new AuditActor(email, operator.getId().getValue()),
                new PrivilegeCommands());
    }

    /** The operator's person in the target. */
    private static Person operator(final String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("--actor EMAIL is required (the operator, a site admin).");
        }
        final Person operator = DAO.getInstance().getPersonByEmail(email, Cached.NO);
        if (operator == null) {
            throw new IllegalArgumentException("No person with the address " + email + " in the target.");
        }
        return operator;
    }

    private void saveReport(final Path dir, final ImportReport report) {
        saveReportTo(dir.resolve("report.json"), report);
    }

    private void saveReportTo(final Path file, final ImportReport report) {
        try {
            mapper.writeValue(file.toFile(), report);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void requireYes(final Map<String, String> opts) {
        if (!opts.containsKey("yes")) {
            throw new IllegalArgumentException("This writes to the target; pass --yes after reviewing plan.txt.");
        }
    }

    private static Path path(final Map<String, String> opts, final String name) {
        final String value = opts.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + name + " is required.");
        }
        return Path.of(value);
    }

    private static Map<String, String> options(final String[] args) {
        final Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                continue;
            }
            final String name = args[i].substring(2);
            final boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("--");
            opts.put(name, hasValue ? args[++i] : "");
        }
        return opts;
    }

    /** The target as the planner reads it: fresh DAO reads, never the cache. */
    static final class DaoTarget implements ConferenceImportPlanner.Target {
        @Override
        public Optional<Person> personByEmail(final String email) {
            return Optional.ofNullable(DAO.getInstance().getPersonByEmail(email, Cached.NO));
        }

        @Override
        public Optional<Person> personById(final Person.Id id) {
            return DAO.getInstance().getPerson(id, Cached.NO);
        }

        @Override
        public boolean tripExists(final String tripId) {
            return DAO.getInstance().getTrip(tripId, Cached.NO).isPresent();
        }
    }
}
