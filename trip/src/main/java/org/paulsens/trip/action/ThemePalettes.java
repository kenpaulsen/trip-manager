package org.paulsens.trip.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.model.CustomPalette;
import org.paulsens.trip.theme.ThemeColorMath;
import org.paulsens.trip.theme.ThemeResources;

/**
 * The palettes an organization can choose, each with the handful of colors that show what it LOOKS like, read
 * from the compiled Freya stylesheets themselves: exposed to pages as {@code #{themePalettes}}.
 *
 * <p>Two pages draw with it. The org Appearance page's palette chooser renders one swatch card per palette (a
 * mini page: the tinted page ({@code --site-bg-default}), a card, text and the accent), and the trip page's live
 * stylesheet editor gets the same list as JSON for its "Preview as" row, which swaps the page's theme and layout
 * links on the client. The colors are
 * parsed out of each palette's own {@code theme.css} rather than listed here because a list would drift: the
 * stylesheets are generated from SCSS in the sibling repo's {@code freya/}, and the cards must show what a
 * visitor will actually get. The theme and layout NAMES come from {@link BrandCommands#themeName} and
 * {@link BrandCommands#layoutCssName}, the rule the server renders with, so the editor's preview cannot name a
 * stylesheet the server would not.
 *
 * <p>The palette list is {@link KnownSettings#THEME_PALETTES} behind the platform default ({@link #PLATFORM}, the
 * blank value the palette setting stores for "none"). If any stylesheet cannot be read, {@link #cards} answers
 * an empty list and the Appearance page falls back to its plain menu: a chooser missing palettes would look
 * like a short list, not like a failure.
 *
 * <p>An organization's OWN palettes ({@link CustomPalettes}) come after the stock ones, through the
 * {@code orgId} overloads. Their cards are built from the palette's stored colors rather than parsed CSS (they all
 * share the one generated {@code freya-custom-*} theme), and their JSON entries carry the {@code --ct-*} values the
 * editor's preview sets on the page. The org id is the one the calling page already resolved and guarded (the
 * Appearance page's org, the trip's own org); nothing here searches across orgs.
 */
@Slf4j
@Named("themePalettes")
@ApplicationScoped
public class ThemePalettes {

    /** The platform default's name in these lists: blank, as the palette setting stores "none". */
    public static final String PLATFORM = "";
    static final String PLATFORM_LABEL = "Platform default";

    /** The custom properties a card is drawn from, each read from the palette's own {@code :root}. */
    static final List<String> SWATCH_VARS = List.of(
            "--primary-color", "--primary-color-text", "--surface-card", "--text-color",
            "--site-bg-default");

    /**
     * What a value read out of a stylesheet may look like before it is written into a {@code style} attribute:
     * a hex color or a functional notation over plain numbers. Anything else (a quote, a semicolon, a nested
     * {@code var()}) drops the palette rather than reaching the attribute.
     */
    private static final Pattern SAFE_VALUE = Pattern.compile("#[0-9A-Fa-f]{3,8}|[a-z]{3,5}\\([0-9., %/]+\\)");
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One palette in one mode, with the theme and layout names that draw it and the colors that show it.
     * {@code param} is what its card sends for it: the name, or {@link BrandCommands#PALETTE_CARD_NONE} for the
     * platform default, whose blank name JSF would drop from an attribute or an {@code f:param}.
     */
    public record Palette(String name, String label, boolean dark, String theme, String layout, String primary,
            String primaryText, String card, String text, String siteBg, String param) {
    }

    /** The org palettes' store (test seam). */
    @Setter
    private CustomPalettes customPalettes = CustomPalettes.getInstance();

    /** Test seam: a theme name ({@code freya-blue-light}) to its compiled stylesheet's text, or null. */
    @Setter
    private Function<String, String> cssSource = ThemePalettes::readThemeCss;

    /** Parsed palettes by theme name. Only successes are kept, so a read that failed is tried again later. */
    private final Map<String, Palette> parsed = new ConcurrentHashMap<>();

    /**
     * Every palette in one mode, the platform default first; empty when any of them cannot be read (the
     * Appearance page then keeps its plain menu).
     */
    public List<Palette> cards(final boolean dark) {
        final List<Palette> out = new ArrayList<>();
        for (final String name : names()) {
            final Optional<Palette> palette = palette(name, dark);
            if (palette.isEmpty()) {
                return List.of();
            }
            out.add(palette.get());
        }
        return out;
    }

    /** The stock cards, then {@code orgId}'s own palettes' cards; empty when the stock cards are. */
    public List<Palette> cards(final String orgId, final boolean dark) {
        final List<Palette> stock = cards(dark);
        if (stock.isEmpty() || !customPalettes.isAvailable()) {
            return stock;
        }
        final List<Palette> out = new ArrayList<>(stock);
        customPalettes.listFor(orgId).forEach(palette -> out.add(customCard(palette, dark)));
        return out;
    }

    /**
     * One org palette's card, from its own colors in the generated theme's terms: the card is {@code shade000}
     * (light) or {@code shade800} (dark) and the text {@code shade700} or {@code shade000}, as the custom theme's
     * {@code :root} maps them; the page around it is tinted from the accent, as the SCSS tints a stock palette's.
     */
    Palette customCard(final CustomPalette palette, final boolean dark) {
        final Map<String, String> in = customPalettes.inputs(palette, dark);
        final String accent = in.get(ThemeColorMath.ACCENT);
        return new Palette(palette.ref(), palette.name(), dark, BrandCommands.themeName(palette.ref(), dark),
                BrandCommands.layoutCssName(palette.ref(), dark), accent, in.get(ThemeColorMath.ON_ACCENT),
                in.get(dark ? "shade800" : "shade000"), in.get(dark ? "shade000" : "shade700"),
                ThemeColorMath.siteBackground(accent, dark), palette.ref());
    }

    /**
     * Whether the swatch cards can be drawn at all, for the Appearance page's fallback: in BOTH modes, since the
     * page draws them in whichever its Dark mode choice says, and a dark theme that cannot be read must bring the
     * menu back rather than leave an empty panel (review finding kenpaulsen/medjugorje#129).
     */
    public boolean isAvailable() {
        return !cards(false).isEmpty() && !cards(true).isEmpty();
    }

    /**
     * The stylesheet editor's palette list as JSON: per palette its name, label and, for each mode, the theme
     * and layout names plus the accent color its dot shows. {@code []} when the palettes cannot be read, which
     * hides the Preview row.
     */
    public String json() {
        return json(null);
    }

    /**
     * {@link #json()} with {@code orgId}'s own palettes after the stock ones; each of those also carries, per mode,
     * {@code vars}: the {@code --ct-*} name to color map the preview sets on the page's root element.
     */
    public String json(final String orgId) {
        final List<Palette> light = cards(false);
        final List<Palette> dark = cards(true);
        if (light.isEmpty() || dark.isEmpty()) {
            return "[]";
        }
        final List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < light.size(); i++) {
            out.add(entry(light.get(i), dark.get(i)));
        }
        if (customPalettes.isAvailable()) {
            customPalettes.listFor(orgId).forEach(palette -> out.add(customEntry(palette)));
        }
        try {
            return JSON.writeValueAsString(out);
        } catch (final JsonProcessingException ex) {
            log.warn("Unable to write the palette list", ex);
            return "[]";
        }
    }

