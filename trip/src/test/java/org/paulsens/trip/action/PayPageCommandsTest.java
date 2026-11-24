package org.paulsens.trip.action;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.paulsens.trip.action.TenancyFixtures.reread;

/**
 * The org-aware "How to pay" page: the one decision both pay pages redirect on, whose page a request is for on
 * each kind of site, the starter text of an empty page, and who may edit it (the existing content rule).
 */
public class PayPageCommandsTest {

    private final PayPageCommands pay = new PayPageCommands(TenancyFixtures::siteAdminCaller);

    @BeforeClass
    public void seed() {
        DAO.getInstance();
        FakeData.addFakeData();
    }

    private static <T> T onSite(final SiteContext site, final ScopedValue.CallableOp<T, Exception> body)
            throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site)).call(body);
    }

    private static SiteContext siteOf(final Organization org) {
        return SiteContext.org(org.getId(), "site", "site.example.test");
    }

    private static Trip payable(final Trip trip) throws IOException {
        trip.getPaymentConfig().setProcessorConfigId("processor-" + TenancyFixtures.unique());
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private static List<ContentInstance> stored(final String key) {
        return DAO.getInstance().getContentForSection(key, Cached.NO);
    }

    // ------------------------------------------------------------------ the decision

    @Test
    public void aTripIsPaidInTheAppOnlyWhenItsPaymentConfigurationCanTakeAPayment() throws IOException {
        final Organization org = TenancyFixtures.org("PayDecision");
        final Trip plain = TenancyFixtures.tripOwnedBy(org);
        final Trip online = payable(TenancyFixtures.tripOwnedBy(org));
        final OrgCommands orgs = TenancyFixtures.asSiteAdmin();

        Assert.assertFalse(orgs.effectivePaymentConfig(plain).isPayable(), "the fixture: nothing configured");
        Assert.assertEquals(pay.destination(plain.getId()), PayPageCommands.Destination.HOW_TO);
        Assert.assertFalse(pay.inApp(plain.getId()));
        Assert.assertEquals(pay.urlFor(plain.getId()), "/trip/pay.jsf?trip=" + plain.getId());

        Assert.assertEquals(pay.destination(online.getId()), PayPageCommands.Destination.IN_APP);
        Assert.assertTrue(pay.inApp(" " + online.getId() + " "), "a pasted id is forgiven");
        Assert.assertEquals(pay.urlFor(online.getId()), "/trip/payment.jsf?trip=" + online.getId());
        Assert.assertEquals(pay.inApp(online.getId()), orgs.effectivePaymentConfig(online).isPayable(),
                "the decision IS the payment page's own test, so the two pages cannot bounce each other");
    }

    @Test
    public void noTripAnUnknownTripAndATripThisSiteDoesNotServeAreAllHowTo() throws Exception {
        final Organization mine = TenancyFixtures.org("PayMine");
        final Organization other = TenancyFixtures.org("PayOther");
        final Trip theirs = payable(TenancyFixtures.tripOwnedBy(other));

        for (final String none : new String[] {null, "", "  ", UUID.randomUUID().toString()}) {
            Assert.assertEquals(pay.destination(none), PayPageCommands.Destination.HOW_TO, "'" + none + "'");
        }
        Assert.assertEquals(pay.urlFor(null), "/trip/pay.jsf", "a blank id's parameter is left out");
        Assert.assertEquals(pay.howToUrl(" "), "/trip/pay.jsf");
        Assert.assertEquals(pay.howToUrlForOrg(null), "/trip/pay.jsf");
        Assert.assertEquals(pay.howToUrlForOrg(" " + mine.getId().getValue()), "/trip/pay.jsf?org="
                + mine.getId().getValue());
        // The anonymous /pay/ page hands its own parameter in: it stays ONE value, never a second parameter.
        Assert.assertEquals(pay.howToUrl("x&org=" + other.getId().getValue()), "/trip/pay.jsf?trip=x%26org%3D"
                + other.getId().getValue());
        Assert.assertEquals(pay.howToUrlForOrg("a b#c"), "/trip/pay.jsf?org=a+b%23c");

        Assert.assertTrue(pay.inApp(theirs.getId()), "where the trip is served it is paid in the app");
        Assert.assertFalse(onSite(siteOf(mine), () -> pay.inApp(theirs.getId())),
                "on another organization's site the trip does not exist, payable or not");
    }

    // ------------------------------------------------------------------ whose page

    @Test
    public void thePageIsTheTripsOrganizationsElseTheNamedOneElseTheSitesOwn() throws Exception {
        final Organization org = TenancyFixtures.org("PayWhose");
        final Organization other = TenancyFixtures.org("PayElse");
        final String orgId = org.getId().getValue();
        final String otherId = other.getId().getValue();
        final Trip trip = TenancyFixtures.tripOwnedBy(org);
        final String slug = "pay" + TenancyFixtures.unique().toLowerCase(Locale.ROOT);
        Assert.assertTrue(TenancyFixtures.asSiteAdmin().saveOrgEdits(otherId, other.getName(), null, null, null,
                null, slug));

        Assert.assertEquals(pay.orgIdFor(trip.getId(), null), orgId);
        Assert.assertEquals(pay.orgIdFor(trip.getId(), otherId), orgId, "the trip decides when both are given");
        Assert.assertEquals(pay.orgIdFor(null, " " + otherId + " "), otherId);
        Assert.assertEquals(pay.orgIdFor("no-such-trip", slug.toUpperCase(Locale.ROOT)), otherId,
                "an organization may be named by its site's slug");
        Assert.assertNull(pay.orgIdFor(null, "no-such-org"));
        Assert.assertNull(pay.orgIdFor(null, null), "no context on a shared host: no organization's page");
        Assert.assertNull(pay.orgIdFor(" ", " "));

        Assert.assertEquals(onSite(siteOf(org), () -> pay.orgIdFor(null, null)), orgId, "an org's own host");
        Assert.assertEquals(onSite(siteOf(org), () -> pay.orgIdFor(null, otherId)), orgId,
                "another organization's page is not served there: the host's own");
        Assert.assertEquals(onSite(siteOf(org), () -> pay.orgIdFor(null, slug)), orgId);
        final Organization.Id ghost = Organization.Id.from(UUID.randomUUID().toString());
        Assert.assertNull(onSite(SiteContext.org(ghost, "ghost", "ghost.example.test"),
                () -> pay.orgIdFor(trip.getId(), orgId)), "a site whose organization is gone shows nobody's");
    }

    @Test
    public void anOrglessTripHasNoOrganizationToPay() throws IOException {
        final Trip legacy = Trip.builder().id(UUID.randomUUID().toString()).title("Legacy").build();
        Assert.assertTrue(DAO.getInstance().saveTrip(legacy));
        final Organization named = TenancyFixtures.org("PayNamed");

        Assert.assertNull(pay.orgIdFor(legacy.getId(), null));
        Assert.assertEquals(pay.orgIdFor(legacy.getId(), named.getId().getValue()), named.getId().getValue(),
                "the organization the link names is the fallback");
    }

    @Test
    public void theKeyIsOnlyGivenForAnOrganizationThisSiteReaches() throws Exception {
        final Organization org = TenancyFixtures.org("PayKey");
        final Organization other = TenancyFixtures.org("PayKeyOther");
        final String orgId = org.getId().getValue();
        final String key = "page:org:" + orgId + ":pay";

        Assert.assertEquals(pay.payPageKey(orgId), key);
        Assert.assertEquals(pay.orgName(orgId), org.getName());
        Assert.assertEquals(OrgPageBootstrap.orgOf(key), org.getId(), "the key names the org its editors come from");
        for (final String none : new String[] {null, "", " ", "no-such-org"}) {
            Assert.assertNull(pay.payPageKey(none));
            Assert.assertNull(pay.ensurePage(none));
            Assert.assertEquals(pay.orgName(none), "");
        }
        Assert.assertEquals(onSite(siteOf(org), () -> pay.payPageKey(orgId)), key);
        Assert.assertNull(onSite(siteOf(other), () -> pay.payPageKey(orgId)),
                "another organization's site: exactly like an organization that does not exist");
        Assert.assertNull(onSite(siteOf(other), () -> pay.ensurePage(orgId)));
        Assert.assertEquals(onSite(siteOf(other), () -> pay.orgName(orgId)), "");
        Assert.assertTrue(stored(key).isEmpty(), "and nothing is seeded from a host that does not reach it");
    }

    // ------------------------------------------------------------------ the starter text

    @Test
    public void aReaderGetsTheVersionInTheirLanguageOnceOneIsWritten() throws Exception {
        final Organization org = TenancyFixtures.org("PayVersions");
        final String orgId = org.getId().getValue();
        Assert.assertTrue(TenancyFixtures.asSiteAdmin().saveOrgSettings(orgId, Map.of("site.languages", "en,es")));
        final String key = OrgPageBootstrap.payPageKey(org.getId());
        final SiteContext site = siteOf(org);
        Assert.assertEquals(reading(site, Locale.of("es"), () -> pay.ensurePage(orgId)), key,
                "nothing written in Spanish: the whole page itself");
        Assert.assertEquals(stored(key).size(), 1, "the starter text went to the page's own key");
        Assert.assertEquals(stored(key + "@es").size(), 0, "never to a version");

        final ContentInstance spanish = new ContentInstance("pay-es-" + UUID.randomUUID(), key + "@es", "Pagar",
                "text-only", 1, new HashMap<>(Map.of("body", "<p>Pague por cheque.</p>")), null, 0, 0, null, "t");
        Assert.assertTrue(DAO.getInstance().saveContent(spanish, 5));
        try {
            Assert.assertEquals(reading(site, Locale.of("es"), () -> pay.ensurePage(orgId)), key + "@es");
            Assert.assertEquals(reading(site, Locale.ENGLISH, () -> pay.ensurePage(orgId)), key);
            Assert.assertEquals(reading(site, Locale.of("hr"), () -> pay.ensurePage(orgId)), key,
                    "a language the site does not offer reads the page itself");
            Assert.assertEquals(stored(key + "@es").size(), 1, "the version's own rows, untouched");
        } finally {
            DAO.getInstance().deleteContent(spanish.getId());
        }
    }

    private static <T> T reading(final SiteContext site, final Locale locale,
            final ScopedValue.CallableOp<T, Exception> body) throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site).withLocale(locale))
                .call(body);
    }

    @Test
    public void aPageGetsItsStarterTextOnceAndAnAuthoredPageIsLeftAlone() throws IOException {
        final Organization org = TenancyFixtures.org("PaySeed");
        final Organization fresh = reread(org);
        fresh.setContactEmail("office." + TenancyFixtures.unique().toLowerCase(Locale.ROOT) + "@example.org");
        Assert.assertTrue(DAO.getInstance().saveOrganization(fresh));
        final String orgId = org.getId().getValue();
        final String key = pay.payPageKey(orgId);

        Assert.assertNull(reread(org).getPayPageSeededAt(), "not seeded until its first render");
        // The audit trail as the console sink prints it (SitePageCommandsTest's way): the seed is in the
        // organization's trail, once, as the system's doing, since any visitor's render may have caused it.
        final PrintStream original = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            Assert.assertEquals(pay.ensurePage(orgId), key);
            Assert.assertEquals(pay.ensurePage(orgId), key);
        } finally {
            System.setOut(original);
        }
        final List<String> audited = captured.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.contains("| ORGANIZATION | How to pay page seeded") && line.contains(key))
                .filter(line -> Character.isDigit(line.charAt(0))) // not a sink's prefixed fallback copy
                .toList();
        Assert.assertEquals(audited.size(), 1, "one record for the one seed: " + audited);
        Assert.assertTrue(audited.get(0).contains(AuditActor.system().email()), audited.get(0));
        final List<ContentInstance> seeded = stored(key);
        Assert.assertEquals(seeded.size(), 1);
        Assert.assertNotNull(reread(org).getPayPageSeededAt(), "the org row records the seeding");
        final String body = seeded.get(0).getValues().get("body");
        Assert.assertTrue(body.contains("<h2>How to pay " + org.getName() + "</h2>"), body);
        Assert.assertTrue(body.contains("mailto:" + fresh.getContactEmail()), body);

        Assert.assertEquals(pay.ensurePage(orgId), key);
        Assert.assertEquals(stored(key).size(), 1, "a second visit seeds nothing more");

        final ContentInstance edited = stored(key).get(0);
        edited.getValues().put("body", "<p>Pay by check.</p>");
        Assert.assertTrue(DAO.getInstance().saveContent(edited, 5));
        Assert.assertEquals(pay.ensurePage(orgId), key);
        Assert.assertEquals(stored(key).get(0).getValues().get("body"), "<p>Pay by check.</p>",
                "an editor's text is never overwritten");
    }

    @Test
    public void aPageItsEditorEmptiedStaysEmptyAndALaterAnonymousRenderWritesNothing() {
        final Organization org = TenancyFixtures.org("PayEmptied");
        final String orgId = org.getId().getValue();
        final String key = pay.ensurePage(orgId);
        Assert.assertEquals(stored(key).size(), 1, "the first render seeds");
        Assert.assertTrue(DAO.getInstance().deleteContent(stored(key).get(0).getId()));
        Assert.assertTrue(stored(key).isEmpty());
        final Organization recorded = reread(org);

        // A signed-out visitor's GET: the bean exactly as the page builds it, nobody signed in.
        final PayPageCommands anonymous = new PayPageCommands();
        Assert.assertEquals(anonymous.ensurePage(orgId), key);
        Assert.assertEquals(pay.ensurePage(orgId), key);

        Assert.assertTrue(stored(key).isEmpty(), "emptied on purpose: the starter text does not come back");
        Assert.assertEquals(reread(org).getVersion(), recorded.getVersion(), "and the org row was not written");
        Assert.assertEquals(reread(org).getPayPageSeededAt(), recorded.getPayPageSeededAt());
    }

    @Test
    public void thePlatformsOwnOrganizationHasNoPayPage() throws Exception {
        final String platformId = FakeData.PLATFORM_ORG_ID;
        final Organization platform = DAO.getInstance().getOrganization(Organization.Id.from(platformId), Cached.NO)
                .orElseThrow();
        Assert.assertTrue(platform.isPlatform(), "the fixture");
        final String key = OrgPageBootstrap.payPageKey(platform.getId());
        final SiteContext platformSite = SiteContext.org(platform.getId(), Organization.PLATFORM_SLUG,
                "www.example.test");

        Assert.assertNull(pay.payPageKey(platformId));
        Assert.assertNull(pay.ensurePage(platformId));
        Assert.assertEquals(pay.orgName(platformId), "");
        Assert.assertNull(pay.orgIdFor(null, platformId), "named by id");
        Assert.assertNull(pay.orgIdFor(null, Organization.PLATFORM_SLUG), "named by its slug");
        Assert.assertNull(onSite(platformSite, () -> pay.orgIdFor(null, null)), "nor on its own site");
        Assert.assertNull(onSite(platformSite, () -> pay.ensurePage(platformId)));
        Assert.assertTrue(stored(key).isEmpty(), "nothing is seeded for it");
        Assert.assertNull(DAO.getInstance().getOrganization(platform.getId(), Cached.NO).orElseThrow()
                .getPayPageSeededAt());
    }

    @Test
    public void aPageAuthoredFirstIsAdoptedAndAnOrganizationWithNoAddressGetsNoAddressSentence() throws IOException {
        final Organization authoredOrg = TenancyFixtures.org("PayAuthored");
        final String authoredKey = OrgPageBootstrap.payPageKey(authoredOrg.getId());
        Assert.assertTrue(DAO.getInstance().saveContent(new ContentInstance("pay-" + UUID.randomUUID(), authoredKey,
                "Ours", "text-only", 1, new HashMap<>(Map.of("body", "<p>Ours</p>")), null, 0, 0, null, "someone"),
                5));
        Assert.assertEquals(pay.ensurePage(authoredOrg.getId().getValue()), authoredKey);
        Assert.assertEquals(stored(authoredKey).size(), 1);
        Assert.assertEquals(stored(authoredKey).get(0).getValues().get("body"), "<p>Ours</p>");
        Assert.assertNotNull(reread(authoredOrg).getPayPageSeededAt(), "adopted as it is, and seeded from now on");

        final Organization silent = TenancyFixtures.org("PaySilent");
        final Organization fresh = reread(silent);
        fresh.setContactEmail(null);
        Assert.assertTrue(DAO.getInstance().saveOrganization(fresh));
        final String key = pay.ensurePage(silent.getId().getValue());
        final String body = stored(key).get(0).getValues().get("body");
        Assert.assertFalse(body.contains("mailto"), body);
        Assert.assertTrue(body.contains("please contact the organization."), body);
    }

    @Test
    public void nothingIsSeededWhileTheStarterTemplateIsMissing() {
        Assert.assertNull(PayPageCommands.missingStarter(id -> true));
        Assert.assertEquals(PayPageCommands.missingStarter(id -> false), "text-only");
        final Organization org = TenancyFixtures.org("PayNoTemplate");
        final PayPageCommands uninstalled = new PayPageCommands(TenancyFixtures::siteAdminCaller) {
            @Override
            protected boolean starterInstalled(final String templateId) {
                return false;
            }
        };

        final String key = uninstalled.ensurePage(org.getId().getValue());

        Assert.assertEquals(key, pay.payPageKey(org.getId().getValue()), "the page still has its key and renders");
        Assert.assertTrue(stored(key).isEmpty(), "a row pinned to a missing template renders as nothing");
        Assert.assertNull(reread(org).getPayPageSeededAt(), "not recorded, so the install is followed by a seed");
        Assert.assertEquals(pay.ensurePage(org.getId().getValue()), key);
        Assert.assertEquals(stored(key).size(), 1, "seeded on the first visit after the template is installed");
    }

    @Test
    public void thePaymentPagesBalanceLinkNamesTheTripsOrganizationNeverTheTrip() {
        final Person traveler = TenancyFixtures.savedPerson("PayBack");
        final Organization org = TenancyFixtures.org("PayBack", traveler);
        final Trip trip = TenancyFixtures.tripOwnedBy(org, traveler);
        final String orgId = org.getId().getValue();

        Assert.assertEquals(pay.balanceUrl(trip.getId(), false), "/trip/transactions.jsf?org=" + orgId);
        Assert.assertEquals(pay.balanceUrl(trip.getId(), true), "/trip/transactions.jsf?scope=family&org=" + orgId,
                "a family manager gets the family's statement: whoever they paid for is on it");
        Assert.assertFalse(pay.balanceUrl(trip.getId(), true).contains("trip="),
                "the ?trip= form sends a payer who is not on the roster home");
        Assert.assertEquals(pay.balanceUrl(UUID.randomUUID().toString(), true), "/trip/transactions.jsf");
        Assert.assertEquals(pay.balanceUrl(null, false), "/trip/transactions.jsf");
    }

    /** A bean whose clock, template check and organization save a test steers, counting each seed attempt. */
    private static final class SteeredPay extends PayPageCommands {
        private Instant clock = Instant.parse("2026-11-20T10:00:00Z");
        private boolean templateInstalled = true;
        private boolean orgSaves = true;
        private int attempts;

        SteeredPay() {
            super(TenancyFixtures::siteAdminCaller);
        }

        @Override
        protected Instant now() {
            return clock;
        }

        @Override
        protected boolean starterInstalled(final String templateId) {
            attempts++;
            return templateInstalled;
        }

        @Override
        protected boolean saveOrg(final Organization org) throws IOException {
            return orgSaves && super.saveOrg(org);
        }
    }

    @Test
    public void aSeedThatCannotSucceedYetIsNotRetriedOnEveryAnonymousHit() {
        final Organization org = TenancyFixtures.org("PayBounded");
        final String orgId = org.getId().getValue();
        final SteeredPay steered = new SteeredPay();
        steered.templateInstalled = false;

        for (int hit = 0; hit < 25; hit++) {
            Assert.assertNotNull(steered.ensurePage(orgId), "the page renders on every hit");
        }
        Assert.assertEquals(steered.attempts, 1, "one try, then none until the retry interval has passed");

        steered.clock = steered.clock.plus(PayPageCommands.SEED_RETRY_AFTER).minusSeconds(1);
        steered.ensurePage(orgId);
        Assert.assertEquals(steered.attempts, 1);
        steered.clock = steered.clock.plusSeconds(1);
        steered.ensurePage(orgId);
        Assert.assertEquals(steered.attempts, 2, "tried again once the interval has passed");

        // The install happened: the next due try seeds, and nothing is tried after that.
        steered.templateInstalled = true;
        steered.clock = steered.clock.plus(PayPageCommands.SEED_RETRY_AFTER);
        final String key = steered.ensurePage(orgId);
        Assert.assertEquals(stored(key).size(), 1);
        Assert.assertNotNull(reread(org).getPayPageSeededAt());
        Assert.assertEquals(new SteeredPay().starterBody(orgId), stored(key).get(0).getValues().get("body"),
                "the page's fallback text IS the seeded text");
        Assert.assertEquals(steered.starterBody("no-such-organization"), "");
    }

    @Test
    public void theSeedIsAuditedOnlyOnceTheOrganizationRowRecordsIt() {
        final Organization org = TenancyFixtures.org("PayUnrecorded");
        final String orgId = org.getId().getValue();
        final SteeredPay steered = new SteeredPay();
        steered.orgSaves = false;
        final PrintStream original = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        final String key;
        try {
            key = steered.ensurePage(orgId);
            for (int hit = 0; hit < 10; hit++) {
                steered.ensurePage(orgId);
            }
            Assert.assertEquals(steered.attempts, 1, "a save that keeps failing is not hammered either");
            Assert.assertEquals(seedRecords(captured, key), 0L, "nothing is recorded, so nothing is audited");
            Assert.assertNull(reread(org).getPayPageSeededAt());

            steered.orgSaves = true;
            steered.clock = steered.clock.plus(PayPageCommands.SEED_RETRY_AFTER);
            steered.ensurePage(orgId);
            steered.ensurePage(orgId);
        } finally {
            System.setOut(original);
        }
        Assert.assertNotNull(reread(org).getPayPageSeededAt());
        Assert.assertEquals(stored(key).size(), 1, "the retry rewrote the one starter row");
        Assert.assertEquals(seedRecords(captured, key), 1L, "one record, when the seed was recorded");
    }

    private static long seedRecords(final ByteArrayOutputStream captured, final String key) {
        return captured.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.contains("| ORGANIZATION | How to pay page seeded") && line.contains(key))
                .filter(line -> Character.isDigit(line.charAt(0))) // not a sink's prefixed fallback copy
                .count();
    }

    @Test
    public void theBeanAsThePagesBuildItAnswersWithoutACaller() {
        final PayPageCommands bean = new PayPageCommands();
        final Organization org = TenancyFixtures.org("PayBean");

        Assert.assertEquals(bean.destination(null), PayPageCommands.Destination.HOW_TO);
        Assert.assertEquals(bean.payPageKey(org.getId().getValue()), "page:org:" + org.getId().getValue() + ":pay",
                "a signed-out visitor's render: nothing here asks who is looking");
    }

    // ------------------------------------------------------------------ who edits it

    @Test
    public void thePageIsEditedByTheExistingContentRule() {
        final Person editor = TenancyFixtures.savedPerson("PayEditor");
        final Person outsider = TenancyFixtures.savedPerson("PayOutsider");
        final Person member = TenancyFixtures.savedPerson("PayMember");
        final Organization org = TenancyFixtures.org("PayEdit", editor, member);
        final Organization other = TenancyFixtures.org("PayEditOther", outsider);
        final OrgCommands admin = TenancyFixtures.asSiteAdmin();
        Assert.assertTrue(admin.grantOrgPrivilege(org.getId().getValue(), editor.getId(),
                PrivilegeCommands.CONTENT_ADMIN));
        Assert.assertTrue(admin.grantOrgPrivilege(other.getId().getValue(), outsider.getId(),
                PrivilegeCommands.CONTENT_ADMIN));
        final String key = pay.payPageKey(org.getId().getValue());

        Assert.assertTrue(contentAs(editor).canEditPage(key, editor.getId()), "contentAdmin of this organization");
        Assert.assertFalse(contentAs(outsider).canEditPage(key, outsider.getId()), "another organization's editor");
        Assert.assertFalse(contentAs(member).canEditPage(key, member.getId()), "membership is not an edit right");
        final ContentCommands site = new ContentCommands();
        site.setCallerSource(TenancyFixtures::siteAdminCaller);
        Assert.assertTrue(site.canEditPage(key, TenancyFixtures.siteAdminCaller().personId()), "a site admin");
    }

    private static ContentCommands contentAs(final Person person) {
        final ContentCommands content = new ContentCommands();
        content.setCallerSource(() -> TenancyFixtures.callerFor(person));
        return content;
    }

    @Test
    public void theLocalCfpwOrganizationHasDemoInstructionsTheFirstVisitAdopts() throws Exception {
        // Local mode's CFPW page is authored by the seed (FakeData), in the starter row's own id, so the page is
        // demoable and its webtests have real content: the first visit must adopt it, never add a starter beside it.
        final Organization.Id cfpw = Organization.Id.from(FakeData.CFPW_ORG_ID);
        final String key = OrgPageBootstrap.payPageKey(cfpw);
        Assert.assertEquals(onSite(SiteContext.shared("localhost"), () -> pay.ensurePage(FakeData.CFPW_ORG_ID)), key);
        final List<ContentInstance> rows = stored(key);
        Assert.assertEquals(rows.size(), 1, "one row, the starter's id: " + rows);
        Assert.assertEquals(rows.getFirst().getId(), OrgPageBootstrap.payRowId(cfpw));
        final String body = String.valueOf(rows.getFirst().getValues().get("body"));
        Assert.assertTrue(body.contains("pay@example.com"), body);
        Assert.assertTrue(body.contains("Every detail below is made up"), "it says it is a demo: " + body);
        Assert.assertFalse(body.toLowerCase(Locale.ROOT).contains("centerforpeacewest"),
                "nothing real in a public repository");
    }
}
