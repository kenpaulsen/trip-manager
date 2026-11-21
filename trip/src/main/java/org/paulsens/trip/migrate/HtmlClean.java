package org.paulsens.trip.migrate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.paulsens.trip.content.HtmlShape;
import org.paulsens.trip.content.PageHtml;
import org.paulsens.trip.content.ProgrammaticTypes;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentRecord;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.Trip;

/**
 * What cleaning every stored piece of hand-authored page HTML would change (medjugorje#158): a read-only
 * evaluation, one verdict per trip and per page. It exists because of two owner rules. Before an output is
 * cleaned, every production value that reaches it is scanned, and anything that would look or behave differently
 * is asked about. And during the migration that rewrites the stored rows, each trip is judged on its own: one
 * with any difference is not changed until the owner has seen that difference and approved it.
 *
 * <p>A UNIT is what a person would look at to judge a difference: one trip (its description, every section on its
 * page, its to-do details), or one page that belongs to no trip (an organization's home page, a site page). A
 * section inside a container belongs to whatever its outermost container belongs to.
 *
 * <p>Each value gets one of three verdicts. IDENTICAL: cleaning returns the stored text. EQUIVALENT: the text
 * differs only in spelling a browser reads alike ({@link HtmlShape}), and the cleaner reported no drop. CHANGED:
 * anything else. EQUIVALENT is a claim about content, not about pixels; the screenshot comparison in the private
 * repo is what settles how a unit looks, and it reads this plan to know what to compare.
 */
public final class HtmlClean {

    /** A trip's page key, and so the section key of every top-level section on it. */
    private static final Pattern TRIP_PAGE = Pattern.compile(
            "page:trip:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}):.*");
    /** Deeper than any real nesting; a bound so two rows naming each other cannot loop forever. */
    private static final int MAX_DEPTH = 20;

    private final Store store;

    public HtmlClean(final Store store) {
        this.store = store;
    }

    /** What the evaluation reads; the DAO in production ({@code HtmlCleanCli}), lists in tests. */
    public interface Store {
        List<Trip> trips();

        List<ContentRecord> contentRecords();

        List<TodoItem> todos(String tripId);

        /** The template version an instance's values were saved against, else the template's latest. */
        Optional<ContentTemplate> template(String templateId, int version);

        /** The address of the site that shows this organization's pages, without a trailing slash; "" if unknown. */
        String siteBase(String orgId);
    }

    public enum Verdict { IDENTICAL, EQUIVALENT, CHANGED }

    /**
     * One stored value and what cleaning does to it.
     *
     * @param table    the table its row is in
     * @param rowId    its row
     * @param version  "current", or "previous N" for a content row's undo history, which no page shows
     * @param field    "description", "moreDetails", "title", or "value NAME"
     * @param drops    what the cleaner says it removed
     * @param lostTags tags the second reading finds in the stored text and not in the cleaned, and one entry
     *                 standing for the comments when those differ
     */
    public record Value(String table, String rowId, String version, String field, Verdict verdict,
            List<String> drops, List<String> lostTags, boolean textDiffers, String beforeSha, String afterSha,
            String before, String after) {

        /** Whether a page shows this value today; undo history is stored but never rendered. */
        public boolean shown() {
            return "current".equals(version);
        }
    }

    /**
     * Everything one person would judge together.
     *
     * @param kind "trip" or "page"
     * @param key  the trip's id, or the page's key
     * @param url  where the unit is shown to a visitor, when that is known; "" otherwise
     */
    public record Unit(String kind, String key, String title, String orgId, String ends, String url,
            List<Value> values) {

        public Verdict verdict() {
            return values.stream().map(Value::verdict).max(Comparator.naturalOrder()).orElse(Verdict.IDENTICAL);
        }
    }

    public record Plan(List<Unit> units) {

        public long count(final Verdict verdict) {
            return units.stream().filter(unit -> unit.verdict() == verdict).count();
        }
    }

    public Plan plan() {
        final Map<String, List<Value>> byUnit = new LinkedHashMap<>();
        final Map<String, Trip> trips = new LinkedHashMap<>();
        for (final Trip trip : store.trips()) {
            trips.put(trip.getId(), trip);
            final List<Value> values = byUnit.computeIfAbsent(trip.getId(), key -> new ArrayList<>());
            addValue(values, "trips", trip.getId(), "current", "description", trip.getDescription());
            store.todos(trip.getId()).forEach(todo -> addValue(values, "todo_items",
                    trip.getId() + "/" + todo.getDataId().getValue(), "current", "moreDetails", todo.getMoreDetails()));
        }
        final List<ContentRecord> records = store.contentRecords();
        final Map<String, String> sections = sectionsById(records);
        for (final ContentRecord record : records) {
            final String unit = unitOf(record.getId(), sections);
            addVersions(byUnit.computeIfAbsent(unit, key -> new ArrayList<>()), record);
        }
        final List<Unit> units = new ArrayList<>();
        byUnit.forEach((key, values) -> addUnit(units, key, values, trips.get(key)));
        units.sort(Comparator.comparing(Unit::kind).reversed().thenComparing(Unit::ends).thenComparing(Unit::title));
        return new Plan(List.copyOf(units));
    }

