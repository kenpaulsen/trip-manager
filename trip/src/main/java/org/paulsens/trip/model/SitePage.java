package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.paulsens.trip.util.Util;

/**
 * One entry of an organization site's menu ({@link Organization#getSitePages()}): either a PAGE -- an editable
 * content page served at {@code /page.jsf?name={name}} whose sections live under {@code page:org:{orgId}:{name}}
 * -- or a LINK to any URL (a hosted donate button, a partner's site). A null {@link #url} is what makes it a page,
 * so there is no separate kind to drift out of step with it.
 *
 * <p>The {@link #name} is the page's address and the key of its content: it is fixed once created, because
 * renaming it would orphan every section stored under the old key. The {@link #placement} is stored as a
 * {@link Placement} NAME so a value from a newer build reads back as {@link Placement#HIDDEN} rather than failing
 * the whole organization row.
 *
 * <p>The {@link #titles} are the entry's title in the site's OTHER languages ({@code docs/i18n.md}, "Language
 * versions of a page"), keyed by language tag; {@link #title} stays the one in the site's default language and
 * the one every reader falls back to. An older row has none and reads exactly as it did.
 *
 * @param name      the page's address and content key, {@code [a-z][a-z0-9-]{0,39}}; unique within the org
 * @param title     the menu label and the page's browser title, in the site's default language
 * @param url       the target of a LINK; null for a PAGE
 * @param placement where the menu shows it, a {@link Placement} name
 * @param titles    language tag to the title in that language; never null, empty when there are none
 */
public record SitePage(String name, String title, String url, String placement,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> titles) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** The address of a PAGE, before its {@link #name}: ONE copy, so a menu link and a link in copy agree. */
    public static final String PATH = "/page.jsf?name=";

    /** Where a site page appears in the menu. */
    public enum Placement {
        /** The "About" submenu, rendered only when it has entries. */
        ABOUT,
        /** The Help submenu, above the built-in Donate and Make Payment items. */
        HELP,
        /** Not in the menu: reachable only by a link (a novena's day pages, say). */
        HIDDEN;

        /** The stored name back as a placement; null, blank or unknown read as {@link #HIDDEN}. */
        public static Placement from(final String stored) {
            if (stored != null) {
                for (final Placement value : values()) {
                    if (value.name().equals(stored.trim())) {
                        return value;
                    }
                }
            }
            return HIDDEN;
        }
    }

    @JsonCreator
    public SitePage(
            @JsonProperty("name") final String name,
            @JsonProperty("title") final String title,
            @JsonProperty("url") final String url,
            @JsonProperty("placement") final String placement,
            @JsonProperty("titles") final Map<String, String> titles) {
        this.name = name;
        this.title = title;
        this.url = Util.trimToNull(url);
        this.placement = placement;
        this.titles = cleaned(titles);
    }

    /** An entry with its title in the site's default language only. */
    public SitePage(final String name, final String title, final String url, final String placement) {
        this(name, title, url, placement, null);
    }

    /**
     * The title a reader of {@code language} sees: the one written for that language, else {@link #title}. Null
     * (an unknown tag) is the default title too.
     */
    public String titleIn(final String language) {
        final String own = language == null ? null : titles.get(language);
        return own == null ? title : own;
    }

    /** This entry with other per-language titles (blanks dropped); everything else unchanged. */
    public SitePage withTitles(final Map<String, String> newTitles) {
        return new SitePage(name, title, url, placement, newTitles);
    }

    /**
     * A read-only copy holding only non-blank titles, trimmed, over a {@link LinkedHashMap}: serializable for the
     * session, stable in its order, and never null, so an older row without titles is one with none.
     */
    private static Map<String, String> cleaned(final Map<String, String> titles) {
        final Map<String, String> out = new LinkedHashMap<>();
        if (titles != null) {
            titles.forEach((tag, text) -> putIfWritten(out, tag, text));
        }
        return Collections.unmodifiableMap(out);
    }

    private static void putIfWritten(final Map<String, String> out, final String tag, final String text) {
        if (tag != null && text != null && !text.isBlank()) {
            out.put(tag.trim(), text.trim());
        }
    }

    /** True for a LINK entry (it has a URL), false for an editable content page. */
    @JsonIgnore
    public boolean isLink() {
        return url != null;
    }

    /** The resolved placement. */
    @JsonIgnore
    public Placement where() {
        return Placement.from(placement);
    }

    /** This entry with another title and placement (the URL too for a link); the name never changes. */
    public SitePage withDetails(final String newTitle, final String newUrl, final Placement newPlacement) {
        return new SitePage(name, newTitle, isLink() ? newUrl : null, newPlacement.name(), titles);
    }
}
