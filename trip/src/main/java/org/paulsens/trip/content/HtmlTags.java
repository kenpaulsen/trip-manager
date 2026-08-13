package org.paulsens.trip.content;

import java.util.Locale;
import java.util.Set;

/**
 * The lenient tag scanner shared by the HTML rules in this package ({@link HtmlFragmentValidator}'s
 * structural check and {@link RichTextRules}' block-context rules). Deliberately not a parser: it reads one
 * tag at a time and knows only what both callers need -- the element name, whether the tag self-closes, and
 * where it ends -- so the two never disagree about where a {@code <p>} begins or ends.
 */
final class HtmlTags {

    /** Elements with no closing tag, per the HTML spec's void list. */
    static final Set<String> VOID_ELEMENTS = Set.of("area", "base", "br", "col", "embed", "hr",
            "img", "input", "link", "meta", "source", "track", "wbr");
    /** Elements whose content is raw text: nothing inside parses as markup until their own end tag. */
    static final Set<String> RAW_TEXT_ELEMENTS = Set.of("script", "style");

    private HtmlTags() {
    }

    /**
     * Reads a tag whose name starts at {@code nameStart}, honoring quoted attribute values (a '>' inside
     * quotes does not end the tag).
     *
     * @return the tag, or null when no name is present or the tag never terminates.
     */
    static Tag readTag(final String html, final int nameStart) {
        int i = nameStart;
        while (i < html.length() && (Character.isLetterOrDigit(html.charAt(i)) || html.charAt(i) == '-')) {
            i++;
        }
        if (i == nameStart) {
            return null;
        }
        final String name = html.substring(nameStart, i).toLowerCase(Locale.ROOT);
        boolean selfClosing = false;
        while (i < html.length()) {
            final char c = html.charAt(i);
            if (c == '"' || c == '\'') {
                final int endQuote = html.indexOf(c, i + 1);
                if (endQuote < 0) {
                    return null;                         // unterminated attribute value
                }
                i = endQuote + 1;
            } else if (c == '>') {
                return new Tag(name, selfClosing, i + 1);
            } else {
                selfClosing = c == '/';
                i++;
            }
        }
        return null;                                     // ran off the end without '>'
    }

    static int skipPast(final String html, final int from, final char stop) {
        final int at = html.indexOf(stop, from);
        return at < 0 ? html.length() : at + 1;
    }

    record Tag(String name, boolean selfClosing, int end) {
    }
}
