package org.paulsens.trip.moderation;

import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The blocked-words matcher: whole words, any case, robust to a malformed list, off when the list is empty. */
public class ContentFilterTest {

    private final ContentFilter filter = new ContentFilter();

    @Test
    public void matchesWholeWordsInAnyCase() {
        Assert.assertEquals(filter.firstMatch("darn, heck", "Well DARN it."), "darn");
        Assert.assertEquals(filter.firstMatch("darn, heck", "what the heck!"), "heck");
        Assert.assertTrue(filter.blocks("darn", "darn"));
    }

    @Test
    public void doesNotMatchInsideOtherWords() {
        // "ass" inside "class" or "assist" is the classic false positive a substring filter produces.
        Assert.assertNull(filter.firstMatch("ass", "the class will assist"));
        Assert.assertNull(filter.firstMatch("darn", "darning socks"));
    }

    @Test
    public void emptyOrNullListMatchesNothing() {
        Assert.assertNull(filter.firstMatch("", "anything at all"));
        Assert.assertNull(filter.firstMatch(null, "anything at all"));
        Assert.assertNull(filter.firstMatch(" , , ", "anything at all"));
        Assert.assertFalse(filter.blocks("darn", null));
        Assert.assertFalse(filter.blocks("darn", ""));
    }

    @Test
    public void regexMetacharactersInTermsAreLiteral() {
        Assert.assertEquals(filter.firstMatch("a.b, c(d", "say a.b now"), "a.b");
        Assert.assertNull(filter.firstMatch("a.b", "say axb now"), "the dot must not be a wildcard");
        Assert.assertEquals(filter.firstMatch("c(d", "c(d"), "c(d");
    }

    @Test
    public void parseTrimsLowercasesAndDedupes() {
        Assert.assertEquals(ContentFilter.parse(" Darn ,heck, DARN ,, "), List.of("darn", "heck"));
        Assert.assertEquals(ContentFilter.parse(null), List.of());
    }

    @Test
    public void recompilesOnlyWhenTheListChanges() {
        // Same list twice, then a different one: the second list must be honoured (no stale cache).
        Assert.assertEquals(filter.firstMatch("darn", "darn"), "darn");
        Assert.assertEquals(filter.firstMatch("darn", "darn"), "darn");
        Assert.assertNull(filter.firstMatch("heck", "darn"));
        Assert.assertEquals(filter.firstMatch("heck", "heck"), "heck");
    }

    @Test
    public void unicodeWordsAndBoundaries() {
        Assert.assertEquals(filter.firstMatch("scheiße", "Ach, SCHEISSE nein"), null,
                "ß and ss are different words; no folding is attempted");
        Assert.assertEquals(filter.firstMatch("scheiße", "ach scheiße!"), "scheiße");
        Assert.assertEquals(filter.firstMatch("darn", "darn.darn"), "darn");
    }

    @Test
    public void sharedInstanceIsOne() {
        Assert.assertSame(ContentFilter.shared(), ContentFilter.shared());
    }
}
