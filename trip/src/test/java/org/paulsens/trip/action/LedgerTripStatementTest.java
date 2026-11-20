package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.paulsens.trip.action.LedgerCommands.FamilyBalances;
import org.paulsens.trip.action.LedgerCommands.Overview;
import org.paulsens.trip.action.LedgerCommands.PersonBalance;
import org.paulsens.trip.action.LedgerCommands.Row;
import org.paulsens.trip.action.LedgerCommands.State;
import org.paulsens.trip.action.LedgerCommands.Statement;
import org.paulsens.trip.action.LedgerCommands.TripGroup;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.CompositeKey;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * What the Balance page asks the ledger for instead of folding in page script: ONE trip's books for someone who
 * is there only by a finance role on that trip, whether a trip with an amount due takes a payment in the app, the
 * first name beside the full one, and the family pills' figures in the same call as the statement.
 */
public class LedgerTripStatementTest {

    private static final SiteContext ACME_SITE = SiteContext.org(Organization.Id.from(FakeData.ACME_ORG_ID), "acme",
            "acme.localhost");

    private final TransactionsCommands txs = new TransactionsCommands();

    @BeforeClass
    public void seed() {
        DAO.getInstance();
        FakeData.addFakeData();
    }

    // ------------------------------------------------------------------ fixtures

    private static LedgerCommands as(final Person person) {
        return new LedgerCommands(() -> TenancyFixtures.callerFor(person));
    }

    private static LedgerCommands asSiteAdmin() {
        return new LedgerCommands(TenancyFixtures::siteAdminCaller);
    }

    private static String key(final Transaction tx) {
        return CompositeKey.builder().partitionKey(tx.getUserId().getValue()).sortKey(tx.getTxId()).build()
                .getValue();
    }

