package org.paulsens.trip.content;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import org.paulsens.trip.content.HtmlTags.Tag;

/**
 * Structural sanity check for admin-authored HTML fragments (template bodies, rich-text values, container
 * titles) BEFORE they are stored: the page emits them with {@code escape="false"}, so one unclosed
 * {@code <div>} can swallow the rest of the page. This is deliberately NOT a spec validator -- unknown tags
 * and attributes are fine -- it only rejects what breaks a page: unbalanced or misnested tags, an
 * unterminated tag or quoted attribute, and an unterminated comment.
 *
 * <p>Lenient where HTML is lenient: a {@code <} not starting a tag is text, void elements
 * ({@code <br>}, {@code <img>}, ...) need no close, {@code <tag/>} self-closes, and raw-text elements
 * ({@code <script>}, {@code <style>}) hide their content from the scanner. {@code {{token}}} placeholders
 * are plain text and pass through untouched.
 */
public final class HtmlFragmentValidator {

    private HtmlFragmentValidator() {
    }

    /**
     * @return a human-readable problem description, or null when the fragment is structurally sound.
     *         Null/blank input is sound (an empty body is legal).
     */
    public static String validate(final String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        final Deque<String> open = new ArrayDeque<>();
        int i = 0;
        while (i < html.length()) {
            final int lt = html.indexOf('<', i);
            if (lt < 0) {
                break;
            }
            if (html.startsWith("<!--", lt)) {
                final int end = html.indexOf("-->", lt + 4);
                if (end < 0) {
                    return "An HTML comment is never closed.";
                }
                i = end + 3;
            } else if (lt + 1 < html.length() && html.charAt(lt + 1) == '!') {
                i = HtmlTags.skipPast(html, lt, '>');   // doctype or CDATA-ish: ignore its innards
            } else if (lt + 1 < html.length() && html.charAt(lt + 1) == '/') {
                final Tag tag = HtmlTags.readTag(html, lt + 2);
                if (tag == null) {
                    i = lt + 1;                          // "</" not starting a real end tag: text
                    continue;
                }
                final String problem = closeTag(open, tag.name());
                if (problem != null) {
                    return problem;
                }
                i = tag.end();
            } else if (lt + 1 < html.length() && Character.isLetter(html.charAt(lt + 1))) {
                final Tag tag = HtmlTags.readTag(html, lt + 1);
                if (tag == null) {
                    return "The tag starting at “" + excerpt(html, lt) + "” is never closed with '>'.";
                }
                i = tag.end();
                if (tag.selfClosing() || HtmlTags.VOID_ELEMENTS.contains(tag.name())) {
                    continue;
                }
                if (HtmlTags.RAW_TEXT_ELEMENTS.contains(tag.name())) {
                    final int close = html.toLowerCase(Locale.ROOT).indexOf("</" + tag.name(), i);
                    if (close < 0) {
                        return "<" + tag.name() + "> is never closed.";
                    }
                    i = HtmlTags.skipPast(html, close, '>');
                } else {
                    open.push(tag.name());
                }
            } else {
                i = lt + 1;                              // '<' followed by anything else is plain text
            }
        }
        if (!open.isEmpty()) {
            return "<" + open.peek() + "> is never closed.";
        }
        return null;
    }

    private static String closeTag(final Deque<String> open, final String name) {
        if (open.isEmpty()) {
            return "</" + name + "> has no matching opening tag.";
        }
        if (!open.peek().equals(name)) {
            return "</" + name + "> found where </" + open.peek() + "> was expected (tags must nest).";
        }
        open.pop();
        return null;
    }

    private static String excerpt(final String html, final int at) {
        final int end = Math.min(html.length(), at + 20);
        return html.substring(at, end).replaceAll("\\s+", " ");
    }
}
