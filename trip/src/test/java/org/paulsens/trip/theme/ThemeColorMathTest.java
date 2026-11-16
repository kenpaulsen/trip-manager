package org.paulsens.trip.theme;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.testng.annotations.Test;

/**
 * The color arithmetic custom palettes rest on, checked against values the stock Freya palettes compile to, so a
 * derivation that drifts from how Freya relates its colors fails here rather than on someone's site.
 */
public class ThemeColorMathTest {

    @Test
    public void hexColorsParseAndNormalize() {
        assertEquals(ThemeColorMath.normalize("#ABC"), "#aabbcc");
        assertEquals(ThemeColorMath.normalize(" 2196F3 "), "#2196f3");
        assertEquals(ThemeColorMath.normalize("#ffffff99"), "#ffffff99");
        assertEquals(ThemeColorMath.normalize("#000000ff"), "#000000", "opaque drops its alpha");
        assertNull(ThemeColorMath.normalize("red"));
        assertNull(ThemeColorMath.normalize("#12345"));
        assertNull(ThemeColorMath.normalize(null));
        assertNull(ThemeColorMath.parse("rgb(0,0,0)"));
    }

    @Test
    public void contrastIsWcag() {
        assertEquals(ThemeColorMath.contrast("#000000", "#ffffff"), 21.0, 0.01);
        assertEquals(ThemeColorMath.contrast("#ffffff", "#ffffff"), 1.0, 0.001);
        assertEquals(ThemeColorMath.contrast("#767676", "#ffffff"), 4.54, 0.01);
        assertEquals(ThemeColorMath.contrast("#00000080", "#ffffff"), ThemeColorMath.contrast("#7f7f7f", "#ffffff"),
                0.01, "a translucent color is composited over the background first");
        assertEquals(ThemeColorMath.contrast("nope", "#ffffff"), 0.0);
        assertEquals(ThemeColorMath.contrast("#000000", null), 0.0);
    }

    @Test
    public void lightnessShiftsLikeSass() {
        // Gray at L 50.2% moved 10 points = L 60.2% = 153.5 of 255 per channel; Sass lighten() rounds the same way.
        assertEquals(ThemeColorMath.shiftLightness("#808080", 10), "#9a9a9a");
        assertEquals(ThemeColorMath.shiftLightness("#2196f3", -8), "#0c82df");
        assertEquals(ThemeColorMath.shiftLightness("#808080", 100), "#ffffff", "clamped");
        assertEquals(ThemeColorMath.shiftLightness("#808080", -100), "#000000");
        assertEquals(ThemeColorMath.shiftLightness("#80808080", 0), "#80808080", "alpha kept");
        assertEquals(ThemeColorMath.shiftLightness("nope", 5), "nope");
        assertEquals(ThemeColorMath.shiftLightness("#00ff00", 0), "#00ff00");
        assertEquals(ThemeColorMath.shiftLightness("#0000ff", 0), "#0000ff");
    }

    @Test
    public void theAccentFamilyFollowsTheAccent() {
        final Map<String, String> family = ThemeColorMath.accentFamily("#2196F3");
        assertEquals(family.get(ThemeColorMath.ACCENT), "#2196f3");
        assertEquals(family.get(ThemeColorMath.HOVER), "#0c82df");
        assertEquals(family.get(ThemeColorMath.ON_ACCENT), "#ffffff");
        assertTrue(ThemeColorMath.contrast(family.get(ThemeColorMath.PRESSED), "#ffffff")
                > ThemeColorMath.contrast(family.get(ThemeColorMath.HOVER), "#ffffff"), "pressed is darker still");
        assertEquals(ThemeColorMath.accentFamily("#ffd54f").get(ThemeColorMath.ON_ACCENT), "#3e4754",
                "a pale accent gets the dark text, as Freya's yellow does");
        assertEquals(ThemeColorMath.accentFamily("nope"), Map.of());
    }

    @Test
    public void theDarkFamilyIsOneStepUp() {
        final Map<String, String> light = ThemeColorMath.accentFamily("#2196f3");
        final Map<String, String> dark = ThemeColorMath.darkAccentFamily(light);
        assertEquals(dark.get(ThemeColorMath.ACCENT), light.get(ThemeColorMath.SOFT));
        assertEquals(dark.get(ThemeColorMath.HOVER), light.get(ThemeColorMath.ACCENT));
        assertEquals(dark.get(ThemeColorMath.PRESSED), light.get(ThemeColorMath.HOVER));
        assertEquals(ThemeColorMath.darkAccentFamily(Map.of(ThemeColorMath.ACCENT, "#2196f3"))
                .get(ThemeColorMath.ACCENT), "#2196f3", "no soft shade: the accent itself");
        assertEquals(ThemeColorMath.darkAccentFamily(Map.of()), Map.of());
    }

    /** The one-step-up rule is how the stock palettes ARE built: every one but the platform default's own. */
    @Test
    public void theDarkRuleReproducesTheStockPalettes() throws Exception {
        final JsonNode palettes = new ObjectMapper().readTree(ThemeResources.read(
                "freya-layout/custom-theme-inputs.json")).path("palettes");
        int checked = 0;
        for (final Map.Entry<String, JsonNode> palette : palettes.properties()) {
            if ("medj".equals(palette.getKey())) {
                continue;
            }
            final Map<String, String> light = colors(palette.getValue().path("light"));
            final Map<String, String> dark = colors(palette.getValue().path("dark"));
            final Map<String, String> derived = ThemeColorMath.darkAccentFamily(light);
            for (final String key : List.of(ThemeColorMath.ACCENT, ThemeColorMath.HOVER, ThemeColorMath.PRESSED)) {
                assertEquals(derived.get(key), dark.get(key), palette.getKey() + " " + key);
            }
            checked++;
        }
        assertEquals(checked, 8);
    }

    private static Map<String, String> colors(final JsonNode mode) {
        final Map<String, String> out = new TreeMap<>();
        mode.properties().forEach(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }

    @Test
    public void theSiteBackgroundMatchesTheScss() {
        assertEquals(ThemeColorMath.siteBackground("#ffffff", false), "#ffffff");
        assertEquals(ThemeColorMath.siteBackground("#000000", false), "#ededed", "7% into white");
        assertEquals(ThemeColorMath.siteBackground("#ffffff", true), "#3a3c40", "16% into #14171c");
        assertNull(ThemeColorMath.siteBackground("nope", true));
    }
}
