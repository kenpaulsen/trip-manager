package org.paulsens.trip.content;

import java.util.List;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Formatting lost while its text stayed ({@link MarkupLoss}): reported, so that Apply asks first; an author's own
 * deletion, a restyle and a mere change of spelling are not.
 */
public class MarkupLossTest {

    private static final String IMG = "https://media.example.com/a.jpg";

    @Test
    public void anImagesStyleThatWentWhileItsTextStayedIsReportedAsWritten() {
        final String stored = "<p><img style=\"float:left;padding: 7px 9px 0px 0px;\" src=\"" + IMG + "\">Hello</p>";
        final String edited = "<p><img src=\"" + IMG + "\">Hello there</p>";
        Assert.assertEquals(MarkupLoss.lost(stored, edited),
                List.of("<img style=\"float:left;padding: 7px 9px 0px 0px;\">"));
    }

    @Test
    public void aStyledWrapperWhoseWordsStayedIsReported() {
        final String stored = "<div style=\"text-align:center;color:red\"><p>Our monthly prayer group</p>"
                + "<p>meets on Tuesday</p></div>";
        final String edited = "<p>Our monthly prayer group</p><p>meets on Wednesday</p>";
        Assert.assertEquals(MarkupLoss.lost(stored, edited), List.of("<div style=\"text-align:center;color:red\">"));
    }

    @DataProvider
    public Object[][] notALoss() {
        return new Object[][] {
            // The author deleted the image, or the paragraph holding a styled span.
            {"<p><img style=\"float:left\" src=\"" + IMG + "\">Hi</p>", "<p>Hi</p>"},
            {"<p>Keep</p><p><span style=\"color:red\">gone words here</span></p>", "<p>Keep</p>"},
            // Spellings: b/strong, i/em, a div that became a p with the same attributes, attribute order,
            // the noopener a link gains, white space in a style.
            {"<p><b>x</b> <i>y</i></p>", "<p><strong>x</strong> <em>y</em></p>"},
            {"<div style=\"text-align:center\">Hi</div>", "<p style=\"text-align: center\">Hi there</p>"},
            {"<a target=\"_blank\" href=\"https://x.org\" rel=\"noreferrer\">x</a>",
                "<a href=\"https://x.org\" rel=\"noopener noreferrer\" target=\"_blank\">x</a>"},
            // The whole section replaced in the HTML view: common words survive, but no link and no word pair.
            {"<p>See <a href=\"https://a.org\" target=\"_blank\">the abundant fruits of grace</a> today.</p>",
                "<h1>The trip</h1><p>Some of the people of the parish and the fruits of the harvest.</p>"},
            // An author's restyle changes a value and keeps the attribute: a new alignment, a new color, a resize.
            {"<p style=\"text-align:center\">Welcome to our parish</p>",
                "<p style=\"text-align: left;\">Welcome to our parish</p>"},
            {"<p><span style=\"color:red\">Pay by Friday please</span></p>",
                "<p><span style=\"color: var(--status-danger, #b71c1c);\">Pay by Friday please</span></p>"},
            {"<p><img src=\"" + IMG + "\" width=\"200\" style=\"float:left\">Hi</p>",
                "<p><img src=\"" + IMG + "\" width=\"320\" style=\"float:left\">Hi</p>"},
            // The author deleted one of several same-styled lines or phrases; the others are untouched.
            {"<p style=\"text-align:center\">Welcome to our parish</p><p style=\"text-align:center\">Mass is at ten"
                + " on Sunday</p>", "<p style=\"text-align:center\">Mass is at ten on Sunday</p>"},
            {"<p><span style=\"color:red\">Pay by Friday</span> and <span style=\"color:red\">bring a hat</span></p>",
                "<p>and <span style=\"color:red\">bring a hat</span></p>"},
            // A blank styled line the author backspaced over (Quill rows hold many).
            {"<p style=\"text-align:center\">Welcome to our parish</p><p style=\"text-align:center\"><br></p>"
                + "<p>More text</p>", "<p style=\"text-align:center\">Welcome to our parish</p><p>More text</p>"},
            // A plain link or image has no formatting to lose: deleting one of two links to an address is not a loss.
            {"<p><a href=\"https://a.org/register\" class=\"cta\">Register now</a></p><p>Or <a href=\"https://a.org/"
                + "register\">sign up here</a> later.</p>",
                "<p><a href=\"https://a.org/register\" class=\"cta\">Register now</a></p>"},
            // Bare tags carry nothing that can be lost.
            {"<div><span>Hi</span></div>", "<p>Hi</p>"},
            // Nothing stored: nothing to lose.
            {null, "<p>x</p>"},
            {"", "<p>x</p>"},
        };
    }

