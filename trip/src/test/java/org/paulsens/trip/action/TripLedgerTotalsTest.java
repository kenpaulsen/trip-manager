package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.paulsens.trip.action.TripLedgerTotals.Bucket;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Transaction.TransactionType;
import org.paulsens.trip.model.Transaction.Type;
import org.paulsens.trip.site.SiteContext;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * The three totals of a trip's books ({@link TripLedgerTotals}): the bucket rule of the trip transactions page,
 * folded without per-row rounding, and the bean method a page reads them through.
 */
public class TripLedgerTotalsTest {
    private static final LocalDateTime WHEN = LocalDateTime.of(2024, 2, 1, 12, 0);

    private static Transaction row(final String userId, final Type type, final TransactionType txType,
            final Float amount, final List<Person.Id> members) {
        return new Transaction(null, Person.Id.from(userId), members == null ? null : "group", type, txType, WHEN,
                amount, "Synthetic", "invented", members);
    }

    @Test
    public void rowsLandInThePagesThreeBuckets() {
        final String tripId = UUID.randomUUID().toString();
        final String person = UUID.randomUUID().toString();
        final TransactionsCommands txs = new TransactionsCommands();
        final List<Transaction> rows = List.of(
                row(tripId, null, TransactionType.Bill, -500f, null),
                row(tripId, null, TransactionType.Payment, 20f, null),
                row(person, null, TransactionType.Bill, -100f, null),
                row(person, null, TransactionType.Payment, 60f, null),
                row(person, null, TransactionType.Donation, -5f, null),
                row(person, null, TransactionType.Payment, null, null));
        final TripLedgerTotals totals = TripLedgerTotals.of(tripId, rows, txs::getUserAmount);
        assertEquals(TripLedgerTotals.bucketOf(tripId, rows.get(0)), Bucket.TRIP, "the trip's own bill is a trip row");
        assertEquals(TripLedgerTotals.bucketOf(tripId, rows.get(2)), Bucket.INVOICE);
        assertEquals(TripLedgerTotals.bucketOf(tripId, rows.get(4)), Bucket.USER, "all but a bill is a user row");
        assertEquals(totals.trip(), -480d);
        assertEquals(totals.invoice(), -100d);
        assertEquals(totals.user(), 55d);
        assertEquals(totals.total(Bucket.USER), 55d);
        assertEquals(totals.cents(Bucket.TRIP), -48000);
        assertEquals(totals.rows(), 6, "a row with no amount is listed and adds nothing");
    }

    @Test
    public void sharesAreFoldedExactlyAndRoundedOnce() {
        final String tripId = UUID.randomUUID().toString();
        final List<Person.Id> three = List.of(Person.Id.newInstance(), Person.Id.newInstance(),
                Person.Id.newInstance());
        final TransactionsCommands txs = new TransactionsCommands();
        final List<Transaction> rows = three.stream()
                .map(member -> row(member.getValue(), Type.Shared, TransactionType.Payment, 100f, three))
                .toList();
        final TripLedgerTotals totals = TripLedgerTotals.of(tripId, rows, txs::getUserAmount);
        assertEquals(totals.user(), 3 * (double) (100f / 3));
        assertEquals(totals.cents(Bucket.USER), 10000, "three shares of 33.33 would have lost a cent");
        final TripLedgerTotals one = TripLedgerTotals.of(tripId, rows.subList(0, 1), txs::getUserAmount);
        assertEquals(one.cents(Bucket.USER), 3333);
    }

    @Test
    public void theBeanTotalsTheRowsATripsBindingsReach() throws IOException {
        final DAO dao = DAO.getInstance();
        final String tripId = UUID.randomUUID().toString();
        final String person = UUID.randomUUID().toString();
        final TransactionsCommands txs = new TransactionsCommands();
        final Transaction bill = row(person, null, TransactionType.Bill, -100f, null);
        final Transaction paid = row(person, null, TransactionType.Payment, 40f, null);
        final Transaction own = row(tripId, null, TransactionType.Payment, -700f, null);
        final Transaction deleted = row(person, null, TransactionType.Payment, 999f, null);
        for (final Transaction tx : List.of(bill, paid, own, deleted)) {
            assertTrue(dao.saveTransaction(tx));
            assertTrue(dao.saveBinding(tx.getUserId().getValue() + "," + tx.getTxId(), BindingType.TRANSACTION,
                    tripId, BindingType.TRIP, true));
        }
        deleted.delete();
        assertTrue(dao.saveTransaction(deleted));
        final TripLedgerTotals totals = txs.getTripLedgerTotals(tripId);
        assertEquals(totals, new TripLedgerTotals(-700d, -100d, 40d, 3), "a deleted row's binding reaches nothing");
        assertEquals(txs.getTripLedgerTotals(""), new TripLedgerTotals(0d, 0d, 0d, 0));
        assertEquals(txs.getTripLedgerTotals(null).rows(), 0);
    }

