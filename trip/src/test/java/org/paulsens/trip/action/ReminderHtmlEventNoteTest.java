package org.paulsens.trip.action;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * A trip event's shared notes through the allowlist ({@link ReminderHtml#cleanEventNote}, medjugorje#156). They
 * were written out unescaped on every itinerary, the shared itinerary and the public trip page. The fixtures
 * below are shaped like what the 2026-11-16 production scan found in all 1,071 stored notes, and every one must
 * come out as it went in: fixing the hole may not cost a note its formatting.
 */
public class ReminderHtmlEventNoteTest {

    private static final String LINK_TAIL = " rel=\"noopener noreferrer\" target=\"_blank\"";

    /** Colors as Quill writes them, its size, indent and font classes, bold, strike and underline. */
    @Test
    public void theFormattingStoredNotesHoldIsKeptAsItIs() {
        final String stored = "<p class=\"ql-indent-1\"><span style=\"color: rgb(230, 0, 0);\">Red</span> "
                + "<strong style=\"background-color: rgb(255, 255, 0);\">marked</strong> "
                + "<span class=\"ql-size-small\">small</span> <span class=\"ql-size-large\">large</span> "
                + "<span class=\"ql-font-monospace\">AB12CD</span> <s>old</s> <u>new</u> <em>now</em><br></p>";
        final ReminderHtml.Cleaned cleaned = ReminderHtml.cleanEventNoteNoting(stored);
        Assert.assertEquals(cleaned.body(), "<p class=\"ql-indent-1\"><span style=\"color: rgb(230, 0, 0)\">Red</span> "
                + "<strong style=\"background-color: rgb(255, 255, 0)\">marked</strong> "
                + "<span class=\"ql-size-small\">small</span> <span class=\"ql-size-large\">large</span> "
                + "<span class=\"ql-font-monospace\">AB12CD</span> <s>old</s> <u>new</u> <em>now</em><br></p>");
        Assert.assertFalse(cleaned.dropped(), "nothing a reader could see was lost");
        Assert.assertEquals(ReminderHtml.cleanEventNote("<span style=\"color: unset;\">x</span>"),
                "<span style=\"color: unset\">x</span>");
    }

    /** Every production link: https to another site (maps, an airline) or mailto, opening a new tab. */
    @Test
    public void webAndMailLinksAreKept() {
        Assert.assertEquals(ReminderHtml.cleanEventNote("<a href=\"https://maps.app.goo.gl/x1\" rel=\"noopener "
                + "noreferrer\" target=\"_blank\" style=\"color: rgb(0, 102, 204);\">Map</a>"),
                "<a href=\"https://maps.app.goo.gl/x1\" style=\"color: rgb(0, 102, 204)\"" + LINK_TAIL + ">Map</a>");
        Assert.assertEquals(ReminderHtml.cleanEventNote("<a href=\"mailto:office@example.org\" target=\"_blank\">"
                + "Write</a>"), "<a href=\"mailto:office@example.org\"" + LINK_TAIL + ">Write</a>");
    }

    /** Quill 1's lists, which carry no data-list, and plain text with the composer's arrow. */
    @Test
    public void oldListsAndPlainTextAreKept() {
        Assert.assertEquals(ReminderHtml.cleanEventNote("<ol><li>one</li><li>two</li></ol><ul><li>dot</li></ul>"),
                "<ol><li>one</li><li>two</li></ol><ul><li>dot</li></ul>");
        // The arrow is escaped; a page writes it out as the same "->", and both apps decode it.
        Assert.assertEquals(ReminderHtml.cleanEventNote("Alaska AS 123: 8:15am -> 5:25pm"),
                "Alaska AS 123: 8:15am -&gt; 5:25pm");
        Assert.assertEquals(ReminderHtml.cleanEventNote(null), "");
    }