    @Test(dataProvider = "notALoss")
    public void changesThatLoseNothingAreNotReported(final String stored, final String edited) {
        Assert.assertEquals(MarkupLoss.lost(stored, edited), List.of());
    }

    /**
     * The check leans to asking (the owner's choice, 2026-11-23): joining two styled lines, or deleting one of several
     * lines alike, asks about formatting that did not go, since those words are still on the page. A rule that stopped
     * these questions hid real losses (theInnerOfTwoTagsOfOneSpellingLosingItsFormattingIsReported) and was reverted.
     */
    @DataProvider
    public Object[][] askedThoughNothingWent() {
        return new Object[][] {
            // Asked: Two styled lines joined with Backspace: the one tag left holds both lines' words.
            {"<p style=\"text-align:center\">Welcome to our parish</p><p style=\"text-align:center\">and to this"
                + " page</p>", "<p style=\"text-align:center\">Welcome to our parish and to this page</p>"},
            // Asked: one of three lines alike but for a word deleted (its words are in the two that stayed).
            {"<p style=\"margin-left:20px\">Mass at 10 am on Monday</p><p style=\"margin-left:20px\">Mass at 10 am"
                + " on Tuesday</p><p style=\"margin-left:20px\">Mass at 10 am on Wednesday</p>",
                "<p style=\"margin-left:20px\">Mass at 10 am on Monday</p><p style=\"margin-left:20px\">Mass at 10"
                + " am on Wednesday</p>"},
            // Asked: One of two links to the same address deleted with its paragraph; one of two uses of an image.
            {"<p><a href=\"https://a.org/r\" target=\"_blank\" class=\"cta\">Register now</a></p><p>Some words about"
                + " the trip here.</p><p><a href=\"https://a.org/r\" target=\"_blank\" class=\"cta\">Register now</a>"
                + "</p>", "<p><a href=\"https://a.org/r\" target=\"_blank\" class=\"cta\">Register now</a></p><p>Some"
                + " words about the trip here.</p>"},
            {"<p><img style=\"float:left\" src=\"" + IMG + "\">One</p><p><img style=\"float:left\" src=\"" + IMG
                + "\">Two</p>", "<p><img style=\"float:left\" src=\"" + IMG + "\">One</p><p>Two</p>"},
        };
    }

    @Test(dataProvider = "askedThoughNothingWent")
    public void anEditAmongLinesAlikeIsAskedAboutRatherThanRiskAMissedLoss(final String stored, final String edited) {
        Assert.assertEquals(MarkupLoss.lost(stored, edited).size(), 1, edited);
    }

    /** A tag whose formatting went inside, or around, an unchanged tag of the same spelling: still reported. */
    @DataProvider
    public Object[][] sameSpellingAroundOrInside() {
        return new Object[][] {
            {"<div style=\"margin-left:40px\"><p>Packing list</p><div style=\"margin-left:40px\">Bring a warm hat and"
                + " good shoes</div></div>", "<div style=\"margin-left:40px\"><p>Packing list</p><div>Bring a warm hat"
                + " and good shoes</div></div>"},
            {"<ul class=\"checks\"><li>Passport<ul class=\"checks\"><li>Two blank pages left</li></ul></li><li>Tickets"
                + "</li></ul>",
                "<ul class=\"checks\"><li>Passport<ul><li>Two blank pages left</li></ul></li><li>Tickets</li></ul>"},
            {"<div class=\"note\"><p class=\"note\">Pay by Friday please</p><p>Bring a warm hat</p></div>",
                "<div class=\"note\"><p>Pay by Friday please</p><p>Bring a warm hat</p></div>"},
            {"<div style=\"padding:12px\"><div style=\"padding:12px\">Bring a warm hat and good shoes</div></div>",
                "<div><div style=\"padding:12px\">Bring a warm hat and good shoes</div></div>"},
        };
    }

