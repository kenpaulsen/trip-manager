package org.paulsens.trip.action;

import java.util.ArrayDeque;
import java.util.Deque;
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
 * <p>What is kept is what the page's editor (Quill 2, inside PrimeFaces) writes: paragraphs, line breaks,
 * bold, italic, underline, strike, links, lists, headings, block quotes, and the handful of {@code ql-} layout
 * classes named in {@link #QUILL_CLASS}. Inline styles and images are deliberately not kept, and the page's
 * toolbar does not offer them; a paste can still bring them, and {@link #dropsSomething} lets the save say so.
 *
 * <p>Quill 2 writes EVERY list as {@code <ol>} and says "bullet" only with {@code data-list} on each item. That
 * attribute is kept (the editor needs it to reopen the list as it was), and an {@code <ol>} whose first item is
 * a bullet is written as {@code <ul>} (and the written list switches kind where the items do), because a mail
 * client knows nothing of {@code data-list} and would number the list.
 */
public final class ReminderHtml {
    /** Tags copied through, without their attributes unless named below. */
    private static final Set<String> ALLOWED = Set.of("p", "br", "b", "strong", "i", "em", "u", "s", "a", "ul", "ol",
            "li", "h1", "h2", "h3", "h4", "blockquote", "span", "div", "pre", "code", "sub", "sup", "hr");
    private static final Set<String> VOID = Set.of("br", "hr");
    /** Tags whose CONTENT is dropped with them: it is code or markup, never prose. */
    private static final Set<String> DROP_WITH_CONTENT = Set.of("script", "style", "iframe", "object", "embed",
            "noscript", "template", "svg", "math", "textarea", "title", "select", "xmp", "noembed", "noframes");

    /**
     * One {@code name=value}. The name may not start inside another name, and is read possessively: without
     * both, a long run of name characters with no {@code =} is retried from every offset in it, and a tag of
     * 2,000 such characters cost about 36 ms to read (a body of them at the size cap, seconds).
     */
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "(?<![-a-zA-Z0-9_:.])([a-zA-Z_:][-a-zA-Z0-9_:.]*+)\\s*+=\\s*(\"[^\"]*\"|'[^']*'|[^\\s\"'>]+)");
    /** An apostrophe is fine in an address: the value is always written back inside double quotes. */
    private static final Pattern WEB_ADDRESS =
            Pattern.compile("(?i)(https?://|mailto:|tel:|sms:)[^\\s\"<>`\\\\]+");
    private static final Pattern LINK_TOKEN = Pattern.compile("\\{\\{[a-zA-Z]+Link\\}\\}");
    /**
     * The Quill classes kept: the ones the toolbar can produce, and nothing else. NOT every {@code ql-} class:
     * the page loads Quill's stylesheet and a mail client does not, so a class that stylesheet hides
     * ({@code ql-hidden}, {@code ql-clipboard}) would hide text from the reviewer and show it to the traveler.
     */
    private static final Pattern QUILL_CLASS = Pattern.compile(
            "ql-(align-(center|right|justify)|indent-[1-8]|direction-rtl|size-(small|large|huge)"
                    + "|font-(serif|monospace)|ui)");
    private static final Set<String> LIST_KINDS = Set.of("bullet", "ordered", "checked", "unchecked");
    /**
     * The longest thing still treated as a tag. A real tag is a few dozen characters; without a bound, a body
     * built of openings that never close makes every one of them scan to the end of the text, and the cleaner's
     * cost grows with the square of its input.
     */
    static final int MAX_TAG = 2_000;

    private ReminderHtml() {
    }

    /** The body with only allowlisted markup left; null becomes blank. Cleaning a cleaned body changes nothing. */
    public static String clean(final String html) {
        return clean(html, new Drops());
    }

    /**
     * Whether cleaning this body removes something its writer could see: a tag that is not on the list (an image,
     * a video, a table), an inline style (a color, a background), a class that is not one of Quill's kept ones,
     * or a link address that is refused (which leaves the link's text in an {@code <a>} that goes nowhere). The
     * save tells the facilitator, rather than reporting plain success over a body that lost them.
     *
     * <p>Answered by the cleaner itself, noting each drop where it happens, and NOT by comparing the cleaned body
     * with the raw one: the cleaner always rewrites what it keeps (the fixed {@code rel} and {@code target} on a
     * link, escaped {@code <} and {@code >}, trimmed whitespace, an {@code <ol>} of bullets written as
     * {@code <ul>}), so nearly every body Quill writes differs from its clean copy. What is invisible is not
     * counted either: a comment, Quill's {@code contenteditable} on a list marker, a link's own {@code rel}.
     */
    public static boolean dropsSomething(final String html) {
        final Drops drops = new Drops();
        clean(html, drops);
        return drops.any;
    }

    private static String clean(final String html, final Drops drops) {
        if (html == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(html.length());
        // Which tag each open list was WRITTEN as, so its closing tag matches (an <ol> of bullets leaves as <ul>).
        final Deque<String> lists = new ArrayDeque<>();
        // Past the last ">" nothing can be a tag, so an unclosed "<a" there is never scanned to the end again.
        final int lastClose = html.lastIndexOf('>');
        int at = 0;
        while (at < html.length()) {
            final char c = html.charAt(at);
            final int tagEnd = (c == '<' && at < lastClose) ? tagEnd(html, at) : -1;
            if (c != '<') {
                out.append(c == '>' ? "&gt;" : String.valueOf(c));
                at++;
            } else if (html.startsWith("<!--", at)) {
                final int end = html.indexOf("-->", at + 4);
                at = (end < 0) ? html.length() : end + 3;
            } else if (tagEnd > 0) {
                at = copyTag(html, at, tagEnd, out, lists, drops);
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
     * name after the {@code <}, or no {@code >} outside a quoted value within {@link #MAX_TAG} characters.
     * Scanned by hand rather than by a regular expression, and never further than that bound. Each tag found
     * is then read once more for its attributes, by a pattern that is linear in the tag's length, so the cost
     * of a clean is bounded by its length times {@link #MAX_TAG}.
     */
    private static int tagEnd(final String html, final int from) {
        int at = from + 1;
        if (at < html.length() && html.charAt(at) == '/') {
            at++;
        }
        if (at >= html.length() || !isLetter(html.charAt(at))) {
            return -1;
        }
        final int limit = Math.min(html.length(), from + MAX_TAG);
        char quote = 0;
        for (; at < limit; at++) {
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

    /**
     * Whether the body holds a link opening ({@code <a ...}) that the cleaner would write out as text: one with
     * no {@code >} within {@link #MAX_TAG} characters (an address of about 1,950 characters or more, or a tag
     * never closed). The save refuses such a body, rather than mailing the raw tag and its address as words.
     * Stops at the first one, and each link that IS a tag is scanned once, so the cost is linear.
     */
    static boolean hasUnreadableLink(final String html) {
        if (html == null) {
            return false;
        }
        for (int at = html.indexOf('<'); at >= 0; at = html.indexOf('<', at + 1)) {
            if (opensTag(html, at, "a")) {
                final int end = tagEnd(html, at);
                if (end < 0) {
                    return true;
                }
                at = end;
            }
        }
        return false;
    }

    /**
     * Whether the tag {@code <name} opens at {@code at}, any case: the name, then a character that is not a
     * letter ({@code <a>} or {@code <a href}, never {@code <abbr}). A name at the very end of the text opens
     * nothing: with no character after it there is no tag to close, so the cleaner writes it out as text, and
     * nothing that reads tags may treat it otherwise.
     */
    private static boolean opensTag(final String html, final int at, final String name) {
        final int after = at + 1 + name.length();
        return after < html.length() && html.regionMatches(true, at, "<" + name, 0, after - at)
                && !isLetter(html.charAt(after));
    }

    private static boolean isLetter(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** Writes the tag spanning {@code start..end} when it is allowed; answers where reading goes on. */
    private static int copyTag(final String html, final int start, final int end, final StringBuilder out,
            final Deque<String> lists, final Drops drops) {
        final boolean closing = html.charAt(start + 1) == '/';
        int nameEnd = start + (closing ? 2 : 1);
        final int nameStart = nameEnd;
        while (nameEnd < end && (isLetter(html.charAt(nameEnd)) || Character.isDigit(html.charAt(nameEnd)))) {
            nameEnd++;
        }
        final String name = html.substring(nameStart, nameEnd).toLowerCase(Locale.ROOT);
        if (!closing && DROP_WITH_CONTENT.contains(name)) {
            drops.any = true;
            return afterClosing(html, name, end + 1);
        }
        if ("ol".equals(name)) {
            out.append(closing ? "</" + (lists.isEmpty() ? "ol" : lists.pop()) + ">" : openList(html, end, lists));
        } else if ("li".equals(name) && !closing) {
            final String attributes = attributesOf(name, html.substring(nameEnd, end), drops);
            out.append(switchList(attributes, lists)).append("<li").append(attributes).append('>');
        } else if (ALLOWED.contains(name)) {
            if (closing) {
                out.append(VOID.contains(name) ? "" : "</" + name + ">");
            } else {
                out.append('<').append(name).append(attributesOf(name, html.substring(nameEnd, end), drops))
                        .append('>');
            }
        } else {
            drops.any = true;
        }
        return end + 1;
    }

    /**
     * The opening tag an {@code <ol>} is written as: {@code <ul>} when the tag that follows it is a Quill bullet
     * item. Only the NEXT tag is read (Quill writes the first {@code <li>} directly after the {@code <ol>}), so
     * a body of list openings with no items costs nothing extra.
     */
    private static String openList(final String html, final int afterOl, final Deque<String> lists) {
        final int next = html.indexOf('<', afterOl);
        final boolean item = next >= 0 && opensTag(html, next, "li");
        final int itemEnd = item ? tagEnd(html, next) : -1;
        // Only a look ahead: what that item drops is noted when the item itself is written.
        final boolean bullets = itemEnd > 0
                && isBullet(attributesOf("li", html.substring(next + 3, itemEnd), new Drops()));
        lists.push(bullets ? "ul" : "ol");
        return bullets ? "<ul>" : "<ol>";
    }

    private static boolean isBullet(final String attributes) {
        return attributes.contains("data-list=\"bullet\"");
    }

    /**
     * Quill 2 keeps adjacent lists of different kinds in ONE {@code <ol>}, telling them apart by each item's
     * {@code data-list}. When an item's kind is not the kind of the list being written, that list is closed and
     * the other opened ({@code </ul><ol>}), so a mail client shows bullets as bullets and steps as numbers.
     * An item with no kind, or outside any list, changes nothing.
     */
    private static String switchList(final String attributes, final Deque<String> lists) {
        final boolean ordered = attributes.contains("data-list=\"ordered\"");
        if (lists.isEmpty() || !(ordered || isBullet(attributes))) {
            return "";
        }
        final String wanted = ordered ? "ol" : "ul";
        if (wanted.equals(lists.peek())) {
            return "";
        }
        final String open = lists.pop();
        lists.push(wanted);
        return "</" + open + "><" + wanted + ">";
    }

    /** {@code String.indexOf} ignoring case, without lower-casing the whole text (which can change its length). */
    private static int indexOfIgnoreCase(final String html, final String what, final int from) {
        for (int at = html.indexOf('<', from); at >= 0; at = html.indexOf('<', at + 1)) {
            if (html.regionMatches(true, at, what, 0, what.length())) {
                return at;
            }
        }
        return -1;
    }

    /**
     * The index just past {@code </name>}, or the end of the text when the tag is never closed: an unclosed
     * {@code <script>} or {@code <svg>} swallows the rest of the body on purpose, because resuming after its
     * opening tag would let its content through as text.
     */
    private static int afterClosing(final String html, final String name, final int from) {
        final int close = indexOfIgnoreCase(html, "</" + name, from);
        final int end = (close < 0) ? -1 : html.indexOf('>', close);
        return (end < 0) ? html.length() : end + 1;
    }

    /** The attributes this tag keeps, rebuilt: a link's checked address, a list item's kind, a Quill class. */
    private static String attributesOf(final String name, final String raw, final Drops drops) {
        final StringBuilder kept = new StringBuilder();
        final Matcher attribute = ATTRIBUTE.matcher(raw);
        boolean linked = false;
        boolean classed = false;
        boolean listed = false;
        while (attribute.find()) {
            final String key = attribute.group(1).toLowerCase(Locale.ROOT);
            final String value = unquoted(attribute.group(2)).trim();
            if ("a".equals(name) && "href".equals(key) && !linked && isSafeAddress(value)) {
                kept.append(" href=\"").append(value).append('"');
                linked = true;
            } else if ("class".equals(key) && !classed && isQuillClasses(value)) {
                kept.append(" class=\"").append(value).append('"');
                classed = true;
            } else if ("li".equals(name) && "data-list".equals(key) && !listed && LIST_KINDS.contains(value)) {
                kept.append(" data-list=\"").append(value).append('"');
                listed = true;
            } else if (refusedFormatting(name, key, value, linked, classed)) {
                drops.any = true;
            }
        }
        if (linked) {
            // What Quill itself puts on a link; fixed here rather than copied.
            kept.append(" rel=\"noopener noreferrer\" target=\"_blank\"");
        }
        return kept.toString();
    }

    /**
     * Whether an attribute the cleaner did not keep was one its writer could see: a style or a class with a value,
     * or the first address of a link (reached here only when it was refused). Read after the kept branches, so a
     * second {@code href} or {@code class} on a tag that already kept one is not counted: a browser ignores it.
     */
    private static boolean refusedFormatting(final String name, final String key, final String value,
            final boolean linked, final boolean classed) {
        final boolean styled = "style".equals(key) || ("class".equals(key) && !classed);
        return (styled && !value.isEmpty()) || ("a".equals(name) && "href".equals(key) && !linked);
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
     * Whether a link may point here: a literal http, https, mailto, tel or sms address, or exactly one link
     * placeholder.
     * Literal on purpose: an address that only BECOMES a script URL after the browser decodes an entity does
     * not start with one of these, so it is refused without this class having to decode anything.
     */
    static boolean isSafeAddress(final String value) {
        return WEB_ADDRESS.matcher(value).matches() || LINK_TOKEN.matcher(value).matches();
    }

    /** What one clean noted as dropped (see {@link #dropsSomething}); set where a drop happens, never cleared. */
    private static final class Drops {
        private boolean any;
    }
}
