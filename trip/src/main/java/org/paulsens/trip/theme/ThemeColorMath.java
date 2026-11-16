package org.paulsens.trip.theme;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The color arithmetic behind custom palettes: parsing and writing hex colors, WCAG contrast, and the two
 * derivations an admin relies on, the accent family from one accent color and a palette's dark set from its light
 * one. Plain functions over {@code double[] {r, g, b, a}} (0..255 channels, 0..1 alpha), no state.
 *
 * <p>The derivations copy how the stock Freya palettes relate their colors, rather than inventing a scheme: in every
 * stock palette the accent's hover, pressed and soft shades sit a few HSL lightness points from the accent, and the
 * DARK palette's accent family is the light family shifted up one step (dark accent = light soft shade, dark hover =
 * light accent, dark pressed = light hover), so a dark card still reads the accent.
 */
public final class ThemeColorMath {

    /** The input names of the accent family, as the generated theme names them. */
    public static final String ACCENT = "primary-color";
    public static final String ON_ACCENT = "primary-text-color";
    public static final String HOVER = "primary-dark-color";
    public static final String PRESSED = "primary-darker-color";
    public static final String SOFT = "primary-light-color";

    /** The dark text Freya draws on its pale accents (red, yellow), and white: the two candidates for On accent. */
    static final String DARK_ON_ACCENT = "#3e4754";
    static final String WHITE = "#ffffff";

    private static final Pattern HEX = Pattern.compile("#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})");

    private ThemeColorMath() {
    }

    /** {@code #rrggbb} (or {@code #rrggbbaa} when not opaque) lowercase, or null when the text is not a hex color. */
    public static String normalize(final String text) {
        final double[] c = parse(text);
        return c == null ? null : hex(c);
    }

    /** A hex color ({@code #rgb}, {@code #rrggbb}, {@code #rrggbbaa}, the {@code #} optional) as rgba, or null. */
    public static double[] parse(final String text) {
        if (text == null) {
            return null;
        }
        final Matcher m = HEX.matcher(text.trim());
        if (!m.matches()) {
            return null;
        }
        String h = m.group(1);
        if (h.length() == 3) {
            h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        }
        final double a = h.length() == 8 ? Integer.parseInt(h.substring(6, 8), 16) / 255.0 : 1;
        return new double[] {Integer.parseInt(h.substring(0, 2), 16), Integer.parseInt(h.substring(2, 4), 16),
            Integer.parseInt(h.substring(4, 6), 16), a};
    }

    public static String hex(final double[] c) {
        final String rgb = String.format(Locale.ROOT, "#%02x%02x%02x", channel(c[0]), channel(c[1]), channel(c[2]));
        return c[3] >= 0.999 ? rgb : rgb + String.format(Locale.ROOT, "%02x", channel(c[3] * 255));
    }

    /** The WCAG contrast ratio of {@code fg} drawn over an opaque {@code bg} (a translucent fg is composited). */
    public static double contrast(final String fg, final String bg) {
        final double[] back = parse(bg);
        final double[] front = parse(fg);
        if (back == null || front == null) {
            return 0;
        }
        final double[] over = mix(front, opaque(back), front[3]);
        final double l1 = luminance(over);
        final double l2 = luminance(back);
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    }

    /** {@code a * p + b * (1 - p)}, channel by channel (Sass {@code mix}, CSS {@code color-mix} in srgb). */
    public static double[] mix(final double[] a, final double[] b, final double p) {
        return new double[] {a[0] * p + b[0] * (1 - p), a[1] * p + b[1] * (1 - p), a[2] * p + b[2] * (1 - p), 1};
    }

    /** The color with its HSL lightness moved by {@code points} (Sass lighten / darken), clamped. */
    public static String shiftLightness(final String color, final double points) {
        final double[] c = parse(color);
        if (c == null) {
            return color;
        }
        final double[] hsl = hsl(c);
        final double[] out = fromHsl(hsl[0], hsl[1], Math.max(0, Math.min(1, hsl[2] + points / 100)));
        out[3] = c[3];
        return hex(out);
    }

