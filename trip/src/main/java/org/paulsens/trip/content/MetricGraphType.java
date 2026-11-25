package org.paulsens.trip.content;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.MetricsCommands;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.metrics.MetricCatalog;
import org.paulsens.trip.metrics.MetricCatalog.Field;
import org.paulsens.trip.metrics.MetricCatalog.Metric;
import org.paulsens.trip.metrics.MetricsStore;
import org.paulsens.trip.model.Placeholder;

/**
 * One graph of the app's own metrics ({@code docs/metrics.md}), as a programmatic content type: the metrics
 * dashboard is an Editable Page built from these, one per graph, so a site admin adds, removes and tunes graphs in
 * the ordinary edit mode. ONE template composes every layout (owner, 2026-11-24): a graph has a size on the
 * "graph-grid" container's four-column grid, a one-row graph is a sparkline tile, a headline number and its own
 * controls are options, and a graph with a name can be TARGETED by others (a tile's click shows its metric there),
 * which is how an overview of tiles drives one large focus graph. Its other options follow what monitoring tools
 * offer a panel (which number, which servers, how far back, how finely, how to fold a bucket, the axis, threshold
 * lines, the number's own ceiling).
 *
 * <p>Site admins only: the fragment draws nothing for anyone else ({@code metrics.canView}), before any read, and
 * the Add dialog offers the type only to a site admin ({@link #offeredTo}).
 */
public final class MetricGraphType implements ProgrammaticContentTemplate {

    public static final String TYPE_ID = "metric-graph";

    /** Which number: {@code <metric>:<field>}, a catalog field ({@link #metricChoices}). */
    public static final String PROP_METRIC = "metric";
    public static final String PROP_DESCRIPTION = "description";
    /** One combined line, or one line per server. */
    public static final String PROP_LINES = "lines";
    public static final String PROP_RANGE = "range";
    public static final String PROP_RESOLUTION = "resolution";
    public static final String PROP_ROLLUP = "rollup";
    public static final String PROP_STYLE = "style";
    public static final String PROP_Y_MIN = "yMin";
    public static final String PROP_Y_MAX = "yMax";
    public static final String PROP_WARN = "warnAt";
    public static final String PROP_ALERT = "alertAt";
    public static final String PROP_LIMIT = "showLimit";
    /** How many of the grid's four columns the graph spans. */
    public static final String PROP_WIDTH = "width";
    /** How many grid rows tall the graph is; one row draws a sparkline, without axes. */
    public static final String PROP_HEIGHT = "height";
    /** An optional name other graphs target ({@link #PROP_TARGETS}), such as {@code focus}. */
    public static final String PROP_NAME = "graphName";
    /** The names of the graphs a click on this one shows its metric in: 0 or more, separated by commas. */
    public static final String PROP_TARGETS = "showsIn";
    /** What a viewer can change on the graph itself: range, servers and zoom; the range only; or nothing. */
    public static final String PROP_CONTROLS = "controls";
    /** Whether the latest value shows large, with the peak in range under it. */
    public static final String PROP_HEADLINE = "headline";

    /** The text keys' prefixes, in the {@code server} bundle: {@code MetricGraphTypeTest} checks every key exists. */
    public static final String TYPE_TEXT = "server.types.metricGraph.";
    public static final String METRIC_TEXT = "server.metrics.";
    public static final String UNIT_TEXT = "server.metrics.unit.";

    /**
     * Each range choice with the time it shows, in the dropdown's order, first the default. Declared once with its
     * duration, so a choice added here is a choice the graph can draw.
     */
    public static final Map<String, Duration> RANGES = ordered("7d", Duration.ofDays(7), "3d", Duration.ofDays(3),
            "24h", Duration.ofHours(24), "6h", Duration.ofHours(6));

    /** The resolution that lets the store pick the finest bucket that fits the range: the default. */
    public static final String AUTO = "auto";

    /** Each resolution choice with its bucket, built from the store's own ({@link MetricsStore#BUCKETS}). */
    public static final Map<String, Duration> RESOLUTIONS = resolutions();

    /** Each CHOICE property's values, first the default. */
    public static final Map<String, List<String>> CHOICES = Map.of(
            PROP_LINES, List.of("combined", "perTask"),
            PROP_RANGE, List.copyOf(RANGES.keySet()),
            PROP_RESOLUTION, resolutionChoices(),
            PROP_ROLLUP, List.of("default", "max", "avg", "min"),
            PROP_STYLE, List.of("line", "area", "bars"),
            PROP_LIMIT, List.of("yes", "no"),
            PROP_WIDTH, List.of("4", "3", "2", "1"),
            PROP_HEIGHT, List.of("2", "1", "3", "4"),
            PROP_CONTROLS, List.of("all", "range", "none"),
            PROP_HEADLINE, List.of("no", "yes"));

