package org.paulsens.trip.content;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.owasp.html.Encoding;
import org.owasp.html.Handler;
import org.owasp.html.HtmlChangeListener;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.HtmlSanitizer;
import org.owasp.html.HtmlStreamEventReceiver;
import org.owasp.html.HtmlStreamEventReceiverWrapper;
import org.owasp.html.HtmlStreamRenderer;
import org.owasp.html.PolicyFactory;

/**
 * The allowlist for HAND-AUTHORED page HTML that someone below the Web Developer tier typed and a page writes out
 * unescaped (medjugorje#158): a trip's description, a content section's rich-text values and markup title, a
 * to-do's details, a speaker's bio. {@code action.RichTextHtml} stays the cleaner for the editor's own text that is
 * also mailed (reminders, event notes, private notes); this one is for the wide markup people paste and write in
 * Source mode.
 *
 * <p>Why it exists: those fields reached every visitor's browser exactly as stored, and a trip manager (anyone who
 * creates a trip) may write them. One login cookie covers every organization's site, so a script in one trip's
 * page acts as whoever looks at it, in every organization. The owner's decision (2026-11-17) is the tiered rule
 * WordPress multisite uses: only the top tier writes raw HTML, and everything else typed goes through an allowlist.
 *
 * <p>The list is as wide as what production holds, by the owner's rule that closing the hole may not cost a page
 * its look: layout tags, classes and ids, tables, figures, images by https or from this site, any https frame,
 * small inline icons ({@code svg} and {@code path} only), inline styles and {@code style} blocks ({@link PageCss}).
 * Never kept: script, an event handler, a form control, {@code base}, {@code meta}, {@code link}, a script or
 * {@code data:} address, a frame's {@code srcdoc}.
 *
 * <p>The parsing, the tag balancing and the writing are the OWASP Java HTML Sanitizer's, not ours: a wide list is
 * where a hand-written scanner goes wrong. What follows is ours, each for a reason a trial of the library showed:
 * <ul>
 *   <li>An inline style is kept as typed and never sees the library's CSS schema, which edits inside a value and
 *       reports nothing ({@link PageCss}). The library treats the name {@code style} specially, so the style
 *       travels through it under {@link #CHECKED_STYLE} and gets its name back on the way out.</li>
 *   <li>Any {@code data-} attribute no page script acts on is kept ({@link #dataAllowed}); the library knows
 *       attribute names only from a fixed list, so they travel together under {@link #CHECKED_DATA}.</li>
 *   <li>Addresses get a second rule ({@link #linkAddress}): the library keeps {@code //other-host} and
 *       {@code /\other-host}, which a browser reads as another site.</li>
 *   <li>A link's {@code rel} travels the same way, under {@link #CHECKED_REL}. The library either adds
 *       {@code noopener noreferrer} to every link that opens a new tab or, told to skip a word, removes it even
 *       where the author typed it. Here the author's words are kept and only {@code noopener} is added:
 *       {@code noreferrer} would hide this site from the page a link opens, which partner sites notice.</li>
 *   <li>Comments and {@code style} blocks are carried around the library and put back ({@link PageAsides}):
 *       authors park text in the one and restyle the page with the other, and the library throws both away.</li>
 *   <li>A non-breaking space is written back as {@code &nbsp;}, and {@code @}, {@code =} and {@code +} as
 *       themselves ({@link #plainlySpelled}): the source an author reopens should read as they wrote it.</li>
 * </ul>
 *
 * <p>Do NOT pass a template body through this class. The library rewrites <code>{{</code> so no template engine
 * downstream can read it (<code>{&lt;!-- --&gt;{</code> in text, a percent-escape in an address), and it moves text
 * out of a table, so a body's tokens and region markers do not survive. A template is judged on what it composes
 * to, after its tokens are filled.
 */
public final class PageHtml {

    /**
     * The name a checked style wears while it passes through the library. An author's own attribute of this name
     * is removed before the library sees it ({@link StyleChecker}), so the only values that come out under it are
     * ones {@link PageCss} kept.
     */
    static final String CHECKED_STYLE = "data-trip-checked-style";
    /** The same disguise for a link's {@code rel}, which the library would otherwise rewrite. */
    static final String CHECKED_REL = "data-trip-checked-rel";

