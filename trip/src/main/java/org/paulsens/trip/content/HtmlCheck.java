package org.paulsens.trip.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.ParseError;
import org.jsoup.parser.Parser;
import org.paulsens.trip.i18n.Text;

/**
 * A VALIDATOR for HTML an author typed: it lists what is wrong and changes nothing, so the author decides what is
 * saved (the owner's rule, 2026-11-23: "a sanitizer loses data"). Where {@link PageHtml} cleans a body and
 * {@link StripNotes} says what the clean took out, this says what would have to change before the body can be stored
 * exactly as written.
 *
 * <p>The body is read by jsoup, which follows the WHATWG HTML parsing rules a browser follows, in the context of a
 * page's {@code <body>}. Every place where the parser has to REPAIR the markup is a parse error, and a parse error is a
 * place where the page would not show what was typed: a stray {@code </div>} that closes the box around the body, a
 * tag, quoted value or comment left unfinished at the end, which swallows the page's own markup after it, an attribute
 * folded into another one ({@code <img src=x /onerror=...>}), a table holding text a browser moves out of it. OWASP's
 * tokenizer read some of these differently from a browser, and a hand-written imitation of the parsing rules missed
 * more each review (blockers, 2026-11-23); this check copies neither.
 *
 * <p>Two sets of rules:
 * <ul>
 *   <li>{@link #structure}: the parse errors alone. For bodies that may hold any element on purpose: a template
 *       body, a page section's rich text, the lodging policy.</li>
 *   <li>{@link #document}: the parse errors, and only a document's own elements ({@link PageHtml#TEXT_ELEMENTS}),
 *       no event handler, no address outside {@link PageHtml#LINK_SCHEMES}, read from the parsed tree, so they are
 *       the elements and attributes a browser will see. For a body stored and shown as written with no cleaner on
 *       the way out: a waiver.</li>
 * </ul>
 *
 * <p>Parsing is linear in the body's length, except that each nested table costs a little more than the one outside
 * it; a body with more than {@link #MAX_TABLES} tables is refused before it is read. A caller still refuses an
 * over-long body first.
 */
public final class HtmlCheck {

    /** At most this many problem lines are listed; one more line says how many were left out. */
    public static final int MAX_PROBLEMS = 10;
    /**
     * More tables than any document holds. Nested tables are the one shape whose parsing cost grows faster than the
     * body (about a third of a second for 13,000 nested in 200,000 characters), so a body past this is refused unread.
     */
    static final int MAX_TABLES = 200;
    /**
     * Parse errors kept, a bound on a hostile body's list. jsoup stops recording at the cap without saying so, and the
     * harmless lone {@code <} errors are skipped, so a list that reached it may hide a dangerous error after them: a
     * full list refuses the body (review blocker, 2026-11-24).
     */
    static final int MAX_ERRORS = 1000;
    /** Names in a problem line are cut to this many characters, so a crafted name cannot fill the message. */
    private static final int MAX_NAME = 40;

