package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.FeesPaidBy;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.ProcessorType;
import org.paulsens.trip.pay.PaymentRecorder;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * What keeps "a balance names one organization" true after the data is clean: every NEW ledger row names a real
 * trip that has an organization and takes that organization; a stored row never silently loses its trip (the
 * trip menu's blank row used to unbind it); no row moves to another organization's trip; and a trip that moves
 * to another organization takes its rows along, which only a site admin may do.
 */
public class LedgerWriteInvariantsTest {

    private final TransactionsCommands txs = new TransactionsCommands();
    private final TripFinanceGate siteAdmin = TripFinanceGate.forCaller(TenancyFixtures::siteAdminCaller);

    @BeforeClass
    public void seed() {
        DAO.getInstance();
        FakeData.addFakeData();
    }

    private static Transaction payment(final Person.Id who, final float amount) {
        final Transaction tx = new Transaction(who, null, null);
        tx.setAmount(amount);
        tx.setNote("invariant " + TenancyFixtures.unique());
        return tx;
    }

    private static Transaction stored(final Transaction tx) {
        return DAO.getInstance().getTransaction(tx.getUserId(), tx.getTxId(), Cached.NO).orElse(null);
    }

    private List<String> tripsOf(final Transaction tx) {
        return DAO.getInstance().getBindings(txs.getBind().key(tx.getUserId().getValue(), tx.getTxId()),
                BindingType.TRANSACTION, BindingType.TRIP, Cached.NO);
    }

    private static Trip orglessTrip() throws IOException {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("No org").build();
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    // ------------------------------------------------------------------ the transaction page

    @Test
    public void aNewRowNeedsARealTripThatHasAnOrganizationAndTakesItsOrganization() throws IOException {
        final Person traveler = TenancyFixtures.savedPerson("New");
        final Organization org = TenancyFixtures.org("Inv", traveler);
        final Trip trip = TenancyFixtures.tripOwnedBy(org, traveler);

        for (final String noTrip : new String[] {null, "", "  ", "none", "NONE"}) {
            final Transaction refused = payment(traveler.getId(), 10f);
            Assert.assertFalse(txs.saveFromPage(siteAdmin, refused, noTrip), "no trip ('" + noTrip + "')");
            Assert.assertNull(stored(refused), "nothing is written for a refused row");
        }
        final Transaction unknown = payment(traveler.getId(), 10f);
        Assert.assertFalse(txs.saveFromPage(siteAdmin, unknown, UUID.randomUUID().toString()), "no such trip");
        Assert.assertFalse(txs.saveFromPage(siteAdmin, unknown, orglessTrip().getId()), "a trip with no org");
        Assert.assertNull(stored(unknown));

        final Transaction fresh = payment(traveler.getId(), 25f);
        fresh.setOrgId("whatever-the-page-held");
        Assert.assertTrue(txs.saveFromPage(siteAdmin, fresh, trip.getId()));
        Assert.assertEquals(stored(fresh).getOrgId(), org.getId().getValue(), "the trip's organization, always");
        Assert.assertEquals(tripsOf(fresh), List.of(trip.getId()), "and bound to the trip by the save itself");

        Assert.assertEquals(TransactionsCommands.tripProblem(null, null), TransactionsCommands.NEEDS_TRIP);
        Assert.assertEquals(TransactionsCommands.tripProblem(null, "nope"), TransactionsCommands.NOT_A_LEDGER_TRIP);
        Assert.assertNull(TransactionsCommands.tripProblem(null, trip.getId()));
        Assert.assertNull(TransactionsCommands.ledgerTrip("none"));
        Assert.assertEquals(TransactionsCommands.ledgerTrip(trip.getId()).getId(), trip.getId());
        Assert.assertTrue(TransactionsCommands.isNoTrip(" None "));
        Assert.assertFalse(TransactionsCommands.isNoTrip(trip.getId()));
        Assert.assertNull(TransactionsCommands.boundTripOf(null));
    }

    @Test
    public void aStoredRowSavedWithNoTripChosenKeepsItsTripAndItsOrganization() {
        final Person traveler = TenancyFixtures.savedPerson("Keep");
        final Organization org = TenancyFixtures.org("Keep", traveler);
        final Trip trip = TenancyFixtures.tripOwnedBy(org, traveler);
        final Transaction row = payment(traveler.getId(), 100f);
        Assert.assertTrue(txs.saveFromPage(siteAdmin, row, trip.getId()));

        // The page's edit of a stored row with the Trip menu on its blank row: "none" reaches both the save and
        // the page's own binding write, and used to take the row off its trip.
        row.setAmount(120f);
        row.setOrgId(null);
        Assert.assertTrue(txs.saveFromPage(siteAdmin, row, "none"));
        txs.getBind().setBindings(txs.getBind().key(traveler.getId().getValue(), row.getTxId()),
                BindingType.TRANSACTION, BindingType.TRIP, List.of("none"), true);

        Assert.assertEquals(stored(row).getAmount(), 120f, "the edit is saved");
        Assert.assertEquals(stored(row).getOrgId(), org.getId().getValue(), "the stored organization is kept");
        Assert.assertEquals(tripsOf(row), List.of(trip.getId()), "and the row is still on its trip's books");
        Assert.assertTrue(DAO.getInstance().getBindings(trip.getId(), BindingType.TRIP, BindingType.TRANSACTION,
                Cached.NO).contains(txs.getBind().key(traveler.getId().getValue(), row.getTxId())));
        Assert.assertTrue(txs.saveFromPage(siteAdmin, row, null), "null is no trip chosen, like the blank row");
        Assert.assertEquals(tripsOf(row), List.of(trip.getId()));

        // The trip's own finance admin may make that same edit: the row is judged against the trip it is on.
        final Person finance = TenancyFixtures.savedPerson("KeepFin");
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.getOrCreate(PrivilegeCommands.TRIP_FIN_ADMIN, trip.getId(), "fin")
                .withNewPerson(finance.getId()), null));
        final TripFinanceGate tripAdmin = TripFinanceGate.forCaller(() -> TenancyFixtures.callerFor(finance));
        Assert.assertTrue(txs.saveFromPage(tripAdmin, row, "none"));
        final Transaction mine = payment(traveler.getId(), 5f);
        Assert.assertFalse(txs.saveFromPage(tripAdmin, mine, "none"), "but a NEW row with no trip is nobody's");

        // Deleting the row is the one way it leaves its trip.
        Assert.assertTrue(txs.deleteForPerson(stored(row), TenancyFixtures.siteAdminCaller().auditActor()));
        Assert.assertEquals(tripsOf(row), List.of());
    }

    @Test
    public void aStoredRowMovesToAnotherTripOfItsOrganizationButNeverToAnotherOrganizations() {
        final Person traveler = TenancyFixtures.savedPerson("Move");
        final Organization org = TenancyFixtures.org("MoveA", traveler);
        final Organization other = TenancyFixtures.org("MoveB", traveler);
        final Trip first = TenancyFixtures.tripOwnedBy(org, traveler);
        final Trip second = TenancyFixtures.tripOwnedBy(org, traveler);
        final Trip foreign = TenancyFixtures.tripOwnedBy(other, traveler);
        final Transaction row = payment(traveler.getId(), 60f);
        Assert.assertTrue(txs.saveFromPage(siteAdmin, row, first.getId()));

        Assert.assertTrue(txs.saveFromPage(siteAdmin, row, second.getId()), "a site admin re-homes it in the org");
        Assert.assertEquals(tripsOf(row), List.of(second.getId()));
        Assert.assertFalse(txs.saveFromPage(siteAdmin, row, foreign.getId()),
                "another organization's trip: its org stamp would disagree with its trip");
        Assert.assertEquals(tripsOf(row), List.of(second.getId()));
        Assert.assertEquals(stored(row).getOrgId(), org.getId().getValue());
        Assert.assertEquals(TransactionsCommands.tripProblem(stored(row), foreign.getId()),
                TransactionsCommands.OTHER_ORG_TRIP);
        Assert.assertFalse(txs.saveFromPage(siteAdmin, row, UUID.randomUUID().toString()), "nor a trip that is gone");
    }

    // ------------------------------------------------------------------ the group editor

    @Test
    public void aGroupSaveHoldsEveryRowToTheSameRule() throws IOException {
        final Person anna = TenancyFixtures.savedPerson("GAnna");
        final Person ben = TenancyFixtures.savedPerson("GBen");
        final Person cara = TenancyFixtures.savedPerson("GCara");
        final Organization org = TenancyFixtures.org("Grp", anna, ben, cara);
        final Organization other = TenancyFixtures.org("GrpOther", anna);
        final Trip trip = TenancyFixtures.tripOwnedBy(org, anna, ben, cara);
        final Trip foreign = TenancyFixtures.tripOwnedBy(other, anna);
        final String gid = UUID.randomUUID().toString();
        final List<Person.Id> two = List.of(anna.getId(), ben.getId());
        final List<Person.Id> three = List.of(anna.getId(), ben.getId(), cara.getId());

        Assert.assertFalse(save(gid, List.of(), "none", two), "a new group must name its trip");
        Assert.assertFalse(save(gid, List.of(), null, two));
        Assert.assertFalse(save(gid, List.of(), orglessTrip().getId(), two), "one that has an organization");
        Assert.assertTrue(txs.getGroupTransactionForUser(anna.getId(), gid).isEmpty(), "nothing was written");
        // Why each was refused is the SAVE's own answer (the path the pages and the REST resource take).
        Assert.assertEquals(refusal(null, List.of(), "none", two), TransactionsCommands.NEEDS_TRIP);
        Assert.assertEquals(refusal(gid, List.of(), "nope", two), TransactionsCommands.NOT_A_LEDGER_TRIP);
        Assert.assertNull(refusal(gid, List.of(), trip.getId(), null), "nobody selected breaks no trip rule");

        Assert.assertTrue(save(gid, List.of(), trip.getId(), two));
        final Transaction annas = txs.getGroupTransactionForUser(anna.getId(), gid).orElseThrow();
        Assert.assertEquals(annas.getOrgId(), org.getId().getValue());
        Assert.assertEquals(tripsOf(annas), List.of(trip.getId()));

        // The editor re-saved with the Trip menu blank: the rows keep their trip, and a member being ADDED
        // joins the trip the group is on (and takes its organization).
        Assert.assertTrue(save(gid, two, "none", two));
        Assert.assertEquals(tripsOf(annas), List.of(trip.getId()), "the blank row no longer unbinds the group");
        Assert.assertTrue(save(gid, two, "none", three));
        final Transaction caras = txs.getGroupTransactionForUser(cara.getId(), gid).orElseThrow();
        Assert.assertEquals(tripsOf(caras), List.of(trip.getId()));
        Assert.assertEquals(caras.getOrgId(), org.getId().getValue());

        Assert.assertFalse(save(gid, three, foreign.getId(), three), "never onto another organization's trip");
        Assert.assertEquals(refusal(gid, three, foreign.getId(), three), TransactionsCommands.OTHER_ORG_TRIP);
        Assert.assertEquals(tripsOf(annas), List.of(trip.getId()));

        // A group that is on NO trip (rows from before the rule) is not edited, and gains nobody, until it
        // names one.
        final String legacy = UUID.randomUUID().toString();
        final Transaction old = new Transaction(anna.getId(), legacy, Transaction.Type.Batch);
        old.setAmount(-5f);
        old.setGroupPeople(List.of(anna.getId()));
        old.setOrgId(org.getId().getValue());
        Assert.assertTrue(DAO.getInstance().saveTransaction(old));
        Assert.assertFalse(save(legacy, List.of(anna.getId()), "none", List.of(anna.getId())),
                "an edit of a trip-less group is refused");
        Assert.assertEquals(stored(old).getAmount(), -5f, "and nothing was written");
        Assert.assertEquals(refusal(legacy, List.of(anna.getId()), null, List.of(anna.getId())),
                TransactionsCommands.NEEDS_TRIP);
        Assert.assertFalse(save(legacy, List.of(anna.getId()), "none", two), "nobody joins a trip-less group");
        Assert.assertTrue(save(legacy, List.of(anna.getId()), trip.getId(), two), "until its trip is named");
        Assert.assertEquals(tripsOf(old), List.of(trip.getId()));
        Assert.assertEquals(stored(old).getAmount(), -30f);
    }

    // ------------------------------------------------------------------ stored rows that are on no trip

    /** A stored row from before the rule: an organization, no trip binding. */
    private static Transaction tripLess(final Person.Id who, final String orgId, final String groupId,
            final Transaction.Type type, final List<Person.Id> members) throws IOException {
        final Transaction tx = new Transaction(who, groupId, type);
        tx.setAmount(-90f);
        tx.setNote("legacy " + TenancyFixtures.unique());
        tx.setOrgId(orgId);
        tx.setGroupPeople(members);
        Assert.assertTrue(DAO.getInstance().saveTransaction(tx));
        return tx;
    }

    @Test
    public void aStoredRowOnNoTripIsNotEditedUntilItsTripIsNamedButIsStillDeleted() throws IOException {
        final Person traveler = TenancyFixtures.savedPerson("Loose");
        final Organization org = TenancyFixtures.org("Loose", traveler);
        final Organization other = TenancyFixtures.org("LooseOther", traveler);
        final Trip trip = TenancyFixtures.tripOwnedBy(org, traveler);
        final Trip foreign = TenancyFixtures.tripOwnedBy(other, traveler);
        final String orgId = org.getId().getValue();
        final Transaction row = tripLess(traveler.getId(), orgId, null, null, null);

        for (final String noTrip : new String[] {null, "", "none"}) {
            row.setAmount(-1f);
            Assert.assertFalse(txs.saveFromPage(siteAdmin, row, noTrip), "no trip ('" + noTrip + "')");
            Assert.assertEquals(stored(row).getAmount(), -90f, "the refused edit wrote nothing");
        }
        Assert.assertEquals(TransactionsCommands.tripProblem(stored(row), "none"), TransactionsCommands.NEEDS_TRIP);
        Assert.assertFalse(txs.saveFromPage(siteAdmin, row, foreign.getId()), "nor onto another organization's");
        Assert.assertEquals(tripsOf(row), List.of());

        row.setAmount(-95f);
        Assert.assertTrue(txs.saveFromPage(siteAdmin, row, trip.getId()), "a real trip of its organization");
        Assert.assertEquals(stored(row).getAmount(), -95f);
        Assert.assertEquals(tripsOf(row), List.of(trip.getId()));
        Assert.assertNull(TransactionsCommands.tripProblem(stored(row), "none"), "on a trip now: it keeps it");
        row.setAmount(-96f);
        Assert.assertTrue(txs.saveFromPage(siteAdmin, row, "none"));

        // Deleting is not an edit: the rows still waiting for their trip must stay deletable.
        final Transaction doomed = tripLess(traveler.getId(), orgId, null, null, null);
        Assert.assertTrue(txs.deleteForPerson(stored(doomed), TenancyFixtures.siteAdminCaller().auditActor()));
        Assert.assertNull(stored(doomed));
    }

    @Test
    public void aGroupOnNoTripStillShrinksAndIsStillDeleted() throws IOException {
        final Person anna = TenancyFixtures.savedPerson("ShrinkAnna");
        final Person ben = TenancyFixtures.savedPerson("ShrinkBen");
        final Person cara = TenancyFixtures.savedPerson("ShrinkCara");
        final Organization org = TenancyFixtures.org("Shrink", anna, ben, cara);
        final String orgId = org.getId().getValue();
        final List<Person.Id> three = List.of(anna.getId(), ben.getId(), cara.getId());
        final String gid = UUID.randomUUID().toString();
        final Transaction annas = tripLess(anna.getId(), orgId, gid, Transaction.Type.Shared, three);
        final Transaction bens = tripLess(ben.getId(), orgId, gid, Transaction.Type.Shared, three);
        final Transaction caras = tripLess(cara.getId(), orgId, gid, Transaction.Type.Shared, three);
        final org.paulsens.trip.audit.AuditActor actor = TenancyFixtures.siteAdminCaller().auditActor();

        Assert.assertFalse(txs.saveGroupTransaction(gid, three, Transaction.Type.Shared,
                Transaction.TransactionType.Bill, LocalDateTime.now(), -120f, "Bus", "edited", "none", null,
                List.of(anna.getId(), ben.getId())), "an edit through the group editor is refused");
        Assert.assertEquals(stored(caras).getAmount(), -90f, "and the member it would have dropped is still there");
        Assert.assertEquals(stored(annas).getGroupPeople(), three);

        // Removing one member: their row goes and the survivors' membership (the Shared divisor) is restamped,
        // a re-save of two trip-less rows that is not an edit.
        Assert.assertTrue(txs.removeFromGroup(stored(caras), actor));
        Assert.assertNull(stored(caras));
        Assert.assertEquals(stored(annas).getGroupPeople(), List.of(anna.getId(), ben.getId()));
        Assert.assertEquals(stored(bens).getGroupPeople(), List.of(anna.getId(), ben.getId()));
        Assert.assertEquals(stored(annas).getAmount(), -90f, "no amount changed");
        Assert.assertEquals(tripsOf(annas), List.of(), "and nothing was bound along the way");

        // The editor's empty selection deletes the whole group, trip or no trip.
        Assert.assertTrue(txs.saveGroupTransaction(gid, List.of(anna.getId(), ben.getId()), Transaction.Type.Shared,
                Transaction.TransactionType.Bill, LocalDateTime.now(), -90f, "Bus", "gone", "none", null,
                List.of()));
        Assert.assertNull(stored(annas));
        Assert.assertNull(stored(bens));

        final String second = UUID.randomUUID().toString();
        final Transaction lone = tripLess(anna.getId(), orgId, second, Transaction.Type.Batch, List.of(anna.getId()));
        Assert.assertTrue(txs.deleteGroupById(second, List.of(anna.getId()), actor));
        Assert.assertNull(stored(lone));
    }

    private boolean save(final String gid, final List<Person.Id> original, final String tripId,
            final List<Person.Id> members) {
        return txs.saveGroupTransaction(gid, original, Transaction.Type.Batch, Transaction.TransactionType.Bill,
                LocalDateTime.now(), -30f, "Bus", "invariant", tripId, null, members);
    }

    /** The trip rule a group save broke, as {@code saveGroup} itself answers it (null: none). */
    private String refusal(final String gid, final List<Person.Id> original, final String tripId,
            final List<Person.Id> members) {
        return txs.saveGroup(gid, original, Transaction.Type.Batch, Transaction.TransactionType.Bill,
                LocalDateTime.now(), -30f, "Bus", "invariant", tripId, null, members).problem();
    }

    @Test
    public void bothEditorsRefuseASaveWithNoTripThroughOneRule() {
        for (final String noTrip : new String[] {null, "", " ", "none"}) {
            Assert.assertTrue(txs.refuseWithoutTrip(noTrip), "'" + noTrip + "' is no trip");
        }
        Assert.assertFalse(txs.refuseWithoutTrip(UUID.randomUUID().toString()), "a chosen trip is the save's to judge");
        Assert.assertEquals(PageFeedback.compose("Not saved", TransactionsCommands.NEEDS_TRIP), "Not saved: Choose "
                + "the trip this transaction is for. Every transaction is recorded on a trip, which is what ties it "
                + "to that trip's organization.", "the page guard says what the save itself says");
    }

    // ------------------------------------------------------------------ a trip that changes organization

    @Test
    public void movingATripWithLedgerRowsReStampsThemAndIsASiteAdminsAlone() throws IOException {
        final Person traveler = TenancyFixtures.savedPerson("Org");
        final Person both = TenancyFixtures.savedPerson("BothAdmin");
        final Organization from = TenancyFixtures.org("From", traveler, both);
        final Organization to = TenancyFixtures.org("To", traveler, both);
        TenancyFixtures.makeOrgAdmin(from, both);
        TenancyFixtures.makeOrgAdmin(to, both);
        final Trip trip = TenancyFixtures.tripOwnedBy(from, traveler);
        final Trip untouched = TenancyFixtures.tripOwnedBy(from, traveler);
        final Transaction bill = payment(traveler.getId(), -400f);
        final Transaction paid = payment(traveler.getId(), 150f);
        final Transaction elsewhere = payment(traveler.getId(), -9f);
        Assert.assertTrue(txs.saveFromPage(siteAdmin, bill, trip.getId()));
        Assert.assertTrue(txs.saveFromPage(siteAdmin, paid, trip.getId()));
        Assert.assertTrue(txs.saveFromPage(siteAdmin, elsewhere, untouched.getId()));
        // A binding whose row is gone must not stop the move.
        Assert.assertTrue(DAO.getInstance().saveBinding(trip.getId(), BindingType.TRIP,
                txs.getBind().key(traveler.getId().getValue(), "no-such-row"), BindingType.TRANSACTION, true));
        Assert.assertTrue(TripCommands.hasLedgerRows(trip.getId()));
        Assert.assertFalse(TripCommands.hasLedgerRows(null));

        final Trip moving = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        moving.setOrgId(to.getId().getValue());
        final TripCommands asOrgAdmin = new TripCommands(() -> TenancyFixtures.orgsFor(both));
        Assert.assertFalse(asOrgAdmin.saveTrip(moving), "an admin of both organizations is still not enough");
        Assert.assertEquals(DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow().getOrgId(),
                from.getId().getValue(), "nothing moved");
        Assert.assertEquals(stored(bill).getOrgId(), from.getId().getValue());

        final TripCommands asSiteAdmin = new TripCommands(TenancyFixtures::asSiteAdmin);
        Assert.assertTrue(asSiteAdmin.saveTrip(moving));
        Assert.assertEquals(DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow().getOrgId(),
                to.getId().getValue());
        Assert.assertEquals(stored(bill).getOrgId(), to.getId().getValue(), "the rows follow their trip");
        Assert.assertEquals(stored(paid).getOrgId(), to.getId().getValue());
        Assert.assertEquals(stored(elsewhere).getOrgId(), from.getId().getValue(), "another trip's rows do not");
        // What the traveler sees: the money is with the new organization now, to the cent.
        final LedgerCommands ledger = new LedgerCommands(() -> TenancyFixtures.callerFor(traveler));
        final List<LedgerCommands.OrgBalance> balances = ledger.orgBalances(List.of(traveler.getId()));
        Assert.assertEquals(balances.stream().filter(entry -> entry.orgId().equals(to.getId().getValue()))
                .findFirst().orElseThrow().balanceCents(), -25000L);
        Assert.assertEquals(balances.stream().filter(entry -> entry.orgId().equals(from.getId().getValue()))
                .findFirst().orElseThrow().balanceCents(), -900L);

        // A trip with no rows is not a ledger move: the two organizations' admins may still move it.
        final Trip empty = TenancyFixtures.tripOwnedBy(from, traveler);
        final Trip emptyCopy = DAO.getInstance().getTrip(empty.getId(), Cached.NO).orElseThrow();
        emptyCopy.setOrgId(to.getId().getValue());
        Assert.assertNull(asOrgAdmin.ledgerMoveOf(emptyCopy, empty, true, null));
        Assert.assertNull(asOrgAdmin.ledgerMoveOf(emptyCopy, null, true, null), "a new trip moves nothing");
        Assert.assertTrue(asOrgAdmin.saveTrip(emptyCopy));
        // Saving a trip in place (the editor's every save) is not a move either.
        Assert.assertNull(asOrgAdmin.ledgerMoveOf(moving, moving, true, null));
        final Trip settled = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        Assert.assertNull(settled.getLedgerMoveFrom(), "a finished move leaves no marker on the trip");
        Assert.assertTrue(asOrgAdmin.saveTrip(settled));
    }

    /** A {@link TripCommands.LedgerRowReader} that counts what a save asks of the ledger. */
    private static TripCommands.LedgerRowReader counted(final AtomicInteger reads) {
        return new TripCommands.LedgerRowReader() {
            @Override
            public List<String> rowKeys(final String tripId) {
                reads.incrementAndGet();
                return TripCommands.DAO_LEDGER_READS.rowKeys(tripId);
            }

            @Override
            public Optional<Transaction> row(final String rowKey) {
                reads.incrementAndGet();
                return TripCommands.DAO_LEDGER_READS.row(rowKey);
            }
        };
    }

    @Test
    public void aSaveThatDoesNotChangeTheOrganizationNeverReadsTheLedgerAndIsNeverRefusedOnItsAccount()
            throws IOException {
        final Person traveler = TenancyFixtures.savedPerson("Roster");
        final Person joining = TenancyFixtures.savedPerson("RosterJoin");
        final Person manager = TenancyFixtures.savedPerson("RosterMgr");
        final Organization old = TenancyFixtures.org("RosterOld", traveler, joining, manager);
        final Organization org = TenancyFixtures.org("RosterNow", traveler, joining, manager);
        final Trip trip = TenancyFixtures.tripOwnedBy(org, traveler);
        final Transaction bill = payment(traveler.getId(), -120f);
        Assert.assertTrue(txs.saveFromPage(siteAdmin, bill, trip.getId()));
        // One row of the trip's books names another organization: what a payment started before a move and
        // captured after it used to leave behind, with no failure anywhere.
        final Transaction foreign = stored(bill);
        foreign.setOrgId(old.getId().getValue());
        Assert.assertTrue(DAO.getInstance().saveTransaction(foreign));

        final AtomicInteger reads = new AtomicInteger();
        final TripCommands asManager = new TripCommands(() -> TenancyFixtures.orgsFor(manager),
                () -> TenancyFixtures.callerFor(manager), row -> DAO.getInstance().saveTransaction(row),
                counted(reads));
        final Trip roster = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        roster.getPeople().add(joining.getId());
        Assert.assertTrue(asManager.saveTrip(roster), "a roster save is not the ledger's to refuse");
        Assert.assertTrue(DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow().getPeople()
                .contains(joining.getId()), "the traveler is on the trip");
        Assert.assertEquals(reads.get(), 0, "no bindings query and no row read for a save that moves nothing");
        Assert.assertEquals(stored(bill).getOrgId(), old.getId().getValue(), "and the row was not touched");

        // The same save by a site administrator reads nothing either: nothing says a move is unfinished.
        final TripCommands asAdmin = new TripCommands(TenancyFixtures::asSiteAdmin,
                TenancyFixtures::siteAdminCaller, row -> DAO.getInstance().saveTransaction(row), counted(reads));
        Assert.assertTrue(asAdmin.saveTrip(DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow()));
        Assert.assertEquals(reads.get(), 0);
    }

    @Test
    public void aPaymentCapturedAfterItsTripMovedIsRecordedWithTheTripsCurrentOrganization() throws IOException {
        final Person payer = TenancyFixtures.savedPerson("Late");
        final Person friend = TenancyFixtures.savedPerson("LateFriend");
        final Organization from = TenancyFixtures.org("LateFrom", payer, friend);
        final Organization to = TenancyFixtures.org("LateTo", payer, friend);
        final String toId = to.getId().getValue();
        final Trip trip = TenancyFixtures.tripOwnedBy(from, payer, friend);
        // Started while the trip was still the first organization's: that is the stamp the payment carries.
        final Payment single = latePayment(trip, from, payer, List.of(new Payment.Allocation(payer.getId(), 5000L)));
        final Payment shared = latePayment(trip, from, payer, List.of(
                new Payment.Allocation(payer.getId(), 2500L), new Payment.Allocation(friend.getId(), 2500L)));

        final Trip moving = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        moving.setOrgId(toId);
        Assert.assertTrue(new TripCommands(TenancyFixtures::asSiteAdmin).saveTrip(moving));

        final PaymentRecorder recorder = new PaymentRecorder(txs, id -> id.getValue());
        final List<String> txIds = new ArrayList<>(recorder.record(single, "LateTo", "Fake").txIds());
        Assert.assertEquals(txIds.size(), 1);
        Assert.assertEquals(DAO.getInstance().getTransaction(payer.getId(), txIds.getFirst(), Cached.NO)
                .orElseThrow().getOrgId(), toId, "a row's organization is its trip's, not the payment's");
        Assert.assertEquals(recorder.record(shared, "LateTo", "Fake").txIds().size(), 2);
        for (final Person member : List.of(payer, friend)) {
            Assert.assertEquals(txs.getGroupTransactionForUser(member.getId(), shared.getPaymentId() + "-pay")
                    .orElseThrow().getOrgId(), toId);
        }
        // So the trip's books hold no row of another organization, and nothing was left for anyone to finish.
        Assert.assertNull(DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow().getLedgerMoveFrom());

        // A payment for a trip that no longer answers keeps the organization it was started with.
        final Payment orphan = Payment.builder().tripId(UUID.randomUUID().toString())
                .orgId(from.getId().getValue()).payerId(payer.getId()).processorType(ProcessorType.FAKE)
                .allocations(List.of(new Payment.Allocation(payer.getId(), 700L))).totalChargedCents(700L)
                .captureId("late-orphan").status(Payment.Status.CAPTURED).build();
        final String orphanTx = recorder.record(orphan, "LateFrom", "Fake").txIds().getFirst();
        Assert.assertEquals(DAO.getInstance().getTransaction(payer.getId(), orphanTx, Cached.NO).orElseThrow()
                .getOrgId(), from.getId().getValue());
    }

    private static Payment latePayment(final Trip trip, final Organization startedWith, final Person payer,
            final List<Payment.Allocation> allocations) {
        final long total = allocations.stream().mapToLong(Payment.Allocation::getAmountCents).sum();
        return Payment.builder().tripId(trip.getId()).orgId(startedWith.getId().getValue())
                .payerId(payer.getId()).processorType(ProcessorType.FAKE).allocations(allocations)
                .feesPaidBy(FeesPaidBy.ORGANIZATION).totalChargedCents(total)
                .captureId("late-" + TenancyFixtures.unique()).status(Payment.Status.CAPTURED).build();
    }

    @Test
    public void aMoveThatFailedOnOneRowIsFinishedByTheNextSave() throws IOException {
        final Person traveler = TenancyFixtures.savedPerson("Retry");
        final Person both = TenancyFixtures.savedPerson("RetryAdmin");
        final Organization from = TenancyFixtures.org("RetryFrom", traveler, both);
        final Organization to = TenancyFixtures.org("RetryTo", traveler, both);
        TenancyFixtures.makeOrgAdmin(from, both);
        TenancyFixtures.makeOrgAdmin(to, both);
        final String fromId = from.getId().getValue();
        final String toId = to.getId().getValue();
        final Trip trip = TenancyFixtures.tripOwnedBy(from, traveler);
        final Transaction bill = payment(traveler.getId(), -300f);
        final Transaction paid = payment(traveler.getId(), 100f);
        final Transaction stuck = payment(traveler.getId(), 50f);
        for (final Transaction row : List.of(bill, paid, stuck)) {
            Assert.assertTrue(txs.saveFromPage(siteAdmin, row, trip.getId()));
        }

        // The first save: the trip row and two ledger rows are written, the third row's write fails.
        final TripCommands failingOnce = new TripCommands(TenancyFixtures::asSiteAdmin,
                TenancyFixtures::siteAdminCaller, row -> !row.getTxId().equals(stuck.getTxId())
                        && DAO.getInstance().saveTransaction(row));
        final Trip moving = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        moving.setOrgId(toId);
        moving.setPageSeededAt(null);
        Assert.assertFalse(failingOnce.saveTrip(moving), "a row that did not move is reported");
        Assert.assertEquals(DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow().getOrgId(), toId,
                "the trip row was already written");
        Assert.assertEquals(stored(bill).getOrgId(), toId);
        Assert.assertEquals(stored(paid).getOrgId(), toId);
        Assert.assertEquals(stored(stuck).getOrgId(), fromId, "one row is still the old organization's");
        Assert.assertNotNull(DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow().getPageSeededAt(),
                "what follows the trip write (its page here) still ran: it used to be skipped for good");

        // The stored trip and the saved one now agree, which used to read as "not a move": the retry did nothing
        // and said it had saved. The trip row itself says the move is unfinished, so no save has to read the
        // rows to find out, and finishing it is still a site admin's alone.
        final Trip again = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        Assert.assertEquals(again.getLedgerMoveFrom(), fromId, "the trip row records where its rows still are");
        final AtomicInteger reads = new AtomicInteger();
        final TripCommands asOrgAdmin = new TripCommands(() -> TenancyFixtures.orgsFor(both),
                () -> TenancyFixtures.callerFor(both), row -> DAO.getInstance().saveTransaction(row),
                counted(reads));
        again.getPeople().add(both.getId());
        Assert.assertTrue(asOrgAdmin.saveTrip(again), "anyone else's save goes through: it is not theirs to finish, "
                + "and not a reason to lose their roster change");
        Assert.assertEquals(reads.get(), 0, "and it reads no ledger row");
        Assert.assertEquals(stored(stuck).getOrgId(), fromId, "the row waits for a site admin");
        final Trip waiting = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        Assert.assertTrue(waiting.getPeople().contains(both.getId()));
        Assert.assertEquals(waiting.getLedgerMoveFrom(), fromId, "and so does the marker, whatever the saver sent");
        // A copy that lost the marker (a client that never had the field) cannot hide the move.
        waiting.setLedgerMoveFrom(null);
        Assert.assertTrue(asOrgAdmin.saveTrip(waiting));
        Assert.assertEquals(DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow().getLedgerMoveFrom(),
                fromId);
        // When the stored row cannot be read, the saved copy's own marker is all there is to go by.
        final TripCommands.LedgerMove blind = new TripCommands(TenancyFixtures::asSiteAdmin)
                .ledgerMoveOf(again, null, false, null);
        Assert.assertNotNull(blind, "a site admin's save still finishes it");
        Assert.assertEquals(blind.fromOrgId(), fromId);
        Assert.assertFalse(blind.refused());

        final Trip retry = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        Assert.assertTrue(new TripCommands(TenancyFixtures::asSiteAdmin).saveTrip(retry),
                "a site admin's save finishes the move");
        Assert.assertEquals(stored(stuck).getOrgId(), toId, "and the row that failed has moved");
        final Trip done = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        Assert.assertNull(done.getLedgerMoveFrom(), "nothing is left to move, and the trip row says so");
        final LedgerCommands ledger = new LedgerCommands(() -> TenancyFixtures.callerFor(traveler));
        Assert.assertEquals(ledger.orgBalances(List.of(traveler.getId())).stream()
                .filter(entry -> entry.orgId().equals(toId)).findFirst().orElseThrow().balanceCents(), -15000L,
                "the whole balance is with the new organization");
        reads.set(0);
        Assert.assertTrue(asOrgAdmin.saveTrip(done));
        Assert.assertEquals(reads.get(), 0);
    }

    /**
     * A TripCommands whose uncached pre-read of the stored trip fails, as DynamoDB throwing once would make it;
     * {@code cacheToo}: the cached copy cannot be read either.
     */
    private static TripCommands blind(final Supplier<OrgCommands> orgs, final boolean cacheToo) {
        return new TripCommands(orgs) {
            @Override
            PreRead preRead(final String tripId) {
                return new PreRead(null, false);
            }

            @Override
            Trip cachedForMove(final String tripId) {
                return cacheToo ? null : super.cachedForMove(tripId);
            }
        };
    }

    @Test
    public void aMoveIsStillAMoveWhenThePreReadFails() throws IOException {
        final Person traveler = TenancyFixtures.savedPerson("Blind");
        final Person both = TenancyFixtures.savedPerson("BlindAdmin");
        final Organization from = TenancyFixtures.org("BlindFrom", traveler, both);
        final Organization to = TenancyFixtures.org("BlindTo", traveler, both);
        TenancyFixtures.makeOrgAdmin(from, both);
        TenancyFixtures.makeOrgAdmin(to, both);
        final Trip trip = TenancyFixtures.tripOwnedBy(from, traveler);
        final Transaction bill = payment(traveler.getId(), -200f);
        Assert.assertTrue(txs.saveFromPage(siteAdmin, bill, trip.getId()));
        final Trip moving = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        moving.setOrgId(to.getId().getValue());

        // The cached copy stands in for the stored one: still a site admin's alone.
        Assert.assertFalse(blind(() -> TenancyFixtures.orgsFor(both), false).saveTrip(moving),
                "a failed read no longer lets an org admin move a trip with rows on its books");
        Assert.assertEquals(DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow().getOrgId(),
                from.getId().getValue(), "nothing moved");
        Assert.assertTrue(blind(TenancyFixtures::asSiteAdmin, false).saveTrip(moving));
        Assert.assertEquals(stored(bill).getOrgId(), to.getId().getValue(), "and a site admin's move takes the rows");
        Assert.assertNull(DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow().getLedgerMoveFrom());

        // Neither copy readable, and the trip has rows: the save cannot tell a move from a roster change.
        final Trip back = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        back.setOrgId(from.getId().getValue());
        Assert.assertFalse(blind(TenancyFixtures::asSiteAdmin, true).saveTrip(back));
        Assert.assertEquals(DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow().getOrgId(),
                to.getId().getValue(), "refused, not written as a silent move");
        Assert.assertEquals(stored(bill).getOrgId(), to.getId().getValue());

        // A trip with no rows has nothing to move: a blind save of it goes through as before.
        final Trip empty = DAO.getInstance().getTrip(TenancyFixtures.tripOwnedBy(from, traveler).getId(),
                Cached.NO).orElseThrow();
        empty.getPeople().add(both.getId());
        Assert.assertTrue(blind(() -> TenancyFixtures.orgsFor(both), true).saveTrip(empty));
        Assert.assertNull(new TripCommands(TenancyFixtures::asSiteAdmin).cachedForMove(null));
    }

    @Test
    public void aGroupRowWithABlankOrganizationTakesItsTrips() throws IOException {
        final Person anna = TenancyFixtures.savedPerson("BlankAnna");
        final Organization org = TenancyFixtures.org("Blank", anna);
        final Trip trip = TenancyFixtures.tripOwnedBy(org, anna);
        final String gid = UUID.randomUUID().toString();
        Assert.assertTrue(save(gid, List.of(), trip.getId(), List.of(anna.getId())));
        final Transaction blank = txs.getGroupTransactionForUser(anna.getId(), gid).orElseThrow();
        blank.setOrgId(" ");
        Assert.assertTrue(DAO.getInstance().saveTransaction(blank));

        Assert.assertTrue(save(gid, List.of(anna.getId()), trip.getId(), List.of(anna.getId())));
        Assert.assertEquals(stored(blank).getOrgId(), org.getId().getValue(),
                "a blank organization is none, as on the transaction page");
    }

    private static OrgCommands nobodyToAsk() {
        throw new IllegalStateException("no request, no caller");
    }

    @Test
    public void aMarkerWithNoRowsLeftIsClearedByASiteAdminsSave() throws IOException {
        final Person traveler = TenancyFixtures.savedPerson("Marker");
        final Organization org = TenancyFixtures.org("Marker", traveler);
        final Trip trip = TenancyFixtures.tripOwnedBy(org, traveler);
        final Trip marked = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        marked.setLedgerMoveFrom(TripCommands.MOVE_FROM_NO_ORG);
        Assert.assertTrue(DAO.getInstance().saveTrip(marked));

        final Trip copy = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        Assert.assertTrue(new TripCommands(() -> TenancyFixtures.orgsFor(traveler)).saveTrip(copy));
        Assert.assertEquals(DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow().getLedgerMoveFrom(),
                TripCommands.MOVE_FROM_NO_ORG);
        Assert.assertTrue(new TripCommands(TenancyFixtures::asSiteAdmin).saveTrip(copy));
        Assert.assertNull(DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow().getLedgerMoveFrom());
        // A save with nobody to ask (the payment webhook's thread) is not a site administrator's: it goes
        // through and leaves the marker, it does not fail on the question.
        marked.setLedgerMoveFrom(TripCommands.MOVE_FROM_NO_ORG);
        Assert.assertTrue(DAO.getInstance().saveTrip(marked));
        Assert.assertTrue(new TripCommands(LedgerWriteInvariantsTest::nobodyToAsk).saveTrip(
                DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow()));
        Assert.assertEquals(DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow().getLedgerMoveFrom(),
                TripCommands.MOVE_FROM_NO_ORG);
        Assert.assertNull(new TripCommands.LedgerMove(null, List.of(), false).fromOrgId());
        Assert.assertEquals(new TripCommands.LedgerMove(" ", List.of(), false).marker(),
                TripCommands.MOVE_FROM_NO_ORG, "a legacy trip with no organization still leaves a marker");
    }

    @Test
    public void aGroupSaveReadsItsTripOnceHoweverManyMembersItHas() throws IOException {
        final List<Person> people = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            people.add(TenancyFixtures.savedPerson("Once" + i));
        }
        final Organization org = TenancyFixtures.org("Once", people.toArray(new Person[0]));
        final Trip trip = TenancyFixtures.tripOwnedBy(org, people.toArray(new Person[0]));
        final List<Person.Id> ids = people.stream().map(Person::getId).toList();
        final String gid = UUID.randomUUID().toString();
        Assert.assertTrue(save(gid, List.of(), trip.getId(), ids.subList(0, 5)));
        final List<Optional<Transaction>> rows = ids.stream()
                .map(id -> txs.getGroupTransactionForUser(id, gid)).toList();
        Assert.assertEquals(rows.stream().filter(Optional::isPresent).count(), 5L);

        final AtomicInteger reads = new AtomicInteger();
        final Function<String, Trip> counting = id -> countedLedgerTrip(reads, id);
        final TransactionsCommands.GroupTrip chosen = TransactionsCommands.groupTrip(trip.getId(), rows, counting);
        Assert.assertNull(chosen.problem());
        Assert.assertEquals(chosen.trip().getId(), trip.getId(), "the trip the rows are then written from");
        Assert.assertEquals(reads.get(), 1, "one read for five stored rows and one new member, not one each");

        reads.set(0);
        final TransactionsCommands.GroupTrip joined = TransactionsCommands.groupTrip("none", rows, counting);
        Assert.assertNull(joined.problem());
        Assert.assertEquals(joined.trip().getId(), trip.getId(), "a member being added joins the group's trip");
        Assert.assertEquals(reads.get(), 1);

        reads.set(0);
        Assert.assertNull(TransactionsCommands.groupTrip("none", rows.subList(0, 5), counting).trip());
        Assert.assertEquals(reads.get(), 0, "nobody added and no trip chosen: the rows keep theirs, nothing is read");

        // The save itself answers why it refused, so the REST resource no longer judges the group before it.
        final TransactionsCommands.GroupSave refused = txs.saveGroup(gid, ids.subList(0, 5), Transaction.Type.Batch,
                Transaction.TransactionType.Bill, LocalDateTime.now(), -30f, "Bus", "invariant", "no-such-trip",
                null, ids);
        Assert.assertFalse(refused.saved());
        Assert.assertEquals(refused.problem(), TransactionsCommands.NOT_A_LEDGER_TRIP);
        Assert.assertTrue(txs.getGroupTransactionForUser(ids.get(5), gid).isEmpty(), "and nothing was written");
        Assert.assertTrue(save(gid, ids.subList(0, 5), "none", ids));
        final Transaction sixth = txs.getGroupTransactionForUser(ids.get(5), gid).orElseThrow();
        Assert.assertEquals(sixth.getOrgId(), org.getId().getValue());
        Assert.assertEquals(tripsOf(sixth), List.of(trip.getId()));
    }

    private static Trip countedLedgerTrip(final AtomicInteger reads, final String tripId) {
        reads.incrementAndGet();
        return TransactionsCommands.ledgerTrip(tripId);
    }

    @Test
    public void theMoveIsRecordedInBothOrganizationsTrails() {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Audited").build();
        trip.setOrgId(FakeData.ACME_ORG_ID);
        final AuditCommands audit = new AuditCommands();
        final String moved = audit.tripLedgerMoved(trip, FakeData.CFPW_ORG_ID, 3, 0,
                TenancyFixtures.siteAdminCaller().auditActor());
        Assert.assertTrue(moved.contains("moved trip 'Audited' from organization " + FakeData.CFPW_ORG_ID + " to "
                + FakeData.ACME_ORG_ID + "; 3 of its transactions moved with it"), moved);
        final String partial = audit.tripLedgerMoved(trip, null, 1, 2, null);
        Assert.assertTrue(partial.contains("from organization (none)") && partial.contains("2 FAILED to move"),
                partial);
    }
}
