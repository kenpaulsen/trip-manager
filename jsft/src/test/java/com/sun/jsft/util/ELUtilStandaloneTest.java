package com.sun.jsft.util;

import jakarta.el.PropertyNotFoundException;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/**
 * Rendering without JSF.
 *
 * <p>Deliberately no {@code FacesContext} anywhere in this class -- that absence <em>is</em> the test. Every other
 * path through {@link ELUtil} reads {@code FacesContext.getCurrentInstance()}, and off a request thread that returns
 * null and {@code eval} hands back null, which callers stringify into the four-character body "null" and send. These
 * methods exist so a scheduled job can render a template at all.
 */
public class ELUtilStandaloneTest {

    /** A record, to prove RecordELResolver is reached -- it is not, if the variable is declared as Object. */
    public record Sender(String preferredName, String last) { }

    private final ELUtil elUtil = ELUtil.getInstance();

    @Test
    public void aWholeTemplateRendersWithNoFacesContext() {
        final String template = "<p>Hi #{person.preferredName} #{person.last},</p>"
                + "<p>You have #{count} new messages in #{tripTitle}.</p>";
        final String out = elUtil.renderWithoutJsf(template, Map.of(
                "person", new Sender("Ken", "Paulsen"),
                "count", 3,
                "tripTitle", "Summer Fest"));

        assertEquals(out, "<p>Hi Ken Paulsen,</p><p>You have 3 new messages in Summer Fest.</p>");
    }

    @Test
    public void mapsListsAndMethodCallsAllResolve() {
        // The standard resolver chain, which is the whole reason this needs no custom resolver.
        final String out = elUtil.renderWithoutJsf(
                "#{trip['title']}|#{items.size()}|#{items[0]}|#{empty missingList ? 'none' : 'some'}",
                Map.of("trip", Map.of("title", "Fest"), "items", List.of("a", "b"), "missingList", List.of()));
        assertEquals(out, "Fest|2|a|none");
    }

    @Test
    public void aTemplateWithNoExpressionsIsReturnedUnchanged() {
        assertEquals(elUtil.renderWithoutJsf("<p>Nothing dynamic here.</p>", Map.of()),
                "<p>Nothing dynamic here.</p>");
        assertEquals(elUtil.renderWithoutJsf("<p>Still nothing.</p>", null), "<p>Still nothing.</p>");
    }

    @Test
    public void nullTemplateRendersEmptyRatherThanTheStringNull() {
        // The specific failure this method was written to end: never emit "null" as body text.
        assertEquals(elUtil.renderWithoutJsf(null, Map.of()), "");
    }

    @Test
    public void aMissingPropertyThrowsRatherThanRenderingQuietly() {
        // Loud on purpose. A typo in a template is a bug, and the alternative -- rendering an empty string -- ships
        // a mail with a blank where a name should be and nothing in the log.
        assertThrows(PropertyNotFoundException.class, () -> elUtil.renderWithoutJsf(
                "Hi #{person.nickname}", Map.of("person", new Sender("Ken", "Paulsen"))));
        assertThrows(PropertyNotFoundException.class, () -> elUtil.renderWithoutJsf(
                "Hi #{whoIsThis.name}", Map.of()));
    }

    @Test
    public void aNullValueRendersEmpty() {
        // Distinct from a MISSING variable: the name resolves, the value is simply absent.
        final Map<String, Object> vars = new java.util.HashMap<>();
        vars.put("nickname", null);
        assertEquals(elUtil.renderWithoutJsf("[#{nickname}]", vars), "[]");
    }

    @Test
    public void nothingIsEscaped() {
        // Pinned as a CONTRACT, not an oversight: callers rendering user text into HTML must escape first. If this
        // ever starts escaping, plain-text mail bodies fill with visible entities.
        final String out = elUtil.renderWithoutJsf("#{body}", Map.of("body", "<script>alert(1)</script>"));
        assertEquals(out, "<script>alert(1)</script>");
    }

    @Test
    public void singleExpressionEvaluationReturnsTheTypedValue() {
        assertEquals(elUtil.evalWithoutJsf("#{a + b}", Map.of("a", 2, "b", 5)).toString(), "7");
        assertTrue((Boolean) elUtil.evalWithoutJsf("#{name eq 'Ken'}", Map.of("name", "Ken")));
        assertEquals(elUtil.evalWithoutJsf(null, Map.of()), null);
    }

    @Test
    public void renderingIsSafeFromManyThreadsAtOnce() throws Exception {
        // The digest renders one template per recipient off a scheduler thread, and the ExpressionFactory is shared.
        final int threads = 8;
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            final List<java.util.concurrent.Future<String>> results = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int n = i;
                results.add(pool.submit(() -> elUtil.renderWithoutJsf(
                        "recipient #{n}", Map.of("n", n))));
            }
            for (int i = 0; i < threads; i++) {
                assertEquals(results.get(i).get(), "recipient " + i);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
