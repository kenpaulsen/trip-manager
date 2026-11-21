package org.paulsens.trip.content;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What travels AROUND the sanitizer for ONE body on its way through {@link PageHtml}: its HTML comments and its
 * {@code style} blocks, taken out before the sanitizer runs and put back into what it wrote.
 *
 * <p>Why comments are kept at all (the owner's decision, 2026-11-17): authors park text in them. Nineteen trips in
 * production hold a commented-out notice ("this trip is full") or an alternate paragraph to bring back next year,
 * and the sanitizer, like every sanitizer, throws comments away. Losing them would delete stored words nobody
 * agreed to delete, where no screenshot would show it.
 *
 * <p>Why style blocks are kept (the owner's decision, 2026-11-21): the people who can edit these pages hold a
 * granted role, and the advanced ones restyle parts of the page outside their own section on purpose, so a block
 * is neither refused nor confined to its section. Its CSS goes back as typed except for the two rules
 * {@link PageCss#block} refuses. The sanitizer cannot carry a block itself: it drops the element, or escapes its
 * text.
 *
 * <p>Neither a comment's text nor a block's CSS is cleaned as markup: it is whatever was typed, so it is safe only
 * for as long as a browser reads it as a comment, or as CSS. Three things make that so.
 * <ul>
 *   <li>It cannot end early. A comment ends at {@code -->} or {@code --!>}, and a browser also ends one that
 *       begins with {@code >} or {@code ->} right there; a body with any of those is not kept. (The first cannot
 *       occur: a body is what precedes the first {@code -->}.) A block ends at the first {@code </style} in any
 *       case, which is where its CSS is cut here too, so the CSS never holds one.</li>
 *   <li>It only goes where text goes. Each piece travels through the sanitizer as a MARKER, a random word in
 *       the text, and the marker is removed from every attribute value and from inside a frame, whose content a
 *       browser reads raw. What the sanitizer writes for a marker in text is that same word, in a place where a
 *       comment is a comment and a style block is a style block.</li>
 *   <li>A comment is not the old Internet Explorer's conditional comment ({@code [if ...]}), which was markup to
 *       that browser and is nobody's parked text.</li>
 * </ul>
 * A piece that fails any of these is dropped and reported, like anything else the cleaner removes.
 *
 * <p>One place needs care: directly inside a list or a table, between its items or rows. The sanitizer wraps
 * text there in an item of its own, or moves it out of the table, either of which a reader would see. So a marker
 * met there waits ({@link #hold}) and is written at the start of the next text that is somewhere ordinary, or at
 * the end of the body: the piece moves a few characters, and the page does not change.
 */
final class PageAsides {

    private static final String OPEN = "<!--";
    private static final String CLOSE = "-->";
    private static final String STYLE_OPEN = "<style";
    private static final String STYLE_CLOSE = "</style";
    /** A block's {@code media} is the one attribute kept: a print stylesheet must stay a print stylesheet. */
    private static final Pattern MEDIA = Pattern.compile(
            "(?i)(?:^|\\s)media\\s*=\\s*(?:\"([A-Za-z0-9 (),.:-]{1,120})\"|'([A-Za-z0-9 (),.:-]{1,120})')");

    /** For a body with neither in it, which is nearly every body: nothing to carry, and nothing to set up. */
    private static final PageAsides NONE = new PageAsides("none");

    private final String prefix;
    private final Pattern marker;
    /** Each kept piece as it will be written back, in the order met. */
    private final List<String> pieces = new ArrayList<>();
    /** What to call each piece if its marker does not come back, same order. */
    private final List<PageHtml.Drop> lost = new ArrayList<>();
    private final List<String> waiting = new ArrayList<>();
    /** What the last take left in the body's place: a marker, or nothing for a piece that was not kept. */
    private String taken = "";

    private PageAsides(final String word) {
        this.prefix = "tripc" + word + "x";
        this.marker = Pattern.compile(prefix + "([0-9]{1,6})x");
    }

    /**
     * The carrier for one body. Its marker word is random and new each time, so no body can be written ahead of
     * time to contain it.
     */
    static PageAsides of(final String html) {
        return html.contains(OPEN) || asciiLower(html).contains(STYLE_OPEN)
                ? new PageAsides(UUID.randomUUID().toString().replace("-", "")) : NONE;
    }

    /** Lower case for A to Z only, so every index into the result is the same index into the original. */
    private static String asciiLower(final String html) {
        final char[] chars = html.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] >= 'A' && chars[i] <= 'Z') {
                chars[i] = (char) (chars[i] + ('a' - 'A'));
            }
        }
        return new String(chars);
    }

    /** Whether the body being cleaned had anything to carry; when not, every method here is a no-op. */
    boolean any() {
        return !pieces.isEmpty();
    }

    /**
     * The body with each keepable comment and style block replaced by its marker, and every other comment
     * removed (and reported). ONE pass, in order, because each hides the other: a {@code <style} inside a comment
     * is the comment's words, and a {@code <!--} inside a block is CSS. A {@code <!--} or a block that never
     * closes is left, with all that follows it, for the sanitizer, which drops it as a browser would.
     */
    String takeFrom(final String html, final List<PageHtml.Drop> drops) {
        if (this == NONE) {
            return html;
        }
        final String lower = asciiLower(html);
        final StringBuilder out = new StringBuilder(html.length());
        int copied = 0;
        int next = nextAside(lower, 0);
        while (next >= 0) {
            final int end = lower.startsWith(OPEN, next)
                    ? takeComment(html, next, drops) : takeStyle(html, lower, next, drops);
            if (end < 0) {
                break;
            }
            out.append(html, copied, next).append(taken);
            copied = end;
            next = nextAside(lower, copied);
        }
        return out.append(html, copied, html.length()).toString();
    }

    private static int nextAside(final String lower, final int from) {
        final int comment = lower.indexOf(OPEN, from);
        final int style = styleOpen(lower, from);
        return comment < 0 || (style >= 0 && style < comment) ? style : comment;
    }

    /** The next {@code <style} that is a tag's name and not the start of a longer one ({@code <styled-box}). */
    private static int styleOpen(final String lower, final int from) {
        int at = lower.indexOf(STYLE_OPEN, from);
        while (at >= 0) {
            final int after = at + STYLE_OPEN.length();
            if (after < lower.length() && (lower.charAt(after) == '>' || lower.charAt(after) == '/'
                    || Character.isWhitespace(lower.charAt(after)))) {
                return at;
            }
            at = lower.indexOf(STYLE_OPEN, after);
        }
        return -1;
    }

    /** @return where the body resumes after this comment, or -1 when it never closes. */
    private int takeComment(final String html, final int open, final List<PageHtml.Drop> drops) {
        final int close = html.indexOf(CLOSE, open + OPEN.length());
        if (close < 0) {
            return -1;
        }
        final String body = html.substring(open + OPEN.length(), close);
        if (keepable(body)) {
            keep(OPEN + body + CLOSE, dropOf(body));
        } else {
            drops.add(dropOf(body));
            taken = "";
        }
        return close + CLOSE.length();
    }

    /** @return where the body resumes after this block, or -1 when its tag or the block never closes. */
    private int takeStyle(final String html, final String lower, final int open,
            final List<PageHtml.Drop> drops) {
        final int tagEnd = html.indexOf('>', open);
        final int close = tagEnd < 0 ? -1 : lower.indexOf(STYLE_CLOSE, tagEnd + 1);
        if (close < 0) {
            return -1;
        }
        final String css = PageCss.block(html.substring(tagEnd + 1, close),
                rule -> drops.add(new PageHtml.Drop(PageHtml.Drop.CSS_RULE, PageHtml.STYLE, rule)));
        if (css.isBlank()) {
            taken = "";
        } else {
            final Matcher media = MEDIA.matcher(html.substring(open + STYLE_OPEN.length(), tagEnd));
            final String attribute = media.find()
                    ? " media=\"" + (media.group(1) == null ? media.group(2) : media.group(1)) + "\"" : "";
            keep(STYLE_OPEN + attribute + ">" + css + STYLE_CLOSE + ">",
                    new PageHtml.Drop(PageHtml.Drop.TAG, PageHtml.STYLE, ""));
        }
        final int end = html.indexOf('>', close);
        return end < 0 ? html.length() : end + 1;
    }

    private void keep(final String piece, final PageHtml.Drop ifLost) {
        taken = prefix + pieces.size() + "x";
        pieces.add(piece);
        lost.add(ifLost);
    }

    private static boolean keepable(final String body) {
        return !body.startsWith(">") && !body.startsWith("->") && !body.contains("--!>")
                && !body.stripLeading().startsWith("[if");
    }

    private static PageHtml.Drop dropOf(final String body) {
        final String shown = body.strip().replaceAll("\\s+", " ");
        return new PageHtml.Drop(PageHtml.Drop.COMMENT, "",
                shown.length() > 40 ? shown.substring(0, 40) + "..." : shown);
    }

    /** The text with no marker in it: for an attribute value, and for the inside of a frame. */
    String without(final String text) {
        return any() && text.contains(prefix) ? marker.matcher(text).replaceAll("") : text;
    }

    /** The text with its markers taken out and kept waiting, for a place where the sanitizer keeps no text. */
    String hold(final String text) {
        if (!any() || !text.contains(prefix)) {
            return text;
        }
        final Matcher found = marker.matcher(text);
        while (found.find()) {
            waiting.add(found.group());
        }
        return found.replaceAll("");
    }

    /** The text with every waiting marker in front of it: the first ordinary text after a list or table edge. */
    String release(final String text) {
        if (waiting.isEmpty()) {
            return text;
        }
        final String released = String.join("", waiting) + text;
        waiting.clear();
        return released;
    }

    /**
     * What the sanitizer wrote, with each marker turned back into its piece, and any piece still waiting
     * written at the end. A piece whose marker did not come through (its surroundings were dropped, or it sat in
     * an attribute or a frame) is reported.
     */
    String restoreInto(final String cleaned, final List<PageHtml.Drop> drops) {
        if (!any()) {
            return cleaned;
        }
        final Set<Integer> written = new HashSet<>();
        final StringBuilder out = new StringBuilder(cleaned.length() + 64);
        final Matcher found = marker.matcher(cleaned);
        while (found.find()) {
            found.appendReplacement(out, "");
            write(Integer.parseInt(found.group(1)), written, out);
        }
        found.appendTail(out);
        for (final String held : waiting) {
            final Matcher index = marker.matcher(held);
            if (index.matches()) {
                write(Integer.parseInt(index.group(1)), written, out);
            }
        }
        waiting.clear();
        for (int i = 0; i < pieces.size(); i++) {
            if (!written.contains(i)) {
                drops.add(lost.get(i));
            }
        }
        return out.toString();
    }

    /** Each piece is written once: a marker the sanitizer somehow wrote twice does not double it. */
    private void write(final int index, final Set<Integer> written, final StringBuilder out) {
        if (index < pieces.size() && written.add(index)) {
            out.append(pieces.get(index));
        }
    }
}
