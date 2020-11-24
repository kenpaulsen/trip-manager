package com.sun.jsft.util;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * @author Ken Paulsen (kenapaulsen@gmail.com)
 */
public class UtilTest {
    @Test
    public void testHtmlEscape() {
	Assert.assertNull(Util.htmlEscape(null));
	// Test <> and &
	Assert.assertEquals("&lt;p&gt;&amp;nbsp;&lt;/p&gt;", Util.htmlEscape("<p>&nbsp;</p>"));
	// Test quotes and backslash (backslash should passthru)
	Assert.assertEquals("&quot;\\&quot;", Util.htmlEscape("\"\\\""));
	// Test single quotes and backslash
	Assert.assertEquals("&#39;\\&#39;", Util.htmlEscape("'\\'"));
	// Test single / double quotes in middle of string
	Assert.assertEquals("text &#39; foo &quot; more text", Util.htmlEscape("text ' foo \" more text"));
	// Test all escape characters together
	Assert.assertEquals("&#39;\\&quot;&lt;&gt;&amp;", Util.htmlEscape("'\\\"<>&"));
    }

    @Test
    public void testUnHtmlEscape() {
	// Noop tests...
	Assert.assertNull(Util.unHtmlEscape(null));
	Assert.assertEquals("", Util.unHtmlEscape(""));
	Assert.assertEquals("A", Util.unHtmlEscape("A"));
	Assert.assertEquals("&", Util.unHtmlEscape("&"));
	Assert.assertEquals("&amp", Util.unHtmlEscape("&amp"));
	Assert.assertEquals("& amp;", Util.unHtmlEscape("& amp;"));
	// Test single entity case...
	Assert.assertEquals("\"", Util.unHtmlEscape("&quot;"));
	Assert.assertEquals("'", Util.unHtmlEscape("&#39;"));
	// Spacing test...
	Assert.assertEquals(" ' ", Util.unHtmlEscape(" &#39; "));
	// Capital test...
	Assert.assertEquals("\"", Util.unHtmlEscape("&QuOt;"));
	// No-double encode test...
	Assert.assertEquals("&amp;", Util.unHtmlEscape("&amp;amp;"));
	// Test <> and &
	Assert.assertEquals("<p>&nbsp;</p>", Util.unHtmlEscape("&lt;p&gt;&amp;nbsp;&lt;/p&gt;"));
	// Test quotes and backslash (backslash should passthru)
	Assert.assertEquals("\"\\\"", Util.unHtmlEscape("&quot;\\&quot;"));
	// Test single quotes and backslash
	Assert.assertEquals("'\\'", Util.unHtmlEscape("&#39;\\&#39;"));
	// Test single / double quotes in middle of string
	Assert.assertEquals("text ' foo \" more text", Util.unHtmlEscape("text &#39; foo &quot; more text"));
	// Test all entities together
	Assert.assertEquals("'\\\"<>&", Util.unHtmlEscape("&#39;\\&quot;&lt;&gt;&amp;"));
    }

    /**
     *	This test uses both htmlEscape and unEscape to ensure they mirror
     *	eachother.
     */
    @Test
    public void testEscaping() {
	// Test single entity case...
	Assert.assertEquals("\"", Util.unHtmlEscape(Util.htmlEscape("\"")));
	Assert.assertEquals("'", Util.unHtmlEscape(Util.htmlEscape("'")));
	// Spacing test...
	Assert.assertEquals(" ' ", Util.unHtmlEscape(Util.htmlEscape(" ' ")));
	// Capital test...
	Assert.assertEquals("\"", Util.unHtmlEscape(Util.htmlEscape("\"")));
	// No-double encode test...
	Assert.assertEquals("&amp;", Util.unHtmlEscape(Util.htmlEscape("&amp;")));
	// Test <> and &
	Assert.assertEquals("<p>&nbsp;</p>", Util.unHtmlEscape(Util.htmlEscape("<p>&nbsp;</p>")));
	// Test quotes and backslash (backslash should passthru)
	Assert.assertEquals("\"\\\"", Util.unHtmlEscape(Util.htmlEscape("\"\\\"")));
	// Test single quotes and backslash
	Assert.assertEquals("'\\'", Util.unHtmlEscape(Util.htmlEscape("'\\'")));
	// Test single / double quotes in middle of string
	Assert.assertEquals("text ' foo \" more text", Util.unHtmlEscape(Util.htmlEscape("text ' foo \" more text")));
	// Test all entities together
	Assert.assertEquals("'\\\"<>&", Util.unHtmlEscape(Util.htmlEscape("'\\\"<>&")));
    }
}
