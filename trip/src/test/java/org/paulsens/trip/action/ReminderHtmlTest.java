package org.paulsens.trip.action;

import java.util.List;
import org.paulsens.trip.content.ReminderDefaults;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** The reminder body's allowlist: what Quill writes survives, and nothing that can run does. */
public class ReminderHtmlTest {

    @Test
    public void theThreeBodiesThatPassedTheOldBlocklistComeOutInert() {
        assertEquals(ReminderHtml.clean("<p>Hi</p><img src=\"x\"onerror=\"fetch('/x')\">"), "<p>Hi</p>");
        assertEquals(ReminderHtml.clean("a<svg/onload=alert(1)>b"), "a", "An unclosed svg takes the rest with it");
        assertEquals(ReminderHtml.clean("a<svg/onload=alert(1)></svg>b"), "ab");
        assertEquals(ReminderHtml.clean("<a href=\"jav&#97;script:alert(1)\">x</a>"), "<a>x</a>");
    }

    @Test
    public void noEventHandlerStyleOrUnknownAttributeIsEverCopied() {
        assertEquals(ReminderHtml.clean("<p onclick=\"x()\" style=\"color:red\" id=a data-x=1>t</p>"), "<p>t</p>");
        assertEquals(ReminderHtml.clean("<b/onmouseover=x()>t</b>"), "<b>t</b>");
        assertEquals(ReminderHtml.clean("<a href='https://x.test' onclick='x()'>t</a>"),
                "<a href=\"https://x.test\" rel=\"noopener noreferrer\" target=\"_blank\">t</a>");
        assertEquals(ReminderHtml.clean("<p class=\"ql-align-center evil\">t</p>"), "<p>t</p>");
        assertEquals(ReminderHtml.clean("<p class=\"ql-align-center ql-indent-1\">t</p>"),
                "<p class=\"ql-align-center ql-indent-1\">t</p>");
    }

    @Test
    public void aLinkGoesOnlyToAWebAddressAMailboxOrALinkPlaceholder() {
        for (final String good : List.of("https://example.com/a?b=1&amp;c=2", "HTTP://example.com",
                "mailto:lead@example.com", "{{paymentLink}}", "{{tripLink}}")) {
            assertTrue(ReminderHtml.isSafeAddress(good), good);
        }
        for (final String bad : List.of("javascript:alert(1)", "JaVaScRiPt:alert(1)", "jav&#97;script:alert(1)",
                "data:text/html,x", "vbscript:x", "//example.com", "/relative", " https://example.com",
                "https://example.com x", "{{firstName}}", "{{paymentLink}}x", "")) {
            assertFalse(ReminderHtml.isSafeAddress(bad), bad);
        }
        assertEquals(ReminderHtml.clean("<a href=\"https://a.test\" href=\"javascript:x\">t</a>"),
                "<a href=\"https://a.test\" rel=\"noopener noreferrer\" target=\"_blank\">t</a>");
    }

    @Test
    public void codeAndFrameTagsGoWithTheirContent() {
        assertEquals(ReminderHtml.clean("a<script>bad()</script>b<STYLE>p{}</STYLE>c<iframe src=x></iframe>d"), "abcd");
        assertEquals(ReminderHtml.clean("a<script>never closed"), "a");
        assertEquals(ReminderHtml.clean("a<!-- note -->b<!-- open"), "ab");
    }

    @Test
    public void unknownTagsAreDroppedAndTheirTextKept() {
        assertEquals(ReminderHtml.clean("<table><tr><td>cell</td></tr></table><font color=red>x</font>"), "cellx");
        assertEquals(ReminderHtml.clean("<img src=\"https://x.test/a.png\">after"), "after");
        assertEquals(ReminderHtml.clean("<form action=x><input name=a><button>go</button></form>"), "go");
    }

    @Test
    public void strayAngleBracketsBecomeText() {
        assertEquals(ReminderHtml.clean("1 < 2 > 1"), "1 &lt; 2 &gt; 1");
        assertEquals(ReminderHtml.clean("<p>a <b"), "<p>a &lt;b");
        assertEquals(ReminderHtml.clean("<<script>x</script>p>"), "&lt;p&gt;");
        assertEquals(ReminderHtml.clean("<!doctype html>t"), "&lt;!doctype html&gt;t");
        assertEquals(ReminderHtml.clean(null), "");
    }

    @Test
    public void whatQuillWritesSurvivesAndCleaningTwiceChangesNothing() {
        final String quill = "<h2>Hello</h2><p>Hi <strong>{{firstName}}</strong>, <em>see</em> <u>the</u> <s>old</s> "
                + "<a href=\"{{tripLink}}\" rel=\"noopener noreferrer\" target=\"_blank\">trip</a>.</p>"
                + "<ol><li>One</li></ol><ul><li class=\"ql-indent-1\">Two</li></ul><blockquote>Quote</blockquote>"
                + "<p><br></p><p>A &amp; B &lt; C</p>";
        assertEquals(ReminderHtml.clean(quill), quill);
        final String messy = "<P CLASS='x'>t<BR/></P><hr></hr><svg><p>gone</p></svg> 2 > 1";
        final String once = ReminderHtml.clean(messy);
        assertEquals(once, "<p>t<br></p><hr> 2 &gt; 1");
        assertEquals(ReminderHtml.clean(once), once);
    }

    @Test
    public void theStarterTextsKeepTheirLinksAndWording() {
        for (final ReminderDefaults.Text text : ReminderDefaults.TEXTS) {
            final String cleaned = ReminderHtml.clean(text.body());
            assertEquals(ReminderHtml.clean(cleaned), cleaned, text.id());
            assertTrue(cleaned.contains("href=\"{{"), text.id() + " keeps its link: " + cleaned);
            assertTrue(cleaned.contains("{{firstName}}"), text.id());
        }
    }
}