    /**
     * The whole accent family from one accent: hover and pressed a step and two darker, the soft shade a step
     * lighter (the spacing of the stock palettes), and the more readable of white and Freya's dark text on top.
     */
    public static Map<String, String> accentFamily(final String accent) {
        final String base = normalize(accent);
        final Map<String, String> out = new LinkedHashMap<>();
        if (base == null) {
            return out;
        }
        out.put(ACCENT, base);
        out.put(HOVER, shiftLightness(base, -8));
        out.put(PRESSED, shiftLightness(base, -14));
        out.put(SOFT, shiftLightness(base, 6));
        out.put(ON_ACCENT, onAccent(base));
        return out;
    }

    /** White on the accent unless Freya's dark text reads better on it. */
    public static String onAccent(final String accent) {
        return contrast(WHITE, accent) >= contrast(DARK_ON_ACCENT, accent) ? WHITE : DARK_ON_ACCENT;
    }

    /**
     * A palette's dark accent family from its light one, the stock way: one step up (dark accent = light soft, dark
     * hover = light accent, dark pressed = light hover), a new soft shade above it, and On accent re-chosen for the
     * new accent.
     */
    public static Map<String, String> darkAccentFamily(final Map<String, String> light) {
        final Map<String, String> out = new LinkedHashMap<>();
        final String accent = light.getOrDefault(SOFT, light.get(ACCENT));
        if (accent == null) {
            return out;
        }
        out.put(ACCENT, accent);
        out.put(HOVER, light.getOrDefault(ACCENT, accent));
        out.put(PRESSED, light.getOrDefault(HOVER, accent));
        out.put(SOFT, shiftLightness(accent, 14));
        out.put(ON_ACCENT, onAccent(accent));
        return out;
    }

    /**
     * The tinted page background an org site paints with no picture: the accent mixed into white (light) or into
     * Freya's near-black (dark), at the weights {@code freya/sass/overrides/_theme_variables.scss} uses.
     */
    public static String siteBackground(final String accent, final boolean dark) {
        final double[] a = parse(accent);
        if (a == null) {
            return null;
        }
        return dark ? hex(mix(opaque(a), parse("#14171c"), 0.16)) : hex(mix(opaque(a), parse(WHITE), 0.07));
    }

    private static double[] opaque(final double[] c) {
        return new double[] {c[0], c[1], c[2], 1};
    }

    private static int channel(final double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    private static double luminance(final double[] c) {
        return 0.2126 * linear(c[0]) + 0.7152 * linear(c[1]) + 0.0722 * linear(c[2]);
    }

    private static double linear(final double channel) {
        final double v = channel / 255;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static double[] hsl(final double[] c) {
        final double r = c[0] / 255;
        final double g = c[1] / 255;
        final double b = c[2] / 255;
        final double max = Math.max(r, Math.max(g, b));
        final double min = Math.min(r, Math.min(g, b));
        final double l = (max + min) / 2;
        // Double.compare, not ==, for SpotBugs (FE_FLOATING_POINT_EQUALITY); max and min are copies of the channels,
        // so exact equality is the intended test.
        if (Double.compare(max, min) == 0) {
            return new double[] {0, 0, l};
        }
        final double d = max - min;
        final double s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
        final double h;
        if (Double.compare(max, r) == 0) {
            h = ((g - b) / d + (g < b ? 6 : 0)) / 6;
        } else if (Double.compare(max, g) == 0) {
            h = ((b - r) / d + 2) / 6;
        } else {
            h = ((r - g) / d + 4) / 6;
        }
        return new double[] {h, s, l};
    }

    private static double[] fromHsl(final double h, final double s, final double l) {
        if (s == 0) {
            return new double[] {l * 255, l * 255, l * 255, 1};
        }
        final double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
        final double p = 2 * l - q;
        return new double[] {hue(p, q, h + 1.0 / 3) * 255, hue(p, q, h) * 255, hue(p, q, h - 1.0 / 3) * 255, 1};
    }

    private static double hue(final double p, final double q, final double tIn) {
        final double t = tIn < 0 ? tIn + 1 : tIn > 1 ? tIn - 1 : tIn;
        if (t < 1.0 / 6) {
            return p + (q - p) * 6 * t;
        }
        if (t < 0.5) {
            return q;
        }
        return t < 2.0 / 3 ? p + (q - p) * (2.0 / 3 - t) * 6 : p;
    }
}
