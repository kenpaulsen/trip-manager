package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.paulsens.trip.action.LedgerCommands.OrgBalance;
import org.paulsens.trip.action.LedgerCommands.Overview;
import org.paulsens.trip.action.LedgerCommands.Row;
import org.paulsens.trip.action.LedgerCommands.State;
import org.paulsens.trip.action.LedgerCommands.Statement;
import org.paulsens.trip.action.LedgerCommands.TripBalances;
import org.paulsens.trip.action.LedgerCommands.TripGroup;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.CompositeKey;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The per-organization ledger: every balance and statement names ONE organization, and both gates (the site's
 * and the viewer's) are asked of every row. Everything is written through the real store with real privilege
 * rows and read back through the bean, because these rules are about which rows exist and who holds what.
 *
 * <p>The matrix: a person with no trips, one trip, several trips in one organization, each of those again for a
 * person who is in two organizations; a family; Shared and Batch rows; credits; the three kinds of site; staff;
 * the default-organization ladder; and the two invariants the migration's proof rests on (the per-organization
 * balances add up to the org-blind one, and every live row is in exactly one statement).
 */
public class LedgerCommandsTest {

    private static final Organization.Id ACME = Organization.Id.from(FakeData.ACME_ORG_ID);
    private static final SiteContext SHARED = SiteContext.shared("localhost");
    private static final SiteContext ACME_SITE = SiteContext.org(ACME, "acme", "acme.localhost");
    private static final double EXACT = 1e-9;

    private final TransactionsCommands txs = new TransactionsCommands();

    @BeforeClass
    public void seed() {
        DAO.getInstance();
        FakeData.addFakeData();     // the seeded organizations: CFPW shares the shared host, Acme has its own
    }

    // ------------------------------------------------------------------ fixtures

