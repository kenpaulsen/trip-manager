package org.paulsens.trip.content;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Placeholder;

/**
 * Substitutes a {@link ContentInstance}'s values into its {@link ContentTemplate}'s body.
 *
 * <p>Tokens are written <code>{{name}}</code> -- a syntax that survives WYSIWYG round trips and cannot
 * collide with EL ({@code #{}}) or JSF markup. Escaping is decided by the placeholder's declared type, which
 * is the security model: the rendered string is emitted with {@code escape="false"}, so everything except
 * {@link Placeholder.Type#RICH_TEXT} must be neutralized here. TEXT is HTML-escaped; URL types must parse as
 * http(s) or render empty, and are attribute-escaped; RICH_TEXT is inserted verbatim -- WYSIWYG output from
 * trusted admins (granting {@code contentAdmin} is equivalent to granting script on public pages; see
 * docs/content-templates.md).
 *
 * <p>A token with no matching declared placeholder, or a missing value, renders as the empty string: a
 * public page must never leak a literal <code>{{token}}</code>.
 */
public final class ContentRenderer {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");
    /**
     * A CONTAINER body's own vocabulary, deliberately separate from {@link #TOKEN}: {@code {{child}}} marks
     * where the child renders and {@code {{child:prop}}} reads one of its properties. Keeping it out of
     * TOKEN means a STANDARD template can never accidentally declare a placeholder named "child", and
     * "Detect from body" never offers one.
     */
    private static final Pattern CHILD_TOKEN =
            Pattern.compile("\\{\\{\\s*child(?::([A-Za-z0-9_-]+))?\\s*}}");
    /** Delimits the repeated region, so the body can also carry a wrapper emitted once around the list. */
    private static final Pattern CHILDREN_REGION =
            Pattern.compile("\\{\\{\\s*children:(start|end)\\s*}}");
    private static final String START = "start";
    private static final String END = "end";
    /** YouTube watch/short/shorts forms whose video id should be rewritten to the embed URL. */
    private static final Pattern YOUTUBE = Pattern.compile(
            "^https?://(?:www\\.|m\\.)?(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/)|youtu\\.be/)"
                    + "([A-Za-z0-9_-]{5,20}).*$");

    private ContentRenderer() {
    }

