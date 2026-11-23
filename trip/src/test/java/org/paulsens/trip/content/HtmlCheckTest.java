package org.paulsens.trip.content;

import java.util.List;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * {@link HtmlCheck}: what keeps a body from being stored as written, read as a browser reads it, and that ordinary
 * markup passes. Every attack body here was published by an earlier version of the check (reviews, 2026-11-23).
 */
public class HtmlCheckTest {

    private static final String UNFINISHED = "The text ends inside an unfinished tag, quoted value or comment.";

    /** A body a browser would run something in is refused, however its quotes and slashes are placed. */
    @Test
    public void aHandlerOrScriptAddressIsRefusedHoweverItIsWritten() {
        for (final String body : new String[] {
            "<p><a title=a'b x='>' onclick=alert(1)>x</a></p>",
            "<p><a title=a'b x='>' href=javascript:alert(1)>x</a></p>",
            "<p><img src=x title=it's alt='>' onerror=alert(1)></p>",
            "<p a=x\"y b=\"z> \" onmouseover=alert(1) x>hello</p>",
            "<a q=y\"z w=\"v> \" href=javascript:alert(document.domain)>click</a>",
            "<p ONCUSTOMTHING=x()>t</p>",
            "<a href=\" jav&#x61;script:x()\">t</a>",
            "<iframe srcdoc=\"x\"></iframe>",
            "<p>Terms</p><noembed><img title=\"</noembed><p oncontentvisibilityautostatechange=alert(1)>x</p>\">"
                + "</noembed>",
            "<p>Terms</p><noframes><img title=\"</noframes><p onfoo=alert(1)>x</p>\"></noframes>",
            "<p><a href=\"java&#x09;script:alert(1)\">t</a></p>",
            "<p><a href=\"java\tscript:alert(1)\">t</a></p>",
            "<a href='&#106avascript:alert(1)'>t</a>",
            "<a href='javascript&#58alert(1)'>t</a>",
            "<a href=\"java&Tab;script:alert(1)\">t</a>",
            "<a href=\"vbscript:x\">t</a>",
            "<p>Terms</p><listing><script>alert(1)</script></listing>",
            "<details open ontoggle=x()>t</details>",
            "<marquee>t</marquee>",
            // An attribute the old tokenizer folded into an unquoted value (review blocker F2).
            "<p>Terms</p><img src=x /onerror=alert(1)>",
            "<p>Terms</p><a href=# /onfocus=alert(1) /autofocus /tabindex=1>x</a>",
            "<p>Terms</p><a title=x /href=javascript:alert(1)>click</a>",
            "<p>Terms</p><img src=x /srcdoc=y>",
            "<p>Terms</p><p style=x /onclick=alert(1)>Read me</p>",
            "<p>Terms</p><img src=x\f/onerror=alert(1)>",
            "<p>Terms</p><img src=x\n/onerror=alert(1)>",
        }) {
            assertFalse(HtmlCheck.document(body).isEmpty(), body);
        }
        assertTrue(HtmlCheck.document("<p>Terms</p><img src=x /onerror=alert(1)>")
                .contains("The onerror attribute on <img>: an event handler runs script."),
                "the handler is named as the browser reads it, a separate attribute");
    }

    /**
     * A tag, quoted value or comment left open at the very end is closed by the page's own markup (review blocker
     * F1), and writing the old end marker first is no way around it (blocker, 2026-11-23): the parser reports the
     * end of the text inside it.
     */
    @Test
    public void aBodyEndingInsideATagAValueOrACommentIsUnfinished() {
        for (final String body : new String[] {
            "<p>Terms</p><img src=x onerror=\"alert(document.domain)\"",
            "<p>Terms</p><a href=\"javascript:alert(1)\"",
            "<p>Terms</p><iframe src=\"https://evil.example/\" ",
            "<p>Terms</p><!--",
            "<p>Terms</p><a href=\"https://x.org/\" title=\"",
            "<p>Terms</p><script ",
            "<p>Terms</p><x-end></x-end><!--",
            "<p>Terms</p><X-END></X-END><!--",
            "<p>Terms</p><x-end></x-end><a href=\"https://x.org/\" title=\"",
            "<p>Terms</p><x-end></x-end><a href=\"javascript:alert(1)//",
            "<p>Terms</p><x-end></x-end><img src=x onerror=\"alert(1)//",
            "<p class=\"unterminated>x</p>",
            "<p",
            "<!-- runaway comment <p>x</p>",
        }) {
            assertTrue(HtmlCheck.structure(body).contains(UNFINISHED), body + " gave " + HtmlCheck.structure(body));
        }
    }

