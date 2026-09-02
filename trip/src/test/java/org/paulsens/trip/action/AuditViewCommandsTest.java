package org.paulsens.trip.action;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditEvent;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.AuditScope;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The filter-parsing and CSV rules behind the admin audit page.
 *
 * <p>Both of the interesting cases here fail SILENTLY in production if they regress: a mis-parsed filter shows
 * the wrong rows rather than erroring, and a badly escaped CSV opens in a spreadsheet with columns quietly
 * shifted.
 */
public class AuditViewCommandsTest {

    private static final Person.Id ACME_AUDITOR = Person.Id.from("acme-auditor");
    private static final Person.Id GLOBAL_AUDITOR = Person.Id.from("global-auditor");
    private static final Person.Id NOBODY = Person.Id.from("no-audit-grant");
    private static final SiteContext SHARED = SiteContext.shared("localhost");
    private static final SiteContext ACME_SITE =
            SiteContext.org(Organization.Id.from(FakeData.ACME_ORG_ID), "acme", "acme.localhost");
    private static final SiteContext BETA_SITE =
            SiteContext.org(Organization.Id.from(FakeData.BETA_ORG_ID), "beta", "beta.localhost");

    private final AuditViewCommands view = new AuditViewCommands();

    @BeforeClass
    public void seed() {
        DAO.getInstance();
        FakeData.addFakeData();
        final PrivilegeCommands priv = new PrivilegeCommands();
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.AUDIT_ADMIN, "acme audit",
                FakeData.ACME_ORG_ID, List.of(ACME_AUDITOR)));
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.AUDIT_ADMIN, "global audit", null,
                List.of(GLOBAL_AUDITOR)));
    }

    private static <T> T onSite(final SiteContext site, final ScopedValue.CallableOp<T, Exception> body)
            throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site)).call(body);
    }

    private static AuditViewCommands viewAs(final Person.Id who) {
        final AuditViewCommands bean = new AuditViewCommands();
        bean.setCallerSource(() -> TestCallers.person(who));
        return bean;
    }

    // --- whose trail: the gate ---

    @Test
    public void anOrgsOwnHolderReadsItsTrailOnItsSiteAndByKeyAndNothingWider() throws Exception {
        final AuditViewCommands acme = viewAs(ACME_AUDITOR);
        Assert.assertTrue(onSite(ACME_SITE, () -> acme.canView(null)), "the org's site: its own view");
        Assert.assertTrue(onSite(ACME_SITE, () -> acme.canView(FakeData.ACME_ORG_ID)));
        Assert.assertTrue(onSite(SHARED, () -> acme.canView(FakeData.ACME_ORG_ID)), "the dashboard card");
        Assert.assertFalse(onSite(SHARED, () -> acme.canView(null)), "never the shared site's trail");
        Assert.assertFalse(onSite(SHARED, () -> acme.canView(FakeData.BETA_ORG_ID)), "never another tenant's");
        Assert.assertFalse(onSite(BETA_SITE, () -> acme.canView(null)));
        Assert.assertFalse(onSite(BETA_SITE, () -> acme.canView(FakeData.ACME_ORG_ID)),
                "a tenant's site serves no other tenant's trail, whoever asks");
    }

    @Test
    public void globalHoldersAndSiteAdminsReadEverythingExceptAcrossTenantSites() throws Exception {
        final AuditViewCommands global = viewAs(GLOBAL_AUDITOR);
        Assert.assertTrue(onSite(SHARED, () -> global.canView(null)));
        Assert.assertTrue(onSite(SHARED, () -> global.canView(FakeData.ACME_ORG_ID)));
        Assert.assertTrue(onSite(ACME_SITE, () -> global.canView(null)));
        Assert.assertFalse(onSite(ACME_SITE, () -> global.canView(FakeData.BETA_ORG_ID)),
                "Beta's trail is not served from Acme's site even to a global reader");

        final AuditViewCommands admin = new AuditViewCommands();
        admin.setCallerSource(TestCallers::siteAdmin);
        Assert.assertTrue(onSite(SHARED, () -> admin.canView(FakeData.BETA_ORG_ID)));
        Assert.assertTrue(admin.canView(null), "off a request too");
    }

    @Test
    public void nobodyElseReadsAnything() throws Exception {
        final AuditViewCommands none = viewAs(NOBODY);
        Assert.assertFalse(onSite(SHARED, () -> none.canView(null)));
        Assert.assertFalse(onSite(ACME_SITE, () -> none.canView(null)));
        Assert.assertFalse(onSite(SHARED, () -> none.canView(FakeData.ACME_ORG_ID)));
        Assert.assertFalse(AuditViewCommands.canView(null, null), "no caller, no trail");
        Assert.assertFalse(AuditViewCommands.canView(Caller.bound(), FakeData.ACME_ORG_ID),
                "anonymous (off a request, nobody is signed in)");
    }

    // --- whose trail: the scope a read gets ---

    @Test
    public void theScopeFollowsTheKeyThenTheSite() throws Exception {
        Assert.assertEquals(onSite(SHARED, () -> AuditViewCommands.scopeFor(FakeData.ACME_ORG_ID)),
                AuditScope.org(FakeData.ACME_ORG_ID), "a keyed read is that org's, from any host");
        Assert.assertEquals(onSite(ACME_SITE, () -> AuditViewCommands.scopeFor(null)),
                AuditScope.org(FakeData.ACME_ORG_ID), "an org site's own view");
        Assert.assertEquals(AuditViewCommands.scopeFor(null), AuditScope.all(),
                "off a request there is no host to draw a boundary from");

        final AuditScope shared = onSite(SHARED, () -> AuditViewCommands.scopeFor(" "));
        Assert.assertFalse(shared.isOrg());
        Assert.assertTrue(shared.admits(null), "site-level records");
        Assert.assertTrue(shared.admits(FakeData.CFPW_ORG_ID), "the sharing tenant (no site of its own)");
        Assert.assertFalse(shared.admits(FakeData.ACME_ORG_ID), "a hosted org's records stay off the shared host");
        Assert.assertFalse(shared.admits(FakeData.BETA_ORG_ID));
        Assert.assertEquals(AuditViewCommands.hostedOrgIds(), Set.of(FakeData.ACME_ORG_ID, FakeData.BETA_ORG_ID));

        // An unreadable org list must narrow the shared view, never widen it.
        final Set<String> unreadable = AuditViewCommands.hostedOrgIds(() -> {
            throw new IllegalStateException("store down");
        });
        Assert.assertNull(unreadable);
        Assert.assertFalse(AuditScope.shared(unreadable).admits(FakeData.CFPW_ORG_ID));
        Assert.assertTrue(AuditScope.shared(unreadable).admits(null));
    }

    @Test
    public void aKeyedReadOfTheFakeStoreAnswersThatOrgsRecordsOnly() throws Exception {
        // The local audit table is real (FakeData), so a record written on Acme's site reads back through
        // the keyed page and only there.
        final String probe = "probe-" + System.nanoTime() + "@example.com";
        onSite(ACME_SITE, () -> {
            org.paulsens.trip.audit.Audit.builder(AuditAction.LOGIN, AuditOutcome.FAILURE)
                    .actor(probe, null).message("probe").log();
            return null;
        });
        final AuditViewCommands admin = new AuditViewCommands();
        admin.setCallerSource(TestCallers::siteAdmin);
        Assert.assertTrue(waitForRecord(admin, FakeData.ACME_ORG_ID, probe), "the record lands in Acme's trail");
        Assert.assertFalse(admin.getPage(FakeData.BETA_ORG_ID, null, probe, null, null, null, 50).getEvents()
                .stream().anyMatch(e -> probe.equals(e.getActorEmail())), "and not in Beta's");
        Assert.assertTrue(admin.toCsv(FakeData.ACME_ORG_ID, null, probe, null, null, null).contains(probe));
        Assert.assertFalse(admin.getRecent(FakeData.BETA_ORG_ID, 50).getEvents().stream()
                .anyMatch(e -> probe.equals(e.getActorEmail())));
    }

    /** The sink writes on its own thread; the page read may need a moment to see the record. */
    private static boolean waitForRecord(final AuditViewCommands view, final String orgId, final String actor)
            throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (view.getRecent(orgId, 50).getEvents().stream().anyMatch(e -> actor.equals(e.getActorEmail()))) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    @Test
    public void theShortNameIsTheAbbreviationOrElseTheName() {
        Assert.assertEquals(AuditViewCommands.shortNameOf(Organization.builder().name("Acme Inc")
                .abbreviation("Acme").build()), "Acme");
        Assert.assertEquals(AuditViewCommands.shortNameOf(Organization.builder().name("Beta Corp")
                .abbreviation(" ").build()), "Beta Corp");
        Assert.assertEquals(AuditViewCommands.shortNameOf(Organization.builder().name("Gamma").build()), "Gamma");
    }

    @Test
    public void theOrgColumnNamesTheOrgByItsShortLabel() {
        final AuditEvent acme = new AuditEvent(Instant.now(), AuditAction.LOGIN, AuditOutcome.SUCCESS,
                "a@example.com", null, null, null, null, "m", null, FakeData.ACME_ORG_ID);
        Assert.assertEquals(view.orgLabel(acme), "Acme");
        Assert.assertEquals(view.orgLabel(new AuditEvent(Instant.now(), AuditAction.LOGIN, AuditOutcome.SUCCESS,
                "a@example.com", null, null, null, null, "m", null, "no-such-org")), "no-such-org",
                "an unknown org shows its id rather than nothing");
        Assert.assertEquals(view.orgLabel(new AuditEvent(Instant.now(), AuditAction.LOGIN, AuditOutcome.SUCCESS,
                "a@example.com", null, null, null, null, "m", null, null)), "");
        Assert.assertEquals(view.orgLabel(null), "");
    }

    @Test
    public void blankFilterMeansNoFilter() {
        // EL hands an unset dropdown "" rather than null.
        Assert.assertNull(AuditViewCommands.parseAction(""));
        Assert.assertNull(AuditViewCommands.parseAction("   "));
        Assert.assertNull(AuditViewCommands.parseAction(null));
        Assert.assertNull(AuditViewCommands.parseOutcome(""));
    }

    @Test
    public void unrecognisedFilterMeansNoFilter() {
        // AuditAction.from() maps anything unknown to UNKNOWN. As a FILTER that would mean "show only the
        // records we could not classify" -- the opposite of what a stale bookmark or a renamed action intends.
        Assert.assertNull(AuditViewCommands.parseAction("NoSuchAction"),
                "An unrecognised filter must widen to 'any', not narrow to UNKNOWN");
        Assert.assertNull(AuditViewCommands.parseOutcome("NoSuchOutcome"));
    }

    @Test
    public void explicitUnknownIsStillAUsableFilter() {
        // But asking FOR unmapped records is legitimate -- it is how you find what the enum is missing.
        Assert.assertEquals(AuditViewCommands.parseAction("UNKNOWN"), AuditAction.UNKNOWN);
        Assert.assertEquals(AuditViewCommands.parseOutcome("UNKNOWN"), AuditOutcome.UNKNOWN);
    }

    @Test
    public void realFiltersParse() {
        Assert.assertEquals(AuditViewCommands.parseAction("LOGIN"), AuditAction.LOGIN);
        Assert.assertEquals(AuditViewCommands.parseAction("login"), AuditAction.LOGIN);
        Assert.assertEquals(AuditViewCommands.parseOutcome("FAILURE"), AuditOutcome.FAILURE);
    }

    @Test
    public void csvEscapesTheThingsThatBreakSpreadsheets() {
        Assert.assertEquals(AuditViewCommands.escape("plain"), "plain");
        Assert.assertEquals(AuditViewCommands.escape(null), "");
        Assert.assertEquals(AuditViewCommands.escape("a,b"), "\"a,b\"");
        Assert.assertEquals(AuditViewCommands.escape("say \"hi\""), "\"say \"\"hi\"\"\"");
    }

    @Test
    public void csvEscapesEmbeddedNewlines() {
        // Not hypothetical: the imported PayPal records contain newlines, which is exactly why the original
        // log files needed continuation-line handling. Unquoted, each one becomes a bogus extra CSV row.
        Assert.assertEquals(AuditViewCommands.escape("PayPal payment.\nFee: $0.53"),
                "\"PayPal payment.\nFee: $0.53\"");
    }

    @Test
    public void dropdownsOfferEveryValue() {
        Assert.assertEquals(view.getActions().size(), AuditAction.values().length,
                "Every action must be filterable, or some records become unreachable from the UI");
        Assert.assertEquals(view.getOutcomes().size(), AuditOutcome.values().length);
    }
}
