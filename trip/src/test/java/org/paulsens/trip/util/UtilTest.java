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

    @Test
    public void trimToNullAndTrimmedTrim() {
        assertNull(Util.trimToNull(null));
        assertNull(Util.trimToNull(" \t"));
        assertEquals(Util.trimToNull(" x "), "x");
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
