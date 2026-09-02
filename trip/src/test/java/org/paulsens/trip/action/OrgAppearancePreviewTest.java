package org.paulsens.trip.action;

import jakarta.faces.component.UIViewRoot;
import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Config;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.SettingDef;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.util.RandomData;
import org.paulsens.trip.web.Sessions;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The org Appearance page's two new mechanics: the page BACKGROUND (an image, else a color, else the shipped
 * dark default -- never the shared site's photograph) and the per-user PREVIEW rung, which lets one
 * administrator see unsaved appearance values applied to the page they are on, server-rendered, without
 * anyone else -- or the organization's live site -- seeing anything.
 *
 * <p>Owns its fixture: a throwaway organization per run, so nothing here can leave a seeded org branded.
 */
public class OrgAppearancePreviewTest {

    private static final String PALETTE = KnownSettings.SITE_THEME_PALETTE.getName();
    private static final String BG_URL = KnownSettings.SITE_BACKGROUND_URL.getName();
    private static final String BG_COLOR = KnownSettings.SITE_BACKGROUND_COLOR.getName();
    private static final String IMAGE = "https://cdn.example/bg.jpg";
    /** What an org that has chosen no color paints: the palette's own tint, with two fallbacks behind it. */
    private static final String PALETTE_BG =
            "--site-bg:none;--site-bg-color:" + BrandCommands.PALETTE_BACKGROUND;
    /**
     * The same thing spelled out. The chain is a contract with the Freya build in the sibling repo, where
     * every palette declares {@code --site-bg-default} as a tint of its own primary color, so it is pinned
     * as a literal here rather than derived from the constant it is supposed to be checking.
     */
    private static final String PALETTE_BG_LITERAL =
            "--site-bg:none;--site-bg-color:var(--site-bg-default, var(--surface-ground, #333333))";

    private Organization org;
    private SiteContext orgSite;
    private BrandCommands brand;

    @BeforeClass
    public void ownAnOrganization() throws IOException {
        org = new Organization();
        org.setName("Preview " + RandomData.genAlpha(6));
        org.setSlug("preview" + RandomData.genAlpha(6).toLowerCase(Locale.ROOT));
        Assert.assertTrue(DAO.getInstance().saveOrganization(org));
        orgSite = SiteContext.org(org.getId(), org.getSlug(), org.getSlug() + ".unitetrip.com");
    }

    /** Fresh per method: a seam one test sets must not steer the next. */
    @BeforeMethod
    public void freshBean() {
        brand = new BrandCommands();
    }

    // --- the background: image, else color, else the shipped default ---

    @Test
    public void anImageWinsOverAColorAndAColorAloneIsPainted() throws IOException {
        stored(Map.of(BG_URL, IMAGE, BG_COLOR, "#abcdef"));
        onSite(orgSite, () -> Assert.assertEquals(brand.getRootStyle(), "--site-bg:url(" + IMAGE + ")",
                "an image covers the page, so the color under it must not be painted as well"));

        stored(Map.of(BG_COLOR, "#ABCDEF"));
        onSite(orgSite, () -> Assert.assertEquals(brand.getRootStyle(),
                "--site-bg:none;--site-bg-color:#abcdef", "a color alone paints, with no image at all"));

        stored(Map.of());
        onSite(orgSite, () -> Assert.assertEquals(brand.getRootStyle(), PALETTE_BG_LITERAL,
                "an org that has chosen no color follows its palette's own tint, never the shared rainbow"));
        Assert.assertEquals(KnownSettings.SITE_BACKGROUND_COLOR.getDefaultValue(), "",
                "blank is the shipped default, and blank is what 'follow the palette' stores");
        Assert.assertEquals(PALETTE_BG, PALETTE_BG_LITERAL, "the constant and the pinned literal are one");
    }

