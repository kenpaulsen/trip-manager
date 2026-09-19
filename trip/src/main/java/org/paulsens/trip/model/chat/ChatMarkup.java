package org.paulsens.trip.model.chat;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The formatting subset a chat body may carry, and the parser every server-side reading of a body goes through.
 *
 * <p>Bodies are stored exactly as typed (see {@code chat-design.md}, "escape on output, not on input"), so
 * formatting is a small inline markup that each surface renders in its own language: this class for the mails,
 * the push preview and the reply snippet; {@code chatFormat.js} for the browser pane and the photo-comment panel;
 * {@code ChatMarkup.swift} for the app. The grammar is documented once, in {@code docs/chat-formatting.md}, and
 * each implementation carries the same conformance table, so one body reads the same everywhere.
 *
 * <p>The grammar, in short:
 * <ul>
 *   <li>Inline, never across a newline: {@code **bold**}, {@code *italic*} or {@code _italic_},
 *       {@code __underline__}, {@code ~~strikethrough~~}; a run of three ({@code ***x***}, {@code ___x___}) is
 *       the double style plus italic. Markers sit at word boundaries: an opener is preceded by the start of the
 *       line or a non-alphanumeric character and followed by non-whitespace, a closer is preceded by
 *       non-whitespace and followed by the end of the line or a non-alphanumeric character. So {@code snake_case},
 *       {@code 2*3*4} and {@code **bold**text} stay literal, and an opener with no closer on its line is text.
 *       Styles nest; the innermost open style closes first.</li>
 *   <li>Opaque tokens the scanner steps over: {@code @{personId}} mention tokens and {@code http(s)://} URLs
 *       (to the next whitespace, minus trailing punctuation and marker characters, so {@code **https://x.org**} bolds
 *       the link).</li>
 *   <li>Blocks, line by line: a line starting {@code - }, {@code * } or {@code \u2022 } (after optional
 *       whitespace) is a bullet, one starting {@code 1. } or {@code 1) } a numbered item; consecutive lines of one
 *       kind form one list, and a numbered list starts at its first number. Everything else is a paragraph line.
 *       Lists are flat.</li>
 * </ul>
 *
 * <p>Pure functions only. {@code Serializable} solely to satisfy the model package sweep, as {@link ChatMentions}
 * is; nothing here is ever placed in a scope.
 */
public final class ChatMarkup implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** One of the four inline styles. */
    public enum Style { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH }

    /** An inline node: literal text, or a styled run of nodes. */
    public sealed interface Inline extends Serializable permits Text, Styled {
    }

    /** Literal text. Mention tokens and URLs arrive here untouched for the caller's own rules. */
    public record Text(String text) implements Inline {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.
    }

    /** A styled run; {@code children} may nest further styles. */
    public record Styled(Style style, List<Inline> children) implements Inline {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.
    }

    /** A block: a run of paragraph lines, or one list. */
    public sealed interface Block extends Serializable permits Paragraph, ListBlock {
    }

    /** Consecutive non-list lines, each already parsed; an empty line is an empty list of inlines. */
    public record Paragraph(List<List<Inline>> lines) implements Block {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.
    }

    /** One list. {@code start} is the first item's number for an ordered list (always 1 for bullets). */
    public record ListBlock(boolean ordered, int start, List<List<Inline>> items) implements Block {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.
    }

    /** The bullet glyph for the plain-text projection. */
    public static final String BULLET_GLYPH = "\u2022";

    private static final Pattern BULLET = Pattern.compile("^[ \\t]*[-*\u2022] (.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^[ \\t]*(\\d{1,3})[.)] (.*)$");
    private static final String MARKER_CHARS = "*_~";
    /** What a URL token leaves outside itself: sentence punctuation (the ChatLinks rule) and marker characters. */
    private static final String URL_TRAILING = ".,;:!?'\")]}>\u2026" + MARKER_CHARS;
    private static final int MAX_MENTION_ID = 128;

    private ChatMarkup() {
    }

    /** The body as blocks. A null or empty body has none. */
    public static List<Block> parse(final String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        final Blocks blocks = new Blocks();
        for (final String line : body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            final Matcher bullet = BULLET.matcher(line);
            final Matcher numbered = NUMBERED.matcher(line);
            if (bullet.matches()) {
                blocks.item(false, 1, bullet.group(1));
            } else if (numbered.matches()) {
                blocks.item(true, Integer.parseInt(numbered.group(1)), numbered.group(2));
            } else {
                blocks.line(line);
            }
        }
        return blocks.finish();
    }

    /** One line as inline nodes. Exposed for the conformance tests; {@link #parse} is the normal entry. */
    public static List<Inline> parseInline(final String line) {
        final List<Inline> out = new ArrayList<>();
        parseRange(line == null ? "" : line, 0, line == null ? 0 : line.length(), out);
        return List.copyOf(out);
    }

    /**
     * The body with the markers removed: what a push preview, a reply snippet or a copy shows. Each source line
     * stays one line; a bullet item gets the bullet glyph, a numbered item its number.
     */
    public static String plain(final String body) {
        final List<String> lines = new ArrayList<>();
        for (final Block block : parse(body)) {
            plainLines(block, lines);
        }
        return String.join("\n", lines);
    }

    /** The literal text of these inlines, markers dropped. */
    public static String text(final List<Inline> inlines) {
        final StringBuilder out = new StringBuilder();
        appendText(out, inlines);
        return out.toString();
    }

    private static void plainLines(final Block block, final List<String> lines) {
        if (block instanceof Paragraph paragraph) {
            for (final List<Inline> line : paragraph.lines()) {
                lines.add(text(line));
            }
        } else if (block instanceof ListBlock list) {
            for (int i = 0; i < list.items().size(); i++) {
                final String prefix = list.ordered() ? (list.start() + i) + ". " : BULLET_GLYPH + " ";
                lines.add(prefix + text(list.items().get(i)));
            }
        }
    }

    private static void appendText(final StringBuilder out, final List<Inline> inlines) {
        for (final Inline inline : inlines) {
            if (inline instanceof Text text) {
                out.append(text.text());
            } else if (inline instanceof Styled styled) {
                appendText(out, styled.children());
            }
        }
    }

    /**
     * Groups lines into blocks as {@link #parse} walks them. Serializable only because the model package sweep
     * ({@code ModelSerializationTest}) covers nested classes too; it never leaves this method.
     */
    private static final class Blocks implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.
        private final List<Block> out = new ArrayList<>();
        private final List<List<Inline>> lines = new ArrayList<>();
        private final List<List<Inline>> items = new ArrayList<>();
        private boolean ordered;
        private int start;

        void line(final String line) {
            closeList();
            lines.add(parseInline(line));
        }

        void item(final boolean isOrdered, final int number, final String content) {
            closeParagraph();
            if (!items.isEmpty() && ordered != isOrdered) {
                closeList();
            }
            if (items.isEmpty()) {
                ordered = isOrdered;
                start = number;
            }
            items.add(parseInline(content));
        }

        List<Block> finish() {
            closeParagraph();
            closeList();
            return List.copyOf(out);
        }

        private void closeParagraph() {
            if (!lines.isEmpty()) {
                out.add(new Paragraph(List.copyOf(lines)));
                lines.clear();
            }
        }

        private void closeList() {
            if (!items.isEmpty()) {
                out.add(new ListBlock(ordered, start, List.copyOf(items)));
                items.clear();
            }
        }
    }

    // ---- inline scanner ----------------------------------------------------------------------------------------

    private static void parseRange(final String s, final int from, final int to, final List<Inline> out) {
        final StringBuilder text = new StringBuilder();
        int i = from;
        while (i < to) {
            final int opaque = opaqueEnd(s, i, to);
            if (opaque > i) {
                text.append(s, i, opaque);
                i = opaque;
                continue;
            }
            final char c = s.charAt(i);
            final int run = MARKER_CHARS.indexOf(c) < 0 ? 0 : runLength(s, i, to);
            if (run == 0) {
                text.append(c);
                i++;
                continue;
            }
            final int close = opens(s, i, run, to) ? findCloser(s, i + run, to, c, run) : -1;
            if (close < 0) {
                text.append(s, i, i + run);
                i += run;
                continue;
            }
            flush(text, out);
            out.add(styled(c, run, s, i + run, close));
            i = close + run;
        }
        flush(text, out);
    }

    private static void flush(final StringBuilder text, final List<Inline> out) {
        if (!text.isEmpty()) {
            out.add(new Text(text.toString()));
            text.setLength(0);
        }
    }

    /** Wraps the parsed content in the style(s) a run of this marker means. */
    private static Inline styled(final char marker, final int run, final String s, final int from, final int to) {
        final List<Inline> children = new ArrayList<>();
        parseRange(s, from, to, children);
        final Inline inner = run == 3 ? new Styled(Style.ITALIC, List.copyOf(children)) : null;
        final List<Inline> content = inner == null ? List.copyOf(children) : List.of(inner);
        return new Styled(run == 1 ? Style.ITALIC : doubleStyle(marker), content);
    }

    private static Style doubleStyle(final char marker) {
        return switch (marker) {
            case '*' -> Style.BOLD;
            case '_' -> Style.UNDERLINE;
            default -> Style.STRIKETHROUGH;
        };
    }

    /** Whether a run of this length means anything: 1 or 3 for {@code *}/{@code _}, exactly 2 for {@code ~}. */
    private static boolean meaningful(final char marker, final int run) {
        return marker == '~' ? run == 2 : run >= 1 && run <= 3;
    }

    private static boolean opens(final String s, final int i, final int run, final int to) {
        return meaningful(s.charAt(i), run)
                && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))
                && i + run < to && !Character.isWhitespace(s.charAt(i + run));
    }

    /** The index of the first closing run for this marker, or -1. Opaque tokens are stepped over. */
    private static int findCloser(final String s, final int start, final int to, final char marker, final int run) {
        int j = start;
        while (j + run <= to) {
            final int opaque = opaqueEnd(s, j, to);
            if (opaque > j) {
                j = opaque;
                continue;
            }
            if (s.charAt(j) != marker) {
                j++;
                continue;
            }
            final int r = runLength(s, j, to);
            if (r == run && !Character.isWhitespace(s.charAt(j - 1))
                    && (j + run >= s.length() || !Character.isLetterOrDigit(s.charAt(j + run)))) {
                return j;
            }
            j += r;
        }
        return -1;
    }

    private static int runLength(final String s, final int i, final int to) {
        final char c = s.charAt(i);
        int end = i;
        while (end < to && s.charAt(end) == c) {
            end++;
        }
        return end - i;
    }

    /**
     * The end of the opaque token starting at {@code i}, or {@code i} when none starts there. A mention token is
     * {@code @{...}} with no whitespace inside; a URL runs from its scheme to the next whitespace, minus the trailing
     * sentence punctuation the link rule leaves outside anyway and any marker characters, which belong to the
     * surrounding style rather than to the address.
     */
    private static int opaqueEnd(final String s, final int i, final int to) {
        if (s.startsWith("@{", i)) {
            final int close = s.indexOf('}', i + 2);
            if (close > i + 2 && close < to && close - i - 2 <= MAX_MENTION_ID && plainToken(s, i + 2, close)) {
                return close + 1;
            }
            return i;
        }
        if (!s.startsWith("http://", i) && !s.startsWith("https://", i)) {
            return i;
        }
        int end = i;
        while (end < to && !Character.isWhitespace(s.charAt(end))) {
            end++;
        }
        while (end > i && URL_TRAILING.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return end;
    }

    private static boolean plainToken(final String s, final int from, final int to) {
        for (int k = from; k < to; k++) {
            final char c = s.charAt(k);
            if (Character.isWhitespace(c) || c == '{') {
                return false;
            }
        }
        return true;
    }
}
