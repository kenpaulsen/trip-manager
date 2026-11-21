package org.paulsens.trip.content;

import java.util.List;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * What an author is told the allowlist will remove ({@link StripNotes}): the owner's rule is that it names the
 * exact thing, so each line here is checked against what was really typed.
 */
public class StripNotesTest {

    private static List<String> said(final String typed) {
        return StripNotes.lines("Body", PageHtml.cleanNoting(typed));
    }

    @DataProvider
    public Object[][] removals() {
        return new Object[][] {
            {"<script>x()</script>", "Body: the <script> tag and everything inside it: script cannot be saved here."},
            {"<object data=\"x\">in</object>",
                "Body: the <object> tag and everything inside it: it is not allowed here."},
            {"<form>in</form>",
                "Body: the <form> tag (what is inside it stays): a form or form field cannot be saved here."},
            {"<body>in</body>", "Body: the <body> tag (what is inside it stays): it belongs to a whole page, and this "
                + "is one part of a page."},
            {"<link rel=\"stylesheet\" href=\"/x.css\">",
                "Body: the <link> tag: a stylesheet is linked from the organization's stylesheets, not from here."},
            {"<marquee>in</marquee>",
                "Body: the <marquee> tag (what is inside it stays): it is not on the list of allowed tags."},
            {"<p onclick=\"x()\">a</p>",
                "Body: the onclick attribute on <p>: an event handler runs script, which cannot be saved here."},
            {"<a href=\"javascript:x()\">a</a>", "Body: the href attribute on <a>: that address is not allowed (use "
                + "https, or a path on this site that starts with /)."},
            {"<p id=\"form:save\">a</p>",
                "Body: the id attribute on <p>: use letters, digits, - and _ only, starting with a letter."},
            {"<iframe src=\"https://x.example\" srcdoc=\"<p>x</p>\"></iframe>",
                "Body: the srcdoc attribute on <iframe>: a frame shows an https address, not markup typed here."},
            {"<p data-pfconfirmcommand=\"x()\">a</p>", "Body: the data-pfconfirmcommand attribute on <p>: a script on "
                + "this site's pages uses that name, so pick another data- name."},
            {"<p contenteditable=\"true\">a</p>",
                "Body: the contenteditable attribute on <p>: that attribute, or its value, is not allowed there."},
            {"<style>@import 'x.css';</style>", "Body: the rule \"@import 'x.css';\" in a <style> block: a stylesheet "
                + "cannot load another one (@import)."},
            {"<style>input[value^=a]{color:red}</style>", "Body: the rule \"input[value^=a]\" in a <style> block: a "
                + "rule cannot test a form field's value ([value...])."},
            {"<p>a</p><!--[if IE]>x<![endif]-->", "Body: the comment \"[if IE]>x<![endif]\": it is written in a way a "
                + "browser could read as markup."},
            {"<iframe src=\"https://x.example\"><style>p{}</style></iframe>",
                "Body: a <style> block: it sits where a browser would not read it as a style block."},
        };
    }

    @Test(dataProvider = "removals")
    public void eachRemovalIsNamedWithItsReason(final String typed, final String line) {
        Assert.assertEquals(said(typed), List.of(line));
    }

    /** The same thing typed three times is one line that says so, not three lines. */
    @Test
    public void aRepeatedRemovalIsCounted() {
        Assert.assertEquals(said("<p onclick=\"a()\">a</p><p onclick=\"b()\">b</p><p onclick=\"c()\">c</p>"),
                List.of("Body: the onclick attribute on <p>: an event handler runs script, which cannot be saved "
                        + "here. (3 times)"));
    }

    @Test
    public void nothingRemovedSaysNothing() {
        Assert.assertEquals(
                said("<p class=\"a\" style=\"position:fixed\" data-x=\"1\">Hi</p><style>p{margin:0}</style>"),
                List.of());
        Assert.assertEquals(said(null), List.of());
    }

    /** A second clean of one body is the first one's answer, and a body too long to hold is still cleaned. */
    @Test
    public void aCleanIsRememberedButNeverABodyTooLongToHold() {
        final CleanMemo memo = new CleanMemo();
        final int[] cleans = {0};
        final java.util.function.Function<String, PageHtml.Cleaned> counting = html -> {
            cleans[0]++;
            return new PageHtml.Cleaned(html, List.of());
        };
        Assert.assertSame(memo.get("<p>a</p>", counting), memo.get("<p>a</p>", counting));
        Assert.assertEquals(cleans[0], 1);
        final String huge = "a".repeat(CleanMemo.MAX_LENGTH + 1);
        memo.get(huge, counting);
        memo.get(huge, counting);
        Assert.assertEquals(cleans[0], 3);
        for (int i = 0; i < CleanMemo.MAX_ENTRIES + 50; i++) {
            memo.get("<p>" + i + "</p>", counting);
        }
        Assert.assertEquals(memo.size(), CleanMemo.MAX_ENTRIES, "the least recently used go first");
    }
}
