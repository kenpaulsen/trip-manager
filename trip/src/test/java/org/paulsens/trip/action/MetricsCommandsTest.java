package org.paulsens.trip.action;

import static org.paulsens.trip.action.TenancyFixtures.orgWithAdmin;
import static org.paulsens.trip.util.TestPeople.savedPerson;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.content.MetricGraphType;
import org.paulsens.trip.metrics.MetricCatalog;
import org.paulsens.trip.metrics.MetricsStore;
import org.paulsens.trip.metrics.MetricsWriter;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

/**
 * The metrics dashboard's bean: site admins only, the hub card's link to the organization's dashboard, and what one
 * graph sends the page (its lines, its ceiling, its thresholds), with every viewer control client-side.
 */
public class MetricsCommandsTest {

    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final long T = NOW.getEpochSecond();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void onlyASiteAdminSeesMetrics() {
        final MetricsCommands admin = commands(TestCallers::siteAdminHoldingNothing, new InMemoryCacheClient());
        final Person person = savedPerson();
        final MetricsCommands plain = commands(() -> TestCallers.holdingNothing(person), new InMemoryCacheClient());
        final MetricsCommands visitor = commands(() -> Caller.of(null), new InMemoryCacheClient());
        assertTrue(admin.canView("any"));
        assertFalse(plain.canView("any"));
        assertFalse(visitor.canView("any"));
        assertNull(plain.graph(graphItem(Map.of(MetricGraphType.PROP_METRIC, "ddb.pool:max"))), "nothing is read");
        assertNull(admin.graph(null));
        assertNull(plain.hubUrl("any"));
    }

    @Test
    public void aGraphSendsItsLinesItsCeilingAndItsThresholds() throws Exception {
        final InMemoryCacheClient cache = new InMemoryCacheClient();
        poolMinute(cache, "one", T - 600, 40, 200);
        poolMinute(cache, "two", T - 600, 70, 200);
        final MetricsCommands admin = commands(TestCallers::siteAdminHoldingNothing, cache);
        final Map<String, String> values = new HashMap<>();
        values.put(MetricGraphType.PROP_METRIC, "ddb.pool:max");
        values.put(MetricGraphType.PROP_RANGE, "6h");
        values.put(MetricGraphType.PROP_RESOLUTION, "10m");
        values.put(MetricGraphType.PROP_WARN, "160");
        values.put(MetricGraphType.PROP_Y_MAX, "lots");
        values.put(MetricGraphType.PROP_DESCRIPTION, "  The pool  ");

        final MetricsCommands.GraphView view = admin.graph(graphItem(values));
        assertEquals(view.title(), MetricGraphType.fieldLabel(MetricCatalog.DDB_POOL,
                MetricCatalog.DDB_POOL.field("max").orElseThrow()), "no title: the metric names itself");
        assertEquals(view.description(), "The pool");
        assertEquals(view.width(), "4", "the full width by default");
        assertEquals(view.height(), "2");
        assertFalse(view.noData());
        final JsonNode data = JSON.readTree(view.json());
        assertEquals(data.path("bucketSeconds").asLong(), 600L);
        assertEquals(data.path("rangeSeconds").asLong(), Duration.ofHours(6).toSeconds());
        assertEquals(data.path("warnAt").asDouble(), 160.0);
        assertTrue(data.path("yMax").isMissingNode(), "a typed value that is no number is left out");
        assertFalse(data.path("perTask").asBoolean(), "combined by default");
        assertEquals(data.path("tasks").size(), 2, "and every server's own line, for the toggle");
        assertEquals(valueAt(data.path("combined"), T - 600), 70.0, "the busier server's peak");
        assertEquals(valueAt(data.path("limit"), T - 600), 200.0, "the pool's size as its own line");
        assertTrue(valueAtIsNull(data.path("combined"), T - 1200), "a bucket with no sample is a gap");
        assertEquals(admin.invalidNumbers(graphItem(values)), List.of("Axis maximum"));

        values.put(MetricGraphType.PROP_LIMIT, "no");
        values.put(MetricGraphType.PROP_LINES, "perTask");
        final JsonNode alone = JSON.readTree(admin.buildGraph(values, "Pool").json());
        assertTrue(alone.path("limit").isMissingNode());
        assertTrue(alone.path("perTask").asBoolean());
    }

    @Test
    public void anUnknownMetricDrawsAnEmptyGraph() {
        final MetricsCommands admin = commands(TestCallers::siteAdminHoldingNothing, new InMemoryCacheClient());
        final MetricsCommands.GraphView view = admin.buildGraph(Map.of(MetricGraphType.PROP_METRIC, "gone:max"), "");
        assertTrue(view.noData());
        assertFalse(view.json().contains("combined"), "no lines, only the view");
        assertTrue(admin.buildGraph(Map.of(), "Mine").title().equals("Mine"));
        assertTrue(admin.buildGraph(Map.of(), null).noData());
    }