    /**
     * The no-color background is three rungs, and the ORDER is what makes a green site green: the palette's
     * own {@code --site-bg-default} first (that is the only rung carrying hue -- every light palette shares
     * one {@code --surface-ground}), then the theme's ground for a stylesheet built before that property,
     * then a literal for a theme declaring neither. A chosen color skips all three, and an image skips the
     * color too.
     */
    @Test
    public void theNoColorBackgroundFallsBackPaletteThenGroundThenLiteral() throws IOException {
        Assert.assertEquals(BrandCommands.PALETTE_BACKGROUND,
                "var(--site-bg-default, var(--surface-ground, " + BrandCommands.FALLBACK_BACKGROUND_COLOR + "))",
                "the palette's own tint is the OUTERMOST rung, or every palette paints the same grey again");
        stored(Map.of());
        onSite(orgSite, () -> Assert.assertEquals(brand.getRootStyle(), PALETTE_BG_LITERAL));
        stored(Map.of(BG_COLOR, "#00ff00"));
        onSite(orgSite, () -> Assert.assertEquals(brand.getRootStyle(), "--site-bg:none;--site-bg-color:#00ff00",
                "a chosen color still wins over the palette's tint"));
        stored(Map.of(BG_COLOR, "#00ff00", BG_URL, IMAGE));
        onSite(orgSite, () -> Assert.assertEquals(brand.getRootStyle(), "--site-bg:url(" + IMAGE + ")",
                "and an image still beats both"));
    }

    @Test
    public void theSharedHostsCarryNoRootStyleAtAll() throws IOException {
        stored(Map.of(BG_COLOR, "#abcdef"));
        onSite(SiteContext.shared("www.visitqueenofpeace.com"), () -> Assert.assertNull(brand.getRootStyle(),
                "the shared stylesheet keeps its own fallbacks: no color, the rainbow image"));
        onSite(SiteContext.marketing("unitetrip.com"), () -> Assert.assertNull(brand.getRootStyle()));
        Assert.assertNull(brand.getRootStyle(), "and off a bound request entirely");
    }

    // --- the setting's validation, in the one judge both save paths ask ---

    @Test
    public void onlyAHexColorIsAccepted() {
        Assert.assertNull(rejection("#333333"));
        Assert.assertNull(rejection("#FC0"), "three digits, any case");
        Assert.assertNull(rejection("  #abc  "), "trimmed before judging");
        Assert.assertNull(rejection(""), "blank is always 'unset', which restores the shipped default");
        Assert.assertNull(rejection(null));
        Assert.assertTrue(rejection("333333").contains("hex color"), "the '#' is not optional");
        Assert.assertTrue(rejection("red").contains("hex color"), "color keywords are not offered");
        Assert.assertTrue(rejection("#12345").contains("hex color"));
        Assert.assertTrue(rejection("#33333g").contains("hex color"));
        Assert.assertTrue(rejection("#333;background:url(//evil)").contains("hex color"),
                "the value lands in a style attribute, so anything that could close it is refused");
        // The judge itself, on the values the bean and the page normalize through.
        Assert.assertEquals(SettingDef.hexColor("#ABC"), "#abc");
        Assert.assertNull(SettingDef.hexColor(null));
        Assert.assertNull(SettingDef.hexColor("abc"));
    }

    private static String rejection(final String value) {
        return new ConfigCommands().rejection(new Config(BG_COLOR, value, Config.Type.STRING, null, null, null));
    }

    // --- the preview rung ---

