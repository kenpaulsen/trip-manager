package org.paulsens.trip.theme;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

import java.util.Map;
import org.testng.annotations.Test;

/** A theme's custom properties followed to plain colors, the way a browser would, for the editors' fallbacks. */
public class ThemeVarsTest {

    private static final String CSS = ":root{--surface-card:#ffffff;--red-700:rgb(211.65, 47.15, 47.15);"
            + "--status-danger:var(--red-700);--status-danger-bg:var(--red-50, #FFEBEE);"
            + "--primary-lighter-color:rgba(105, 183, 255, 0.1);--text-color:var(--ct-shade700);"
            + "--soft:color-mix(in srgb, var(--ct-primary-color) 20.00%, transparent);--loop-a:var(--loop-b);"
            + "--loop-b:var(--loop-a);--font-family:Roboto, sans-serif}"
            + ":root{--surface-card:#fafafa}";

    @Test
    public void declarationsKeepTheLastOfEachName() {
        final Map<String, String> vars = ThemeVars.declarations(CSS);
        assertEquals(vars.get("--surface-card"), "#fafafa", "a later block wins, as in the browser");
        assertEquals(vars.get("--font-family"), "Roboto, sans-serif");
        assertEquals(ThemeVars.declarations(null), Map.of());
    }

    @Test
    public void resolvesEveryShapeTheThemesWrite() {
        final Map<String, String> vars = new java.util.HashMap<>(ThemeVars.declarations(CSS));
        assertEquals(ThemeVars.resolve(vars, "--status-danger"), "#d42f2f", "through a var() to decimal rgb()");
        assertEquals(ThemeVars.resolve(vars, "--status-danger-bg"), "#ffebee", "an undefined var() takes its fallback");
        assertEquals(ThemeVars.resolve(vars, "--primary-lighter-color"), "#69b7ff1a", "rgba keeps its alpha");
        assertNull(ThemeVars.resolve(vars, "--text-color"), "a custom input the page has not set");
        vars.put("--ct-shade700", "#222222");
        vars.put("--ct-primary-color", "#8a2be2");
        assertEquals(ThemeVars.resolve(vars, "--text-color"), "#222222");
        assertEquals(ThemeVars.resolve(vars, "--soft"), "#8a2be233", "the custom theme's translucent mix");
        assertNull(ThemeVars.resolve(vars, "--loop-a"), "a cycle ends, it does not spin");
        assertNull(ThemeVars.resolve(vars, "--font-family"), "not a color");
        assertNull(ThemeVars.resolve(vars, "--missing"));
    }

    @Test
    public void overCompositesOntoTheGround() {
        assertEquals(ThemeVars.over("#00000080", "#ffffff"), "#7f7f7f");
        assertEquals(ThemeVars.over("#123456", "#ffffff"), "#123456");
        assertNull(ThemeVars.over("red", "#ffffff"));
        assertNull(ThemeVars.over("#123456", null));
    }
}
