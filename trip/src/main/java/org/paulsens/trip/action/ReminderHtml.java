package org.paulsens.trip.action;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The cleaner for a trip reminder's body ({@code docs/reminders.md}): an ALLOWLIST. The body is written by a
 * trip facilitator, who may hold no privilege at all, and it is rendered unescaped to everyone who reviews it
 * (organization and site admins included) and mailed to travelers. So nothing gets through because it failed
 * to look dangerous: a tag is kept only when its name is on the list, and it is rebuilt from scratch with only
 * the attributes named here. Everything else is dropped or escaped.
 *
 * <p>{@link WaiverHtml#sanitize} is the wrong tool for this body, which is why this class exists. It is a
 * blocklist for text only an org admin writes, and the first review of the reminders found three bodies it
 * passes unchanged: an event handler with no space before it ({@code <img src="x"onerror=...>}), a tag it
 * never heard of ({@code <svg/onload=...>}), and a script URL spelled with an entity
 * ({@code href="jav&#97;script:..."}). None of the three can survive here: {@code img} and {@code svg} are not
 * on the list, no {@code on...} attribute is ever copied, and a link address must literally start with
 * {@code http://}, {@code https://} or {@code mailto:}, or be exactly one {@code {{...Link}}} placeholder.
 *
 * <p>What is kept is what the page's editor (Quill) writes: paragraphs, line breaks, bold, italic, underline,
 * strike, links, lists, headings, block quotes and its own {@code ql-} layout classes. Inline styles and
 * images are deliberately not kept.
 */
public final class ReminderHtml {
    /** Tags copied through, without their attributes unless named below. */
    private static final Set<String> ALLOWED = Set.of("p", "br", "b", "strong", "i", "em", "u", "s", "a", "ul", "ol",
            "li", "h1", "h2", "h3", "h4", "blockquote", "span", "div", "pre", "code", "sub", "sup", "hr");
    private static final Set<String> VOID = Set.of("br", "hr");
    /** Tags whose CONTENT is dropped with them: it is code or markup, never prose. */
    private static final Set<String> DROP_WITH_CONTENT = Set.of("script", "style", "iframe", "object", "embed",
            "noscript", "template", "svg", "math", "textarea", "title", "select", "xmp", "noembed", "noframes");

    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s\"'>]+)");
    private static final Pattern WEB_ADDRESS = Pattern.compile("(?i)(https?://|mailto:)[^\\s\"'<>`\\\\]+");
    private static final Pattern LINK_TOKEN = Pattern.compile("\\{\\{[a-zA-Z]+Link\\}\\}");
    private static final Pattern QUILL_CLASS = Pattern.compile("ql-[a-z0-9-]+");

    private ReminderHtml() {
    }

    /** The body with only allowlisted markup left; null becomes blank. Cleaning a cleaned body changes nothing. */
    public static String clean(final String html) {
        if (html == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(html.length());
        int at = 0;
        while (at < html.length()) {
            final char c = html.charAt(at);
            final int tagEnd = (c == '<') ? tagEnd(html, at) : -1;
            if (c != '<') {
                out.append(c == '>' ? "&gt;" : String.valueOf(c));
                at++;
            } else if (html.startsWith("<!--", at)) {
                final int end = html.indexOf("-->", at + 4);
                at = (end < 0) ? html.length() : end + 3;
            } else if (tagEnd > 0) {
                at = copyTag(html, at, tagEnd, out);
            } else {
                // A "<" that opens nothing this cleaner recognizes is text, and is written as text.
                out.append("&lt;");
                at++;
            }
        }
        return out.toString().trim();
    }

    /**
     * Where the tag opening at {@code from} ends (the index of its {@code >}), or -1 when this is not a tag: no
     * name after the {@code <}, or no {@code >} outside a quoted value. One pass over the text, by hand rather
     * than by a regular expression, so a long hostile body costs its length and no more.
     */
    private static int tagEnd(final String html, final int from) {
        int at = from + 1;
        if (at < html.length() && html.charAt(at) == '/') {
            at++;
        }
        if (at >= html.length() || !isLetter(html.charAt(at))) {
            return -1;
        }
        char quote = 0;
        for (; at < html.length(); at++) {
            final char c = html.charAt(at);
            if (quote != 0) {
                quote = (c == quote) ? 0 : quote;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return at;
            }
        }
        return -1;
    }

    private static boolean isLetter(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** Writes the tag spanning {@code start..end} when it is allowed; answers where reading goes on. */
    private static int copyTag(final String html, final int start, final int end, final StringBuilder out) {
        final boolean closing = html.charAt(start + 1) == '/';
        int nameEnd = start + (closing ? 2 : 1);
        final int nameStart = nameEnd;
        while (nameEnd < end && (isLetter(html.charAt(nameEnd)) || Character.isDigit(html.charAt(nameEnd)))) {
            nameEnd++;
        }
        final String name = html.substring(nameStart, nameEnd).toLowerCase(Locale.ROOT);
        if (!closing && DROP_WITH_CONTENT.contains(name)) {
            return afterClosing(html, name, end + 1);
        }
        if (ALLOWED.contains(name)) {
            if (closing) {
                out.append(VOID.contains(name) ? "" : "</" + name + ">");
            } else {
                out.append('<').append(name).append(attributesOf(name, html.substring(nameEnd, end))).append('>');
            }
        }
        return end + 1;
    }

    /** The index just past {@code </name>}, or the end of the text when the tag is never closed. */
    private static int afterClosing(final String html, final String name, final int from) {
        final String lower = html.toLowerCase(Locale.ROOT);
        final int close = lower.indexOf("</" + name, from);
        final int end = (close < 0) ? -1 : lower.indexOf('>', close);
        return (end < 0) ? html.length() : end + 1;
    }

    /** The attributes this tag keeps, rebuilt: a link's checked address, and Quill's own layout classes. */
    private static String attributesOf(final String name, final String raw) {
        final StringBuilder kept = new StringBuilder();
        final Matcher attribute = ATTRIBUTE.matcher(raw);
        boolean linked = false;
        boolean classed = false;
        while (attribute.find()) {
            final String key = attribute.group(1).toLowerCase(Locale.ROOT);
            final String value = unquoted(attribute.group(2)).trim();
            if ("a".equals(name) && "href".equals(key) && !linked && isSafeAddress(value)) {
                kept.append(" href=\"").append(value).append('"');
                linked = true;
            } else if ("class".equals(key) && !classed && isQuillClasses(value)) {
                kept.append(" class=\"").append(value).append('"');
                classed = true;
            }
        }
        if (linked) {
            // What Quill itself puts on a link; fixed here rather than copied.
            kept.append(" rel=\"noopener noreferrer\" target=\"_blank\"");
        }
        return kept.toString();
    }

    /** Every class named is one of Quill's own layout classes ({@code ql-align-center}, {@code ql-indent-1}). */
    private static boolean isQuillClasses(final String value) {
        for (final String one : value.split(" ")) {
            if (!QUILL_CLASS.matcher(one).matches()) {
                return false;
            }
        }
        return true;
    }

    private static String unquoted(final String value) {
        final boolean quoted = value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'');
        return quoted ? value.substring(1, value.length() - 1) : value;
    }

    /**
     * Whether a link may point here: a literal http, https or mailto address, or exactly one link placeholder.
     * Literal on purpose: an address that only BECOMES a script URL after the browser decodes an entity does
     * not start with one of these, so it is refused without this class having to decode anything.
     */
    static boolean isSafeAddress(final String value) {
        return WEB_ADDRESS.matcher(value).matches() || LINK_TOKEN.matcher(value).matches();
    }
}