    @Test
    public void aPreviewAppliesToItsOwnPageAndToNothingElse() throws IOException {
        stored(Map.of(PALETTE, "blue"));
        final HttpSession session = fakeSession();
        brand.setSessionSource(() -> session);
        onAppearancePageOf(org.getId().getValue());
        brand.preview(org.getId().getValue(), Map.of(PALETTE, "red", BG_COLOR, "#00ff00"), null);

        Assert.assertTrue(brand.isPreviewing(), "the page says so, and the banner renders");
        Assert.assertEquals(brand.getTheme(), "freya-red-light", "the unsaved palette, chosen by the SERVER");
        Assert.assertEquals(brand.getLayoutCss(), "layout-red-light");
        Assert.assertEquals(brand.getRootStyle(), "--site-bg:none;--site-bg-color:#00ff00");
        Assert.assertEquals(brand.getWordmark(), org.getName(),
                "the previewed org's identity, even though this request is not on its host");

        // Any other view: the same session, the same org, no preview. This is the whole isolation rule --
        // the org's live site and every other page read what is stored.
        brand.setAppearanceViewSource(() -> null);
        Assert.assertFalse(brand.isPreviewing());
        Assert.assertEquals(brand.getTheme(), BrandCommands.DEFAULT_THEME_LIGHT, "no org host either: neutral");
        onSite(orgSite, () -> Assert.assertEquals(brand.getTheme(), "freya-blue-light",
                "the org's OWN site keeps showing the STORED palette while the preview is unsaved"));

        // The Appearance page of a DIFFERENT org: one admin's preview never leaks across tenants.
        brand.setAppearanceViewSource(() -> Organization.Id.newInstance().getValue());
        Assert.assertFalse(brand.isPreviewing());
        Assert.assertEquals(brand.getTheme(), BrandCommands.DEFAULT_THEME_LIGHT);

        // And a visitor with no session sees nothing of it, on the Appearance page or anywhere else.
        onAppearancePageOf(org.getId().getValue());
        brand.setSessionSource(() -> null);
        Assert.assertFalse(brand.isPreviewing());
        Assert.assertEquals(brand.getTheme(), BrandCommands.DEFAULT_THEME_LIGHT);
    }

    @Test
    public void cancelRestoresTheStoredValuesAndSaveIsWhatChangesThem() throws IOException {
        stored(Map.of(PALETTE, "blue"));
        final HttpSession session = fakeSession();
        brand.setSessionSource(() -> session);
        onAppearancePageOf(org.getId().getValue());

        brand.preview(org.getId().getValue(), Map.of(PALETTE, "green"), null);
        Assert.assertEquals(brand.getTheme(), "freya-green-light");
        Assert.assertEquals(brand.appearanceEdit(org.getId().getValue()).get(PALETTE), "green",
                "returning to the page while previewing shows the unsaved value");

        brand.clearPreview();
        Assert.assertFalse(brand.isPreviewing(), "Cancel discards it");
        Assert.assertEquals(brand.getTheme(), BrandCommands.DEFAULT_THEME_LIGHT, "off the org's host: neutral");
        Assert.assertEquals(brand.appearanceEdit(org.getId().getValue()).get(PALETTE), "blue",
                "and the page starts from the stored values again");
        brand.clearPreview();      // idempotent, and harmless with nothing stored
        onSite(orgSite, () -> Assert.assertEquals(brand.getTheme(), "freya-blue-light",
                "the site itself never moved"));
    }

    @Test
    public void aPreviewIsNeverStoredWithoutASessionOrAnOrganization() {
        brand.setSessionSource(() -> null);
        brand.preview(org.getId().getValue(), Map.of(PALETTE, "red"), null);       // no session: no-op
        brand.clearPreview();
        final HttpSession session = fakeSession();
        brand.setSessionSource(() -> session);
        brand.preview(null, Map.of(PALETTE, "red"), null);
        brand.preview("  ", Map.of(PALETTE, "red"), null);
        Assert.assertNull(session.getAttribute(Sessions.APPEARANCE_PREVIEW), "nothing to preview for");
        // A session attribute that is not a value map (a stale shape) is ignored rather than trusted.
        session.setAttribute(Sessions.APPEARANCE_PREVIEW_ORG, org.getId().getValue());
        session.setAttribute(Sessions.APPEARANCE_PREVIEW, "not a map");
        onAppearancePageOf(org.getId().getValue());
        Assert.assertFalse(brand.isPreviewing());
    }