    private static Map<String, Duration> ordered(final Object... choicesAndDurations) {
        final Map<String, Duration> out = new LinkedHashMap<>();
        for (int i = 0; i < choicesAndDurations.length; i += 2) {
            out.put((String) choicesAndDurations[i], (Duration) choicesAndDurations[i + 1]);
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, Duration> resolutions() {
        final Map<String, Duration> out = new LinkedHashMap<>();
        MetricsStore.BUCKETS.forEach(bucket -> out.put(resolutionChoice(bucket), bucket));
        return Collections.unmodifiableMap(out);
    }

    /** A bucket's choice value: "1h" for whole hours, else minutes ("5m"); each has its words in the bundle. */
    static String resolutionChoice(final Duration bucket) {
        return bucket.toMinutesPart() == 0 && bucket.toHours() > 0 ? bucket.toHours() + "h" : bucket.toMinutes() + "m";
    }

    private static List<String> resolutionChoices() {
        final List<String> out = new ArrayList<>();
        out.add(AUTO);
        out.addAll(RESOLUTIONS.keySet());
        return List.copyOf(out);
    }

    @Override
    public String getTypeId() {
        return TYPE_ID;
    }

    @Override
    public String getDisplayName() {
        return Text.get("server.types.metricGraph.name");
    }

    @Override
    public String getDescription() {
        return Text.get("server.types.metricGraph.description");
    }

    @Override
    public String getIcon() {
        return "pi-chart-line";
    }

    @Override
    public List<Placeholder> getProperties() {
        return List.of(
                new Placeholder(PROP_METRIC, Placeholder.Type.CHOICE, label(PROP_METRIC), hint(PROP_METRIC), true),
                text(PROP_DESCRIPTION), choice(PROP_WIDTH), choice(PROP_HEIGHT), choice(PROP_HEADLINE),
                choice(PROP_CONTROLS), text(PROP_NAME), text(PROP_TARGETS), choice(PROP_LINES), choice(PROP_RANGE),
                choice(PROP_RESOLUTION), choice(PROP_ROLLUP), choice(PROP_STYLE), text(PROP_Y_MIN), text(PROP_Y_MAX),
                text(PROP_WARN), text(PROP_ALERT), choice(PROP_LIMIT));
    }

    @Override
    public String getFragmentPath() {
        return "/WEB-INF/ptypes/metricGraph.xhtml";
    }

    /** A drawn line in the fragment's own classes; its color is the theme's, through {@code currentColor}. */
    @Override
    public String getSampleHtml() {
        return """
                <div class="metricGraph metricGraphSample">
                    <div class="metricGraphTitle">Connections in use, peak per minute</div>
                    <svg viewBox="0 0 300 80" class="metricGraphSampleLine" aria-hidden="true">
                        <polyline fill="none" stroke="currentColor" stroke-width="2"
                                points="0,70 30,66 60,68 90,40 120,62 150,64 180,30 210,58 240,60 270,55 300,57" />
                    </svg>
                </div>
                """;
    }

    /** The dashboard is about this deployment, never about one trip. */
    @Override
    public boolean fitsTripPage() {
        return false;
    }

    /** Only who may read metrics is offered a graph to add: the same rule as the graphs themselves. */
    @Override
    public boolean offeredTo(final Caller caller) {
        return MetricsCommands.mayViewMetrics(caller);
    }

    @Override
    public List<Choice> choicesFor(final String propertyName) {
        if (PROP_METRIC.equals(propertyName)) {
            return metricChoices();
        }
        return CHOICES.getOrDefault(propertyName, List.of()).stream()
                .map(value -> new Choice(value, Text.get(TYPE_TEXT + propertyName + "." + value)))
                .toList();
    }

    /** Every catalog field, labeled with its metric and its unit: the "which number" list. */
    public static List<Choice> metricChoices() {
        return MetricCatalog.ALL.stream().flatMap(metric -> metric.fields().stream()
                .map(field -> new Choice(value(metric, field), fieldLabel(metric, field)))).toList();
    }

    /** The stored value naming {@code field} of {@code metric}. */
    public static String value(final Metric metric, final Field field) {
        return metric.name() + ":" + field.name();
    }

    /** "DynamoDB connections: in use, peak per minute (connections)", in the reader's language. */
    public static String fieldLabel(final Metric metric, final Field field) {
        return Text.get("server.types.metricGraph.fieldLabel", Text.get(METRIC_TEXT + metric.name() + ".label"),
                Text.get(METRIC_TEXT + metric.name() + "." + field.name()), Text.get(UNIT_TEXT + field.unit()));
    }

    private static Placeholder choice(final String name) {
        return new Placeholder(name, Placeholder.Type.CHOICE, label(name), hint(name), false);
    }

    private static Placeholder text(final String name) {
        return new Placeholder(name, Placeholder.Type.TEXT, label(name), hint(name), false);
    }

    private static String label(final String name) {
        return Text.get(TYPE_TEXT + name + ".label");
    }

    private static String hint(final String name) {
        return Text.get(TYPE_TEXT + name + ".hint");
    }
}
