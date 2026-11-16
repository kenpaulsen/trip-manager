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

    /** Quill 2 keeps a bullet list and the numbered list after it in ONE ol: the written list switches kind. */
    @Test
    public void adjacentBulletsAndStepsEachKeepTheirKind() {
        assertEquals(ReminderHtml.clean("<ol><li data-list=\"bullet\">A</li><li data-list=\"ordered\">One</li>"
                + "<li data-list=\"ordered\">Two</li></ol>"),
                "<ul><li data-list=\"bullet\">A</li></ul><ol><li data-list=\"ordered\">One</li>"
                        + "<li data-list=\"ordered\">Two</li></ol>");
        assertEquals(ReminderHtml.clean("<ol><li data-list=\"ordered\">One</li><li data-list=\"bullet\">A</li></ol>"),
                "<ol><li data-list=\"ordered\">One</li></ol><ul><li data-list=\"bullet\">A</li></ul>");
        assertEquals(ReminderHtml.clean("<li data-list=\"bullet\">loose</li>"), "<li data-list=\"bullet\">loose</li>",
                "An item outside any list opens nothing");
        assertEquals(ReminderHtml.clean("<ol><link data-list=\"bullet\"><li data-list=\"ordered\">a</li></ol>"),
                "<ol><li data-list=\"ordered\">a</li></ol>", "Only a real li decides the kind");
    }

    /**
     * Bodies built to be slow, at the largest size a save hands the cleaner (ReminderCommands.MAX_RAW_BODY): each
     * made the cleaner quadratic before the tag scan was bounded, the list look-ahead read one tag only, and the
     * attribute pattern stopped retrying a name from inside itself (the last two bodies, 3.6 and 7.1 s before).
     */
    @Test(timeOut = 2_000)
    public void aHostileBodyAtTheSizeCapIsCleanedPromptly() {
        final int cap = ReminderCommands.MAX_RAW_BODY;
        assertEquals(ReminderHtml.clean("<a".repeat(cap / 2)), "&lt;a".repeat(cap / 2));
        assertEquals(ReminderHtml.clean("<svg></svg>".repeat(cap / 11)), "");
        assertEquals(ReminderHtml.clean("<ol>".repeat(cap / 4)), "<ol>".repeat(cap / 4));
        assertFalse(ReminderHtml.clean("<ol>".repeat(cap / 4 - 2) + "<li '\">").isEmpty());
        // Openings whose quote never closes before the end, with a ">" far away so each must be scanned.
        assertFalse(ReminderHtml.clean("<a \"".repeat(cap / 4 - 1) + "'>  ").isEmpty());
        assertFalse(ReminderHtml.clean("<a".repeat(cap / 2 - 1) + "\">").isEmpty());
        assertEquals(ReminderHtml.clean("x\u0130<script>a</script>y"), "x\u0130y", "A case that changes length");
        // Long tags of name characters with no "=": every one is a tag, read for attributes it does not have.
        assertEquals(ReminderHtml.clean(("<a " + "b".repeat(1990) + ">").repeat(100)), "<a>".repeat(100));
        assertFalse(ReminderHtml.clean(("<ol><li " + "b".repeat(1980) + "></ol>").repeat(99)).isEmpty());
        // The save's unreadable-tag check reads the same bodies first, and must be just as prompt.
        assertTrue(ReminderHtml.hasUnreadableTag("<a \"".repeat(cap / 4 - 1) + "'>  "));
        assertFalse(ReminderHtml.hasUnreadableTag(("<a " + "b".repeat(1990) + ">").repeat(100)));
    }

    /**
     * A pasted image is an {@code <img>} whose data address runs to thousands of characters, past what the cleaner
     * reads as a tag, so it would be written out as that many characters of text. The save refuses it, and the
     * cleaner counts it as a drop either way.
     */
    @Test
    public void aTagTooLongToReadIsFoundAndCountsAsDropped() {
        final String logo = "<p>Logo <img src=\"data:image/png;base64," + "A".repeat(ReminderHtml.MAX_TAG * 4)
                + "\"></p>";
        assertTrue(ReminderHtml.hasUnreadableTag(logo));
        assertTrue(ReminderHtml.dropsSomething(logo));
        assertTrue(ReminderHtml.clean(logo).contains("&lt;img src="), "Why the save refuses it");
        assertTrue(ReminderHtml.hasUnreadableTag("<p>an image never closed <img src=\"x\""));
        assertTrue(ReminderHtml.hasUnreadableTag("<p>x</p></" + "p".repeat(ReminderHtml.MAX_TAG) + " more"));
        assertFalse(ReminderHtml.hasUnreadableTag("<p><b>bold</b> <a href=\"https://example.com\">ok</a></p>"));
        assertFalse(ReminderHtml.hasUnreadableTag(null));
        assertFalse(ReminderHtml.hasUnreadableTag("plain < text, 1 <2"), "a < with no tag name is text");
        assertFalse(ReminderHtml.hasUnreadableTag("ends in <a"), "a bare name at the end is text, as the cleaner says");
        assertFalse(ReminderHtml.dropsSomething("ends in <a"));
        assertFalse(ReminderHtml.dropsSomething("plain < text"));
    }

    @Test
    public void oneCleanGivesTheBodyAndWhetherItDroppedSomething() {
        final String pasted = "<p><span style=\"color: red;\">Due</span></p>";
        assertEquals(ReminderHtml.cleanNoting(pasted), new ReminderHtml.Cleaned("<p><span>Due</span></p>", true));
        assertEquals(ReminderHtml.cleanNoting("<p>Hi</p>"), new ReminderHtml.Cleaned("<p>Hi</p>", false));
        assertEquals(ReminderHtml.cleanNoting(null), new ReminderHtml.Cleaned("", false));
    }

    @Test
    public void anAttributeIsReadOnlyFromTheStartOfItsName() {
        assertEquals(ReminderHtml.clean("<a xhref=\"https://example.com\">t</a>"), "<a>t</a>");
        assertEquals(ReminderHtml.clean("<a data-x=1 href = \"https://example.com\">t</a>"),
                "<a href=\"https://example.com\" rel=\"noopener noreferrer\" target=\"_blank\">t</a>");
    }

    @Test
    public void aLinkTooLongToBeATagIsFound() {
        final String longLink = "<p><a href=\"https://example.com/" + "x".repeat(ReminderHtml.MAX_TAG)
                + "\">Sign here</a></p>";
        assertTrue(ReminderHtml.hasUnreadableLink(longLink));
        assertTrue(ReminderHtml.clean(longLink).contains("&lt;a href="), "Why the save refuses it");
        assertTrue(ReminderHtml.hasUnreadableLink("<p>see <a href=\"https://example.com\""));
        assertFalse(ReminderHtml.hasUnreadableLink("<p><a href=\"https://example.com\">ok</a> <abbr " + "y".repeat(
                ReminderHtml.MAX_TAG) + "</p>"), "Only a link counts");
        assertFalse(ReminderHtml.hasUnreadableLink("<p><a>x</a><A HREF='https://example.com'>y</A></p>"));
        assertFalse(ReminderHtml.hasUnreadableLink(null));
        assertFalse(ReminderHtml.hasUnreadableLink("plain < text"));
        assertFalse(ReminderHtml.hasUnreadableLink("ends in <a"), "a bare <a at the end is text, as the cleaner says");
        assertEquals(ReminderHtml.clean("ends in <a"), "ends in &lt;a");
    }

    @Test
    public void somethingLongerThanATagIsText() {
        final String attribute = " title=\"" + "x".repeat(ReminderHtml.MAX_TAG) + "\"";
        assertTrue(ReminderHtml.clean("<p" + attribute + ">t</p>").startsWith("&lt;p title="));
    }

    @Test
    public void theStarterTextsKeepTheirLinksAndWording() {
        for (final ReminderDefaults.Text text : ReminderDefaults.TEXTS) {
            final String cleaned = ReminderHtml.clean(text.body());
            assertEquals(ReminderHtml.clean(cleaned), cleaned, text.id());
            assertTrue(cleaned.contains("href=\"{{"), text.id() + " keeps its link: " + cleaned);
            assertTrue(cleaned.contains("{{firstName}}"), text.id());
            assertFalse(ReminderHtml.dropsSomething(text.body()), text.id());
        }
    }

    /** What Quill 2 writes from the reminder toolbar, list markers and a link's own rel and target included. */
    @Test
    public void aBodyTheEditorWritesDropsNothing() {
        final String quill = "<h2>Getting ready</h2><p>Hello <strong>{{firstName}}</strong>, <em>soon</em> "
                + "<u>now</u> <s>never</s>.</p><p class=\"ql-align-center\">Center</p>"
                + "<ol><li data-list=\"bullet\"><span class=\"ql-ui\" contenteditable=\"false\"></span>Hat</li>"
                + "<li data-list=\"ordered\"><span class=\"ql-ui\" contenteditable=\"false\"></span>One</li></ol>"
                + "<blockquote>Quote</blockquote><p><br></p>"
                + "<p><a href=\"https://example.com/pay\" rel=\"noopener noreferrer\" target=\"_blank\">Pay</a> or "
                + "<a href=\"tel:+15551234567\" rel=\"noopener noreferrer\" target=\"_blank\">call</a> or "
                + "<a href=\"{{paymentLink}}\">here</a> 1 < 2 > 0 <!-- a note --></p>  ";
        assertFalse(ReminderHtml.dropsSomething(quill));
        assertFalse(ReminderHtml.clean(quill).equals(quill.trim()), "Normalized, so a comparison would misfire");
        assertFalse(ReminderHtml.dropsSomething(null));
        assertFalse(ReminderHtml.dropsSomething(""));
        assertFalse(ReminderHtml.dropsSomething("<p style=\"\" class=\"\">t</p>"), "Empty: nothing to see");
        assertFalse(ReminderHtml.dropsSomething("<a href=\"https://a.test\" href=\"javascript:x\">t</a>"),
                "A second address is ignored by a browser too");
    }

    @Test
    public void colorsImagesAndRefusedLinksAreReportedAsDropped() {
        for (final String lost : List.of("<p><span style=\"color: rgb(230, 0, 0);\">Due Friday</span></p>",
                "<p><span style=\"background-color: yellow;\">x</span></p>",
                "<p>Logo <img src=\"data:image/png;base64,AAAA\"></p>",
                "<iframe class=\"ql-video\" src=\"https://video.test/x\"></iframe>",
                "<p><a href=\"javascript:alert(1)\">x</a></p>", "<p><a href=\"example.com/pay\">pay</a></p>",
                "<p><a href=\"\">x</a></p>", "<p class=\"ql-hidden\">x</p>", "<table><tr><td>x</td></tr></table>",
                "text</font>")) {
            assertTrue(ReminderHtml.dropsSomething(lost), lost);
        }
    }
}
