package org.paulsens.trip.action;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text handling a waiver document needs, with no HTML library on the classpath: the publish-time cleanup,
 * the body's hash, and a plain-text rendering for the signed-copy PDF.
 *
 * <p>The body is the trip editor's kind of rich text (Quill output, or an org admin's hand-written source), and
 * it is rendered unescaped on the website and inside the phones' web views. {@link #sanitize} therefore removes
 * everything that can run -- script and style blocks, embedded frames and objects, {@code on*} handlers,
 * {@code javascript:} URLs -- and keeps the markup that carries meaning. It is defense in depth for a body only
 * an org admin can write, not a general-purpose sanitizer.
 */
public final class WaiverHtml {

    private static final Pattern DANGEROUS_BLOCK = Pattern.compile(
            "(?is)<(script|style|iframe|object|embed|noscript|template)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern DANGEROUS_TAG = Pattern.compile(
            "(?is)</?(script|style|iframe|object|embed|noscript|template|link|meta|base|form|input|button)\\b[^>]*>");
    private static final Pattern EVENT_ATTR = Pattern.compile(
            "(?is)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    private static final Pattern SCRIPT_URL = Pattern.compile(
            "(?is)(href|src)\\s*=\\s*([\"']?)\\s*(javascript|vbscript|data):[^\"'>\\s]*\\2");
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

    /** The body with anything executable removed; null becomes blank. */
    public static String sanitize(final String html) {
        if (html == null) {
            return "";
        }
        String out = DANGEROUS_BLOCK.matcher(html).replaceAll("");
        out = DANGEROUS_TAG.matcher(out).replaceAll("");
        out = EVENT_ATTR.matcher(out).replaceAll("");
        out = SCRIPT_URL.matcher(out).replaceAll("$1=\"#\"");
        return out.trim();
    }

    /** Whether the body says anything once its markup is gone (an empty Quill editor is {@code <p><br></p>}). */
    public static boolean isBlank(final String html) {
        return toText(html).isBlank();
    }

    /** Lower-case hex SHA-256 of the body's UTF-8 bytes. */
    public static String sha256(final String html) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((html == null ? "" : html).getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", ex);
        }
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
