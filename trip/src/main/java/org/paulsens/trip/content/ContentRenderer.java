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
            case TEXT, CHOICE -> escapeHtml(raw);
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
