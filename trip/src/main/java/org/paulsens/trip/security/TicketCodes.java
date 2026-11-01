package org.paulsens.trip.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Event ticket codes: the bearer secret a registration's ticket page and check-in QR carry (see
 * {@code docs/checkin.md}). 128 random bits as 26 RFC 4648 base32 characters (A-Z, 2-7, no padding): long
 * enough that guessing one is hopeless, so the ticket page needs no sign-in, and free of the lower-case and
 * punctuation that make a code hard to read aloud at a check-in table.
 *
 * <p>The mir2026 branch encoded the person's UUID in its QR, which made the confirmation page a PII lookup for
 * anyone holding an id; a ticket code identifies a registration and nothing else.
 */
public final class TicketCodes {
    /** Characters in a code: 128 bits in 5-bit symbols, rounded up. */
    public static final int LENGTH = 26;

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int BYTES = 16;
    private static final int GROUP = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TicketCodes() {
    }

    /** A fresh random code. */
    public static String mint() {
        final byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        return encode(bytes);
    }

    /**
     * The canonical form of a typed or scanned code, or null when it cannot be one: upper-cased, spaces and
     * hyphens removed, and the digits people type for look-alike letters mapped back (0 to O, 1 to I, 8 to B;
     * base32 has no 0, 1 or 8, so the mapping never changes a valid code).
     */
    public static String normalize(final String presented) {
        if (presented == null) {
            return null;
        }
        final StringBuilder out = new StringBuilder(LENGTH);
        for (final char raw : presented.toCharArray()) {
            if (raw == ' ' || raw == '-') {
                continue;
            }
            final char c = lookalike(Character.toUpperCase(raw));
            if (ALPHABET.indexOf(c) < 0) {
                return null;
            }
            out.append(c);
        }
        return out.length() == LENGTH ? out.toString() : null;
    }

    /** A code split into groups of four for display: {@code MFRG-GZDF-...}. */
    public static String grouped(final String code) {
        if (code == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < code.length(); i += GROUP) {
            if (i > 0) {
                out.append('-');
            }
            out.append(code, i, Math.min(code.length(), i + GROUP));
        }
        return out.toString();
    }

    /** Constant-time comparison of a stored code with a presented one (normalized first); false for nulls. */
    public static boolean matches(final String stored, final String presented) {
        final String normalized = normalize(presented);
        if (stored == null || normalized == null) {
            return false;
        }
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII),
                normalized.getBytes(StandardCharsets.US_ASCII));
    }

    private static char lookalike(final char c) {
        switch (c) {
            case '0':
                return 'O';
            case '1':
                return 'I';
            case '8':
                return 'B';
            default:
                return c;
        }
    }

    static String encode(final byte[] bytes) {
        final StringBuilder out = new StringBuilder(LENGTH);
        int buffer = 0;
        int bits = 0;
        for (final byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return out.toString();
    }
}