    static final String STYLE = "style";
    private static final String REL = "rel";
    private static final String NO_OPENER = "noopener";
    private static final Pattern REL_WORDS = Pattern.compile("[A-Za-z -]{0,80}");

    /** The name every author-typed {@code data-} attribute travels under through the library ({@link #dataAllowed}). */
    static final String CHECKED_DATA = "data-trip-checked-data";

    private static final Pattern DATA_NAME = Pattern.compile("data-[a-z0-9][a-z0-9_.-]{0,60}");
    private static final int DATA_VALUE_MAX = 2000;

    /**
     * The {@code data-} names production content holds (Quill's list marker, a pasted carousel's settings). Always
     * kept, even where a page script reads one of the same name ({@code data-id}, {@code data-section}): they were
     * allowed before the list was opened, and a stored page may not lose them.
     */
    private static final Set<String> DATA_IN_USE = Set.of("data-list", "data-id", "data-section", "data-per-view",
            "data-gap", "data-peek", "data-autoplay", "data-height", "data-hide-arrows", "data-glide-el",
            "data-ast-blocks-layout", "data-row", "data-language");

    /**
     * The {@code data-} names an author may NOT type, because a script on the site's pages acts on them: content
     * that carried one could make that script treat the author's markup as its own (PrimeFaces runs the text of
     * {@code data-pfconfirmcommand} as script). Every other {@code data-} name is the author's (the owner's
     * decision, 2026-11-21). The list is every name the private repo's pages, scripts and theme scripts mention,
     * kept current by its {@code DataAttributePolicyIT}, which fails when a page starts to use a name an author can
     * still type. PrimeFaces' own names are NOT scanned (its jars name about two hundred, most for widgets no page
     * here uses, and many as plain as {@code data-src}): its two prefixes are reserved whole, and the names its
     * core script reads on any page are listed by hand ({@code data-ri}, {@code data-rk}, {@code data-rowkey},
     * {@code data-prk}, {@code data-widget}, {@code data-item-value}, {@code data-item-label},
     * {@code data-token-value}, {@code data-bui-overlay}, {@code data-bui-content}). A PrimeFaces upgrade is the
     * time to look at that list again.
     */
    private static final Set<String> DATA_RESERVED = Set.of(
            "data-act", "data-album", "data-aliases", "data-auto", "data-before", "data-brand", "data-bui-content",
            "data-bui-overlay", "data-busy", "data-client-secret", "data-colors", "data-contrast-exempt",
            "data-count", "data-covered", "data-ctx", "data-dark", "data-draft", "data-due", "data-email",
            "data-epoch", "data-fallback", "data-fits", "data-flickr-embed", "data-fmt", "data-full", "data-handle",
            "data-hint", "data-holds", "data-input", "data-insert-editor", "data-insert-input", "data-item-id",
            "data-item-label", "data-item-value", "data-jsqr", "data-key", "data-kind", "data-la-bound",
            "data-label", "data-lang", "data-light", "data-low", "data-manual", "data-max", "data-me", "data-mode",
            "data-name", "data-new", "data-on", "data-open", "data-org", "data-overridden-browser-behavior-msg",
            "data-overrides", "data-page", "data-pairs", "data-palette", "data-palettes", "data-party",
            "data-pay-total", "data-person", "data-person-id", "data-ph", "data-photo", "data-picker-for",
            "data-plan-id", "data-prk", "data-publishable-key", "data-pv-keys", "data-reactions-for", "data-ready",
            "data-real", "data-region-id", "data-reprice-url", "data-reservation-id", "data-return-url", "data-ri",
            "data-rk", "data-room", "data-room-id", "data-rot-key", "data-rotatable", "data-rowkey", "data-rule",
            "data-scope", "data-search", "data-self", "data-setting", "data-signed-in", "data-source", "data-start",
            "data-state", "data-status", "data-step", "data-tag", "data-tall", "data-template", "data-ticket-origin",
            "data-title", "data-token", "data-token-value", "data-tooltip", "data-tx", "data-userid", "data-utc",
            "data-value", "data-var", "data-widget", "data-wired");

    /** Whole families a script owns: PrimeFaces ({@code data-pf...}, {@code data-p-...}), and this site's own. */
    private static final List<String> DATA_RESERVED_PREFIXES = List.of("data-pf", "data-p-", "data-trip",
            "data-tooltip", "data-ui-", "data-jsf", "data-faces");

