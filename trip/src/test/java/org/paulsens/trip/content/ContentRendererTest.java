package org.paulsens.trip.content;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static ContentInstance child(final String id, final String title) {
        return new ContentInstance(id, "s", title, "t", 1, new HashMap<>(), null, 0, 1, null, null);
    }

    /** The wrapper a row cannot express: emitted once around the whole list, not per child. */
    @Test
    public void aRegionSplitsTheWrapperFromTheRepeatedRow() {
        final ContentRenderer.ContainerBody parts = ContentRenderer.containerBody(
                "<ul class=\"ev\">{{children:start}}<li>{{child}}</li>{{children:end}}</ul>");
        Assert.assertEquals(parts.beforeAll(), "<ul class=\"ev\">");
        Assert.assertEquals(parts.row(), "<li>{{child}}</li>");
        Assert.assertEquals(parts.afterAll(), "</ul>");
    }

    @Test
    public void aBodyWithNoRegionIsAllRow() {
        final ContentRenderer.ContainerBody parts =
                ContentRenderer.containerBody("<li>{{child:title}}{{child}}</li>");
        Assert.assertEquals(parts.beforeAll(), "");
        Assert.assertEquals(parts.row(), "<li>{{child:title}}{{child}}</li>");
        Assert.assertEquals(parts.afterAll(), "");
    }

    /** Half a wrapper would emit an unclosed tag into the page, so anything unusable loses the wrapper. */
    @Test
    public void anUnusableBodyDegradesToTheBuiltInRowWithNoWrapper() {
        for (final String bad : new String[] {null, "  ", "<ul>{{children:start}}<li>{{child}}</li>",
                "<ul>{{children:start}}<li>no slot</li>{{children:end}}</ul>", "<li>no slot at all</li>"}) {
            final ContentRenderer.ContainerBody parts = ContentRenderer.containerBody(bad);
            Assert.assertEquals(parts.beforeAll(), "", "no half wrapper for: " + bad);
            Assert.assertEquals(parts.afterAll(), "", "no half wrapper for: " + bad);
            Assert.assertEquals(parts.row(), ContentRenderer.DEFAULT_CHILD_ROW,
                    "children must still render for: " + bad);
        }
    }

    @Test
    public void theRegionIsValidatedAsAPair() {
        Assert.assertNull(ContentRenderer.containerBodyProblem(null), "blank means the built-in row");
        Assert.assertNull(ContentRenderer.containerBodyProblem(""));
        Assert.assertNull(ContentRenderer.containerBodyProblem("<li>{{child}}</li>"));
        Assert.assertNull(ContentRenderer.containerBodyProblem(
                "<ul>{{children:start}}<li>{{child}}</li>{{children:end}}</ul>"));

        Assert.assertTrue(ContentRenderer.containerBodyProblem("<ul>{{children:start}}<li>{{child}}</li>")
                .contains("as a pair"), "an unclosed region is refused");
        Assert.assertTrue(ContentRenderer.containerBodyProblem("{{children:end}}{{child}}{{children:start}}")
                .contains("in that order"), "a reversed region is refused");
        Assert.assertTrue(ContentRenderer.containerBodyProblem(
                        "{{children:start}}{{child}}{{children:end}}{{children:start}}{{children:end}}")
                .contains("only once"), "one region per body");
        Assert.assertTrue(ContentRenderer.containerBodyProblem("<li>no slot</li>").contains("{{child}}"),
                "a row must still say where the child goes");
    }

    /** The wrapper is emitted once; the row's per-child tokens are not filled in it. */
    @Test
    public void theWrapperIsNotPerChild() {
        final String body = "<ul>{{children:start}}<li>{{child:title}}{{child}}</li>{{children:end}}</ul>";
        final ContentRenderer.ChildRow row =
                ContentRenderer.renderChildRow(body, child("c1", "Rome"), false, 0);
        Assert.assertEquals(row.before(), "<li>Rome", "the row alone repeats");
        Assert.assertEquals(row.after(), "</li>");
    }

    @Test
    public void aContainerRowSplitsAtTheChildSlot() {
        final ContentRenderer.ChildRow row = ContentRenderer.renderChildRow(
                "<li class=\"item\"><h4>{{child:title}}</h4>{{child}}</li>", child("c1", "Rome"), false, 0);
        Assert.assertEquals(row.before(), "<li class=\"item\"><h4>Rome</h4>");
        Assert.assertEquals(row.after(), "</li>");
    }

    @Test
    public void aChildTitleIsEscapedLikeAnyOtherData() {
        final ContentRenderer.ChildRow row = ContentRenderer.renderChildRow(
                "<b>{{child:title}}</b>{{child}}", child("c1", "<img src=x onerror=alert(1)>"), false, 0);
        Assert.assertEquals(row.before(), "<b>&lt;img src=x onerror=alert(1)&gt;</b>");
    }

    @Test
    public void idAndOneBasedIndexAreReadable() {
        final ContentRenderer.ChildRow row = ContentRenderer.renderChildRow(
                "<a id=\"{{child:id}}\">{{child:index}}.</a>{{child}}", child("docs-3", "T"), false, 4);
        Assert.assertEquals(row.before(), "<a id=\"docs-3\">5.</a>");
    }

    /** A programmatic child builds its own heading from live data; repeating the instance title duplicates it. */
    @Test
    public void aProgrammaticChildSuppressesTheContainerWrittenTitle() {
        final ContentRenderer.ChildRow row = ContentRenderer.renderChildRow(
                ContentRenderer.DEFAULT_CHILD_ROW, child("c1", "Photo Album"), true, 0);
        Assert.assertEquals(row.before(), "<div class=\"contentTitle\"></div>");
        Assert.assertEquals(row.after(), "");
    }

    @Test
    public void unknownChildPropertiesAndNullsRenderEmpty() {
        final ContentRenderer.ChildRow row = ContentRenderer.renderChildRow(
                "[{{child:bogus}}][{{child:title}}]{{child}}", child("c1", null), false, 0);
        Assert.assertEquals(row.before(), "[][]");
        Assert.assertEquals(ContentRenderer.renderChildRow("{{child}}", null, false, 0).before(), "");
    }

    /** Dropping every child is the one container failure an editor cannot see or diagnose. */
    @Test
    public void aBodyWithoutTheSlotFallsBackToTheDefaultRow() {
        final ContentRenderer.ChildRow row =
                ContentRenderer.renderChildRow("<li>{{child:title}}</li>", child("c1", "Kept"), false, 0);
        Assert.assertEquals(row.before(), "<div class=\"contentTitle\">Kept</div>");
        Assert.assertEquals(row.after(), "");
        Assert.assertEquals(ContentRenderer.renderChildRow(null, child("c1", "Kept"), false, 0).after(), "");
        Assert.assertEquals(ContentRenderer.renderChildRow("  ", child("c1", "Kept"), false, 0).after(), "");
    }

    @Test
    public void theSlotIsRecognizedWithWhitespaceAndOnlyWithoutAProperty() {
        Assert.assertTrue(ContentRenderer.hasChildSlot("<li>{{ child }}</li>"));
        Assert.assertFalse(ContentRenderer.hasChildSlot("<li>{{child:title}}</li>"));
        Assert.assertFalse(ContentRenderer.hasChildSlot(null));
        final ContentRenderer.ChildRow row =
                ContentRenderer.renderChildRow("a{{ child }}b", child("c1", "T"), false, 0);
        Assert.assertEquals(row.before(), "a");
        Assert.assertEquals(row.after(), "b");
    }

    /**
     * The property form carries a ':', which the STANDARD token pattern does not admit -- so a container
     * body's vocabulary can never be mistaken for a placeholder, nor offered by "Detect from body".
     */
    @Test
    public void childPropertyTokensAreNotStandardPlaceholders() {
        Assert.assertEquals(ContentRenderer.tokenNames("<p>{{child:title}}{{real}}</p>"), Set.of("real"));
        final ContentTemplate tpl = template("<p>{{child:title}}</p>");
        Assert.assertEquals(ContentRenderer.render(tpl, instance(Map.of())), "<p>{{child:title}}</p>");
    }

    @Test
    public void textIsHtmlEscaped() {
        final ContentTemplate tpl = template("<p>{{msg}}</p>", ph("msg", Placeholder.Type.TEXT));
        final String out = ContentRenderer.render(tpl, instance(Map.of("msg", "<script>alert('&')</script>")));
        Assert.assertEquals(out, "<p>&lt;script&gt;alert(&#39;&amp;&#39;)&lt;/script&gt;</p>");
    }

    /**
     * medjugorje#158: a rich-text value is cleaned where it is written out, whatever reached storage. Without
     * this, a row stored before the cleaner (or written past it) would go to the page as it is.
     */
    @Test
    public void richTextIsCleanedWhereItIsRendered() {
        final ContentTemplate tpl = template("<div>{{body}}</div>", ph("body", Placeholder.Type.RICH_TEXT));
        final String out = ContentRenderer.render(tpl,
                instance(Map.of("body", "<p onclick=\"x()\">a</p><script>x()</script>")));
        Assert.assertEquals(out, "<div><p>a</p></div>");
        Assert.assertEquals(ContentRenderer.renderContainerTitle("<b onclick=\"x()\">T</b><script>x()</script>"),
                "<b>T</b>");
        Assert.assertEquals(ContentRenderer.titleText("<i onmouseover=\"x()\">T</i>"), "<i>T</i>");
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
    public void onlyLinksPassTheLinkTargetAndWhatItAnswersIsStillScreened() {
        final ContentTemplate tpl = template("<a href=\"{{u}}\">{{t}}</a><img src=\"{{i}}\">",
                ph("u", Placeholder.Type.URL), ph("t", Placeholder.Type.TEXT), ph("i", Placeholder.Type.IMAGE_URL));
        final Map<String, String> values = Map.of("u", "/go", "t", "/go", "i", "https://example.com/a.png");
        Assert.assertEquals(ContentRenderer.render(tpl, instance(values),
                        link -> "/go".equals(link) ? "https://example.com/?a=1&b=2" : link),
                "<a href=\"https://example.com/?a=1&amp;b=2\">/go</a><img src=\"https://example.com/a.png\">",
                "the link alone is retargeted, then escaped like any stored link");
        Assert.assertEquals(ContentRenderer.render(tpl, instance(values), link -> "javascript:alert(1)"),
                "<a href=\"\">/go</a><img src=\"https://example.com/a.png\">",
                "a target that is no link renders nothing, as a stored one would");
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

    /** A container's own title has a slot of its own; the STANDARD vocabulary never sees it. */
    @Test
    public void aContainerBodyCanPlaceItsOwnTitle() {
        Assert.assertTrue(ContentRenderer.hasContainerTitleSlot("<h2>{{container:title}}</h2>{{child}}"));
        Assert.assertTrue(ContentRenderer.hasContainerTitleSlot("<h2>{{ container:title }}</h2>"));
        Assert.assertFalse(ContentRenderer.hasContainerTitleSlot("<h2>{{child:title}}</h2>{{child}}"));
        Assert.assertFalse(ContentRenderer.hasContainerTitleSlot(null));
        Assert.assertEquals(ContentRenderer.tokenNames("{{container:title}}{{x}}"), Set.of("x"),
                "never offered by 'Detect from body'");

        final ContentInstance band = child("b1", "Trips & <b>more</b>");
        Assert.assertEquals(ContentRenderer.fillContainerTokens("<h2>{{container:title}}</h2>", band),
                "<h2>Trips &amp; <b>more</b></h2>", "markup is kept, cleaned (the ampersand is spelled out)");
        Assert.assertEquals(ContentRenderer.fillContainerTokens("<h2>{{container:title}}</h2>",
                child("b1", "  Trips & more ")), "<h2>Trips &amp; more</h2>", "plain text is escaped and trimmed");
        Assert.assertEquals(ContentRenderer.fillContainerTokens("<h2>{{container:title}}</h2>", child("b1", " ")),
                "<h2></h2>", "blank leaves the element empty for the stylesheet to hide");
        Assert.assertEquals(ContentRenderer.fillContainerTokens("<h2>{{container:title}}</h2>", null), "<h2></h2>");
        Assert.assertEquals(ContentRenderer.fillContainerTokens(null, band), "");
        Assert.assertEquals(ContentRenderer.fillContainerTokens("<p>no slot</p>", band), "<p>no slot</p>");
    }

    /** A LINK may point into the site or at an anchor; only fetched sources (images, video) stay http(s). */
    @Test
    public void linkUrlsAdmitSiteRelativePathsAndFragments() {
        Assert.assertEquals(ContentRenderer.requireLinkUrl("/account/createAccount.jsf"), "/account/createAccount.jsf");
        Assert.assertEquals(ContentRenderer.requireLinkUrl(" #benefits "), "#benefits");
        Assert.assertEquals(ContentRenderer.requireLinkUrl("https://example.com/x"), "https://example.com/x");
        Assert.assertEquals(ContentRenderer.requireLinkUrl("//evil.example/x"), "", "protocol-relative is refused");
        Assert.assertEquals(ContentRenderer.requireLinkUrl("/\\evil.example/x"), "",
                "browsers read a backslash as a slash here");
        Assert.assertEquals(ContentRenderer.requireLinkUrl("javascript:alert(1)"), "");
        Assert.assertEquals(ContentRenderer.requireLinkUrl("account/x.jsf"), "", "a bare relative path is not a link");
        Assert.assertEquals(ContentRenderer.requireLinkUrl(""), "");
        Assert.assertEquals(ContentRenderer.requireLinkUrl("   "), "");
        Assert.assertEquals(ContentRenderer.requireLinkUrl(null), "");
        Assert.assertEquals(ContentRenderer.requireLinkUrl("/"), "/", "the site root is a link");

        final ContentTemplate link = template("<a href=\"{{u}}\">x</a>", ph("u", Placeholder.Type.URL));
        Assert.assertEquals(ContentRenderer.render(link, instance(Map.of("u", "/trip/index.jsf?a=1&b=2"))),
                "<a href=\"/trip/index.jsf?a=1&amp;b=2\">x</a>", "still attribute-escaped");
        final ContentTemplate img = template("<img src=\"{{i}}\" />", ph("i", Placeholder.Type.IMAGE_URL));
        Assert.assertEquals(ContentRenderer.render(img, instance(Map.of("i", "/images/x.png"))),
                "<img src=\"\" />", "an image source stays absolute http(s)");
    }

    @Test
    public void requireHttpUrlHandlesEdgeCases() {
        Assert.assertEquals(ContentRenderer.requireHttpUrl("  https://x.example  "), "https://x.example");
        Assert.assertEquals(ContentRenderer.requireHttpUrl("http://x.example"), "http://x.example");
        Assert.assertEquals(ContentRenderer.requireHttpUrl("//x.example"), "");
        Assert.assertEquals(ContentRenderer.requireHttpUrl("::::"), "");
    }

    /** A length may sit inside a style attribute: only a CSS length gets there, anything else renders as nothing. */
    @Test
    public void aLengthPromptRendersOnlyALength() {
        final Placeholder height = new Placeholder("h", Placeholder.Type.TEXT, "H", null, false, null, null,
                Placeholder.FORMAT_CSS_LENGTH);
        final ContentTemplate tpl = template("<div style=\"height:{{h}}\"></div>", height);
        for (final String good : List.of("20px", "1.5em", "2rem", "50%", "10vh", "33vw", "0", " 12px ")) {
            Assert.assertEquals(ContentRenderer.render(tpl, instance(Map.of("h", good))),
                    "<div style=\"height:" + good.trim() + "\"></div>", good);
        }
        for (final String bad : List.of("20", "1px;position:fixed", "calc(1px)", "-5px", "20 px", "url(x)",
                "\"><script>", "123456px")) {
            Assert.assertEquals(ContentRenderer.render(tpl, instance(Map.of("h", bad))),
                    "<div style=\"height:\"></div>", bad);
        }
        Assert.assertTrue(Placeholder.isLength("4px"));
        Assert.assertFalse(Placeholder.isLength(null));
    }

    /** A menu value renders escaped, exactly like text: an old hand-typed value is never screened out. */
    @Test
    public void aMenuValueRendersEscapedAsTyped() {
        final Placeholder tone = new Placeholder("tone", Placeholder.Type.CHOICE, "Tone", null, false, null,
                List.of("plain", "dark"), null);
        final ContentTemplate tpl = template("<b class=\"band-{{tone}}\"></b>", tone);
        Assert.assertEquals(ContentRenderer.render(tpl, instance(Map.of("tone", "Dark x\""))),
                "<b class=\"band-Dark x&quot;\"></b>");
    }
}
