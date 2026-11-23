package org.paulsens.trip.action;

import java.util.List;
import org.paulsens.trip.content.ReminderDefaults;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** The reminder body's allowlist: what Quill writes survives, and nothing that can run does. */
public class RichTextHtmlTest {

    @Test
    public void theThreeBodiesThatPassedTheOldBlocklistComeOutInert() {
        assertEquals(RichTextHtml.clean("<p>Hi</p><img src=\"x\"onerror=\"fetch('/x')\">"), "<p>Hi</p>");
        assertEquals(RichTextHtml.clean("a<svg/onload=alert(1)>b"), "a", "An unclosed svg takes the rest with it");
        assertEquals(RichTextHtml.clean("a<svg/onload=alert(1)></svg>b"), "ab");
        assertEquals(RichTextHtml.clean("<a href=\"jav&#97;script:alert(1)\">x</a>"), "<a>x</a>");
    }

    @Test
    public void noEventHandlerStyleOrUnknownAttributeIsEverCopied() {
        assertEquals(RichTextHtml.clean("<p onclick=\"x()\" style=\"color:red\" id=a data-x=1>t</p>"),
                "<p style=\"color: red\">t</p>", "a plain color is kept, rebuilt; nothing else is copied");
        assertEquals(RichTextHtml.clean("<p style=\"background:url(https://x.test/a.png)\">t</p>"), "<p>t</p>");
        assertEquals(RichTextHtml.clean("<b/onmouseover=x()>t</b>"), "<b>t</b>");
        assertEquals(RichTextHtml.clean("<a href='https://x.test' onclick='x()'>t</a>"),
                "<a href=\"https://x.test\" rel=\"noopener noreferrer\" target=\"_blank\">t</a>");
        assertEquals(RichTextHtml.clean("<p class=\"ql-align-center evil\">t</p>"), "<p>t</p>");
        assertEquals(RichTextHtml.clean("<p class=\"ql-align-center ql-indent-1\">t</p>"),
                "<p class=\"ql-align-center ql-indent-1\">t</p>");
    }

    @Test
    public void aLinkGoesOnlyToAWebAddressAMailboxOrALinkPlaceholder() {
        for (final String good : List.of("https://example.com/a?b=1&amp;c=2", "HTTP://example.com",
                "mailto:lead@example.com", "{{paymentLink}}", "{{tripLink}}")) {
            assertTrue(RichTextHtml.isSafeAddress(good), good);
        }
        for (final String bad : List.of("javascript:alert(1)", "JaVaScRiPt:alert(1)", "jav&#97;script:alert(1)",
                "data:text/html,x", "vbscript:x", "//example.com", "/relative", " https://example.com",
                "https://example.com x", "{{firstName}}", "{{paymentLink}}x", "")) {
            assertFalse(RichTextHtml.isSafeAddress(bad), bad);
        }
        assertEquals(RichTextHtml.clean("<a href=\"https://a.test\" href=\"javascript:x\">t</a>"),
                "<a href=\"https://a.test\" rel=\"noopener noreferrer\" target=\"_blank\">t</a>");
    }

    @Test
    public void codeAndFrameTagsGoWithTheirContent() {
        assertEquals(RichTextHtml.clean("a<script>bad()</script>b<STYLE>p{}</STYLE>c<iframe src=x></iframe>d"), "abcd");
        assertEquals(RichTextHtml.clean("a<script>never closed"), "a");
        assertEquals(RichTextHtml.clean("a<!-- note -->b<!-- open"), "ab");
    }

