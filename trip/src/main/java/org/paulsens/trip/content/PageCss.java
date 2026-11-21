package org.paulsens.trip.content;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rule for CSS in hand-authored page HTML ({@link PageHtml}): the two things refused in a {@code style} block
 * ({@link #block}).
 *
 * <p>An inline {@code style} attribute has no rule at all, and is kept exactly as typed (the owner's decision,
 * 2026-11-21, so that an attribute allows whatever a block allows). Neither of a block's two refusals can occur in
 * one: an attribute holds declarations only, so it has no at-rule and no selector. And nothing in it can leave
 * the attribute, because the sanitizer library still writes the attribute and escapes its value. Until that
 * decision this class held a property and value allowlist for attributes, written because the library's own CSS
 * schema edits inside a value ({@code margin:-10px auto 0 -5px} came out as {@code margin:auto 0}) and reports
 * nothing it removes; that is still why the library's schema is not used.
 */
final class PageCss {

    private static final Pattern CSS_COMMENT = Pattern.compile("(?s)/\\*.*?(?:\\*/|$)");
    /** A CSS escape: up to six hex digits and one optional space after them, or any one other character. */
    private static final Pattern ESCAPE = Pattern.compile("(?s)\\\\(?:([0-9a-fA-F]{1,6})\\s?|(.))");
    private static final Pattern IMPORT = Pattern.compile("@import(?![a-z0-9_-])");
    /** {@code [value...]}, with or without a namespace bar in front of the name. */
    private static final Pattern VALUE_TEST = Pattern.compile(
            "\\[\\s*(?:\\*\\||[a-z0-9_-]{1,40}\\||\\|)?value(?![a-z0-9_-])");
    /** {@code [data-...}: the attribute a selector tests, whatever follows its name. */
    private static final Pattern DATA_TEST = Pattern.compile(
            "\\[\\s*(?:\\*\\||[a-z0-9_-]{1,40}\\||\\|)?(data-[a-z0-9_.-]{1,60})");
    private static final int OPENING_MAX = 80;

    private PageCss() {
    }

    /**
     * The CSS of a {@code style} block, with only what the owner refuses taken out (2026-11-21): a rule that loads
     * another stylesheet ({@code @import}: what it holds can change after anyone looked), and a rule whose
     * selector READS the page rather than restyling it: one that tests a form field's {@code value}, or names a
     * {@code data-} attribute the site's own pages use (with a picture fetched per match, either sends what it
     * finds off, a character at a time). This is not every attribute selector: one on a class, a link's address
     * or an author's own {@code data-} attribute is styling, and stays.
     * Everything else is the author's, kept exactly as typed: the people who write these blocks hold a granted
     * role, and restyling the page around their section is what they use it for.
     *
     * <p>A refused rule goes whole, with its block, and is reported by its opening words; when it sits inside an
     * {@code @media} block or a nested rule, that whole outer rule goes, since half of it would not be what was
     * written. The test reads the rule the way a browser does: comments are not words, and an escape
     * ({@code @\69mport}, {@code [\76 alue]}) spells what it spells.
     *
     * <p>Not checked, because the caller sees to it: the text cannot end the block ({@link PageAsides} cuts it at
     * the first {@code </style}).
     *
     * @return the CSS, the very same string when nothing was refused
     */
    static String block(final String css, final Consumer<String> dropped) {
        final StringBuilder kept = new StringBuilder(css.length());
        boolean lost = false;
        int start = 0;
        while (start < css.length()) {
            final int end = statementEnd(css, start);
            final String statement = css.substring(start, end);
            if (refused(statement)) {
                dropped.accept(opening(statement));
                lost = true;
            } else {
                kept.append(statement);
            }
            start = end;
        }
        return lost ? kept.toString() : css;
    }

    /**
     * Where the statement that starts at {@code from} ends: after the {@code ;} of an at-rule with no block, or
     * after the brace that closes its block, strings, comments and escapes read as a browser reads them. The
     * last statement ends with the text, closed or not.
     */
    private static int statementEnd(final String css, final int from) {
        int depth = 0;
        int i = from;
        while (i < css.length()) {
            final char ch = css.charAt(i);
            if (ch == '\\') {
                i += 2;
            } else if (ch == '/' && css.startsWith("/*", i)) {
                final int close = css.indexOf("*/", i + 2);
                i = close < 0 ? css.length() : close + 2;
            } else if (ch == '"' || ch == '\'') {
                i = stringEnd(css, i);
            } else if (ch == '{') {
                depth++;
                i++;
            } else if (ch == '}') {
                depth--;
                i++;
                if (depth <= 0) {
                    return i;
                }
            } else if (ch == ';' && depth == 0) {
                return i + 1;
            } else {
                i++;
            }
        }
        return css.length();
    }

    /** Just past the quote that closes the string opening at {@code open}; a line break ends one too. */
    private static int stringEnd(final String css, final int open) {
        final char quote = css.charAt(open);
        int i = open + 1;
        while (i < css.length()) {
            final char ch = css.charAt(i);
            if (ch == '\\') {
                i += 2;
            } else if (ch == quote || ch == '\n') {
                return i + 1;
            } else {
                i++;
            }
        }
        return css.length();
    }

    private static boolean refused(final String statement) {
        final String read = unescaped(CSS_COMMENT.matcher(statement).replaceAll(" ")).toLowerCase(Locale.ROOT);
        return IMPORT.matcher(read).find() || VALUE_TEST.matcher(read).find() || testsASiteAttribute(read);
    }

    /**
     * Whether a selector names a {@code data-} attribute the site's own pages use ({@link PageHtml#dataAllowed}).
     * Those hold what the pages hand their scripts, some of it the visitor's own (the trip page carries the
     * signed-in person's id in one), and a selector on one with a picture fetched per match sends it off just as
     * a selector on a field's value does. An author cannot type such an attribute, so no rule of theirs has a
     * reason to name one.
     */
    private static boolean testsASiteAttribute(final String read) {
        final Matcher named = DATA_TEST.matcher(read);
        while (named.find()) {
            if (!PageHtml.dataAllowed(named.group(1), "")) {
                return true;
            }
        }
        return false;
    }

    /** The text with each CSS escape replaced by the character it spells. */
    private static String unescaped(final String css) {
        if (css.indexOf('\\') < 0) {
            return css;
        }
        final Matcher escape = ESCAPE.matcher(css);
        final StringBuilder out = new StringBuilder(css.length());
        while (escape.find()) {
            escape.appendReplacement(out, Matcher.quoteReplacement(spelled(escape)));
        }
        return escape.appendTail(out).toString();
    }

    private static String spelled(final Matcher escape) {
        if (escape.group(1) == null) {
            return escape.group(2);
        }
        final int code = Integer.parseInt(escape.group(1), 16);
        return Character.isValidCodePoint(code) && code != 0 ? Character.toString(code) : "\ufffd";
    }

    /** A statement's first words on one line, for telling its author which one went. */
    private static String opening(final String statement) {
        final String line = statement.strip().replaceAll("\\s+", " ");
        final int brace = line.indexOf('{');
        final String head = brace > 0 ? line.substring(0, brace).strip() : line;
        return head.length() > OPENING_MAX ? head.substring(0, OPENING_MAX) + "..." : head;
    }
}
