package org.paulsens.trip.migrate;

import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.migrate.LedgerBindingApplier.Added;
import org.paulsens.trip.migrate.LedgerBindingApplier.Approval;
import org.paulsens.trip.migrate.LedgerBindingApplier.Outcome;
import org.paulsens.trip.migrate.LedgerBindingApplier.Step;
import org.paulsens.trip.migrate.LedgerBindingPlan.Kind;
import org.paulsens.trip.migrate.LedgerBindingPlan.Uncertain;

/**
 * The one-time migration that gives every ledger row a trip (docs/migrations/ledger-trip-binding.md); the
 * account's script ({@code ../medjugorje/scripts/ledger-bind.sh}) supplies the classpath, the profile and the
 * region. It adds TRANSACTION and TRIP bindings and nothing else: no transaction row is written.
 *
 * <pre>
 * snapshot --out DIR [--dump DIR]         the ledger's figures by today's arithmetic: snapshot.json, snapshot.txt
 * plan     --out DIR [--dump DIR]         reads only: plan.json, uncertain.csv (for the owner), plan.txt,
 *                                         per-trip-summary.csv
 * rehearse --out DIR --dump DIR [--plan FILE] [--approved FILE]
 *                                         IN MEMORY: applies the plan to the dump, verifies, applies again
 *                                         (nothing new may be written), rolls back; results in DIR/rehearsal
 * apply    --out DIR --plan FILE --approved FILE [--yes]
 *                                         a DRY RUN without --yes (every check against the deployment, no write:
 *                                         apply-dry-run.txt). With it, WRITES THE DEPLOYMENT: binds the plan's
 *                                         rows and the rows approved in the review file; rollback.json and
 *                                         apply.txt land in --out
 * verify   --out DIR --before FILE [--dump DIR] [--plan FILE] [--approved FILE]
 *                                         the seven checks of the ledger now against a snapshot: verify.txt
 * rollback --out DIR [--rollback FILE] [--yes]
 *                                         a DRY RUN without --yes; with it, WRITES THE DEPLOYMENT: removes
 *                                         exactly the bindings in rollback.json
 * </pre>
 * With {@code --dump} a command reads a dump directory ({@code <table>.json} per table) and touches no store;
 * without it, the live tables, uncached. {@code --today YYYY-MM-DD} pins the day "has this trip ended" is asked
 * on. Exit status: 0 done, 1 usage or failure, 2 failed checks or writes.
 */
public final class LedgerBindingCli {
    private static final String USAGE = "usage: snapshot|plan|rehearse|apply|verify|rollback --out DIR "
            + "[--dump DIR] [--plan FILE] [--approved FILE] [--before FILE] [--rollback FILE] [--today DATE] [--yes]";
    private static final String ROLLBACK_FILE = "rollback.json";
    private static final String PLAN_FILE = "plan.json";

    private final PrintStream out;
    private final Supplier<DAO> dao;

    /**
     * @param dao asked only by a command that reads or writes the deployment, so an offline run builds no client
     */
    LedgerBindingCli(final PrintStream out, final Supplier<DAO> dao) {
        this.out = out;
        this.dao = dao;
    }

    public static void main(final String[] args) {
        System.exit(new LedgerBindingCli(System.out, DAO::getInstance).run(args));
    }

    int run(final String[] args) {
        if (args.length == 0) {
            out.println(USAGE);
            return 1;
        }
        final Map<String, String> opts = CliArgs.options(args);
        try {
            return switch (args[0]) {
                case "snapshot" -> snapshot(opts);
                case "plan" -> plan(opts);
                case "rehearse" -> rehearse(opts);
                case "apply" -> apply(opts);
                case "verify" -> verify(opts);
                case "rollback" -> rollback(opts);
                default -> {
                    out.println("Unknown command: " + args[0]);
                    yield 1;
                }
            };
        } catch (final IOException | UncheckedIOException | IllegalArgumentException | IllegalStateException ex) {
            out.println("ERROR: " + ex.getMessage());
            return 1;
        }
    }

    // ------------------------------------------------------------------ commands

