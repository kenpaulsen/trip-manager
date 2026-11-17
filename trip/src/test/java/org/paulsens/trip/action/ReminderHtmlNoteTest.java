package org.paulsens.trip.action;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * A trip event's private note through the allowlist ({@link ReminderHtml#cleanNote}). Notes were evaluated as EL
 * and written out unescaped until medjugorje#116; the itinerary's editor offers colors and images by address, so
 * the note keeps exactly those two beyond the reminder's list, rebuilt and checked, and nothing else.
 */
public class ReminderHtmlNoteTest {

    @Test
    public void aColorIsKeptAndAnImageIsNot() {
        Assert.assertEquals(ReminderHtml.cleanNote(
                "<span style=\"color: var(--red-500, #ef4444); BACKGROUND-COLOR:#fff\">Room 114</span>"),
                "<span style=\"color: var(--red-500, #ef4444); background-color: #fff\">Room 114</span>");
        Assert.assertEquals(ReminderHtml.cleanNote("<p style='color: rgb(1, 2, 3)'>x</p>"),
                "<p style=\"color: rgb(1, 2, 3)\">x</p>");
        // No image, not even a web one: a remote image is a tracking pixel every reader's browser fetches.
        final ReminderHtml.Cleaned image = ReminderHtml.cleanNoteNoting("<img src=\"https://cdn.example/a.png\">ok");
        Assert.assertEquals(image.body(), "ok");
        Assert.assertTrue(image.dropped(), "the save says it removed something");
    }

    @Test
    public void everyOtherStyleAndImageIsDroppedAndNoted() {
        final ReminderHtml.Cleaned styled = ReminderHtml.cleanNoteNoting(
                "<span style=\"color:#c00; background: url(https://x/y); position:fixed\">a</span>");
        Assert.assertEquals(styled.body(), "<span style=\"color: #c00\">a</span>");
        Assert.assertTrue(styled.dropped());

        Assert.assertEquals(ReminderHtml.cleanNote("<span style=\"color: expression(alert(1))\">a</span>"),
                "<span>a</span>");
        Assert.assertEquals(ReminderHtml.cleanNote("<span style=\"color: #c00\\3b\">a</span>"), "<span>a</span>");
        final ReminderHtml.Cleaned image = ReminderHtml.cleanNoteNoting(
                "<img src=\"javascript:alert(1)\" onerror=\"alert(1)\"><img src=x onerror=alert(1)></img>after");
        Assert.assertEquals(image.body(), "after");
        Assert.assertTrue(image.dropped());
        Assert.assertEquals(ReminderHtml.cleanNote("<img src=\"http://plain.example/a.png\">"), "");
    }

    /**
     * What the 2026-11 production scan found in stored notes: a named color (also as an old {@code <font>}),
     * {@code <strike>}, a smaller font, bold, and links to the site's own check-in page. All kept, rebuilt.
     */
    @Test
    public void theFormattingStoredNotesHoldIsKept() {
        final ReminderHtml.Cleaned old = ReminderHtml.cleanNoteNoting("<font color=\"red\">a</font><strike>b</strike>"
                + "<span style=\"font-size: 0.8em; font-weight:bold\">c</span>"
                + "<a href=\"deltaklm.jsf\" target=\"_blank\">d</a>");
        Assert.assertEquals(old.body(), "<span style=\"color: red\">a</span><s>b</s>"
                + "<span style=\"font-size: 0.8em; font-weight: bold\">c</span>"
                + "<a href=\"deltaklm.jsf\" rel=\"noopener noreferrer\" target=\"_blank\">d</a>");
        Assert.assertFalse(old.dropped(), "nothing a reader could see was lost");
    }

    /** Only a page of this site, by relative address: nothing with a scheme or another host. */
    @Test
    public void aRelativeLinkIsKeptOnlyForTheSitesOwnPages() {
        Assert.assertEquals(ReminderHtml.cleanNote("<a href=\"/trip/itinerary.jsf?trip=1&id=2\">x</a>"),
                "<a href=\"/trip/itinerary.jsf?trip=1&id=2\" rel=\"noopener noreferrer\" target=\"_blank\">x</a>");
        // A pasted check-in link with a space in its query: a browser sends it encoded, and it stays quoted here.
        Assert.assertEquals(ReminderHtml.cleanNote("<a href=\"deltaklm.jsf?last=Lee&pnr= AB12CD\">x</a>"),
                "<a href=\"deltaklm.jsf?last=Lee&pnr= AB12CD\" rel=\"noopener noreferrer\" target=\"_blank\">x</a>");
        for (final String href : new String[] {"javascript:alert(1)//.jsf", "//evil.example/x.jsf", "x.html",
            "../x.jsf", "x.jsf\" onclick=\"y"}) {
            Assert.assertEquals(ReminderHtml.cleanNote("<a href='" + href + "'>x</a>"), "<a>x</a>", href);
        }
        Assert.assertEquals(ReminderHtml.cleanNote("<font color=\"red;background:url(x)\" size=\"7\">a</font>"),
                "<span>a</span>", "an old font's color is one literal value or nothing");
        Assert.assertEquals(ReminderHtml.clean("<font color=\"red\">a</font><a href=\"deltaklm.jsf\">b</a>"),
                "a<a>b</a>", "a reminder keeps neither");
    }

    /** Text that once ran as EL is only text now: kept as typed, never evaluated, never markup. */
    @Test
    public void anExpressionIsJustText() {
        Assert.assertEquals(ReminderHtml.cleanNote("Room #{reg.getRoomPDV(theTrip.id, person.id).content}"),
                "Room #{reg.getRoomPDV(theTrip.id, person.id).content}");
        Assert.assertEquals(ReminderHtml.cleanNote("<script>#{7*7}</script>ok"), "ok");
    }

    /** A reminder's body is unchanged by the note's extras: still no colors, still no images. */
    @Test
    public void aReminderStillKeepsNeitherColorsNorImages() {
        Assert.assertEquals(ReminderHtml.clean("<span style=\"color:#c00\">a</span><img src=\"https://x/y.png\">"),
                "<span>a</span>");
        Assert.assertEquals(ReminderHtml.cleanNote(null), "");
        Assert.assertEquals(ReminderHtml.cleanNote("<ol><li data-list=\"bullet\" style=\"color:#000\">a</li></ol>"),
                "<ul><li data-list=\"bullet\" style=\"color: #000\">a</li></ul>");
    }
}
