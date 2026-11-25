package org.paulsens.trip.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.paulsens.trip.content.MetricGraphType;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.metrics.MetricCatalog;
import org.paulsens.trip.metrics.MetricCatalog.Field;
import org.paulsens.trip.metrics.MetricCatalog.Metric;
import org.paulsens.trip.metrics.MetricCatalog.Rollup;
import org.paulsens.trip.metrics.MetricsStore;
import org.paulsens.trip.metrics.MetricsStore.Line;
import org.paulsens.trip.metrics.MetricsStore.Point;
import org.paulsens.trip.metrics.MetricsStore.Series;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.SitePage;
import org.paulsens.trip.util.Util;

/**
 * The metrics dashboard's bean ({@code docs/metrics.md}): who may see it, where the organization hub's card points,
 * and what each graph on it draws.
 *
 * <p>The dashboard is an Editable Page, {@value #PAGE_NAME}, on every organization's site, gated to site admins
 * ({@link SitePage.Access#SITE_ADMINS}) and built from {@code metric-graph} items ({@link MetricGraphType}). Its
 * graphs are the server's own numbers, the same on every organization's page for now; business metrics, which will
 * be the organization's own, are why it lives with the organization rather than on a site-admin page.
 *
 * <p>A graph's data goes to the browser as JSON ({@link #graph}) and the page's script builds the chart from it, so
 * every viewer control (combined or per server, the range, zoom) is client-side: the fragment is render-time only,
 * like every content fragment, and holds nothing in the view.
 */
@Named("metrics")
@ApplicationScoped
public class MetricsCommands {

    /** The dashboard page's name on every organization's site. */
    public static final String PAGE_NAME = "metrics";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Supplier<Caller> callerSource;
    private final MetricsStore store;

    public MetricsCommands() {
        this(Caller::current, new MetricsStore(RequestMemo::get));
    }

    /** Test seam: the caller and the store. */
    public MetricsCommands(final Supplier<Caller> callerSource, final MetricsStore store) {
        this.callerSource = callerSource;
        this.store = store;
    }

    /**
     * What one graph draws, for the fragment: its heading, the line under it, the axis unit, its size on the graph
     * grid (columns and rows, each 1 to 4), whether there is nothing to draw ({@code noData}: never {@code empty},
     * an EL operator a page cannot call), and the data as JSON for the page's script: the lines, the thresholds and
     * the view (its name, the graphs it shows in, its controls, its headline number).
     */
    public record GraphView(String title, String description, String unit, String width, String height,
            boolean noData, String json) {
    }

    /**
     * Whether the caller may see {@code orgId}'s metrics: site admins, for now. The hub card and every graph ask
     * this; the dashboard page's own gate is its access level. Business metrics will widen it to the
     * organization's admins for that organization's numbers.
     */
    public boolean canView(final String orgId) {
        return mayViewMetrics(callerSource.get());
    }

    /**
     * The one rule for who may read metrics: the hub card, every graph and the Add dialog's offer of a graph all
     * come here, so widening it widens them together.
     */
    public static boolean mayViewMetrics(final Caller caller) {
        return caller.isSignedInSiteAdmin();
    }

    /**
     * The hub card's link: the dashboard on the organization's own site, or null when the caller may not see it,
     * the organization has no site, or its dashboard page is gone.
     */
    public String hubUrl(final String orgId) {
        if (!canView(orgId)) {
            return null;
        }
        final SitePageCommands pages = new SitePageCommands(callerSource);
        final SitePage page = pages.pageOf(orgId, PAGE_NAME);
        // An open page at that address is the organization's own "metrics" page, not the gated dashboard.
        return page == null || page.isLink() || page.who() == SitePage.Access.EVERYONE ? null
                : pages.viewUrl(orgId, page);
    }

    /**
     * What {@code instance} (a {@code metric-graph} item) draws, read once per request. Null for a caller who may
     * not see metrics: the fragment draws nothing then, and nothing has been read.
     */
    public GraphView graph(final ContentInstance instance) {
        if (instance == null || !canView(null)) {
            return null;
        }
        return RequestMemo.get("MetricsCommands.graph:" + instance.getId() + ":" + instance.getVersion(),
                () -> buildGraph(values(instance), instance.getTitle()));
    }

