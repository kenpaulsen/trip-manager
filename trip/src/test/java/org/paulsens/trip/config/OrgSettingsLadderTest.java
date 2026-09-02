package org.paulsens.trip.config;

import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Config;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.SettingDef;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The per-organization settings ladder: org override &rarr; site row &rarr; compiled default, and the two
 * rules that make it safe -- an org may override ONLY what the registry marks overridable, and an org-only
 * setting never inherits the site's row on an org host (an analytics id would otherwise leak one tenant's
 * traffic into another's property). Resolution is automatic for a request on an org host (through the
 * bound {@link SiteContext}) and explicit everywhere else.
 */
public class OrgSettingsLadderTest {

    private final ConfigCommands config = new ConfigCommands();

    @Test
    public void theFlagsAreOptInAndOrgOnlyImpliesOverridable() {
        final SettingDef plain = new SettingDef("x.plain", Config.Type.STRING, "d", "label", "desc");
        Assert.assertFalse(plain.isOrgOverridable());
        Assert.assertFalse(plain.isOrgOnly());
        final SettingDef overridable = plain.withOrgOverride();
        Assert.assertTrue(overridable.isOrgOverridable());
        Assert.assertFalse(overridable.isOrgOnly());
        final SettingDef orgOnly = plain.withOrgOnly();
        Assert.assertTrue(orgOnly.isOrgOverridable(), "org-only is a kind of overridable");
        Assert.assertTrue(orgOnly.isOrgOnly());
        // Everything else survives the marking: same key, type, default and copy.
        Assert.assertEquals(orgOnly.getName(), "x.plain");
        Assert.assertEquals(orgOnly.getDefaultValue(), "d");
        Assert.assertEquals(orgOnly.getLabel(), "label");
        Assert.assertEquals(orgOnly.getDescription(), "desc");
        Assert.assertEquals(orgOnly.getType(), Config.Type.STRING);
    }

    @Test
    public void theRegistryMarksExactlyTheAgreedSet() {
        final List<String> names = KnownSettings.orgOverridable().stream().map(SettingDef::getName).toList();
        Assert.assertEquals(names, List.of("site.org.name", "site.analytics.id",
                "site.theme.palette", "site.logo.url", "site.favicon.url", "site.ogImage.url",
                "site.background.url", "site.footer.title", "site.footer.text",
                "site.contact.name", "site.contact.phone", "site.donate.url",
                "home.photos.windowDays", "home.photos.minCount", "home.countdown.soonDays",
                "chat.reactions.palette", "chat.background.colors", "chat.background.image", "reg.allowEdits"),
                "in page order; a new org-overridable setting is a product decision, so update this list");
        Assert.assertTrue(KnownSettings.SITE_ANALYTICS_ID.isOrgOnly(), "the analytics id is org-explicit");
        for (final SettingDef def : KnownSettings.sections().stream()
                .filter(section -> section.getTitle().equals(KnownSettings.BRANDING_SECTION))
                .findFirst().orElseThrow().getSettings()) {
            Assert.assertTrue(def.isOrgOnly(), def.getName() + ": an org site never inherits the shared look");
            Assert.assertEquals(def.getDefaultValue(), "", def.getName() + ": blank is the neutral default");
            Assert.assertEquals(def.isHttpUrl(), def.getName().endsWith(".url"),
                    def.getName() + ": every *.url branding value is validated as an http(s) URL");
        }
        Assert.assertFalse(KnownSettings.SITE_ORG_NAME.isOrgOnly());
        Assert.assertTrue(KnownSettings.findOrgOverridable("reg.allowEdits").isPresent());
        Assert.assertTrue(KnownSettings.findOrgOverridable("chat.mail.baseUrl").isEmpty(),
                "a declared but site-only key is not overridable");
        Assert.assertTrue(KnownSettings.findOrgOverridable("no.such").isEmpty());
        Assert.assertTrue(KnownSettings.findOrgOverridable(null).isEmpty());
    }

