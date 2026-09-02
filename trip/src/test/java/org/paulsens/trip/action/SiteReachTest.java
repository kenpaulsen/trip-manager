package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.pay.PaymentRecorder;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * What a site does not list, it does not serve: a trip, its chat, its ledger rows and its payments are
 * reachable on a site only if that site lists the trip's organization ({@code ListingScope.reaches}). The
 * shared host serves its sharing tenants (CFPW, org-less legacy rows) and NOT a hosted org's (Acme's) --
 * the production leak of 2026-09-01; an org host serves only its own; the unbound/system context serves
 * everything. Pinned on every read path the pages and REST go through, so a non-listed trip behaves
 * exactly like an unknown one: the blank bean answer, null, an empty list.
 */
public class SiteReachTest {

    private static final Organization.Id ACME = Organization.Id.from(FakeData.ACME_ORG_ID);
    private static final SiteContext SHARED = SiteContext.shared("localhost");
    private static final SiteContext ACME_SITE = SiteContext.org(ACME, "acme", "acme.localhost");
    private static final String CFPW_TRIP = "faketrip";

    private final TripCommands trips = new TripCommands();
    private final TransactionsCommands txs = new TransactionsCommands();
    private Person.Id kevin;

    @BeforeClass
    public void init() throws IOException {
        DAO.getInstance();
        FakeData.addFakeData();
        // Kevin (user3) is the one person on the seeded Acme trip's roster, and on faketrip's too.
        kevin = DAO.getInstance().getPersonByEmail("user3@example.com", Cached.NO).getId();
        // Self-healing (the webtest ensure-idiom): the org seed runs once per store, but a test earlier in
        // the suite can clear the trip rows; re-seeding the one fixture trip this class is about keeps the
        // class independent of run order.
        if (DAO.getInstance().getTrip(FakeData.ACME_TRIP_ID, Cached.NO).isEmpty()) {
            final Trip acmeTrip = Trip.builder()
                    .id(FakeData.ACME_TRIP_ID)
                    .title("2027 Jun: Acme Retreat")
                    .startDate(LocalDateTime.of(2027, 6, 10, 9, 0))
                    .endDate(LocalDateTime.of(2027, 6, 20, 17, 0))
                    .people(new java.util.ArrayList<>(List.of(kevin)))
                    .build();
            acmeTrip.setOrgId(FakeData.ACME_ORG_ID);
            Assert.assertTrue(DAO.getInstance().saveTrip(acmeTrip));
        }
    }

