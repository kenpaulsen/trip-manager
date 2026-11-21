package org.paulsens.trip.migrate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.paulsens.trip.model.ContentRecord;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.Trip;

/**
 * The migration that rewrites stored hand-authored page HTML to its cleaned form (medjugorje#158), one unit at a
 * time, under the owner's rule: every trip, and every page of no trip, is judged on its own, and one with ANY
 * detected difference, in its text or in how it looks, is not changed until the owner has seen that difference
 * and approved it. There is no blanket approval, and an approval is for one exact pair of texts.
 *
 * <p>How the rule is held. Each unit that has anything to rewrite gets a FINGERPRINT: a hash over every value
 * that would change, stored text and cleaned text both ({@link #fingerprint}). A unit is written only when
 * <ul>
 *   <li>the owner approved exactly that fingerprint ({@link Approval}), or</li>
 *   <li>its text is the same content respelled ({@link HtmlClean.Verdict#EQUIVALENT}), AND the evaluation the
 *       owner's tools ran still describes it (same fingerprint), AND that evaluation's picture comparison found
 *       the unit the SAME.</li>
 * </ul>
 * Anything else stops the apply at that unit ({@link #clearance}). An edit to the stored text, or a change to the
 * cleaner, moves the fingerprint, so an old approval or an old picture comparison stops counting by itself.
 *
 * <p>How a row is written. As text ({@code dynamo.RawRows}): the row's stored JSON is read as a tree, only the
 * fields being cleaned are replaced in it, and it is written back only while the row still holds what was read
 * ({@link Rows#replace}). Nothing else in the row changes in what it SAYS. How it is spelled can: a row that a
 * script wrote with its own JSON habits (production holds a few with {@code \u00f1} where the app writes the
 * letter) comes back in this program's spelling, the same data ({@link #respelled} counts them, and a rollback
 * restores the original bytes). A row that cannot be read, or whose field no longer holds the text that was
 * judged, is left alone and stops its unit: nothing is ever written that was not looked at.
 */
public final class HtmlCleanMigration {

    static final String TRIPS = "trips";
    static final String CONTENT = "content";
    static final String TODO_ITEMS = "todo_items";
    static final List<String> TABLES = List.of(TRIPS, CONTENT, TODO_ITEMS);

    /** What a picture comparison says of a unit that looks the same before and after. */
    static final String PICTURES_SAME = "SAME";

    private final ObjectMapper mapper;