    private void addUnit(final List<Unit> units, final String key, final List<Value> values, final Trip trip) {
        if (values.isEmpty()) {
            return;
        }
        if (trip == null) {
            units.add(new Unit("page", key, key, orgOfPage(key), "", pageUrl(key), List.copyOf(values)));
            return;
        }
        final String base = store.siteBase(trip.getOrgId());
        final String url = base.isEmpty() ? "" : base + "/trip/tripPage.jsf?trip=" + trip.getId();
        final String ends = trip.getEndDate() == null ? "" : trip.getEndDate().toLocalDate().toString();
        units.add(new Unit("trip", key, String.valueOf(trip.getTitle()), String.valueOf(trip.getOrgId()), ends, url,
                List.copyOf(values)));
    }

    private static String orgOfPage(final String pageKey) {
        final String[] parts = pageKey.split(":");
        return parts.length >= 3 && "org".equals(parts[1]) ? parts[2] : "";
    }

    /**
     * Where an organization's own page ({@code page:org:{orgId}:{name}}) is shown: its site's front page for
     * {@code home}, else {@code /page.jsf?name=}. Blank for any other key (the shared hosts' pages), which the
     * screenshot comparison then judges value by value instead of as a page.
     */
    private String pageUrl(final String pageKey) {
        final String[] parts = pageKey.split(":");
        final String base = parts.length == 4 && "org".equals(parts[1]) ? store.siteBase(parts[2]) : "";
        if (base.isEmpty()) {
            return "";
        }
        return "home".equals(parts[3]) ? base + "/" : base + "/page.jsf?name=" + parts[3];
    }

    private static Map<String, String> sectionsById(final List<ContentRecord> records) {
        final Map<String, String> sections = new HashMap<>();
        for (final ContentRecord record : records) {
            if (record.getCurrent() != null) {
                sections.put(record.getId(), record.getCurrent().getSection());
            }
        }
        return sections;
    }

    /**
     * The unit a content row belongs to: its section, followed up through any containers (a child's section is
     * its container's id) to a page key, and from a trip's page key to the trip.
     */
    static String unitOf(final String recordId, final Map<String, String> sections) {
        String section = sections.get(recordId);
        int depth = 0;
        while (section != null && sections.containsKey(section) && depth++ < MAX_DEPTH) {
            section = sections.get(section);
        }
        // No row, or rows that name each other in a ring: it belongs to no page, and is reported as its own unit.
        if (section == null || sections.containsKey(section)) {
            return "orphan:" + recordId;
        }
        final Matcher trip = TRIP_PAGE.matcher(section);
        return trip.matches() ? trip.group(1) : section;
    }

    private void addVersions(final List<Value> values, final ContentRecord record) {
        final List<ContentInstance> versions = record.getAllVersions();
        for (int i = 0; i < versions.size(); i++) {
            final ContentInstance instance = versions.get(i);
            final String version = i == 0 && instance == record.getCurrent() ? "current" : "previous " + i;
            // A title with no markup is escaped where it is written, so it is text, not HTML to clean.
            if (instance.getTitle() != null && instance.getTitle().indexOf('<') >= 0) {
                addValue(values, "content", record.getId(), version, "title", instance.getTitle());
            }
            for (final String name : richTextNames(instance)) {
                addValue(values, "content", record.getId(), version, "value " + name, instance.getValues().get(name));
            }
        }
    }

    private Set<String> richTextNames(final ContentInstance instance) {
        final Set<String> names = new TreeSet<>();
        if (instance.getTemplateId() == null || instance.getValues() == null) {
            return names;
        }
        final Optional<ContentTemplate> template = store.template(instance.getTemplateId(),
                instance.getTemplateVersion());
        for (final Placeholder placeholder : ProgrammaticTypes.placeholdersOf(template.orElse(null))) {
            if (placeholder.getType() == Placeholder.Type.RICH_TEXT) {
                names.add(placeholder.getName());
            }
        }
        return names;
    }

    private static void addValue(final List<Value> values, final String table, final String rowId,
            final String version, final String field, final String stored) {
        if (stored == null || stored.isBlank()) {
            return;
        }
        final PageHtml.Cleaned cleaned = PageHtml.cleanNoting(stored);
        final HtmlShape before = HtmlShape.of(stored);
        final HtmlShape after = HtmlShape.of(cleaned.body());
        final List<String> lostTags = new ArrayList<>(before.tagsMissingFrom(after));
        if (!before.comments().equals(after.comments())) {
            lostTags.add("!-- a comment --");
        }
        final boolean textDiffers = !before.text().equals(after.text());
        final List<String> drops = cleaned.drops().stream().map(PageHtml.Drop::describe).distinct().toList();
        values.add(new Value(table, rowId, version, field,
                verdict(stored, cleaned, lostTags, textDiffers, before, after), drops, lostTags, textDiffers,
                sha256(stored), sha256(cleaned.body()), stored, cleaned.body()));
    }

    private static Verdict verdict(final String stored, final PageHtml.Cleaned cleaned, final List<String> lostTags,
            final boolean textDiffers, final HtmlShape before, final HtmlShape after) {
        if (cleaned.body().equals(stored)) {
            return Verdict.IDENTICAL;
        }
        final boolean same = !cleaned.dropped() && lostTags.isEmpty() && !textDiffers && before.equals(after);
        return same ? Verdict.EQUIVALENT : Verdict.CHANGED;
    }

    /** The fingerprint an approval is tied to: it stops counting the moment the stored or the cleaned text moves. */
    static String sha256(final String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Every Java runtime has SHA-256", ex);
        }
    }
}