    private static final String[] ARIA_ATTRIBUTES = {"aria-hidden", "aria-label", "aria-labelledby",
        "aria-describedby", "aria-expanded", "aria-controls", "aria-current", "aria-level", "aria-live"};

    /** No colon and no dot, so an id here can never equal a Faces client id ({@code form:save}). */
    private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    /** Ids the page itself owns outside any naming container; content that took one would win the lookup. */
    private static final Set<String> PAGE_IDS = Set.of("form");

    private static final Pattern CLASSES = Pattern.compile("[A-Za-z0-9_ \\t-]{0,400}");
    private static final Pattern WORDS = Pattern.compile("[A-Za-z0-9_ ./:#-]{0,200}");
    private static final Pattern SIZE = Pattern.compile("[0-9]{1,4}(\\.[0-9]{1,2})?(px|%|em|rem)?");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]{1,4}");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{3,8}|[A-Za-z]{1,30}");
    private static final Pattern VIEW_BOX = Pattern.compile("[0-9 .,-]{1,60}");
    private static final Pattern PATH_DATA = Pattern.compile("[MmLlHhVvCcSsQqTtAaZz0-9eE ,.+\\-\\s]{1,20000}");
    private static final Pattern SVG_PAINT = Pattern.compile("none|currentColor|#[0-9a-fA-F]{3,8}|[A-Za-z]{1,30}");

    /**
     * What a frame may be handed. The list is what video and map embeds ask for; camera, microphone, location and
     * payment are not on it, so a framed site cannot prompt for them under this site's name.
     */
    private static final Set<String> FRAME_FEATURES = Set.of("accelerometer", "autoplay", "clipboard-write",
            "encrypted-media", "fullscreen", "gyroscope", "picture-in-picture", "web-share");

    private static final HtmlChangeListener<List<Drop>> NOTING = new HtmlChangeListener<>() {
        @Override
        public void discardedTag(final List<Drop> drops, final String element) {
            drops.add(new Drop(Drop.Kind.TAG, element, ""));
        }

        @Override
        public void discardedAttributes(final List<Drop> drops, final String element, final String... attributes) {
            for (final String attribute : attributes) {
                drops.add(new Drop(Drop.Kind.ATTRIBUTE, element, attribute));
            }
        }
    };

    private static final PolicyFactory POLICY = policy();
    private static final CleanMemo MEMO = new CleanMemo();

    private PageHtml() {
    }

    /**
     * One thing a clean removed that its writer could have seen or relied on.
     *
     * @param element the tag it was, or was on; blank for a comment
     * @param detail  the attribute's name, a comment's first words, or the start of a style block's rule;
     *                blank for a tag
     */
    public record Drop(Kind kind, String element, String detail) {

        /** What kind of thing went; an enum so that whoever words a removal cannot forget one. */
        public enum Kind { TAG, ATTRIBUTE, COMMENT, CSS_RULE }

        /** The short name a report uses; {@link StripNotes} words it for the person who typed it. */
        public String describe() {
            return switch (kind) {
                case TAG -> "<" + element + ">";
                case ATTRIBUTE -> detail + " on <" + element + ">";
                case COMMENT -> "the comment \"" + detail + "\"";
                case CSS_RULE -> "the rule \"" + detail + "\" in a <style> block";
            };
        }
    }

    /** A clean body and everything cleaning it removed, in the order it was met. */
    public record Cleaned(String body, List<Drop> drops) {

        public boolean dropped() {
            return !drops.isEmpty();
        }
    }

    /**
     * The HTML with only allowlisted markup left; null becomes blank. Cleaning a cleaned body changes nothing.
     * This is what a page calls on every render, so the answer is remembered ({@link CleanMemo}): a page writes
     * the same few stored values to every visitor.
     */
    public static String clean(final String html) {
        return html == null || html.isEmpty() ? "" : MEMO.get(html, PageHtml::cleanNow).body();
    }

    /**
     * Whether a TITLE is markup or text: it is markup when it holds a {@code <}. THE one test: a title that is
     * markup is cleaned where it is saved, judged and written out, and one that is text is escaped where it is
     * written out, so save, render and migration must all ask it here.
     */
    public static boolean titleHasMarkup(final String title) {
        return title != null && title.indexOf('<') >= 0;
    }

    /**
     * A title as it is STORED: as typed when it is text; cleaned when it is markup. With one catch, which is why
     * this is not just {@link #clean}: when cleaning leaves no tag (the {@code <} was text, as in "I <3 trips",
     * or every tag was dropped), what is left is text again, and it is stored AS text, entities decoded. Stored
     * as the cleaned HTML ("I &amp;lt;3 trips") it would hold no {@code <}, be taken for text on the next render
     * and be escaped a second time, and the page would show the entity.
     */
    public static Cleaned storedTitle(final String title) {
        if (!titleHasMarkup(title)) {
            return new Cleaned(title == null ? "" : title, List.of());
        }
        final Cleaned cleaned = cleanNoting(title);
        return titleHasMarkup(cleaned.body()) ? cleaned
                : new Cleaned(Encoding.decodeHtml(cleaned.body()), cleaned.drops());
    }

    /** {@link #clean}, but a null stays null: for a DTO field whose absence means something to its reader. */
    public static String cleanOrNull(final String html) {
        return html == null ? null : clean(html);
    }

    /**
     * {@link #clean}, noting what it removed ({@link Cleaned}): ONE pass, so a save that stores the body and
     * warns about what it lost judges both on the same input. Everything removed is noted, a comment that could
     * not be kept included; only the respelling the library always does is not (quotes, entity spellings, an end
     * tag a browser would have implied). NOT remembered: this is what a save and the editor's as-you-type preview
     * call, each time with a body nobody has seen before, and remembering those would fill the memo with drafts.
     */
    public static Cleaned cleanNoting(final String html) {
        return html == null || html.isEmpty() ? new Cleaned("", List.of()) : cleanNow(html);
    }

    private static Cleaned cleanNow(final String html) {
        final List<Drop> drops = new ArrayList<>();
        final PageAsides comments = PageAsides.of(html);
        final String marked = comments.takeFrom(html, drops);
        final StringBuilder out = new StringBuilder(html.length() + 64);
        final HtmlStreamRenderer renderer = HtmlStreamRenderer.create(out, Handler.DO_NOTHING);
        final HtmlSanitizer.Policy policy = POLICY.apply(new StyleRestorer(renderer, comments), NOTING, drops);
        HtmlSanitizer.sanitize(marked, policy, receiver -> new StyleChecker(receiver, drops, comments));
        // Before the comments go back: one is restored as typed, and what is inside it stays what it is.
        final String spaced = plainlySpelled(out.toString());
        return new Cleaned(comments.restoreInto(spaced, drops), List.copyOf(drops));
    }

    /**
     * The library's output with four spellings put back the way an author writes them. It writes a non-breaking
     * space as the bare character, which {@code ContentCommands.isBlankValue} and anyone reading the source both
     * need to see as {@code &nbsp;}. And it writes {@code @}, {@code =} and {@code +} as numeric entities, a
     * guard for template engines and browsers this site does not have: an author who opens the source of a mail
     * link they typed should find {@code mailto:office@example.org} there, not {@code office&#64;example.org}.
     * None of the three means anything to an HTML parser, in text or inside the quoted attribute values the
     * library writes, and the library never writes these entities for anything else (an ampersand an author
     * typed comes out as {@code &amp;}).
     */
    private static String plainlySpelled(final String written) {
        return written.replace("\u00a0", "&nbsp;").replace("&#64;", "@").replace("&#61;", "=")
                .replace("&#43;", "+");
    }

    private static PolicyFactory policy() {
        return new HtmlPolicyBuilder()
                .allowElements("p", "div", "span", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
                        "strong", "b", "em", "i", "u", "s", "strike", "del", "ins", "mark", "small", "sub", "sup",
                        "abbr", "cite", "q", "code", "pre", "blockquote", "center", "font", "address",
                        "big", "tt", "kbd", "var", "samp", "time", "picture", "source", "video", "audio",
                        "a", "ul", "ol", "li", "dl", "dt", "dd",
                        "table", "caption", "colgroup", "col", "thead", "tbody", "tfoot", "tr", "th", "td",
                        "figure", "figcaption", "img", "iframe", "svg", "path",
                        "section", "article", "header", "footer", "main", "aside", "nav", "details", "summary")
                // The library drops these three when they carry no attribute; a bare span or link is still text.
                .allowWithoutAttributes("a", "span", "font")
                .allowAttributes("id").matching(PageHtml::pageId).globally()
                .allowAttributes("class").matching(CLASSES).globally()
                .allowAttributes("title", "lang", "itemprop", "itemtype").matching(WORDS).globally()
                .allowAttributes("itemscope", "hidden").globally()
                .allowAttributes("dir").matching(false, "ltr", "rtl", "auto").globally()
                .allowAttributes("role").matching(Pattern.compile("[a-z]{1,30}")).globally()
                .allowAttributes("tabindex").matching(false, "0", "-1").globally()
                // Quill marks its list bullets this way; "true" would let a visitor type into the page.
                .allowAttributes("contenteditable").matching(false, "false").globally()
                .allowAttributes(ARIA_ATTRIBUTES).matching(WORDS).globally()
                .allowAttributes(CHECKED_DATA).globally()
                .allowAttributes(CHECKED_STYLE).globally()
                .allowAttributes(CHECKED_REL).onElements("a")
                .allowAttributes("href").matching(PageHtml::linkAddress).onElements("a")
                // An old-style in-page anchor (a table of contents links to it); held to the id's rule.
                .allowAttributes("name").matching(PageHtml::pageId).onElements("a")
                .allowAttributes("target").matching(false, "_blank", "_self", "_top", "_parent").onElements("a")
                .allowAttributes("src").matching(PageHtml::imageAddress).onElements("img")
                .allowAttributes("alt").onElements("img")
                .allowAttributes("sizes").matching(Pattern.compile("[A-Za-z0-9 (),.:%-]{0,200}")).onElements("img")
                .allowAttributes("loading").matching(false, "lazy", "eager").onElements("img", "iframe")
                .allowAttributes("decoding").matching(false, "async", "sync", "auto").onElements("img")
                .allowAttributes("width", "height").matching(SIZE)
                        .onElements("img", "iframe", "table", "td", "th", "col", "svg", "hr", "video", "source")
                // A player and a picture's alternatives: files by the image's rule, and switches that run nothing.
                .allowAttributes("src", "poster").matching(PageHtml::imageAddress).onElements("video", "audio",
                        "source")
                .allowAttributes("controls", "muted", "loop", "playsinline", "autoplay").onElements("video", "audio")
                .allowAttributes("preload").matching(false, "none", "metadata", "auto").onElements("video", "audio")
                .allowAttributes("type", "media").matching(Pattern.compile("[A-Za-z0-9 (),.:;=/+\"'-]{1,120}"))
                        .onElements("source")
                .allowAttributes("datetime").matching(Pattern.compile("[0-9A-Za-z:.+-]{1,40}")).onElements("time")
                .allowAttributes("src").matching(PageHtml::frameAddress).onElements("iframe")
                .allowAttributes("allow").matching(PageHtml::frameFeatures).onElements("iframe")
                .allowAttributes("allowfullscreen").onElements("iframe")
                .allowAttributes("frameborder").matching(NUMBER).onElements("iframe")
                .allowAttributes("referrerpolicy").matching(Pattern.compile("[a-z-]{1,40}")).onElements("iframe")
                .allowAttributes("colspan", "rowspan", "span").matching(NUMBER).onElements("td", "th", "col",
                        "colgroup")
                .allowAttributes("border", "cellpadding", "cellspacing").matching(NUMBER).onElements("table")
                .allowAttributes("align").matching(false, "left", "right", "center", "justify")
                        .onElements("p", "div", "table", "td", "th", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "img")
                .allowAttributes("valign").matching(false, "top", "middle", "bottom", "baseline")
                        .onElements("td", "th", "tr")
                .allowAttributes("scope").matching(false, "row", "col", "rowgroup", "colgroup").onElements("th")
                .allowAttributes("clear").matching(false, "all", "left", "right", "both", "none").onElements("br")
                .allowAttributes("start", "value").matching(NUMBER).onElements("ol", "li")
                .allowAttributes("type").matching(Pattern.compile("[1aAiI]|disc|circle|square")).onElements("ol",
                        "ul", "li")
                .allowAttributes("reversed").onElements("ol")
                .allowAttributes("open").onElements("details")
                .allowAttributes("color").matching(COLOR).onElements("font")
                .allowAttributes("size").matching(Pattern.compile("[+-]?[1-7]")).onElements("font")
                .allowAttributes("face").matching(Pattern.compile("[A-Za-z0-9 ,'-]{1,100}")).onElements("font")
                .allowAttributes("xmlns").matching(false, "http://www.w3.org/2000/svg").onElements("svg")
                .allowAttributes("viewBox", "viewbox").matching(VIEW_BOX).onElements("svg")
                .allowAttributes("focusable").matching(false, "false").onElements("svg")
                .allowAttributes("d").matching(PATH_DATA).onElements("path")
                .allowAttributes("fill", "stroke").matching(SVG_PAINT).onElements("svg", "path")
                .allowAttributes("stroke-width").matching(SIZE).onElements("svg", "path")
                .allowAttributes("stroke-linecap", "stroke-linejoin", "fill-rule", "clip-rule")
                        .matching(Pattern.compile("[a-z]{1,10}")).onElements("svg", "path")
                .allowUrlProtocols("http", "https", "mailto", "tel", "sms")
                // The library writes no rel of its own: the author's travels as CHECKED_REL, and StyleRestorer adds
                // the one word a new tab needs.
                .skipRelsOnLinks(NO_OPENER, "noreferrer")
                .toFactory();
    }

    /**
     * Whether an author may type this {@code data-} attribute: a plain name no page script acts on
     * ({@link #DATA_RESERVED}), and a value of ordinary characters. The library takes attribute names only from a
     * fixed list, so the ones kept travel through it together, under {@link #CHECKED_DATA}; one that is not kept is
     * handed to the library as typed, which drops and reports it like any attribute it does not know.
     */
    static boolean dataAllowed(final String name, final String value) {
        if (!DATA_NAME.matcher(name).matches() || value.length() > DATA_VALUE_MAX
                || value.chars().anyMatch(ch -> ch < 0x20 && ch != '\n' && ch != '\t')) {
            return false;
        }
        return DATA_IN_USE.contains(name)
                || (!DATA_RESERVED.contains(name) && DATA_RESERVED_PREFIXES.stream().noneMatch(name::startsWith));
    }

    private static String pageId(final String element, final String attribute, final String value) {
        return ID.matcher(value).matches() && !PAGE_IDS.contains(value) ? value : null;
    }

    /**
     * A link's address, on top of the library's scheme list: here, a fragment, a path on this site, a relative
     * page, or one of the allowed schemes, and never anything a browser would read as another host without
     * saying so ({@code //host}, {@code /\host}, a backslash or a control character anywhere).
     */
    static String linkAddress(final String element, final String attribute, final String value) {
        final String address = value.trim();
        return sameHostOrNamedScheme(address) ? address : null;
    }

    /** An image's address: http(s), or a file on this site. No {@code data:} picture, by the scheme list. */
    static String imageAddress(final String element, final String attribute, final String value) {
        final String address = value.trim();
        final String lower = address.toLowerCase(Locale.ROOT);
        final boolean web = lower.startsWith("https://") || lower.startsWith("http://");
        final boolean local = !lower.contains(":");
        return sameHostOrNamedScheme(address) && (web || local) ? address : null;
    }

    /** A frame's address: any https site (the owner's choice, 2026-11-17), and nothing else. */
    static String frameAddress(final String element, final String attribute, final String value) {
        final String address = value.trim();
        final boolean https = address.regionMatches(true, 0, "https://", 0, 8);
        return https && sameHostOrNamedScheme(address) ? address : null;
    }

    private static boolean sameHostOrNamedScheme(final String address) {
        if (address.isEmpty() || address.startsWith("//") || address.startsWith("/\\")) {
            return false;
        }
        return address.chars().noneMatch(ch -> ch == '\\' || ch < 0x20 || ch == 0x7f);
    }

    /** A frame's {@code allow} list, kept only when every feature it names is one of {@link #FRAME_FEATURES}. */
    static String frameFeatures(final String element, final String attribute, final String value) {
        for (final String feature : value.split(";")) {
            final String name = feature.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && !FRAME_FEATURES.contains(name)) {
                return null;
            }
        }
        return value;
    }

    /**
     * Before the library: checks each tag's {@code style} with {@link PageCss} and hands the kept part on under
     * {@link #CHECKED_STYLE}, and a link's {@code rel} under {@link #CHECKED_REL}. An attribute that arrives
     * already wearing either name is the author's, unchecked, and is removed; so is a second {@code style} or
     * {@code rel} on one tag, which a browser ignores too.
     *
     * <p>It also steers the markers of the body's comments ({@link PageAsides}) away from the two places text
     * cannot stay: out of every attribute value, and, between the items of a list or the rows of a table, on to
     * the next ordinary text.
     */
    private static final class StyleChecker extends HtmlStreamEventReceiverWrapper {
        /** Right after one of these opens, the sanitizer keeps no text: it wraps it or moves it out. */
        private static final Set<String> NO_TEXT_INSIDE = Set.of("ul", "ol", "dl", "table", "thead", "tbody",
                "tfoot", "tr", "colgroup");
        /** Right after one of these closes, the body is back between the items of a list or the parts of a table. */
        private static final Set<String> BACK_BETWEEN = Set.of("li", "dt", "dd", "tr", "td", "th", "thead",
                "tbody", "tfoot", "caption", "colgroup", "col");

        private final List<Drop> drops;
        private final PageAsides comments;
        private boolean noTextHere;

        StyleChecker(final HtmlStreamEventReceiver underlying, final List<Drop> drops, final PageAsides comments) {
            super(underlying);
            this.drops = drops;
            this.comments = comments;
        }

        @Override
        public void closeTag(final String element) {
            noTextHere = BACK_BETWEEN.contains(element.toLowerCase(Locale.ROOT));
            underlying.closeTag(element);
        }

        @Override
        public void text(final String text) {
            underlying.text(noTextHere ? comments.hold(text) : comments.release(text));
        }

        @Override
        public void openTag(final String element, final List<String> attributes) {
            final List<String> out = new ArrayList<>(attributes.size());
            noTextHere = NO_TEXT_INSIDE.contains(element.toLowerCase(Locale.ROOT));
            // A browser takes the first of two attributes with one name; so does this.
            final Map<String, String> data = new LinkedHashMap<>();
            boolean styled = false;
            boolean related = false;
            for (int i = 0; i + 1 < attributes.size(); i += 2) {
                final String name = attributes.get(i);
                final String value = comments.without(attributes.get(i + 1));
                if (CHECKED_STYLE.equalsIgnoreCase(name) || CHECKED_REL.equalsIgnoreCase(name)
                        || CHECKED_DATA.equalsIgnoreCase(name)) {
                    continue;
                }
                if (comments.marks(name)) {
                    // A comment or style block typed in the MIDDLE of an attribute's name: its marker would be
                    // written as part of the name and the raw text put back inside the tag, where its first ">"
                    // ends the tag and the rest is live markup. No such attribute goes any further.
                    drops.add(new Drop(Drop.Kind.ATTRIBUTE, element, comments.without(name)));
                    continue;
                }
                final String lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith("data-") && dataAllowed(lower, value)) {
                    data.putIfAbsent(lower, value);
                    continue;
                }
                if (STYLE.equalsIgnoreCase(name)) {
                    if (!styled) {
                        addChecked(value, out);
                    }
                    styled = true;
                } else if (REL.equalsIgnoreCase(name)) {
                    if (!related) {
                        addRel(element, value, out);
                    }
                    related = true;
                } else {
                    out.add(name);
                    out.add(value);
                }
            }
            if (!data.isEmpty()) {
                out.add(CHECKED_DATA);
                out.add(data.entrySet().stream().map(StyleChecker::packed).collect(Collectors.joining("&")));
            }
            underlying.openTag(element, out);
        }

        /** One attribute for the trip through the library: nothing in it but letters, digits and percent signs. */
        private static String packed(final Map.Entry<String, String> attribute) {
            return attribute.getKey() + "=" + URLEncoder.encode(attribute.getValue(), StandardCharsets.UTF_8);
        }

        private void addRel(final String element, final String rel, final List<String> out) {
            if (REL_WORDS.matcher(rel).matches()) {
                out.add(CHECKED_REL);
                out.add(rel);
            } else {
                drops.add(new Drop(Drop.Kind.ATTRIBUTE, element, REL));
            }
        }

        /** As typed: an attribute holds declarations only, and the library escapes what it writes ({@link PageCss}). */
        private static void addChecked(final String style, final List<String> out) {
            if (!style.isBlank()) {
                out.add(CHECKED_STYLE);
                out.add(style);
            }
        }
    }

    /**
     * After the library: gives a checked style and a checked rel their real names back (nothing else can be
     * wearing the checked names), and makes sure a link that opens a new tab says {@code noopener}, so the page
     * it opens gets no handle on this one.
     *
     * <p>It is also the last word on where a marker may be ({@link PageAsides}): whatever happened upstream, none
     * leaves here inside an attribute's name or value, inside a frame, or inside {@code svg}
     * ({@link #NO_ASIDES_INSIDE}), where what is put back would be live markup.
     */
    private static final class StyleRestorer extends HtmlStreamEventReceiverWrapper {
        /**
         * Where a browser does not read a restored piece as what it was typed as: a frame's content is read raw,
         * and inside {@code svg} a {@code style} element's content is parsed as MARKUP, not CSS, so an
         * {@code <img onerror>} typed in one would come alive there.
         */
        private static final Set<String> NO_ASIDES_INSIDE = Set.of("iframe", "svg");

        private final PageAsides comments;
        private int frames;

        StyleRestorer(final HtmlStreamEventReceiver underlying, final PageAsides comments) {
            super(underlying);
            this.comments = comments;
        }

        @Override
        public void closeTag(final String element) {
            if (NO_ASIDES_INSIDE.contains(element) && frames > 0) {
                frames--;
            }
            underlying.closeTag(element);
        }

        @Override
        public void text(final String text) {
            underlying.text(frames > 0 ? comments.without(text) : text);
        }

        @Override
        public void openTag(final String element, final List<String> attributes) {
            final List<String> out = withoutMarkedNames(unpacked(attributes));
            if (NO_ASIDES_INSIDE.contains(element)) {
                frames++;
            }
            int rel = -1;
            boolean newTab = false;
            for (int i = 0; i + 1 < out.size(); i += 2) {
                out.set(i + 1, comments.without(out.get(i + 1)));
                if (CHECKED_STYLE.equals(out.get(i))) {
                    out.set(i, STYLE);
                } else if (CHECKED_REL.equals(out.get(i))) {
                    out.set(i, REL);
                    rel = i + 1;
                } else if ("target".equals(out.get(i))) {
                    newTab = "_blank".equalsIgnoreCase(out.get(i + 1));
                }
            }
            if (newTab && "a".equals(element)) {
                addNoOpener(out, rel);
            }
            underlying.openTag(element, out);
        }

        /** The last word on an attribute NAME: whatever happened upstream, none leaves here holding a marker. */
        private List<String> withoutMarkedNames(final List<String> attributes) {
            final List<String> out = new ArrayList<>(attributes.size());
            for (int i = 0; i + 1 < attributes.size(); i += 2) {
                if (!comments.marks(attributes.get(i))) {
                    out.add(attributes.get(i));
                    out.add(attributes.get(i + 1));
                }
            }
            return out;
        }

        /** The attributes with the author's {@code data-} ones back under their own names, where they were. */
        private static List<String> unpacked(final List<String> attributes) {
            final List<String> out = new ArrayList<>(attributes.size());
            for (int i = 0; i + 1 < attributes.size(); i += 2) {
                if (CHECKED_DATA.equals(attributes.get(i))) {
                    for (final String pair : attributes.get(i + 1).split("&")) {
                        final int equals = pair.indexOf('=');
                        out.add(pair.substring(0, equals));
                        out.add(URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
                    }
                } else {
                    out.add(attributes.get(i));
                    out.add(attributes.get(i + 1));
                }
            }
            return out;
        }

        private static void addNoOpener(final List<String> attributes, final int rel) {
            if (rel < 0) {
                attributes.add(REL);
                attributes.add(NO_OPENER);
            } else if (!List.of(attributes.get(rel).toLowerCase(Locale.ROOT).split("\\s+")).contains(NO_OPENER)) {
                attributes.set(rel, (attributes.get(rel).trim() + " " + NO_OPENER).trim());
            }
        }
    }
}
