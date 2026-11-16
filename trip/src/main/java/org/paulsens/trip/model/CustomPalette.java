package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import java.util.TreeMap;

/**
 * One of an organization's own color palettes ({@link Organization#getCustomPalettes()}), drawn by the generated
 * {@code freya-custom-{light|dark}} theme. Its colors are the theme's INPUTS: the names the freya build's generator
 * found every palette-dependent color to come from ({@code primary-color}, {@code shade700}, {@code body-bg-color},
 * ...), each a {@code #rrggbb} or {@code #rrggbbaa} value. The server sets them on the page as {@code --ct-<name>}.
 *
 * <p>Only the LIGHT set is stored whole. The dark set is derived from it and from {@link #source}'s stock dark
 * values ({@code CustomPalettes.darkInputs}), so an admin who changes the accent gets a matching dark mode without
 * doing it twice, and {@link #darkOverrides} holds just the dark colors they set by hand on top. The org site's
 * palette setting names a palette as {@code custom:<id>}; the {@link #id} never changes, so renaming a palette never
 * unlinks the site from it.
 *
 * @param id            stable identity, a short lowercase token; the palette setting stores {@code custom:<id>}
 * @param name          what the Appearance page's card shows; unique within the org
 * @param source        the stock palette it was cloned from ({@code blue}, {@code medj} = platform default, ...);
 *                      the dark derivation starts from that palette's dark values
 * @param light         every light input, name to color
 * @param darkOverrides the dark inputs set by hand; empty means dark mode is fully automatic
 */
public record CustomPalette(String id, String name, String source, Map<String, String> light,
        Map<String, String> darkOverrides) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** What the palette setting stores for a custom palette, before its id. */
    public static final String REF_PREFIX = "custom:";

    @JsonCreator
    public CustomPalette(
            @JsonProperty("id") final String id,
            @JsonProperty("name") final String name,
            @JsonProperty("source") final String source,
            @JsonProperty("light") final Map<String, String> light,
            @JsonProperty("darkOverrides") final Map<String, String> darkOverrides) {
        this.id = id;
        this.name = name;
        this.source = source;
        // TreeMap: serializable, ordered (a stable row), and a defensive copy of whatever the caller held.
        this.light = light == null ? new TreeMap<>() : new TreeMap<>(light);
        this.darkOverrides = darkOverrides == null ? new TreeMap<>() : new TreeMap<>(darkOverrides);
    }

    /** The palette setting's value that selects this palette. */
    public String ref() {
        return REF_PREFIX + id;
    }

    /** The id a palette setting value names, or null when it names no custom palette. */
    public static String idOf(final String setting) {
        return setting != null && setting.startsWith(REF_PREFIX) && setting.length() > REF_PREFIX.length()
                ? setting.substring(REF_PREFIX.length()) : null;
    }

    /** A copy with another name (the id and colors kept). */
    public CustomPalette withName(final String newName) {
        return new CustomPalette(id, newName, source, light, darkOverrides);
    }

    /** A copy with other colors (the id, name and source kept). */
    public CustomPalette withColors(final Map<String, String> newLight, final Map<String, String> newDark) {
        return new CustomPalette(id, name, source, newLight, newDark);
    }
}
