package org.paulsens.trip.content;

/**
 * The string work behind the embedded trip page's inline stylesheet ({@code ConferenceCommands.embedCss}, the
 * render the apps fetch and cache; docs/conference.md "The embed"): which part of a theme to carry, and the rules
 * the embed adds on top. Pure functions, so the pieces are unit-tested without a container.
 */
public final class EmbedStyles {
    private EmbedStyles() {
    }

    /**
     * The theme's {@code :root{...}} block alone: its custom properties ({@code --primary-color}, the surfaces, the
     * text color) are what {@code site.css} and a trip's own stylesheet read; the remaining 200 KB style PrimeFaces
     * widgets the embed never renders. Found by index, never by a pattern over the whole file, and empty when the
     * theme has no such block. A leading byte-order mark is skipped.
     */
    public static String rootBlockOf(final String themeCss) {
        if (themeCss == null) {
            return "";
        }
        final int start = themeCss.indexOf(":root{");
        if (start < 0) {
            return "";
        }
        final int end = themeCss.indexOf('}', start);
        return end < 0 ? "" : themeCss.substring(start, end + 1);
    }

    /**
     * What the embed hides or repaints: the Join and Register buttons (the app owns registration, and a tap would
     * open an anonymous sign-in page in the system browser), the shared site's rainbow backdrop (an app view shows
     * the trip's own background or none), and the full-width ground (the app's backdrop shows through unless the
     * trip set a background of its own).
     */
    public static String overrides() {
        return ".tripEmbed .cta-row, .tripEmbed .regStatus-button, .tripEmbed .confNavCta { display: none; }\n"
                + "html.tripEmbed body:before { background-image: var(--site-bg, none); }\n"
                + ".tripEmbed .layoutFull { background: var(--layout-full-bg, transparent); }\n"
                + "html.tripEmbed, .tripEmbed body { margin: 0; padding: 0; }\n";
    }
}
