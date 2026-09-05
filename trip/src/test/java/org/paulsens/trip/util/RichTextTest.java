package org.paulsens.trip.util;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Quill's paragraph-per-line output turned into break-per-line, for the fields that want it.
 *
 * <p>The complaint driving this: every Enter in the WYSIWYG editor produced a {@code <p>}, and the site's
 * paragraph spacing made a three-line note look double-spaced. Each case here is a shape Quill actually emits.
 */
public class RichTextTest {

    @DataProvider(name = "conversions")
    public Object[][] conversions() {
        return new Object[][] {
                {null, "", "null is empty, not the word null"},
                {"", "", "empty"},
                {"   ", "", "blank"},
                {"<p>one</p>", "one", "a single line loses its wrapper and gains no break"},
                {"<p>one</p><p>two</p>", "one<br />two", "two lines are joined by exactly one break"},
                {"<p>one</p><p>two</p><p>three</p>", "one<br />two<br />three", "three lines"},
                {"<p>one</p><p><br></p><p>two</p>", "one<br /><br />two",
                        "a blank line (what Quill emits for Enter, Enter) survives as a second break"},
                {"<p>one</p><p><br></p><p><br></p><p>two</p>", "one<br /><br /><br />two",
                        "two blank lines survive as two extra breaks"},
                {"<p><br></p><p>one</p>", "one", "a leading blank line is dropped"},
                {"<p>one</p><p><br></p>", "one", "a trailing blank line is dropped"},
                {"<p>one</p><p></p><p>two</p>", "one<br /><br />two", "an outright empty paragraph is a blank line"},
                {"<p><strong>bold</strong> and <em>em</em></p><p>x</p>", "<strong>bold</strong> and <em>em</em><br />x",
                        "inline formatting inside a line is kept"},
                {"<p>a<br>b</p>", "a<br />b", "a soft break inside a paragraph is normalized to the XHTML form"},
                {"<p>a<br/>b</p><p>c</p>", "a<br />b<br />c", "the self-closing spelling is normalized too"},
                {"<P>upper</P><p>lower</p>", "upper<br />lower", "tag case does not matter"},
                {"<p>\n  spaced  \n</p>\n<p>x</p>", "spaced<br />x", "whitespace inside and between paragraphs"},
                {"<p class=\"ql-align-center\">centered</p><p>after</p>",
                        "<div class=\"ql-align-center\">centered</div>after",
                        "an aligned paragraph keeps its attributes as a block, which separates itself"},
                {"<p>before</p><p class=\"ql-align-right\">right</p>",
                        "before<div class=\"ql-align-right\">right</div>", "a block after a line needs no break"},
                {"<p>before</p><p><br></p><p class=\"ql-align-right\">right</p>",
                        "before<br /><div class=\"ql-align-right\">right</div>",
                        "but a blank line the author put before a block is honoured"},
                {"<p class=\"a\">x</p><p class=\"b\">y</p>", "<div class=\"a\">x</div><div class=\"b\">y</div>",
                        "two blocks, no break between"},
                {"<p>intro</p><ul><li>item</li></ul><p>outro</p>", "intro<ul><li>item</li></ul>outro",
                        "a list passes through untouched and counts as a block on both sides"},
                {"<h1>Title</h1><p>line</p>", "<h1>Title</h1>line", "a heading passes through"},
                {"plain text with no markup", "plain text with no markup", "no paragraphs: unchanged"},
                {"already<br>broken", "already<br />broken", "no paragraphs, but breaks still normalized"},
                {"<p>one</p><p>two</p><p><br></p>", "one<br />two", "a trailing blank after real lines is dropped"},
        };
    }

    @Test(dataProvider = "conversions")
    public void paragraphsBecomeBreaks(final String in, final String expected, final String why) {
        Assert.assertEquals(RichText.paragraphsToBreaks(in), expected, why);
    }

    @Test
    public void theResultContainsNoParagraphTags() {
        // Whatever the shape, the point of the exercise is that nothing renders with paragraph spacing.
        final String out = RichText.paragraphsToBreaks("<p>a</p><p class=\"x\">b</p><p><br></p><p>c</p>");
        Assert.assertFalse(out.toLowerCase().contains("<p"), out);
        Assert.assertFalse(out.contains("<br>"), "every break is the XHTML spelling: " + out);
    }

    @Test(expectedExceptions = Exception.class)
    public void isNotInstantiable() throws Exception {
        final var ctor = RichText.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        ctor.newInstance();
    }
}