    /**
     * The trigger, through the real Faces plumbing rather than the seam: the Appearance page's VIEW ID plus
     * its {@code orgId} parameter, and a session read that never creates one. The view id is the key because
     * the theme is resolved while {@code h:head} renders, before an {@code initPage} handler could set a flag.
     */
    @Test
    public void theTriggerIsTheViewIdAndTheOrgParameterAndItNeverCreatesASession() {
        final FacesContext ctx = Mockito.mock(FacesContext.class, Mockito.RETURNS_DEEP_STUBS);
        final UIViewRoot view = Mockito.mock(UIViewRoot.class);
        final HttpSession session = fakeSession();
        final Map<String, String> params = new HashMap<>();
        Mockito.when(ctx.getViewRoot()).thenReturn(view);
        Mockito.when(ctx.getExternalContext().getRequestParameterMap()).thenReturn(params);
        Mockito.when(ctx.getExternalContext().getSession(false)).thenReturn(session);
        try (MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class)) {
            faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);
            final BrandCommands real = new BrandCommands();
            params.put("orgId", org.getId().getValue());

            Mockito.when(view.getViewId()).thenReturn("/admin/orgConfig.xhtml");
            real.preview(org.getId().getValue(), Map.of(PALETTE, "purple"), null);
            Assert.assertFalse(real.isPreviewing(), "another admin page is not the Appearance page");

            Mockito.when(view.getViewId()).thenReturn(BrandCommands.APPEARANCE_VIEW_ID);
            Assert.assertTrue(real.isPreviewing(), "the Appearance view, for the org the parameter names");
            Assert.assertEquals(real.getTheme(), "freya-purple-light");

