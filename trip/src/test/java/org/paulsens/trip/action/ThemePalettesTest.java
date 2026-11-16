package org.paulsens.trip.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.paulsens.trip.config.KnownSettings;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link ThemePalettes}: the palette cards and the editor's preview list, parsed out of the compiled themes. What is
 * pinned is the naming (the same rule {@link BrandCommands} renders with), that only the theme's own declarations
 * count, that a value unsafe for an attribute drops the palette, and that one unreadable palette empties the whole
 * list so the Appearance page falls back to its menu.
 */
public class ThemePalettesTest {

    /** A compiled theme in miniature: one {@code :root} with every swatch property, plus a later use of each. */
    private static String css(final String primary, final String card) {
        return ":root{--surface-card:" + card + ";--surface-ground:#F2F4F6;--text-color:#69707A;"
                + "--primary-color:" + primary + ";--primary-color-text:#FFFFFF;}"
                + ":root{--site-bg-default:rgb(242.4, 247.5, 255);}"
                + ".ui-button{background:var(--primary-color);color:var(--primary-color-text)}";
    }

    private static ThemePalettes all(final String primary) {
        final ThemePalettes palettes = new ThemePalettes();
        palettes.setCssSource(theme -> css(primary, theme.endsWith("dark") || theme.endsWith("-d")
                ? "#293241" : "#ffffff"));
        return palettes;
    }

    @Test
    public void everyPaletteIsACardWithItsOwnNamesAndColors() {
        final List<ThemePalettes.Palette> light = all("#5297FF").cards(false);
        Assert.assertEquals(light.size(), KnownSettings.THEME_PALETTES.size() + 1, "the platform default leads");
        final ThemePalettes.Palette platform = light.get(0);
        Assert.assertEquals(platform.name(), ThemePalettes.PLATFORM);
        Assert.assertEquals(platform.param(), BrandCommands.PALETTE_CARD_NONE, "the card's one spelling of none");
        Assert.assertEquals(platform.label(), "Platform default");
        Assert.assertEquals(platform.theme(), "freya-medj-l");
        Assert.assertEquals(platform.layout(), "layout-light");

        final ThemePalettes.Palette turquoise = light.stream().filter(p -> "turquoise".equals(p.name())).findFirst()
                .orElseThrow();
        Assert.assertEquals(turquoise.label(), "Turquoise");
        Assert.assertEquals(turquoise.theme(), "freya-turquoise-light");
        Assert.assertEquals(turquoise.layout(), "layout-turquoise-light");
        Assert.assertEquals(turquoise.primary(), "#5297FF");
        Assert.assertEquals(turquoise.primaryText(), "#FFFFFF");
        Assert.assertEquals(turquoise.card(), "#ffffff");
        Assert.assertEquals(turquoise.param(), "turquoise", "a card sends its own name");
        Assert.assertEquals(turquoise.text(), "#69707A");
        Assert.assertEquals(turquoise.siteBg(), "rgb(242.4, 247.5, 255)", "a later :root block is read too");

        final ThemePalettes.Palette darkPlatform = all("#5297FF").cards(true).get(0);
        Assert.assertTrue(darkPlatform.dark());
        Assert.assertEquals(darkPlatform.theme(), "freya-medj-d");
        Assert.assertEquals(darkPlatform.layout(), "layout-dark");
        Assert.assertEquals(darkPlatform.card(), "#293241");
    }

    @Test
    public void theNamingIsTheServersOwn() {
        Assert.assertEquals(BrandCommands.themeName(null, false), "freya-medj-l");
        Assert.assertEquals(BrandCommands.themeName("", true), "freya-medj-d");
        Assert.assertEquals(BrandCommands.themeName("red", true), "freya-red-dark");
        Assert.assertEquals(BrandCommands.layoutCssName(null, true), "layout-dark");
        Assert.assertEquals(BrandCommands.layoutCssName("red", false), "layout-red-light");
    }