    private static <T> T onSite(final SiteContext site, final ScopedValue.CallableOp<T, Exception> body)
            throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site)).call(body);
    }

    private static boolean isBlank(final Trip trip, final String askedFor) {
        return trip.getTitle() == null && !askedFor.equals(trip.getId());
    }

    private static boolean anyOfOrg(final List<Trip> found, final String orgId) {
        return found.stream().anyMatch(trip -> orgId.equals(trip.getOrgId()));
    }

    @Test
    public void aSharedHostServesItsTenantsTripsButNotAHostedOrgs() throws Exception {
        Assert.assertEquals(onSite(SHARED, () -> trips.getTrip(CFPW_TRIP)).getId(), CFPW_TRIP);
        Assert.assertTrue(isBlank(onSite(SHARED, () -> trips.getTrip(FakeData.ACME_TRIP_ID)), FakeData.ACME_TRIP_ID),
                "a hosted org's trip is blank on the shared host until the shared page curates the org");
        Assert.assertTrue(isBlank(onSite(SHARED, () -> trips.getTripForEdit(FakeData.ACME_TRIP_ID)),
                FakeData.ACME_TRIP_ID), "the edit read applies the same gate");
        // The system/unbound context (mail, digests, schedulers, tests) reaches everything.
        Assert.assertEquals(trips.getTrip(FakeData.ACME_TRIP_ID).getId(), FakeData.ACME_TRIP_ID);
        Assert.assertEquals(trips.getTrip(CFPW_TRIP).getId(), CFPW_TRIP);
        Assert.assertEquals(trips.getTripForEdit(FakeData.ACME_TRIP_ID).getId(), FakeData.ACME_TRIP_ID);
    }

    @Test
    public void anOrgHostServesOnlyItsOwnTrips() throws Exception {
        Assert.assertEquals(onSite(ACME_SITE, () -> trips.getTrip(FakeData.ACME_TRIP_ID)).getId(),
                FakeData.ACME_TRIP_ID);
        Assert.assertTrue(isBlank(onSite(ACME_SITE, () -> trips.getTrip(CFPW_TRIP)), CFPW_TRIP),
                "another tenant's trip is blank on Acme's host");
        Assert.assertTrue(isBlank(onSite(ACME_SITE, () -> trips.getTrip("no-such-trip")), "no-such-trip"));
    }

    @Test
    public void tripListsOfferOnlyWhatTheSiteReaches() throws Exception {
        final List<Trip> mineOnShared = onSite(SHARED, () -> trips.getTripsForUser(kevin));
        Assert.assertTrue(mineOnShared.stream().anyMatch(trip -> CFPW_TRIP.equals(trip.getId())));
        Assert.assertFalse(anyOfOrg(mineOnShared, FakeData.ACME_ORG_ID), "Kevin's Acme trip stays off vqop");
        final List<Trip> mineOnAcme = onSite(ACME_SITE, () -> trips.getTripsForUser(kevin));
        Assert.assertEquals(mineOnAcme.size(), 1, "only the Acme trip on Acme's host: " + mineOnAcme);
        Assert.assertEquals(mineOnAcme.get(0).getId(), FakeData.ACME_TRIP_ID);
        Assert.assertTrue(anyOfOrg(trips.getTripsForUser(kevin), FakeData.ACME_ORG_ID), "unbound: everything");

        Assert.assertFalse(anyOfOrg(onSite(SHARED, () -> trips.getRecentTrips(100)), FakeData.ACME_ORG_ID));
        Assert.assertTrue(onSite(ACME_SITE, () -> trips.getRecentTrips(100)).stream()
                .allMatch(trip -> FakeData.ACME_ORG_ID.equals(trip.getOrgId())), "admin pickers are site-scoped");
        Assert.assertFalse(anyOfOrg(onSite(SHARED, () -> trips.getActiveTrips(0)), FakeData.ACME_ORG_ID));
        Assert.assertTrue(anyOfOrg(onSite(ACME_SITE, () -> trips.getActiveTrips(0)), FakeData.ACME_ORG_ID));
        Assert.assertTrue(onSite(ACME_SITE, () -> trips.getInactiveTrips(kevin, true, 0, 0)).stream()
                .allMatch(trip -> FakeData.ACME_ORG_ID.equals(trip.getOrgId())));
        Assert.assertFalse(anyOfOrg(onSite(SHARED, () -> trips.recentTripsFor(kevin, 10)), FakeData.ACME_ORG_ID));
        // The org's own Trips page is keyed by the org and deliberately not site-gated (its links go to
        // the host that reaches each trip).
        final OrgCommands orgs = org.mockito.Mockito.mock(OrgCommands.class);
        org.mockito.Mockito.when(orgs.canViewOrgTrips(FakeData.ACME_ORG_ID)).thenReturn(true);
        Assert.assertEquals(onSite(SHARED, () -> new TripCommands(() -> orgs)
                .getTripsForOrg(FakeData.ACME_ORG_ID, 10)).get(0).getId(), FakeData.ACME_TRIP_ID);
    }

    @Test
    public void theCurrentTripIsChosenAmongWhatTheSiteLists() throws Exception {
        // The "current trip" behind /account.jsf: a page hands back the blank answer for a last-visited
        // trip the host no longer reaches, and an admin "can see" any trip -- the pick must move on to a
        // trip this site actually lists, never land on the blank one.
        final Trip blank = Trip.builder().build();
        final Trip picked = onSite(SHARED, () -> trips.getTripForUser(blank, kevin, true, null));
        Assert.assertNotNull(picked);
        Assert.assertNotEquals(picked.getId(), blank.getId(), "never the blank trip");
        Assert.assertNotEquals(picked.getOrgId(), FakeData.ACME_ORG_ID, "never a hosted org's trip on vqop");
        final Trip requested = onSite(SHARED, () -> trips.getTripForUser(null, kevin, false, FakeData.ACME_TRIP_ID));
        Assert.assertNotNull(requested);
        Assert.assertNotEquals(requested.getId(), FakeData.ACME_TRIP_ID, "?trip= naming an Acme trip is ignored");
        // On the org's host, the org's own trip wins.
        Assert.assertEquals(onSite(ACME_SITE, () -> trips.getTripForUser(null, kevin, false, null)).getId(),
                FakeData.ACME_TRIP_ID);
        Assert.assertEquals(onSite(ACME_SITE, () -> trips.getTripForUser(null, kevin, false, CFPW_TRIP)).getId(),
                FakeData.ACME_TRIP_ID, "a CFPW trip asked for on Acme's host falls through to Acme's own");
    }

    @Test
    public void chatAccessFollowsTheSite() throws Exception {
        final ChatCommands chat = ChatCommands.getChatCommands();
        Assert.assertTrue(chat.canParticipate(FakeData.ACME_TRIP_ID, kevin), "unbound: a member participates");
        Assert.assertTrue(onSite(ACME_SITE, () -> chat.canParticipate(FakeData.ACME_TRIP_ID, kevin)));
        Assert.assertFalse(onSite(SHARED, () -> chat.canParticipate(FakeData.ACME_TRIP_ID, kevin)),
                "the Acme chat is not on the shared host, member or not");
        Assert.assertFalse(onSite(SHARED, () -> chat.canParticipate("no-such-trip", kevin)));
        Assert.assertNull(onSite(ACME_SITE, () -> chat.readDenial(chat.channelForPage(FakeData.ACME_TRIP_ID), kevin)));
        Assert.assertEquals(onSite(SHARED, () -> chat.readDenial(chat.channelForPage(FakeData.ACME_TRIP_ID), kevin)),
                "NOT_A_TRIP_MEMBER", "the REST feed and long poll refuse like the page");
        Assert.assertNull(onSite(SHARED, () -> chat.tripForChatPage(null, FakeData.ACME_TRIP_ID, kevin)),
                "the chat page's own trip resolution answers nothing here");
        Assert.assertEquals(onSite(ACME_SITE, () -> chat.tripForChatPage(null, FakeData.ACME_TRIP_ID, kevin)).getId(),
                FakeData.ACME_TRIP_ID);
    }

    @Test
    public void ledgerRowsAndBalancesListPerSite() throws Exception {
        final Person person = savedPerson();
        final Transaction acmeRow = saveRow(person.getId(), FakeData.ACME_ORG_ID, -10f, 30);
        final Transaction cfpwRow = saveRow(person.getId(), FakeData.CFPW_ORG_ID, -20f, 20);
        final Transaction legacyRow = saveRow(person.getId(), null, -40f, 10);

        final List<Transaction> onAcme = onSite(ACME_SITE, () -> txs.getTransactions(person.getId()));
        Assert.assertEquals(onAcme.stream().map(Transaction::getTxId).toList(), List.of(acmeRow.getTxId()),
                "Acme's host lists Acme's row only");
        Assert.assertEquals(onSite(ACME_SITE, () -> txs.getBalance(person.getId())), -10d, 0.001,
                "a balance shown on a site is the balance of the rows that site lists");

        final List<Transaction> onShared = onSite(SHARED, () -> txs.getTransactionsSorted(person.getId()));
        Assert.assertEquals(onShared.stream().map(Transaction::getTxId).toList(),
                List.of(cfpwRow.getTxId(), legacyRow.getTxId()), "the shared host lists CFPW's and legacy rows");
        Assert.assertEquals(onSite(SHARED, () -> txs.getBalance(person.getId())), -60d, 0.001);
        Assert.assertEquals(onSite(SHARED, () -> txs.getFamilyBalance(List.of(person.getId()))), -60d, 0.001);
        Assert.assertEquals(onSite(SHARED, () -> txs.getFamilyTransactions(List.of(person.getId()))).size(), 2);
        Assert.assertNull(onSite(SHARED, () -> txs.getTransaction(person.getId(), acmeRow.getTxId())),
                "a row the site does not list reads as absent");
        Assert.assertNotNull(onSite(ACME_SITE, () -> txs.getTransaction(person.getId(), acmeRow.getTxId())));
        Assert.assertNull(onSite(ACME_SITE, () -> txs.getTransaction(person.getId(), legacyRow.getTxId())),
                "org-less legacy rows belong to the shared sites, not to an org's");

        Assert.assertEquals(txs.getTransactions(person.getId()).size(), 3, "unbound: the whole ledger");
        Assert.assertEquals(txs.getBalance(person.getId()), -70d, 0.001);
    }

    @Test
    public void openPaymentsReconcilePerSite() throws Exception {
        final Person admin = savedPerson();
        final Supplier<Caller> siteAdmin = () -> new Caller(admin.getId(), true,
                new AuditActor(admin.getEmail(), admin.getId().getValue()), new PrivilegeCommands());
        final PaymentCommands payments = new PaymentCommands(siteAdmin, new OrgCommands(siteAdmin),
                new AuditCommands(), new PaymentRecorder(), (config, sandbox) -> null, () -> null);
        final Payment acmePayment = savePayment(admin.getId(), FakeData.ACME_ORG_ID, FakeData.ACME_TRIP_ID);
        final Payment cfpwPayment = savePayment(admin.getId(), FakeData.CFPW_ORG_ID, CFPW_TRIP);

        final List<String> onShared = ids(onSite(SHARED, payments::getOpenPayments));
        Assert.assertTrue(onShared.contains(cfpwPayment.getPaymentId()));
        Assert.assertFalse(onShared.contains(acmePayment.getPaymentId()), "an Acme payment never shows on vqop");
        final List<String> onAcme = ids(onSite(ACME_SITE, payments::getOpenPayments));
        Assert.assertTrue(onAcme.contains(acmePayment.getPaymentId()));
        Assert.assertFalse(onAcme.contains(cfpwPayment.getPaymentId()));
        Assert.assertTrue(ids(payments.getOpenPayments()).containsAll(
                List.of(acmePayment.getPaymentId(), cfpwPayment.getPaymentId())), "unbound: everything");
    }

    @Test
    public void adminLinksToAnUnreachableOrgGoToItsOwnHost() throws Exception {
        final SiteCommands site = new SiteCommands();
        Assert.assertEquals(onSite(SHARED, () -> site.hostFor(FakeData.ACME_ORG_ID)), "https://acme.unitetrip.com",
                "a hosted org the shared host does not list is linked on its own host");
        Assert.assertEquals(onSite(SHARED, () -> site.hostFor(FakeData.CFPW_ORG_ID)), "", "reachable: site-relative");
        Assert.assertEquals(onSite(ACME_SITE, () -> site.hostFor(FakeData.ACME_ORG_ID)), "");
        Assert.assertEquals(onSite(ACME_SITE, () -> site.hostFor(FakeData.BETA_ORG_ID)), "https://beta.unitetrip.com");
        Assert.assertEquals(onSite(ACME_SITE, () -> site.hostFor(FakeData.CFPW_ORG_ID)), "",
                "an org with no site of its own has nowhere else to link to");
        Assert.assertEquals(site.hostFor(FakeData.ACME_ORG_ID), "", "unbound reaches everything");
    }

    private static List<String> ids(final List<Payment> payments) {
        return payments.stream().map(Payment::getPaymentId).toList();
    }

    private static Person savedPerson() throws IOException {
        final Person person = Person.builder()
                .first(RandomData.genAlpha(6)).last(RandomData.genAlpha(8))
                .email("reach." + RandomData.genAlpha(10) + "@example.com")
                .build();
        Assert.assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    private static Transaction saveRow(final Person.Id personId, final String orgId, final float amount,
            final int secondsAgo) throws IOException {
        final Transaction tx = new Transaction(personId, null, null);
        tx.setOrgId(orgId);
        tx.setAmount(amount);
        tx.setTxDate(LocalDateTime.now().minusSeconds(secondsAgo));
        tx.setNote("site reach " + orgId);
        Assert.assertTrue(DAO.getInstance().saveTransaction(tx));
        return tx;
    }

    private static Payment savePayment(final Person.Id payer, final String orgId, final String tripId)
            throws IOException {
        final Payment payment = Payment.builder()
                .tripId(tripId).orgId(orgId).payerId(payer)
                .status(Payment.Status.CREATED).createdAt(LocalDateTime.now())
                .allocations(List.of()).totalChargedCents(1000L)
                .build();
        Assert.assertTrue(DAO.getInstance().createPayment(payment));
        return payment;
    }
}