    @Test
    public void ordinaryDocumentsPublish() {
        for (final String body : new String[] {
            "<p style=\"float:left\"><a href=\"https://x.org\" title=\"it's\">x</a></p>",
            "<img src=\"https://x.org/a.png\" alt=\"Data: 2026 figures\">",
            "<img src=\"https://x.org/a.png\" alt=\"Sign up online = free\"><p title=\"Option one = $40\">a</p>"
                + "<a href=\"https://x.org/a/one=1\">x</a>",
            "<table><tbody><tr><td colspan=\"2\"><b>Fee</b></td></tr></tbody></table>"
                + "<ul><li><em>One</em></li></ul><h2>Terms</h2><blockquote>Q</blockquote><hr>",
            "<p><a href=\"/trip/waivers.jsf?x=1#top\">a</a> <a href=\"mailto:a@b.org\">b</a>"
                + " <a href=\"tel:+15551234\">c</a> <img src=\"https://x.org/a.png\"></p>",
            "<p>Option one = $40 each, and online = free.</p>",
            "<p>Press <kbd>Ctrl</kbd>.</p><aside>A note</aside>",
            "<p>Fees &amp; costs: 5&nbsp;&euro; &copy; 2026, Tom &amp; Jerry, A & B</p>",
            "<p>Hello <strong>bold</strong><br></p><p><img src=\"https://x.org/a.png\""
                + " style=\"float:left;padding:4px\" alt=\"\"></p>",
            "<P>Upper <B>case</B></P>",
            "a < b, and 5 > 3",
            "<p>a</p><!-- parked -->",
        }) {
            assertEquals(HtmlCheck.document(body), List.of(), body);
        }
    }

    /** The end tags HTML lets an author leave out, void elements and a slash on one: no error, as in a browser. */
    @Test
    public void optionalEndTagsAndVoidElementsPass() {
        for (final String body : new String[] {
            "<p>one<p>two<ul><li>a<li>b<p>c<li>d</ul><br><br/><hr><img src=\"x.jpg\" />",
            "<table><colgroup><col><col></colgroup><tr><td>a<td>b<tr><td>c</table>",
            "<dl><dt>t<dd>d<dt>u</dl><div><p>closed by its parent</div>",
            "<table><tr><td><p>a<td><p>b</table>",
            "<table><tr><th><p>a<th><p>b</table>",
            "<table><tr><td><p>a<tr><td>b</table>",
        }) {
            assertEquals(HtmlCheck.structure(body), List.of(), body);
        }
    }

    /** A template body, a page section or the lodging policy may hold anything that is written soundly. */
    @Test
    public void structureLeavesContentAloneAndDocumentDoesNot() {
        for (final String body : new String[] {
            "plain text, no markup",
            "<div><ul><li>a</li><li>b</li></ul></div>",
            "<a href=\"/x?a=1&b=2\">x</a>",
            "<div title=\"a > b\">quoted angle</div>",
            "<script>if (a < b) { x(); }</script>",
            "<p>{{body}}</p>",
            "<style>p { color: red; }</style><p>x</p>",
        }) {
            assertNull(HtmlCheck.structureProblem(body), body);
        }
        assertNull(HtmlCheck.structureProblem(null));
        assertNull(HtmlCheck.structureProblem("   "));
        assertNull(HtmlCheck.templateProblem(null));
        assertNull(HtmlCheck.templateProblem("<td>{{a}}</td><td>{{b}}</td>"), "a cell is read inside its row");
        assertNull(HtmlCheck.templateProblem("<html><body><p>{{x}}</p></body></html>"));
        assertEquals(HtmlCheck.templateProblem("<td>{{a}}</td></table>"),
                "The </table> tag on line 1 does not close the tag open there.");
        final String body = "<script>x()</script><p onclick=\"y()\">t</p><a href=\"javascript:z()\">a</a>";
        assertEquals(HtmlCheck.structure(body), List.of(), "a template may hold script on purpose");
        assertEquals(HtmlCheck.document(body), List.of("The <script> tag: a published document cannot hold it.",
                "The onclick attribute on <p>: an event handler runs script.",
                "The href attribute on <a>: the address is not a web, mail or phone address."));
        assertEquals(HtmlCheck.document("<iframe srcdoc=\"x\"></iframe>"),
                List.of("The <iframe> tag: a published document cannot hold it.",
                        "The srcdoc attribute on <iframe>: a published document cannot hold it."));
    }

    /** Every starter template body a new organization gets is sound: the regression corpus for false alarms. */
    @Test
    public void theStarterBodiesAreSound() {
        for (final var starter : StarterTemplates.all()) {
            assertNull(HtmlCheck.structureProblem(starter.getBody()), "starter body: " + starter.getId());
        }
    }

