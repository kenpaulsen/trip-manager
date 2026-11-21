package org.paulsens.trip.migrate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.ContentRecord;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.site.SiteUrls;

/**
 * The command line of the page-HTML cleaning evaluation and migration ({@link HtmlClean},
 * {@link HtmlCleanMigration}, medjugorje#158); the account's script ({@code ../medjugorje/scripts/html-clean.sh})
 * supplies the classpath, the profile and the region.
 *
 * <pre>
 * plan     --out DIR                  reads only: plan.json (every in-scope value, stored and cleaned, with its
 *                                     verdict, by trip and by page) and plan.txt (the summary a person reads)
 * approve  --out DIR --unit KEY --words "..."
 *                                     records the owner's yes to ONE unit of plan.json, in their words, in
 *                                     approvals.json; it is for that unit's texts as plan.json holds them
 * rehearse --out DIR                  reads only: does the whole apply in memory, on the rows as they are now,
 *                                     and says what it would write, every unit it would stop at and why, and
 *                                     whether the result verifies (rehearsal.txt)
 * apply    --out DIR --yes            writes each cleared unit's rows, in order, each only if unchanged since it
 *                                     was read; STOPS at the first unit that is not cleared. before/ holds each
 *                                     row as it was, applied.json what was written. Run it again to go on.
 * verify   --out DIR                  reads only: nothing stored would be changed by cleaning it
 * rollback --out DIR --yes            puts back each row applied.json names, if it still holds what apply wrote
 * </pre>
 * Exit status: 0 done, 1 usage or failure, 2 something needs the owner or was refused (a CHANGED unit, a unit not
 * cleared, a row saved by someone meanwhile, a failed check).
 *
 * <p>DIR holds production content, so it belongs in a directory only its operator can read, and never in a
 * repository; the wrapper script creates the directory that way. What clears a unit is in DIR too: plan.json and
 * visual/visual.json (the evaluation the owner's tools ran) and approvals.json.
 */
public final class HtmlCleanCli {
    static final String PLAN = "plan.json";
    static final String APPROVALS = "approvals.json";
    static final String APPLIED = "applied.json";
    static final String VISUAL = "visual/visual.json";

    private final ObjectMapper mapper;
    private final ObjectWriter files;
    private final HtmlClean.Store store;
    /** The rows as text, for everything but a test of the evaluation alone (which passes none). */
    private final HtmlCleanMigration.Rows rows;
    private final PrintStream out;

    HtmlCleanCli(final ObjectMapper mapper, final HtmlClean.Store store, final PrintStream out) {
        this(mapper, store, null, out);
    }

    HtmlCleanCli(final ObjectMapper mapper, final HtmlClean.Store store, final HtmlCleanMigration.Rows rows,
            final PrintStream out) {
        this.mapper = mapper;
        this.files = mapper.writerWithDefaultPrettyPrinter();
        this.store = store;
        this.rows = rows;
        this.out = out;
    }

    public static void main(final String[] args) {
        System.exit(new HtmlCleanCli(DAO.getInstance().getMapper(), new DaoStore(), new DaoRows(), System.out)
                .run(args));
    }

    int run(final String[] args) {
        if (args.length == 0) {
            out.println("usage: plan|approve|rehearse|apply|verify|rollback --out DIR [--unit KEY --words \"...\"]"
                    + " [--yes]");
            return 1;
        }
        final Map<String, String> opts = CliArgs.options(args);
        try {
            final Path dir = CliArgs.path(opts, "out");
            return switch (args[0]) {
                case "plan" -> plan(dir);
                case "approve" -> approve(dir, opts);
                case "rehearse" -> rehearse(dir);
                case "apply" -> apply(dir, opts);
                case "verify" -> verify();
                case "rollback" -> rollback(dir, opts);
                default -> unknown(args[0]);
            };
        } catch (final IOException | RuntimeException ex) {
            out.println("ERROR: " + ex.getMessage());
            return 1;
        }
    }

    private int unknown(final String command) {
        out.println("Unknown command: " + command);
        return 1;
    }

    /** The evaluation over the rows as text when there are any, so a plan and an apply read one and the same thing. */
    private HtmlClean.Plan planOver(final Map<String, Map<String, String>> raw) {
        final HtmlClean.Store source = raw == null ? store : new HtmlCleanMigration(mapper).storeOver(raw, store);
        return new HtmlClean(source).plan();
    }