    public HtmlCleanMigration(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** The stored rows as text; the DAO in production, maps in tests and in a rehearsal. */
    public interface Rows {
        /** Every row's stored JSON by row id (a to-do's is {@code tripId/dataId}). */
        Map<String, String> read(String table);

        /** Replaces a row only if it still holds {@code expected}; false, writing nothing, when it does not. */
        boolean replace(String table, String rowId, String expected, String replacement);
    }

    /** One row, as stored and as it will be: the cleaned fields replaced and nothing else touched. */
    public record RowChange(String unit, String table, String rowId, String before, String after) {
    }

    /**
     * What there is to do for one unit.
     *
     * @param fingerprint names the exact texts this work would replace and what with; "" when there is none
     * @param problems    why a row of this unit cannot be written as judged; empty when all can
     */
    public record Work(String kind, String key, String title, HtmlClean.Verdict verdict, String fingerprint,
            List<RowChange> rows, List<String> problems) {
    }

    /** The owner's yes to one unit's difference, in their words, for one fingerprint. */
    public record Approval(String key, String title, String fingerprint, String words, String date) {
    }

    /**
     * What the evaluation the owner looked at said of a unit: the fingerprint it judged, and its picture
     * comparison's verdict ("" when the pictures were not compared).
     */
    public record Evaluated(String fingerprint, String pictures) {
    }

    /** Whether a unit may be written, and the reason either way, for the log and for whoever is asked. */
    public record Clearance(boolean cleared, String why) {
    }

    /**
     * How a run went.
     *
     * @param applied   every row written, in order, with what it held before
     * @param refused   rows that changed between being read and being written; left as they are
     * @param held      units not written, each with its reason: the one an apply stopped at, or all of them in
     *                  a rehearsal
     * @param stoppedAt the unit an apply stopped at; "" when it reached the end
     */
    public record Outcome(List<RowChange> applied, List<String> refused, List<String> held, String stoppedAt) {
    }

    /** The store an evaluation reads, over the SAME rows a migration will write: one read, one judgment. */
    public HtmlClean.Store storeOver(final Map<String, Map<String, String>> raw, final HtmlClean.Store lookups) {
        return new RawStore(raw, lookups);
    }

    /** All three tables, read once. */
    public static Map<String, Map<String, String>> readAll(final Rows rows) {
        final Map<String, Map<String, String>> raw = new LinkedHashMap<>();
        TABLES.forEach(table -> raw.put(table, new LinkedHashMap<>(rows.read(table))));
        return raw;
    }

    /**
     * The fingerprint of a unit: a hash over every value cleaning would change, each as its place and the hashes
     * of its stored and its cleaned text. "" for a unit with nothing to change.
     */
    public static String fingerprint(final HtmlClean.Unit unit) {
        final TreeSet<String> lines = new TreeSet<>();
        for (final HtmlClean.Value value : unit.values()) {
            if (value.verdict() != HtmlClean.Verdict.IDENTICAL) {
                lines.add(line(value.table(), value.rowId(), value.version(), value.field(), value.beforeSha(),
                        value.afterSha()));
            }
        }
        return lines.isEmpty() ? "" : HtmlClean.sha256(String.join("\n", lines));
    }

    /** The same fingerprint, from a unit as an evaluation wrote it to plan.json. */
    public static String fingerprint(final JsonNode unit) {
        final TreeSet<String> lines = new TreeSet<>();
        for (final JsonNode value : unit.path("values")) {
            if (!HtmlClean.Verdict.IDENTICAL.name().equals(value.path("verdict").asText())) {
                lines.add(line(value.path("table").asText(), value.path("rowId").asText(),
                        value.path("version").asText(), value.path("field").asText(),
                        value.path("beforeSha").asText(), value.path("afterSha").asText()));
            }
        }
        return lines.isEmpty() ? "" : HtmlClean.sha256(String.join("\n", lines));
    }

    private static String line(final String table, final String rowId, final String version, final String field,
            final String beforeSha, final String afterSha) {
        return String.join("|", table, rowId, version, field, beforeSha, afterSha);
    }

    /** What there is to do, unit by unit in the plan's order; a unit with nothing to change is left out. */
    public List<Work> work(final HtmlClean.Plan plan, final Map<String, Map<String, String>> raw) {
        final List<Work> works = new ArrayList<>();
        for (final HtmlClean.Unit unit : plan.units()) {
            final String fingerprint = fingerprint(unit);
            if (!fingerprint.isEmpty()) {
                works.add(workOf(unit, fingerprint, raw));
            }
        }
        return works;
    }

    private Work workOf(final HtmlClean.Unit unit, final String fingerprint,
            final Map<String, Map<String, String>> raw) {
        final Map<String, List<HtmlClean.Value>> byRow = new LinkedHashMap<>();
        for (final HtmlClean.Value value : unit.values()) {
            if (value.verdict() != HtmlClean.Verdict.IDENTICAL) {
                byRow.computeIfAbsent(value.table() + "\n" + value.rowId(), key -> new ArrayList<>()).add(value);
            }
        }
        final List<RowChange> rows = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        for (final List<HtmlClean.Value> values : byRow.values()) {
            final HtmlClean.Value first = values.get(0);
            final String stored = raw.getOrDefault(first.table(), Map.of()).get(first.rowId());
            final String rewritten = stored == null ? null : rewritten(stored, values, problems);
            if (stored == null) {
                problems.add(first.table() + " " + first.rowId() + ": the row is gone");
            } else if (rewritten != null) {
                rows.add(new RowChange(unit.key(), first.table(), first.rowId(), stored, rewritten));
            }
        }
        return new Work(unit.kind(), unit.key(), unit.title(), unit.verdict(), fingerprint, List.copyOf(rows),
                List.copyOf(problems));
    }

    /** The row's JSON with each judged value replaced by its cleaned text; null (and a problem) when it cannot be. */
    private String rewritten(final String stored, final List<HtmlClean.Value> values, final List<String> problems) {
        final String where = values.get(0).table() + " " + values.get(0).rowId();
        final JsonNode tree;
        try {
            tree = mapper.readTree(stored);
        } catch (final JsonProcessingException ex) {
            problems.add(where + ": its stored JSON cannot be read (" + ex.getOriginalMessage() + ")");
            return null;
        }
        for (final HtmlClean.Value value : values) {
            final ObjectNode holder = holderOf(tree, value);
            final String name = fieldName(value);
            if (holder == null || !holder.path(name).isTextual()
                    || !holder.path(name).asText().equals(value.before())) {
                problems.add(where + " " + value.field() + " [" + value.version() + "]: the row no longer holds"
                        + " the text that was judged");
                return null;
            }
            holder.put(name, value.after());
        }
        try {
            return mapper.writeValueAsString(tree);
        } catch (final JsonProcessingException ex) {
            problems.add(where + ": cannot be written (" + ex.getOriginalMessage() + ")");
            return null;
        }
    }

    /**
     * Whether a row was stored in another JSON spelling than this program's, so that its rewrite respells fields
     * it did not clean (to the same data). For the run's account of itself.
     */
    public boolean respelled(final RowChange row) {
        try {
            return !mapper.writeValueAsString(mapper.readTree(row.before())).equals(row.before());
        } catch (final JsonProcessingException ex) {
            return false;
        }
    }

    /** The JSON object that holds a value's field: the row itself, a content version, or that version's values. */
    private static ObjectNode holderOf(final JsonNode row, final HtmlClean.Value value) {
        JsonNode holder = row;
        if (CONTENT.equals(value.table())) {
            holder = versionOf(row, value.version());
            if (value.field().startsWith("value ")) {
                holder = holder.path("values");
            }
        }
        return holder instanceof ObjectNode object ? object : null;
    }

    /**
     * A content row's version by the evaluation's name for it: "current", or "previous N", N counting every
     * retained version with the current one first ({@code ContentRecord.getAllVersions}).
     */
    private static JsonNode versionOf(final JsonNode row, final String version) {
        if ("current".equals(version)) {
            return row.path("current");
        }
        final int all = Integer.parseInt(version.substring(version.indexOf(' ') + 1));
        return row.path("previous").path(row.hasNonNull("current") ? all - 1 : all);
    }

    private static String fieldName(final HtmlClean.Value value) {
        return value.field().startsWith("value ") ? value.field().substring("value ".length()) : value.field();
    }

    /**
     * Whether the owner's rule lets a unit be written now (the class comment has the rule).
     *
     * @param evaluated what the evaluation in hand said of this unit, or null when it did not see it
     */
    public static Clearance clearance(final Work work, final Evaluated evaluated, final List<Approval> approvals) {
        if (!work.problems().isEmpty()) {
            return new Clearance(false, "cannot be written as judged: " + String.join("; ", work.problems()));
        }
        for (final Approval approval : approvals) {
            if (approval.key().equals(work.key()) && approval.fingerprint().equals(work.fingerprint())) {
                return new Clearance(true, "approved by the owner on " + approval.date());
            }
        }
        final boolean approvedBefore = approvals.stream().anyMatch(approval -> approval.key().equals(work.key()));
        final String lapsed = approvedBefore ? " (an earlier approval was for other text, and no longer counts)" : "";
        if (work.verdict() == HtmlClean.Verdict.CHANGED) {
            return new Clearance(false, "cleaning changes its content, and the owner has not approved this" + lapsed);
        }
        if (evaluated == null || !evaluated.fingerprint().equals(work.fingerprint())) {
            return new Clearance(false, "its text is not what the evaluation judged: evaluate it again" + lapsed);
        }
        if (!PICTURES_SAME.equals(evaluated.pictures())) {
            return new Clearance(false, evaluated.pictures().isEmpty()
                    ? "its pictures have not been compared" + lapsed
                    : "its pictures compared as " + evaluated.pictures() + ", and the owner has not approved this"
                            + lapsed);
        }
        return new Clearance(true, "same content respelled, and its pictures compared the same");
    }

    /**
     * Writes each cleared unit's rows, in order.
     *
     * @param stopAtFirst the real apply's rule: stop at the first unit that is not cleared, having written
     *                    nothing of it. A rehearsal passes false, to list every unit that would stop.
     * @param beforeWrite called with each row just before it is written, so a backup exists before the write
     * @param afterUnit   called after each unit with what has been applied so far, so a run cut short still
     *                    leaves an account of itself
     */
    public Outcome apply(final List<Work> works, final Map<String, Evaluated> evaluated,
            final List<Approval> approvals, final Rows rows, final boolean stopAtFirst,
            final java.util.function.Consumer<RowChange> beforeWrite,
            final java.util.function.Consumer<List<RowChange>> afterUnit) {
        final List<RowChange> applied = new ArrayList<>();
        final List<String> refused = new ArrayList<>();
        final List<String> held = new ArrayList<>();
        for (final Work work : works) {
            final Clearance clearance = clearance(work, evaluated.get(work.key()), approvals);
            if (!clearance.cleared()) {
                held.add(work.kind() + " " + work.key() + " (" + work.title() + "): " + clearance.why());
                if (stopAtFirst) {
                    return new Outcome(List.copyOf(applied), List.copyOf(refused), List.copyOf(held), work.key());
                }
                continue;
            }
            for (final RowChange row : work.rows()) {
                beforeWrite.accept(row);
                if (rows.replace(row.table(), row.rowId(), row.before(), row.after())) {
                    applied.add(row);
                } else {
                    refused.add(row.table() + " " + row.rowId() + " (" + work.title() + "): saved by someone since"
                            + " it was read");
                }
            }
            afterUnit.accept(List.copyOf(applied));
        }
        return new Outcome(List.copyOf(applied), List.copyOf(refused), List.copyOf(held), "");
    }

    /**
     * What is still to clean: every stored value the cleaner would change, by unit. Empty when the migration is
     * complete, which is what verify asks: cleaning anything stored now returns it as it is.
     */
    public static List<String> stillToClean(final HtmlClean.Plan plan) {
        final List<String> lines = new ArrayList<>();
        for (final HtmlClean.Unit unit : plan.units()) {
            for (final HtmlClean.Value value : unit.values()) {
                if (value.verdict() != HtmlClean.Verdict.IDENTICAL) {
                    lines.add(unit.kind() + " " + unit.key() + " (" + unit.title() + "): " + value.table() + " "
                            + value.rowId() + " " + value.field() + " [" + value.version() + "]");
                }
            }
        }
        return lines;
    }

    /**
     * Puts back each applied row, last first, only where the row still holds what the apply wrote.
     *
     * @return the rows that were NOT put back, because someone saved them since
     */
    public static List<String> rollback(final List<RowChange> applied, final Rows rows) {
        final List<String> refused = new ArrayList<>();
        for (int i = applied.size() - 1; i >= 0; i--) {
            final RowChange row = applied.get(i);
            if (!rows.replace(row.table(), row.rowId(), row.after(), row.before())) {
                refused.add(row.table() + " " + row.rowId() + ": saved by someone since the apply");
            }
        }
        return refused;
    }

    /** {@link HtmlClean.Store} over rows held as text; templates and site addresses come from {@code lookups}. */
    private final class RawStore implements HtmlClean.Store {
        private final Map<String, Map<String, String>> raw;
        private final HtmlClean.Store lookups;

        RawStore(final Map<String, Map<String, String>> raw, final HtmlClean.Store lookups) {
            this.raw = raw;
            this.lookups = lookups;
        }

        @Override
        public List<Trip> trips() {
            return parsed(TRIPS, "", Trip.class);
        }

        @Override
        public List<ContentRecord> contentRecords() {
            return parsed(CONTENT, "", ContentRecord.class);
        }

        @Override
        public List<TodoItem> todos(final String tripId) {
            return parsed(TODO_ITEMS, tripId + "/", TodoItem.class);
        }

        @Override
        public Optional<ContentTemplate> template(final String templateId, final int version) {
            return lookups.template(templateId, version);
        }

        @Override
        public String siteBase(final String orgId) {
            return lookups.siteBase(orgId);
        }

        private <T> List<T> parsed(final String table, final String idPrefix, final Class<T> type) {
            final List<T> rows = new ArrayList<>();
            for (final Map.Entry<String, String> row : raw.getOrDefault(table, Map.of()).entrySet()) {
                if (row.getKey().startsWith(idPrefix)) {
                    rows.add(parse(table, row.getKey(), row.getValue(), type));
                }
            }
            return rows;
        }

        private <T> T parse(final String table, final String rowId, final String json, final Class<T> type) {
            try {
                return mapper.readValue(json, type);
            } catch (final JsonProcessingException ex) {
                throw new IllegalStateException(table + " " + rowId + " cannot be read as a "
                        + type.getSimpleName() + ": " + ex.getOriginalMessage(), ex);
            }
        }
    }
}
