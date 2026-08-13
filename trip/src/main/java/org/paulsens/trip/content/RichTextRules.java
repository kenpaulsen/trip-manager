package org.paulsens.trip.content;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.paulsens.trip.content.HtmlTags.Tag;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Placeholder;

/**
 * The block-context rules for RICH_TEXT: what a WYSIWYG value is cleaned up into on save, and where a
 * template body may legally host one.
 *
 * <p>Quill's document model is a list of <em>lines</em>, and every line is a block element -- there is no
 * inline-only mode, so a one-line caption comes back as {@code <p>...</p>} plus a trailing empty paragraph,
 * and alignment arrives as a {@code ql-align-*} CLASS that only means anything where Quill's stylesheet is
 * loaded. Neither is what an author asked for, and the wrapper additionally breaks out of a template body
 * that placed the token inside a {@code <p>} (a {@code <p>} cannot nest, so the browser closes the outer one
 * early). {@link #normalize} therefore trims the noise the editor adds and keeps only what carries meaning;
 * {@link #richTextTokensInsideParagraph} warns the template author about the other half of the problem.
 *
 * <p>Deliberately conservative -- the Source toggle lets a contentAdmin hand-author markup, and that must
 * survive a round trip untouched. Only an attribute-free {@code <p>} wrapper is ever removed; anything
 * carrying attributes is the author's and stays.
 */
public final class RichTextRules {

    /** Quill's alignment classes; left is its default and carries no class. */
    private static final Pattern ALIGN_CLASS = Pattern.compile("ql-align-(center|right|justify)");
    private static final Pattern CLASS_ATTR = Pattern.compile("(?i)\\s*class=([\"'])(.*?)\\1");
    private static final Pattern STYLE_ATTR = Pattern.compile("(?i)style=([\"'])(.*?)\\1");
    /** A trailing paragraph holding nothing but breaks and blanks -- Quill's terminating line. */
    private static final Pattern TRAILING_EMPTY_PARAGRAPH =
            Pattern.compile("(?i)<p>(?:\\s|&nbsp;|<br\\s*/?>)*</p>\\s*$");
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    private RichTextRules() {
    }

    /**
     * Cleans one WYSIWYG value for storage: alignment classes become inline styles (so the page never
     * depends on the editor's stylesheet), Quill's trailing empty paragraph goes away, and a value that is
     * one attribute-free {@code <p>} loses that wrapper entirely. Multi-paragraph values keep their
     * structure -- there the blocks are the author's meaning, not the editor's bookkeeping.
     *
     * @return the value to store; null/blank input is returned unchanged.
     */
    public static String normalize(final String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        final String aligned = alignClassesToStyles(raw.trim());
        return unwrapSoleBareParagraph(stripTrailingEmptyParagraphs(aligned));
    }

    /**
     * The RICH_TEXT token names a template body places inside a {@code <p>} -- where the editor's own block
     * output cannot legally nest, so the browser will split the paragraph and the value escapes its
     * intended spot. The fix is always the body's: use a block container such as {@code <div>}.
     *
     * @return the offending token names in body order; empty when the body is fine.
     */
    public static List<String> richTextTokensInsideParagraph(final ContentTemplate template) {
        if (template == null || template.getBody() == null || template.getBody().isEmpty()) {
            return List.of();
        }
        final Set<String> rich = richTextNames(template);
        if (rich.isEmpty()) {
            return List.of();
        }
        return scanForNestedTokens(template.getBody(), rich);
    }

    private static Set<String> richTextNames(final ContentTemplate template) {
        final Set<String> names = new LinkedHashSet<>();
        for (final Placeholder ph : template.getPlaceholders()) {
            if (ph.getType() == Placeholder.Type.RICH_TEXT && ph.getName() != null) {
                names.add(ph.getName());
            }
        }
        return names;
    }

    /** Walks the body keeping the open-element stack, collecting rich-text tokens seen inside a {@code p}. */
    private static List<String> scanForNestedTokens(final String body, final Set<String> rich) {
        final List<String> flagged = new ArrayList<>();
        final Deque<String> open = new ArrayDeque<>();
        int i = 0;
        while (i < body.length()) {
            final int lt = body.indexOf('<', i);
            final String text = body.substring(i, lt < 0 ? body.length() : lt);
            if (open.contains("p")) {
                collectTokens(text, rich, flagged);
            }
            if (lt < 0) {
                break;
            }
            i = advancePastTag(body, lt, open);
        }
        return flagged;
    }

