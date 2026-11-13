package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The ledger half of {@link TripFinanceGate} (audit 2026-11-11, F3): a finance role is a role on ONE trip, and
 * it reaches a ledger row only when the row is on that trip's books. {@code TransactionsResourceTest} drives
 * the same rule through the endpoints; this pins the rule itself, including the corners no endpoint reaches.
 */
public class TripFinanceGateLedgerTest {

    private static Trip storedTrip(final Person.Id... roster) throws IOException {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Gate " + System.nanoTime())
                .startDate(LocalDateTime.now().plusDays(30)).people(new ArrayList<>(List.of(roster))).build();
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

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
    public void aFinanceRoleReachesOnlyTheRowsOnItsOwnTripsBooks() throws IOException {
        final Person.Id traveler = Person.Id.newInstance();
        final Trip mine = storedTrip(traveler);
        final Trip other = storedTrip(traveler);
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
        final Trip mine = storedTrip(traveler);
        final Trip other = storedTrip(traveler, stranger);
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

        final List<Transaction> shown = new TransactionsCommands().getTransactionsOnBooks(traveler, mine.getId());
        Assert.assertEquals(shown.stream().map(Transaction::getTxId).toList(), List.of(here.getTxId()),
                "only this trip's rows are handed to the page");
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
        final Trip mine = storedTrip();
        final Trip other = storedTrip();
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
        final Trip mine = storedTrip(onRoster);
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
