package org.paulsens.trip.content;

import java.util.List;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Placeholder;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * What the WYSIWYG editor adds and what an author actually wrote. The normalizer must remove Quill's
 * bookkeeping (the block wrapper on a one-line value, its trailing empty paragraph, its stylesheet-dependent
 * alignment classes) while leaving hand-authored markup -- which the Source toggle makes reachable --
 * byte-identical.
 */
public class RichTextRulesTest {

    @Test
    public void quillsWrapperGoesAwayOnASingleLineValue() {
        Assert.assertEquals(RichTextRules.normalize("<p>Thurs, <strong>Aug 27</strong></p>"),
                "Thurs, <strong>Aug 27</strong>");
        Assert.assertEquals(RichTextRules.normalize("<p>plain</p><p><br></p>"), "plain",
                "the trailing empty paragraph is Quill's terminating line, not content");
        Assert.assertEquals(RichTextRules.normalize("<p>plain</p><p></p>  "), "plain");
        Assert.assertEquals(RichTextRules.normalize("<p>a</p><p>&nbsp;</p><p><br/></p>"), "a",
                "several trailing empties peel off");
    }

    @Test
    public void realParagraphsAreKept() {
        Assert.assertEquals(RichTextRules.normalize("<p>one</p><p>two</p>"), "<p>one</p><p>two</p>",
                "multiple blocks are the author's meaning, not the editor's");
        Assert.assertEquals(RichTextRules.normalize("<p>line<br>break</p>"), "line<br>break",
                "a shift-enter break stays inside the one block that is then unwrapped");
        Assert.assertEquals(RichTextRules.normalize("<ul><li>a</li></ul>"), "<ul><li>a</li></ul>");
    }

    @Test
    public void alignmentBecomesAnInlineStyleAndKeepsItsBlock() {
        Assert.assertEquals(RichTextRules.normalize("<p class=\"ql-align-center\">hi</p>"),
                "<p style=\"text-align:center\">hi</p>",
                "alignment needs a block to live on, but must not need Quill's stylesheet");
        Assert.assertEquals(RichTextRules.normalize("<p class=\"ql-align-right\">a</p><p>b</p>"),
                "<p style=\"text-align:right\">a</p><p>b</p>");
        Assert.assertEquals(RichTextRules.normalize("<h1 class=\"ql-align-justify\">t</h1>"),
                "<h1 style=\"text-align:justify\">t</h1>");
        Assert.assertEquals(
                RichTextRules.normalize("<p class=\"keep ql-align-center\" style=\"color:red\">x</p>"),
                "<p class=\"keep\" style=\"text-align:center;color:red\">x</p>",
                "other classes and styles survive the rewrite");
        Assert.assertEquals(
                RichTextRules.normalize("<p class=\"ql-align-center\" style=\"text-align:left\">x</p>"),
                "<p style=\"text-align:left\">x</p>", "an explicit style wins over the class");
    }

    @Test
    public void handAuthoredMarkupSurvivesUntouched() {
        final String raw = "<div style=\"text-align:center\"><h1 style=\"margin-bottom:0px;\">T</h1>"
                + "<span style=\"font-size:1.3em\">by someone</span></div>";
        Assert.assertEquals(RichTextRules.normalize(raw), raw, "the Source toggle's output is authoritative");
        Assert.assertEquals(RichTextRules.normalize("<p style=\"margin:0\">x</p>"), "<p style=\"margin:0\">x</p>",
                "only an ATTRIBUTE-FREE wrapper is the editor's; one with attributes is the author's");
        Assert.assertEquals(RichTextRules.normalize("<p id=\"x\">y</p>"), "<p id=\"x\">y</p>");
        Assert.assertEquals(RichTextRules.normalize("<p>a</p> tail"), "<p>a</p> tail",
                "a value that does not END with the wrapper is not one paragraph");
        Assert.assertEquals(RichTextRules.normalize("<script>var a = '<p>';</script>"),
                "<script>var a = '<p>';</script>");
    }

    @Test
    public void emptyAndNullValuesPassThrough() {
        Assert.assertNull(RichTextRules.normalize(null));
        Assert.assertEquals(RichTextRules.normalize("   "), "   ");
        Assert.assertEquals(RichTextRules.normalize("<p><br></p>"), "", "an untouched editor stores nothing");
        Assert.assertEquals(RichTextRules.normalize("no markup at all"), "no markup at all");
    }

    @Test
    public void aRichTextTokenInsideAParagraphIsFlagged() {
        Assert.assertEquals(richTokens("<p><img src=\"{{imageUrl}}\">{{caption}}</p>"), List.of("caption"),
                "the exact shape that broke the Events image");
        Assert.assertEquals(richTokens("<div><p>x</p>{{caption}}</div>"), List.of(),
                "a closed paragraph earlier in the body is not a container");
        Assert.assertEquals(richTokens("<div style=\"text-align:center\">{{caption}}</div>"), List.of());
        Assert.assertEquals(richTokens("<p><em>{{caption}}</em></p>"), List.of("caption"),
                "nesting depth inside the paragraph does not matter");
        Assert.assertEquals(richTokens("<p>{{width}}</p>"), List.of(),
                "only RICH_TEXT placeholders are block content");
        Assert.assertEquals(richTokens("<!-- <p> --><div>{{caption}}</div>"), List.of(),
                "a paragraph inside a comment opens nothing");
    }

    @Test
    public void bodiesWithNothingToWarnAboutAnswerEmpty() {
        Assert.assertEquals(RichTextRules.richTextTokensInsideParagraph(null), List.of());
        Assert.assertEquals(RichTextRules.richTextTokensInsideParagraph(
                new ContentTemplate("t", 1, "T", "", null, List.of(), null, null)), List.of());
        Assert.assertEquals(RichTextRules.richTextTokensInsideParagraph(
                new ContentTemplate("t", 1, "T", "", "<p>{{a}}</p>",
                        List.of(new Placeholder("a", Placeholder.Type.TEXT, "A", null, false)), null, null)),
                List.of(), "no rich-text placeholder, nothing to warn about");
    }

    @Test
    public void theStarterTemplatesHostTheirRichTextCorrectly() {
        for (final ContentTemplate starter : StarterTemplates.all()) {
            Assert.assertEquals(RichTextRules.richTextTokensInsideParagraph(starter), List.of(),
                    "a shipped template must not place rich text inside a <p>: " + starter.getId());
        }
    }

    /** A body with {@code caption} declared RICH_TEXT and {@code width} TEXT, scanned for nested tokens. */
    private List<String> richTokens(final String body) {
        return RichTextRules.richTextTokensInsideParagraph(new ContentTemplate("img", 1, "Image", "", body,
                List.of(new Placeholder("caption", Placeholder.Type.RICH_TEXT, "Caption", null, false),
                        new Placeholder("width", Placeholder.Type.TEXT, "Width", null, false)),
                null, null));
    }
}