    /**
     * Mail keeps what mail clients show and the mail editors offer (owner, 2026-11-23): an alignment, an indent, and
     * a table with its widths and cell spans, each rebuilt; nothing else on them is copied.
     */
    @Test
    public void mailKeepsAlignmentIndentAndTables() {
        assertEquals(RichTextHtml.clean("<p style=\"text-align: center;\">Hi</p><p style=\"margin-left: 40px\">In</p>"),
                "<p style=\"text-align: center\">Hi</p><p style=\"margin-left: 40px\">In</p>");
        final String table = "<table style=\"border-collapse: collapse; width: 100%;\" onclick=\"x()\"><tbody><tr>"
                + "<th colspan=\"2\" style=\"width: 50%\" class=\"x\">Day</th></tr><tr><td rowspan=\"999\">1</td>"
                + "<td style=\"vertical-align: top\">Mass</td></tr></tbody></table>";
        assertEquals(RichTextHtml.clean(table), "<table style=\"border-collapse: collapse; width: 100%\"><tbody><tr>"
                + "<th colspan=\"2\" style=\"width: 50%\">Day</th></tr><tr><td>1</td>"
                + "<td style=\"vertical-align: top\">Mass</td></tr></tbody></table>");
        assertTrue(dropped(table), "the handler and the class went, and the save says so");
        assertFalse(dropped("<table><tr><td colspan=\"2\">x</td></tr></table>"));
    }

    /**
     * Mail fills its fields everywhere in the body, attributes included: a field inside an image's address would send
     * each recipient's own value to the writer's host, fetched with no click (review blocker, 2026-11-23). An address
     * holding a brace is refused; a field stands as a whole link only.
     */
    @Test
    public void anAddressHoldingAFieldIsRefusedAndAWholeLinkFieldIsKept() {
        final String pixel = "<p>Hi {{firstName}}</p><img src=\"https://collect.example/p.gif?b={{balanceDue}}"
                + "&p={{passportIssue}}\" width=\"1\" height=\"1\">";
        assertEquals(RichTextHtml.clean(pixel), "<p>Hi {{firstName}}</p>");
        assertTrue(dropped(pixel), "and the save says so");
        assertEquals(RichTextHtml.clean("<a href=\"https://x.org/pay?id={{personId}}\">pay</a>"), "<a>pay</a>");
        assertEquals(RichTextHtml.clean("<a href=\"{{paymentLink}}\">pay</a>"),
                "<a href=\"{{paymentLink}}\" rel=\"noopener noreferrer\" target=\"_blank\">pay</a>");
        assertEquals(RichTextHtml.cleanEventNote("<p><a href=\"https://example.org/q?f={a}\">map</a></p>"),
                "<p><a href=\"https://example.org/q?f={a}\" rel=\"noopener noreferrer\" target=\"_blank\">map</a>"
                        + "</p>", "a note fills no field and keeps a brace, as stored notes always did");
        assertEquals(RichTextHtml.clean("<td style=\"width: 33.3333%;\">a</td>").contains("33.3333%"), true,
                "the editor writes a third of a table with four decimals");
    }

    @Test
    public void unknownTagsAreDroppedAndTheirTextKept() {
        assertEquals(RichTextHtml.clean("<table><tr><td>cell</td></tr></table><font color=red>x</font>"),
                "<table><tr><td>cell</td></tr></table><span style=\"color: red\">x</span>",
                "mail keeps a table (2026-11-23)");
        assertEquals(RichTextHtml.cleanNote("<table><tr><td>cell</td></tr></table>"), "cell", "a note keeps none");
        assertEquals(RichTextHtml.clean("<img src=\"https://x.test/a.png\">after"),
                "<img src=\"https://x.test/a.png\">after", "mail keeps an image by web address (2026-11-23)");
        assertEquals(RichTextHtml.clean("<form action=x><input name=a><button>go</button></form>"), "go");
    }

    @Test
    public void strayAngleBracketsBecomeText() {
        assertEquals(RichTextHtml.clean("1 < 2 > 1"), "1 &lt; 2 &gt; 1");
        assertEquals(RichTextHtml.clean("<p>a <b"), "<p>a &lt;b");
        assertEquals(RichTextHtml.clean("<<script>x</script>p>"), "&lt;p&gt;");
        assertEquals(RichTextHtml.clean("<!doctype html>t"), "&lt;!doctype html&gt;t");
        assertEquals(RichTextHtml.clean(null), "");
    }

