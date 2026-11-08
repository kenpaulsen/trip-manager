package org.paulsens.trip.content;

/**
 * The string work behind the embedded trip page's inline stylesheet ({@code TripPageCommands.embedCss}, the
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
     * What the embed hides, repaints or supplies: the Join and Register buttons (the app owns registration, and a tap
     * would open an anonymous sign-in page in the system browser), the shared site's rainbow backdrop (an app view
     * shows the trip's own background or none), the full-width ground (the app's backdrop shows through unless the
     * trip set a background of its own), and the page's base typography. The site gets its body font, size and color
     * from the layout stylesheet, which the embed does not carry (only the theme's variables), so without these a
     * page drew in the browser's default serif. An old description page ({@code .tripEmbedText}) also gets the
     * margins its card gave it on the site, and its pictures and videos never overflow a phone's width.
     */
    public static String overrides() {
        return ".tripEmbed .cta-row, .tripEmbed .regStatus-button, .tripEmbed .confNavCta { display: none; }\n"
                + "html.tripEmbed body:before { background-image: var(--site-bg, none); }\n"
                + ".tripEmbed .layoutFull { background: var(--layout-full-bg, transparent); }\n"
                + "html.tripEmbed, .tripEmbed body { margin: 0; padding: 0; }\n"
                + "body.tripEmbed { font-family: var(--font-family, -apple-system, system-ui, Roboto, sans-serif);"
                + " font-size: 14px; font-weight: 400; line-height: 1.5; color: var(--text-color, #495057); }\n"
                + ".tripEmbed .tripEmbedText { padding: 16px; overflow-wrap: anywhere; }\n"
                + ".tripEmbed .tripEmbedText img, .tripEmbed .tripEmbedText iframe, .tripEmbed .tripEmbedText video"
                + " { max-width: 100%; }\n"
                + ".tripEmbed .tripEmbedText img { height: auto; }\n";
    }
}
