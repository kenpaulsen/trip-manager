package org.paulsens.trip.action;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.Test;

import static org.paulsens.trip.action.TenancyFixtures.ledgerTrip;

/**
 * The ledger half of {@link TripFinanceGate} (audit 2026-11-11, F3): a finance role is a role on ONE trip, and
 * it reaches a ledger row only when the row is on that trip's books. {@code TransactionsResourceTest} drives
 * the same rule through the endpoints; this pins the rule itself, including the corners no endpoint reaches.
 */
public class TripFinanceGateLedgerTest {

    private static TripFinanceGate gateFor(final String privilege, final Trip trip) {
        final Person.Id who = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.getOrCreate(privilege, trip.getId(), privilege)
                .withNewPerson(who), null));
        return TripFinanceGate.forCaller(() -> TestCallers.person(who));
    }

    private static Transaction rowOnBooks(final Person.Id who, final Trip trip) {
        final Transaction row = new Transaction(who, null, null);
        final BindingCommands bind = new BindingCommands();
        bind.setBindings(bind.key(who.getValue(), row.getTxId()), BindingType.TRANSACTION, BindingType.TRIP,
                List.of(trip.getId()), true);
        return row;
    }

    @Test
    public void theMoneyPagesAgreeOnWhatIsATripHere() throws IOException {
        final Person.Id traveler = Person.Id.newInstance();
        final Trip trip = ledgerTrip(traveler);
        final String padded = " " + trip.getId() + " ";
        final TripCommands trips = new TripCommands(TenancyFixtures::asSiteAdmin, TenancyFixtures::siteAdminCaller);
        for (final String none : new String[] {null, "", "  ", UUID.randomUUID().toString()}) {
            Assert.assertNull(TripFinanceGate.knownTrip(trips, none), "'" + none + "'");
        }
        Assert.assertEquals(TripFinanceGate.knownTrip(trips, padded).getId(), trip.getId(), "padding is forgiven");

        // One ?trip= value, three askers. The pay page forgave a padded id while the Balance page and the gate
        // called it no trip, each from its own copy of the test: they share one now.
        final TripFinanceGate gate = TripFinanceGate.forCaller(TenancyFixtures::siteAdminCaller);
        final LedgerCommands ledger = new LedgerCommands(TenancyFixtures::siteAdminCaller);
        final PayPageCommands pay = new PayPageCommands(TenancyFixtures::siteAdminCaller);
        Assert.assertEquals(gate.knownTrip(padded).getId(), trip.getId());
        Assert.assertTrue(gate.seesMoney(padded));
        Assert.assertEquals(pay.orgIdFor(padded, null), FakeData.CFPW_ORG_ID);
        Assert.assertEquals(ledger.tripAndOrgBalances(padded).tripId(), trip.getId());
    }

    @Test
    public void theReminderGateAndTheLodgingReportsAskTheSameTest() throws IOException {
        final Trip trip = storedTrip(Person.Id.newInstance());
        final String padded = " " + trip.getId() + " ";
        final ReminderGate reminders = new ReminderGate(TenancyFixtures::siteAdminCaller,
                () -> new TripCommands(TenancyFixtures::asSiteAdmin, TenancyFixtures::siteAdminCaller),
                TenancyFixtures::asSiteAdmin);
        // Each kept a copy without the trim, so the id the money pages took was no trip to the Communications page.
        Assert.assertEquals(reminders.knownTrip(padded).getId(), trip.getId());
        Assert.assertNull(reminders.knownTrip("  "));
        Assert.assertNull(reminders.knownTrip(null));

        // The reports' copy dereferenced getTrip's answer: a null from it was a 500, not "no such trip".
        final ReportCommands reports = new ReportCommands(() -> new NoTrips(), LodgingCommands::new);
        Assert.assertTrue(reports.roomInvoice(trip.getId()).getSections().isEmpty());
        Assert.assertTrue(reports.billedVsCost(trip.getId()).getSections().isEmpty());
    }

    /** Trips that answer null where the real bean answers a blank trip. */
    private static final class NoTrips extends TripCommands {
        @Override
        public Trip getTrip(final String id) {
            return null;
        }
    }

    /**
     * Who the Balance page's {@code ?trip=} form opens for (a site admin, the trip's exact Finance Viewer) and whom
     * it is about: someone on the trip, or someone with a row on its books after leaving it.
     */
    @Test
    public void theTripLedgerFormOpensForTheTripsFinanceViewerAndReachesWhoeverIsOnItsBooks() throws IOException {
        final Person.Id traveler = Person.Id.newInstance();
        final Person.Id withdrawn = Person.Id.newInstance();
        final Trip trip = ledgerTrip(traveler);
        Assert.assertTrue(gateFor(PrivilegeCommands.TRIP_FIN_VIEW, trip).opensTripLedger(trip.getId()));
        Assert.assertFalse(gateFor(PrivilegeCommands.TRIP_FIN_ADMIN, trip).opensTripLedger(trip.getId()),
                "Finance Admin does not imply the viewer role");
        Assert.assertFalse(gateFor(PrivilegeCommands.TRIP_FIN_VIEW, trip).opensTripLedger(null));
        Assert.assertFalse(gateFor(PrivilegeCommands.TRIP_FIN_VIEW, trip).opensTripLedger(" "));
        Assert.assertTrue(TripFinanceGate.forCaller(TenancyFixtures::siteAdminCaller).opensTripLedger(null));
        Assert.assertFalse(TripFinanceGate.forCaller(() -> Caller.of(null)).opensTripLedger(trip.getId()),
                "nobody signed in");

        Assert.assertTrue(TripFinanceGate.onTripLedger(trip, traveler), "on the roster");
        Assert.assertFalse(TripFinanceGate.onTripLedger(trip, withdrawn), "neither on the roster nor the books");
        rowOnBooks(withdrawn, trip);
        Assert.assertTrue(TripFinanceGate.onTripLedger(trip, withdrawn), "off the roster, a row on the books");
        Assert.assertFalse(TripFinanceGate.onTripLedger(null, traveler));
        Assert.assertFalse(TripFinanceGate.onTripLedger(trip, null));
        Assert.assertTrue(new TransactionsCommands().onTripLedger(trip, withdrawn), "the page's door to it");
    }

    @Test
    public void aFinanceRoleReachesOnlyTheRowsOnItsOwnTripsBooks() throws IOException {
        final Person.Id traveler = Person.Id.newInstance();
        final Trip mine = ledgerTrip(traveler);
        final Trip other = ledgerTrip(traveler);
        final Transaction here = rowOnBooks(traveler, mine);
        final Transaction elsewhere = rowOnBooks(traveler, other);
        final Transaction unbound = new Transaction(traveler, null, null);
        final TripFinanceGate viewer = gateFor(PrivilegeCommands.TRIP_FIN_VIEW, mine);
        final TripFinanceGate admin = gateFor(PrivilegeCommands.TRIP_FIN_ADMIN, mine);

        Assert.assertTrue(viewer.canReadLedgers(mine.getId()));
        Assert.assertFalse(viewer.canWriteLedgers(mine.getId()), "a finance viewer writes nothing");
        Assert.assertTrue(viewer.mayReadRow(mine.getId(), here));
        Assert.assertFalse(viewer.mayReadRow(mine.getId(), elsewhere), "another trip's books");
        Assert.assertFalse(viewer.mayReadRow(mine.getId(), unbound), "a row on no trip's books is nobody's staff's");
        Assert.assertFalse(viewer.mayReadRow(other.getId(), elsewhere), "no role on that trip");
        Assert.assertFalse(viewer.mayWriteRow(mine.getId(), here));

        Assert.assertTrue(admin.canReadLedgers(mine.getId()), "an admin reads what it may write");
        Assert.assertTrue(admin.mayWriteRow(mine.getId(), here));
        Assert.assertFalse(admin.mayWriteRow(mine.getId(), elsewhere));
        Assert.assertFalse(admin.canWriteLedgers(null), "no trip named, no role");
        Assert.assertFalse(admin.canReadLedgers(UUID.randomUUID().toString()), "a trip nobody created");
    }

    /**
     * The website's half of the same rule: the transaction page opens a row for a trip's finance admin only when
     * the row is on that trip's books (a new row: when its person is on the trip), and the ledger page hands a
     * trip's finance staff only that trip's rows instead of the whole ledger with the rest hidden by a style.
     */
    @Test
    public void theTransactionPagesHoldATripsFinanceStaffToThatTripsBooks() throws IOException {
        final Person.Id traveler = Person.Id.newInstance();
        final Person.Id stranger = Person.Id.newInstance();
        final Trip mine = ledgerTrip(traveler);
        final Trip other = ledgerTrip(traveler, stranger);
        final Transaction here = saved(rowOnBooks(traveler, mine));
        final Transaction elsewhere = saved(rowOnBooks(traveler, other));
        final TripFinanceGate admin = gateFor(PrivilegeCommands.TRIP_FIN_ADMIN, mine);
        final TripFinanceGate viewer = gateFor(PrivilegeCommands.TRIP_FIN_VIEW, mine);

        Assert.assertTrue(TransactionsCommands.tripStaffMayOpen(admin, mine.getId(), traveler, here.getTxId()));
        Assert.assertFalse(TransactionsCommands.tripStaffMayOpen(admin, mine.getId(), traveler, elsewhere.getTxId()),
                "a row on another trip's books, opened with a role on mine");
        Assert.assertFalse(TransactionsCommands.tripStaffMayOpen(admin, mine.getId(), traveler, "no-such-row"));
        Assert.assertTrue(TransactionsCommands.tripStaffMayOpen(admin, mine.getId(), traveler, null),
                "a new row for someone on the trip");
        Assert.assertFalse(TransactionsCommands.tripStaffMayOpen(admin, mine.getId(), stranger, " "),
                "a new row for someone who is not on it");
        Assert.assertFalse(TransactionsCommands.tripStaffMayOpen(viewer, mine.getId(), traveler, here.getTxId()),
                "a finance viewer opens nothing for writing");
        Assert.assertFalse(TransactionsCommands.tripStaffMayOpen(admin, null, traveler, here.getTxId()));
        Assert.assertFalse(TransactionsCommands.tripStaffMayOpen(admin, mine.getId(), null, here.getTxId()));

        final List<Transaction> shown = new TransactionsCommands().getTransactionsOnBooks(admin, traveler,
                mine.getId());
        Assert.assertEquals(shown.stream().map(Transaction::getTxId).toList(), List.of(here.getTxId()),
                "only this trip's rows are handed to the page");
        // The trip is asked about in the bean too: the page's filter menu once chose it.
        final TransactionsCommands transactions = new TransactionsCommands();
        Assert.assertTrue(transactions.getTransactionsOnBooks(admin, traveler, other.getId()).isEmpty(),
                "no finance role on that trip, so none of its rows");
        Assert.assertTrue(transactions.getTransactionsOnBooks(viewer, null, mine.getId()).isEmpty());
        Assert.assertEquals(transactions.getTransactionsOnBooks(viewer, traveler, mine.getId()).size(), 1);
    }

    /**
     * The transaction page's Save binds the row to a trip, so the bean asks whether THIS caller may put the row
     * on THAT trip's books. The page asked once about the trip in the URL and then offered a Trip menu.
     */
    @Test
    public void thePageSavePutsARowOnlyOnBooksTheCallerAdministers() throws IOException {
        final Person.Id traveler = Person.Id.newInstance();
        final Person.Id stranger = Person.Id.newInstance();
        final Trip mine = ledgerTrip(traveler);
        final Trip other = ledgerTrip(traveler, stranger);
        final TripFinanceGate admin = gateFor(PrivilegeCommands.TRIP_FIN_ADMIN, mine);
        final TransactionsCommands transactions = new TransactionsCommands();

        final Transaction fresh = payment(traveler);
        Assert.assertFalse(transactions.saveFromPage(admin, fresh, other.getId()),
            "a new row bound to a trip whose finances the caller does not administer");
        Assert.assertTrue(DAO.getInstance().getTransaction(traveler, fresh.getTxId(), Cached.NO).isEmpty());
        Assert.assertFalse(transactions.saveFromPage(admin, payment(stranger), mine.getId()),
                "nor a row for someone who is not on the caller's trip");
        Assert.assertFalse(transactions.saveFromPage(admin, fresh, null), "nor a row on no trip's books");
        Assert.assertFalse(transactions.saveFromPage(admin, null, mine.getId()));
        Assert.assertTrue(transactions.saveFromPage(admin, fresh, mine.getId()));

        // An existing row stays on the books it is on: its own trip's admin changes it, nobody moves it.
        final Transaction here = saved(rowOnBooks(traveler, mine));
        final Transaction elsewhere = saved(rowOnBooks(traveler, other));
        here.setAmount(25f);
        Assert.assertTrue(transactions.saveFromPage(admin, here, mine.getId()));
        Assert.assertFalse(transactions.saveFromPage(gateFor(PrivilegeCommands.TRIP_FIN_ADMIN, other), here,
                other.getId()), "another trip's admin cannot take a row onto their books");
        Assert.assertFalse(transactions.saveFromPage(admin, elsewhere, mine.getId()));
        final TripFinanceGate siteAdmin = TripFinanceGate.forCaller(TenancyFixtures::siteAdminCaller);
        Assert.assertTrue(transactions.saveFromPage(siteAdmin, elsewhere, mine.getId()), "a site admin may");
    }

    private static Transaction payment(final Person.Id who) {
        final Transaction row = new Transaction(who, null, null);
        row.setAmount(10f);
        row.setCategory("Payment");
        return row;
    }

    private static Transaction saved(final Transaction row) throws IOException {
        row.setAmount(10f);
        row.setCategory("Payment");
        Assert.assertTrue(DAO.getInstance().saveTransaction(row));
        return row;
    }

    /** The trip's own ledger keys its rows by the trip id (the page's "trip as person"): those are its books. */
    @Test
    public void theTripsOwnLedgerRowsAreOnItsBooks() throws IOException {
        final Trip mine = ledgerTrip();
        final Trip other = ledgerTrip();
        final Transaction tripsOwn = new Transaction(Person.Id.from(mine.getId()), null, null);
        final TripFinanceGate admin = gateFor(PrivilegeCommands.TRIP_FIN_ADMIN, mine);

        Assert.assertTrue(TripFinanceGate.onBooks(mine.getId(), tripsOwn));
        Assert.assertFalse(TripFinanceGate.onBooks(other.getId(), tripsOwn));
        Assert.assertTrue(admin.mayWriteRow(mine.getId(), tripsOwn));
        Assert.assertTrue(admin.mayCharge(mine.getId(), Person.Id.from(mine.getId())));
        Assert.assertFalse(TripFinanceGate.onBooks(null, tripsOwn));
        Assert.assertFalse(TripFinanceGate.onBooks(mine.getId(), null));
    }

    @Test
    public void aChargeNeedsItsPersonOnTheTripAndASiteAdminNeedsNoTrip() throws IOException {
        final Person.Id onRoster = Person.Id.newInstance();
        final Person.Id pending = Person.Id.newInstance();
        final Person.Id stranger = Person.Id.newInstance();
        final Trip mine = ledgerTrip(onRoster);
        Assert.assertTrue(DAO.getInstance().saveRegistration(new Registration(mine.getId(), pending, null,
                Registration.Status.PENDING, new HashMap<>())));
        final TripFinanceGate admin = gateFor(PrivilegeCommands.TRIP_FIN_ADMIN, mine);

        Assert.assertTrue(admin.mayCharge(mine.getId(), onRoster));
        Assert.assertTrue(admin.mayCharge(mine.getId(), pending));
        Assert.assertFalse(admin.mayCharge(mine.getId(), stranger));
        Assert.assertFalse(admin.mayCharge(mine.getId(), null));
        Assert.assertFalse(admin.mayCharge(null, onRoster));
        Assert.assertFalse(TripCommands.isOnTrip(null, onRoster));
        Assert.assertFalse(TripCommands.isOnTrip(mine, null));

        final TripFinanceGate siteAdmin = TripFinanceGate.forCaller(TestCallers::siteAdmin);
        final Transaction anyRow = new Transaction(stranger, null, null);
        Assert.assertTrue(siteAdmin.canReadLedgers(null));
        Assert.assertTrue(siteAdmin.mayReadRow(null, anyRow));
        Assert.assertTrue(siteAdmin.mayWriteRow("no-such-trip", anyRow));
        Assert.assertTrue(siteAdmin.mayCharge(null, stranger));
        Assert.assertNull(siteAdmin.knownTrip(null));
    }
}