    @Test
    public void choicesAreOrderedImmutableAndSurviveTheOtherMarkings() {
        final SettingDef free = new SettingDef("x.free", Config.Type.STRING, "", "label", "desc");
        Assert.assertFalse(free.hasChoices());
        Assert.assertEquals(free.getChoices(), List.of());
        Assert.assertTrue(free.allows("anything"), "free text admits everything");
        Assert.assertFalse(free.isHttpUrl());

        final SettingDef menu = free.withChoices("blue", "avocado", "red");
        Assert.assertTrue(menu.hasChoices());
        Assert.assertEquals(menu.getChoices(), List.of("blue", "avocado", "red"), "declaration order, unsorted");
        Assert.assertTrue(menu.allows("avocado"));
        Assert.assertFalse(menu.allows("Avocado"), "exact: the value becomes a stylesheet path");
        Assert.assertFalse(menu.allows("pink"));
        Assert.assertThrows(UnsupportedOperationException.class, () -> menu.getChoices().add("pink"));
        Assert.assertFalse(free.hasChoices(), "withChoices is a copy, not a mutation");

        Assert.assertEquals(menu.withOrgOnly().getChoices(), menu.getChoices(), "the marking keeps the menu");
        Assert.assertTrue(menu.withOrgOnly().isOrgOnly());
        Assert.assertEquals(menu.withOrgOverride().getChoices(), menu.getChoices());
        Assert.assertEquals(free.withOrgOnly().withChoices("a").getChoices(), List.of("a"));
        Assert.assertTrue(free.withOrgOnly().withChoices("a").isOrgOnly(), "and the menu keeps the marking");

        final SettingDef url = free.withHttpUrl();
        Assert.assertTrue(url.isHttpUrl());
        Assert.assertTrue(url.withOrgOnly().isHttpUrl());
        Assert.assertTrue(url.withOrgOverride().isHttpUrl());
        Assert.assertTrue(free.withOrgOnly().withHttpUrl().isOrgOnly());
        Assert.assertFalse(free.isHttpUrl(), "withHttpUrl is a copy too");
        Assert.assertEquals(KnownSettings.SITE_THEME_PALETTE.getChoices(), KnownSettings.THEME_PALETTES);
    }

    @Test
    public void aBrandingSettingResolvesOnTheOrgHostOnlyAndNeverFromTheSiteRow() throws java.io.IOException {
        final SettingDef def = KnownSettings.SITE_THEME_PALETTE;
        final Organization org = new Organization();
        org.setName("Palette " + RandomData.genAlpha(6));
        org.setSlug("palette" + RandomData.genAlpha(6).toLowerCase(java.util.Locale.ROOT));
        Assert.assertTrue(DAO.getInstance().saveOrganization(org));
        final SiteContext orgSite = SiteContext.org(org.getId(), org.getSlug(), org.getSlug() + ".unitetrip.com");
        try {
            siteRow(def, "red");
            Assert.assertEquals(onSite(orgSite, () -> config.getString(def)), "",
                    "an org without a palette gets the NEUTRAL default, never the site row's palette");
            Assert.assertEquals(config.getString(def), "red", "the site rung itself still reads its row");

            org.getSettingsOverrides().put(def.getName(), "green");
            Assert.assertTrue(DAO.getInstance().saveOrganization(org));
            Assert.assertEquals(onSite(orgSite, () -> config.getString(def)), "green");
            Assert.assertEquals(onSite(orgSite, () -> config.getString(def.getName())), "green",
                    "the by-name page entry point too");
            Assert.assertEquals(onSite(SiteContext.shared("visitqueenofpeace.com"), () -> config.getString(def)),
                    "red", "a shared host never sees an org's palette");
            Assert.assertEquals(onSite(SiteContext.marketing("unitetrip.com"), () -> config.getString(def)),
                    "red");
        } finally {
            siteRow(def, null);
        }
    }

    @Test
    public void anOrganizationAnswersItsOverrideOnlyWhenNonBlank() {
        final Organization org = new Organization();
        Assert.assertNull(org.settingOverride("site.org.name"), "nothing stored: inherit");
        Assert.assertNull(org.settingOverride(null));
        org.getSettingsOverrides().put("site.org.name", "  Acme  ");
        Assert.assertEquals(org.settingOverride("site.org.name"), "Acme", "trimmed");
        org.getSettingsOverrides().put("site.org.name", "   ");
        Assert.assertNull(org.settingOverride("site.org.name"), "a blank override is inherit, never ''");
    }

    @Test
    public void explicitOrgResolutionWalksOverrideThenSiteThenDefault() {
        final SettingDef def = KnownSettings.HOME_COUNTDOWN_SOON_DAYS;
        final Organization org = new Organization();
        try {
            Assert.assertEquals(config.getInt(def, null), def.intDefault(), "no org, no row: the default");
            Assert.assertEquals(config.getInt(def, org), def.intDefault(), "org without override: default");

            siteRow(def, "77");
            Assert.assertEquals(config.getInt(def, null), 77, "the site rung");
            Assert.assertEquals(config.getInt(def, org), 77, "an org without an override inherits the site");
            Assert.assertEquals(config.siteString(def), "77");

            org.getSettingsOverrides().put(def.getName(), "12");
            Assert.assertEquals(config.getInt(def, org), 12, "the org's override wins");
            Assert.assertEquals(config.getString(def, org), "12");
            Assert.assertEquals(config.getLong(def, org), 12L);
            Assert.assertEquals(config.siteString(def), "77", "siteString ignores every org");

            org.getSettingsOverrides().put(def.getName(), " ");
            Assert.assertEquals(config.getInt(def, org), 77, "a blank override inherits");

            org.getSettingsOverrides().put(def.getName(), "lots");
            Assert.assertEquals(config.getInt(def, org), def.intDefault(),
                    "an unparseable override degrades to the compiled default, like an unparseable row");
        } finally {
            siteRow(def, null);
        }
    }