    /** The three tools the event dialog's editor offers beyond the private note's list. */
    @Test
    public void theEditorsImageVideoAndCodeBlockAreKept() {
        final ReminderHtml.Cleaned image = ReminderHtml.cleanEventNoteNoting(
                "<p><img src=\"https://cdn.example/hotel.jpg\" alt=\"Hotel\" width=\"300\" height=\"200px\"></p>");
        Assert.assertEquals(image.body(),
                "<p><img src=\"https://cdn.example/hotel.jpg\" alt=\"Hotel\" width=\"300\" height=\"200px\"></p>");
        Assert.assertFalse(image.dropped());
        Assert.assertEquals(ReminderHtml.cleanEventNote("<img src=\"https://x.example/a.png\"></img>after"),
                "<img src=\"https://x.example/a.png\">after", "a stray </img> closes nothing");
        // Plain http too: the editor's image-by-URL prompt accepts it, and a video does not get the same allowance.
        Assert.assertEquals(ReminderHtml.cleanEventNote("<img src=\"http://plain.example/a.png\">"),
                "<img src=\"http://plain.example/a.png\">");

        final ReminderHtml.Cleaned video = ReminderHtml.cleanEventNoteNoting("<iframe class=\"ql-video\" "
                + "frameborder=\"0\" allowfullscreen=\"true\" src=\"https://www.youtube.com/embed/abc\"></iframe>"
                + "<p>after</p>");
        Assert.assertEquals(video.body(), "<iframe class=\"ql-video\" frameborder=\"0\" allowfullscreen=\"true\" "
                + "src=\"https://www.youtube.com/embed/abc\"></iframe><p>after</p>");
        Assert.assertFalse(video.dropped());

        final ReminderHtml.Cleaned code = ReminderHtml.cleanEventNoteNoting("<div class=\"ql-code-block-container\" "
                + "spellcheck=\"false\"><div class=\"ql-code-block\" data-language=\"plain\">PNR AB12CD</div></div>");
        Assert.assertEquals(code.body(), "<div class=\"ql-code-block-container\"><div class=\"ql-code-block\">"
                + "PNR AB12CD</div></div>");
        Assert.assertFalse(code.dropped());
    }

    /** What #156 is about: markup that runs. None of it survives, and the save is told something went. */
    @Test
    public void scriptsHandlersAndForeignEmbedsAreDropped() {
        assertDropped("<img src=x onerror=alert(1)>after", "after");
        assertDropped("<img src=\"https://x.example/a.png\" onerror=\"alert(1)\" style=\"position:fixed\">",
                "<img src=\"https://x.example/a.png\">");
        assertDropped("<iframe class=\"ql-video\" src=\"http://v.example/1\"></iframe>ok", "ok");
        assertDropped("<img src=\"data:image/png;base64,AAAA\">ok", "ok");
        assertDropped("<img src=\"javascript:alert(1)\">ok", "ok");
        assertDropped("<iframe src=\"https://evil.example/\">inside</iframe>ok", "ok");
        assertDropped("<iframe class=\"ql-video\" src=\"javascript:alert(1)\"></iframe>ok", "ok");
        assertDropped("<iframe class=\"ql-video\" srcdoc=\"<script>alert(1)</script>\"></iframe>ok", "ok");
        assertDropped("<script>alert(1)</script>ok", "ok");
        assertDropped("<svg/onload=alert(1)>ok</svg>after", "after");
        assertDropped("<p onclick=\"alert(1)\" style=\"color:red;background:url(https://x/y)\">a</p>",
                "<p style=\"color: red\">a</p>");
        assertDropped("<a href=\"jav&#97;script:alert(1)\">x</a>", "<a>x</a>");
        Assert.assertEquals(ReminderHtml.cleanEventNote("<img src=\"https://x.example/a.png\" alt='a\"b'>"),
                "<img src=\"https://x.example/a.png\">", "an alt text that needs escaping is not kept");
        Assert.assertEquals(ReminderHtml.cleanEventNote(
                "<iframe class=\"ql-video\" src=\"https://v.example/1\" onload=\"alert(1)\" sandbox=\"\"></iframe>"),
                "<iframe class=\"ql-video\" frameborder=\"0\" allowfullscreen=\"true\" src=\"https://v.example/1\">"
                        + "</iframe>", "only the fixed attributes and the address are written");
    }

    /** The extras are the event's alone: a private note and a reminder still keep no image, video or code block. */
    @Test
    public void privateNotesAndRemindersKeepTheirOwnLists() {
        final String extras = "<img src=\"https://x.example/a.png\"><iframe class=\"ql-video\" "
                + "src=\"https://v.example/1\"></iframe><div class=\"ql-code-block\">c</div>";
        Assert.assertEquals(ReminderHtml.cleanNote(extras), "<div>c</div>");
        Assert.assertEquals(ReminderHtml.clean(extras), "<div>c</div>");
        Assert.assertEquals(ReminderHtml.cleanEventNote("<font color=\"red\">a</font><a href=\"deltaklm.jsf\">b</a>"),
                "<span style=\"color: red\">a</span><a href=\"deltaklm.jsf\"" + LINK_TAIL + ">b</a>",
                "the private note's old tags and site links are the event's too");
    }

    private static void assertDropped(final String raw, final String expected) {
        final ReminderHtml.Cleaned cleaned = ReminderHtml.cleanEventNoteNoting(raw);
        Assert.assertEquals(cleaned.body(), expected, raw);
        Assert.assertTrue(cleaned.dropped(), "the save says it removed something: " + raw);
    }
}