    private static Map<String, Object> entry(final Palette light, final Palette dark) {
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", light.name());
        entry.put("label", light.label());
        entry.put("light", mode(light));
        entry.put("dark", mode(dark));
        return entry;
    }

    private static Map<String, String> mode(final Palette palette) {
        return Map.of("theme", palette.theme(), "layout", palette.layout(), "primary", palette.primary());
    }

    private Map<String, Object> customEntry(final CustomPalette palette) {
        final Map<String, Object> entry = entry(customCard(palette, false), customCard(palette, true));
        entry.put("light", customMode(palette, false));
        entry.put("dark", customMode(palette, true));
        return entry;
    }

    private Map<String, Object> customMode(final CustomPalette palette, final boolean dark) {
        final Map<String, Object> mode = new LinkedHashMap<>(mode(customCard(palette, dark)));
        final Map<String, String> vars = new LinkedHashMap<>();
        customPalettes.inputs(palette, dark).forEach((input, color) -> vars.put("--ct-" + input, color));
        mode.put("vars", vars);
        return mode;
    }

    /** One palette in one mode, parsed once; empty when its stylesheet cannot be read or lacks a color. */
    Optional<Palette> palette(final String name, final boolean dark) {
        final String theme = BrandCommands.themeName(name, dark);
        final Palette known = parsed.get(theme);
        if (known != null) {
            return Optional.of(known);
        }
        final Optional<Palette> loaded = parse(name, dark, theme, cssSource.apply(theme));
        loaded.ifPresent(palette -> parsed.put(theme, palette));
        return loaded;
    }

    /** The palette names in chooser order: the platform default, then the shipped palettes. */
    static List<String> names() {
        final List<String> names = new ArrayList<>();
        names.add(PLATFORM);
        names.addAll(KnownSettings.THEME_PALETTES);
        return names;
    }

    /** A palette's display name: "Platform default", else the palette's name capitalized ("Turquoise"). */
    static String label(final String name) {
        return PLATFORM.equals(name) ? PLATFORM_LABEL
                : name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    static Optional<Palette> parse(final String name, final boolean dark, final String theme, final String css) {
        if (css == null) {
            log.warn("Theme {} cannot be read; the palette cards fall back to the menu", theme);
            return Optional.empty();
        }
        final Map<String, String> values = new LinkedHashMap<>();
        for (final String var : SWATCH_VARS) {
            final String value = declared(css, var);
            if (value == null) {
                log.warn("Theme {} declares no usable {}; the palette cards fall back to the menu", theme, var);
                return Optional.empty();
            }
            values.put(var, value);
        }
        return Optional.of(new Palette(name, label(name), dark, theme, BrandCommands.layoutCssName(name, dark),
                values.get("--primary-color"), values.get("--primary-color-text"), values.get("--surface-card"),
                values.get("--text-color"), values.get("--site-bg-default"),
                PLATFORM.equals(name) ? BrandCommands.PALETTE_CARD_NONE : name));
    }

    /**
     * The value of the FIRST declaration of {@code var} in the stylesheet (the theme's own {@code :root}), or
     * null when it is missing or not a plain color. The name must be followed by its colon, so
     * {@code --primary-color} never matches {@code --primary-color-text} or a {@code var(--primary-color)} use.
     */
    static String declared(final String css, final String var) {
        final Matcher match = Pattern.compile("(?<![\\w-])" + Pattern.quote(var) + "\\s*:\\s*([^;}]+)").matcher(css);
        if (!match.find()) {
            return null;
        }
        final String value = match.group(1).trim();
        return SAFE_VALUE.matcher(value).matches() ? value : null;
    }

    private static String readThemeCss(final String theme) {
        return ThemeResources.themeCss(theme);
    }
}