    @Test(dataProvider = "sameSpellingAroundOrInside")
    public void theInnerOfTwoTagsOfOneSpellingLosingItsFormattingIsReported(final String stored, final String edited) {
        Assert.assertEquals(MarkupLoss.lost(stored, edited).size(), 1, edited);
    }

    /** One of three lines alike but for a word had its style cleared: reported. */
    @Test
    public void oneOfSeveralLinesAlikeThatLostItsStyleIsReported() {
        final String open = "<p style=\"margin-left:20px\">";
        Assert.assertEquals(MarkupLoss.lost(
                open + "Mass at 10 am on Monday</p>" + open + "Mass at 10 am on Tuesday</p>" + open
                        + "Mass at 10 am on Wednesday</p>",
                open + "Mass at 10 am on Monday</p><p>Mass at 10 am on Tuesday</p>" + open
                        + "Mass at 10 am on Wednesday</p>"), List.of(open));
    }

    @Test
    public void quillsAlignmentClassIsTheStoredInlineAlignmentOnceTidied() {
        final String stored = "<p style=\"text-align:center\">Hi</p>";
        final String edited = RichTextRules.normalize("<p class=\"ql-align-center\">Hi there</p><p>More</p>");
        Assert.assertEquals(MarkupLoss.lost(stored, edited), List.of());
    }

    @Test
    public void anEmptyAnchorThatWentIsReportedSinceNothingShowsItWasMeant() {
        Assert.assertEquals(MarkupLoss.lost("<p><a name=\"top\"></a>Hi</p>", "<p>Hi</p>"),
                List.of("<a name=\"top\">"));
    }

    @Test
    public void aVideoFrameKeptByAddressButStrippedOfItsSizeIsReported() {
        final String stored = "<iframe width=\"560\" height=\"315\" src=\"https://www.youtube.com/embed/x\"></iframe>";
        final String edited = "<iframe class=\"ql-video\" src=\"https://www.youtube.com/embed/x\"></iframe>";
        Assert.assertEquals(MarkupLoss.lost(stored, edited), List.of("<iframe width=\"560\" height=\"315\">"));
    }

    /** A styled outer element kept as it was does not stand in for the inner one whose style went. */
    @Test
    public void anInnerStyleThatWentInsideAnUnchangedStyledOuterIsReported() {
        Assert.assertEquals(MarkupLoss.lost(
                "<p><span style=\"font-size:20px\"><span style=\"color:red\">Pay by Friday please</span></span></p>",
                "<p><span style=\"font-size:20px\">Pay by Friday please</span></p>"),
                List.of("<span style=\"color:red\">"));
        Assert.assertEquals(MarkupLoss.lost(
                "<div style=\"text-align:center\"><p style=\"font-size:1.4em\">Welcome to our parish</p></div>",
                "<div style=\"text-align:center\"><p>Welcome to our parish</p></div>"),
                List.of("<p style=\"font-size:1.4em\">"));
    }

    @Test
    public void anImageThatKeptItsAltAndWidthButLostItsFloatIsReported() {
        final String stored = "<p><img src=\"" + IMG + "\" alt=\"Cross\" width=\"212\" style=\"float:left\">Hi</p>";
        final String edited = "<p><img src=\"" + IMG + "\" alt=\"Cross\" width=\"212\">Hi there</p>";
        Assert.assertEquals(MarkupLoss.lost(stored, edited),
                List.of("<img alt=\"Cross\" width=\"212\" style=\"float:left\">"));
    }

    @Test
    public void aLinkKeptByAddressButStrippedOfItsTargetIsReported() {
        final String stored = "<p>Go <a href=\"https://a.org\" target=\"_blank\" class=\"cta\">there</a></p>";
        final String edited = "<p>Go now <a href=\"https://a.org\">there</a></p>";
        Assert.assertEquals(MarkupLoss.lost(stored, edited), List.of("<a target=\"_blank\" class=\"cta\">"));
    }

    @Test
    public void twoIdenticalTagsAreCountedSoLosingOneIsReported() {
        final String span = "<span style=\"color:red\">";
        final String stored = "<p>" + span + "one</span> " + span + "two</span></p>";
        final String edited = "<p>" + span + "one</span> two</p>";
        Assert.assertEquals(MarkupLoss.lost(stored, edited), List.of(span));
    }