    private int plan(final Path dir) throws IOException {
        final HtmlClean.Plan plan = planOver(rows == null ? null : HtmlCleanMigration.readAll(rows));
        Files.createDirectories(dir);
        files.writeValue(dir.resolve(PLAN).toFile(), plan);
        final List<String> summary = summary(plan);
        Files.write(dir.resolve("plan.txt"), summary);
        summary.forEach(out::println);
        out.println("Wrote " + dir.resolve(PLAN) + ".");
        return plan.count(HtmlClean.Verdict.CHANGED) == 0 ? 0 : 2;
    }

    static List<String> summary(final HtmlClean.Plan plan) {
        final List<String> lines = new ArrayList<>();
        lines.add("Units (a trip, or a page of no trip): " + plan.units().size()
                + "; IDENTICAL " + plan.count(HtmlClean.Verdict.IDENTICAL)
                + ", EQUIVALENT " + plan.count(HtmlClean.Verdict.EQUIVALENT)
                + ", CHANGED " + plan.count(HtmlClean.Verdict.CHANGED) + ".");
        for (final HtmlClean.Unit unit : plan.units()) {
            lines.add(unit.verdict() + "  " + unit.kind() + " " + unit.key() + "  " + unit.ends() + "  "
                    + unit.title() + "  (" + unit.values().size() + " value(s))");
            unit.values().stream().filter(value -> value.verdict() == HtmlClean.Verdict.CHANGED)
                    .forEach(value -> lines.add("    " + describe(value)));
        }
        return lines;
    }

    private static String describe(final HtmlClean.Value value) {
        final List<String> reasons = new ArrayList<>(value.drops());
        value.lostTags().stream().map(tag -> "lost <" + tag + ">").forEach(reasons::add);
        if (value.textDiffers()) {
            reasons.add("text differs");
        }
        return value.table() + " " + value.rowId() + " " + value.field() + " [" + value.version() + "]: "
                + String.join("; ", reasons);
    }

    // ------------------------------------------------------------------ what clears a unit