    private int snapshot(final Map<String, String> opts) throws IOException {
        final Path dir = LedgerFiles.dir(CliArgs.path(opts, "out"));
        final LedgerData data = load(opts);
        final LedgerSnapshot snapshot = LedgerSnapshot.of(data, today(opts));
        LedgerFiles.writeJson(dir.resolve("snapshot.json"), snapshot);
        final List<String> lines = List.of("Ledger snapshot (as of " + snapshot.asOf() + ")",
                "  rows: " + snapshot.allRows() + "; live: " + snapshot.liveRows(),
                "  partitions with live rows: " + snapshot.balance().size(),
                "  live rows bound to no trip: " + snapshot.unboundLive().size(),
                "  trips: " + snapshot.tripTotals().size() + "; not ended: " + snapshot.tripBound().size());
        LedgerFiles.writeLines(dir.resolve("snapshot.txt"), lines);
        lines.forEach(out::println);
        out.println("Wrote " + dir.resolve("snapshot.json"));
        return 0;
    }

    private int plan(final Map<String, String> opts) throws IOException {
        final Path dir = LedgerFiles.dir(CliArgs.path(opts, "out"));
        final LedgerData data = load(opts);
        final LedgerBindingPlan plan = new LedgerBindingPlanner(data, today(opts)).plan();
        final LedgerPlanFiles files = new LedgerPlanFiles(data, plan);
        LedgerFiles.writeJson(dir.resolve(PLAN_FILE), plan);
        LedgerFiles.writeLines(dir.resolve("uncertain.csv"), files.uncertainCsv());
        LedgerFiles.writeLines(dir.resolve("per-trip-summary.csv"), files.perTripCsv());
        LedgerFiles.writeLines(dir.resolve("plan.txt"), files.summary());
        files.summary().forEach(out::println);
        out.println("Wrote " + dir.resolve(PLAN_FILE) + " (an apply runs exactly this file) and uncertain.csv.");
        return 0;
    }

    /** The real applier and verifier on the real plan, against the dump's own bindings: nothing is written. */
    private int rehearse(final Map<String, String> opts) throws IOException {
        final Path dir = CliArgs.path(opts, "out");
        final Path rehearsal = LedgerFiles.dir(dir.resolve("rehearsal"));
        final LedgerData original = LedgerData.fromDump(CliArgs.path(opts, "dump"));
        final LocalDate today = today(opts);
        final LedgerSnapshot before = LedgerSnapshot.of(original, today);
        final LedgerBindingPlan plan = readPlan(opts.containsKey("plan") ? CliArgs.path(opts, "plan")
                : dir.resolve(PLAN_FILE));
        final List<String> report = new ArrayList<>();
        final List<Step> steps = LedgerBindingApplier.steps(plan, approvals(opts), report);
        final LedgerData working = original.copy();
        final LedgerBindingApplier applier = new LedgerBindingApplier(new LedgerStore.Memory(working),
                added -> saveRollback(rehearsal.resolve(ROLLBACK_FILE), added));
        final Outcome first = applier.apply(steps, List.of());
        report.add(applied(first));
        report.addAll(first.skipped());
        report.addAll(first.failures());
        final LedgerBindingVerifier verifier = verifier(before, working, today, plan, steps).verify();
        final Outcome second = applier.apply(steps, List.of());
        report.add("Re-apply: " + second.added().size() + " bindings written again (must be 0), "
                + second.failures().size() + " failures");
        final List<String> undone = applier.rollback(first.added());
        final boolean restored = working.forward().equals(original.forward())
                && working.reverse().equals(original.reverse());
        report.add("Rollback: " + undone.size() + " bindings removed; the dump's bindings are "
                + (restored ? "exactly restored" : "NOT RESTORED"));
        LedgerFiles.writeLines(rehearsal.resolve("apply.txt"), report);
        writeVerify(rehearsal.resolve("verify.txt"), verifier);
        report.forEach(out::println);
        verifier.lines().forEach(out::println);
        final boolean mechanics = report.stream().noneMatch(line -> line.startsWith("REFUSED"))
                && first.failures().isEmpty() && second.added().isEmpty() && second.failures().isEmpty()
                && restored;
        return conclude("REHEARSAL", mechanics, verifier, true);
    }