    GraphView buildGraph(final Map<String, String> values, final String title) {
        final Optional<Metric> metric = metricOf(values.get(MetricGraphType.PROP_METRIC));
        final Optional<Field> field = metric.flatMap(found -> found.field(fieldName(values)));
        if (field.isEmpty()) {
            return new GraphView(Util.isBlank(title) ? Text.get("server.types.metricGraph.unknownMetric")
                    : title.trim(),
                    Util.trimmed(values.get(MetricGraphType.PROP_DESCRIPTION)), "",
                    choice(values, MetricGraphType.PROP_WIDTH), choice(values, MetricGraphType.PROP_HEIGHT), true,
                    view(values).toString());
        }
        final Duration range = range(choice(values, MetricGraphType.PROP_RANGE));
        final Duration bucket = bucket(choice(values, MetricGraphType.PROP_RESOLUTION));
        final Rollup rollup = rollup(choice(values, MetricGraphType.PROP_ROLLUP));
        final Series series = store.read(metric.get(), field.get(), range, bucket, rollup);
        final ObjectNode data = view(values);
        data.put("metric", values.get(MetricGraphType.PROP_METRIC).trim());
        data.put("unit", Text.get(MetricGraphType.UNIT_TEXT + field.get().unit()));
        data.put("unitName", field.get().unit());
        data.put("style", choice(values, MetricGraphType.PROP_STYLE));
        data.put("perTask", "perTask".equals(choice(values, MetricGraphType.PROP_LINES)));
        data.put("rangeSeconds", range.toSeconds());
        data.put("bucketSeconds", series.bucket().toSeconds());
        putNumber(data, "yMin", values.get(MetricGraphType.PROP_Y_MIN));
        putNumber(data, "yMax", values.get(MetricGraphType.PROP_Y_MAX));
        putNumber(data, "warnAt", values.get(MetricGraphType.PROP_WARN));
        putNumber(data, "alertAt", values.get(MetricGraphType.PROP_ALERT));
        final long step = series.bucket().toSeconds();
        data.set("combined", points(store.combinedLine(series).points(), step));
        final ArrayNode tasks = data.putArray("tasks");
        series.lines().forEach(line -> tasks.addObject().put("id", line.task()).set("points", points(line.points(),
                step)));
        limit(metric.get(), field.get(), values, range, bucket).ifPresent(line -> data.set("limit",
                points(line.points(), step)));
        final String heading = Util.isBlank(title) ? MetricGraphType.fieldLabel(metric.get(), field.get())
                : title.trim();
        data.put("title", heading);
        data.put("description", Util.trimmed(values.get(MetricGraphType.PROP_DESCRIPTION)));
        return new GraphView(heading, Util.trimmed(values.get(MetricGraphType.PROP_DESCRIPTION)),
                Text.get(MetricGraphType.UNIT_TEXT + field.get().unit()), choice(values, MetricGraphType.PROP_WIDTH),
                choice(values, MetricGraphType.PROP_HEIGHT), series.isEmpty(), data.toString());
    }

    /** The field's ceiling (the pool's size, the heap's max), as its own line, when the graph asks for it. */
    private Optional<Line> limit(final Metric metric, final Field field, final Map<String, String> values,
            final Duration range, final Duration bucket) {
        if (field.limit() == null || "no".equals(choice(values, MetricGraphType.PROP_LIMIT))) {
            return Optional.empty();
        }
        return metric.field(field.limit()).map(ceiling -> store.combinedLine(store.read(metric, ceiling, range,
                bucket, Rollup.MAX)));
    }

    /** Numbers the editor typed that are not numbers: shown to whoever can edit the page, ignored by the graph. */
    public List<String> invalidNumbers(final ContentInstance instance) {
        final Map<String, String> values = values(instance);
        final List<String> bad = new ArrayList<>();
        for (final String name : List.of(MetricGraphType.PROP_Y_MIN, MetricGraphType.PROP_Y_MAX,
                MetricGraphType.PROP_WARN, MetricGraphType.PROP_ALERT)) {
            if (!Util.isBlank(values.get(name)) && parse(values.get(name)) == null) {
                bad.add(Text.get(MetricGraphType.TYPE_TEXT + name + ".label"));
            }
        }
        return bad;
    }

    /** {@link #invalidNumbers} as one comma-separated phrase for the editor's note, never a list's brackets. */
    public String invalidNumberNames(final ContentInstance instance) {
        return String.join(", ", invalidNumbers(instance));
    }

