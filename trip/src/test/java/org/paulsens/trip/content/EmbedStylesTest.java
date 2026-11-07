package org.paulsens.trip.content;

import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class EmbedStylesTest {

    @Test
    public void onlyTheRootBlockOfAThemeIsCarried() {
        final String theme = "﻿:root{--surface-a:#ffffff;--primary-color:#3366FF}.ui-widget{font-size:1rem}"
                + ".other{color:red}";
        assertEquals(EmbedStyles.rootBlockOf(theme), ":root{--surface-a:#ffffff;--primary-color:#3366FF}");
        assertEquals(EmbedStyles.rootBlockOf(".ui-widget{font-size:1rem}"), "", "a theme without variables");
        assertEquals(EmbedStyles.rootBlockOf(":root{unterminated"), "");
        assertEquals(EmbedStyles.rootBlockOf(null), "");
    }

    @Test
    public void theOverridesHideEveryRegistrationControl() {
        final String css = EmbedStyles.overrides();
        assertTrue(css.contains(".tripEmbed .cta-row"), "the hero and call-to-action buttons");
        assertTrue(css.contains(".tripEmbed .regStatus-button"), "the registration block's button");
        assertTrue(css.contains(".tripEmbed .confNavCta"), "the nav strip's button, should a host ever build it");
        assertTrue(css.contains("display: none"));
        assertTrue(css.contains("--layout-full-bg"), "the app's backdrop shows through the full-width ground");
    }
}
