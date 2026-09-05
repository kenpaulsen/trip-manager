package org.paulsens.trip.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-processing for HTML that comes out of the site's WYSIWYG editor (Quill, via {@code p:textEditor}).
 *
 * <p>Quill wraps every line in a {@code <p>}, and the site's paragraph styling puts a full blank line between
 * them. For short, note-like text (a person's private notes on an itinerary event) that reads as double
 * spacing, so those fields want a {@code <br />} per Enter instead. This is the one place that rewrite lives.
 */
public final class RichText {
    private static final String BR = "<br />";

    /** A whole paragraph: optional attributes, then the body up to the closing tag. */
    private static final Pattern PARAGRAPH = Pattern.compile("<p(\\s[^>]*)?>(.*?)</p>", Pattern.CASE_INSENSITIVE
            | Pattern.DOTALL);
    /** Any spelling of a line break, so the output uses exactly one. */
    private static final Pattern ANY_BR = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_BREAKS = Pattern.compile("(\\s*" + BR + ")+\\s*$");

    private RichText() {
        throw new UnsupportedOperationException("Do not instantiate utility class.");
    }

    /**
     * Replaces the editor's paragraph wrappers with line breaks.
     *
     * <ul>
     *   <li>A plain {@code <p>} is unwrapped; consecutive paragraphs are joined by one {@code <br />}.</li>
     *   <li>An empty paragraph ({@code <p><br></p>}, what Quill emits for a blank line) becomes one extra
     *       {@code <br />}, so deliberate blank lines survive.</li>
     *   <li>A paragraph that carries attributes (an alignment class, an inline style) is kept as a block: it
     *       becomes a {@code <div>} with the same attributes, since a break cannot carry them.</li>
     *   <li>Markup that is not a paragraph (a list, a heading) passes through untouched.</li>
     *   <li>Every {@code <br>} is normalized to {@code <br />}; leading and trailing breaks are dropped.</li>
     * </ul>
     *
     * @param html  the editor's output; {@code null} is treated as empty.
     * @return the same content with breaks instead of paragraphs, never {@code null}.
     */
    public static String paragraphsToBreaks(final String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        final String normalized = ANY_BR.matcher(html).replaceAll(BR);
        final Matcher m = PARAGRAPH.matcher(normalized);
        final StringBuilder out = new StringBuilder();
        // The state of the join between pieces: how many blank paragraphs are pending, and whether the piece
        // before them was a block (which already separates itself from what follows).
        int blankLines = 0;
        boolean afterBlock = false;
        int last = 0;
        while (m.find()) {
            afterBlock = appendPassThrough(out, normalized.substring(last, m.start())) || afterBlock;
            last = m.end();
            final String attrs = m.group(1);
            final String body = m.group(2).strip();
            if (attrs != null && !attrs.isBlank()) {
                appendBreaks(out, blankLines, afterBlock, true);
                out.append("<div").append(attrs).append('>').append(body).append("</div>");
                blankLines = 0;
                afterBlock = true;
            } else if (body.isEmpty() || BR.equals(body)) {
                blankLines++;
            } else {
                appendBreaks(out, blankLines, afterBlock, false);
                out.append(body);
                blankLines = 0;
                afterBlock = false;
            }
        }
        appendPassThrough(out, normalized.substring(last));
        return TRAILING_BREAKS.matcher(out.toString().strip()).replaceAll("");
    }

    /** Appends non-paragraph markup verbatim; returns whether anything was appended. */
    private static boolean appendPassThrough(final StringBuilder out, final String text) {
        if (text.isBlank()) {
            return false;
        }
        out.append(text.strip());
        return true;
    }

    /**
     * The breaks that belong between the previous piece and the next one. Nothing precedes the first piece,
     * so leading blank lines vanish. Two blocks (a div next to a div, or a div next to a line) need no
     * separator of their own, only the blank lines the author put between them; two lines need exactly one.
     */
    private static void appendBreaks(
            final StringBuilder out, final int blankLines, final boolean afterBlock, final boolean nextIsBlock) {
        if (out.isEmpty()) {
            return;
        }
        final int count = (afterBlock || nextIsBlock) ? blankLines : blankLines + 1;
        out.append(BR.repeat(count));
    }
}
