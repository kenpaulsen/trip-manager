package org.paulsens.trip.theme;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a theme's custom properties to plain colors, with no browser: the declarations of a compiled theme's
 * {@code :root} blocks ({@code --text-color:var(--ct-shade700)}, {@code --status-danger:var(--red-700)},
 * {@code --red-700:rgb(211.65, 47.15, 47.15)}, ...) followed through every {@code var()} to a {@code #rrggbb(aa)}.
 *
 * <p>What needs it: a color a rich-text editor inserts carries the org's current color as its {@code var()} fallback
 * (and an email gets that color alone), for the places that do not define the theme's variables (mail clients, the
 * phone apps' web views, print). The page itself keeps the variable, so it still follows the palette and dark mode.
 *
 * <p>It understands hex, {@code rgb()}/{@code rgba()} (decimal channels, as the Sass build writes them),
 * {@code var(--x[, fallback])} and the one {@code color-mix} shape the custom theme writes
 * ({@code color-mix(in srgb, <color> <p>%, transparent)}). Anything else resolves to null rather than a guess.
 */
public final class ThemeVars {

    private static final Pattern DECLARATION = Pattern.compile("(--[A-Za-z0-9-]+)\\s*:\\s*([^;}]*)");
    private static final Pattern VAR = Pattern.compile("var\\(\\s*(--[A-Za-z0-9-]+)\\s*(?:,\\s*(.*))?\\)");
    /** The argument list is split on commas in {@link #rgb}, not matched channel by channel (REDOS). */
    private static final Pattern RGB = Pattern.compile("rgba?\\(([^()]*)\\)");
    private static final Pattern NUMBER = Pattern.compile("[0-9.]+");
    private static final Pattern MIX_TRANSPARENT = Pattern.compile(
            "color-mix\\(\\s*in srgb\\s*,\\s*(.+?)\\s+([0-9.]+)%\\s*,\\s*transparent\\s*\\)");
    /** Deeper than any real chain (the custom theme's longest is three); a cycle ends here too. */
    private static final int MAX_DEPTH = 12;

    private ThemeVars() {
    }

    /**
     * Every custom property declared in {@code css} (a theme's {@code :root} blocks), name to raw value; a later
     * declaration of the same name wins, as it does in the browser.
     */
    public static Map<String, String> declarations(final String css) {
        final Map<String, String> out = new LinkedHashMap<>();
        if (css == null) {
            return out;
        }
        final Matcher m = DECLARATION.matcher(css);
        while (m.find()) {
            out.put(m.group(1), m.group(2).trim());
        }
        return out;
    }

    /** {@code name} resolved through {@code vars} to {@code #rrggbb(aa)}, or null when it is not a plain color. */
    public static String resolve(final Map<String, String> vars, final String name) {
        return value(vars, vars.get(name), 0, new HashSet<>(Set.of(name)));
    }

    /** A translucent color laid over an opaque one ({@code #rrggbb}): what a reader actually sees. */
    public static String over(final String color, final String ground) {
        final double[] fg = ThemeColorMath.parse(color);
        final double[] bg = ThemeColorMath.parse(ground);
        if (fg == null || bg == null) {
            return null;
        }
        return ThemeColorMath.hex(ThemeColorMath.mix(new double[] {fg[0], fg[1], fg[2], 1},
                new double[] {bg[0], bg[1], bg[2], 1}, fg[3]));
    }

    private static String value(final Map<String, String> vars, final String raw, final int depth,
            final Set<String> seen) {
        if (raw == null || depth > MAX_DEPTH) {
            return null;
        }
        final String text = raw.trim();
        final String hex = ThemeColorMath.normalize(text.startsWith("#") ? text : "");
        if (hex != null) {
            return hex;
        }
        final Matcher var = VAR.matcher(text);
        if (var.matches()) {
            final String name = var.group(1);
            final String found = seen.add(name) ? value(vars, vars.get(name), depth + 1, seen) : null;
            return found != null ? found : value(vars, var.group(2), depth + 1, seen);
        }
        final Matcher rgb = RGB.matcher(text.toLowerCase(Locale.ROOT));
        if (rgb.matches()) {
            return rgb(rgb.group(1));
        }
        final Matcher mix = MIX_TRANSPARENT.matcher(text);
        if (mix.matches()) {
            final double[] base = ThemeColorMath.parse(value(vars, mix.group(1), depth + 1, seen));
            return base == null ? null : ThemeColorMath.hex(new double[] {base[0], base[1], base[2],
                base[3] * Double.parseDouble(mix.group(2)) / 100});
        }
        return null;
    }

    /** {@code r, g, b[, a]} as decimal numbers to {@code #rrggbb(aa)}; anything else is null. */
    private static String rgb(final String args) {
        final String[] parts = args.split(",", -1);
        if (parts.length != 3 && parts.length != 4) {
            return null;
        }
        final double[] channels = {0, 0, 0, 1};
        for (int i = 0; i < parts.length; i++) {
            final String part = parts[i].trim();
            if (!NUMBER.matcher(part).matches()) {
                return null;
            }
            try {
                channels[i] = Double.parseDouble(part);
            } catch (final NumberFormatException ex) {
                return null;
            }
        }
        return ThemeColorMath.hex(channels);
    }
}