    /** What a published document may hold: a site page's text elements, and a line-break hint. */
    private static final Set<String> DOCUMENT_ELEMENTS = Stream.concat(PageHtml.TEXT_ELEMENTS.stream(),
            Stream.of("wbr")).collect(Collectors.toUnmodifiableSet());
    /** The attributes a browser follows as an address. */
    private static final Set<String> ADDRESS_ATTRIBUTES = Set.of("href", "src", "action", "formaction", "poster",
            "background", "cite", "longdesc", "xlink:href", "srcset", "ping", "data");
    private static final Pattern SAFE_SCHEME = Pattern.compile("(?i)("
            + String.join("|", PageHtml.LINK_SCHEMES) + ")");
    /** An address's scheme, after what a browser's URL parser ignores has been taken out. */
    private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*):");
    /** Elements whose end tag may be left out: the parser closes them itself, and that is no error. */
    private static final Set<String> OPTIONAL_END = Set.of("p", "li", "dt", "dd", "tr", "td", "th", "thead",
            "tbody", "tfoot", "colgroup", "caption", "option", "optgroup", "html", "body", "head");
    private static final Pattern TABLE_TAG = Pattern.compile("(?i)<table");
    /**
     * A template's placeholder or region marker, any spelling the renderers read ({@code {{name}}},
     * {@code {{children:start}}}): text to the parser, though it is replaced before anyone sees it.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[^{}]*}}");
    /** A body's first tag, after any space and comments. */
    private static final Pattern FIRST_TAG = Pattern.compile("^\\s*(?:<!--.*?-->\\s*)*<(!doctype|[a-zA-Z][^\\s/>]*)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    /**
     * A body that starts with a table's inner part is read inside the element that holds that part, as it is used: a
     * container's row ({@code <tr>...</tr>}) goes inside its wrapper's {@code <tbody>}.
     */
    private static final Map<String, String> TABLE_PART_CONTEXT = Map.of("tr", "tbody", "td", "tr", "th", "tr",
            "tbody", "table", "thead", "table", "tfoot", "table", "caption", "table", "colgroup", "table",
            "col", "colgroup");

    /*
     * jsoup's parse errors carry no codes: each is matched by its wording, and HtmlCheckTest holds one body per
     * pattern, so an upgrade that rewords one fails the build rather than a page. The jar is pinned in trip/pom.xml.
     */
    private static final Pattern EOF_IN_INPUT = Pattern.compile("^Unexpectedly reached end of file \\(EOF\\)");
    private static final Pattern TOKEN = Pattern.compile(
            "^Unexpected (StartTag|EndTag|Character|EOF|Comment|Doctype) token \\[(.*)] when in state \\[(\\w+)]",
            Pattern.DOTALL);
    private static final Pattern SELF_CLOSING = Pattern.compile("^Tag \\[([^\\]]+)] cannot be self-closing");
    private static final Pattern REFERENCE = Pattern.compile("^Invalid character reference");
    /** A {@code <} that does not start a tag ({@code a < b}): a browser shows it as the text it is. */
    private static final Pattern LONE_LESS_THAN = Pattern.compile(
            "^Unexpected character '.' in input state \\[TagOpen]", Pattern.DOTALL);
    private static final Pattern TAG_NAME = Pattern.compile("^</?([^\\s/>]+)");

    private HtmlCheck() {
    }

    /** The problems with a body stored and shown as written: {@link #structure}, plus what it may hold. */
    public static List<String> document(final String html) {
        return read(html, true, false);
    }

    /** The problems with a body's markup alone: every place a browser would repair it, as a browser reads it. */
    public static List<String> structure(final String html) {
        return read(html, false, false);
    }

    /**
     * {@link #structure}'s problems as one sentence each, joined, or null when there are none: for a save that
     * refuses with one message (a template body, a page section, the lodging policy).
     */
    public static String structureProblem(final String html) {
        final List<String> problems = structure(html);
        return problems.isEmpty() ? null : String.join(" ", problems);
    }

    /**
     * {@link #structureProblem} for a template body, read as it is used once filled: its placeholders and region
     * markers taken out (a {@code {{rows}}} between table tags is not text inside a table), a whole mail document read
     * as a document, and a body that starts with a table's inner part read inside the element that holds it.
     */
    public static String templateProblem(final String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        final List<String> problems = read(PLACEHOLDER.matcher(html).replaceAll(""), false, true);
        return problems.isEmpty() ? null : String.join(" ", problems);
    }

    /**
     * Each distinct problem once, in the page's language, with a count when it repeats; at most
     * {@link #MAX_PROBLEMS} lines and one saying how many more. Empty when there is nothing to fix.
     */
    private static List<String> read(final String html, final boolean asDocument, final boolean asTemplate) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        final Problems found = new Problems();
        if (TABLE_TAG.matcher(html).results().limit(MAX_TABLES + 1L).count() > MAX_TABLES) {
            found.note("server.htmlCheck.tooManyTables", MAX_TABLES);
            return found.lines();
        }
        final Parser parser = Parser.htmlParser().setTrackErrors(MAX_ERRORS).setTrackPosition(true);
        final List<Node> nodes = parse(parser, html, asTemplate);
        for (final ParseError error : parser.getErrors()) {
            explain(error, nodes, found);
        }
        if (parser.getErrors().size() >= MAX_ERRORS) {
            found.note("server.htmlCheck.tooManyErrors", MAX_ERRORS);
        }
        if (asDocument) {
            for (final Node node : nodes) {
                if (node instanceof Element element) {
                    element.getAllElements().forEach(found::checkContent);
                }
            }
        }
        return found.lines();
    }

    /**
     * The body's top-level nodes, read in a page's {@code <body>}. A template may also be a whole document or a
     * table's inner part ({@link #templateProblem}).
     */
    private static List<Node> parse(final Parser parser, final String html, final boolean asTemplate) {
        final Matcher first = FIRST_TAG.matcher(html);
        final String tag = asTemplate && first.find() ? first.group(1).toLowerCase(Locale.ROOT) : "";
        if ("!doctype".equals(tag) || "html".equals(tag)) {
            final Document document = parser.parseInput(html, "");
            return document.childNodes();
        }
        return parser.parseFragmentInput(html, new Element(TABLE_PART_CONTEXT.getOrDefault(tag, "body")), "");
    }

    /** One parse error in the author's words; a {@code <} that starts no tag is not one. */
    private static void explain(final ParseError error, final List<Node> nodes, final Problems found) {
        final String message = error.getErrorMessage();
        final int[] at = lineAndColumn(error.getCursorPos());
        final Matcher token = TOKEN.matcher(message);
        final Matcher selfClosing = SELF_CLOSING.matcher(message);
        if (LONE_LESS_THAN.matcher(message).find()) {
            return;
        }
        if (EOF_IN_INPUT.matcher(message).find()) {
            found.note("server.htmlCheck.unfinished");
        } else if (selfClosing.find()) {
            found.note("server.htmlCheck.selfClosing", shown(selfClosing.group(1)), at[0]);
        } else if (REFERENCE.matcher(message).find()) {
            found.note("server.htmlCheck.reference", at[0], at[1]);
        } else if (token.find()) {
            explainToken(token, at, nodes, found);
        } else {
            found.note(message.startsWith("Unexpected character") ? "server.htmlCheck.malformed"
                    : "server.htmlCheck.parse", at[0], at[1]);
        }
    }

    private static void explainToken(final Matcher token, final int[] at, final List<Node> nodes,
            final Problems found) {
        final Matcher name = TAG_NAME.matcher(token.group(2));
        final String tag = name.find() ? shown(name.group(1).toLowerCase(Locale.ROOT)) : "";
        switch (token.group(1)) {
            case "EndTag" -> found.note("server.htmlCheck.endTag", tag, at[0]);
            case "StartTag" -> found.note("server.htmlCheck.misplaced", tag, at[0]);
            case "Character" -> found.note("server.htmlCheck.text", at[0]);
            case "EOF" -> unclosedAtEnd(nodes, found);
            default -> found.note("server.htmlCheck.parse", at[0], at[1]);
        }
    }

    /**
     * The elements still open where the body ends: the last element read and its ancestors, those whose end tag was
     * never written and may not be left out. The parser reports the end of the body once, without naming them.
     */
    private static void unclosedAtEnd(final List<Node> nodes, final Problems found) {
        Element last = null;
        for (final Node node : nodes) {
            if (node instanceof Element element) {
                last = element.getAllElements().last();
            }
        }
        final List<Element> open = new ArrayList<>();
        // Up to the body's own top level: above it is the parser's document root, which nobody wrote.
        for (Element element = last; element != null; element = nodes.contains(element) ? null : element.parent()) {
            // An end tag the parser supplied at the end of the text is IMPLICIT; one never reached is untracked.
            final boolean written = element.endSourceRange().isTracked() && !element.endSourceRange().isImplicit();
            if (!written && !element.tag().isEmpty()
                    && !OPTIONAL_END.contains(element.normalName()) && element.sourceRange().isTracked()) {
                open.addFirst(element);
            }
        }
        for (final Element element : open) {
            found.note("server.htmlCheck.unclosed", shown(element.normalName()),
                    element.sourceRange().start().lineNumber());
        }
        if (open.isEmpty()) {
            found.note("server.htmlCheck.unfinished");
        }
    }

    /** jsoup's cursor position, {@code "line:column"}; zeros when it cannot be read. */
    private static int[] lineAndColumn(final String cursor) {
        final int colon = cursor == null ? -1 : cursor.indexOf(':');
        try {
            return colon < 0 ? new int[] {0, 0} : new int[] {Integer.parseInt(cursor.substring(0, colon)),
                Integer.parseInt(cursor.substring(colon + 1))};
        } catch (final NumberFormatException ex) {
            return new int[] {0, 0};
        }
    }

    /**
     * Whether an address is one a document may hold: relative, or with one of {@link PageHtml#LINK_SCHEMES}, read as
     * a browser's URL parser reads it (tab, line feed and carriage return removed anywhere, leading control
     * characters and spaces skipped). The parser has already decoded character references, as a browser does.
     */
    private static boolean safeAddress(final String value) {
        final String read = value.replaceAll("[\\t\\n\\r]", "").replaceFirst("^[\\x00-\\x20]+", "");
        final Matcher scheme = SCHEME.matcher(read);
        return !scheme.find() || SAFE_SCHEME.matcher(scheme.group(1)).matches();
    }

    /** A name as a problem line shows it: a crafted name thousands of characters long is cut. */
    private static String shown(final String name) {
        return name.length() <= MAX_NAME ? name : name.substring(0, MAX_NAME) + "\u2026";
    }

    /** The problems found so far, each distinct line once with how often it was found. */
    private static final class Problems {
        private final Map<String, Integer> found = new LinkedHashMap<>();

        /** Records one problem; a number goes in as text, so a line number never gains a thousands separator. */
        void note(final String key, final Object... args) {
            final Object[] shownArgs = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                shownArgs[i] = args[i] instanceof Integer number ? String.valueOf(number) : args[i];
            }
            found.merge(Text.get(key, shownArgs), 1, Integer::sum);
        }

        /** What a published document may not hold, on one element as the parser built it. */
        void checkContent(final Element element) {
            final String name = element.normalName();
            if (!DOCUMENT_ELEMENTS.contains(name)) {
                note("server.htmlCheck.element", shown(name));
            }
            for (final Attribute attribute : element.attributes()) {
                final String key = attribute.getKey().toLowerCase(Locale.ROOT);
                if (key.startsWith("on")) {
                    note("server.htmlCheck.handler", shown(key), shown(name));
                } else if ("srcdoc".equals(key)) {
                    note("server.htmlCheck.attribute", shown(key), shown(name));
                } else if (ADDRESS_ATTRIBUTES.contains(key) && !safeAddress(attribute.getValue())) {
                    note("server.htmlCheck.address", shown(key), shown(name));
                }
            }
        }

        List<String> lines() {
            final List<String> all = StripNotes.withCounts(found);
            if (all.size() <= MAX_PROBLEMS) {
                return all;
            }
            final int more = all.size() - MAX_PROBLEMS;
            final List<String> shownLines = new ArrayList<>(all.subList(0, MAX_PROBLEMS));
            shownLines.add(Text.get("server.htmlCheck.more", more, String.valueOf(more)));
            return shownLines;
        }
    }
}