    @Test
    public void aGraphsViewCarriesItsSizeItsNameAndWhereItsClickShowsIt() throws Exception {
        final MetricsCommands admin = commands(TestCallers::siteAdminHoldingNothing, new InMemoryCacheClient());
        final Map<String, String> values = new HashMap<>(Map.of(MetricGraphType.PROP_METRIC, "jvm.heap:used",
                MetricGraphType.PROP_WIDTH, "1", MetricGraphType.PROP_HEIGHT, "1", MetricGraphType.PROP_HEADLINE,
                "yes", MetricGraphType.PROP_CONTROLS, "none", MetricGraphType.PROP_TARGETS,
                " Focus, side-Panel ,,focus", MetricGraphType.PROP_NAME, " Tile 1! "));
        final MetricsCommands.GraphView tile = admin.buildGraph(values, "Heap");
        assertEquals(tile.width(), "1");
        assertEquals(tile.height(), "1");
        final JsonNode view = JSON.readTree(tile.json());
        assertEquals(view.path("width").asInt(), 1);
        assertEquals(view.path("controls").asText(), "none");
        assertTrue(view.path("headline").asBoolean());
        assertEquals(view.path("name").asText(), "tile1", "a name is lower case letters, digits and hyphens");
        assertEquals(JSON.convertValue(view.path("targets"), List.class), List.of("focus", "side-panel"),
                "cleaned, blanks and repeats dropped");
        assertEquals(view.path("metric").asText(), "jvm.heap:used", "what a target compares to mark the tile chosen");
        assertEquals(view.path("title").asText(), "Heap");
        assertEquals(MetricsCommands.targets(null), List.of());
        assertEquals(MetricsCommands.graphName(null), "");
        final JsonNode unknown = JSON.readTree(admin.buildGraph(Map.of(MetricGraphType.PROP_NAME, "focus"), "").json());
        assertEquals(unknown.path("name").asText(), "focus", "an unknown metric still keeps its view");
    }

    @Test
    public void choicesFallBackToTheirDefaults() {
        assertEquals(MetricsCommands.choice(Map.of(), MetricGraphType.PROP_RANGE), "7d");
        assertEquals(MetricsCommands.choice(Map.of(MetricGraphType.PROP_RANGE, "1y"), MetricGraphType.PROP_RANGE),
                "7d");
        assertEquals(MetricsCommands.range("24h"), Duration.ofHours(24));
        assertEquals(MetricsCommands.range("3d"), Duration.ofDays(3));
        assertEquals(MetricsCommands.range("6h"), Duration.ofHours(6));
        assertEquals(MetricsCommands.bucket("1m"), Duration.ofMinutes(1));
        assertEquals(MetricsCommands.bucket("5m"), Duration.ofMinutes(5));
        assertEquals(MetricsCommands.bucket("1h"), Duration.ofHours(1));
        assertNull(MetricsCommands.bucket("auto"));
        assertEquals(MetricsCommands.rollup("max"), MetricCatalog.Rollup.MAX);
        assertEquals(MetricsCommands.rollup("avg"), MetricCatalog.Rollup.AVG);
        assertEquals(MetricsCommands.rollup("min"), MetricCatalog.Rollup.MIN);
        assertNull(MetricsCommands.rollup("default"));
        assertNull(MetricsCommands.parse("Infinity"));
        assertEquals(MetricsCommands.parse(" 2.5 "), 2.5);
    }

    @Test
    public void theHubCardLinksToTheOrganizationsOwnDashboard() {
        final Organization org = orgWithAdmin(savedPerson());
        final String orgId = org.getId().getValue();
        final MetricsCommands admin = new MetricsCommands(TestCallers::siteAdminHoldingNothing, new MetricsStore());
        assertNull(admin.hubUrl(orgId), "no site yet: nothing to link to");
        final String slug = "metrics" + RandomData.genAlpha(10).toLowerCase();
        assertTrue(new OrgCommands(TestCallers::siteAdminHoldingNothing).saveOrgEdits(orgId, org.getName(), null,
                null, null, null, slug));
        assertEquals(admin.hubUrl(orgId), "https://" + slug + ".unitetrip.com/page.jsf?name=metrics");
        assertTrue(new SitePageCommands(TestCallers::siteAdminHoldingNothing).deletePage(orgId, "metrics"));
        assertNull(admin.hubUrl(orgId), "its dashboard deleted");
    }

    private static MetricsCommands commands(final Supplier<Caller> caller,
            final InMemoryCacheClient cache) {
        return new MetricsCommands(caller, new MetricsStore(() -> cache, Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static void poolMinute(final InMemoryCacheClient cache, final String task, final long at,
            final long max, final long limit) {
        cache.putHashField(CacheKeys.metricKey("ddb.pool", task), String.valueOf(MetricsWriter.minuteOfWeek(at)),
                MetricsWriter.record(at, Map.of("max", max, "limit", limit)));
        cache.addScoredEntries(CacheKeys.METRICS_TASKS, Map.of(task, (double) at));
    }

    private static ContentInstance graphItem(final Map<String, String> values) {
        return new ContentInstance("g-" + RandomData.genAlpha(8), "page:x", "", MetricGraphType.TYPE_ID, 1,
                new HashMap<>(values), null, 0, 0, null, "test");
    }

    private static double valueAt(final JsonNode line, final long at) {
        return value(line, at).asDouble();
    }

    private static boolean valueAtIsNull(final JsonNode line, final long at) {
        return value(line, at).isNull();
    }

    /** The value of {@code line} (start, step, values) in the bucket starting at {@code at} (epoch seconds). */
    private static JsonNode value(final JsonNode line, final long at) {
        final long index = (at * 1000 - line.path("start").asLong()) / line.path("step").asLong();
        if (index < 0 || index >= line.path("values").size()) {
            throw new AssertionError("no point at " + at);
        }
        return line.path("values").get((int) index);
    }
}