    /** The distinct token names appearing in a body, in first-appearance order -- for "detect from body". */
    public static Set<String> tokenNames(final String body) {
        final LinkedHashSet<String> names = new LinkedHashSet<>();
        if (body != null) {
            final Matcher matcher = TOKEN.matcher(body);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /** @return the template body with every token replaced by its typed, escaped value. */
    public static String render(final ContentTemplate template, final ContentInstance instance) {
        if (template == null || template.getBody() == null || instance == null) {
            return "";
        }
        final Map<String, String> values = instance.getValues();
        final Matcher matcher = TOKEN.matcher(template.getBody());
        final StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            final String name = matcher.group(1);
            final Placeholder declared = template.getPlaceholders().stream()
                    .filter(ph -> ph.getName().equals(name))
                    .findFirst()
                    .orElse(null);
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(renderValue(declared, values.get(name))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String renderValue(final Placeholder declared, final String raw) {
        if (declared == null || raw == null || raw.isBlank()) {
            return "";
        }
        return switch (declared.getType()) {
            case TEXT, CHOICE, MULTI_CHOICE -> escapeHtml(raw);
            case RICH_TEXT -> raw;
            case IMAGE_URL, URL -> escapeHtml(requireHttpUrl(raw));
            case VIDEO_URL -> escapeHtml(normalizeVideoUrl(raw));
        };
    }

    /**
     * A container instance's on-page title. Plain text gets the site's default heading treatment; a title
     * containing markup renders verbatim (the WYSIWYG/raw escape hatch, validated at save). Blank renders
     * nothing -- an untitled container is legal.
     */
    public static String renderContainerTitle(final String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        if (title.indexOf('<') >= 0) {
            return title;
        }
        return "<h3 class=\"contentTitle\">" + escapeHtml(title.trim()) + "</h3>";
    }

    /**
     * The row a container wraps around each of its children when its body says nothing else. It reproduces
     * exactly what the page markup used to hardcode, so seeding it changes no rendered byte.
     */
    public static final String DEFAULT_CHILD_ROW = "<div class=\"contentTitle\">{{child:title}}</div>{{child}}";

    /** The two halves of a container's row for one child: the markup around {@code {{child}}}. */
    public record ChildRow(String before, String after) {
    }

    /**
     * A container body's three parts. {@code beforeAll}/{@code afterAll} are emitted ONCE around the whole
     * child list -- the wrapper a row cannot express ({@code <ul>}, {@code <table>}) -- and {@code row} is
     * repeated per child. A body with no region markers is entirely a row, which is what every container
     * written before they existed is.
     */
    public record ContainerBody(String beforeAll, String row, String afterAll) {
    }

    /**
     * Parses a container body into its once-around wrapper and its per-child row:
     *
     * <pre>
     * &lt;ul&gt;                      &lt;- beforeAll
     * {{children:start}}
     *   &lt;li&gt;{{child}}&lt;/li&gt;      &lt;- row, repeated
     * {{children:end}}
     * &lt;/ul&gt;                     &lt;- afterAll
     * </pre>
     *
     * <p>Anything unusable -- blank, no {@code {{child}}} in the row, a region opened and never closed --
     * degrades to the built-in row with no wrapper. Half a wrapper would emit an unclosed {@code <ul>} into
     * the page, and dropping the children outright is the one failure an editor cannot diagnose.
     */
    public static ContainerBody containerBody(final String body) {
        final ContainerBody parsed = body == null || body.isBlank() ? null : parseContainerBody(body);
        return parsed != null && hasChildSlot(parsed.row())
                ? parsed : new ContainerBody("", DEFAULT_CHILD_ROW, "");
    }

    /**
     * @return the body's three parts exactly as written, or null when its region markers are not a single
     *         well-ordered pair. No markers at all is not malformed: the whole body is then the row.
     */
    private static ContainerBody parseContainerBody(final String body) {
        final Matcher region = CHILDREN_REGION.matcher(body);
        int beforeEnd = -1;
        int rowStart = -1;
        int rowEnd = -1;
        int afterStart = -1;
        int markers = 0;
        while (region.find()) {
            markers++;
            if (START.equals(region.group(1)) && rowStart < 0 && rowEnd < 0) {
                beforeEnd = region.start();
                rowStart = region.end();
            } else if (END.equals(region.group(1)) && rowStart >= 0 && rowEnd < 0) {
                rowEnd = region.start();
                afterStart = region.end();
            } else {
                return null;
            }
        }
        if (markers == 0) {
            return new ContainerBody("", body, "");
        }
        return markers == 2 && rowEnd >= 0
                ? new ContainerBody(body.substring(0, beforeEnd), body.substring(rowStart, rowEnd),
                        body.substring(afterStart))
                : null;
    }

    /**
     * @return why this body cannot be saved as a container's, or null when it is fine. Blank is fine and
     *         means the built-in row.
     */
    public static String containerBodyProblem(final String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        final Matcher region = CHILDREN_REGION.matcher(body);
        int starts = 0;
        int ends = 0;
        boolean endBeforeStart = false;
        while (region.find()) {
            if (START.equals(region.group(1))) {
                starts++;
            } else {
                ends++;
                endBeforeStart = endBeforeStart || starts == 0;
            }
        }
        if (starts > 1 || ends > 1) {
            return "A container body may open and close {{children:start}} / {{children:end}} only once.";
        }
        if (starts != ends || endBeforeStart) {
            return "A container body must use {{children:start}} and {{children:end}} as a pair, in that "
                    + "order, around the row (or use neither).";
        }
        // The RAW row, not containerBody()'s -- that one answers the built-in row for anything unusable,
        // which would report every slotless body as fine.
        final ContainerBody parsed = parseContainerBody(body);
        if (parsed == null || !hasChildSlot(parsed.row())) {
            return "A container body must contain {{child}} to mark where each item renders "
                    + "(leave it blank for the default row).";
        }
        return null;
    }

    /** @return true when the body marks where its children render -- the one thing a container body needs. */
    public static boolean hasChildSlot(final String body) {
        if (body == null) {
            return false;
        }
        final Matcher matcher = CHILD_TOKEN.matcher(body);
        while (matcher.find()) {
            if (matcher.group(1) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Splits a container's body at {@code {{child}}} and fills the child-property tokens in each half, so
     * the page can emit the markup around a child that is itself a JSF component tree (a programmatic
     * fragment, the edit buttons) rather than a string.
     *
     * <p>Operates on the ROW part of the body ({@link #containerBody}), which for a body with no region
     * markers is the whole of it.
     *
     * @param childRendersOwnHeading true for a child that titles itself from live data (a PROGRAMMATIC
     *         one), for which {@code {{child:title}}} is empty -- the instance title would duplicate it.
     */
    public static ChildRow renderChildRow(final String body, final ContentInstance child,
            final boolean childRendersOwnHeading, final int index) {
        final String row = containerBody(body).row();
        final Matcher slot = CHILD_TOKEN.matcher(row);
        int start = row.length();
        int end = row.length();
        while (slot.find()) {
            if (slot.group(1) == null) {
                start = slot.start();
                end = slot.end();
                break;
            }
        }
        return new ChildRow(
                childTokens(row.substring(0, start), child, childRendersOwnHeading, index),
                childTokens(row.substring(end), child, childRendersOwnHeading, index));
    }

    private static String childTokens(final String part, final ContentInstance child,
            final boolean childRendersOwnHeading, final int index) {
        final Matcher matcher = CHILD_TOKEN.matcher(part);
        final StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            final String property = matcher.group(1);
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    childProperty(property, child, childRendersOwnHeading, index)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Child properties a container may read. All escaped: a container body is markup, values are data. */
    private static String childProperty(final String property, final ContentInstance child,
            final boolean childRendersOwnHeading, final int index) {
        if (property == null || child == null) {
            return "";
        }
        return switch (property) {
            case "title" -> childRendersOwnHeading || child.getTitle() == null
                    ? "" : escapeHtml(child.getTitle().trim());
            case "id" -> child.getId() == null ? "" : escapeHtml(child.getId());
            case "index" -> String.valueOf(index + 1);
            default -> "";
        };
    }

    /** @return the URL when it parses as absolute http(s), else the empty string. */
    static String requireHttpUrl(final String raw) {
        try {
            final URI uri = URI.create(raw.trim());
            final String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return raw.trim();
            }
        } catch (final IllegalArgumentException ignored) {
            // Fall through to empty: an unparseable URL must not reach the page.
        }
        return "";
    }

    /**
     * Normalizes the YouTube link forms people actually paste ({@code watch?v=}, {@code youtu.be/},
     * {@code /shorts/}, already-{@code /embed/}) to the embeddable URL; any other http(s) URL passes
     * through for non-YouTube players.
     */
    static String normalizeVideoUrl(final String raw) {
        final String url = requireHttpUrl(raw);
        if (url.isEmpty()) {
            return "";
        }
        final Matcher matcher = YOUTUBE.matcher(url);
        return matcher.matches() ? "https://www.youtube.com/embed/" + matcher.group(1) : url;
    }

    /** Minimal HTML/attribute escaping; both element and attribute contexts are covered by the five. */
    static String escapeHtml(final String raw) {
        final StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
