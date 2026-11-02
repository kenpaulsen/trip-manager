package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/** A conference page's own look (docs/conference.md, "Look and feel"): null means the organization's. */
public class TripPageOptionsTest {
    private static final ObjectMapper MAPPER = DAO.getInstance().getMapper();

    @Test
    public void equality() {
        EqualsVerifier.forClass(TripPageOptions.class).suppress(Warning.NONFINAL_FIELDS).verify();
    }

    @Test
    public void aTripStoredBeforePageOptionsHasNoneAndRendersAsTheOrgs() throws Exception {
        final Trip legacy = MAPPER.readValue("{\"id\":\"t\",\"title\":\"Old\"}", Trip.class);
        assertNull(legacy.pageOrNull(), "render code reads this and never allocates");
        assertTrue(legacy.pageProblems().isEmpty());
        assertTrue(legacy.getPage().isEmpty(), "the editor's lazy copy is all-inherited");
        assertSame(legacy.getPage(), legacy.getPage(), "lazy, then kept: the dialog binds into it");
        assertTrue(legacy.getPage().fullWidth(true), "no layout of its own: the org's");
        assertFalse(legacy.getPage().fullWidth(false));
        assertFalse(legacy.getPage().isTitleShown(), "the hero carries the title");
        assertTrue(legacy.getPage().isNavShown());
    }

    @Test
    public void theOptionsRoundTripAndBlanksReadAsUnset() throws Exception {
        final TripPageOptions options = new TripPageOptions(" full-width ", "", " ", Boolean.TRUE, Boolean.FALSE,
                "https://cdn.example/page.css ");
        assertEquals(options.getLayout(), KnownSettings.LAYOUT_FULL_WIDTH);
        assertNull(options.getBackgroundUrl());
        assertNull(options.getBackgroundColor());
        assertEquals(options.getStylesheetUrl(), "https://cdn.example/page.css");
        final TripPageOptions back = MAPPER.readValue(MAPPER.writeValueAsString(options), TripPageOptions.class);
        assertEquals(back, options);
        assertTrue(options.fullWidth(false), "its own layout wins over the org's");
        assertTrue(options.isTitleShown());
        assertFalse(options.isNavShown());
        assertFalse(options.isEmpty());
    }

    @Test
    public void theDialogsTriStateRadiosAndSwitchesStoreDefaultsAsNull() {
        final TripPageOptions options = new TripPageOptions();
        assertEquals(options.getLayoutMode(), TripPageOptions.MODE_ORG);
        options.setLayoutMode(KnownSettings.LAYOUT_CLASSIC);
        assertEquals(options.getLayout(), KnownSettings.LAYOUT_CLASSIC);
        assertFalse(options.fullWidth(true), "classic of its own while the org is full width");
        options.setLayoutMode(TripPageOptions.MODE_ORG);
        assertNull(options.getLayout());

        assertEquals(options.getBackgroundMode(), TripPageOptions.MODE_ORG);
        options.setBackgroundMode(TripPageOptions.BG_COLOR);
        options.setBackgroundColor("#336699");
        assertEquals(options.getBackgroundMode(), TripPageOptions.BG_COLOR);
        options.setBackgroundMode(TripPageOptions.BG_IMAGE);
        assertNull(options.getBackgroundColor(), "choosing a mode clears what it does not use");
        options.setBackgroundUrl("https://cdn.example/bg.jpg");
        assertEquals(options.getBackgroundMode(), TripPageOptions.BG_IMAGE);
        options.setBackgroundMode(TripPageOptions.MODE_ORG);
        assertNull(options.getBackgroundUrl());
        assertEquals(options.getBackgroundMode(), TripPageOptions.MODE_ORG);

        options.setTitleOn(true);
        assertEquals(options.getShowTitle(), Boolean.TRUE);
        options.setTitleOn(false);
        assertNull(options.getShowTitle(), "the default is stored as null");
        options.setNavOn(false);
        assertEquals(options.getShowNav(), Boolean.FALSE);
        options.setNavOn(true);
        assertNull(options.getShowNav());
        assertTrue(options.isEmpty());
    }

    @Test
    public void theFieldRulesAreTheOrgSettingsOwn() {
        assertTrue(new TripPageOptions().problems().isEmpty());
        final List<String> problems = new TripPageOptions("sideways", "javascript:alert(1)", "red", null, null,
                "ftp://x/y.css").problems();
        assertEquals(problems.size(), 4, String.join(" | ", problems));
        assertTrue(problems.get(0).contains("classic or full-width"), problems.get(0));
        assertTrue(problems.get(1).contains("background image"));
        assertTrue(problems.get(2).contains("hex color"));
        assertTrue(problems.get(3).contains("stylesheet"));
        // A site-relative path is what the media library's URLs are wherever no media host is configured.
        assertTrue(new TripPageOptions(null, "/org/x/bg.jpg", "#FFF", null, null, "/org/x/page.css").problems()
                .isEmpty());
        assertFalse(new TripPageOptions(null, "//evil.example/bg.jpg", null, null, null, null).problems().isEmpty(),
                "a protocol-relative URL is not a site-relative path");
    }
}
