package org.paulsens.trip.security;

import java.util.HashSet;
import java.util.Set;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class TicketCodesTest {
    @Test
    public void codesAreLongUpperCaseBase32AndDistinct() {
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            final String code = TicketCodes.mint();
            assertEquals(code.length(), TicketCodes.LENGTH);
            assertTrue(code.matches("[A-Z2-7]{26}"), code);
            assertTrue(seen.add(code), "a repeat in 1000 mints means the randomness is broken");
        }
    }

    @Test
    public void encodingMatchesRfc4648() {
        // RFC 4648 test vector: "foobar" is MZXW6YTBOI; 16 zero bytes are 26 A's.
        assertEquals(TicketCodes.encode("foobar".getBytes(java.nio.charset.StandardCharsets.US_ASCII)), "MZXW6YTBOI");
        assertEquals(TicketCodes.encode(new byte[16]), "A".repeat(26));
    }

    @Test
    public void typedAndGroupedFormsNormalizeBack() {
        final String code = TicketCodes.mint();
        assertEquals(TicketCodes.normalize(TicketCodes.grouped(code)), code);
        assertEquals(TicketCodes.normalize(" " + code.toLowerCase() + " "), code);
        final String lookalikes = "O" + "I" + "B" + code.substring(3);
        assertEquals(TicketCodes.normalize("018" + code.substring(3)), lookalikes,
                "0, 1 and 8 are typed for O, I and B");
        assertNull(TicketCodes.normalize(null));
        assertNull(TicketCodes.normalize("too-short"));
        assertNull(TicketCodes.normalize(code + "A"));
        assertNull(TicketCodes.normalize(code.substring(1) + "!"));
        assertEquals(TicketCodes.grouped("ABCDEFGHIJ"), "ABCD-EFGH-IJ");
        assertEquals(TicketCodes.grouped(null), "");
    }

    @Test
    public void matchingIsExactAfterNormalizing() {
        final String code = TicketCodes.mint();
        assertTrue(TicketCodes.matches(code, TicketCodes.grouped(code).toLowerCase()));
        assertFalse(TicketCodes.matches(code, TicketCodes.mint()));
        assertFalse(TicketCodes.matches(code, code.substring(0, 20)), "a prefix never matches");
        assertFalse(TicketCodes.matches(null, code));
        assertFalse(TicketCodes.matches(code, null));
    }
}