            params.remove("orgId");
            Assert.assertFalse(real.isPreviewing(), "the page always carries ?orgId=; without it, no preview");
            params.put("orgId", "  ");
            Assert.assertFalse(real.isPreviewing());
            Mockito.when(ctx.getViewRoot()).thenReturn(null);
            Assert.assertFalse(real.isPreviewing(), "no view yet, no preview");
            Mockito.verify(ctx.getExternalContext(), Mockito.never()).getSession(true);
        }
        Assert.assertFalse(new BrandCommands().isPreviewing(), "and outside JSF entirely");
    }

    // --- the page's edit map, its save map and its small helpers ---

    @Test
    public void theEditMapStartsFromTheStoredOverridesAndDerivesTheBackgroundMode() throws IOException {
        stored(Map.of(PALETTE, " orange ", BG_URL, IMAGE));
        final Map<String, String> edit = brand.appearanceEdit(org.getId().getValue());
        Assert.assertEquals(edit.get(PALETTE), "orange", "trimmed, as stored");
        Assert.assertEquals(edit.get(BG_URL), IMAGE);
        Assert.assertEquals(edit.get(BrandCommands.BG_MODE_KEY), BrandCommands.BG_MODE_IMAGE,
                "an org with a background image opens on the Image choice");
        Assert.assertEquals(edit.get(KnownSettings.SITE_LOGO_URL.getName()), "", "unset reads as blank");
        Assert.assertEquals(edit.size(), KnownSettings.branding().size() + 1, "every branding row, plus the mode");

        stored(Map.of());
        Assert.assertEquals(brand.appearanceEdit(org.getId().getValue()).get(BrandCommands.BG_MODE_KEY),
                BrandCommands.BG_MODE_PALETTE,
                "with neither image nor color stored, the page opens on 'follow the palette' -- which is how "
                        + "a blank color round-trips through a picker that can never hold one");
        stored(Map.of(BG_COLOR, "#abcdef"));
        Assert.assertEquals(brand.appearanceEdit(org.getId().getValue()).get(BrandCommands.BG_MODE_KEY),
                BrandCommands.BG_MODE_COLOR, "a stored color opens on the Color choice");
        Assert.assertEquals(brand.appearanceEdit(null).get(PALETTE), "",
                "an unknown organization edits blanks rather than failing");
    }

    @Test
    public void theSaveMapCarriesOnlySettingsAndClearsTheLosingBackground() {
        final Map<String, String> vals = new LinkedHashMap<>();
        vals.put(PALETTE, " red ");
        vals.put(BG_URL, IMAGE);
        vals.put(BG_COLOR, "#123456");

        vals.put(BrandCommands.BG_MODE_KEY, BrandCommands.BG_MODE_IMAGE);
        final Map<String, String> image = brand.forSave(vals, null);
        Assert.assertEquals(image.get(PALETTE), "red");
        Assert.assertEquals(image.get(BG_URL), IMAGE);
        Assert.assertEquals(image.get(BG_COLOR), "", "picking an image clears the color that cannot show");
        Assert.assertFalse(image.containsKey(BrandCommands.BG_MODE_KEY),
                "the chooser's mode is not a setting and must never reach a row");
        Assert.assertEquals(image.keySet().size(), KnownSettings.branding().size());

        vals.put(BrandCommands.BG_MODE_KEY, BrandCommands.BG_MODE_COLOR);
        final Map<String, String> color = brand.forSave(vals, "0f0");
        Assert.assertEquals(color.get(BG_COLOR), "#0f0", "the color picker reports hex without its '#'");
        Assert.assertEquals(color.get(BG_URL), "", "picking a color clears the image that would cover it");

        Assert.assertEquals(brand.forSave(vals, "not-a-color").get(BG_COLOR), "#123456",
                "an unusable picker value leaves the edited value alone");

        vals.put(BrandCommands.BG_MODE_KEY, BrandCommands.BG_MODE_PALETTE);
        final Map<String, String> palette = brand.forSave(vals, "0f0");
        Assert.assertEquals(palette.get(BG_COLOR), "",
                "following the palette stores a BLANK color, whatever the picker still holds");
        Assert.assertEquals(palette.get(BG_URL), "", "and no image either");
        Assert.assertEquals(brand.forSave(Map.of(), null).get(BG_URL), "", "an empty map saves blanks");
        Assert.assertEquals(brand.forSave(Map.of(), null).get(BG_COLOR), "",
                "an org that never touched the background is left following its palette");
    }

    @Test
    public void thePagesSmallHelpersAnswerWhatTheControlsNeed() {
        Assert.assertEquals(brand.getPalettes(), KnownSettings.THEME_PALETTES);
        Assert.assertEquals(brand.appearanceUrl("abc"), "/admin/orgAppearance.jsf?orgId=abc",
                "every preview, Save and Cancel redirects here, so the orgId parameter is always present");
        Assert.assertEquals(brand.appearanceUrl(null), "/admin/orgAppearance.jsf?orgId=");
        Assert.assertEquals(brand.colorHex(Map.of(BG_COLOR, "#AABBCC")), "aabbcc",
                "the widget's value shape: no '#'");
        Assert.assertEquals(brand.colorHex(Map.of()), "333333", "nothing chosen: what the picker opens on");
        Assert.assertEquals(brand.colorHex(Map.of(BG_COLOR, "nonsense")), "333333");
        Assert.assertEquals(brand.colorHex(null), "333333");
    }

    /**
     * The Appearance page renders EVERY branding setting: the six with a control of their own, and the rest
     * from {@code brand.detailFields}, which the page repeats over. This is the guard the split needed --
     * `site.ogImage.url`, the two footer settings, both contact settings and the donate URL were on NEITHER
     * page for a while (the org Settings page excludes the whole Branding section), which is how a site
     * ended up with a "Questions?" card nobody could put a name or a phone number on.
     */
    @Test
    public void everyBrandingSettingIsReachableOnTheAppearancePage() {
        final List<String> detail = brand.getDetailFields().stream().map(SettingDef::getName).toList();
        final List<String> rendered = Stream.concat(BrandCommands.DEDICATED_FIELDS.stream(), detail.stream())
                .sorted().toList();
        final List<String> declared = KnownSettings.branding().stream().map(SettingDef::getName).sorted()
                .toList();
        Assert.assertEquals(rendered, declared,
                "a branding setting the page neither gives a control nor repeats over is editable NOWHERE");
        for (final String missed : List.of("site.ogImage.url", "site.footer.title", "site.footer.text",
                "site.contact.name", "site.contact.phone", "site.donate.url")) {
            Assert.assertTrue(detail.contains(missed), missed + " must be on the page's own key list");
        }
        Assert.assertFalse(detail.contains(PALETTE), "the palette has a menu of its own, not a text box");
        Assert.assertFalse(detail.contains(KnownSettings.SITE_THEME_DARK.getName()));
    }

    /**
     * And the two pages still partition the org-overridable settings exactly: nothing is offered twice and
     * nothing goes missing, whichever list a new setting is added to.
     */
    @Test
    public void theTwoOrgPagesPartitionTheOverridableSettings() {
        final List<String> branding = KnownSettings.branding().stream().map(SettingDef::getName).toList();
        final List<String> generic = KnownSettings.orgOverridableNonBranding().stream()
                .map(SettingDef::getName).toList();
        final List<String> both = Stream.concat(branding.stream(), generic.stream()).sorted().toList();
        Assert.assertEquals(both, KnownSettings.orgOverridable().stream().map(SettingDef::getName).sorted()
                .toList(), "branding + the rest must be exactly the org-overridable set");
        Assert.assertTrue(branding.stream().noneMatch(generic::contains), "and the halves may not overlap");
        Assert.assertTrue(branding.contains(KnownSettings.SITE_THEME_DARK.getName()),
                "Dark mode is a look-and-feel setting: the Appearance page, beside the palette");
    }

    /** A preview whose image is switched off by the mode shows the color, and keeps the URL for a switch back. */
    @Test
    public void theModeDecidesWhichBackgroundThePreviewPaints() {
        final HttpSession session = fakeSession();
        brand.setSessionSource(() -> session);
        onAppearancePageOf(org.getId().getValue());
        final Map<String, String> vals = new LinkedHashMap<>();
        vals.put(BG_URL, IMAGE);
        vals.put(BrandCommands.BG_MODE_KEY, BrandCommands.BG_MODE_COLOR);
        brand.preview(org.getId().getValue(), vals, "abc");

        Assert.assertEquals(brand.getRootStyle(), "--site-bg:none;--site-bg-color:#abc",
                "in Color mode the image is not painted, however good the URL still in the box is");
        Assert.assertEquals(brand.appearanceEdit(org.getId().getValue()).get(BG_URL), IMAGE,
                "and the URL is kept, so switching back to Image does not lose what was typed");

        vals.put(BrandCommands.BG_MODE_KEY, BrandCommands.BG_MODE_IMAGE);
        brand.preview(org.getId().getValue(), vals, "abc");
        Assert.assertEquals(brand.getRootStyle(), "--site-bg:url(" + IMAGE + ")");
    }

    // --- fixture plumbing ---

    /** Replaces the owned org's overrides, as the editor would; every test states its own starting point. */
    private void stored(final Map<String, String> overrides) throws IOException {
        final Organization fresh = DAO.getInstance().getOrganization(org.getId(), Cached.NO).orElseThrow();
        fresh.getSettingsOverrides().clear();
        fresh.getSettingsOverrides().putAll(overrides);
        Assert.assertTrue(DAO.getInstance().saveOrganization(fresh));
    }

    /** Pretends this request is rendering {@code orgId}'s Appearance page (the seam over the view id). */
    private void onAppearancePageOf(final String orgId) {
        brand.setAppearanceViewSource(() -> orgId);
    }

    private void onSite(final SiteContext site, final Runnable body) {
        ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site)).run(body);
    }

    /** A session that really holds what is put on it, so the preview can be read back as the page reads it. */
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

    /** Guards the list the org Settings page renders against silently gaining a look-and-feel row. */
    @Test
    public void theGenericOrgSettingsPageOffersNoAppearanceRow() {
        final List<String> names = new ConfigCommands().getOrgConfigDefs().stream()
                .map(SettingDef::getName).toList();
        Assert.assertFalse(names.contains(PALETTE), "the palette belongs to the Appearance page alone");
        Assert.assertFalse(names.contains(BG_COLOR));
        Assert.assertTrue(names.contains("reg.allowEdits"), "everything else still shows there");
    }
}