    @Test
    public void oneUnreadablePaletteEmptiesTheList() {
        final ThemePalettes palettes = new ThemePalettes();
        palettes.setCssSource(theme -> theme.contains("purple") ? null : css("#5297FF", "#ffffff"));
        Assert.assertEquals(palettes.cards(false), List.of(), "a short chooser would look like fewer palettes");
        Assert.assertFalse(palettes.isAvailable());
        Assert.assertEquals(palettes.json(), "[]", "the editor hides its Preview row");
    }

    @Test
    public void anUnreadableDarkThemeAloneBringsTheMenuBack() {
        final ThemePalettes palettes = new ThemePalettes();
        palettes.setCssSource(theme -> theme.equals("freya-purple-dark") ? null : css("#5297FF", "#ffffff"));
        Assert.assertFalse(palettes.cards(false).isEmpty(), "light reads fine");
        Assert.assertEquals(palettes.cards(true), List.of());
        Assert.assertFalse(palettes.isAvailable(), "the page may be in dark mode: no empty panel (#129)");
    }

    @Test
    public void aValueUnsafeForAnAttributeDropsThePalette() {
        Assert.assertEquals(ThemePalettes.parse("red", false, "freya-red-light",
                css("red\" onmouseover=\"x", "#ffffff")), Optional.empty());
        Assert.assertEquals(ThemePalettes.parse("red", false, "freya-red-light",
                css("var(--blue-500)", "#ffffff")), Optional.empty(), "a nested var() is not a color to paint");
        Assert.assertEquals(ThemePalettes.parse("red", false, "freya-red-light", null), Optional.empty());
    }

    @Test
    public void onlyTheDeclarationItselfCounts() {
        final String css = ".x{color:var(--primary-color)}:root{--primary-color-text:#000;--primary-color:#123456}";
        Assert.assertEquals(ThemePalettes.declared(css, "--primary-color"), "#123456",
                "neither a var() use nor a longer name that starts the same way is the declaration");
        Assert.assertNull(ThemePalettes.declared(css, "--surface-card"));
        Assert.assertNull(ThemePalettes.declared(":root{--x-primary-color:#fff}", "--primary-color"));
    }

    @Test
    public void aParsedPaletteIsKeptAndAFailedReadIsRetried() {
        final int[] reads = {0};
        final ThemePalettes palettes = new ThemePalettes();
        palettes.setCssSource(theme -> ++reads[0] == 1 ? null : css("#5297FF", "#ffffff"));
        Assert.assertTrue(palettes.palette("blue", false).isEmpty());
        Assert.assertTrue(palettes.palette("blue", false).isPresent(), "a miss is not remembered");
        Assert.assertTrue(palettes.palette("blue", false).isPresent());
        Assert.assertEquals(reads[0], 2, "a success is read once");
    }

    @Test
    public void theEditorsListCarriesBothModesPerPalette() throws Exception {
        final JsonNode list = new ObjectMapper().readTree(all("#5297FF").json());
        Assert.assertEquals(list.size(), KnownSettings.THEME_PALETTES.size() + 1);
        final JsonNode red = list.get(KnownSettings.THEME_PALETTES.indexOf("red") + 1);
        Assert.assertEquals(red.get("name").asText(), "red");
        Assert.assertEquals(red.get("label").asText(), "Red");
        Assert.assertEquals(new ObjectMapper().convertValue(red.get("light"), Map.class),
                Map.of("theme", "freya-red-light", "layout", "layout-red-light", "primary", "#5297FF"));
        Assert.assertEquals(red.get("dark").get("theme").asText(), "freya-red-dark");
        Assert.assertEquals(list.get(0).get("dark").get("layout").asText(), "layout-dark");
    }

    @Test
    public void withNoFacesContextTheClassPathIsAsked() {
        // The test class path holds a stand-in for ONE compiled theme (META-INF/resources, where the theme jar
        // keeps them): that palette is read from it, and the list as a whole is empty because the rest are absent.
        final ThemePalettes palettes = new ThemePalettes();
        Assert.assertEquals(palettes.palette("blue", false).map(ThemePalettes.Palette::primary),
                Optional.of("#5297FF"));
        Assert.assertTrue(palettes.palette("red", false).isEmpty());
        Assert.assertEquals(palettes.cards(false), List.of());
    }
}
