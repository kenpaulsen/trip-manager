package org.paulsens.trip.action;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** {@link WaiverHtml}: what publish strips, what the PDF's text keeps, and the hash. */
public class WaiverHtmlTest {

    @Test
    public void sanitizeRemovesEverythingThatCanRun() {
        assertEquals(WaiverHtml.sanitize(null), "");
        assertEquals(WaiverHtml.sanitize("<p onclick=\"steal()\">Hi</p><script>x()</script>"), "<p>Hi</p>");
        assertEquals(WaiverHtml.sanitize("<a href=\"javascript:alert(1)\">x</a>"), "<a href=\"#\">x</a>");
        assertEquals(WaiverHtml.sanitize("<iframe src=\"https://e.com\"></iframe><p>ok</p>"), "<p>ok</p>");
        assertEquals(WaiverHtml.sanitize("<style>p{}</style><form><input name=a></form><b>kept</b>"),
                "<b>kept</b>");
        assertEquals(WaiverHtml.sanitize("<img src='data:image/png;base64,AAA'>"), "<img src=\"#\">");
    }

    @Test
    public void textKeepsParagraphsAndBullets() {
        assertEquals(WaiverHtml.toText(null), "");
        assertEquals(WaiverHtml.toText("<h2>Release</h2><p>I accept &amp; agree&nbsp;&mdash; fully.</p>"
                + "<ul><li>One</li><li>Two &#x2019;s &#8220;q&#8221;</li></ul><p>A<br>B</p>"),
                "Release\n\nI accept & agree — fully.\n\n• One\n• Two ’s “q”\n\nA\nB");
        assertEquals(WaiverHtml.toText("&lt;b&gt; &quot;x&quot; &apos;y&#39; &ndash; &lsquo;&rsquo;&ldquo;&rdquo;"
                + "&hellip; &#99999999;"), "<b> \"x\" 'y' – ‘’“”…");
        assertTrue(WaiverHtml.isBlank("<p><br></p>"));
        assertFalse(WaiverHtml.isBlank("<p>x</p>"));
    }

    @Test
    public void hashAndSlug() {
        assertEquals(WaiverHtml.sha256(""), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertEquals(WaiverHtml.sha256(null), WaiverHtml.sha256(""));
        assertEquals(WaiverHtml.slug("Code of Conduct!"), "code-of-conduct");
        assertEquals(WaiverHtml.slug(null), "document");
        assertEquals(WaiverHtml.slug("!!!"), "document");
    }
}