    private static Transaction loose(final Person.Id who, final String orgId, final float amount,
            final int daysAgo) {
        final Transaction tx = new Transaction(who, null, null);
        tx.setOrgId(orgId);
        tx.setAmount(amount);
        tx.setNote("row " + TenancyFixtures.unique());
        tx.setTxDate(LocalDateTime.now().minusDays(daysAgo));
        try {
            Assert.assertTrue(DAO.getInstance().saveTransaction(tx));
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        return tx;
    }

    private static Transaction row(final Person.Id who, final Trip trip, final float amount, final int daysAgo) {
        final Transaction tx = loose(who, trip.getOrgId(), amount, daysAgo);
        Assert.assertTrue(DAO.getInstance().saveBinding(key(tx), BindingType.TRANSACTION, trip.getId(),
                BindingType.TRIP, true));
        return tx;
    }

    private static void grant(final String privilege, final String scopeId, final Person holder) {
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.getOrCreate(privilege, scopeId, "test")
                .withNewPerson(holder.getId()), null));
    }

    private static Trip payable(final Trip trip) throws IOException {
        trip.getPaymentConfig().setProcessorConfigId("processor-" + TenancyFixtures.unique());
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private static List<String> txIds(final TripGroup group) {
        return group.rows().stream().map(Row::txId).toList();
    }

    // ------------------------------------------------------------------ one trip's books

    @Test
    public void aTripsFinanceViewerGetsThatTripsBooksAndNothingElse() {
        final Person traveler = TenancyFixtures.savedPerson("Books");
        final Person viewer = TenancyFixtures.savedPerson("BooksFin");
        final Person stranger = TenancyFixtures.savedPerson("BooksNobody");
        final Organization org = TenancyFixtures.org("Books", traveler);
        final Organization other = TenancyFixtures.org("BooksOther", traveler);
        final Trip trip = TenancyFixtures.tripOwnedBy(org, traveler);
        final Trip sibling = TenancyFixtures.tripOwnedBy(org, traveler);
        final Trip elsewhere = TenancyFixtures.tripOwnedBy(other, traveler);
        final Transaction bill = row(traveler.getId(), trip, -900f, 30);
        final Transaction paid = row(traveler.getId(), trip, 350.5f, 20);
        final Transaction refund = row(traveler.getId(), trip, 49.5f, 10);
        row(traveler.getId(), sibling, -77f, 15);
        row(traveler.getId(), elsewhere, -33f, 15);
        loose(traveler.getId(), org.getId().getValue(), -5f, 1);
        grant(PrivilegeCommands.TRIP_FIN_VIEW, trip.getId(), viewer);

        // The per-organization ledger answers this viewer nothing: that is why the page folded for itself.
        Assert.assertEquals(as(viewer).overview(List.of(traveler.getId()), null).orgs(), List.of());

        final Statement books = as(viewer).tripStatement(traveler.getId(), trip.getId());
        Assert.assertEquals(books.orgId(), org.getId().getValue());
        Assert.assertEquals(books.orgName(), org.getName());
        Assert.assertEquals(books.trips().size(), 1, "one trip group: this trip's");
        final TripGroup group = books.trips().getFirst();
        Assert.assertEquals(group.tripId(), trip.getId());
        Assert.assertEquals(group.title(), trip.getTitle());
        Assert.assertTrue(group.assigned());
        Assert.assertEquals(txIds(group), List.of(bill.getTxId(), paid.getTxId(), refund.getTxId()), "date order");
        final TripFinanceGate gate = TripFinanceGate.forCaller(() -> TenancyFixtures.callerFor(viewer));
        Assert.assertEquals(txIds(group), txs.getTransactionsOnBooks(gate, traveler.getId(), trip.getId()).stream()
                .map(Transaction::getTxId).toList(), "the rows the page listed for them before");
        Assert.assertEquals(group.charges(), 900d, 1e-9);
        Assert.assertEquals(group.payments(), 400d, 1e-9);
        Assert.assertEquals(group.balanceCents(), -50000L);
        Assert.assertEquals(group.rows().stream().map(Row::running).toList(), List.of(-900d, -549.5d, -500d));
        Assert.assertEquals(group.rows().getLast().statementRunning(), -500d, 1e-9);
        Assert.assertEquals(books.state(), State.OWES);
        Assert.assertEquals(books.balanceCents(), -50000L);
        Assert.assertEquals(books.charges(), 900d, 1e-9);
        Assert.assertEquals(books.payments(), 400d, 1e-9);
        Assert.assertEquals(books.rowCount(), 3);
        Assert.assertEquals(books.magnitude(), 500d, 1e-9);
        Assert.assertFalse(books.hasBreakdown());
        Assert.assertFalse(group.payableInApp(), "nothing here takes a payment in the app");

        // Their role is on THIS trip: the sibling trip's books, and a stranger, get the empty statement.
        Assert.assertEquals(as(viewer).tripStatement(traveler.getId(), sibling.getId()), Statement.none());
        Assert.assertEquals(as(stranger).tripStatement(traveler.getId(), trip.getId()), Statement.none());
        Assert.assertEquals(as(viewer).tripStatement(null, trip.getId()), Statement.none());
        Assert.assertEquals(as(viewer).tripStatement(traveler.getId(), null), Statement.none());
        Assert.assertEquals(as(viewer).tripStatement(traveler.getId(), UUID.randomUUID().toString()),
                Statement.none());
    }

    @Test
    public void theWiderReadersGetTheSameBooksAndAnEmptyTripIsStillItsGroup() throws Exception {
        final Person traveler = TenancyFixtures.savedPerson("Wide");
        final Person finAdmin = TenancyFixtures.savedPerson("WideAdmin");
        final Person orgViewer = TenancyFixtures.savedPerson("WideOrg");
        final Organization org = TenancyFixtures.org("Wide", traveler, orgViewer);
        final Trip trip = TenancyFixtures.tripOwnedBy(org, traveler);
        final Trip quiet = TenancyFixtures.tripOwnedBy(org, traveler);
        final Transaction credit = row(traveler.getId(), trip, 20f, 3);
        final Transaction own = loose(Person.Id.from(trip.getId()), org.getId().getValue(), -640f, 2);
        grant(PrivilegeCommands.TRIP_FIN_ADMIN, trip.getId(), finAdmin);
        Assert.assertTrue(TenancyFixtures.asSiteAdmin().grantOrgPrivilege(org.getId().getValue(), orgViewer.getId(),
                PrivilegeCommands.VIEW_FINANCES));

        for (final LedgerCommands reader : List.of(as(finAdmin), as(orgViewer), as(traveler), asSiteAdmin())) {
            final Statement books = reader.tripStatement(traveler.getId(), trip.getId());
            Assert.assertEquals(txIds(books.trips().getFirst()), List.of(credit.getTxId()));
            Assert.assertEquals(books.state(), State.CREDIT);
        }

        // The trip's own ledger keys its rows by the trip id: on its books with no binding at all.
        final Statement tripsOwn = as(finAdmin).tripStatement(Person.Id.from(trip.getId()), trip.getId());
        Assert.assertEquals(txIds(tripsOwn.trips().getFirst()), List.of(own.getTxId()));
        Assert.assertEquals(tripsOwn.balanceCents(), -64000L);

        final Statement nothing = as(traveler).tripStatement(traveler.getId(), quiet.getId());
        Assert.assertEquals(nothing.trips().size(), 1, "the trip's title and dates are still there to show");
        Assert.assertEquals(nothing.trips().getFirst().title(), quiet.getTitle());
        Assert.assertEquals(nothing.trips().getFirst().rows(), List.of());
        Assert.assertEquals(nothing.state(), State.NO_ACTIVITY);
        Assert.assertEquals(nothing.balanceCents(), 0L);

        Assert.assertEquals(ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, ACME_SITE))
                .call(() -> asSiteAdmin().tripStatement(traveler.getId(), trip.getId())), Statement.none(),
                "a site that does not serve the trip has no books of it");
    }

    @Test
    public void aRowBoundToAnEventOnTheTripsBooksCarriesItsTitle() throws IOException {
        final Person traveler = TenancyFixtures.savedPerson("BooksEvent");
        final Organization org = TenancyFixtures.org("BooksEvent", traveler);
        final Trip trip = TenancyFixtures.tripOwnedBy(org, traveler);
        final Transaction fee = row(traveler.getId(), trip, -15f, 2);
        final String eventId = UUID.randomUUID().toString();
        Assert.assertTrue(DAO.getInstance().saveBinding(key(fee), BindingType.TRANSACTION, eventId,
                BindingType.TRIP_EVENT, true));

        final Row shown = as(traveler).tripStatement(traveler.getId(), trip.getId()).trips().getFirst().rows()
                .getFirst();

        Assert.assertEquals(shown.eventId(), eventId);
        Assert.assertEquals(shown.tripId(), trip.getId());
        Assert.assertTrue(shown.charge());
    }

    // ------------------------------------------------------------------ what the page no longer asks per row

    @Test
    public void aTripGroupSaysWhetherItIsPayableInTheAppOnlyWhenSomethingIsDue() throws IOException {
        final Person traveler = TenancyFixtures.savedPerson("Payable");
        final Organization org = TenancyFixtures.org("Payable", traveler);
        final Trip online = payable(TenancyFixtures.tripOwnedBy(org, traveler));
        final Trip settled = payable(TenancyFixtures.tripOwnedBy(org, traveler));
        final Trip offline = TenancyFixtures.tripOwnedBy(org, traveler);
        row(traveler.getId(), online, -300f, 9);
        row(traveler.getId(), settled, -80f, 9);
        row(traveler.getId(), settled, 80f, 8);
        row(traveler.getId(), offline, -45f, 9);
        loose(traveler.getId(), org.getId().getValue(), -10f, 9);

        final Statement statement = as(traveler).statement(List.of(traveler.getId()), org.getId().getValue());

        Assert.assertTrue(groupOf(statement, online.getId()).payableInApp());
        Assert.assertFalse(groupOf(statement, settled.getId()).payableInApp(), "nothing due: not even asked");
        Assert.assertFalse(groupOf(statement, offline.getId()).payableInApp(), "no processor: the how-to page");
        Assert.assertFalse(groupOf(statement, null).payableInApp(), "rows on no trip have no trip to pay");
        Assert.assertTrue(as(traveler).tripStatement(traveler.getId(), online.getId()).trips().getFirst()
                .payableInApp(), "the one-trip statement is the same fold");
    }

    private static TripGroup groupOf(final Statement statement, final String tripId) {
        return statement.trips().stream()
                .filter(group -> tripId == null ? !group.assigned() : tripId.equals(group.tripId()))
                .findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ a shortfall a credit elsewhere covers

    /**
     * Owner decision 2026-11-20. Overpaid on one trip and short by the same on another, same organization: the
     * organization card says "all paid up", so the short trip's card must not demand the money or offer to take
     * it. The trip keeps its own figures; only what the card makes of them changes.
     */
    @Test
    public void aTripDueOnAStatementThatIsAllPaidUpIsCoveredAndOffersNoPayment() throws IOException {
        final Person traveler = TenancyFixtures.savedPerson("Offset");
        final Organization org = TenancyFixtures.org("Offset", traveler);
        final Trip overpaid = payable(TenancyFixtures.tripOwnedBy(org, traveler));
        final Trip shortOne = payable(TenancyFixtures.tripOwnedBy(org, traveler));
        row(traveler.getId(), overpaid, -500f, 9);
        row(traveler.getId(), overpaid, 700f, 8);
        row(traveler.getId(), shortOne, -200f, 7);

        final Statement statement = as(traveler).statement(List.of(traveler.getId()), org.getId().getValue());

        Assert.assertEquals(statement.state(), State.PAID_UP);
        final TripGroup due = groupOf(statement, shortOne.getId());
        Assert.assertTrue(due.coveredByCredit(), "nothing is owed the organization: the credit covers it");
        Assert.assertTrue(due.payableInApp(), "the trip does take payments in the app");
        Assert.assertFalse(due.payOffered(), "but there is nothing to pay");
        Assert.assertEquals(due.balanceCents(), -20000L, "the trip's own figure is untouched");
        Assert.assertEquals(due.magnitude(), 200d, 1e-9);
        final TripGroup credit = groupOf(statement, overpaid.getId());
        Assert.assertFalse(credit.coveredByCredit(), "a credit is not an amount due");
        Assert.assertFalse(credit.payOffered());
        Assert.assertEquals(credit.magnitude(), 200d, 1e-9);
        Assert.assertFalse(statement.hasOffsettingCredit(), "the organization card shows no amount to qualify");
        Assert.assertEquals(statement.openKey(null), overpaid.getId(), "a covered trip does not open for being due");
        Assert.assertEquals(statement.openKey(shortOne.getId()), shortOne.getId(), "it opens when asked for");
    }

    @Test
    public void aTripDueOnAStatementInCreditIsCoveredAndSoAreRowsOnNoTrip() throws IOException {
        final Person traveler = TenancyFixtures.savedPerson("OffsetCredit");
        final Organization org = TenancyFixtures.org("OffsetCredit", traveler);
        final Trip overpaid = payable(TenancyFixtures.tripOwnedBy(org, traveler));
        final Trip shortOne = payable(TenancyFixtures.tripOwnedBy(org, traveler));
        row(traveler.getId(), overpaid, 300f, 8);
        row(traveler.getId(), shortOne, -200f, 7);
        loose(traveler.getId(), org.getId().getValue(), -10f, 6);

        final Statement statement = as(traveler).statement(List.of(traveler.getId()), org.getId().getValue());

        Assert.assertEquals(statement.state(), State.CREDIT);
        Assert.assertEquals(statement.balanceCents(), 9000L);
        Assert.assertTrue(groupOf(statement, shortOne.getId()).coveredByCredit());
        Assert.assertFalse(groupOf(statement, shortOne.getId()).payOffered());
        Assert.assertTrue(groupOf(statement, null).coveredByCredit(), "the rows on no trip are covered the same way");
        Assert.assertFalse(statement.hasOffsettingCredit(), "that line is for an organization that IS owed");
    }

    @Test
    public void aCreditThatCoversOnlyPartLeavesTheTripDueAndTheStatementSaysACreditIsIncluded()
            throws IOException {
        final Person traveler = TenancyFixtures.savedPerson("OffsetPart");
        final Organization org = TenancyFixtures.org("OffsetPart", traveler);
        final Trip overpaid = payable(TenancyFixtures.tripOwnedBy(org, traveler));
        final Trip shortOne = payable(TenancyFixtures.tripOwnedBy(org, traveler));
        row(traveler.getId(), overpaid, 100f, 8);
        row(traveler.getId(), shortOne, -200f, 7);
        final String orgId = org.getId().getValue();

        final Statement partial = as(traveler).statement(List.of(traveler.getId()), orgId);

        Assert.assertEquals(partial.state(), State.OWES);
        Assert.assertEquals(partial.balanceCents(), -10000L, "the organization card's amount is the true total");
        final TripGroup due = groupOf(partial, shortOne.getId());
        Assert.assertFalse(due.coveredByCredit(), "money is owed: the trip card stays a trip with an amount due");
        Assert.assertTrue(due.payOffered());
        Assert.assertEquals(due.balanceCents(), -20000L);
        Assert.assertTrue(partial.hasOffsettingCredit(), "which is more than the card above: it says why");
        Assert.assertEquals(partial.openKey(null), partial.trips().getFirst().key(), "the first with a balance");

        // Without the credit there is nothing to explain: the due trips add up to the organization's amount.
        final Person plain = TenancyFixtures.savedPerson("OffsetNone");
        final Organization plainOrg = TenancyFixtures.org("OffsetNone", plain);
        row(plain.getId(), TenancyFixtures.tripOwnedBy(plainOrg, plain), -200f, 7);
        row(plain.getId(), TenancyFixtures.tripOwnedBy(plainOrg, plain), -30f, 7);
        Assert.assertFalse(as(plain).statement(List.of(plain.getId()), plainOrg.getId().getValue())
                .hasOffsettingCredit());
    }

    /** A family's statement is judged by the family's balance; one member's own by that member's. */
    @Test
    public void aFamilysStatementIsCoveredByAnotherMembersCreditButTheMembersOwnIsNot() throws IOException {
        final Person child = TenancyFixtures.savedPerson("OffsetKid");
        final Person parent = Person.builder().first("OffsetParent").last("T" + TenancyFixtures.unique())
                .email("tf.offset." + TenancyFixtures.unique() + "@example.com").build();
        parent.setManagedUsers(List.of(child.getId()));
        TenancyFixtures.saved(parent);
        final Organization org = TenancyFixtures.org("OffsetFamily", parent, child);
        final Trip parents = payable(TenancyFixtures.tripOwnedBy(org, parent));
        final Trip childs = payable(TenancyFixtures.tripOwnedBy(org, parent, child));
        row(parent.getId(), parents, 200f, 8);
        row(child.getId(), childs, -200f, 7);
        final String orgId = org.getId().getValue();
        final List<Person.Id> family = List.of(parent.getId(), child.getId());

        final Statement together = as(parent).familyOverview(family, family, orgId).statement();
        Assert.assertEquals(together.state(), State.PAID_UP, "the family owes the organization nothing");
        Assert.assertTrue(groupOf(together, childs.getId()).coveredByCredit());
        Assert.assertFalse(groupOf(together, childs.getId()).payOffered());

        final Statement childsOwn = as(parent).familyOverview(List.of(child.getId()), family, orgId).statement();
        Assert.assertEquals(childsOwn.state(), State.OWES, "the child's own account is short");
        Assert.assertFalse(groupOf(childsOwn, childs.getId()).coveredByCredit());
        Assert.assertTrue(groupOf(childsOwn, childs.getId()).payOffered());
        Assert.assertFalse(childsOwn.hasOffsettingCredit(), "and holds no credit of its own");
    }

    /** One trip's books have no organization balance to judge by: finance staff read them as before. */
    @Test
    public void theTripBooksOnlyStatementIsNeverCoveredWhateverTheOrganizationBalance() throws IOException {
        final Person traveler = TenancyFixtures.savedPerson("OffsetBooks");
        final Person viewer = TenancyFixtures.savedPerson("OffsetBooksFin");
        final Organization org = TenancyFixtures.org("OffsetBooks", traveler);
        final Trip overpaid = payable(TenancyFixtures.tripOwnedBy(org, traveler));
        final Trip shortOne = payable(TenancyFixtures.tripOwnedBy(org, traveler));
        row(traveler.getId(), overpaid, 200f, 8);
        row(traveler.getId(), shortOne, -200f, 7);
        grant(PrivilegeCommands.TRIP_FIN_VIEW, shortOne.getId(), viewer);

        final Statement books = as(viewer).tripStatement(traveler.getId(), shortOne.getId());

        Assert.assertEquals(books.state(), State.OWES, "this trip's books, with no organization context");
        final TripGroup group = books.trips().getFirst();
        Assert.assertFalse(group.coveredByCredit(), "no 'covered' line for a viewer with no reach in the organization");
        Assert.assertEquals(group.payOffered(), group.payableInApp(), "unchanged: the page's who-may-pay rule decides");
        Assert.assertEquals(group.balanceCents(), -20000L);
        Assert.assertFalse(books.hasOffsettingCredit());
        Assert.assertEquals(books.openKey(null), shortOne.getId(), "and it opens as a trip with a balance");
    }

    /** The printed statement's table is drawn from these: a covered trip's totals stay the trip's true numbers. */
    @Test
    public void aCoveredTripsRowsAndTotalsAreTheOnesItsOwnBooksShow() throws IOException {
        final Person traveler = TenancyFixtures.savedPerson("OffsetPrint");
        final Organization org = TenancyFixtures.org("OffsetPrint", traveler);
        final Trip overpaid = payable(TenancyFixtures.tripOwnedBy(org, traveler));
        final Trip shortOne = payable(TenancyFixtures.tripOwnedBy(org, traveler));
        row(traveler.getId(), overpaid, 250f, 9);
        row(traveler.getId(), shortOne, -300f, 8);
        row(traveler.getId(), shortOne, 100f, 7);

        final Statement statement = as(traveler).statement(List.of(traveler.getId()), org.getId().getValue());
        final TripGroup covered = groupOf(statement, shortOne.getId());
        final TripGroup books = as(traveler).tripStatement(traveler.getId(), shortOne.getId()).trips().getFirst();

        Assert.assertTrue(covered.coveredByCredit());
        Assert.assertEquals(covered.charges(), 300d, 1e-9);
        Assert.assertEquals(covered.payments(), 100d, 1e-9);
        Assert.assertEquals(covered.balanceCents(), -20000L, "the printed trip total is still what the trip is short");
        Assert.assertEquals(covered.paidPercent(), 33);
        Assert.assertEquals(covered.rows().stream().map(Row::running).toList(), List.of(-300d, -200d));
        Assert.assertEquals(txIds(covered), txIds(books));
        Assert.assertEquals(covered.balance(), books.balance(), 1e-9);
        Assert.assertEquals(statement.balanceCents(), 5000L, "and the closing line is the organization's: a credit");
    }

    @Test
    public void rowsAndMembersCarryThePreferredNameBesideTheFullOne() {
        final Person nicknamed = Person.builder().first("Margaret").last("T" + TenancyFixtures.unique())
                .email("tf.peg." + TenancyFixtures.unique() + "@example.com").build();
        nicknamed.setNickname("Peg");
        TenancyFixtures.saved(nicknamed);
        final Organization org = TenancyFixtures.org("Names", nicknamed);
        final Trip trip = TenancyFixtures.tripOwnedBy(org, nicknamed);
        row(nicknamed.getId(), trip, -12f, 1);
        final Person.Id ghost = Person.Id.newInstance();

        final Statement statement = asSiteAdmin().statement(List.of(nicknamed.getId(), ghost),
                org.getId().getValue());

        final Row shown = statement.trips().getFirst().rows().getFirst();
        Assert.assertEquals(shown.personPreferredName(), "Peg");
        Assert.assertEquals(shown.personName(), "Peg " + nicknamed.getLast());
        final PersonBalance member = statement.people().getFirst();
        Assert.assertEquals(member.preferredName(), "Peg");
        Assert.assertEquals(member.name(), "Peg " + nicknamed.getLast());
        Assert.assertEquals(member.magnitude(), 12d, 1e-9);
        Assert.assertEquals(statement.people().get(1).preferredName(), "", "nobody known: no first name to show");
    }

    // ------------------------------------------------------------------ the family pills, in the page's one call

    /** The real reads, counted. */
    private static final class CountingReads implements LedgerCommands.Reads {
        private final List<Person.Id> partitions = new ArrayList<>();
        private final List<String> bindings = new ArrayList<>();

        @Override
        public List<Transaction> partition(final Person.Id personId) {
            partitions.add(personId);
            return DAO.getInstance().getTransactions(personId, Cached.NO);
        }

        @Override
        public List<String> bindings(final String id, final BindingType type, final BindingType destType) {
            bindings.add(id);
            return DAO.getInstance().getBindings(id, type, destType, Cached.YES);
        }

        @Override
        public List<Registration> registrations(final String tripId) {
            return DAO.getInstance().getRegistrations(tripId, Cached.YES);
        }

        @Override
        public List<Trip> activeTrips() {
            return List.of();
        }
    }

    @Test
    public void theFamilyOverviewCarriesEveryMembersBalanceWhicheverMemberIsSelected() {
        final Person child = TenancyFixtures.savedPerson("PillKid");
        final Person idle = TenancyFixtures.savedPerson("PillIdle");
        final Person manager = Person.builder().first("PillParent").last("T" + TenancyFixtures.unique())
                .email("tf.pill." + TenancyFixtures.unique() + "@example.com").build();
        manager.setManagedUsers(List.of(child.getId(), idle.getId()));
        TenancyFixtures.saved(manager);
        final Organization org = TenancyFixtures.org("Pills", manager, child, idle);
        final Organization second = TenancyFixtures.org("PillsSecond", manager);
        final Trip together = TenancyFixtures.tripOwnedBy(org, manager, child);
        final Transaction mine = row(manager.getId(), together, -400f, 6);
        row(manager.getId(), together, 400f, 5);
        final Transaction kids = row(child.getId(), together, -125.5f, 6);
        loose(manager.getId(), second.getId().getValue(), -9f, 40);
        final List<Person.Id> family = List.of(manager.getId(), child.getId(), idle.getId());
        final String orgId = org.getId().getValue();
        final CountingReads reads = new CountingReads();
        final LedgerCommands ledger = new LedgerCommands(() -> TenancyFixtures.callerFor(manager), reads,
                LocalDate::now);

        // The child selected: the statement is the child's, the pills are the whole family's.
        final Overview page = ledger.familyOverview(List.of(child.getId()), family, orgId);
        Assert.assertEquals(page.statement().balanceCents(), -12550L);
        Assert.assertEquals(page.statement().people(), List.of(), "one subject: the statement has no breakdown");
        final FamilyBalances pills = page.family();
        Assert.assertTrue(pills.hasBreakdown());
        Assert.assertEquals(pills.orgId(), orgId);
        Assert.assertEquals(pills.balanceCents(), -12550L, "Everyone: the family's total with this organization");
        Assert.assertEquals(pills.state(), State.OWES);
        Assert.assertEquals(pills.magnitude(), 125.5d, 1e-9);
        Assert.assertEquals(pills.people().stream().map(PersonBalance::personId).toList(), family);
        Assert.assertEquals(pills.people().stream().map(PersonBalance::state).toList(),
                List.of(State.PAID_UP, State.OWES, State.NO_ACTIVITY));
        Assert.assertEquals(pills.people().get(1).preferredName(), "PillKid");
        Assert.assertEquals(reads.partitions, List.of(child.getId(), manager.getId(), idle.getId()),
                "ONE partition read per member, the selected one's shared with the pills");
        Assert.assertTrue(reads.bindings.stream().allMatch(read -> read.equals(key(kids))),
                "binding reads for the statement's rows only, none for the pills: " + reads.bindings);
        Assert.assertFalse(reads.bindings.contains(key(mine)));

        // The figures are the ones the page read with a second overview call before.
        final Statement before = ledger.overview(family, orgId).statement();
        Assert.assertEquals(pills.people(), before.people());
        Assert.assertEquals(pills.balanceCents(), before.balanceCents());
        Assert.assertEquals(pills.state(), before.state());

        // Family scope: the pills ARE the statement's breakdown.
        final Overview everyone = ledger.familyOverview(family, family, orgId);
        Assert.assertEquals(everyone.family(), FamilyBalances.of(everyone.statement()));
        Assert.assertEquals(everyone.family().people().size(), 3);
        Assert.assertEquals(ledger.overview(family, orgId).family(), everyone.family());

        // A member with no organization of their own: the pills open on the family's default one.
        final Overview quiet = ledger.familyOverview(List.of(idle.getId()), family, "");
        Assert.assertNull(quiet.orgId());
        Assert.assertEquals(quiet.family().orgId(), orgId, "the family's own default organization");
        Assert.assertEquals(quiet.family().people().size(), 3);

        // Somebody with no reach is shown no pills, whatever member ids they hand in.
        final Person outsider = TenancyFixtures.savedPerson("PillOutsider");
        final FamilyBalances none = as(outsider).familyOverview(List.of(child.getId()), family, orgId).family();
        Assert.assertEquals(none, FamilyBalances.none());
        Assert.assertFalse(none.hasBreakdown());
        Assert.assertEquals(none.magnitude(), 0d);
    }
}
