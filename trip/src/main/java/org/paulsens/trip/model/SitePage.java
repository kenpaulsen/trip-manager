package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;

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
 * @param name      the page's address and content key, {@code [a-z][a-z0-9-]{0,39}}; unique within the org
 * @param title     the menu label and the page's browser title
 * @param url       the target of a LINK; null for a PAGE
 * @param placement where the menu shows it, a {@link Placement} name
 */
public record SitePage(String name, String title, String url, String placement) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

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
            @JsonProperty("placement") final String placement) {
        this.name = name;
        this.title = title;
        this.url = (url == null || url.isBlank()) ? null : url.trim();
        this.placement = placement;
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
        return new SitePage(name, newTitle, isLink() ? newUrl : null, newPlacement.name());
    }
}