    private int apply(final Map<String, String> opts) throws IOException {
        final Path dir = LedgerFiles.dir(CliArgs.path(opts, "out"));
        final LedgerBindingPlan plan = readPlan(CliArgs.path(opts, "plan"));
        final List<Approval> approvals = LedgerPlanFiles.approvals(Files.readString(CliArgs.path(opts, "approved")));
        final List<String> report = new ArrayList<>();
        final List<Step> steps = LedgerBindingApplier.steps(plan, approvals, report);
        if (!opts.containsKey("yes")) {
            return dryApply(dir, steps, report);
        }
        final Path rollbackFile = dir.resolve(ROLLBACK_FILE);
        final List<Added> already = Files.exists(rollbackFile) ? readRollback(rollbackFile) : List.of();
        final Outcome outcome = new LedgerBindingApplier(new LedgerStore.Live(dao.get()),
                added -> saveRollback(rollbackFile, added)).apply(steps, already);
        saveRollback(rollbackFile, outcome.added());
        report.add(applied(outcome));
        report.addAll(outcome.skipped());
        report.addAll(outcome.failures());
        LedgerFiles.writeLines(dir.resolve("apply.txt"), report);
        report.forEach(out::println);
        out.println("Wrote " + rollbackFile + " (exactly the bindings added). Now press Clear caches on the admin "
                + "Settings page, then run verify.");
        return outcome.failures().isEmpty() && report.stream().noneMatch(line -> line.startsWith("REFUSED")) ? 0 : 2;
    }

    /** Without {@code --yes}: every check of an apply against the deployment, and not one write. */
    private int dryApply(final Path dir, final List<Step> steps, final List<String> report) throws IOException {
        final Outcome outcome = LedgerBindingApplier.dryRun(new LedgerStore.Live(dao.get())).apply(steps, List.of());
        final long would = outcome.skipped().stream().filter(line -> line.startsWith("WOULD BIND")).count();
        report.addAll(outcome.skipped());
        report.addAll(outcome.failures());
        LedgerFiles.writeLines(dir.resolve("apply-dry-run.txt"), report);
        report.stream().filter(line -> !line.startsWith("WOULD BIND")).forEach(out::println);
        out.println("DRY RUN: " + would + " rows would be bound, " + (outcome.skipped().size() - would)
                + " skipped, " + outcome.failures().size() + " failures; nothing written. Add --yes to apply.");
        return outcome.failures().isEmpty() && report.stream().noneMatch(line -> line.startsWith("REFUSED")) ? 0 : 2;
    }

    private int verify(final Map<String, String> opts) throws IOException {
        final Path dir = LedgerFiles.dir(CliArgs.path(opts, "out"));
        final LedgerSnapshot before = LedgerData.mapper().readValue(CliArgs.path(opts, "before").toFile(),
                LedgerSnapshot.class);
        final LedgerData now = load(opts);
        final LedgerBindingPlan plan = opts.containsKey("plan") ? readPlan(CliArgs.path(opts, "plan"))
                : new LedgerBindingPlan(before.asOf(), List.of(), List.of());
        final List<Step> steps = LedgerBindingApplier.steps(plan, approvals(opts), new ArrayList<>());
        final LedgerBindingVerifier verifier = verifier(before, now, today(opts), plan, steps).verify();
        writeVerify(dir.resolve("verify.txt"), verifier);
        verifier.lines().forEach(out::println);
        return conclude("VERIFY", true, verifier, false);
    }

    private int rollback(final Map<String, String> opts) throws IOException {
        final boolean write = opts.containsKey("yes");
        final Path dir = LedgerFiles.dir(CliArgs.path(opts, "out"));
        final List<Added> added = readRollback(opts.containsKey("rollback") ? CliArgs.path(opts, "rollback")
                : dir.resolve(ROLLBACK_FILE));
        final LedgerStore store = new LedgerStore.Live(dao.get());
        final List<String> lines = (write ? new LedgerBindingApplier(store, ignored -> { })
                : LedgerBindingApplier.dryRun(store)).rollback(added);
        final Path report = dir.resolve(write ? "rollback.txt" : "rollback-dry-run.txt");
        LedgerFiles.writeLines(report, lines);
        final long failed = lines.stream().filter(line -> line.startsWith("FAILED")).count();
        if (!write) {
            out.println("DRY RUN: " + lines.stream().filter(line -> line.startsWith("WOULD REMOVE")).count()
                    + " bindings would be removed (see " + report + "); nothing written. Add --yes to roll back.");
            return 0;
        }
        out.println("Rollback: " + (lines.size() - failed) + " bindings handled, " + failed + " FAILED (see "
                + report + "). Now press Clear caches on the admin Settings page.");
        return failed == 0 ? 0 : 2;
    }

