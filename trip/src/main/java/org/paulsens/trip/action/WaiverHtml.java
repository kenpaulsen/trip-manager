package org.paulsens.trip.action;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.paulsens.trip.security.Digests;

/**
 * The text handling a waiver document needs: the longest body a publish reads, the body's hash, and a plain-text
 * rendering for the signed-copy PDF.
 *
 * <p>The body is the trip editor's kind of rich text (the site's rich-text editor's output, Jodit since 2026-11-23, or
 * an org admin's hand-written source), and it is rendered unescaped on the website and inside the phones' web views,
 * with no cleaner on the way out. A publish therefore VALIDATES and never cleans: {@code HtmlCheck.document} lists
 * everything that keeps a body from being published as written, and a body with none is stored exactly as typed. A
 * cleaner loses what the author wrote without a word; a list of problems leaves the author in control of what is
 * saved (the owner's rule, 2026-11-23).
 */
public final class WaiverHtml {

    /**
     * The longest body a publish reads, in characters. Checked before any parsing, so a crafted body costs nothing;
     * a long waiver with the editor's markup is a few tens of thousands.
     */
    public static final int MAX_BODY = 200_000;

    private static final Pattern DANGEROUS_BLOCK = Pattern.compile(
            "(?is)<(script|style|iframe|object|embed|noscript|template)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern BREAK = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern LIST_ITEM = Pattern.compile("(?i)<li\\b[^>]*>");
    private static final Pattern BLOCK_END = Pattern.compile(
            "(?i)</(p|div|h[1-6]|ul|ol|blockquote|pre|tr|table)\\s*>");
    private static final Pattern BLOCK_START = Pattern.compile("(?i)<(p|div|h[1-6]|blockquote|pre|tr)\\b[^>]*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n(\\s*\\n)+");

    private WaiverHtml() {
    }

    /** Whether the body says anything once its markup is gone (an empty editor is {@code <p><br></p>}). */
    public static boolean isBlank(final String html) {
        return toText(html).isBlank();
    }

    /** Lower-case hex SHA-256 of the body's UTF-8 bytes. */
    public static String sha256(final String html) {
        return Digests.sha256Hex(html == null ? "" : html);
    }

    /**
     * Plain text for the PDF: block elements become line breaks, list items become bullet lines, entities are
     * decoded, every other tag disappears. Paragraphs are separated by one blank line.
     */
    public static String toText(final String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String out = DANGEROUS_BLOCK.matcher(html).replaceAll("");
        out = BREAK.matcher(out).replaceAll("\n");
        out = LIST_ITEM.matcher(out).replaceAll("\n\u2022 ");
        out = BLOCK_END.matcher(out).replaceAll("\n");
        out = BLOCK_START.matcher(out).replaceAll("\n\n");
        out = TAG.matcher(out).replaceAll("");
        out = decodeEntities(out);
        out = out.replace("\r", "").replace('\u00a0', ' ');
        out = BLANK_LINES.matcher(out).replaceAll("\n\n");
        final StringBuilder lines = new StringBuilder();
        for (final String line : out.split("\n", -1)) {
            lines.append(line.strip()).append('\n');
        }
        return BLANK_LINES.matcher(lines.toString()).replaceAll("\n\n").strip();
    }

    private static String decodeEntities(final String text) {
        final Matcher numeric = NUMERIC_ENTITY.matcher(text);
        final StringBuilder sb = new StringBuilder();
        while (numeric.find()) {
            numeric.appendReplacement(sb, Matcher.quoteReplacement(codePoint(numeric.group(1), numeric.group(2))));
        }
        numeric.appendTail(sb);
        return sb.toString()
                .replace("&nbsp;", " ")
                .replace("&mdash;", "\u2014")
                .replace("&ndash;", "\u2013")
                .replace("&rsquo;", "\u2019")
                .replace("&lsquo;", "\u2018")
                .replace("&rdquo;", "\u201d")
                .replace("&ldquo;", "\u201c")
                .replace("&hellip;", "\u2026")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static String codePoint(final String hexFlag, final String digits) {
        try {
            final int cp = Integer.parseInt(digits, hexFlag.isEmpty() ? 10 : 16);
            return Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : "";
        } catch (final NumberFormatException ex) {
            return "";
        }
    }

    /** A file-name-safe rendering of a title, lower-cased words joined by hyphens. */
    static String slug(final String title) {
        final String base = (title == null) ? "document" : title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return base.isEmpty() ? "document" : base;
    }
}