    /**
     * One body per wording of jsoup's parse errors that {@link HtmlCheck} reads: an upgrade that rewords one fails
     * here, not on a page.
     */
    @Test
    public void eachParseErrorIsSaidInTheAuthorsWords() {
        assertEquals(HtmlCheck.structure("<p>Terms</p>\n</div>\n<p>x</p>"),
                List.of("The </div> tag on line 2 does not close the tag open there."),
                "a stray end tag escapes the page's box");
        assertTrue(HtmlCheck.structure("<div><b>oops</div></b>")
                .contains("The </b> tag on line 1 does not close the tag open there."));
        assertEquals(HtmlCheck.structure("<a href=\"/a\">x<a href=\"/b\">y</a></a>").getFirst(),
                "The <a> tag on line 1 cannot go there: a browser would move it or leave it out.");
        assertEquals(HtmlCheck.structure("<table>oops<tr><td>a</td></tr></table>"),
                List.of("Text on line 1 cannot go directly inside a table: a browser would move it out in front of"
                        + " the table."));
        assertEquals(HtmlCheck.structure("<p>a</p>\n\n<a href=\"https://x.org\">b"),
                List.of("The <a> tag on line 3 is never closed."));
        assertEquals(HtmlCheck.structure("<div><p>oops</p>"), List.of("The <div> tag on line 1 is never closed."));
        assertTrue(HtmlCheck.structure("<div/>a").contains("The <div/> tag on line 1 stays open: a slash closes"
                + " only an empty element such as <br/>. Write <div></div>."));
        assertEquals(HtmlCheck.structure("<a href='x'>&#106avascript</a>"),
                List.of("The character reference on line 1 (column 18) is incomplete: end it with a semicolon, or"
                        + " write & as &amp;."));
        assertEquals(HtmlCheck.structure("<a title=a'b>x</a>"),
                List.of("The tag on line 1 (column 12) is written in a way a browser reads differently: check its"
                        + " quotes, slashes and spaces."));
        assertTrue(HtmlCheck.structure("<!DOCTYPE html><p>x</p>").getFirst().startsWith("Line 1, column"),
                "anything else is named by its place");
        assertFalse(HtmlCheck.structure("</p> came from nowhere").isEmpty());
        assertFalse(HtmlCheck.structure("<script>never ends").isEmpty());
    }

    @Test
    public void eachProblemIsListedOnceWithACountAndTheListIsCapped() {
        assertEquals(HtmlCheck.document("<marquee>a</marquee><marquee>b</marquee>"),
                List.of("The <marquee> tag: a published document cannot hold it. (2 times)"));
        final List<String> many = HtmlCheck.structure("<p>x</p>\n</div>".repeat(15));
        assertEquals(many.size(), HtmlCheck.MAX_PROBLEMS + 1);
        assertEquals(many.getFirst(), "The </div> tag on line 2 does not close the tag open there.");
        assertEquals(many.getLast(), "And 5 more problems.");
        assertEquals(HtmlCheck.document("<" + "a".repeat(100) + ">x</" + "a".repeat(100) + ">").getFirst(),
                "The <" + "a".repeat(40) + "\u2026> tag: a published document cannot hold it.",
                "a crafted name is cut");
        assertEquals(HtmlCheck.structure("<table></table>".repeat(HtmlCheck.MAX_TABLES + 1)),
                List.of("The text holds more than 200 tables, more than any document needs."));
    }

    /**
     * jsoup stops recording parse errors at a cap without saying so, and a lone {@code <} is one it records that the
     * check skips: a body padded with them hid the error after them and published (review blocker, 2026-11-24). A
     * full list now refuses.
     */
    @Test
    public void harmlessErrorsCannotHideADangerousOne() {
        final String padding = "a < b ".repeat(HtmlCheck.MAX_ERRORS);
        final String tooMany = "The text has more than 1000 places a browser would have to repair, too many to check."
                + " If it has many < signs in plain text, write each one as &lt;.";
        for (final String body : new String[] {
            padding + "<p>x</p><a title='",
            "<p>" + padding + "</p><p>Terms</p><img src=x onerror=\"alert(1)\"",
            padding + "</div></div><a href=\"https://evil.example/\">click",
        }) {
            assertTrue(HtmlCheck.document(body).contains(tooMany), body.substring(padding.length()));
            assertTrue(HtmlCheck.structure(body).contains(tooMany));
        }
        assertEquals(HtmlCheck.document("a < b ".repeat(HtmlCheck.MAX_ERRORS - 1)), List.of(),
                "fewer harmless ones still publish");
        assertEquals(HtmlCheck.document("<p>a <\nb</p>"), List.of(), "a < before a line break is text too");
    }

    /**
     * One parse, never repeated: each body built to make an earlier check run for seconds or minutes on a request
     * thread (reviews, 2026-11-23) is read at once. Each is under the waiver's 200,000-character cap.
     */
    @Test(timeOut = 5_000)
    public void craftedBodiesAreReadFast() {
        for (final String body : new String[] {
            "<p>Terms</p>" + "<scr".repeat(24_000) + "<script>" + "ipt>".repeat(24_000),
            "<!-->" + "<b></b>".repeat(28_570),
            "<div>".repeat(39_000),
            "<div>".repeat(20_000) + "<li>".repeat(24_000),
            "<span>".repeat(16_000) + "<p>".repeat(30_000),
            "<div>".repeat(14_000) + "<b>".repeat(14_000) + "</div>".repeat(14_000),
            "<a>".repeat(66_000),
            "<table><tr><td>".repeat(HtmlCheck.MAX_TABLES) + "x",
        }) {
            assertFalse(HtmlCheck.document(body).isEmpty());
        }
    }
}