    private static void collectTokens(final String text, final Set<String> rich, final List<String> into) {
        final Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            final String name = matcher.group(1);
            if (rich.contains(name) && !into.contains(name)) {
                into.add(name);
            }
        }
    }

    /**
     * Consumes the markup starting at {@code lt}, maintaining {@code open}. Mirrors the validator's leniency
     * -- unbalanced input cannot reach here (the validator runs first), so this only has to not throw.
     */
    private static int advancePastTag(final String body, final int lt, final Deque<String> open) {
        if (body.startsWith("<!--", lt)) {
            final int end = body.indexOf("-->", lt + 4);
            return end < 0 ? body.length() : end + 3;
        }
        if (lt + 1 >= body.length()) {
            return body.length();
        }
        final char after = body.charAt(lt + 1);
        if (after == '!') {
            return HtmlTags.skipPast(body, lt, '>');
        }
        if (after == '/') {
            return closeElement(body, lt, open);
        }
        if (!Character.isLetter(after)) {
            return lt + 1;                               // '<' as plain text
        }
        return openElement(body, lt, open);
    }

    private static int closeElement(final String body, final int lt, final Deque<String> open) {
        final Tag tag = HtmlTags.readTag(body, lt + 2);
        if (tag == null) {
            return lt + 1;
        }
        if (open.contains(tag.name())) {
            // Pop through anything this close implicitly ended, then the element itself.
            String popped = open.pop();
            while (!popped.equals(tag.name()) && !open.isEmpty()) {
                popped = open.pop();
            }
        }
        return tag.end();
    }

    private static int openElement(final String body, final int lt, final Deque<String> open) {
        final Tag tag = HtmlTags.readTag(body, lt + 1);
        if (tag == null) {
            return lt + 1;
        }
        if (HtmlTags.RAW_TEXT_ELEMENTS.contains(tag.name())) {
            final int close = body.indexOf("</" + tag.name(), tag.end());
            return close < 0 ? body.length() : HtmlTags.skipPast(body, close, '>');
        }
        if (!tag.selfClosing() && !HtmlTags.VOID_ELEMENTS.contains(tag.name())) {
            open.push(tag.name());
        }
        return tag.end();
    }

    // ------------------------------------------------------------------------------ normalization steps

    /** Rewrites every {@code ql-align-*} class as an inline {@code text-align}, dropping the class. */
    private static String alignClassesToStyles(final String html) {
        final StringBuilder out = new StringBuilder(html.length());
        int i = 0;
        while (i < html.length()) {
            final int lt = html.indexOf('<', i);
            if (lt < 0 || lt + 1 >= html.length() || !Character.isLetter(html.charAt(lt + 1))) {
                out.append(html, i, html.length());
                break;
            }
            final Tag tag = HtmlTags.readTag(html, lt + 1);
            if (tag == null) {
                out.append(html, i, html.length());
                break;
            }
            out.append(html, i, lt).append(rewriteAlign(html.substring(lt, tag.end())));
            i = tag.end();
        }
        return out.toString();
    }

    /** @param tag one complete start tag, angle brackets included. */
    private static String rewriteAlign(final String tag) {
        final Matcher align = ALIGN_CLASS.matcher(tag);
        if (!align.find()) {
            return tag;
        }
        return withAlignStyle(dropClass(tag, align.group()), align.group(1));
    }

    /** Removes one class name, and the whole {@code class} attribute when nothing else was in it. */
    private static String dropClass(final String tag, final String className) {
        final Matcher classAttr = CLASS_ATTR.matcher(tag);
        if (!classAttr.find()) {
            return tag;
        }
        final String kept = String.join(" ", classAttr.group(2).trim().split("\\s+")).replace(className, "").trim();
        final String head = tag.substring(0, classAttr.start());
        final String tail = tag.substring(classAttr.end());
        return kept.isEmpty() ? head + tail
                : head + " class=" + classAttr.group(1) + kept.replaceAll("\\s+", " ") + classAttr.group(1) + tail;
    }

    /** Merges {@code text-align} into an existing style attribute, or adds one just before the '>'. */
    private static String withAlignStyle(final String tag, final String align) {
        final Matcher style = STYLE_ATTR.matcher(tag);
        if (!style.find()) {
            final int close = tag.lastIndexOf('>');
            final String head = tag.substring(0, close).stripTrailing();
            final String selfClosing = head.endsWith("/") ? "/>" : ">";
            final String body = head.endsWith("/") ? head.substring(0, head.length() - 1).stripTrailing() : head;
            return body + " style=\"text-align:" + align + "\"" + selfClosing;
        }
        final String existing = style.group(2).trim();
        if (existing.toLowerCase(Locale.ROOT).contains("text-align")) {
            return tag;                                  // the author's own alignment wins
        }
        final String merged = "text-align:" + align + (existing.isEmpty() ? "" : ";" + existing);
        return tag.substring(0, style.start()) + "style=" + style.group(1) + merged + style.group(1)
                + tag.substring(style.end());
    }

    private static String stripTrailingEmptyParagraphs(final String html) {
        String out = html;
        Matcher matcher = TRAILING_EMPTY_PARAGRAPH.matcher(out);
        while (matcher.find()) {
            out = out.substring(0, matcher.start()).stripTrailing();
            matcher = TRAILING_EMPTY_PARAGRAPH.matcher(out);
        }
        return out;
    }

    /**
     * Drops the wrapper of a value that is exactly one attribute-free paragraph. A {@code </p>} anywhere in
     * the inner text means the opening tag closed earlier (paragraphs cannot nest), so the value is really
     * several blocks and keeps them.
     */
    private static String unwrapSoleBareParagraph(final String html) {
        if (!html.startsWith("<p>") || !html.endsWith("</p>")) {
            return html;
        }
        final String inner = html.substring(3, html.length() - "</p>".length());
        return inner.toLowerCase(Locale.ROOT).contains("</p") ? html : inner.trim();
    }
}
