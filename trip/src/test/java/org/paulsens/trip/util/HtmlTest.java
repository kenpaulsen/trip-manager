package org.paulsens.trip.util;

import org.testng.Assert;
import org.testng.annotations.Test;

public class HtmlTest {
    private static final String ALL = "a&b <i>\"quoted\"</i> it's";

    @Test
    public void escapeCoversElementAndAttributeContexts() {
        Assert.assertEquals(Html.escape("&<>\"'"), "&amp;&lt;&gt;&quot;&#39;");
        Assert.assertEquals(Html.escape(ALL), "a&amp;b &lt;i&gt;&quot;quoted&quot;&lt;/i&gt; it&#39;s");
        Assert.assertEquals(Html.escape("plain"), "plain");
    }

    /** Stored markup keeps its apostrophes, so an author never meets an entity in a name they typed. */
    @Test
    public void theStoredFormLeavesApostrophesAlone() {
        Assert.assertEquals(Html.escapeKeepingApostrophes(ALL), "a&amp;b &lt;i&gt;&quot;quoted&quot;&lt;/i&gt; it's");
    }

    @Test
    public void theTextFormEscapesMarkupOnly() {
        Assert.assertEquals(Html.escapeText(ALL), "a&amp;b &lt;i&gt;\"quoted\"&lt;/i&gt; it's");
    }

    /** An already-escaped ampersand is escaped again: the input is text, never markup. */
    @Test
    public void anAmpersandIsEscapedOnce() {
        Assert.assertEquals(Html.escape("&lt;"), "&amp;lt;");
    }

    @Test
    public void nullEscapesToEmpty() {
        Assert.assertEquals(Html.escape(null), "");
        Assert.assertEquals(Html.escapeKeepingApostrophes(null), "");
        Assert.assertEquals(Html.escapeText(null), "");
    }
}
