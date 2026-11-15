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

    /** What the page's editor really posts: Quill 2 writes every list as ol and marks bullets on the item. */
    @Test
    public void aQuillBulletListStaysABulletListForTheEditorAndTheMailClient() {
        final String bullets = "<ol><li data-list=\"bullet\"><span class=\"ql-ui\" contenteditable=\"false\"></span>"
                + "Passport</li><li data-list=\"bullet\">Ticket</li></ol>";
        final String cleaned = ReminderHtml.clean(bullets);
        assertEquals(cleaned, "<ul><li data-list=\"bullet\"><span class=\"ql-ui\"></span>Passport</li>"
                + "<li data-list=\"bullet\">Ticket</li></ul>");
        assertEquals(ReminderHtml.clean(cleaned), cleaned);
        final String numbers = "<ol><li data-list=\"ordered\">One</li></ol>";
        assertEquals(ReminderHtml.clean(numbers), numbers);
        assertEquals(ReminderHtml.clean("<OL><LI DATA-LIST='bullet'>x</LI></OL>"),
                "<ul><li data-list=\"bullet\">x</li></ul>");
        assertEquals(ReminderHtml.clean("<ol><li data-list=\"evil\" onclick=x>x</li></ol></ol>"),
                "<ol><li>x</li></ol></ol>", "An unknown kind is dropped; a stray closing tag stays a list tag");
        assertEquals(ReminderHtml.clean("<ol>no items</ol>"), "<ol>no items</ol>");
        assertEquals(ReminderHtml.clean("<p data-list=\"bullet\">x</p>"), "<p>x</p>", "Only on a list item");
    }

    @Test
    public void aQuillClassThatHidesTextFromTheReviewerIsDropped() {
        assertEquals(ReminderHtml.clean("<div class=\"ql-snow\"><p class=\"ql-hidden\">secret</p></div>"),
                "<div><p>secret</p></div>");
        assertEquals(ReminderHtml.clean("<p class=\"ql-clipboard\">secret</p>"), "<p>secret</p>");
        assertEquals(ReminderHtml.clean("<p class=\"ql-size-huge ql-font-serif ql-direction-rtl\">t</p>"),
                "<p class=\"ql-size-huge ql-font-serif ql-direction-rtl\">t</p>");
        assertEquals(ReminderHtml.clean("<p class=\"ql-indent-9\">t</p>"), "<p>t</p>");
    }

    @Test
    public void phoneLinksAndAnApostropheInAnAddressAreKept() {
        assertTrue(ReminderHtml.isSafeAddress("tel:+15035551212"));
        assertTrue(ReminderHtml.isSafeAddress("sms:+15035551212"));
        assertTrue(ReminderHtml.isSafeAddress("https://example.com/o'brien"));
        assertEquals(ReminderHtml.clean("<a href=\"https://example.com/o'brien\">x</a>"),
                "<a href=\"https://example.com/o'brien\" rel=\"noopener noreferrer\" target=\"_blank\">x</a>");
    }

    /** The largest body a save hands the cleaner (ReminderCommands.MAX_RAW_BODY), built to be slow. */
    @Test(timeOut = 20_000)
    public void aHostileBodyAtTheSizeCapIsCleanedPromptly() {
        final int cap = ReminderCommands.MAX_RAW_BODY;
        assertEquals(ReminderHtml.clean("<a".repeat(cap / 2)), "&lt;a".repeat(cap / 2));
        assertEquals(ReminderHtml.clean("<svg></svg>".repeat(cap / 11)), "");
        assertEquals(ReminderHtml.clean("<a \"".repeat(cap / 4) + ">").length() > 0, true);
        assertEquals(ReminderHtml.clean("x\u0130<script>a</script>y"), "x\u0130y", "A case that changes length");
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