    private static <T> T onSite(final SiteContext site, final ScopedValue.CallableOp<T, Exception> body)
            throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site)).call(body);
    }

    private static LedgerCommands as(final Person person) {
        return new LedgerCommands(() -> TenancyFixtures.callerFor(person));
    }

    private static LedgerCommands asSiteAdmin() {
        return new LedgerCommands(TenancyFixtures::siteAdminCaller);
    }

    private static Trip trip(final String orgId, final int startsInDays, final Person... roster) {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString())
                .title("Trip " + TenancyFixtures.unique())
                .startDate(LocalDateTime.now().plusDays(startsInDays))
                .endDate(LocalDateTime.now().plusDays(startsInDays + 7))
                .openToPublic(false)
                .people(new ArrayList<>(List.of(roster).stream().map(Person::getId).toList())).build();
        trip.setOrgId(orgId);
        return saved(trip);
    }

    private static Trip trip(final Organization org, final int startsInDays, final Person... roster) {
        return trip(org.getId().getValue(), startsInDays, roster);
    }

    private static Trip saved(final Trip trip) {
        try {
            Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        return trip;
    }

    /** A plain row with this organization and no trip binding. */
    private static Transaction loose(final Person.Id who, final String orgId, final float amount,
            final int daysAgo) {
        final Transaction tx = new Transaction(who, null, null);
        tx.setOrgId(orgId);
        tx.setAmount(amount);
        tx.setTxType(amount < 0 ? Transaction.TransactionType.Bill : Transaction.TransactionType.Payment);
        tx.setCategory(amount < 0 ? "Trip cost" : "Payment");
        tx.setNote("row " + TenancyFixtures.unique());
        tx.setTxDate(LocalDateTime.now().minusDays(daysAgo));
        try {
            Assert.assertTrue(DAO.getInstance().saveTransaction(tx));
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        return tx;
    }

    /** A plain row on this trip's books: the trip's organization, bound both ways (what every writer leaves). */
    private static Transaction row(final Person.Id who, final Trip trip, final float amount, final int daysAgo) {
        final Transaction tx = loose(who, trip.getOrgId(), amount, daysAgo);
        Assert.assertTrue(DAO.getInstance().saveBinding(key(tx), BindingType.TRANSACTION, trip.getId(),
                BindingType.TRIP, true));
        return tx;
    }

    private static String key(final Transaction tx) {
        return CompositeKey.builder().partitionKey(tx.getUserId().getValue()).sortKey(tx.getTxId()).build()
                .getValue();
    }

    private static List<String> orgIds(final List<OrgBalance> balances) {
        return balances.stream().map(OrgBalance::orgId).toList();
    }

    private static OrgBalance of(final List<OrgBalance> balances, final String orgId) {
        return balances.stream().filter(entry -> entry.orgId().equals(orgId)).findFirst().orElseThrow();
    }

    private static List<String> txIds(final Statement statement) {
        return statement.trips().stream().flatMap(group -> group.rows().stream()).map(Row::txId).toList();
    }

    /** {@code viewFinances@org} through the real grant path: an org admin's grant to a member. */
    private static void grantViewFinances(final Organization org, final Person holder) {
        Assert.assertTrue(TenancyFixtures.asSiteAdmin().grantOrgPrivilege(org.getId().getValue(), holder.getId(),
                PrivilegeCommands.VIEW_FINANCES));
    }

    /** A trip-scoped role, written as the trip editor's role toggle stores it. */
    private static void grant(final String privilege, final String scopeId, final Person holder) {
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.getOrCreate(privilege, scopeId, privilege)
                .withNewPerson(holder.getId()), null));
    }

    // ------------------------------------------------------------------ the matrix: one organization

    @Test
    public void aPersonWithNoTripsAndNoRowsHasNoAccountActivity() {
        final Person nobody = TenancyFixtures.savedPerson("None");
        final List<Person.Id> subject = List.of(nobody.getId());
        final LedgerCommands ledger = as(nobody);

        Assert.assertEquals(ledger.orgBalances(subject), List.of());
        Assert.assertNull(ledger.defaultOrg(subject), "nothing to open on: the 'no account activity yet' state");
        Assert.assertEquals(ledger.otherOrgBalances(subject, null), List.of());
        final Overview page = ledger.overview(subject, null);
        Assert.assertNull(page.orgId());
        Assert.assertFalse(page.hasChoice());
        Assert.assertEquals(page.statement(), Statement.none());
        Assert.assertEquals(page.statement().state(), State.NO_ACTIVITY);
        Assert.assertEquals(page.statement().magnitude(), 0d);
        Assert.assertFalse(page.statement().hasBreakdown());
        // No subject at all is the same answer, never an exception (a signed-out visitor's page).
        Assert.assertEquals(ledger.orgBalances(null), List.of());
        Assert.assertEquals(ledger.orgBalances(new ArrayList<>(java.util.Arrays.asList((Person.Id) null))),
                List.of());
    }

    @Test
    public void aPersonOnATripWithNoRowsYetIsListedWithNoActivity() {
        final Person joiner = TenancyFixtures.savedPerson("Joiner");
        final Organization org = TenancyFixtures.org("Join", joiner);
        trip(org, 30, joiner);
        final List<Person.Id> subject = List.of(joiner.getId());

        final OrgBalance only = as(joiner).orgBalances(subject).getFirst();
        Assert.assertEquals(only.orgId(), org.getId().getValue());
        Assert.assertEquals(only.orgName(), org.getName());
        Assert.assertEquals(only.state(), State.NO_ACTIVITY);
        Assert.assertEquals(only.rowCount(), 0);
        Assert.assertFalse(only.nonZero());
        final Statement statement = as(joiner).statement(subject, org.getId().getValue());
        Assert.assertEquals(statement.orgName(), org.getName(), "the header still names who they travel with");
        Assert.assertEquals(statement.state(), State.NO_ACTIVITY);
        Assert.assertEquals(statement.trips(), List.of());
    }

    @Test
    public void oneTripInOneOrganizationIsOneBalanceAndOneGroup() {
        final Person traveler = TenancyFixtures.savedPerson("One");
        final Organization org = TenancyFixtures.org("Solo", traveler);
        final Trip only = trip(org, 30, traveler);
        final Transaction bill = row(traveler.getId(), only, -1000f, 20);
        final Transaction paid = row(traveler.getId(), only, 400f, 10);
        final List<Person.Id> subject = List.of(traveler.getId());
        final LedgerCommands ledger = as(traveler);

        final List<OrgBalance> balances = ledger.orgBalances(subject);
        Assert.assertEquals(orgIds(balances), List.of(org.getId().getValue()));
        final OrgBalance balance = balances.getFirst();
        Assert.assertEquals(balance.orgName(), org.getName());
        Assert.assertEquals(balance.balance(), -600d, EXACT);
        Assert.assertEquals(balance.balanceCents(), -60000L);
        Assert.assertEquals(balance.magnitude(), 600d, EXACT);
        Assert.assertEquals(balance.state(), State.OWES);
        Assert.assertEquals(balance.rowCount(), 2);
        Assert.assertTrue(balance.nonZero());
        Assert.assertFalse(balance.unassigned());
        Assert.assertEquals(ledger.defaultOrg(subject), org.getId().getValue());
        Assert.assertEquals(ledger.otherOrgBalances(subject, org.getId().getValue()), List.of());
        Assert.assertEquals(ledger.orgBalance(subject, org.getId().getValue()), balance);

        final Statement statement = ledger.statement(subject, org.getId().getValue());
        Assert.assertEquals(statement.orgId(), org.getId().getValue());
        Assert.assertEquals(statement.orgName(), org.getName(), "the header names the payee");
        Assert.assertEquals(statement.state(), State.OWES);
        Assert.assertEquals(statement.balanceCents(), -60000L);
        Assert.assertEquals(statement.magnitude(), 600d, EXACT);
        Assert.assertEquals(statement.charges(), 1000d, EXACT);
        Assert.assertEquals(statement.payments(), 400d, EXACT);
        Assert.assertEquals(statement.rowCount(), 2);
        Assert.assertEquals(statement.people(), List.of(), "one subject: no breakdown");
        Assert.assertEquals(statement.trips().size(), 1);
        final TripGroup group = statement.trips().getFirst();
        Assert.assertTrue(group.assigned());
        Assert.assertEquals(group.tripId(), only.getId());
        Assert.assertEquals(group.title(), only.getTitle());
        Assert.assertEquals(group.startDate(), only.getStartDate());
        Assert.assertEquals(group.endDate(), only.getEndDate());
        Assert.assertEquals(group.charges(), 1000d, EXACT);
        Assert.assertEquals(group.payments(), 400d, EXACT);
        Assert.assertEquals(group.balance(), -600d, EXACT);
        Assert.assertEquals(group.balanceCents(), -60000L);
        Assert.assertEquals(group.rows().stream().map(Row::txId).toList(), List.of(bill.getTxId(), paid.getTxId()),
                "date order");
        final Row first = group.rows().getFirst();
        Assert.assertEquals(first.personId(), traveler.getId());
        Assert.assertEquals(first.personName(), traveler.getPreferredName() + " " + traveler.getLast());
        Assert.assertEquals(first.date(), bill.getTxDate());
        Assert.assertEquals(first.category(), "Trip cost");
        Assert.assertEquals(first.note(), bill.getNote());
        Assert.assertEquals(first.txType(), "Bill");
        Assert.assertEquals(first.amount(), -1000d, EXACT);
        Assert.assertTrue(first.charge());
        Assert.assertEquals(first.running(), -1000d, EXACT);
        Assert.assertEquals(first.statementRunning(), -1000d, EXACT);
        Assert.assertEquals(first.tripId(), only.getId());
        Assert.assertFalse(first.group());
        Assert.assertFalse(first.shared());
        Assert.assertNull(first.groupId());
        Assert.assertNull(first.eventId());
        Assert.assertEquals(first.eventTitle(), "");
        final Row second = group.rows().get(1);
        Assert.assertFalse(second.charge());
        Assert.assertEquals(second.running(), -600d, EXACT);
        Assert.assertEquals(second.statementRunning(), -600d, EXACT);
    }

    @Test
    public void severalTripsInOneOrganizationGroupNewestFirstWithUnassignedRowsLast() {
        final Person traveler = TenancyFixtures.savedPerson("Many");
        final Organization org = TenancyFixtures.org("Many", traveler);
        final Trip old = trip(org, -400, traveler);
        final Trip recent = trip(org, -30, traveler);
        final Trip next = trip(org, 45, traveler);
        final Transaction oldBill = row(traveler.getId(), old, -900f, 420);
        final Transaction oldPaid = row(traveler.getId(), old, 900f, 410);
        final Transaction recentBill = row(traveler.getId(), recent, -500f, 60);
        final Transaction nextBill = row(traveler.getId(), next, -200f, 5);
        final Transaction stray = loose(traveler.getId(), org.getId().getValue(), 50f, 100);
        final List<Person.Id> subject = List.of(traveler.getId());

        final Statement statement = as(traveler).statement(subject, org.getId().getValue());

        Assert.assertEquals(statement.trips().stream().map(TripGroup::tripId).toList(),
                java.util.Arrays.asList(next.getId(), recent.getId(), old.getId(), null),
                "newest trip first, the rows on no trip last");
        final TripGroup unassigned = statement.trips().getLast();
        Assert.assertFalse(unassigned.assigned());
        Assert.assertEquals(unassigned.title(), LedgerCommands.UNASSIGNED_TRIP_TITLE);
        Assert.assertNull(unassigned.startDate());
        Assert.assertEquals(unassigned.rows().getFirst().txId(), stray.getTxId());
        Assert.assertNull(unassigned.rows().getFirst().tripId());
        Assert.assertEquals(statement.trips().get(2).balanceCents(), 0L, "the old trip is paid in full");
        Assert.assertEquals(statement.trips().get(2).rows().get(1).running(), 0d, EXACT);
        Assert.assertEquals(statement.balanceCents(), -65000L);
        Assert.assertEquals(statement.charges(), 1600d, EXACT);
        Assert.assertEquals(statement.payments(), 950d, EXACT);
        // The statement-wide running column follows the calendar, whatever group a row is shown in.
        final Row last = statement.trips().getFirst().rows().getFirst();
        Assert.assertEquals(last.txId(), nextBill.getTxId());
        Assert.assertEquals(last.statementRunning(), -650d, EXACT, "the latest row carries the final balance");
        Assert.assertEquals(statement.trips().get(1).rows().getFirst().statementRunning(), -450d, EXACT,
                "after the old trip (net 0), the stray credit (+50) and this bill (-500)");
        Assert.assertEquals(new HashSet<>(txIds(statement)), Set.of(oldBill.getTxId(), oldPaid.getTxId(),
                recentBill.getTxId(), nextBill.getTxId(), stray.getTxId()));
        Assert.assertEquals(txIds(statement).size(), 5, "each row exactly once");
        Assert.assertEquals(as(traveler).defaultOrg(subject), org.getId().getValue());
    }

    // ------------------------------------------------------------------ the matrix: two organizations

    @Test
    public void aPersonInTwoOrganizationsWithNoTripsGetsABalancePerOrganization() {
        final Person traveler = TenancyFixtures.savedPerson("TwoNone");
        final Organization alpha = TenancyFixtures.org("Alpha", traveler);
        final Organization zulu = TenancyFixtures.org("Zulu", traveler);
        loose(traveler.getId(), alpha.getId().getValue(), -300f, 40);
        final Transaction latest = loose(traveler.getId(), zulu.getId().getValue(), -70f, 2);
        final List<Person.Id> subject = List.of(traveler.getId());
        final LedgerCommands ledger = as(traveler);

        final List<OrgBalance> balances = ledger.orgBalances(subject);
        Assert.assertEquals(orgIds(balances), List.of(alpha.getId().getValue(), zulu.getId().getValue()),
                "sorted by organization name");
        Assert.assertEquals(of(balances, alpha.getId().getValue()).balanceCents(), -30000L);
        Assert.assertEquals(of(balances, zulu.getId().getValue()).balanceCents(), -7000L);
        Assert.assertEquals(ledger.defaultOrg(subject), zulu.getId().getValue(),
                "no trip at all: the organization of the most recent row");
        final Statement statement = ledger.statement(subject, zulu.getId().getValue());
        Assert.assertEquals(txIds(statement), List.of(latest.getTxId()), "only that organization's rows");
        Assert.assertFalse(statement.trips().getFirst().assigned());
        Assert.assertEquals(orgIds(ledger.otherOrgBalances(subject, zulu.getId().getValue())),
                List.of(alpha.getId().getValue()), "the notice names the other organization");
        assertAddsUpAndEveryRowIsShownOnce(ledger, traveler.getId(), txs.getTransactions(traveler.getId()),
                txs.getBalance(traveler.getId()));
    }

    @Test
    public void aPersonInTwoOrganizationsWithOneTripEachOpensOnTheNextTripsOrganization() {
        final Person traveler = TenancyFixtures.savedPerson("TwoOne");
        final Organization home = TenancyFixtures.org("Home", traveler);
        final Organization away = TenancyFixtures.org("Away", traveler);
        final Trip past = trip(home, -90, traveler);
        final Trip coming = trip(away, 20, traveler);
        row(traveler.getId(), past, -800f, 120);
        row(traveler.getId(), past, 800f, 100);
        final Transaction deposit = row(traveler.getId(), coming, -250f, 3);
        final List<Person.Id> subject = List.of(traveler.getId());
        final LedgerCommands ledger = as(traveler);

        final Overview page = ledger.overview(subject, null);
        Assert.assertTrue(page.hasChoice());
        Assert.assertEquals(page.orgId(), away.getId().getValue(), "the organization of the next upcoming trip");
        Assert.assertEquals(txIds(page.statement()), List.of(deposit.getTxId()));
        Assert.assertEquals(page.statement().state(), State.OWES);
        Assert.assertEquals(page.others(), List.of(), "the other organization is paid up: nothing to tell them");
        Assert.assertEquals(of(page.orgs(), home.getId().getValue()).state(), State.PAID_UP);
        Assert.assertFalse(of(page.orgs(), home.getId().getValue()).nonZero());

        final Overview asked = ledger.overview(subject, home.getId().getValue());
        Assert.assertEquals(asked.orgId(), home.getId().getValue(), "?org= wins when it is reachable");
        Assert.assertEquals(asked.statement().state(), State.PAID_UP);
        Assert.assertEquals(orgIds(asked.others()), List.of(away.getId().getValue()));
        Assert.assertEquals(ledger.overview(subject, "no-such-org").orgId(), away.getId().getValue(),
                "an organization that is not theirs falls back to the default");
        assertAddsUpAndEveryRowIsShownOnce(ledger, traveler.getId(), txs.getTransactions(traveler.getId()),
                txs.getBalance(traveler.getId()));
    }

    @Test
    public void aPersonInTwoOrganizationsWithSeveralTripsKeepsEachOrganizationsTripsApart() {
        final Person traveler = TenancyFixtures.savedPerson("TwoMany");
        final Organization north = TenancyFixtures.org("North", traveler);
        final Organization south = TenancyFixtures.org("South", traveler);
        final Trip northOld = trip(north, -300, traveler);
        final Trip northNew = trip(north, 60, traveler);
        final Trip southOld = trip(south, -200, traveler);
        final Trip southNew = trip(south, 90, traveler);
        row(traveler.getId(), northOld, -100f, 310);
        row(traveler.getId(), northNew, -200f, 9);
        row(traveler.getId(), southOld, -300f, 205);
        row(traveler.getId(), southNew, 1000f, 8);
        final List<Person.Id> subject = List.of(traveler.getId());
        final LedgerCommands ledger = as(traveler);

        final Statement northern = ledger.statement(subject, north.getId().getValue());
        Assert.assertEquals(northern.trips().stream().map(TripGroup::tripId).toList(),
                List.of(northNew.getId(), northOld.getId()));
        Assert.assertEquals(northern.balanceCents(), -30000L);
        final Statement southern = ledger.statement(subject, south.getId().getValue());
        Assert.assertEquals(southern.trips().stream().map(TripGroup::tripId).toList(),
                List.of(southNew.getId(), southOld.getId()));
        Assert.assertEquals(southern.state(), State.CREDIT);
        Assert.assertEquals(southern.balanceCents(), 70000L);
        Assert.assertEquals(ledger.defaultOrg(subject), north.getId().getValue(), "the earlier of the two next trips");
        assertAddsUpAndEveryRowIsShownOnce(ledger, traveler.getId(), txs.getTransactions(traveler.getId()),
                txs.getBalance(traveler.getId()));
    }

    /**
     * The two invariants the migration's before/after proof rests on, for one viewer on one site: the
     * per-organization balances add up to the org-blind balance of the same reach, and every row of that reach
     * is in exactly one organization's statement (so nothing a person could see before can vanish).
     */
    private static void assertAddsUpAndEveryRowIsShownOnce(final LedgerCommands ledger, final Person.Id who,
            final List<Transaction> reach, final double orgBlind) {
        final List<Person.Id> subject = List.of(who);
        final List<OrgBalance> balances = ledger.orgBalances(subject);
        final double sum = balances.stream().mapToDouble(OrgBalance::balance).sum();
        Assert.assertEquals(sum, orgBlind, 1e-6, "the per-organization balances add up to the org-blind one");
        Assert.assertEquals(LedgerCommands.cents(sum), LedgerCommands.cents(orgBlind));
        final List<String> shown = new ArrayList<>();
        for (final OrgBalance balance : balances) {
            final Statement statement = ledger.statement(subject, balance.orgId());
            Assert.assertEquals(statement.balance(), balance.balance(), EXACT, "a statement IS its balance");
            Assert.assertEquals(statement.rowCount(), balance.rowCount());
            shown.addAll(txIds(statement));
        }
        Assert.assertEquals(shown.size(), new HashSet<>(shown).size(), "no row in two statements");
        Assert.assertEquals(new HashSet<>(shown),
                new HashSet<>(reach.stream().map(Transaction::getTxId).toList()),
                "the statements together are exactly the rows this reach lists");
    }

    // ------------------------------------------------------------------ family

    @Test
    public void aFamilyManagerSeesTheFamilysBalanceAndEachMembersShareOfIt() {
        final Person child = TenancyFixtures.savedPerson("Kid");
        final Person manager = Person.builder().first("Parent").last("T" + TenancyFixtures.unique())
                .email("tf.parent." + TenancyFixtures.unique() + "@example.com").build();
        manager.setManagedUsers(List.of(child.getId()));
        TenancyFixtures.saved(manager);
        final Person outsider = TenancyFixtures.savedPerson("Outsider");
        final Organization org = TenancyFixtures.org("Fam", manager, child, outsider);
        final Trip together = trip(org, 25, manager, child);
        row(manager.getId(), together, -1200f, 30);
        row(manager.getId(), together, 1200f, 20);
        final Transaction kids = row(child.getId(), together, -650.25f, 30);
        final List<Person.Id> family = List.of(manager.getId(), child.getId());

        final Overview page = as(manager).overview(family, null);
        Assert.assertEquals(page.orgId(), org.getId().getValue());
        Assert.assertEquals(page.statement().balanceCents(), -65025L, "the family total: each member's own rows");
        Assert.assertEquals(page.statement().balance(), txs.getFamilyBalance(family), 1e-6);
        Assert.assertTrue(page.statement().hasBreakdown());
        Assert.assertEquals(page.statement().people().stream().map(LedgerCommands.PersonBalance::personId).toList(),
                family, "in the order the subjects were given");
        final LedgerCommands.PersonBalance mine = page.statement().people().getFirst();
        Assert.assertEquals(mine.name(), "Parent " + manager.getLast());
        Assert.assertEquals(mine.state(), State.PAID_UP);
        Assert.assertEquals(mine.rowCount(), 2);
        final LedgerCommands.PersonBalance theirs = page.statement().people().get(1);
        Assert.assertEquals(theirs.balanceCents(), -65025L);
        Assert.assertEquals(theirs.balance(), -650.25d, EXACT);
        Assert.assertEquals(theirs.state(), State.OWES);
        final Row kidsRow = page.statement().trips().getFirst().rows().stream()
                .filter(shown -> shown.txId().equals(kids.getTxId())).findFirst().orElseThrow();
        Assert.assertEquals(kidsRow.personId(), child.getId(), "a family row says whose it is");
        Assert.assertEquals(kidsRow.personName(), child.getPreferredName() + " " + child.getLast());

        // A manager reads a member alone too; the member does not read the manager; a stranger reads neither.
        Assert.assertEquals(as(manager).orgBalances(List.of(child.getId())).getFirst().balanceCents(), -65025L);
        Assert.assertEquals(as(child).orgBalances(family).getFirst().balanceCents(), -65025L,
                "the child's own rows only: the manager's are not theirs to read");
        Assert.assertEquals(as(child).statement(family, org.getId().getValue()).people().getFirst().rowCount(), 0);
        Assert.assertEquals(as(outsider).orgBalances(family), List.of());
        Assert.assertEquals(as(outsider).statement(family, org.getId().getValue()), Statement.none(),
                "not even the organization's name for someone with no reach");
        Assert.assertTrue(as(manager).mayView(child.getId()));
        Assert.assertFalse(as(child).mayView(manager.getId()));
        Assert.assertFalse(as(outsider).mayView(child.getId()));
        Assert.assertFalse(as(manager).mayView(null));
    }

    // ------------------------------------------------------------------ Shared and Batch rows, credits

    @Test
    public void aSharedRowCountsThisPersonsExactShareAndABatchRowItsWholeAmount() {
        final Person anna = TenancyFixtures.savedPerson("Anna");
        final Person ben = TenancyFixtures.savedPerson("Ben");
        final Person cara = TenancyFixtures.savedPerson("Cara");
        final Organization org = TenancyFixtures.org("Share", anna, ben, cara);
        final Trip trip = trip(org, 15, anna, ben, cara);
        final List<Person.Id> three = List.of(anna.getId(), ben.getId(), cara.getId());
        final String shared = UUID.randomUUID().toString();
        final String batch = UUID.randomUUID().toString();
        // $100 split three ways is not a whole number of cents; $40 each is.
        Assert.assertTrue(txs.saveGroupTransaction(shared, List.of(), Transaction.Type.Shared,
                Transaction.TransactionType.Payment, LocalDateTime.now().minusDays(4), 100f, "Payment", "split",
                trip.getId(), null, three));
        Assert.assertTrue(txs.saveGroupTransaction(batch, List.of(), Transaction.Type.Batch,
                Transaction.TransactionType.Bill, LocalDateTime.now().minusDays(6), -40f, "Bus", "each",
                trip.getId(), null, three));
        final List<Person.Id> subject = List.of(anna.getId());
        final LedgerCommands ledger = as(anna);

        final float share = 100f / 3;
        final OrgBalance balance = ledger.orgBalances(subject).getFirst();
        Assert.assertEquals(balance.balance(), (double) -40f + (double) share, EXACT,
                "the exact Float share, not a share rounded to cents first");
        Assert.assertEquals(balance.balance(), txs.getBalance(anna.getId()), EXACT, "the same fold as getBalance");
        Assert.assertEquals(balance.balanceCents(), -667L, "rounded once, at the end: -40.00 + 33.33...");
        final List<Row> rows = ledger.statement(subject, org.getId().getValue()).trips().getFirst().rows();
        final Row batchRow = rows.getFirst();
        Assert.assertTrue(batchRow.group());
        Assert.assertFalse(batchRow.shared());
        Assert.assertEquals(batchRow.groupId(), batch);
        Assert.assertEquals(batchRow.amount(), -40d, EXACT, "a Batch row is each member's whole charge");
        final Row sharedRow = rows.get(1);
        Assert.assertTrue(sharedRow.group());
        Assert.assertTrue(sharedRow.shared());
        Assert.assertEquals(sharedRow.groupId(), shared);
        Assert.assertEquals(sharedRow.amount(), (double) share, EXACT, "a Shared row shows the share");
        // Three shares of 33.33 are 99.99: nobody's balance is "fixed up" to make the cents add to 100.00.
        final long each = as(ben).orgBalances(List.of(ben.getId())).getFirst().balanceCents();
        Assert.assertEquals(each, -667L);
        assertAddsUpAndEveryRowIsShownOnce(ledger, anna.getId(), txs.getTransactions(anna.getId()),
                txs.getBalance(anna.getId()));
    }

    @Test
    public void aCreditAndAPaidUpAccountAreToldApartFromOwing() {
        final Person ahead = TenancyFixtures.savedPerson("Ahead");
        final Person even = TenancyFixtures.savedPerson("Even");
        final Organization org = TenancyFixtures.org("Credit", ahead, even);
        final Trip trip = trip(org, 10, ahead, even);
        row(ahead.getId(), trip, -500f, 9);
        row(ahead.getId(), trip, 750.5f, 5);
        row(even.getId(), trip, -500f, 9);
        row(even.getId(), trip, 500f, 5);

        final OrgBalance credit = as(ahead).orgBalances(List.of(ahead.getId())).getFirst();
        Assert.assertEquals(credit.state(), State.CREDIT);
        Assert.assertEquals(credit.balanceCents(), 25050L);
        Assert.assertEquals(credit.magnitude(), 250.5d, EXACT);
        final Statement settled = as(even).statement(List.of(even.getId()), org.getId().getValue());
        Assert.assertEquals(settled.state(), State.PAID_UP);
        Assert.assertEquals(settled.balanceCents(), 0L);
        Assert.assertEquals(settled.charges(), settled.payments(), EXACT);
        Assert.assertEquals(State.of(0L, 0), State.NO_ACTIVITY);
        Assert.assertEquals(State.of(-1L, 1), State.OWES);
    }

    @Test
    public void aStateSpellsItselfTheSameWayWhereverThePageShowsIt() {
        // The Balance page's chip, plain-language line, family pills and closing line, word for word.
        Assert.assertEquals(State.OWES.label(), "Due");
        Assert.assertEquals(State.CREDIT.label(), "Credit");
        Assert.assertEquals(State.PAID_UP.label(), "Paid up");
        Assert.assertEquals(State.NO_ACTIVITY.label(), "No activity");
        Assert.assertEquals(List.of(State.OWES.chipStyle(), State.CREDIT.chipStyle(), State.PAID_UP.chipStyle(),
                State.NO_ACTIVITY.chipStyle()), List.of("due", "ok", "ok", ""));
        Assert.assertEquals("You " + State.OWES.phrase(true) + " Acme", "You owe Acme");
        Assert.assertEquals("Ken " + State.OWES.phrase(false) + " Acme", "Ken owes Acme");
        Assert.assertEquals(State.CREDIT.phrase(true), "have a credit with");
        Assert.assertEquals(State.CREDIT.phrase(false), "has a credit with");
        Assert.assertEquals(State.PAID_UP.phrase(true), "are all paid up with");
        Assert.assertEquals(State.PAID_UP.phrase(false), "is all paid up with");
        Assert.assertEquals(State.NO_ACTIVITY.phrase(true), "have no activity yet with");
        Assert.assertEquals(State.NO_ACTIVITY.phrase(false), "has no activity yet with");
        Assert.assertEquals(List.of(State.OWES.pillWords(), State.CREDIT.pillWords(), State.PAID_UP.pillWords(),
                State.NO_ACTIVITY.pillWords()), List.of(" due", " credit", "Paid up", "No activity"));
        Assert.assertEquals(List.of(State.OWES.closingWords(), State.CREDIT.closingWords(),
                State.PAID_UP.closingWords(), State.NO_ACTIVITY.closingWords()), List.of(" due", " credit", "", ""));
        Assert.assertEquals(List.of(State.OWES.amountStyle(), State.CREDIT.amountStyle(),
                State.PAID_UP.amountStyle(), State.NO_ACTIVITY.amountStyle()), List.of("balOwe", "balCredit", "", ""));
    }

    // ------------------------------------------------------------------ site reach

    /**
     * One person with rows in a hosted organization (Acme), a sharing tenant (CFPW) and, the shape that must
     * never vanish, a row with no organization: an org host lists its own; the shared host its tenants and the
     * org-less row under the "unassigned" pseudo-organization; with no host, everything.
     */
    @Test
    public void whatASiteListsIsWhatItsBalancesAreMadeOf() throws Exception {
        final Person traveler = TenancyFixtures.savedPerson("Reach");
        final Trip acmeTrip = trip(FakeData.ACME_ORG_ID, 40, traveler);
        final Trip cfpwTrip = trip(FakeData.CFPW_ORG_ID, 70, traveler);
        final Transaction acmeRow = row(traveler.getId(), acmeTrip, -10f, 30);
        final Transaction cfpwRow = row(traveler.getId(), cfpwTrip, -20f, 20);
        final Transaction orgless = loose(traveler.getId(), null, -40f, 10);
        final List<Person.Id> subject = List.of(traveler.getId());
        final LedgerCommands ledger = as(traveler);

        final List<OrgBalance> onAcme = onSite(ACME_SITE, () -> ledger.orgBalances(subject));
        Assert.assertEquals(orgIds(onAcme), List.of(FakeData.ACME_ORG_ID), "Acme's host: Acme's rows only");
        Assert.assertEquals(onAcme.getFirst().balanceCents(), -1000L);
        Assert.assertEquals(onSite(ACME_SITE, () -> ledger.defaultOrg(subject)), FakeData.ACME_ORG_ID);
        Assert.assertEquals(onSite(ACME_SITE, () -> ledger.statement(subject, FakeData.CFPW_ORG_ID)),
                Statement.none(), "another tenant's statement does not exist on Acme's host");
        Assert.assertEquals(onSite(ACME_SITE, () -> ledger.otherOrgBalances(subject, FakeData.ACME_ORG_ID)),
                List.of(), "and the notice never mentions an organization this site does not list");

        final List<OrgBalance> onShared = onSite(SHARED, () -> ledger.orgBalances(subject));
        Assert.assertEquals(orgIds(onShared), List.of(FakeData.CFPW_ORG_ID, LedgerCommands.UNASSIGNED_ORG_ID),
                "the shared host: its tenant, then the row with no organization, never the hosted org's");
        final OrgBalance unassigned = onShared.getLast();
        Assert.assertTrue(unassigned.unassigned());
        Assert.assertEquals(unassigned.orgName(), LedgerCommands.UNASSIGNED_ORG_NAME);
        Assert.assertEquals(unassigned.balanceCents(), -4000L);
        final Statement stray = onSite(SHARED, () -> ledger.statement(subject, LedgerCommands.UNASSIGNED_ORG_ID));
        Assert.assertTrue(stray.unassigned());
        Assert.assertEquals(txIds(stray), List.of(orgless.getTxId()), "an org-less row never vanishes");
        Assert.assertEquals(onSite(SHARED, () -> ledger.defaultOrg(subject)), FakeData.CFPW_ORG_ID,
                "the next trip (Acme's) is not reachable here, so the next reachable one decides");
        Assert.assertEquals(orgIds(onSite(SHARED, () -> ledger.otherOrgBalances(subject, FakeData.CFPW_ORG_ID))),
                List.of(LedgerCommands.UNASSIGNED_ORG_ID));

        Assert.assertEquals(orgIds(ledger.orgBalances(subject)),
                List.of(FakeData.ACME_ORG_ID, FakeData.CFPW_ORG_ID, LedgerCommands.UNASSIGNED_ORG_ID),
                "no host (mail, exports, tests): the whole ledger");
        Assert.assertEquals(ledger.defaultOrg(subject), FakeData.ACME_ORG_ID);

        // The invariants hold on every site, against the org-blind read of the SAME site.
        for (final SiteContext site : List.of(ACME_SITE, SHARED)) {
            final List<Transaction> reach = onSite(site, () -> txs.getTransactions(traveler.getId()));
            final double orgBlind = onSite(site, () -> txs.getBalance(traveler.getId()));
            onSite(site, () -> addsUp(ledger, traveler.getId(), reach, orgBlind));
        }
        assertAddsUpAndEveryRowIsShownOnce(ledger, traveler.getId(), txs.getTransactions(traveler.getId()),
                txs.getBalance(traveler.getId()));
        Assert.assertEquals(txs.getTransactions(traveler.getId()).stream().map(Transaction::getTxId).toList().size(),
                3);
        Assert.assertTrue(List.of(acmeRow.getTxId(), cfpwRow.getTxId()).stream().noneMatch(orgless.getTxId()::equals));
    }

    private static boolean addsUp(final LedgerCommands ledger, final Person.Id who, final List<Transaction> reach,
            final double orgBlind) {
        assertAddsUpAndEveryRowIsShownOnce(ledger, who, reach, orgBlind);
        return true;
    }

    // ------------------------------------------------------------------ staff reach

    @Test
    public void staffSeeOnlyTheOrganizationsTheyHaveMoneyReachIn() {
        final Person traveler = TenancyFixtures.savedPerson("Both");
        final Person admin = TenancyFixtures.savedPerson("AdminA");
        final Person peopleAdmin = TenancyFixtures.savedPerson("PeopleA");
        final Person financeViewer = TenancyFixtures.savedPerson("FinanceA");
        final Person bystander = TenancyFixtures.savedPerson("Bystander");
        final Organization mine = TenancyFixtures.org("Mine", traveler, admin, peopleAdmin, financeViewer, bystander);
        final Organization theirs = TenancyFixtures.org("Theirs", traveler, admin);
        TenancyFixtures.makeOrgAdmin(mine, admin);
        TenancyFixtures.grantPeopleAdmin(mine, peopleAdmin);
        grantViewFinances(mine, financeViewer);
        final Trip here = trip(mine, -20, traveler);
        final Trip there = trip(theirs, 15, traveler);
        final Transaction ours = row(traveler.getId(), here, -120f, 25);
        row(traveler.getId(), there, -999f, 5);
        loose(traveler.getId(), null, -5f, 1);
        final List<Person.Id> subject = List.of(traveler.getId());
        final String mineId = mine.getId().getValue();
        final String theirsId = theirs.getId().getValue();

        for (final Person staff : List.of(admin, peopleAdmin, financeViewer)) {
            final LedgerCommands ledger = as(staff);
            final List<OrgBalance> balances = ledger.orgBalances(subject);
            Assert.assertEquals(orgIds(balances), List.of(mineId),
                    staff.getFirst() + " sees their own organization only: not the other, not the org-less row");
            Assert.assertEquals(balances.getFirst().balanceCents(), -12000L);
            Assert.assertEquals(ledger.otherOrgBalances(subject, mineId), List.of(),
                    "and no notice that the person owes somebody else");
            Assert.assertEquals(ledger.statement(subject, theirsId), Statement.none(),
                    "the other organization's statement does not exist for them, name included");
            Assert.assertEquals(ledger.statement(subject, LedgerCommands.UNASSIGNED_ORG_ID), Statement.none());
            Assert.assertEquals(ledger.orgBalance(subject, theirsId).orgName(), "");
            Assert.assertEquals(ledger.orgBalance(subject, theirsId).state(), State.NO_ACTIVITY);
            final Overview page = ledger.overview(subject, theirsId);
            Assert.assertEquals(page.orgId(), mineId, "asking for the other organization lands on their own");
            Assert.assertEquals(txIds(page.statement()), List.of(ours.getTxId()));
            Assert.assertEquals(ledger.defaultOrg(subject), mineId,
                    "the person's next trip is with the other organization, which is not reachable for staff");
            Assert.assertTrue(ledger.mayView(traveler.getId()));
        }
        // Membership is not reach (it is self-grantable), and neither is being signed in.
        Assert.assertEquals(as(bystander).orgBalances(subject), List.of());
        Assert.assertFalse(as(bystander).mayView(traveler.getId()));
        final LedgerCommands anonymous = new LedgerCommands(() -> Caller.of(null));
        Assert.assertEquals(anonymous.orgBalances(subject), List.of());
        Assert.assertFalse(anonymous.mayView(traveler.getId()));
        // The person themself and a site admin: every organization, and the org-less row.
        Assert.assertEquals(orgIds(as(traveler).orgBalances(subject)),
                List.of(mineId, theirsId, LedgerCommands.UNASSIGNED_ORG_ID));
        Assert.assertEquals(orgIds(asSiteAdmin().orgBalances(subject)),
                List.of(mineId, theirsId, LedgerCommands.UNASSIGNED_ORG_ID));
        Assert.assertTrue(asSiteAdmin().mayView(traveler.getId()));
        // The admin of BOTH organizations (they are an admin of one, a plain member of the other) gains the
        // second only when they are made its admin too.
        TenancyFixtures.makeOrgAdmin(theirs, admin);
        Assert.assertEquals(orgIds(as(admin).orgBalances(subject)), List.of(mineId, theirsId));
    }

    // ------------------------------------------------------------------ the default organization

    @Test
    public void theDefaultOrganizationFollowsTheLadder() throws IOException {
        final Person traveler = TenancyFixtures.savedPerson("Ladder");
        final Organization rowsOnly = TenancyFixtures.org("Rows", traveler);
        final Organization past = TenancyFixtures.org("Past", traveler);
        final Organization later = TenancyFixtures.org("Later", traveler);
        final Organization sooner = TenancyFixtures.org("Sooner", traveler);
        final Organization pending = TenancyFixtures.org("Pending", traveler);
        final List<Person.Id> subject = List.of(traveler.getId());
        final Supplier<Caller> me = () -> TenancyFixtures.callerFor(traveler);
        final LedgerCommands ledger = new LedgerCommands(me, new CountingReads(), LocalDate::now);

        loose(traveler.getId(), rowsOnly.getId().getValue(), -1f, 0);
        awaitDefault(ledger, subject, rowsOnly.getId().getValue(), "rung 3: the latest row");

        trip(past, -500, traveler);
        final Trip recent = trip(past, -40, traveler);
        awaitDefault(ledger, subject, recent.getOrgId(), "rung 2: the most recent past trip");

        trip(later, 80, traveler);
        awaitDefault(ledger, subject, later.getId().getValue(), "rung 1: the next upcoming trip");
        trip(sooner, 12, traveler);
        awaitDefault(ledger, subject, sooner.getId().getValue(), "the EARLIEST upcoming one");

        // A registration counts as being on the trip without a roster seat, unless it says Not Registered.
        final Trip withdrawn = trip(rowsOnly, 1);
        Assert.assertTrue(DAO.getInstance().saveRegistration(new Registration(withdrawn.getId(), traveler.getId(),
                LocalDateTime.now(), Registration.Status.NOT_REGISTERED, new HashMap<>())));
        awaitDefault(ledger, subject, sooner.getId().getValue(), "a withdrawn registration is not");
        // (Filed before the trip's registrations are first read: that read tolerates the near cache.)
        final Trip applied = trip(pending, 3);
        Assert.assertTrue(DAO.getInstance().saveRegistration(new Registration(applied.getId(), traveler.getId(),
                LocalDateTime.now(), Registration.Status.PENDING, new HashMap<>())));
        awaitDefault(ledger, subject, pending.getId().getValue(), "a pending one is");
        Assert.assertTrue(orgIds(ledger.orgBalances(subject)).contains(pending.getId().getValue()),
                "and the organization is offered, with no activity yet");
        Assert.assertEquals(of(ledger.orgBalances(subject), pending.getId().getValue()).state(), State.NO_ACTIVITY);

        // "Upcoming" is judged against today: seen from after every trip, the latest START decides.
        final LedgerCommands farFuture = new LedgerCommands(me, new CountingReads(),
                () -> LocalDate.now().plusYears(2));
        Assert.assertEquals(farFuture.defaultOrg(subject), later.getId().getValue());
    }

    /**
     * The trip lists behind the ladder are read through the near cache, whose invalidation after a save is
     * asynchronous (as in production): poll until the write is seen instead of sleeping and hoping.
     */
    private static void awaitDefault(final LedgerCommands ledger, final List<Person.Id> subject,
            final String expected, final String why) {
        final long deadline = System.currentTimeMillis() + 5_000L;
        while (!expected.equals(ledger.defaultOrg(subject)) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        Assert.assertEquals(ledger.defaultOrg(subject), expected, why);
    }

    // ------------------------------------------------------------------ rows the bindings put somewhere odd

    @Test
    public void aRowIsNeverShownUnderATripOfAnotherOrganizationOrOneThatIsGone() throws IOException {
        final Person traveler = TenancyFixtures.savedPerson("Odd");
        final Organization org = TenancyFixtures.org("Odd", traveler);
        final Organization other = TenancyFixtures.org("Elsewhere", traveler);
        final Trip foreign = trip(other, 10, traveler);
        final Transaction crossed = loose(traveler.getId(), org.getId().getValue(), -30f, 4);
        Assert.assertTrue(DAO.getInstance().saveBinding(key(crossed), BindingType.TRANSACTION, foreign.getId(),
                BindingType.TRIP, true));
        final Transaction dangling = loose(traveler.getId(), org.getId().getValue(), -20f, 3);
        Assert.assertTrue(DAO.getInstance().saveBinding(key(dangling), BindingType.TRANSACTION,
                UUID.randomUUID().toString(), BindingType.TRIP, true));

        final Statement statement = as(traveler).statement(List.of(traveler.getId()), org.getId().getValue());

        Assert.assertEquals(statement.trips().size(), 1, "both rows fall into the one unassigned group");
        Assert.assertFalse(statement.trips().getFirst().assigned());
        Assert.assertEquals(new HashSet<>(txIds(statement)), Set.of(crossed.getTxId(), dangling.getTxId()));
        Assert.assertEquals(statement.balanceCents(), -5000L, "and both still count");
    }

    @Test
    public void aRowBoundToAnEventCarriesItsTitle() throws IOException {
        final Person traveler = TenancyFixtures.savedPerson("Event");
        final Organization org = TenancyFixtures.org("Event", traveler);
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("With event")
                .startDate(LocalDateTime.now().plusDays(9)).endDate(LocalDateTime.now().plusDays(12))
                .people(new ArrayList<>(List.of(traveler.getId()))).build();
        trip.setOrgId(org.getId().getValue());
        final String eventId = trip.addTripEvent(TripEvent.Type.LODGING, "Pansion Stay", "",
                LocalDateTime.now().plusDays(9), LocalDateTime.now().plusDays(12));
        saved(trip);
        final Transaction lodging = row(traveler.getId(), trip, -350f, 2);
        Assert.assertTrue(DAO.getInstance().saveBinding(key(lodging), BindingType.TRANSACTION, eventId,
                BindingType.TRIP_EVENT, true));
        final Transaction ghost = row(traveler.getId(), trip, -1f, 1);
        Assert.assertTrue(DAO.getInstance().saveBinding(key(ghost), BindingType.TRANSACTION, "no-such-event",
                BindingType.TRIP_EVENT, true));

        final List<Row> rows = as(traveler).statement(List.of(traveler.getId()), org.getId().getValue())
                .trips().getFirst().rows();

        Assert.assertEquals(rows.getFirst().eventId(), eventId);
        Assert.assertEquals(rows.getFirst().eventTitle(), "Pansion Stay");
        Assert.assertEquals(rows.get(1).eventTitle(), "", "an event that is gone has no title, and no error");
    }

    // ------------------------------------------------------------------ the Trip Contacts figures

    @Test
    public void tripContactsShowsTheTripAmountAndTheOrganizationAmount() throws Exception {
        final Person two = TenancyFixtures.savedPerson("TwoTrips");
        final Person one = TenancyFixtures.savedPerson("OneTrip");
        final Person parent = Person.builder().first("Guardian").last("T" + TenancyFixtures.unique())
                .email("tf.guardian." + TenancyFixtures.unique() + "@example.com").build();
        parent.setManagedUsers(List.of(one.getId()));
        TenancyFixtures.saved(parent);
        final Person financeViewer = TenancyFixtures.savedPerson("TripFin");
        final Person orgFinance = TenancyFixtures.savedPerson("OrgFin");
        final Person manager = TenancyFixtures.savedPerson("TripMgr");
        final Organization org = TenancyFixtures.org("Contacts", two, one, parent, financeViewer, orgFinance, manager);
        final Organization other = TenancyFixtures.org("Unrelated", two);
        final Trip trip = trip(org, 20, two, one);
        final Trip earlier = trip(org, -100, two);
        final Trip elsewhere = trip(other, 50, two);
        row(two.getId(), trip, -500f, 9);
        row(two.getId(), trip, 200f, 4);
        row(two.getId(), earlier, -100f, 120);
        row(two.getId(), elsewhere, -7000f, 2);
        row(one.getId(), trip, -500f, 9);
        grant(PrivilegeCommands.TRIP_FIN_VIEW, trip.getId(), financeViewer);
        grantViewFinances(org, orgFinance);
        grant(PrivilegeCommands.TRIP_MGR, trip.getId(), manager);

        final TripBalances all = asSiteAdmin().tripAndOrgBalances(trip.getId());
        Assert.assertEquals(all.tripId(), trip.getId());
        Assert.assertEquals(all.orgId(), org.getId().getValue());
        Assert.assertEquals(all.orgName(), org.getName());
        Assert.assertEquals(all.byPerson().keySet(), Set.of(two.getId(), one.getId()));
        final LedgerCommands.TripOrgBalance both = all.forPerson(two.getId());
        Assert.assertTrue(both.visible());
        Assert.assertEquals(both.tripCents(), -30000L, "this trip's books only");
        Assert.assertEquals(both.tripBalance(), -300d, EXACT);
        Assert.assertEquals(both.orgCents(), -40000L, "with the organization: this trip and the earlier one");
        Assert.assertEquals(both.orgBalance(), -400d, EXACT);
        Assert.assertTrue(both.differ(), "so the page shows both");
        final LedgerCommands.TripOrgBalance single = all.forPerson(one.getId());
        Assert.assertEquals(single.tripCents(), -50000L);
        Assert.assertFalse(single.differ(), "equal: the page shows the trip amount alone");
        Assert.assertFalse(all.forPerson(Person.Id.newInstance()).visible(), "nobody on the roster: hidden, not null");
        Assert.assertFalse(all.forPerson(null).visible());

        // The trip's finance viewer and the organization's finance viewer see every row.
        Assert.assertEquals(as(financeViewer).tripAndOrgBalances(trip.getId()).byPerson().size(), 2);
        Assert.assertEquals(as(orgFinance).tripAndOrgBalances(trip.getId()).forPerson(two.getId()).orgCents(),
                -40000L);
        // A traveler sees their own row, a parent their child's; a trip manager as such sees nobody's money.
        final TripBalances own = as(one).tripAndOrgBalances(trip.getId());
        Assert.assertTrue(own.forPerson(one.getId()).visible());
        Assert.assertFalse(own.forPerson(two.getId()).visible());
        Assert.assertEquals(own.forPerson(two.getId()).tripCents(), 0L, "a hidden row carries no figure");
        Assert.assertEquals(as(parent).tripAndOrgBalances(trip.getId()).byPerson().keySet(), Set.of(one.getId()));
        Assert.assertEquals(as(manager).tripAndOrgBalances(trip.getId()).byPerson(), java.util.Map.of());

        // An unknown trip, no trip, and a trip this site does not serve are all the same empty answer.
        Assert.assertEquals(asSiteAdmin().tripAndOrgBalances("no-such-trip").byPerson(), java.util.Map.of());
        Assert.assertEquals(asSiteAdmin().tripAndOrgBalances(" ").orgName(), "");
        Assert.assertNull(asSiteAdmin().tripAndOrgBalances(null).orgId());
        final Trip acmes = trip(FakeData.ACME_ORG_ID, 5, two);
        Assert.assertEquals(onSite(SHARED, () -> asSiteAdmin().tripAndOrgBalances(acmes.getId())).byPerson(),
                java.util.Map.of(), "a hosted organization's trip is not on the shared host");
    }

    /**
     * PINS an owner decision (2026-11-20), so that undoing it has to be deliberate. The reviewers read this as a
     * leak: someone whose ONLY standing is {@code tripFinView} on one trip sees the traveler's balance with the
     * whole organization on Trip Contacts and can subtract to get the other trips' net, while every other read
     * holds that role to the trip's books. The owner was shown exactly that and kept it: Trip Contacts shows both
     * amounts to everyone who sees finances there. If this test fails, the code changed, not the decision.
     */
    @Test
    public void aTripOnlyFinanceViewerGetsTheOrganizationFigureOnTripContactsByOwnerDecision() {
        final Person traveler = TenancyFixtures.savedPerson("PinTraveler");
        final Person viewer = TenancyFixtures.savedPerson("PinTripFin");
        final Organization org = TenancyFixtures.org("PinContacts", traveler);
        final Trip trip = trip(org, 20, traveler);
        final Trip another = trip(org, 60, traveler);
        row(traveler.getId(), trip, -500f, 9);
        row(traveler.getId(), trip, 200f, 4);
        row(traveler.getId(), another, -50f, 3);
        grant(PrivilegeCommands.TRIP_FIN_VIEW, trip.getId(), viewer);

        final LedgerCommands.TripOrgBalance shown = as(viewer).tripAndOrgBalances(trip.getId())
                .forPerson(traveler.getId());

        Assert.assertTrue(shown.visible(), "the money rule on this trip admits its finance viewer");
        Assert.assertEquals(shown.tripCents(), -30000L, "this trip's books");
        Assert.assertEquals(shown.orgCents(), -35000L, "AND the organization figure, the other trip's $50 in it");
        Assert.assertTrue(shown.differ(), "so the page draws the 'With {org}' line for this viewer");
        // The exception is Trip Contacts alone: everywhere else the role still reaches this trip's books only.
        Assert.assertFalse(as(viewer).mayView(traveler.getId()), "no organization reach: the balances API is 403");
        Assert.assertEquals(as(viewer).overview(List.of(traveler.getId()), null).orgs(), List.of());
        Assert.assertEquals(as(viewer).tripAndOrgBalances(another.getId()).byPerson(), java.util.Map.of(),
                "and no role on the other trip, so nothing there");
    }

    // ------------------------------------------------------------------ the trip payments report

    @Test
    public void theTripsBooksAreItsBoundRowsNotItsRostersWholeLedgers() {
        final Person onRoster = TenancyFixtures.savedPerson("Stays");
        final Person withdrew = TenancyFixtures.savedPerson("Left");
        final Person financeViewer = TenancyFixtures.savedPerson("BooksFin");
        final Organization org = TenancyFixtures.org("Books", onRoster, withdrew, financeViewer);
        final Organization other = TenancyFixtures.org("OtherBooks", onRoster);
        final Trip trip = trip(org, 14, onRoster);
        final Trip another = trip(org, -60, onRoster);
        final Trip foreign = trip(other, 30, onRoster);
        final Transaction second = row(onRoster.getId(), trip, 300f, 2);
        final Transaction first = row(onRoster.getId(), trip, -900f, 8);
        final Transaction refunded = row(withdrew.getId(), trip, 150f, 5);
        final Transaction otherTrips = row(onRoster.getId(), another, 77f, 70);
        final Transaction otherOrgs = row(onRoster.getId(), foreign, 5000f, 1);
        final Transaction tripsOwn = row(Person.Id.from(trip.getId()), trip, -2500f, 6);
        grant(PrivilegeCommands.TRIP_FIN_VIEW, trip.getId(), financeViewer);

        final List<Transaction> books = as(financeViewer).tripBooks(trip.getId());

        Assert.assertEquals(books.stream().map(Transaction::getTxId).toList(),
                List.of(first.getTxId(), refunded.getTxId(), second.getTxId()),
                "this trip's rows in date order, the traveler who left included");
        Assert.assertFalse(books.stream().anyMatch(tx -> tx.getTxId().equals(otherTrips.getTxId())
                || tx.getTxId().equals(otherOrgs.getTxId())), "never a roster member's other trips or organizations");
        Assert.assertFalse(books.stream().anyMatch(tx -> tx.getTxId().equals(tripsOwn.getTxId())),
                "nor the trip's own expense rows");
        books.add(first);       // PrimeFaces sorts the value list in place: it must be mutable
        // The old read, for contrast: every roster member's whole ledger, the other organization's row included.
        Assert.assertTrue(txs.getTripTransactions(trip.getId()).stream()
                .anyMatch(tx -> tx.getTxId().equals(otherOrgs.getTxId())));

        Assert.assertEquals(asSiteAdmin().tripBooks(trip.getId()).size(), 3);
        Assert.assertEquals(as(onRoster).tripBooks(trip.getId()), List.of(), "a traveler is not finance staff");
        Assert.assertEquals(as(financeViewer).tripBooks(another.getId()), List.of(), "a role on ONE trip");
        Assert.assertEquals(asSiteAdmin().tripBooks("no-such-trip"), List.of());
        Assert.assertEquals(asSiteAdmin().tripBooks(null), List.of());
    }

    // ------------------------------------------------------------------ store reads

    /** The real reads, counted: what the bean is allowed to ask the store for is part of its contract. */
    private static final class CountingReads implements LedgerCommands.Reads {
        private final List<Person.Id> partitions = new ArrayList<>();
        private final List<String> bindings = new ArrayList<>();
        private int registrations;
        private boolean failing;

        @Override
        public List<Transaction> partition(final Person.Id personId) {
            partitions.add(personId);
            if (failing) {
                throw new IllegalStateException("store down");
            }
            return DAO.getInstance().getTransactions(personId, Cached.NO);
        }

        @Override
        public List<String> bindings(final String id, final BindingType type, final BindingType destType) {
            bindings.add(type + ":" + id + ">" + destType);
            return DAO.getInstance().getBindings(id, type, destType, Cached.YES);
        }

        @Override
        public List<Registration> registrations(final String tripId) {
            registrations++;
            if (failing) {
                throw new IllegalStateException("store down");
            }
            return DAO.getInstance().getRegistrations(tripId, Cached.YES);
        }

        /** Read fresh: the bean's own read tolerates the near cache, which trails a trip saved a moment ago. */
        @Override
        public List<Trip> activeTrips() {
            return DAO.getInstance().getActiveTrips(LocalDateTime.now(), Cached.NO).stream()
                    .filter(TripCommands::reachesHere).toList();
        }

        void reset() {
            partitions.clear();
            bindings.clear();
            registrations = 0;
        }
    }

    @Test
    public void theBalancePageReadsOnePartitionPerSubjectAndBindingsOnlyForTheChosenOrganization() {
        final Person parent = TenancyFixtures.savedPerson("ReadsParent");
        final Person child = TenancyFixtures.savedPerson("ReadsChild");
        parent.setManagedUsers(List.of(child.getId()));
        TenancyFixtures.saved(parent);
        final Organization chosen = TenancyFixtures.org("Chosen", parent, child);
        final Organization busy = TenancyFixtures.org("Busy", parent, child);
        final Trip trip = trip(chosen, 21, parent, child);
        final Trip busyTrip = trip(busy, 200, parent, child);
        final List<Transaction> chosenRows = List.of(row(parent.getId(), trip, -100f, 5),
                row(parent.getId(), trip, 40f, 4), row(child.getId(), trip, -60f, 5));
        final List<Transaction> allRows = new ArrayList<>(chosenRows);
        for (int count = 0; count < 12; count++) {
            allRows.add(row(count % 2 == 0 ? parent.getId() : child.getId(), busyTrip, -10f, 30 + count));
        }
        final List<Person.Id> family = List.of(parent.getId(), child.getId());
        final CountingReads reads = new CountingReads();
        final LedgerCommands ledger = new LedgerCommands(() -> TenancyFixtures.callerFor(parent), reads,
                LocalDate::now);

        Assert.assertEquals(ledger.orgBalances(family).size(), 2);
        Assert.assertEquals(reads.partitions, family, "one partition read per subject");
        Assert.assertEquals(reads.bindings, List.of(), "and not one binding read: a balance needs no trip");

        reads.reset();
        Assert.assertEquals(ledger.otherOrgBalances(family, chosen.getId().getValue()).size(), 1);
        Assert.assertEquals(ledger.defaultOrg(family), chosen.getId().getValue());
        Assert.assertEquals(reads.bindings, List.of(), "nor does the notice, nor the default organization");

        reads.reset();
        final Statement statement = ledger.statement(family, chosen.getId().getValue());
        Assert.assertEquals(statement.rowCount(), 3);
        Assert.assertEquals(reads.partitions, family);
        final Set<String> chosenKeys = new HashSet<>(chosenRows.stream().map(LedgerCommandsTest::key).toList());
        Assert.assertTrue(reads.bindings.stream().allMatch(read -> chosenKeys.stream().anyMatch(read::contains)),
                "binding reads for the chosen organization's rows only: " + reads.bindings);
        Assert.assertEquals(reads.bindings.size(), 2 * chosenRows.size(), "its trip and its event, per row");

        // The whole page in ONE call: still one partition read per subject, and fewer binding reads than the
        // page it replaces, which read two bindings for every row of every organization.
        reads.reset();
        final Overview page = ledger.overview(family, null);
        Assert.assertEquals(page.orgs().size(), 2);
        Assert.assertEquals(reads.partitions, family, "everything the page shows from one read per subject");
        Assert.assertEquals(reads.bindings.size(), 2 * chosenRows.size());
        Assert.assertTrue(reads.bindings.size() < 2 * allRows.size(), "today: " + 2 * allRows.size());

        // A row with no trip costs one binding read, not two (no trip, so no event to look for).
        final Transaction stray = loose(parent.getId(), chosen.getId().getValue(), -1f, 1);
        reads.reset();
        ledger.statement(family, chosen.getId().getValue());
        Assert.assertEquals(reads.bindings.size(), 2 * chosenRows.size() + 1, "for " + stray.getTxId());
    }

    @Test
    public void thePaymentPageReadsEachMembersRowsOnceForEveryFigureItShows() {
        final Person parent = TenancyFixtures.savedPerson("PayParent");
        final Person child = TenancyFixtures.savedPerson("PayChild");
        parent.setManagedUsers(List.of(child.getId()));
        TenancyFixtures.saved(parent);
        final Organization org = TenancyFixtures.org("PayFigures", parent, child);
        final Organization other = TenancyFixtures.org("PayFiguresOther", parent, child);
        final Trip trip = trip(org, 21, parent, child);
        row(parent.getId(), trip, -100f, 5);
        row(parent.getId(), trip, 40f, 4);
        row(child.getId(), trip, -60f, 5);
        row(child.getId(), trip(other, 40, child), -7f, 6);
        final Family family = Family.builder().memberIds(List.of(parent.getId(), child.getId()))
                .managerIds(List.of(parent.getId())).build();
        final List<String> rows = List.of(parent.getId().getValue(), child.getId().getValue());
        final String orgId = org.getId().getValue();
        final CountingReads reads = new CountingReads();
        final LedgerCommands ledger = new LedgerCommands(() -> TenancyFixtures.callerFor(parent), reads,
                LocalDate::now);

        final LedgerCommands.PayBalances figures = ledger.payBalances(rows, family, true, orgId);
        Assert.assertEquals(figures.forPerson(parent.getId().getValue()).balanceCents(), -6000L);
        Assert.assertEquals(figures.forPerson(child.getId().getValue()).balanceCents(), -6000L,
                "the other organization's row is not this one's");
        Assert.assertEquals(figures.family().balanceCents(), -12000L);
        Assert.assertEquals(figures.forPerson(parent.getId().getValue()),
                ledger.orgBalance(List.of(parent.getId()), orgId), "the figure the page used to ask for per row");
        reads.reset();
        ledger.payBalances(rows, family, true, orgId);
        Assert.assertEquals(reads.partitions, List.of(parent.getId(), child.getId()),
                "three figures, one partition read per member");
        Assert.assertEquals(figures.forPerson("nobody").state(), State.NO_ACTIVITY);

        Assert.assertNull(ledger.payBalances(rows, family, false, orgId).family(), "no family line for a member");
        Assert.assertNull(ledger.payBalances(rows, null, true, orgId).family());
        Assert.assertTrue(ledger.payBalances(null, null, false, orgId).byPerson().isEmpty());
    }

    @Test
    public void tripContactsTakesOneBindingReadForTheWholeTrip() {
        final Person ada = TenancyFixtures.savedPerson("ReadsAda");
        final Person bob = TenancyFixtures.savedPerson("ReadsBob");
        final Person cy = TenancyFixtures.savedPerson("ReadsCy");
        final Organization org = TenancyFixtures.org("ReadsTrip", ada, bob, cy);
        final Trip trip = trip(org, 33, ada, bob, cy);
        int rows = 0;
        for (final Person person : List.of(ada, bob, cy)) {
            for (int count = 0; count < 5; count++) {
                row(person.getId(), trip, -25f, 10 + count);
                rows++;
            }
        }
        final CountingReads reads = new CountingReads();
        final LedgerCommands ledger = new LedgerCommands(TenancyFixtures::siteAdminCaller, reads, LocalDate::now);

        final TripBalances balances = ledger.tripAndOrgBalances(trip.getId());

        Assert.assertEquals(balances.forPerson(bob.getId()).tripCents(), -12500L);
        Assert.assertEquals(reads.bindings, List.of("TRIP:" + trip.getId() + ">TRANSACTION"),
                "ONE trip-side read for " + rows + " rows, never one per row");
        Assert.assertEquals(reads.partitions, List.of(ada.getId(), bob.getId(), cy.getId()),
                "and the one partition read per person the page already did");
        Assert.assertEquals(reads.registrations, 0);

        reads.reset();
        Assert.assertEquals(ledger.tripBooks(trip.getId()).size(), rows);
        Assert.assertEquals(reads.bindings.size(), 1, "the payments report: the same single read");
        Assert.assertEquals(reads.partitions.size(), 3, "one partition per person who has a row on the books");
    }

    @Test
    public void aStoreFailureReadsAsNoRowsRatherThanAnError() {
        final Person traveler = TenancyFixtures.savedPerson("Down");
        final Organization org = TenancyFixtures.org("Down", traveler);
        row(traveler.getId(), trip(org, 5, traveler), -10f, 1);
        final CountingReads reads = new CountingReads();
        reads.failing = true;
        final LedgerCommands ledger = new LedgerCommands(() -> TenancyFixtures.callerFor(traveler), reads,
                LocalDate::now);

        final List<OrgBalance> balances = ledger.orgBalances(List.of(traveler.getId()));

        Assert.assertEquals(balances.size(), 1, "the trip is still theirs; the rows could not be read");
        Assert.assertEquals(balances.getFirst().state(), State.NO_ACTIVITY);
        Assert.assertTrue(reads.registrations > 0, "the registration read failed too, and was survived");
    }

    @Test
    public void nobodySignedInSeesNothingAndTheOwnerSeesTheOrganizationsName() {
        // Nobody signed in sees nobody's money, not even the name of the organization asked for.
        final Person traveler = TenancyFixtures.savedPerson("Wired");
        loose(traveler.getId(), FakeData.CFPW_ORG_ID, -1f, 1);
        Assert.assertEquals(new LedgerCommands(() -> Caller.of(null)).statement(List.of(traveler.getId()),
                FakeData.CFPW_ORG_ID), Statement.none());
        Assert.assertEquals(as(traveler).orgBalances(List.of(traveler.getId())).getFirst().orgName(), "CFPW");
    }

    // ------------------------------------------------------------------ what the trip card asks of a group

    private static LedgerCommands.TripGroup group(final String tripId, final double charges, final double payments) {
        final long cents = Math.round((payments - charges) * 100d);
        return new LedgerCommands.TripGroup(tripId, "Trip " + tripId, null, null, tripId != null, List.of(), charges,
                payments, payments - charges, cents, false, false);
    }

    @Test
    public void aTripGroupSaysHowMuchOfItsChargesIsPaid() {
        Assert.assertEquals(group("t", 300d, 100d).paidPercent(), 33, "rounded down");
        Assert.assertEquals(group("t", 300d, 299.99d).paidPercent(), 99, "never full while a cent is owed");
        Assert.assertEquals(group("t", 300d, 300d).paidPercent(), 100);
        Assert.assertEquals(group("t", 300d, 450d).paidPercent(), 100, "an overpayment is still a full bar");
        Assert.assertEquals(group("t", 0d, 0d).paidPercent(), 100, "nothing charged: nothing left to pay");
        Assert.assertEquals(group("t", 0d, 50d).paidPercent(), 100);
        Assert.assertEquals(group("t", 300d, 0d).paidPercent(), 0);
        Assert.assertEquals(group("t", 300d, -20d).paidPercent(), 0, "never below empty");
        Assert.assertEquals(group("t", 1d, 1d).key(), "t");
        Assert.assertEquals(group(null, 1d, 1d).key(), LedgerCommands.TripGroup.UNASSIGNED_KEY);
    }

    @Test
    public void aStatementOpensTheTripTheVisitorCameFromElseTheFirstWithABalance() {
        final List<LedgerCommands.TripGroup> trips = List.of(group("paid", 50d, 50d), group("due", 300d, 100d),
                group("later", 80d, 0d), group(null, 10d, 0d));
        final LedgerCommands.Statement statement = new LedgerCommands.Statement("org", "Org", false,
                LedgerCommands.State.OWES, -290d, -29000L, 440d, 150d, 0, trips, List.of());
        Assert.assertEquals(statement.openKey("paid"), "paid", "the trip the visitor came from, balance or not");
        Assert.assertEquals(statement.openKey(null), "due", "else the first trip with a balance");
        Assert.assertEquals(statement.openKey("not-on-this-statement"), "due");
        final LedgerCommands.Statement settled = new LedgerCommands.Statement("org", "Org", false,
                LedgerCommands.State.PAID_UP, 0d, 0L, 50d, 50d, 0, List.of(group("paid", 50d, 50d)), List.of());
        Assert.assertEquals(settled.openKey(null), "", "nothing due and no trip asked for: every card closed");
        final LedgerCommands.Statement loose = new LedgerCommands.Statement("org", "Org", false,
                LedgerCommands.State.OWES, -10d, -1000L, 10d, 0d, 0, List.of(group(null, 10d, 0d)), List.of());
        Assert.assertEquals(loose.openKey(null), LedgerCommands.TripGroup.UNASSIGNED_KEY);
    }
}
