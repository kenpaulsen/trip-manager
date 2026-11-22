package org.paulsens.trip.action;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.config.OrgSettingsTabs;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.SettingDef;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * An organization's site text in its other languages ({@code docs/i18n.md}, "Language versions of a page"): a
 * translatable setting's override key with a language mark ({@code site.footer.text@es}) is read before the plain
 * one for a reader in that language, only for display text, only from the organization's own row, and it is
 * edited and saved through the same map as the plain override.
 */
public class TranslatedSettingsTest {

    private final ConfigCommands config = new ConfigCommands();

    @BeforeClass
    public void seed() {
        DAO.getInstance();
    }

    @Test
    public void aReaderGetsTheTextWrittenForTheirLanguageElseThePlainOne() throws Exception {
        final Organization org = new Organization();
        org.getSettingsOverrides().put("site.footer.text", "Pilgrimages since 1990");
        org.getSettingsOverrides().put("site.footer.text@es", "Peregrinaciones desde 1990");
        final SettingDef footer = KnownSettings.SITE_FOOTER_TEXT;

        Assert.assertEquals(in(Language.Spanish, () -> config.getString(footer, org)), "Peregrinaciones desde 1990");
        Assert.assertEquals(in(Language.English, () -> config.getString(footer, org)), "Pilgrimages since 1990");
        Assert.assertEquals(in(Language.Croatian, () -> config.getString(footer, org)), "Pilgrimages since 1990",
                "nothing written in Croatian: the plain text");
        Assert.assertEquals(config.getString(footer, org), "Pilgrimages since 1990", "off a request: English");

        org.getSettingsOverrides().put("site.footer.text@es", "  ");
        Assert.assertEquals(in(Language.Spanish, () -> config.getString(footer, org)), "Pilgrimages since 1990",
                "a blank translation is no translation");
    }

    @Test
    public void onlyDisplayTextIsEverReadInALanguage() throws Exception {
        final Organization org = new Organization();
        // Written by hand onto the row: the save path refuses both, and the read must ignore them too.
        org.getSettingsOverrides().put("site.donate.url", "https://example.com/give");
        org.getSettingsOverrides().put("site.donate.url@es", "https://example.com/dar");
        org.getSettingsOverrides().put("site.contact.name", "Bea");
        org.getSettingsOverrides().put("site.contact.name@es", "Beatriz");
        Assert.assertEquals(in(Language.Spanish, () -> config.getString(KnownSettings.SITE_DONATE_URL, org)),
                "https://example.com/give");
        Assert.assertEquals(in(Language.Spanish, () -> config.getString(KnownSettings.SITE_CONTACT_NAME, org)),
                "Bea", "a person's name is not translated");
        Assert.assertEquals(KnownSettings.translatable().stream().map(SettingDef::getName).toList(),
                List.of("site.footer.title", "site.footer.text", "site.donate.title", "site.donate.text",
                        "site.title", "site.sidebar.title"));
    }

    @Test
    public void anOrganizationsTranslationNeverReachesAnotherOrganization() throws Exception {
        final Organization beta = TenancyFixtures.org("TranslatedA");
        final Organization other = TenancyFixtures.org("TranslatedB");
        Assert.assertTrue(TenancyFixtures.asSiteAdmin().saveOrgSettings(beta.getId().getValue(),
                Map.of("site.title", "Beta Travel", "site.title@es", "Viajes Beta")));
        final Organization stored = DAO.getInstance().getOrganization(beta.getId(), Cached.NO).orElseThrow();
        Assert.assertEquals(stored.getSettingsOverrides().get("site.title@es"), "Viajes Beta");

        Assert.assertEquals(onSite(beta, Language.Spanish, () -> new BrandCommands().getSiteTitle()),
                "Viajes Beta");
        Assert.assertEquals(onSite(beta, Language.English, () -> new BrandCommands().getSiteTitle()),
                "Beta Travel");
        Assert.assertNull(onSite(other, Language.Spanish, () -> new BrandCommands().getSiteTitle()),
                "the other organization set nothing, and nothing of Beta's shows there");
        Assert.assertEquals(onSite(beta, Language.Spanish, () -> SiteCommands.siteTitleOf(SiteContext.current(),
                id -> DAO.getInstance().getOrganization(id, Cached.YES))), "Viajes Beta", "the browser title too");
    }

    @Test
    public void theSaveTakesALanguageKeyOnlyForDisplayTextInAKnownLanguage() throws IOException {
        final Organization org = TenancyFixtures.org("TranslatedSave");
        final String orgId = org.getId().getValue();
        final OrgCommands admin = TenancyFixtures.asSiteAdmin();
        Assert.assertTrue(admin.saveOrgSettings(orgId, Map.of("site.donate.text@hr", "Hvala")));
        Assert.assertFalse(admin.saveOrgSettings(orgId, Map.of("site.donate.url@es", "https://example.com/dar")),
                "a URL is the same in every language");
        Assert.assertFalse(admin.saveOrgSettings(orgId, Map.of("site.donate.text@ES", "x")), "exact tags only");
        Assert.assertFalse(admin.saveOrgSettings(orgId, Map.of("site.donate.text@xx", "x")));
        Assert.assertFalse(admin.saveOrgSettings(orgId, Map.of("home.banner.text@es", "x")),
                "a site-only setting has no org rung to translate");
        Assert.assertTrue(admin.saveOrgSettings(orgId, Map.of("site.donate.text@hr", "")));
        final Organization stored = DAO.getInstance().getOrganization(org.getId(), Cached.NO).orElseThrow();
        Assert.assertFalse(stored.getSettingsOverrides().containsKey("site.donate.text@hr"), "blank clears it");
        Assert.assertTrue(stored.getSettingsOverrides().keySet().stream().noneMatch(key -> key.contains("@")));
    }

