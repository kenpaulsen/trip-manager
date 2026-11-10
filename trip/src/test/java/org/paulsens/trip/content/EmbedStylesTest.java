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
        assertTrue(css.contains("html.tripEmbed { background: var(--layout-full-bg, var(--surface-ground"),
                "an opaque ground: the apps' web view is transparent over a dark photo");
        assertTrue(css.contains("body.tripEmbed { font-family: var(--font-family"),
                "the theme's font, not the browser's default serif");
        assertTrue(css.contains("color: var(--text-color"), "the theme's text color");
        assertTrue(css.contains(".tripEmbed .tripEmbedText { margin: 8px; padding: 16px;"),
                "an old description page keeps margins");
        assertTrue(css.contains("background: var(--surface-card, #ffffff); border-radius: 12px; }"),
                "and sits on a card, as on the site");
        assertTrue(css.contains(".tripEmbed .tripEmbedText img, .tripEmbed .tripEmbedText iframe"),
                "pictures and videos never overflow the column");
    }
}