    /**
     * A line as its first bucket (epoch milliseconds for the chart's time axis), the bucket's length in
     * milliseconds and one value per bucket, null for a gap the chart draws as one. Every line is a dense grid on
     * one step, so a timestamp per point would only repeat what the step says: the dashboard's eight demo graphs
     * went from 575 KB to about a fifth of that.
     */
    private static ObjectNode points(final List<Point> points, final long stepSeconds) {
        final ObjectNode out = JSON.createObjectNode();
        out.put("start", points.isEmpty() ? 0 : points.get(0).at() * 1000);
        out.put("step", stepSeconds * 1000);
        final ArrayNode values = out.putArray("values");
        points.forEach(point -> values.add(point.value() == null ? null
                : JSON.getNodeFactory().numberNode(Math.round(point.value() * 1000.0) / 1000.0)));
        return out;
    }

    private static void putNumber(final ObjectNode data, final String name, final String raw) {
        final Double value = parse(raw);
        if (value != null) {
            data.put(name, value);
        }
    }

    static Double parse(final String raw) {
        if (Util.isBlank(raw)) {
            return null;
        }
        try {
            final double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) ? value : null;
        } catch (final NumberFormatException ex) {
            return null;
        }
    }

    private static Optional<Metric> metricOf(final String value) {
        return Util.isBlank(value) || !value.contains(":") ? Optional.empty()
                : MetricCatalog.metric(value.substring(0, value.indexOf(':')));
    }

    private static String fieldName(final Map<String, String> values) {
        final String value = values.get(MetricGraphType.PROP_METRIC);
        return value.substring(value.indexOf(':') + 1);
    }

    /** A CHOICE property's value, or its default (the first choice) when unset or unknown. */
    static String choice(final Map<String, String> values, final String name) {
        final List<String> allowed = MetricGraphType.CHOICES.get(name);
        final String value = Util.trimmed(values.get(name));
        return allowed.contains(value) ? value : allowed.get(0);
    }

    /** A range choice as the time it shows; an unknown one is the default's. */
    static Duration range(final String choice) {
        final Duration range = MetricGraphType.RANGES.get(choice);
        return range != null ? range : MetricGraphType.RANGES.values().iterator().next();
    }

    /** A resolution choice as a bucket; null (auto, or unknown) lets the store pick the finest that fits. */
    static Duration bucket(final String choice) {
        return MetricGraphType.RESOLUTIONS.get(choice);
    }

    /** A rollup choice; null (default) means the field's own. */
    static Rollup rollup(final String choice) {
        return switch (choice) {
            case "max" -> Rollup.MAX;
            case "avg" -> Rollup.AVG;
            case "min" -> Rollup.MIN;
            default -> null;
        };
    }

    /**
     * How the graph presents itself, for the page's script: its size, its controls, its headline number, its own
     * name and the names of the graphs a click on it shows its metric in. Names are a graph's handle on the page:
     * lower-case letters, digits and hyphens, so a typed "Focus " still finds "focus".
     */
    static ObjectNode view(final Map<String, String> values) {
        final ObjectNode view = JSON.createObjectNode();
        view.put("width", Integer.parseInt(choice(values, MetricGraphType.PROP_WIDTH)));
        view.put("height", Integer.parseInt(choice(values, MetricGraphType.PROP_HEIGHT)));
        view.put("controls", choice(values, MetricGraphType.PROP_CONTROLS));
        view.put("headline", "yes".equals(choice(values, MetricGraphType.PROP_HEADLINE)));
        view.put("name", graphName(values.get(MetricGraphType.PROP_NAME)));
        final ArrayNode targets = view.putArray("targets");
        targets(values.get(MetricGraphType.PROP_TARGETS)).forEach(targets::add);
        return view;
    }

    /** A graph name as the page compares it: trimmed, lower case, only letters, digits and hyphens. */
    static String graphName(final String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "");
    }

    /** The graph names a "Shows in" value lists, separated by commas or spaces, each cleaned, blanks dropped. */
    static List<String> targets(final String raw) {
        return raw == null ? List.of() : Arrays.stream(raw.split("[,\\s]+")).map(MetricsCommands::graphName)
                .filter(name -> !name.isEmpty()).distinct().toList();
    }

    private static Map<String, String> values(final ContentInstance instance) {
        return instance == null || instance.getValues() == null ? Map.of() : instance.getValues();
    }
}