    @Test
    public void theEditorOffersOneFieldPerOtherLanguageTheSiteOffers() throws IOException {
        final Organization org = TenancyFixtures.org("TranslatedEdit");
        final String orgId = org.getId().getValue();
        final OrgCommands admin = TenancyFixtures.asSiteAdmin();
        Assert.assertTrue(admin.orgSettingsEdit(orgId).keySet().stream().noneMatch(key -> key.contains("@")),
                "a one-language site has nothing to translate into");

        Assert.assertTrue(admin.saveOrgSettings(orgId, Map.of("site.languages", "es,en",
                "site.sidebar.title@en", "Trips")));
        final Map<String, String> edit = admin.orgSettingsEdit(orgId);
        Assert.assertEquals(edit.get("site.sidebar.title@en"), "Trips");
        Assert.assertEquals(edit.get("site.footer.text@en"), "");
        Assert.assertFalse(edit.containsKey("site.footer.text@es"), "Spanish is this site's own language");
        Assert.assertFalse(edit.containsKey("site.donate.url@en"));
        final Organization stored = DAO.getInstance().getOrganization(org.getId(), Cached.NO).orElseThrow();
        Assert.assertEquals(config.otherSiteLanguages(stored), List.of(Language.English));
        Assert.assertEquals(config.otherSiteLanguages(null), List.of());
    }

    @Test
    public void aTabsSaveCarriesItsOwnRowsTranslationsAndNoOneElses() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("site.donate.text", "Give");
        values.put("site.donate.text@es", "Dar");
        values.put("chat.reactions.palette@es", "x");
        values.put("site.contact.name", "Bea");
        final Map<String, String> home = OrgSettingsTabs.valuesFor(OrgSettingsTabs.HOME, values);
        Assert.assertEquals(home.get("site.donate.text@es"), "Dar");
        Assert.assertFalse(home.containsKey("site.contact.name"), "the Profile tab's");
        Assert.assertFalse(home.containsKey("chat.reactions.palette@es"));
        Assert.assertFalse(OrgSettingsTabs.valuesFor(OrgSettingsTabs.PROFILE, values)
                .containsKey("site.donate.text@es"));
    }

    @Test
    public void theAppearanceSaveKeepsWhatWasTypedInOtherLanguages() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("site.footer.text", "Hello");
        values.put("site.footer.text@hr", " Bok ");
        values.put("site.donate.text@hr", "Hvala");
        values.put("site.logo.url@hr", "https://example.com/x.png");
        final Map<String, String> saved = new BrandCommands().forSave(values, null);
        Assert.assertEquals(saved.get("site.footer.text@hr"), "Bok");
        Assert.assertFalse(saved.containsKey("site.donate.text@hr"), "the Home tab saves that one");
        Assert.assertFalse(saved.containsKey("site.logo.url@hr"));
    }

    @Test
    public void aLanguageKeyNamesItsSettingAndOneExactLanguage() {
        final SettingDef footer = KnownSettings.SITE_FOOTER_TEXT;
        Assert.assertEquals(footer.keyIn(Language.Spanish), "site.footer.text@es");
        Assert.assertEquals(SettingDef.baseNameOf("site.footer.text@es"), "site.footer.text");
        Assert.assertEquals(SettingDef.baseNameOf("site.footer.text"), "site.footer.text");
        Assert.assertEquals(SettingDef.baseNameOf("odd@ES"), "odd@ES");
        Assert.assertNull(SettingDef.baseNameOf(null));
        Assert.assertTrue(SettingDef.languageOfKey("@es").isEmpty());
        Assert.assertEquals(KnownSettings.findOrgOverrideKey("site.footer.text@hr"), java.util.Optional.of(footer));
        Assert.assertEquals(KnownSettings.findOrgOverrideKey("site.footer.text"), java.util.Optional.of(footer));
        Assert.assertTrue(KnownSettings.findOrgOverrideKey("site.footer.text.color@es").isEmpty());
        for (final SettingDef def : KnownSettings.translatable()) {
            Assert.assertEquals(def.getType(), org.paulsens.trip.model.Config.Type.STRING, def.getName());
        }
        Assert.assertTrue(KnownSettings.all().stream().filter(SettingDef::isTranslatable)
                .allMatch(SettingDef::isOrgOverridable), "a translation lives on the org row: org rung only");
    }

    private static <T> T in(final Language language, final ScopedValue.CallableOp<T, Exception> body)
            throws Exception {
        final RequestContext context = RequestContext.of(null, null, SiteContext.shared("localhost"))
                .withLocale(language.getLocale());
        return ScopedValue.where(RequestContext.SCOPE, context).call(body);
    }

    private static <T> T onSite(final Organization org, final Language language,
            final ScopedValue.CallableOp<T, Exception> body) throws Exception {
        final RequestContext context = RequestContext.of(null, null, SiteContext.org(org.getId(), "t", "t.test"))
                .withLocale(language.getLocale());
        return ScopedValue.where(RequestContext.SCOPE, context).call(body);
    }
}