    private JsonNode evaluatedPlan(final Path dir) throws IOException {
        final Path file = dir.resolve(PLAN);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("No " + file + ": run plan --out " + dir + " first.");
        }
        return mapper.readTree(file.toFile());
    }

    /** What the evaluation in DIR said of each unit: the fingerprint it judged, and its pictures' verdict. */
    private Map<String, HtmlCleanMigration.Evaluated> evaluated(final Path dir) throws IOException {
        final Map<String, String> pictures = new HashMap<>();
        final Path visual = dir.resolve(VISUAL);
        if (Files.exists(visual)) {
            for (final JsonNode unit : mapper.readTree(visual.toFile())) {
                pictures.put(unit.path("key").asText(), unit.path("verdict").asText());
            }
        }
        final Map<String, HtmlCleanMigration.Evaluated> evaluated = new HashMap<>();
        for (final JsonNode unit : evaluatedPlan(dir).path("units")) {
            final String key = unit.path("key").asText();
            evaluated.put(key, new HtmlCleanMigration.Evaluated(HtmlCleanMigration.fingerprint(unit),
                    pictures.getOrDefault(key, "")));
        }
        return evaluated;
    }

    private List<HtmlCleanMigration.Approval> approvals(final Path dir) throws IOException {
        final List<HtmlCleanMigration.Approval> approvals = new ArrayList<>();
        final Path file = dir.resolve(APPROVALS);
        if (Files.exists(file)) {
            for (final JsonNode node : mapper.readTree(file.toFile())) {
                approvals.add(new HtmlCleanMigration.Approval(node.path("key").asText(), node.path("title").asText(),
                        node.path("fingerprint").asText(), node.path("words").asText(), node.path("date").asText()));
            }
        }
        return approvals;
    }

    /**
     * Records the owner's yes to one unit. It is taken from plan.json, the evaluation the owner was shown, and not
     * from the table as it is now: an approval is for the texts that were looked at.
     */
    private int approve(final Path dir, final Map<String, String> opts) throws IOException {
        final String key = opts.getOrDefault("unit", "");
        final String words = opts.getOrDefault("words", "");
        if (key.isBlank() || words.isBlank()) {
            throw new IllegalArgumentException("approve needs --unit KEY and --words \"what the owner said\".");
        }
        JsonNode found = null;
        for (final JsonNode unit : evaluatedPlan(dir).path("units")) {
            found = key.equals(unit.path("key").asText()) ? unit : found;
        }
        final String fingerprint = found == null ? "" : HtmlCleanMigration.fingerprint(found);
        if (fingerprint.isEmpty()) {
            throw new IllegalArgumentException(found == null ? "plan.json has no unit " + key + "."
                    : "Unit " + key + " has nothing to change, so there is nothing to approve.");
        }
        final List<HtmlCleanMigration.Approval> approvals = new ArrayList<>(approvals(dir));
        approvals.removeIf(approval -> approval.key().equals(key));
        approvals.add(new HtmlCleanMigration.Approval(key, found.path("title").asText(), fingerprint, words,
                LocalDate.now().toString()));
        files.writeValue(dir.resolve(APPROVALS).toFile(), approvals);
        out.println("Approved " + key + " (" + found.path("title").asText() + ") for the texts in " + PLAN + ".");
        return 0;
    }

    // ------------------------------------------------------------------ rehearse, apply, verify, rollback

    private HtmlCleanMigration.Rows requireRows() {
        if (rows == null) {
            throw new IllegalStateException("This command needs the stored rows.");
        }
        return rows;
    }

    /** The whole apply, in memory: nothing is written, and every unit that would stop is listed, not only the first. */
    private int rehearse(final Path dir) throws IOException {
        final HtmlCleanMigration migration = new HtmlCleanMigration(mapper);
        final Map<String, Map<String, String>> raw = HtmlCleanMigration.readAll(requireRows());
        final MemoryRows memory = new MemoryRows(raw);
        final HtmlCleanMigration.Outcome outcome = migration.apply(migration.work(planOver(raw), raw),
                evaluated(dir), approvals(dir), memory, false, row -> { }, applied -> { });
        final List<String> lines = new ArrayList<>();
        lines.add("REHEARSAL, nothing was written. Rows an apply would write: " + outcome.applied().size() + ".");
        outcome.applied().forEach(row -> lines.add("  write " + row.table() + " " + row.rowId() + " (unit "
                + row.unit() + ")"));
        lines.add("Of those, rows stored in another JSON spelling, which come back in this program's (same data): "
                + outcome.applied().stream().filter(migration::respelled).count() + ".");
        lines.add("Units an apply would STOP at (it stops at the first): " + outcome.held().size() + ".");
        outcome.held().forEach(held -> lines.add("  " + held));
        final List<String> left = HtmlCleanMigration.stillToClean(planOver(memory.raw));
        lines.add("After it, values still to clean (all in the units above): " + left.size() + ".");
        Files.write(dir.resolve("rehearsal.txt"), lines);
        lines.forEach(out::println);
        return outcome.held().isEmpty() && left.isEmpty() ? 0 : 2;
    }

    private int apply(final Path dir, final Map<String, String> opts) throws IOException {
        CliArgs.requireYes(opts);
        final HtmlCleanMigration migration = new HtmlCleanMigration(mapper);
        final Map<String, Map<String, String>> raw = HtmlCleanMigration.readAll(requireRows());
        final List<HtmlCleanMigration.RowChange> earlier = appliedSoFar(dir);
        Files.createDirectories(dir.resolve("before"));
        final HtmlCleanMigration.Outcome outcome = migration.apply(migration.work(planOver(raw), raw),
                evaluated(dir), approvals(dir), rows, true, row -> backUp(dir, row),
                applied -> recordApplied(dir, earlier, applied));
        recordApplied(dir, earlier, outcome.applied());
        outcome.refused().forEach(refused -> out.println("REFUSED " + refused));
        out.println("Applied " + outcome.applied().size() + " row(s); " + APPLIED + " lists every row written so far.");
        if (!outcome.stoppedAt().isEmpty()) {
            out.println("STOPPED at " + outcome.held().get(0));
            out.println("Nothing of that unit was written. Show the owner its difference; after their yes, record it"
                    + " with approve and run apply again.");
            return 2;
        }
        final int verified = verify();
        return outcome.refused().isEmpty() ? verified : 2;
    }

    /** A row as it was, on disk BEFORE it is written; a failure here stops the run with the row untouched. */
    private void backUp(final Path dir, final HtmlCleanMigration.RowChange row) {
        try {
            Files.writeString(dir.resolve("before").resolve(row.table() + "-"
                    + HtmlClean.sha256(row.rowId()).substring(0, 16) + "-" + System.nanoTime() + ".json"),
                    row.before());
        } catch (final IOException ex) {
            throw new UncheckedIOException("Cannot write the backup of " + row.table() + " " + row.rowId(), ex);
        }
    }

    private List<HtmlCleanMigration.RowChange> appliedSoFar(final Path dir) throws IOException {
        final List<HtmlCleanMigration.RowChange> applied = new ArrayList<>();
        final Path file = dir.resolve(APPLIED);
        if (Files.exists(file)) {
            for (final JsonNode node : mapper.readTree(file.toFile())) {
                applied.add(new HtmlCleanMigration.RowChange(node.path("unit").asText(), node.path("table").asText(),
                        node.path("rowId").asText(), node.path("before").asText(), node.path("after").asText()));
            }
        }
        return applied;
    }

    /** Every row written by this run and the runs before it, rewritten after each unit. */
    private void recordApplied(final Path dir, final List<HtmlCleanMigration.RowChange> earlier,
            final List<HtmlCleanMigration.RowChange> now) {
        final List<HtmlCleanMigration.RowChange> all = new ArrayList<>(earlier);
        all.addAll(now);
        try {
            files.writeValue(dir.resolve(APPLIED).toFile(), all);
        } catch (final IOException ex) {
            throw new UncheckedIOException("Cannot write " + APPLIED, ex);
        }
    }

    private int verify() {
        final List<String> left = HtmlCleanMigration.stillToClean(
                planOver(HtmlCleanMigration.readAll(requireRows())));
        left.forEach(line -> out.println("STILL TO CLEAN " + line));
        out.println(left.isEmpty() ? "Verified: cleaning anything stored returns it as it is."
                : left.size() + " stored value(s) would still be changed by cleaning.");
        return left.isEmpty() ? 0 : 2;
    }

    private int rollback(final Path dir, final Map<String, String> opts) throws IOException {
        CliArgs.requireYes(opts);
        final List<HtmlCleanMigration.RowChange> applied = appliedSoFar(dir);
        final List<String> refused = HtmlCleanMigration.rollback(applied, requireRows());
        refused.forEach(row -> out.println("REFUSED " + row));
        out.println("Restored " + (applied.size() - refused.size()) + " of " + applied.size() + " row(s).");
        if (refused.isEmpty()) {
            Files.move(dir.resolve(APPLIED), dir.resolve("applied-rolled-back-" + System.nanoTime() + ".json"));
        }
        return refused.isEmpty() ? 0 : 2;
    }

    /** The rows of a rehearsal: a copy that takes the writes, with the same "only if unchanged" rule. */
    static final class MemoryRows implements HtmlCleanMigration.Rows {
        private final Map<String, Map<String, String>> raw = new LinkedHashMap<>();

        MemoryRows(final Map<String, Map<String, String>> from) {
            from.forEach((table, tableRows) -> raw.put(table, new LinkedHashMap<>(tableRows)));
        }

        @Override
        public Map<String, String> read(final String table) {
            return raw.getOrDefault(table, Map.of());
        }

        @Override
        public boolean replace(final String table, final String rowId, final String expected,
                final String replacement) {
            return raw.computeIfAbsent(table, key -> new LinkedHashMap<>()).replace(rowId, expected, replacement);
        }
    }

    /** The live rows: uncached, consistent reads and conditional writes ({@code dynamo.RawRows}). */
    static final class DaoRows implements HtmlCleanMigration.Rows {
        @Override
        public Map<String, String> read(final String table) {
            return DAO.getInstance().rawRows(table);
        }

        @Override
        public boolean replace(final String table, final String rowId, final String expected,
                final String replacement) {
            return DAO.getInstance().replaceRawRow(table, rowId, expected, replacement);
        }
    }

    /** The live store: uncached reads through the DAO, and each organization's own site address. */
    static final class DaoStore implements HtmlClean.Store {
        private final ConfigCommands config = new ConfigCommands();

        @Override
        public List<Trip> trips() {
            return DAO.getInstance().getRecentTrips(0, Cached.NO);
        }

        @Override
        public List<ContentRecord> contentRecords() {
            return DAO.getInstance().getAllContentRecords(Cached.NO);
        }

        @Override
        public List<TodoItem> todos(final String tripId) {
            return DAO.getInstance().getTodoItems(tripId, Cached.NO);
        }

        @Override
        public Optional<ContentTemplate> template(final String templateId, final int version) {
            return DAO.getInstance().getTemplate(templateId, version, Cached.NO)
                    .or(() -> DAO.getInstance().getLatestTemplate(templateId, Cached.NO));
        }

        @Override
        public String siteBase(final String orgId) {
            final String base = SiteUrls.baseUrlForOrgId(orgId, KnownSettings.REG_MAIL_BASE_URL, config);
            return base == null ? "" : base.replaceAll("/+$", "");
        }
    }
}