    private static <T> T onSite(final SiteContext site, final ScopedValue.CallableOp<T, Exception> body)
            throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site)).call(body);
    }

    /**
     * What {@code trip/tripTransactions.xhtml} lists, as the page itself gathers it: the trip's bindings, each
     * row through the site-filtered {@code getTransaction}, in the page's three lists. Spelled out here apart
     * from {@link TripLedgerTotals} on purpose: the page's footers are the bean's totals, and this is the check
     * that they are the totals of exactly the rows the tables above them show.
     */
    private static double[] listedByThePage(final TransactionsCommands txs, final String tripId) {
        final double[] sums = new double[4];
        for (final String key : txs.getBind().getBindings(tripId, BindingType.TRIP, BindingType.TRANSACTION)) {
            final String[] ids = key.split(",", 2);
            final Transaction tx = txs.getTransaction(Person.Id.from(ids[0]), ids[1]);
            if (tx != null) {
                final int list = ids[0].equals(tripId) ? 0 : (tx.getTxType() == TransactionType.Bill ? 1 : 2);
                sums[list] += txs.getUserAmount(tx);
                sums[3]++;
            }
        }
        return sums;
    }

    @Test
    public void theFootersAreTheTotalsOfTheRowsEachHostLists() throws Exception {
        DAO.getInstance();
        FakeData.addFakeData();
        final Person traveler = TenancyFixtures.savedPerson("Footer");
        final Organization org = TenancyFixtures.org("FooterOrg", traveler);
        final Organization elsewhere = TenancyFixtures.org("FooterElsewhere", traveler);
        final String tripId = TenancyFixtures.tripOwnedBy(org, traveler).getId();
        final String orgId = org.getId().getValue();
        final TransactionsCommands txs = new TransactionsCommands();
        final Transaction bill = row(traveler.getId().getValue(), null, TransactionType.Bill, -100f, null);
        final Transaction paid = row(traveler.getId().getValue(), null, TransactionType.Payment, 40f, null);
        final Transaction own = row(tripId, null, TransactionType.Payment, -70f, null);
        // A row on these books that names ANOTHER organization (the shape an unfinished move leaves): a host
        // that does not list that organization shows neither the row nor its amount in a footer.
        final Transaction foreign = row(traveler.getId().getValue(), null, TransactionType.Payment, 9f, null);
        for (final Transaction tx : List.of(bill, paid, own, foreign)) {
            tx.setOrgId(tx == foreign ? elsewhere.getId().getValue() : orgId);
            assertTrue(DAO.getInstance().saveTransaction(tx));
            assertTrue(DAO.getInstance().saveBinding(tx.getUserId().getValue() + "," + tx.getTxId(),
                    BindingType.TRANSACTION, tripId, BindingType.TRIP, true));
        }
        final SiteContext orgHost = SiteContext.org(org.getId(), "footer", "footer.example.test");
        final SiteContext sharedHost = SiteContext.shared("localhost");
        for (final SiteContext site : List.of(orgHost, sharedHost)) {
            final TripLedgerTotals totals = onSite(site, () -> txs.getTripLedgerTotals(tripId));
            final double[] listed = onSite(site, () -> listedByThePage(txs, tripId));
            assertEquals(totals.trip(), listed[0], "the trip footer on " + site);
            assertEquals(totals.invoice(), listed[1], "the invoice footer on " + site);
            assertEquals(totals.user(), listed[2], "the user footer on " + site);
            assertEquals(totals.rows(), (int) listed[3]);
        }
        final TripLedgerTotals onOrgHost = onSite(orgHost, () -> txs.getTripLedgerTotals(tripId));
        assertEquals(onOrgHost, new TripLedgerTotals(-70d, -100d, 40d, 3),
                "the organization's own host lists its three rows and not the other organization's");
    }
}
