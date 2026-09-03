package org.paulsens.trip.action;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.mockito.Mockito;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Config;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The {@code site.layout} setting and {@code BrandCommands.isFullWidth}: the marketing host is always
 * full-width, an organization opts in from its Appearance page, and every other host keeps the classic
 * card-and-sidebar page byte for byte. Owns its fixture (a throwaway organization).
 */
public class SiteLayoutTest {

    private static final String LAYOUT = KnownSettings.SITE_LAYOUT.getName();

    private Organization org;
    private SiteContext orgSite;
    private BrandCommands brand;

    @BeforeClass
    public void ownAnOrganization() throws IOException {
        org = new Organization();
        org.setName("Layout " + RandomData.genAlpha(6));
        org.setSlug("layout" + RandomData.genAlpha(6).toLowerCase(Locale.ROOT));
        Assert.assertTrue(DAO.getInstance().saveOrganization(org));
        orgSite = SiteContext.org(org.getId(), org.getSlug(), org.getSlug() + ".unitetrip.com");
    }

    @BeforeMethod
    public void freshBean() {
        brand = new BrandCommands();
    }

    @Test
    public void theMarketingHostIsAlwaysFullWidthAndTheSharedHostsNever() {
        onSite(SiteContext.marketing("unitetrip.com"), () -> Assert.assertTrue(brand.isFullWidth()));
        onSite(SiteContext.marketing("www.localhost"), () -> Assert.assertTrue(brand.isFullWidth()));
        onSite(SiteContext.shared("www.visitqueenofpeace.com"), () -> Assert.assertFalse(brand.isFullWidth(),
                "the shared site keeps its card and sidebar"));
        onSite(SiteContext.shared(null), () -> Assert.assertFalse(brand.isFullWidth()));
        Assert.assertFalse(brand.isFullWidth(), "off a bound request: classic, and no session consulted");
    }

    @Test
    public void anOrganizationOptsInAndAnyOtherValueIsClassic() throws IOException {
        stored(Map.of());
        onSite(orgSite, () -> Assert.assertFalse(brand.isFullWidth(), "blank is classic"));
        stored(Map.of(LAYOUT, KnownSettings.LAYOUT_FULL_WIDTH));
        onSite(orgSite, () -> Assert.assertTrue(brand.isFullWidth(), "the org's own choice, on its own host"));
        onSite(SiteContext.shared("www.visitqueenofpeace.com"), () -> Assert.assertFalse(brand.isFullWidth(),
                "an org's choice never reaches another host"));
        stored(Map.of(LAYOUT, KnownSettings.LAYOUT_CLASSIC));
        onSite(orgSite, () -> Assert.assertFalse(brand.isFullWidth()));
        stored(Map.of(LAYOUT, "wide"));
        onSite(orgSite, () -> Assert.assertFalse(brand.isFullWidth(), "a hand-written bogus value is classic"));
    }

    /** The one judge both save paths ask: the two spellings, blank, and nothing else. */
    @Test
    public void onlyTheTwoLayoutsAreAccepted() {
        Assert.assertNull(rejection(KnownSettings.LAYOUT_CLASSIC));
        Assert.assertNull(rejection(KnownSettings.LAYOUT_FULL_WIDTH));
        Assert.assertNull(rejection(""), "blank is always 'unset'");
        Assert.assertNull(rejection(null));
        Assert.assertTrue(rejection("wide").contains("not one of the choices"));
        Assert.assertTrue(rejection("Full-Width").contains("not one of the choices"), "matched exactly");
        Assert.assertTrue(KnownSettings.SITE_LAYOUT.isOrgOnly(), "the shared site's row never applies");
        Assert.assertEquals(KnownSettings.SITE_LAYOUT.getDefaultValue(), "");
        Assert.assertTrue(KnownSettings.isBranding(LAYOUT), "it is edited on the Appearance page");
    }

    /** The Appearance page's preview applies to the setting like any other, for that one admin only. */
    @Test
    public void thePreviewIsHonoredAndTheFieldHasItsOwnControl() throws IOException {
        stored(Map.of());
        final HttpSession session = fakeSession();
        brand.setSessionSource(() -> session);
        brand.setAppearanceViewSource(() -> org.getId().getValue());
        brand.preview(org.getId().getValue(), Map.of(LAYOUT, KnownSettings.LAYOUT_FULL_WIDTH), null);
        Assert.assertTrue(brand.isFullWidth(), "the unsaved choice, previewed");
        Assert.assertEquals(brand.appearanceEdit(org.getId().getValue()).get(LAYOUT),
                KnownSettings.LAYOUT_FULL_WIDTH);
        brand.clearPreview();
        Assert.assertFalse(brand.isFullWidth());
        Assert.assertEquals(brand.appearanceEdit(org.getId().getValue()).get(LAYOUT), "", "stored: blank");
        Assert.assertEquals(brand.forSave(Map.of(LAYOUT, " full-width "), null).get(LAYOUT),
                KnownSettings.LAYOUT_FULL_WIDTH, "trimmed into the save map");

        Assert.assertTrue(BrandCommands.DEDICATED_FIELDS.contains(LAYOUT), "a radio of its own on the page");
        Assert.assertTrue(brand.getDetailFields().stream().noneMatch(def -> def.getName().equals(LAYOUT)),
                "and therefore not a text box in Site details");
    }

    private static String rejection(final String value) {
        return new ConfigCommands().rejection(new Config(LAYOUT, value, Config.Type.STRING, null, null, null));
    }

    private void stored(final Map<String, String> overrides) throws IOException {
        final Organization fresh = DAO.getInstance().getOrganization(org.getId(), Cached.NO).orElseThrow();
        fresh.getSettingsOverrides().clear();
        fresh.getSettingsOverrides().putAll(overrides);
        Assert.assertTrue(DAO.getInstance().saveOrganization(fresh));
    }

    private void onSite(final SiteContext site, final Runnable body) {
        ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site)).run(body);
    }

    private static HttpSession fakeSession() {
        final Map<String, Object> attributes = new HashMap<>();
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(Mockito.anyString()))
                .thenAnswer(call -> attributes.get(call.<String>getArgument(0)));
        Mockito.doAnswer(call -> attributes.put(call.getArgument(0), call.getArgument(1)))
                .when(session).setAttribute(Mockito.anyString(), Mockito.any());
        Mockito.doAnswer(call -> attributes.remove(call.<String>getArgument(0)))
                .when(session).removeAttribute(Mockito.anyString());
        return session;
    }
}
