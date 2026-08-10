package org.paulsens.trip.content;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The structural HTML guard: everything an admin can legitimately author must pass, and the classic
 * page-breaking mistakes (unclosed tags, misnesting, runaway quotes/comments) must be caught with a
 * message a non-developer can act on.
 */
public class HtmlFragmentValidatorTest {

    @Test
    public void soundFragmentsPass() {
        Assert.assertNull(HtmlFragmentValidator.validate(null));
        Assert.assertNull(HtmlFragmentValidator.validate("   "));
        Assert.assertNull(HtmlFragmentValidator.validate("plain text, no markup"));
        Assert.assertNull(HtmlFragmentValidator.validate("<p>hello <b>world</b></p>"));
        Assert.assertNull(HtmlFragmentValidator.validate("<div><ul><li>a</li><li>b</li></ul></div>"));
        Assert.assertNull(HtmlFragmentValidator.validate("<br><img src=\"x.jpg\"><hr>"),
                "void elements need no close");
        Assert.assertNull(HtmlFragmentValidator.validate("<img src=\"x.jpg\" />"), "self-closing is fine");
        Assert.assertNull(HtmlFragmentValidator.validate("<a href=\"/x?a=1&b=2\" title='it''s'>x</a>"));
        Assert.assertNull(HtmlFragmentValidator.validate("1 < 2 and 3 > 2"),
                "a lone < that starts no tag is text");
        Assert.assertNull(HtmlFragmentValidator.validate("<!-- note --><p>x</p>"));
        Assert.assertNull(HtmlFragmentValidator.validate("<div title=\"a > b\">quoted angle</div>"),
                "a > inside a quoted attribute does not end the tag");
        Assert.assertNull(HtmlFragmentValidator.validate("<script>if (a < b) { x(); }</script>"),
                "raw-text elements hide their content");
        Assert.assertNull(HtmlFragmentValidator.validate("<p>{{body}}</p>"), "tokens are plain text");
    }

    @Test
    public void theStarterBodiesAreTheRegressionCorpus() {
        for (final var starter : StarterTemplates.all()) {
            Assert.assertNull(HtmlFragmentValidator.validate(starter.getBody()),
                    "starter body must validate: " + starter.getId());
        }
    }

    @Test
    public void brokenFragmentsAreCaughtWithReadableMessages() {
        Assert.assertTrue(HtmlFragmentValidator.validate("<div><p>oops</p>")
                .contains("<div> is never closed"));
        Assert.assertTrue(HtmlFragmentValidator.validate("<div><b>oops</div></b>")
                .contains("tags must nest"));
        Assert.assertTrue(HtmlFragmentValidator.validate("</p> came from nowhere")
                .contains("no matching opening tag"));
        Assert.assertTrue(HtmlFragmentValidator.validate("<p class=\"unterminated>x</p>") != null,
                "a runaway attribute quote is caught");
        Assert.assertTrue(HtmlFragmentValidator.validate("<p")
                .contains("never closed with '>'"));
        Assert.assertTrue(HtmlFragmentValidator.validate("<!-- runaway comment <p>x</p>")
                .contains("comment"));
        Assert.assertTrue(HtmlFragmentValidator.validate("<script>never ends")
                .contains("<script> is never closed"));
    }
}
