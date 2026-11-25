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
 * <p>The {@link #access} says who may SEE a page ({@link Access}): an older row has none, which is
 * {@link Access#EVERYONE}, so every page stored before the gate existed reads exactly as it did. Like the placement
 * it is stored as a NAME, but an unknown one fails CLOSED ({@link Access#SITE_ADMINS}): a gate a newer build wrote
 * must never read back as open.
 *
 * @param name      the page's address and content key, {@code [a-z][a-z0-9-]{0,39}}; unique within the org
 * @param title     the menu label and the page's browser title, in the site's default language
 * @param url       the target of a LINK; null for a PAGE
 * @param placement where the menu shows it, a {@link Placement} name
 * @param titles    language tag to the title in that language; never null, empty when there are none
 * @param access    who may see the page, an {@link Access} name; null (every older row) for everyone
 * @param sidebar   whether the page shows the sidebar, a {@link Sidebar} name; null (every older row) follows the
 *                  organization's default
 */
public record SitePage(String name, String title, String url, String placement,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> titles,
        @JsonInclude(JsonInclude.Include.NON_NULL) String access,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sidebar) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** The address of a PAGE, before its {@link #name}: ONE copy, so a menu link and a link in copy agree. */
    public static final String PATH = "/page.jsf?name=";

    /** Where an Editable Page appears in the menu. */
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

    /**
     * Who may see an Editable Page. The menu lists an entry only for a reader who may open it, and the page host
     * sends anyone else to sign in or away ({@code SitePageCommands.canView}).
     */
    public enum Access {
        /** Every visitor, signed in or not: what every page was before the gate existed. */
        EVERYONE,
        /** Anyone signed in. */
        SIGNED_IN,
        /** The organization's admins (and site admins, who manage every organization). */
        ORG_ADMINS,
        /** Site admins only; only a site admin may set it, change such an entry, or delete it. */
        SITE_ADMINS;

        /** The stored name back as an access: null or blank is {@link #EVERYONE}, an unknown name fails closed. */
        public static Access from(final String stored) {
            if (stored == null || stored.isBlank()) {
                return EVERYONE;
            }
            for (final Access value : values()) {
                if (value.name().equals(stored.trim())) {
                    return value;
                }
            }
            return SITE_ADMINS;
        }
    }

    /**
     * Whether a page shows the sidebar beside its content: the organization's default ({@code site.pages.sidebar},
     * on or off), or this page's own choice. An organization that forces the sidebar off overrides SHOW; there is no
     * forcing it on. Stored as a name; anything unknown reads as the default.
     */
    public enum Sidebar {
        /** Whatever the organization's default says. */
        DEFAULT,
        /** Shown, unless the organization forces it off. */
        SHOW,
        /** Hidden. */
        HIDE;

        /** The stored name back as a choice: null, blank or unknown is {@link #DEFAULT}. */
        public static Sidebar from(final String stored) {
            if (stored != null) {
                for (final Sidebar value : values()) {
                    if (value.name().equals(stored.trim())) {
                        return value;
                    }
                }
            }
            return DEFAULT;
        }
    }

    @JsonCreator
    public SitePage(
            @JsonProperty("name") final String name,
            @JsonProperty("title") final String title,
            @JsonProperty("url") final String url,
            @JsonProperty("placement") final String placement,
            @JsonProperty("titles") final Map<String, String> titles,
            @JsonProperty("access") final String access,
            @JsonProperty("sidebar") final String sidebar) {
        this.name = name;
        this.title = title;
        this.url = Util.trimToNull(url);
        this.placement = placement;
        this.titles = cleaned(titles);
        // EVERYONE is stored as nothing, so an open page's row is exactly what it was before the gate existed.
        this.access = Access.from(access) == Access.EVERYONE ? null : Access.from(access).name();
        // DEFAULT is stored as nothing, like an open page's access: an older row reads exactly as it did.
        this.sidebar = Sidebar.from(sidebar) == Sidebar.DEFAULT ? null : Sidebar.from(sidebar).name();
    }

    /** An entry following the organization's sidebar default, with an access and per-language titles. */
    public SitePage(final String name, final String title, final String url, final String placement,
            final Map<String, String> titles, final String access) {
        this(name, title, url, placement, titles, access, null);
    }

    /** An entry everyone may see, with its titles in the site's other languages. */
    public SitePage(final String name, final String title, final String url, final String placement,
            final Map<String, String> titles) {
        this(name, title, url, placement, titles, null, null);
    }

    /** An entry everyone may see, with its title in the site's default language only. */
    public SitePage(final String name, final String title, final String url, final String placement) {
        this(name, title, url, placement, null, null, null);
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
        return new SitePage(name, title, url, placement, newTitles, access, sidebar);
    }

    /** This entry with another access; everything else unchanged. */
    public SitePage withAccess(final Access newAccess) {
        return new SitePage(name, title, url, placement, titles, newAccess == null ? null : newAccess.name(),
                sidebar);
    }

    /** This entry with another sidebar choice; everything else unchanged. */
    public SitePage withSidebar(final Sidebar newSidebar) {
        return new SitePage(name, title, url, placement, titles, access, newSidebar == null ? null : newSidebar.name());
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

    /** The resolved sidebar choice. */
    @JsonIgnore
    public Sidebar side() {
        return Sidebar.from(sidebar);
    }

    /** The resolved access: who may see this page. */
    @JsonIgnore
    public Access who() {
        return Access.from(access);
    }

    /** This entry with another title and placement (the URL too for a link); the name never changes. */
    public SitePage withDetails(final String newTitle, final String newUrl, final Placement newPlacement) {
        return new SitePage(name, newTitle, isLink() ? newUrl : null, newPlacement.name(), titles, access, sidebar);
    }
}
