package org.paulsens.trip.action;

import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.web.Sessions;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The {@code #{brand}} bean: an org host draws ONLY on the org's own Branding settings (blank = the neutral
 * platform default, never the shared site's look), every other host gets the neutral / empty answers, and
 * nothing unsafe for an attribute ever leaves it.
 */
public class BrandCommandsTest {

    private static final Organization.Id ACME = Organization.Id.from(FakeData.ACME_ORG_ID);
    private static final SiteContext ACME_SITE = SiteContext.org(ACME, "acme", "acme.unitetrip.com");

    /** Fresh per method: the seams a test sets (dark flag, org lookup) must not leak into the next one. */
    private BrandCommands brand;

    @BeforeClass
    public void seed() {
        DAO.getInstance();      // local mode: FakeData seeds Acme
    }

    @BeforeMethod
    public void freshBean() {
        brand = new BrandCommands();
    }

    /** Acme is shared with every other test class: whatever a test set on it is taken off again. */
    @AfterMethod
    public void restoreAcme() throws IOException {
        final Organization fresh = DAO.getInstance().getOrganization(ACME, Cached.NO).orElseThrow();
        if (!fresh.getSettingsOverrides().isEmpty() || fresh.getContactEmail() != null) {
            fresh.getSettingsOverrides().clear();
            fresh.setContactEmail(null);
            Assert.assertTrue(DAO.getInstance().saveOrganization(fresh));
        }
    }

    @Test
    public void offAnOrgHostEverythingIsNeutral() {
        assertNeutral("no bound request");
        onSite(SiteContext.shared("www.visitqueenofpeace.com"), () -> assertNeutral("the shared site"));
        onSite(SiteContext.marketing("unitetrip.com"), () -> assertNeutral("the marketing host"));
    }

    private void assertNeutral(final String where) {
        Assert.assertEquals(brand.getTheme(), BrandCommands.DEFAULT_THEME_LIGHT, where);
        Assert.assertEquals(brand.getLayoutCss(), "layout-light", where);
        Assert.assertFalse(brand.isDark(), where);
        Assert.assertNull(brand.getLogoUrl(), where);
        Assert.assertNull(brand.getWordmark(), where);
        Assert.assertNull(brand.getFaviconHref(), where + ": the shared page keeps its own favicon link");
        Assert.assertNull(brand.getOgImage(), where);
        Assert.assertNull(brand.getRootStyle(), where + ": the shared stylesheet keeps its own fallback");
        Assert.assertNull(brand.getFooterTitle(), where);
        Assert.assertNull(brand.getFooterText(), where);
        Assert.assertNull(brand.getContactName(), where);
        Assert.assertNull(brand.getContactPhone(), where);
        Assert.assertNull(brand.getContactEmail(), where);
        Assert.assertFalse(brand.isShowContact(), where);
        Assert.assertNull(brand.getDonateUrl(), where);
        Assert.assertFalse(brand.isShowDonate(), where);
        Assert.assertNull(brand.getAnalyticsId(), where + ": the shared pages keep their literal property");
    }

    @Test
    public void anOrgHostWithNoSettingsGetsThePlatformDefaultsAndItsOwnName() {
        onSite(ACME_SITE, () -> {
            Assert.assertEquals(brand.getTheme(), BrandCommands.DEFAULT_THEME_LIGHT, "no palette: medj");
            Assert.assertEquals(brand.getLayoutCss(), "layout-light");
            Assert.assertNull(brand.getLogoUrl());
            Assert.assertEquals(brand.getWordmark(), "Acme Inc", "no logo: the name is the wordmark");
            Assert.assertEquals(brand.getFaviconHref(), BrandCommands.NO_FAVICON,
                    "no icon: the browser must not fetch the shared site's /favicon.ico");
            Assert.assertNull(brand.getOgImage(), "no image and no logo: no preview picture");
            Assert.assertEquals(brand.getRootStyle(), "--site-bg:none");
            Assert.assertEquals(brand.getFooterTitle(), "Acme Inc");
            Assert.assertNull(brand.getFooterText());
            Assert.assertNull(brand.getContactName());
            Assert.assertNull(brand.getContactPhone());
            Assert.assertNull(brand.getContactEmail(), "Acme's profile has no contact email");
            Assert.assertFalse(brand.isShowContact());
            Assert.assertNull(brand.getDonateUrl());
            Assert.assertFalse(brand.isShowDonate());
            Assert.assertNull(brand.getAnalyticsId(), "blank means no tag, never the shared site's id");
        });
    }

    @Test
    public void anOrgHostWithSettingsAnswersEachOne() throws IOException {
        setAcme(Map.of(
                KnownSettings.SITE_THEME_PALETTE.getName(), "green",
                KnownSettings.SITE_LOGO_URL.getName(), "https://cdn.acme.example/logo.png",
                KnownSettings.SITE_FAVICON_URL.getName(), "https://cdn.acme.example/favicon.ico",
                KnownSettings.SITE_OG_IMAGE_URL.getName(), "https://cdn.acme.example/og.png",
                KnownSettings.SITE_BACKGROUND_URL.getName(), "https://cdn.acme.example/bg.jpg",
                KnownSettings.SITE_FOOTER_TITLE.getName(), "Acme Pilgrimages",
                KnownSettings.SITE_FOOTER_TEXT.getName(), "A registered charity",
                KnownSettings.SITE_CONTACT_NAME.getName(), "Wile E.",
                KnownSettings.SITE_CONTACT_PHONE.getName(), "+1 555 0100",
                KnownSettings.SITE_DONATE_URL.getName(), "https://donate.acme.example/"), "info@acme.example");
        onSite(ACME_SITE, () -> {
            Assert.assertEquals(brand.getTheme(), "freya-green-light");
            Assert.assertEquals(brand.getLayoutCss(), "layout-green-light");
            Assert.assertEquals(brand.getLogoUrl(), "https://cdn.acme.example/logo.png");
            Assert.assertNull(brand.getWordmark(), "a logo replaces the wordmark");
            Assert.assertEquals(brand.getFaviconHref(), "https://cdn.acme.example/favicon.ico");
            Assert.assertEquals(brand.getOgImage(), "https://cdn.acme.example/og.png");
            Assert.assertEquals(brand.getRootStyle(), "--site-bg:url(https://cdn.acme.example/bg.jpg)");
            Assert.assertEquals(brand.getFooterTitle(), "Acme Pilgrimages");
            Assert.assertEquals(brand.getFooterText(), "A registered charity");
            Assert.assertEquals(brand.getContactName(), "Wile E.");
            Assert.assertEquals(brand.getContactPhone(), "+1 555 0100");
            Assert.assertEquals(brand.getContactEmail(), "info@acme.example");
            Assert.assertTrue(brand.isShowContact());
            Assert.assertEquals(brand.getDonateUrl(), "https://donate.acme.example/");
            Assert.assertTrue(brand.isShowDonate());
        });
        // The same settings are NOT the shared site's: another host still sees nothing of them.
        onSite(SiteContext.shared("www.visitqueenofpeace.com"), () -> assertNeutral("the shared site"));
    }

    @Test
    public void theLinkPreviewFallsBackToTheLogoAndTheContactCardNeedsAnyOneField() throws IOException {
        setAcme(Map.of(KnownSettings.SITE_LOGO_URL.getName(), "https://cdn.acme.example/logo.png",
                KnownSettings.SITE_CONTACT_PHONE.getName(), "555"), null);
        onSite(ACME_SITE, () -> {
            Assert.assertEquals(brand.getOgImage(), "https://cdn.acme.example/logo.png", "no og image: the logo");
            Assert.assertTrue(brand.isShowContact(), "a phone alone shows the card");
            Assert.assertNull(brand.getContactEmail());
        });
        setAcme(Map.of(KnownSettings.SITE_ANALYTICS_ID.getName(), "G-ACME"), "  ");
        onSite(ACME_SITE, () -> {
            Assert.assertFalse(brand.isShowContact(), "a blank contact email is no email");
            Assert.assertEquals(brand.getAnalyticsId(), "G-ACME");
        });
    }

    @Test
    public void darkModeFollowsTheSessionFlag() throws IOException {
        brand.setDarkSource(() -> true);
        Assert.assertTrue(brand.isDark());
        Assert.assertEquals(brand.getTheme(), BrandCommands.DEFAULT_THEME_DARK, "no org host: the medj dark theme");
        Assert.assertEquals(brand.getLayoutCss(), "layout-dark");
        onSite(ACME_SITE, () -> {
            Assert.assertEquals(brand.getTheme(), BrandCommands.DEFAULT_THEME_DARK, "no palette: medj dark");
            Assert.assertEquals(brand.getLayoutCss(), "layout-dark");
        });
        setAcme(Map.of(KnownSettings.SITE_THEME_PALETTE.getName(), "green"), null);
        onSite(ACME_SITE, () -> {
            Assert.assertEquals(brand.getTheme(), "freya-green-dark");
            Assert.assertEquals(brand.getLayoutCss(), "layout-green-dark");
        });
        brand.setDarkSource(() -> null);
        Assert.assertFalse(brand.isDark(), "an unreadable flag is light");
    }

    @Test
    public void theSessionFlagIsReadWithoutCreatingASession() {
        Assert.assertFalse(new BrandCommands().isDark(), "no FacesContext: light");
        Assert.assertFalse(BrandCommands.darkOf(null));
        Assert.assertFalse(BrandCommands.darkOf("not a session"));

        final HttpSession session = Mockito.mock(HttpSession.class);
        Assert.assertFalse(BrandCommands.darkOf(session), "no flag: light");
        Mockito.when(session.getAttribute(Sessions.DARK)).thenReturn(Boolean.TRUE);
        Assert.assertTrue(BrandCommands.darkOf(session), "the topbar stores a Boolean");
        Mockito.when(session.getAttribute(Sessions.DARK)).thenReturn("true");
        Assert.assertTrue(BrandCommands.darkOf(session), "a string spelling counts too");
        Mockito.when(session.getAttribute(Sessions.DARK)).thenReturn(Boolean.FALSE);
        Assert.assertFalse(BrandCommands.darkOf(session));

        final FacesContext ctx = Mockito.mock(FacesContext.class, Mockito.RETURNS_DEEP_STUBS);
        try (MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class)) {
            faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);
            Mockito.when(ctx.getExternalContext().getSession(false)).thenReturn(null);
            Assert.assertFalse(new BrandCommands().isDark(), "a visitor without a session is light");
            Mockito.when(session.getAttribute(Sessions.DARK)).thenReturn(Boolean.TRUE);
            Mockito.when(ctx.getExternalContext().getSession(false)).thenReturn(session);
            Assert.assertTrue(new BrandCommands().isDark());
            Mockito.verify(ctx.getExternalContext(), Mockito.never()).getSession(true);
        }
    }

    @Test
    public void anUnreadableOrgRendersTheNeutralLookInsteadOfFailing() throws IOException {
        setAcme(Map.of(KnownSettings.SITE_THEME_PALETTE.getName(), "green",
                KnownSettings.SITE_CONTACT_NAME.getName(), "Wile E."), "info@acme.example");
        final SiteContext ghost = SiteContext.org(Organization.Id.newInstance(), "ghost", "ghost.unitetrip.com");
        onSite(ghost, () -> {
            Assert.assertEquals(brand.getTheme(), BrandCommands.DEFAULT_THEME_LIGHT);
            Assert.assertEquals(brand.getLayoutCss(), "layout-light");
            Assert.assertEquals(brand.getWordmark(), "ghost", "no row: the slug, as SiteCommands does");
            Assert.assertEquals(brand.getFooterTitle(), "ghost");
            Assert.assertEquals(brand.getFaviconHref(), BrandCommands.NO_FAVICON);
            Assert.assertEquals(brand.getRootStyle(), "--site-bg:none");
            Assert.assertFalse(brand.isShowContact());
            Assert.assertNull(brand.getContactEmail());
        });
        brand.setOrgSource(id -> {
            throw new IllegalStateException("cache down");
        });
        onSite(ACME_SITE, () -> {
            Assert.assertEquals(brand.getTheme(), BrandCommands.DEFAULT_THEME_LIGHT, "a failed read: neutral");
            Assert.assertEquals(brand.getWordmark(), "acme");
            Assert.assertNull(brand.getContactName());
            Assert.assertNull(brand.getContactEmail());
        });
        brand.setOrgSource(id -> Optional.empty());
        onSite(ACME_SITE, () -> Assert.assertEquals(brand.getFooterTitle(), "acme"));
    }

    @Test
    public void aHostileUrlInARowNeverReachesAnAttribute() throws IOException {
        // Written straight onto the row (the save path would refuse these): the bean is the last line.
        setAcme(Map.of(
                KnownSettings.SITE_LOGO_URL.getName(), "https://x.example/a\"onload=alert(1)",
                KnownSettings.SITE_FAVICON_URL.getName(), "javascript:alert(1)",
                KnownSettings.SITE_OG_IMAGE_URL.getName(), "https://x.example/<img>",
                KnownSettings.SITE_BACKGROUND_URL.getName(), "https://x.example/a b.jpg",
                KnownSettings.SITE_DONATE_URL.getName(), "ftp://x.example/",
                KnownSettings.SITE_THEME_PALETTE.getName(), "../../etc"), null);
        onSite(ACME_SITE, () -> {
            Assert.assertNull(brand.getLogoUrl());
            Assert.assertEquals(brand.getWordmark(), "Acme Inc", "a refused logo means the wordmark shows");
            Assert.assertEquals(brand.getFaviconHref(), BrandCommands.NO_FAVICON);
            Assert.assertNull(brand.getOgImage());
            Assert.assertEquals(brand.getRootStyle(), "--site-bg:none");
            Assert.assertNull(brand.getDonateUrl());
            Assert.assertFalse(brand.isShowDonate());
            Assert.assertEquals(brand.getTheme(), BrandCommands.DEFAULT_THEME_LIGHT,
                    "an unshipped palette is a stylesheet 404, so it is the default look");
            Assert.assertEquals(brand.getLayoutCss(), "layout-light");
        });
    }

    @Test
    public void urlsAreScreenedAndCssEncoded() {
        Assert.assertEquals(BrandCommands.safeUrl("https://x.example/p?q=1&r=2"), "https://x.example/p?q=1&r=2");
        Assert.assertEquals(BrandCommands.safeUrl("http://x.example/a(b).png"), "http://x.example/a(b).png",
                "legal in a URL; the CSS context encodes it");
        Assert.assertNull(BrandCommands.safeUrl(null));
        Assert.assertNull(BrandCommands.safeUrl(""));
        Assert.assertNull(BrandCommands.safeUrl("x.example/no-scheme"));
        Assert.assertNull(BrandCommands.safeUrl("https://x.example/a'b"));
        Assert.assertNull(BrandCommands.safeUrl("https://x.example/a\\b"));
        Assert.assertNull(BrandCommands.safeUrl("https://x.example/a>b"));
        Assert.assertNull(BrandCommands.safeUrl("https://x.example/a\tb"));
        Assert.assertEquals(BrandCommands.cssUrl("https://x.example/a(b);c'd{e}\"f\\g.png"),
                "https://x.example/a%28b%29%3Bc%27d%7Be%7D%22f%5Cg.png");
        Assert.assertEquals(BrandCommands.cssUrl("https://x.example/plain.png"), "https://x.example/plain.png");
    }

    private void onSite(final SiteContext site, final Runnable body) {
        ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site)).run(body);
    }

    /** Writes overrides (and a contact email) straight onto Acme's row, as the org editor would. */
    private static void setAcme(final Map<String, String> overrides, final String contactEmail)
            throws IOException {
        final Organization fresh = DAO.getInstance().getOrganization(ACME, Cached.NO).orElseThrow();
        fresh.getSettingsOverrides().clear();
        fresh.getSettingsOverrides().putAll(overrides);
        fresh.setContactEmail(contactEmail);
        Assert.assertTrue(DAO.getInstance().saveOrganization(fresh));
    }
}