    @Test
    public void aVeryLongTagIsShortened() {
        final String style = "color:red;" + "x".repeat(300);
        final List<String> lost = MarkupLoss.lost("<span style=\"" + style + "\">word</span>", "word");
        Assert.assertEquals(lost.size(), 1);
        Assert.assertTrue(lost.get(0).length() <= 160 && lost.get(0).endsWith("…"), lost.get(0));
    }

    /** A page of {@code count} lines, each its own words, every line opened by {@code open}. */
    private static String page(final int count, final String open, final String close) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            out.append(open).append("Line ").append(i).append(" tells of day ").append(i)
                    .append(" and what the group does that morning").append(close);
        }
        return out.toString();
    }

    private static final String CALIBRI = "<p style=\"font-family:Calibri\">";

    /**
     * The check's work follows the size of the edit, never the square of the page: until 2026-11-23 one check of a
     * pasted 300 KB page whose font had been changed took 14 seconds, on a request thread of the one shared server.
     * Counted in comparisons, not seconds, so that a slow build machine cannot fail it.
     */
    private static void assertCheckedCheaply(final String stored, final String edited, final int losses) {
        final MarkupLoss.Check check = MarkupLoss.check(stored, edited);
        Assert.assertFalse(check.skipped(), "checked, not skipped");
        Assert.assertEquals(check.lost().size(), losses);
        Assert.assertTrue(check.work() < 600_000, "a few comparisons per tag, not one per pair of tags: "
                + check.work());
        Assert.assertTrue(check.held() < MarkupLoss.HELD_BOUND, "kept: " + check.held());
    }

    @Test
    public void selectAllAndAnotherFontRestylesEveryTagAndLosesNone() {
        assertCheckedCheaply(page(3500, CALIBRI, "</p>"), page(3500, "<p style=\"font-family:Arial\">", "</p>"), 0);
    }

    @Test
    public void everyPhraseRecoloredLosesNone() {
        assertCheckedCheaply(page(3500, "<p><span style=\"color:red\">", "</span></p>"),
                page(3500, "<p><span style=\"color:blue\">", "</span></p>"), 0);
    }

    @Test
    public void findAndReplaceOverEveryLineKeepsEveryTag() {
        final String stored = page(3500, CALIBRI, "</p>");
        assertCheckedCheaply(stored, stored.replace("morning", "evening"), 0);
    }

    @Test
    public void clearFormattingOverEverythingNamesEveryLine() {
        assertCheckedCheaply(page(3000, CALIBRI, "</p>"), page(3000, "<p>", "</p>"), 3000);
    }

    @Test
    public void oneWordTypedIntoABigPageInsideAWrapperOfAllOfIt() {
        final String lines = page(3500, CALIBRI, "</p>");
        assertCheckedCheaply("<div class=\"wrap\">" + lines + "</div>",
                "<div class=\"wrap\">" + lines.replace("day 77 ", "day 77 here ") + "</div>", 0);
    }

    @Test
    public void anUntouchedValueIsAnsweredWithoutReadingIt() {
        final String stored = page(3500, "<p style=\"font-family:Calibri\">", "</p>");
        Assert.assertEquals(MarkupLoss.check(stored, stored), new MarkupLoss.Check(List.of(), 0, 0, false));
    }

    /**
     * Past a bound the check gives up and reports nothing, the same way every time it runs (Apply is answered by
     * the key of the list the drawer showed).
     */
    @Test
    public void aComparisonPastABoundIsSkippedTheSameWayEveryTime() {
        final String stored = page(200, CALIBRI, "</p>");
        final String edited = page(200, "<p style=\"font-family:Arial\">", "</p>");
        Assert.assertFalse(MarkupLoss.check(stored, edited).skipped());
        final MarkupLoss.Check work = MarkupLoss.check(stored, edited, 500, MarkupLoss.HELD_BOUND);
        Assert.assertTrue(work.skipped());
        Assert.assertEquals(work.lost(), List.of());
        Assert.assertEquals(MarkupLoss.check(stored, edited, 500, MarkupLoss.HELD_BOUND), work);
        final MarkupLoss.Check held = MarkupLoss.check(stored, edited, MarkupLoss.WORK_BOUND, 500);
        Assert.assertTrue(held.skipped() && held.held() <= 600, "stopped before more was kept: " + held);
        Assert.assertEquals(MarkupLoss.check(stored, edited, MarkupLoss.WORK_BOUND, 500), held);
        final String tooLong = "<p style=\"color:red\">" + "word ".repeat(MarkupLoss.LENGTH_BOUND / 5) + "</p>";
        Assert.assertTrue(MarkupLoss.check(tooLong, tooLong + " ").skipped(), "longer than any row: not read");
        Assert.assertEquals(MarkupLoss.lost(tooLong, tooLong + " "), List.of());
    }

    /**
     * An untouched field is answered before the gate, even while every turn is taken; a real comparison that gets no
     * turn in time is skipped (the drawer then says it was too large to check, and Apply asks), never waited on
     * without end.
     */
    @Test
    public void anUntouchedFieldNeverWaitsAndAComparisonWithNoTurnIsSkipped() throws InterruptedException {
        MarkupLoss.GATE.acquire(MarkupLoss.AT_ONCE);
        try {
            final long start = System.nanoTime();
            Assert.assertEquals(MarkupLoss.check("<p style=\"color:red\">a b</p>", "<p style=\"color:red\">a b</p>"),
                    new MarkupLoss.Check(List.of(), 0, 0, false));
            Assert.assertTrue(System.nanoTime() - start < 1_000_000_000L, "answered at once");
            final MarkupLoss.Check waited = MarkupLoss.check("<p style=\"color:red\">a b</p>", "<p>a b</p>");
            Assert.assertTrue(waited.skipped(), waited.toString());
        } finally {
            MarkupLoss.GATE.release(MarkupLoss.AT_ONCE);
        }
        Assert.assertEquals(MarkupLoss.check("<p style=\"color:red\">a b</p>", "<p>a b</p>").lost().size(), 1);
    }

    /** {@code count} different words. */
    private static String manyWords(final int count) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            out.append("word").append(i).append(' ');
        }
        return out.toString();
    }

    /**
     * What a check keeps is counted before it is built. One posted value, a tag with hundreds of attributes around
     * tens of thousands of words, was once indexed once per attribute: 2.4 GB for 254 KB, on the server every
     * organization shares (review, 2026-11-23). The same for words nested many tags deep, each tag touched.
     */
    @Test
    public void whatACheckKeepsIsBoundedHoweverTheValueIsShaped() {
        final StringBuilder attributes = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            attributes.append(" a").append(i).append("=\"1\"");
        }
        final String stored = "<div" + attributes + ">" + manyWords(30_000) + "</div>";
        final String edited = "<div" + attributes.toString().replace("\"1\"", "\"2\"") + ">"
                + manyWords(30_000) + "</div>";
        // The stored tag lacks one attribute, so the edited one is not it: a restyled version is looked for.
        final MarkupLoss.Check wide = MarkupLoss.check(stored.replace(" a7=\"1\"", ""), edited);
        Assert.assertFalse(wide.skipped(), wide.toString());
        Assert.assertTrue(wide.held() <= 60_001, "indexed under one kind, not under each attribute: " + wide.held());
        final MarkupLoss.Check fresh = MarkupLoss.check("<p class=\"intro\">Welcome</p>", edited);
        Assert.assertEquals(fresh.held(), 0, "nothing stored asks about the new tag: " + fresh);

        final String deep = "<div class=\"w\">".repeat(40) + manyWords(40_000) + "</div>".repeat(40);
        final MarkupLoss.Check nested = MarkupLoss.check(deep, deep.replace("word77 ", "word77 here "));
        Assert.assertTrue(nested.skipped() && nested.held() <= MarkupLoss.HELD_BOUND + 80_001, nested.toString());
        // Untouched, the same nesting costs nothing: each word is held once, however deep it sits.
        final MarkupLoss.Check untouched = MarkupLoss.check(deep + "<p>More</p>", deep + "<p>More here</p>");
        Assert.assertFalse(untouched.skipped());
        Assert.assertTrue(untouched.held() < 10, "only the one line that changed: " + untouched.held());
    }

    /**
     * Comparing formatting is charged per attribute name: tags with two dozen attributes, compared against thousands
     * of candidates that each lack only the last, once held a request thread for eight seconds while counting as
     * little work (review, 2026-11-23). Now such a value passes the bound and is skipped.
     */
    @Test
    public void manyAttributesPerTagAreChargedPerAttributeCompared() {
        final StringBuilder names = new StringBuilder();
        for (char c = 'a'; c <= 'v'; c++) {
            names.append(" data-").append(c).append("=\"1\"");
        }
        final String all = "<p" + names + " data-zz=\"1\">same</p>";
        final String allButLast = "<p" + names + ">same</p>";
        final MarkupLoss.Check check = MarkupLoss.check(all.repeat(1380),
                allButLast.repeat(2090) + "<p data-zz=\"1\">z</p>".repeat(2090));
        Assert.assertTrue(check.skipped(), check.toString());
    }

    /** A value with no formatted tag anywhere holds nothing to lose: nothing is compared, however it is nested. */
    @Test
    public void aValueWithNoFormattedTagIsNotCompared() {
        final String body = "<table><tbody><tr><td>" + manyWords(40_000) + "</td></tr></tbody></table>";
        final MarkupLoss.Check check = MarkupLoss.check(body, body.replace("word77 ", "word77 here "));
        Assert.assertFalse(check.skipped(), check.toString());
        Assert.assertEquals(check.held(), 0);
    }

    /** A day deleted from a large itinerary of days styled alike is checked, not skipped as too large. */
    @Test
    public void aDayDeletedFromALargeStyledItineraryIsChecked() {
        final String[] opens = {"<h3 style=\"color:#336\">", "<p style=\"text-align:center\">",
            "<p class=\"note\">", "<blockquote style=\"margin-left:20px\">"};
        final String[] closes = {"</h3>", "</p>", "</p>", "</blockquote>"};
        final StringBuilder page = new StringBuilder();
        final StringBuilder without = new StringBuilder();
        for (int day = 1; day <= 60; day++) {
            final StringBuilder one = new StringBuilder();
            for (int k = 0; k < opens.length; k++) {
                one.append(opens[k]).append("Day ").append(day).append(' ').append(manyWords(40)).append(closes[k]);
            }
            one.append("<p>").append(manyWords(60)).append("</p>");
            page.append(one);
            if (day != 2) {
                without.append(one);
            }
        }
        Assert.assertFalse(MarkupLoss.check(page.toString(), without.toString()).skipped());
    }

    private static final String BOTH = "<p class=\"MsoNormal\" style=\"text-align:center\">";

    /** Lines of each kind of text an edit can run over: all different, alike but for a number, the same, one word. */
    @DataProvider
    public Object[][] texts() {
        return new Object[][] {
            {"Line %d tells of day %d and what the group does that morning"},
            {"Day %d: Mass at nine in the chapel, then breakfast with the group"},
            {"Mass at nine in the chapel"},
            {"Yes"},
        };
    }

    private static String lines(final int count, final String open, final String text) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            out.append(open).append(String.format(text, i, i)).append("</p>");
        }
        return out.toString();
    }

    /**
     * Every whole-page edit of every kind of text is checked in work that follows the page's size: one cell of this
     * table (every line keeps its class and loses its style, over lines alike but for a number) once walked every
     * line for every line, passed the bound, and saved a thousand lost alignments unasked (review, 2026-11-23).
     */
    @Test(dataProvider = "texts")
    public void everyWholePageEditOfEveryKindOfTextIsCheckedInLinearWork(final String text) {
        final int count = 3000;
        final String stored = lines(count, BOTH, text);
        final String noStyle = lines(count, "<p class=\"MsoNormal\">", text);
        final String noClass = lines(count, "<p style=\"text-align:center\">", text);
        final String restyled = lines(count, "<p class=\"MsoNormal\" style=\"text-align:left\">", text);
        assertCheckedCheaply(stored, noStyle, count);
        assertCheckedCheaply(stored, noClass, count);
        assertCheckedCheaply(stored, lines(count, "<p>", text), count);
        assertCheckedCheaply(stored, restyled, 0);
        assertCheckedCheaply(stored, stored.replace("group", "parish").replace("nine", "ten").replace("Yes", "No"), 0);
        // Half the lines deleted: lines this much alike share their words, so the check asks about the deleted half
        // (it leans to asking; anEditAmongLinesAlikeIsAskedAboutRatherThanRiskAMissedLoss).
        assertCheckedCheaply(stored, lines(count / 2, BOTH, text), count - count / 2);
        // Half deleted and the rest stripped of their style: lines this much alike share most of their words, so
        // the deleted ones' words are still on the page and they are named with the rest.
        assertCheckedCheaply(stored, lines(count / 2, "<p class=\"MsoNormal\">", text), count);
    }
}
