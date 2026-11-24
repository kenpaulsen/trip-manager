package org.paulsens.trip.util;

/**
 * HTML escaping for text spliced into markup this application writes (mail bodies, rendered content, seeded
 * pages). Never null: a null text escapes to {@code ""}.
 *
 * <p>Use {@link #escape} unless a site has a reason not to. The two narrower forms exist because what they
 * write is stored or compared as written, so widening their character set would change saved rows.
 */
public final class Html {
    private Html() {
    }

    /** Escapes {@code & < > " '}: safe in element content and in either kind of quoted attribute. */
    public static String escape(final String text) {
        return escape(text, true, true);
    }

    /**
     * Escapes {@code & < > "} and leaves an apostrophe alone: for markup that is STORED and later opened by an
     * author (a seeded page naming "St. Mary's"), who should not meet {@code &#39;} in their own text. Not for a
     * single-quoted attribute.
     */
    public static String escapeKeepingApostrophes(final String text) {
        return escape(text, true, false);
    }

    /** Escapes {@code & < >} only: element content, never an attribute value. */
    public static String escapeText(final String text) {
        return escape(text, false, false);
    }

    private static String escape(final String text, final boolean doubleQuotes, final boolean apostrophes) {
        if (text == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '&') {
                out.append("&amp;");
            } else if (c == '<') {
                out.append("&lt;");
            } else if (c == '>') {
                out.append("&gt;");
            } else if (c == '"' && doubleQuotes) {
                out.append("&quot;");
            } else if (c == '\'' && apostrophes) {
                out.append("&#39;");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