    @Test
    public void booleansResolveThroughTheLadderToo() {
        final Organization org = new Organization();
        Assert.assertTrue(config.getBoolean(KnownSettings.REG_ALLOW_EDITS, org), "shipped default is true");
        org.getSettingsOverrides().put(KnownSettings.REG_ALLOW_EDITS.getName(), "no");
        Assert.assertFalse(config.getBoolean(KnownSettings.REG_ALLOW_EDITS, org));
    }

    @Test
    public void aSiteOnlySettingIgnoresTheOrgEntirely() {
        final Organization org = new Organization();
        org.getSettingsOverrides().put(KnownSettings.CHAT_MAIL_BASE_URL.getName(), "https://evil.example");
        Assert.assertEquals(config.getString(KnownSettings.CHAT_MAIL_BASE_URL, org),
                KnownSettings.CHAT_MAIL_BASE_URL.getDefaultValue(),
                "a stray override of a site-only key (never writable through the UI) must be inert");
    }

    @Test
    public void anOrgOnlySettingNeverInheritsTheSiteRow() {
        final SettingDef def = KnownSettings.SITE_ANALYTICS_ID;
        final Organization org = new Organization();
        try {
            siteRow(def, "G-SHARED");
            Assert.assertEquals(config.getString(def, null), "G-SHARED", "the shared site reports as before");
            Assert.assertEquals(config.getString(def, org), "",
                    "an org with no id of its own gets the DEFAULT (blank), never the site's property");
            org.getSettingsOverrides().put(def.getName(), "G-ACME");
            Assert.assertEquals(config.getString(def, org), "G-ACME");
        } finally {
            siteRow(def, null);
        }
    }

    @Test
    public void aRequestOnAnOrgHostResolvesAutomaticallyAndOtherHostsDoNot() throws java.io.IOException {
        final SettingDef def = KnownSettings.SITE_ORG_NAME;
        final Organization org = new Organization();
        org.setName("Ladder " + RandomData.genAlpha(6));
        org.setSlug("ladder" + RandomData.genAlpha(6).toLowerCase(java.util.Locale.ROOT));
        org.getSettingsOverrides().put(def.getName(), "Acme On Its Site");
        Assert.assertTrue(DAO.getInstance().saveOrganization(org));
        final String siteValue = config.getString(def);

        final SiteContext orgSite = SiteContext.org(org.getId(), org.getSlug(), org.getSlug() + ".unitetrip.com");
        Assert.assertEquals(onSite(orgSite, () -> config.getString(def)), "Acme On Its Site",
                "the SettingDef overloads walk the ladder on the org's host");
        Assert.assertEquals(onSite(orgSite, () -> config.getString(def.getName())), "Acme On Its Site",
                "the by-name page entry point too");
        Assert.assertEquals(onSite(orgSite, () -> config.siteString(def)), siteValue,
                "siteString is the site rung even on the org host");
        Assert.assertEquals(onSite(orgSite, () -> config.getString(KnownSettings.CHAT_MAIL_BASE_URL)),
                KnownSettings.CHAT_MAIL_BASE_URL.getDefaultValue(), "site-only settings are untouched");

        Assert.assertEquals(onSite(SiteContext.shared("visitqueenofpeace.com"), () -> config.getString(def)),
                siteValue, "a shared host never sees an org's override");
        Assert.assertEquals(onSite(SiteContext.marketing("unitetrip.com"), () -> config.getString(def)),
                siteValue);
        Assert.assertEquals(config.getString(def), siteValue, "no bound request: the site rung");

        final SiteContext ghost = SiteContext.org(Organization.Id.newInstance(), "ghost", "ghost.unitetrip.com");
        Assert.assertEquals(onSite(ghost, () -> config.getString(def)), siteValue,
                "an org host whose org row cannot be read degrades to the site value, never a broken page");
    }

    private static <T> T onSite(final SiteContext site, final ScopedValue.CallableOp<T, RuntimeException> body) {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site)).call(body);
    }

    /** Writes (value) or clears (null) the SITE row of a setting; tests restore what they touch. */
    private void siteRow(final SettingDef def, final String value) {
        Assert.assertTrue(config.save(new Config(def.getName(), value, def.getType(), "test",
                LocalDateTime.now(), "tester"), "tester"));
    }
}
