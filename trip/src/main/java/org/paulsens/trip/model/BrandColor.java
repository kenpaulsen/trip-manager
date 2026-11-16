package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;

/**
 * One of an organization's own named colors ({@link Organization#getBrandColors()}), offered beside the theme colors in
 * the rich-text editors' color buttons and the trip page style editor, whatever palette the org's site uses.
 *
 * <p>Pages carry it as the custom property {@link #varName()}, {@code --brand-<id>}, set on the org site's root
 * element to the light or the dark value for the current mode. The {@link #id} never changes, so renaming a color
 * never breaks text already colored with it; only deleting it does (that text then draws in the light value it
 * carries as its fallback, see {@code EditorColors}).
 *
 * @param id    stable identity, a short lowercase token
 * @param name  what the editors call it ("Parish gold"); unique within the org; admin configuration, never sent to a
 *              visitor
 * @param light the color in light mode, {@code #rrggbb}
 * @param dark  the color in dark mode, {@code #rrggbb}, or null for the light one in both modes
 */
public record BrandColor(String id, String name, String light, String dark) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** What every brand color's custom property starts with. */
    public static final String VAR_PREFIX = "--brand-";

    @JsonCreator
    public BrandColor(
            @JsonProperty("id") final String id,
            @JsonProperty("name") final String name,
            @JsonProperty("light") final String light,
            @JsonProperty("dark") final String dark) {
        this.id = id;
        this.name = name;
        this.light = light;
        this.dark = dark == null || dark.isBlank() ? null : dark;
    }

    /** The custom property pages read: {@code --brand-<id>}. */
    public String varName() {
        return VAR_PREFIX + id;
    }

    /** The color for a mode: the dark value in dark mode when there is one, else the light value. */
    public String valueFor(final boolean darkMode) {
        return darkMode && dark != null ? dark : light;
    }
}