    @Test
    public void whatQuillWritesSurvivesAndCleaningTwiceChangesNothing() {
        final String quill = "<h2>Hello</h2><p>Hi <strong>{{firstName}}</strong>, <em>see</em> <u>the</u> <s>old</s> "
                + "<a href=\"{{tripLink}}\" rel=\"noopener noreferrer\" target=\"_blank\">trip</a>.</p>"
                + "<ol><li>One</li></ol><ul><li class=\"ql-indent-1\">Two</li></ul><blockquote>Quote</blockquote>"
                + "<p><br></p><p>A &amp; B &lt; C</p>";
        assertEquals(RichTextHtml.clean(quill), quill);
        final String messy = "<P CLASS='x'>t<BR/></P><hr></hr><svg><p>gone</p></svg> 2 > 1";
        final String once = RichTextHtml.clean(messy);
        assertEquals(once, "<p>t<br></p><hr> 2 &gt; 1");
        assertEquals(RichTextHtml.clean(once), once);
    }

    /** What the page's editor really posts: Quill 2 writes every list as ol and marks bullets on the item. */
    @Test
    public void aQuillBulletListStaysABulletListForTheEditorAndTheMailClient() {
        final String bullets = "<ol><li data-list=\"bullet\"><span class=\"ql-ui\" contenteditable=\"false\"></span>"
                + "Passport</li><li data-list=\"bullet\">Ticket</li></ol>";
        final String cleaned = RichTextHtml.clean(bullets);
        assertEquals(cleaned, "<ul><li data-list=\"bullet\"><span class=\"ql-ui\"></span>Passport</li>"
                + "<li data-list=\"bullet\">Ticket</li></ul>");
        assertEquals(RichTextHtml.clean(cleaned), cleaned);
        final String numbers = "<ol><li data-list=\"ordered\">One</li></ol>";
        assertEquals(RichTextHtml.clean(numbers), numbers);
        assertEquals(RichTextHtml.clean("<OL><LI DATA-LIST='bullet'>x</LI></OL>"),
                "<ul><li data-list=\"bullet\">x</li></ul>");
        assertEquals(RichTextHtml.clean("<ol><li data-list=\"evil\" onclick=x>x</li></ol></ol>"),
                "<ol><li>x</li></ol></ol>", "An unknown kind is dropped; a stray closing tag stays a list tag");
        assertEquals(RichTextHtml.clean("<ol>no items</ol>"), "<ol>no items</ol>");
        assertEquals(RichTextHtml.clean("<p data-list=\"bullet\">x</p>"), "<p>x</p>", "Only on a list item");
    }

    @Test
    public void aQuillClassThatHidesTextFromTheReviewerIsDropped() {
        assertEquals(RichTextHtml.clean("<div class=\"ql-snow\"><p class=\"ql-hidden\">secret</p></div>"),
                "<div><p>secret</p></div>");
        assertEquals(RichTextHtml.clean("<p class=\"ql-clipboard\">secret</p>"), "<p>secret</p>");
        assertEquals(RichTextHtml.clean("<p class=\"ql-size-huge ql-font-serif ql-direction-rtl\">t</p>"),
                "<p class=\"ql-size-huge ql-font-serif ql-direction-rtl\">t</p>");
        assertEquals(RichTextHtml.clean("<p class=\"ql-indent-9\">t</p>"), "<p>t</p>");
    }

    @Test
    public void phoneLinksAndAnApostropheInAnAddressAreKept() {
        assertTrue(RichTextHtml.isSafeAddress("tel:+15035551212"));
        assertTrue(RichTextHtml.isSafeAddress("sms:+15035551212"));
        assertTrue(RichTextHtml.isSafeAddress("https://example.com/o'brien"));
        assertEquals(RichTextHtml.clean("<a href=\"https://example.com/o'brien\">x</a>"),
                "<a href=\"https://example.com/o'brien\" rel=\"noopener noreferrer\" target=\"_blank\">x</a>");
    }

