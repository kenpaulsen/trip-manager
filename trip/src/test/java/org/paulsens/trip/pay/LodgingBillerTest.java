package org.paulsens.trip.pay;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.action.TransactionsCommands;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/** Lodging ledger writes against the in-memory store: negative bills, idempotence, credits. */
public class LodgingBillerTest {
    private static final Person.Id ADA = Person.Id.from("biller-ada-" + System.nanoTime());
    private static final Person.Id BOB = Person.Id.from("biller-bob-" + System.nanoTime());
    private final TransactionsCommands txCmds = new TransactionsCommands();
    private final LodgingBiller biller = new LodgingBiller(txCmds);
    private String tripId;

    @BeforeClass
    public void trip() throws Exception {
        final Trip trip = Trip.builder().id("biller-trip-" + System.nanoTime()).title("Biller").build();
        trip.setOrgId(FakeData.CFPW_ORG_ID);
        assertTrue(DAO.getInstance().saveTrip(trip));
        tripId = trip.getId();
    }

    @Test
    public void billsAreNegativeIdempotentBoundAndOrgStamped() {
        final Reservation res = reservation(List.of(ADA, BOB));
        final ReservationOffer offer = ReservationOffer.builder().tripId(tripId).tripEventId("ev-1").build();
        final List<LodgingPricing.Line> lines = List.of(line(res, ADA, 11000, "Lodging: A"),
                line(res, BOB, 11000, "Lodging: A"));
        LodgingBiller.Result result = biller.applyBills(res, offer, lines);
        assertEquals(result.written(), 2);
        final Transaction ada = DAO.getInstance()
                .getTransaction(ADA, LodgingBiller.billTxId(res.getId(), ADA), Cached.NO).orElseThrow();
        assertEquals(ada.getAmount(), -110.0f, "Bills are written NEGATIVE");
        assertEquals(ada.getTxType(), Transaction.TransactionType.Bill);
        assertEquals(ada.getCategory(), LodgingBiller.CATEGORY);
        assertEquals(ada.getNote(), "Lodging: A");
        assertEquals(ada.getOrgId(), FakeData.CFPW_ORG_ID, "Stamped from the trip");
        assertTrue(txCmds.getBind().getBindings(txCmds.getBind().key(ADA.getValue(), ada.getTxId()),
                BindingType.TRANSACTION, BindingType.TRIP).contains(tripId));
        assertTrue(txCmds.getBind().getBindings(txCmds.getBind().key(ADA.getValue(), ada.getTxId()),
                BindingType.TRANSACTION, BindingType.TRIP_EVENT).contains("ev-1"));

        result = biller.applyBills(res, offer, lines);
        assertEquals(result.unchanged(), 2, "A recompute with the same answer touches nothing");
        assertEquals(result.written(), 0);
        assertEquals(result.summary(), "0 written, 2 unchanged, 0 removed");

        final LocalDateTime firstDate = ada.getTxDate();
        result = biller.applyBills(res, offer, List.of(line(res, ADA, 12000, "Lodging: B"), lines.get(1)));
        assertEquals(result.written(), 1);
        final Transaction rewritten = DAO.getInstance()
                .getTransaction(ADA, LodgingBiller.billTxId(res.getId(), ADA), Cached.NO).orElseThrow();
        assertEquals(rewritten.getAmount(), -120.0f, "Rewritten in place under the same id");
        assertEquals(rewritten.getTxDate(), firstDate, "The original bill date is kept");
        assertEquals(biller.billedByPerson(res), Map.of(ADA, 12000L, BOB, 11000L));
    }

    @Test
    public void zeroLinesRemoveStaleRowsAndSoftDeletedRowsAreNeverResurrected() {
        final Reservation res = reservation(List.of(ADA));
        final ReservationOffer offer = ReservationOffer.builder().tripId(tripId).build();
        assertEquals(biller.applyBills(res, offer, List.of(line(res, ADA, 0, "zero"))), LodgingBiller.Result.none(),
                "Nothing billed, nothing to remove");
        assertEquals(biller.applyBills(res, offer, List.of(line(res, ADA, 5000, "five"))).written(), 1);
        assertEquals(biller.applyBills(res, offer, List.of(line(res, ADA, 0, "zero"))).removed(), 1,
                "A stay that shrank to nothing soft-deletes its bill");
        assertNull(DAO.getInstance().getTransaction(ADA, LodgingBiller.billTxId(res.getId(), ADA), Cached.NO)
                .orElse(null), "A soft-deleted row reads as absent through the DAO");
        assertEquals(biller.billedByPerson(res), Map.of(ADA, 0L), "A deleted row bills nothing");
        assertEquals(biller.applyBills(res, offer, List.of(line(res, ADA, 5000, "five"))).written(), 1,
                "...and a later non-zero price writes a fresh row (cancel the reservation to stop billing)");
    }

    @Test
    public void cancelCreditsAreBilledMinusTheFeeShareAndSkipZero() {
        final Reservation res = reservation(List.of(ADA, BOB));
        final ReservationOffer offer = ReservationOffer.builder().tripId(tripId).tripEventId("ev-2").build();
        assertEquals(biller.applyBills(res, offer, List.of(line(res, ADA, 10000, "x"), line(res, BOB, 3000, "x")))
                .written(), 2);
        final LodgingBiller.Result credits = biller.applyCancelCredits(res, offer, biller.billedByPerson(res), 6001,
                "Lodging cancellation credit");
        assertEquals(credits.written(), 1, "Bob's $30 is eaten by his $30.00 fee share: no zero row");
        final Transaction credit = DAO.getInstance()
                .getTransaction(ADA, LodgingBiller.cancelTxId(res.getId(), ADA), Cached.NO).orElseThrow();
        assertEquals(credit.getAmount(), 69.99f, "$100 billed minus $30.01 (the odd cent to the lowest id)");
        assertEquals(credit.getTxType(), Transaction.TransactionType.Bill);
        assertNull(DAO.getInstance().getTransaction(BOB, LodgingBiller.cancelTxId(res.getId(), BOB), Cached.NO)
                .orElse(null));
        assertEquals(biller.applyCancelCredits(res, offer, biller.billedByPerson(res), 6001, "Lodging cancellation "
                + "credit").unchanged(), 1, "Idempotent");
        assertEquals(biller.applyCancelCredits(reservation(List.of()), null, Map.of(), 0, "n/a"),
                LodgingBiller.Result.none());
        assertEquals(LodgingBiller.cents(-1.25f), -125L, "Signed cents from the stored float");
        assertEquals(LodgingBiller.cents(69.99f), 6999L);
    }

    private Reservation reservation(final List<Person.Id> who) {
        return Reservation.builder().tripId(tripId).occupants(who).start(LocalDateTime.of(2026, 9, 21, 15, 0))
                .end(LocalDateTime.of(2026, 9, 23, 10, 0)).build();
    }

    private static LodgingPricing.Line line(final Reservation res, final Person.Id who, final long cents,
            final String desc) {
        return new LodgingPricing.Line(res.getId(), who, cents, 2, 0, desc);
    }
}
