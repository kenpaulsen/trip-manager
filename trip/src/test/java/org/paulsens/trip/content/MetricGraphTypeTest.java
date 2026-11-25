package org.paulsens.trip.content;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.paulsens.trip.metrics.MetricCatalog;
import org.paulsens.trip.metrics.MetricCatalog.Field;
import org.paulsens.trip.metrics.MetricCatalog.Metric;
import org.paulsens.trip.model.Placeholder;
import org.testng.annotations.Test;

/**
 * The metrics graph content type: its options, its "which number" list built from the catalog, and every text key
 * it builds from a prefix present in every language (the bundle policy only sees literal keys, so this one does).
 */
public class MetricGraphTypeTest {

    private final MetricGraphType type = new MetricGraphType();

    @Test
    public void itIsAGraphForSiteAdminsOffTripPages() {
        assertEquals(ProgrammaticTypes.byId(MetricGraphType.TYPE_ID).orElseThrow().getClass(), MetricGraphType.class);
        assertEquals(type.getFragmentPath(), "/WEB-INF/ptypes/metricGraph.xhtml");
        assertEquals(type.getIcon(), "pi-chart-line");
        assertFalse(type.fitsTripPage());
        assertTrue(type.offeredTo(true));
        assertFalse(type.offeredTo(false), "only a site admin is offered a graph to add");
        assertTrue(type.getSampleHtml().contains("currentColor"), "the sample draws in the theme's color");
        assertFalse(type.getSampleHtml().contains("#"), "no literal color");
        assertTrue(StarterTemplates.IDS.contains(MetricGraphType.TYPE_ID));
        assertFalse(type.getDisplayName().isBlank());
        assertFalse(type.getDescription().isBlank());
    }

    @Test
    public void theMetricListIsTheCatalogAndEveryChoiceHasItsWords() {
        final List<ProgrammaticContentTemplate.Choice> metrics = type.choicesFor(MetricGraphType.PROP_METRIC);
        assertEquals(metrics.size(), MetricCatalog.ALL.stream().mapToInt(metric -> metric.fields().size()).sum());
        assertEquals(metrics.get(0).value(), "ddb.pool:max");
        assertEquals(metrics.get(0).label(), "DynamoDB connections: in use, peak per minute (connections)");
        assertEquals(type.choicesFor(MetricGraphType.PROP_RANGE).get(0).label(), "Last 7 days", "the default first");
        assertEquals(type.choicesFor("nope"), List.of());
        final Placeholder metric = type.getProperties().get(0);
        assertTrue(metric.isRequired());
        assertEquals(metric.getType(), Placeholder.Type.CHOICE);
    }

    @Test
    public void everyKeyItBuildsIsInEveryLanguage() throws IOException {
        final List<String> keys = new ArrayList<>();
        for (final Placeholder property : type.getProperties()) {
            keys.add(MetricGraphType.TYPE_TEXT + property.getName() + ".label");
            keys.add(MetricGraphType.TYPE_TEXT + property.getName() + ".hint");
        }
        MetricGraphType.CHOICES.forEach((property, values) -> values.forEach(value -> keys.add(
                MetricGraphType.TYPE_TEXT + property + "." + value)));
        for (final Metric metric : MetricCatalog.ALL) {
            keys.add(MetricGraphType.METRIC_TEXT + metric.name() + ".label");
            for (final Field field : metric.fields()) {
                keys.add(MetricGraphType.METRIC_TEXT + metric.name() + "." + field.name());
                keys.add(MetricGraphType.UNIT_TEXT + field.unit());
            }
        }
        for (final String bundle : List.of("server", "server_es", "server_hr")) {
            final Properties texts = load("/i18n/" + bundle + ".properties");
            final List<String> missing = keys.stream().filter(key -> texts.getProperty(key) == null).toList();
            assertEquals(missing, List.of(), bundle + " lacks the graph's words");
        }
    }

    private static Properties load(final String resource) throws IOException {
        final Properties properties = new Properties();
        try (InputStream in = MetricGraphTypeTest.class.getResourceAsStream(resource)) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
