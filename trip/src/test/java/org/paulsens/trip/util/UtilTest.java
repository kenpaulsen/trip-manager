package org.paulsens.trip.util;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.List;
import org.testng.annotations.Test;

public class UtilTest {

    /** One wording for every name list in a ledger row, a lodging bill and a report: the serial comma's. */
    @Test
    public void aNameListReadsAsASentence() {
        assertEquals(Util.joinNames(List.of("Joe")), "Joe");
        assertEquals(Util.joinNames(List.of("Joe", "Bob")), "Joe and Bob");
        assertEquals(Util.joinNames(List.of("Joe", "Bob", "Mike")), "Joe, Bob, and Mike");
        assertEquals(Util.joinNames(List.of("A", "B", "C", "D")), "A, B, C, and D");
        assertEquals(Util.joinNames(List.of()), "");
        assertEquals(Util.joinNames(null), "");
    }

    @Test
    public void blankAndDefault() {
        assertTrue(Util.isBlank(null));
        assertTrue(Util.isBlank("  "));
        assertFalse(Util.isBlank("x"));
        assertEquals(Util.orDefault(null, "d"), "d");
        assertEquals(Util.orDefault("v", "d"), "v");
    }

    @Test
    public void blankToNullKeepsTheValueAsWritten() {
        assertNull(Util.blankToNull(null));
        assertNull(Util.blankToNull("  "));
        assertEquals(Util.blankToNull(" x "), " x ", "not trimmed: trimToNull is the trimming one");
    }

    /**
     * "Nothing to keep" is one answer however the nothing is spelled. trim() and isBlank() disagree about it:
     * trim() strips control characters that isBlank() does not count as blank, and isBlank() counts Unicode
     * spaces that trim() keeps. Whichever was asked first, the other kind came back as text ("" or " ").
     */
    @Test
    public void trimToNullAnswersNullForEveryKindOfNothing() {
        assertNull(Util.trimToNull(null));
        assertNull(Util.trimToNull(""));
        assertNull(Util.trimToNull(" \t\r\n"));
        assertNull(Util.trimToNull("\u2003\u2009\u3000"),
                "Unicode spaces only: isBlank() counts them, trim() keeps them");
        assertNull(Util.trimToNull("\u0001\u0007\u001b"), "control characters only: trim() strips them to \"\"");
        assertNull(Util.trimToNull(" \u0001\u2003\u0002 "), "and a mix of both");
    }

    @Test
    public void trimToNullAndTrimmedTrim() {
        assertEquals(Util.trimToNull(" x "), "x");
        assertEquals(Util.trimToNull("\u0001 x y\u0002"), "x y");
        assertEquals(Util.trimmed(null), "");
        assertEquals(Util.trimmed(" x "), "x");
    }

    @Test
    public void truncateCutsWithoutAMarker() {
        assertNull(Util.truncate(null, 3));
        assertEquals(Util.truncate("abc", 3), "abc");
        assertEquals(Util.truncate("abcd", 3), "abc");
    }
}
