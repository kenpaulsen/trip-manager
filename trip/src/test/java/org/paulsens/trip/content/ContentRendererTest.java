package org.paulsens.trip.content;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Placeholder;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The renderer's contract IS the security model for section editors: everything except RICH_TEXT must come
 * out neutralized, because the page emits the result with {@code escape="false"}.
 */
public class ContentRendererTest {

    private static ContentTemplate template(final String body, final Placeholder... placeholders) {
        return new ContentTemplate("t", 1, "T", null, body, List.of(placeholders), null, null);
    }

    private static ContentInstance instance(final Map<String, String> values) {
        return new ContentInstance("c", "s", "C", "t", 1, new HashMap<>(values), null, 0, 1, null, null);
    }

    private static Placeholder ph(final String name, final Placeholder.Type type) {
        return new Placeholder(name, type, name, null, false);
    }

    @Test
    public void textIsHtmlEscaped() {
        final ContentTemplate tpl = template("<p>{{msg}}</p>", ph("msg", Placeholder.Type.TEXT));
        final String out = ContentRenderer.render(tpl, instance(Map.of("msg", "<script>alert('&')</script>")));
        Assert.assertEquals(out, "<p>&lt;script&gt;alert(&#39;&amp;&#39;)&lt;/script&gt;</p>");
    }

    @Test
    public void richTextPassesThroughVerbatim() {
        final ContentTemplate tpl = template("<div>{{body}}</div>", ph("body", Placeholder.Type.RICH_TEXT));
        final String out = ContentRenderer.render(tpl, instance(Map.of("body", "<b>bold &amp; kept</b>")));
        Assert.assertEquals(out, "<div><b>bold &amp; kept</b></div>");
    }

    @Test
    public void urlMustBeHttp() {
        final ContentTemplate tpl = template("<a href=\"{{u}}\">x</a>", ph("u", Placeholder.Type.URL));
        Assert.assertEquals(ContentRenderer.render(tpl,
                instance(Map.of("u", "javascript:alert(1)"))), "<a href=\"\">x</a>");
        Assert.assertEquals(ContentRenderer.render(tpl,
                instance(Map.of("u", "not a url at all"))), "<a href=\"\">x</a>");
        Assert.assertEquals(ContentRenderer.render(tpl,
                instance(Map.of("u", "https://example.com/x"))), "<a href=\"https://example.com/x\">x</a>");
    }

    @Test
    public void urlIsAttributeEscaped() {
        final ContentTemplate tpl = template("<a href=\"{{u}}\">x</a>", ph("u", Placeholder.Type.URL));
        final String out = ContentRenderer.render(tpl,
                instance(Map.of("u", "https://example.com/?a=1&b=2")));
        Assert.assertEquals(out, "<a href=\"https://example.com/?a=1&amp;b=2\">x</a>",
                "an ampersand inside an attribute must be entity-escaped");
        // A quote is not legal in a URI at all; strict parsing rejects it outright.
        Assert.assertEquals(ContentRenderer.render(tpl,
                instance(Map.of("u", "https://example.com/?b=\"2\""))), "<a href=\"\">x</a>");
    }

    @Test
    public void youTubeFormsNormalizeToEmbed() {
        for (final String form : List.of(
                "https://www.youtube.com/watch?v=abc123XYZ_-",
                "https://m.youtube.com/watch?list=x&v=abc123XYZ_-",
                "https://youtu.be/abc123XYZ_-",
                "https://www.youtube.com/shorts/abc123XYZ_-",
                "https://www.youtube.com/embed/abc123XYZ_-?rel=0")) {
            Assert.assertEquals(ContentRenderer.normalizeVideoUrl(form),
                    "https://www.youtube.com/embed/abc123XYZ_-", "for " + form);
        }
    }

    @Test
    public void nonYouTubeVideoUrlPassesThrough() {
        Assert.assertEquals(ContentRenderer.normalizeVideoUrl("https://vimeo.com/12345"),
                "https://vimeo.com/12345");
        Assert.assertEquals(ContentRenderer.normalizeVideoUrl("ftp://example.com/x"), "");
    }

    @Test
    public void videoUrlRendersEscaped() {
        final ContentTemplate tpl = template("<iframe src=\"{{v}}\"></iframe>",
                ph("v", Placeholder.Type.VIDEO_URL));
        Assert.assertEquals(ContentRenderer.render(tpl,
                        instance(Map.of("v", "https://youtu.be/abc123XYZ_-"))),
                "<iframe src=\"https://www.youtube.com/embed/abc123XYZ_-\"></iframe>");
    }

    @Test
    public void unknownAndMissingTokensRenderEmpty() {
        // {{undeclared}} has no Placeholder; {{msg}} is declared but has no value. Neither may leak.
        final ContentTemplate tpl = template("A{{undeclared}}B{{msg}}C", ph("msg", Placeholder.Type.TEXT));
        Assert.assertEquals(ContentRenderer.render(tpl, instance(Map.of())), "ABC");
    }

    @Test
    public void tokenWhitespaceIsTolerated() {
        final ContentTemplate tpl = template("[{{ msg }}]", ph("msg", Placeholder.Type.TEXT));
        Assert.assertEquals(ContentRenderer.render(tpl, instance(Map.of("msg", "hi"))), "[hi]");
    }

    @Test
    public void nullsRenderEmpty() {
        Assert.assertEquals(ContentRenderer.render(null, instance(Map.of())), "");
        Assert.assertEquals(ContentRenderer.render(template("x"), null), "");
        Assert.assertEquals(ContentRenderer.render(template(null), instance(Map.of())), "");
    }

    @Test
    public void tokenNamesFindDistinctInOrder() {
        Assert.assertEquals(ContentRenderer.tokenNames("{{b}} {{a}} {{b}} {{ c }}").stream().toList(),
                List.of("b", "a", "c"));
        Assert.assertTrue(ContentRenderer.tokenNames(null).isEmpty());
    }

    @Test
    public void escapeHelperCoversAllFive() {
        Assert.assertEquals(ContentRenderer.escapeHtml("&<>\"'"), "&amp;&lt;&gt;&quot;&#39;");
        Assert.assertEquals(ContentRenderer.escapeHtml("plain"), "plain");
    }

    @Test
    public void requireHttpUrlHandlesEdgeCases() {
        Assert.assertEquals(ContentRenderer.requireHttpUrl("  https://x.example  "), "https://x.example");
        Assert.assertEquals(ContentRenderer.requireHttpUrl("http://x.example"), "http://x.example");
        Assert.assertEquals(ContentRenderer.requireHttpUrl("//x.example"), "");
        Assert.assertEquals(ContentRenderer.requireHttpUrl("::::"), "");
    }
}
