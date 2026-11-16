package org.paulsens.trip.action;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.model.BrandColor;
import org.paulsens.trip.model.CustomPalette;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.site.SiteContext;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * An org site drawn in one of the org's OWN palettes: the generated custom theme and layout, the palette's colors
 * on the root element (also under a conference page's own background), the platform default once the palette is
 * gone, the REST look, and the palette cards and editor JSON that offer it.
 */
public class BrandCommandsCustomPaletteTest {

    private static final String SETTING = "site.theme.palette";
    private static final CustomPalette OURS = new CustomPalette("p1", "Ours", "blue",
            Map.of("primary-color", "#8a2be2", "primary-text-color", "#ffffff", "shade000", "#ffffff",
                    "shade700", "#222222", "body-bg-color", "#f0f0f0"),
            Map.of("shade800", "#101010"));

    /** A stand-in for every stock palette's compiled theme: just the colors the cards read. */
    private static final String STOCK_CSS = ":root{--primary-color:#5297FF;--primary-color-text:#ffffff;"
            + "--surface-card:#ffffff;--surface-ground:#f2f4f6;--text-color:#495057;--site-bg-default:#f3f8ff}";

    private Organization org;
    private SiteContext site;
    private BrandCommands brand;

    @BeforeMethod
    public void freshOrg() {
        org = Organization.builder().id(Organization.Id.newInstance()).name("Painted").build();
        org.setCustomPalettes(List.of(OURS));
        org.getSettingsOverrides().put(SETTING, OURS.ref());
        site = SiteContext.org(org.getId(), "painted", "painted.unitetrip.com");
        brand = new BrandCommands();
        brand.setOrgSource(id -> id.equals(org.getId()) ? Optional.of(org) : Optional.empty());
        brand.setAppearanceViewSource(() -> null);
        brand.setDarkSource(() -> null);
    }

    @Test
    public void theSiteDrawsTheCustomThemeWithThePalettesColors() {
        onSite(site, () -> {
            assertEquals(brand.getTheme(), "freya-custom-light");
            assertEquals(brand.getLayoutCss(), "layout-custom-light");
            final String style = brand.getRootStyle();
            assertTrue(style.startsWith("--site-bg:none;--site-bg-color:"), style);
            assertTrue(style.contains(";--ct-primary-color:#8a2be2;"), style);
            assertTrue(style.contains("--ct-shade700:#222222;"), style);
        });
        brand.setDarkSource(() -> true);
        onSite(site, () -> {
            assertEquals(brand.getTheme(), "freya-custom-dark");
            assertEquals(brand.getLayoutCss(), "layout-custom-dark");
            assertTrue(brand.getRootStyle().contains("--ct-shade800:#101010;"), "the dark override");
            assertTrue(brand.getRootStyle().contains("--ct-primary-dark-color:#8a2be2;"), "the derived dark accent");
        });
    }

    @Test
    public void aConferencePagesOwnBackgroundKeepsThePalette() {
        onSite(site, () -> {
            final String style = brand.rootStyleWith("--site-bg:url(x.png)");
            assertTrue(style.startsWith("--site-bg:url(x.png);--ct-"), style);
            assertEquals(brand.rootStyleWith(""), brand.getRootStyle());
            assertEquals(brand.rootStyleWith(null), brand.getRootStyle());
        });
        org.getSettingsOverrides().put(SETTING, "blue");
        onSite(site, () -> assertEquals(brand.rootStyleWith("--site-bg:url(x.png)"), "--site-bg:url(x.png)",
                "a stock palette adds nothing"));
        onSite(SiteContext.shared("www.visitqueenofpeace.com"), () -> {
            assertEquals(brand.rootStyleWith("--site-bg:url(x.png)"), "--site-bg:url(x.png)");
            assertNull(brand.rootStyleWith(""), "the shared site keeps no root style at all");
        });
    }

    @Test
    public void theOrgsBrandColorsRideAlongInEveryPaletteAndMode() {
        org.setBrandColors(List.of(new BrandColor("gold1", "Parish gold", "#a07a2c", "#e0b85c"),
                new BrandColor("navy1", "Navy", "#123456", null)));
        onSite(site, () -> {
            final String style = brand.getRootStyle();
            assertTrue(style.contains("--brand-gold1:#a07a2c;--brand-navy1:#123456;"), style);
            assertTrue(brand.getOrgColorVars().startsWith("--ct-"), "the palette's own colors come first");
            assertTrue(brand.getOrgColorVars().endsWith("--brand-navy1:#123456;"), brand.getOrgColorVars());
            assertFalse(brand.getPaletteVars().contains("--brand-"), "getPaletteVars is the palette alone");
        });
        brand.setDarkSource(() -> true);
        onSite(site, () -> assertTrue(brand.getRootStyle().contains("--brand-gold1:#e0b85c;--brand-navy1:#123456;"),
                "dark mode: the dark value, or the light one when the org set none"));
        org.getSettingsOverrides().put(SETTING, "blue");
        onSite(site, () -> assertEquals(brand.rootStyleWith("--site-bg:url(x.png)"),
                "--site-bg:url(x.png);--brand-gold1:#e0b85c;--brand-navy1:#123456;", "a stock palette keeps them"));
        onSite(SiteContext.shared("www.visitqueenofpeace.com"), () -> assertNull(brand.rootStyleWith(""),
                "the shared site is not the org's: its content draws the fallback each color carries"));
    }

    @Test
    public void thePaletteEditorIsDrawnInThePaletteItEdits() {
        brand.setPaletteEditorSource(() -> OURS);
        onSite(SiteContext.shared("www.visitqueenofpeace.com"), () -> {
            assertEquals(brand.getTheme(), "freya-custom-light", "wherever the editor is opened");
            assertEquals(brand.getLayoutCss(), "layout-custom-light");
            assertTrue(brand.getRootStyle().startsWith("--ct-"), brand.getRootStyle());
            assertTrue(brand.getRootStyle().contains("--ct-primary-color:#8a2be2;"));
        });
        onSite(SiteContext.shared("www.visitqueenofpeace.com"), () -> {
            brand.setPaletteEditorSource(() -> null);
            assertEquals(brand.getTheme(), BrandCommands.DEFAULT_THEME_LIGHT, "any other view: the usual rule");
        });
    }

    @Test
    public void theEditorRungTrustsOnlyTheGuardedOrg() {
        final jakarta.faces.context.FacesContext ctx = Mockito.mock(jakarta.faces.context.FacesContext.class,
                Mockito.RETURNS_DEEP_STUBS);
        final jakarta.faces.component.UIViewRoot view = Mockito.mock(jakarta.faces.component.UIViewRoot.class);
        final Map<String, String> params = new java.util.HashMap<>(Map.of("orgId", org.getId().getValue(),
                "paletteId", OURS.id()));
        final Map<String, Object> viewScope = new java.util.HashMap<>(Map.of("theOrgId", org.getId().getValue()));
        Mockito.when(ctx.getViewRoot()).thenReturn(view);
        Mockito.when(view.getViewId()).thenReturn("/admin/orgPalette.xhtml");
        Mockito.when(view.getViewMap(false)).thenReturn(viewScope);
        Mockito.when(ctx.getExternalContext().getRequestParameterMap()).thenReturn(params);
        final BrandCommands real = new BrandCommands();
        real.setOrgSource(id -> id.equals(org.getId()) ? Optional.of(org) : Optional.empty());
        real.setAppearanceViewSource(() -> null);
        real.setDarkSource(() -> null);
        try (MockedStatic<jakarta.faces.context.FacesContext> faces =
                Mockito.mockStatic(jakarta.faces.context.FacesContext.class)) {
            faces.when(jakarta.faces.context.FacesContext::getCurrentInstance).thenReturn(ctx);
            onSite(SiteContext.shared("www.visitqueenofpeace.com"), () -> {
                assertEquals(real.getTheme(), "freya-custom-light", "the editor, for the guarded org");
                params.put("paletteId", "gone");
                assertEquals(real.getTheme(), BrandCommands.DEFAULT_THEME_LIGHT, "a palette the org does not hold");
                params.remove("paletteId");
                params.put("from", "purple");
                assertEquals(real.getTheme(), "freya-custom-light", "create mode draws the draft");
                assertTrue(real.getRootStyle().contains("--ct-primary-color:#464df2;"), "purple's own accent");
                params.put("from", "custom:gone");
                assertEquals(real.getTheme(), BrandCommands.DEFAULT_THEME_LIGHT, "a draft of nothing draws nothing");
                params.remove("from");
                params.put("paletteId", OURS.id());
                viewScope.put("theOrgId", "another-org");
                assertEquals(real.getTheme(), BrandCommands.DEFAULT_THEME_LIGHT,
                        "an orgId the page's guard never checked draws nothing of that org's");
                Mockito.when(view.getViewMap(false)).thenReturn(null);
                assertEquals(real.getTheme(), BrandCommands.DEFAULT_THEME_LIGHT, "no view scope yet");
                Mockito.when(view.getViewId()).thenReturn("/admin/orgConfig.xhtml");
                assertEquals(real.getTheme(), BrandCommands.DEFAULT_THEME_LIGHT, "another view");
                Mockito.when(ctx.getViewRoot()).thenReturn(null);
                assertEquals(real.getTheme(), BrandCommands.DEFAULT_THEME_LIGHT, "no view");
            });
        }
    }

    @Test
    public void theEmbedGetsThePalettesColorsToo() {
        onSite(site, () -> assertTrue(brand.getPaletteVars().startsWith("--ct-"), brand.getPaletteVars()));
        org.getSettingsOverrides().put(SETTING, "blue");
        onSite(site, () -> assertEquals(brand.getPaletteVars(), "", "a stock palette needs none"));
        onSite(SiteContext.shared("www.visitqueenofpeace.com"), () -> assertEquals(brand.getPaletteVars(), ""));
    }

    @Test
    public void aLibraryPictureIsStoredAbsolute() {
        assertEquals(BrandCommands.absolute("https://cdn.example/x.jpg"), "https://cdn.example/x.jpg");
        assertEquals(BrandCommands.absolute("//cdn.example/x.jpg"), "//cdn.example/x.jpg");
        final jakarta.faces.context.FacesContext ctx = Mockito.mock(jakarta.faces.context.FacesContext.class,
                Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(ctx.getExternalContext().getRequestScheme()).thenReturn("http");
        Mockito.when(ctx.getExternalContext().getRequestServerName()).thenReturn("localhost");
        Mockito.when(ctx.getExternalContext().getRequestServerPort()).thenReturn(8080);
        Mockito.when(ctx.getExternalContext().getRequestContextPath()).thenReturn("");
        try (MockedStatic<jakarta.faces.context.FacesContext> faces =
                Mockito.mockStatic(jakarta.faces.context.FacesContext.class)) {
            faces.when(jakarta.faces.context.FacesContext::getCurrentInstance).thenReturn(ctx);
            final Map<String, String> values = new java.util.HashMap<>();
            brand.pickBackground(values, " /resources/images/rainbow-bg.jpg ");
            assertEquals(values.get("site.background.url"), "http://localhost:8080/resources/images/rainbow-bg.jpg",
                    "the save's http(s) rule accepts what the preview showed (#130)");
        }
    }

    @Test
    public void deletingThePreviewedPaletteDropsItFromTheUnsavedPreview() {
        final HttpSession session = OrgAppearancePreviewTest.fakeSession();
        brand.setSessionSource(() -> session);
        final String orgId = org.getId().getValue();
        brand.preview(orgId, new java.util.HashMap<>(Map.of(SETTING, OURS.ref(), "site.logo.url",
                "https://x.example/logo.png")), null);
        brand.forgetDeletedPalette(orgId, "custom:other");
        assertEquals(brand.previewFor(orgId).get(SETTING), OURS.ref(), "another palette's delete leaves it");
        brand.forgetDeletedPalette(orgId, OURS.ref());
        assertEquals(brand.previewFor(orgId).get(SETTING), "", "blank: the platform default");
        assertEquals(brand.previewFor(orgId).get("site.logo.url"), "https://x.example/logo.png",
                "the admin's other unsaved changes stay");
        brand.forgetDeletedPalette("another-org", OURS.ref());
        brand.forgetDeletedPalette(orgId, null);
    }

    @Test
    public void thePickedLibraryTileIsComparedAsStored() {
        final Map<String, String> values = new java.util.HashMap<>(Map.of("site.background.url",
                "https://cdn.example/a.jpg"));
        assertTrue(brand.isPickedBackground(values, " https://cdn.example/a.jpg "));
        assertFalse(brand.isPickedBackground(values, "https://cdn.example/b.jpg"));
        assertFalse(brand.isPickedBackground(values, ""));
        assertFalse(brand.isPickedBackground(null, "https://cdn.example/a.jpg"));
    }

    @Test
    public void oneRuleResolvesThePalette() {
        assertEquals(BrandCommands.resolvedPalette(org, "blue"), "blue");
        assertEquals(BrandCommands.resolvedPalette(org, OURS.ref()), OURS.ref());
        assertNull(BrandCommands.resolvedPalette(org, "custom:gone"));
        assertNull(BrandCommands.resolvedPalette(null, OURS.ref()));
        assertNull(BrandCommands.resolvedPalette(org, "no-such"));
        assertNull(BrandCommands.resolvedPalette(org, null));
    }

    @Test
    public void theEditorIsAlwaysDrawnLight() {
        brand.setDarkSource(() -> true);
        brand.setPaletteEditorSource(() -> OURS);
        assertFalse(brand.isDark(), "the wrapper's classes follow the light sheets the editor loads");
        brand.setPaletteEditorSource(() -> null);
        assertTrue(brand.isDark());
    }

    @Test
    public void aDeletedPaletteFallsBackToThePlatformDefault() {
        org.setCustomPalettes(null);
        onSite(site, () -> {
            assertEquals(brand.getTheme(), BrandCommands.DEFAULT_THEME_LIGHT);
            assertEquals(brand.getLayoutCss(), "layout-light");
            assertFalse(brand.getRootStyle().contains("--ct-"));
        });
        final BrandCommands.OrgLook look = brand.lookOf(org);
        assertNull(look.palette(), "the REST look names no palette either");
        assertNull(look.customPalette());
        assertNull(brand.customColorsOf(look, false));
    }

    @Test
    public void anotherOrgsPaletteIsNeverUsed() {
        final Organization other = Organization.builder().id(Organization.Id.newInstance()).name("Other").build();
        other.getSettingsOverrides().put(SETTING, OURS.ref());
        assertNull(brand.lookOf(other).palette(), "the id names a palette only on the row that holds it");
    }

    @Test
    public void theRestLookCarriesTheColors() {
        final BrandCommands.OrgLook look = brand.lookOf(org);
        assertEquals(look.palette(), "custom:p1");
        assertEquals(look.customPalette(), OURS);
        assertEquals(brand.themeNameOf(look), "freya-custom-light");
        assertEquals(brand.customColorsOf(look, false).get("primary-color"), "#8a2be2");
        assertEquals(brand.customColorsOf(look, true).get("shade800"), "#101010");
        assertNull(brand.customColorsOf(brand.lookOf(new Organization()), false), "no palette, no colors");
    }

    @Test
    public void theCardsAndTheEditorJsonOfferIt() {
        final CustomPalettes store = new CustomPalettes(() -> null);
        store.setOrgSource(id -> id.equals(org.getId().getValue()) ? org : null);
        final ThemePalettes palettes = new ThemePalettes();
        palettes.setCssSource(theme -> STOCK_CSS);
        palettes.setCustomPalettes(store);

        final List<ThemePalettes.Palette> light = palettes.cards(org.getId().getValue(), false);
        assertEquals(light.size(), palettes.cards(false).size() + 1, "the stock cards, then the org's own");
        final ThemePalettes.Palette card = light.get(light.size() - 1);
        assertEquals(card.name(), "custom:p1");
        assertEquals(card.label(), "Ours");
        assertEquals(card.theme(), "freya-custom-light");
        assertEquals(card.layout(), "layout-custom-light");
        assertEquals(card.primary(), "#8a2be2");
        assertEquals(card.card(), "#ffffff");
        assertEquals(card.text(), "#222222");
        assertEquals(card.param(), "custom:p1");
        final ThemePalettes.Palette dark = palettes.cards(org.getId().getValue(), true).get(light.size() - 1);
        assertEquals(dark.card(), "#101010", "the dark card is shade800");
        assertEquals(palettes.cards("no-such-org", false).size(), light.size() - 1);

        final String json = palettes.json(org.getId().getValue());
        assertTrue(json.contains("\"name\":\"custom:p1\""), json);
        assertTrue(json.contains("\"--ct-primary-color\":\"#8a2be2\""), json);
        assertFalse(palettes.json().contains("custom:p1"), "no org, no custom palettes");

        final CustomPalettes noManifest = new CustomPalettes(() -> null);
        noManifest.setOrgSource(id -> org);
        noManifest.setManifestSource(() -> null);
        final ThemePalettes without = new ThemePalettes();
        without.setCssSource(theme -> STOCK_CSS);
        without.setCustomPalettes(noManifest);
        assertEquals(without.cards(org.getId().getValue(), false).size(), light.size() - 1,
                "without the generated theme no custom card is offered");
        assertFalse(without.json(org.getId().getValue()).contains("custom:p1"));
    }

    private static void onSite(final SiteContext where, final Runnable body) {
        ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, where)).run(body);
    }
}