    // ------------------------------------------------------------------ helpers

    private LedgerData load(final Map<String, String> opts) throws IOException {
        return opts.containsKey("dump") ? LedgerData.fromDump(CliArgs.path(opts, "dump"))
                : LedgerData.live(dao.get());
    }

    private static LocalDate today(final Map<String, String> opts) {
        return opts.containsKey("today") ? LocalDate.parse(opts.get("today")) : LocalDate.now();
    }

    private static List<Approval> approvals(final Map<String, String> opts) throws IOException {
        return opts.containsKey("approved")
                ? LedgerPlanFiles.approvals(Files.readString(CliArgs.path(opts, "approved"))) : List.of();
    }

    private static LedgerBindingPlan readPlan(final Path file) throws IOException {
        return LedgerData.mapper().readValue(file.toFile(), LedgerBindingPlan.class);
    }

    private static List<Added> readRollback(final Path file) throws IOException {
        return LedgerData.mapper().readValue(file.toFile(), new TypeReference<List<Added>>() { });
    }

    private static void saveRollback(final Path file, final List<Added> added) {
        try {
            LedgerFiles.writeJson(file, added);
        } catch (final IOException ex) {
            // The record of a write must exist before the write: an apply that cannot keep it stops here.
            throw new UncheckedIOException(ex);
        }
    }

    private static String applied(final Outcome outcome) {
        return "Applied: " + outcome.added().size() + " rows bound, " + outcome.skipped().size() + " skipped, "
                + outcome.failures().size() + " failures";
    }

    /**
     * The verifier over the new statement code. The rows the plan left open and no approval covers are handed in
     * as "awaiting", so a rehearsal before the owner's review says exactly which failure is theirs to clear.
     */
    private static LedgerBindingVerifier verifier(final LedgerSnapshot before, final LedgerData now,
            final LocalDate today, final LedgerBindingPlan plan, final List<Step> steps) {
        final Set<String> awaiting = new TreeSet<>(plan.uncertain().stream().map(Uncertain::key).toList());
        steps.stream().filter(step -> step.kind() == Kind.APPROVED).forEach(step -> awaiting.remove(step.key()));
        return new LedgerBindingVerifier(before, now, today, LedgerStatementSource.statements(now), awaiting);
    }

    private static void writeVerify(final Path file, final LedgerBindingVerifier verifier) throws IOException {
        final List<String> lines = new ArrayList<>(verifier.lines());
        lines.add("");
        lines.add("Newly bound rows, by trip:");
        lines.addAll(verifier.details());
        LedgerFiles.writeLines(file, lines);
    }

    /**
     * @param awaitingIsFine a rehearsal before the owner's review may end with rows still waiting for approval;
     *                       a verify of the deployment may not: there, a trip-less row is a failure whoever owes
     *                       the answer
     */
    private int conclude(final String what, final boolean mechanics, final LedgerBindingVerifier verifier,
            final boolean awaitingIsFine) {
        if (mechanics && verifier.failed().isEmpty()) {
            out.println(what + " PASSED: all " + LedgerBindingVerifier.CHECKS + " checks");
            return 0;
        }
        if (mechanics && verifier.onlyAwaitingApproval()) {
            out.println(what + (awaitingIsFine ? " PASSED SO FAR" : " INCOMPLETE") + ": checks 1 to 6 passed; "
                    + "check 7 fails only on the rows still waiting for approval in uncertain.csv");
            return awaitingIsFine ? 0 : 2;
        }
        out.println(what + " FAILED: " + (mechanics ? "" : "the apply, re-apply or rollback misbehaved; ")
                + "failed checks " + verifier.failed());
        return 2;
    }
}