    /** Quill 2 keeps a bullet list and the numbered list after it in ONE ol: the written list switches kind. */
    @Test
    public void adjacentBulletsAndStepsEachKeepTheirKind() {
        assertEquals(RichTextHtml.clean("<ol><li data-list=\"bullet\">A</li><li data-list=\"ordered\">One</li>"
                + "<li data-list=\"ordered\">Two</li></ol>"),
                "<ul><li data-list=\"bullet\">A</li></ul><ol><li data-list=\"ordered\">One</li>"
                        + "<li data-list=\"ordered\">Two</li></ol>");
        assertEquals(RichTextHtml.clean("<ol><li data-list=\"ordered\">One</li><li data-list=\"bullet\">A</li></ol>"),
                "<ol><li data-list=\"ordered\">One</li></ol><ul><li data-list=\"bullet\">A</li></ul>");
        assertEquals(RichTextHtml.clean("<li data-list=\"bullet\">loose</li>"), "<li data-list=\"bullet\">loose</li>",
                "An item outside any list opens nothing");
        assertEquals(RichTextHtml.clean("<ol><link data-list=\"bullet\"><li data-list=\"ordered\">a</li></ol>"),
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
        assertEquals(RichTextHtml.clean("<a".repeat(cap / 2)), "&lt;a".repeat(cap / 2));
        assertEquals(RichTextHtml.clean("<svg></svg>".repeat(cap / 11)), "");
        assertEquals(RichTextHtml.clean("<ol>".repeat(cap / 4)), "<ol>".repeat(cap / 4));
        assertFalse(RichTextHtml.clean("<ol>".repeat(cap / 4 - 2) + "<li '\">").isEmpty());
        // Openings whose quote never closes before the end, with a ">" far away so each must be scanned.
        assertFalse(RichTextHtml.clean("<a \"".repeat(cap / 4 - 1) + "'>  ").isEmpty());
        assertFalse(RichTextHtml.clean("<a".repeat(cap / 2 - 1) + "\">").isEmpty());
        assertEquals(RichTextHtml.clean("x\u0130<script>a</script>y"), "x\u0130y", "A case that changes length");
        // Long tags of name characters with no "=": every one is a tag, read for attributes it does not have.
        assertEquals(RichTextHtml.clean(("<a " + "b".repeat(1990) + ">").repeat(100)), "<a>".repeat(100));
        assertFalse(RichTextHtml.clean(("<ol><li " + "b".repeat(1980) + "></ol>").repeat(99)).isEmpty());
        // The save's unreadable-tag check reads the same bodies first, and must be just as prompt.
        assertTrue(RichTextHtml.hasUnreadableTag("<a \"".repeat(cap / 4 - 1) + "'>  "));
        assertFalse(RichTextHtml.hasUnreadableTag(("<a " + "b".repeat(1990) + ">").repeat(100)));
    }

    /**
     * A pasted image is an {@code <img>} whose data address runs to thousands of characters, past what the cleaner
     * reads as a tag, so it would be written out as that many characters of text. The save refuses it, and the
     * cleaner counts it as a drop either way.
     */
    @Test
    public void aTagTooLongToReadIsFoundAndCountsAsDropped() {
        final String logo = "<p>Logo <img src=\"data:image/png;base64," + "A".repeat(RichTextHtml.MAX_TAG * 4)
                + "\"></p>";
        assertTrue(RichTextHtml.hasUnreadableTag(logo));
        assertTrue(dropped(logo));
        assertTrue(RichTextHtml.clean(logo).contains("&lt;img src="), "Why the save refuses it");
        assertTrue(RichTextHtml.hasUnreadableTag("<p>an image never closed <img src=\"x\""));
        assertTrue(RichTextHtml.hasUnreadableTag("<p>x</p></" + "p".repeat(RichTextHtml.MAX_TAG) + " more"));
        assertFalse(RichTextHtml.hasUnreadableTag("<p><b>bold</b> <a href=\"https://example.com\">ok</a></p>"));
        assertFalse(RichTextHtml.hasUnreadableTag(null));
        assertFalse(RichTextHtml.hasUnreadableTag("plain < text, 1 <2"), "a < with no tag name is text");
        assertFalse(RichTextHtml.hasUnreadableTag("ends in <a"), "a bare name at the end is text, as the cleaner says");
        assertFalse(dropped("ends in <a"));
        assertFalse(dropped("plain < text"));
    }

    @Test
    public void oneCleanGivesTheBodyAndWhetherItDroppedSomething() {
        final String pasted = "<p><span style=\"color: red; font-family: Comic Sans\">Due</span></p>";
        assertEquals(RichTextHtml.cleanNoting(pasted),
                new RichTextHtml.Cleaned("<p><span style=\"color: red\">Due</span></p>", true));
        assertEquals(RichTextHtml.cleanNoting("<p>Hi</p>"), new RichTextHtml.Cleaned("<p>Hi</p>", false));
        assertEquals(RichTextHtml.cleanNoting(null), new RichTextHtml.Cleaned("", false));
    }

    @Test
    public void anAttributeIsReadOnlyFromTheStartOfItsName() {
        assertEquals(RichTextHtml.clean("<a xhref=\"https://example.com\">t</a>"), "<a>t</a>");
        assertEquals(RichTextHtml.clean("<a data-x=1 href = \"https://example.com\">t</a>"),
                "<a href=\"https://example.com\" rel=\"noopener noreferrer\" target=\"_blank\">t</a>");
    }

    @Test
    public void aLinkTooLongToBeATagIsFound() {
        final String longLink = "<p><a href=\"https://example.com/" + "x".repeat(RichTextHtml.MAX_TAG)
                + "\">Sign here</a></p>";
        assertTrue(RichTextHtml.hasUnreadableLink(longLink));
        assertTrue(RichTextHtml.clean(longLink).contains("&lt;a href="), "Why the save refuses it");
        assertTrue(RichTextHtml.hasUnreadableLink("<p>see <a href=\"https://example.com\""));
        assertFalse(RichTextHtml.hasUnreadableLink("<p><a href=\"https://example.com\">ok</a> <abbr " + "y".repeat(
                RichTextHtml.MAX_TAG) + "</p>"), "Only a link counts");
        assertFalse(RichTextHtml.hasUnreadableLink("<p><a>x</a><A HREF='https://example.com'>y</A></p>"));
        assertFalse(RichTextHtml.hasUnreadableLink(null));
        assertFalse(RichTextHtml.hasUnreadableLink("plain < text"));
        assertFalse(RichTextHtml.hasUnreadableLink("ends in <a"), "a bare <a at the end is text, as the cleaner says");
        assertEquals(RichTextHtml.clean("ends in <a"), "ends in &lt;a");
    }

    @Test
    public void somethingLongerThanATagIsText() {
        final String attribute = " title=\"" + "x".repeat(RichTextHtml.MAX_TAG) + "\"";
        assertTrue(RichTextHtml.clean("<p" + attribute + ">t</p>").startsWith("&lt;p title="));
    }

    @Test
    public void theStarterTextsKeepTheirLinksAndWording() {
        for (final ReminderDefaults.Text text : ReminderDefaults.TEXTS) {
            final String cleaned = RichTextHtml.clean(text.body());
            assertEquals(RichTextHtml.clean(cleaned), cleaned, text.id());
            assertTrue(cleaned.contains("href=\"{{"), text.id() + " keeps its link: " + cleaned);
            assertTrue(cleaned.contains("{{firstName}}"), text.id());
            assertFalse(dropped(text.body()), text.id());
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
        assertFalse(dropped(quill));
        assertFalse(RichTextHtml.clean(quill).equals(quill.trim()), "Normalized, so a comparison would misfire");
        assertFalse(dropped(null));
        assertFalse(dropped(""));
        assertFalse(dropped("<p style=\"\" class=\"\">t</p>"), "Empty: nothing to see");
        assertFalse(dropped("<a href=\"https://a.test\" href=\"javascript:x\">t</a>"),
                "A second address is ignored by a browser too");
    }

    @Test
    public void videosTablesOtherStylesAndRefusedLinksAreReportedAsDropped() {
        for (final String lost : List.of("<p><span style=\"font-family: Comic Sans;\">Due Friday</span></p>",
                "<p><span style=\"background: url(x);\">x</span></p>",
                "<p>Logo <img src=\"data:image/png;base64,AAAA\"></p>",
                "<iframe class=\"ql-video\" src=\"https://video.test/x\"></iframe>",
                "<p><a href=\"javascript:alert(1)\">x</a></p>", "<p><a href=\"example.com/pay\">pay</a></p>",
                "<p><a href=\"\">x</a></p>", "<p class=\"ql-hidden\">x</p>", "<form><p>x</p></form>")) {
            assertTrue(dropped(lost), lost);
        }
    }

    /** Whether cleaning {@code html} dropped something the writer could see. */
    private static boolean dropped(final String html) {
        return RichTextHtml.cleanNoting(html).dropped();
    }
}
