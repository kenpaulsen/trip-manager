package org.paulsens.trip.content;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

/**
 * {@link HtmlTags}' lenient scanner, which {@link RichTextRules} reads tags with: a quoted {@code >} does not end a
 * tag, a slash before the end self-closes it, and a tag that never ends is no tag. Its old second caller (the
 * fragment validator, replaced by {@link HtmlCheck}) carried these cases until 2026-11-24.
 */
public class HtmlTagsTest {

    @Test
    public void aTagIsReadToItsEndPastQuotedBrackets() {
        final String html = "<div title=\"a > b\" data-x='>'>x";
        assertEquals(HtmlTags.readTag(html, 1), new HtmlTags.Tag("div", false, html.lastIndexOf('x')));
        assertEquals(HtmlTags.readTag("<BR/>", 1), new HtmlTags.Tag("br", true, 5));
        assertEquals(HtmlTags.readTag("<x-end a/ b>", 1), new HtmlTags.Tag("x-end", false, 12),
                "a slash not right before the end does not self-close");
    }

    @Test
    public void whatNeverEndsIsNoTag() {
        assertNull(HtmlTags.readTag("< p>", 1), "no name");
        assertNull(HtmlTags.readTag("<p title=\"open>", 1), "an unterminated value");
        assertNull(HtmlTags.readTag("<p class=x", 1), "runs off the end");
    }

    @Test
    public void skipPastFindsTheStopOrTheEnd() {
        assertEquals(HtmlTags.skipPast("<!-- c -->x", 0, '>'), 10);
        assertEquals(HtmlTags.skipPast("<!-- c", 0, '>'), 6);
    }
}
