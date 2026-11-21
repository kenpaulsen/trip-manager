package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.BindingCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.action.TransactionsCommands;
import org.paulsens.trip.api.dto.OrgBalanceDto;
import org.paulsens.trip.api.dto.TransactionDto;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link TransactionsResource}: money owed and money paid.
 *
 * <p>Three behaviours carry the risk here. A traveller reads their OWN ledger and nobody else's without
 * privilege; a shared amount is presented as this person's SHARE, not the whole bill; and the group membership
 * list is staff-only on the wire.
 *
 * <p>The second half of the file is the tenancy rule (audit 2026-11-11, F3): a finance role is a role on ONE
 * trip and reaches only the rows on that trip's books. Those tests run the real {@code TripFinanceGate} over
 * real trips, privileges and bindings in the fake store; only the ledger bean stays a mock.
 */
public class TransactionsResourceTest extends ResourceTestSupport {

    private static final Person.Id ME = Person.Id.from("tx-me");
    private static final Person.Id OTHER = Person.Id.from("tx-other");
    private static final String TRIP_ID = "trip-tx";

    private TransactionsCommands transactions;
    private AuditCommands audit;
    private TransactionsResource resource;

    private BindingCommands bindings;

    /**
     * {@link #TRIP_ID} names a real trip that has an organization: a new row (or a new group) is refused for any
     * other, before the mocked ledger bean is asked anything.
     */
    @BeforeClass
    public void storeTheTrip() throws IOException {
        final Trip trip = Trip.builder().id(TRIP_ID).title("Resource trip").build();
        trip.setOrgId(FakeData.CFPW_ORG_ID);
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
    }

    @BeforeMethod
    public void bindBeans() {
        transactions = bindMock(TransactionsCommands.class);
        audit = bindMock(AuditCommands.class);
        bindMock(PersonCommands.class);
        bindings = bindMock(BindingCommands.class);
        resource = resource(new TransactionsResource());
    }

    /** The trip a save is handed, matched by id (the resource reads its own copy). */
    private static Trip tripWithId(final String tripId) {
        return ArgumentMatchers.argThat(trip -> trip != null && tripId.equals(trip.getId()));
    }

    private static Transaction tx(final Person.Id who) {
        final Transaction tx = new Transaction(who, "group-1", Transaction.Type.Shared);
        tx.setAmount(1000f);
        return tx;
    }

    @Test
    public void aTravellerReadsTheirOwnLedgerWithoutAnyPrivilege() {
        signedInAs(ME);
        Mockito.when(transactions.getTransactions(ME)).thenReturn(List.of(tx(ME)));
        Mockito.when(transactions.getUserAmount(ArgumentMatchers.any())).thenReturn(250f);

        final Response response = resource.forPerson(ME.getValue(), null, null);

        assertOk(response);
        Assert.assertEquals(((List<?>) response.getEntity()).size(), 1);
    }

    @Test
    public void aTravellerCannotReadAnybodyElsesLedger() {
        signedInAs(ME);

        assertError(resource.forPerson(OTHER.getValue(), TRIP_ID, null), 403, ApiErrors.FORBIDDEN);
        assertError(resource.get(OTHER.getValue(), "tx-1", TRIP_ID), 403, ApiErrors.FORBIDDEN);
        Mockito.verifyNoInteractions(transactions);
    }

    /** The share rule: a traveller on a shared bill is told their share, not the whole amount. */
    @Test
    public void aSharedAmountIsPresentedAsThisPersonsShare() {
        signedInAs(ME);
        Mockito.when(transactions.getTransactions(ME)).thenReturn(List.of(tx(ME)));
        Mockito.when(transactions.getUserAmount(ArgumentMatchers.any())).thenReturn(250f);

        final TransactionDto dto = (TransactionDto)
                ((List<?>) resource.forPerson(ME.getValue(), null, null).getEntity()).get(0);

        Assert.assertEquals(dto.userAmount(), 250f);
        Assert.assertEquals(dto.amount(), 1000f, "The full amount stays present for context");
    }

    /** The trip a row belongs to is its binding, the same edge the website's ledger filters on. */
    @Test
    public void aTransactionCarriesItsBoundTrip() {
        signedInAs(ME);
        final Transaction mine = tx(ME);
        Mockito.when(transactions.getTransactions(ME)).thenReturn(List.of(mine));
        Mockito.when(transactions.getUserAmount(ArgumentMatchers.any())).thenReturn(250f);
        Mockito.when(bindings.key(ME.getValue(), mine.getTxId())).thenReturn("composite");
        Mockito.when(bindings.getBindings("composite", BindingType.TRANSACTION, BindingType.TRIP))
                .thenReturn(List.of(TRIP_ID));

        final TransactionDto dto = (TransactionDto)
                ((List<?>) resource.forPerson(ME.getValue(), null, null).getEntity()).get(0);

        Assert.assertEquals(dto.tripIds(), List.of(TRIP_ID));
        Assert.assertEquals(dto.withoutGroupPeople().tripIds(), List.of(TRIP_ID), "the trip survives redaction");
    }

    /**
     * A row names its trip by title and dates, as the website's statement does, even a trip the person is not on
     * the roster of (no trip list of theirs would name it); never a trip of another organization than the row's.
     */
    @Test
    public void aTransactionNamesItsTripByTheStatementsRule() throws IOException {
        final LocalDateTime start = LocalDateTime.of(2025, 5, 10, 0, 0);
        final LocalDateTime end = LocalDateTime.of(2025, 5, 20, 0, 0);
        final Trip past = Trip.builder().id(UUID.randomUUID().toString()).startDate(start).endDate(end).build();
        past.setTitle("Spring 2025");
        past.setOrgId(FakeData.CFPW_ORG_ID);
        Assert.assertTrue(DAO.getInstance().saveTrip(past));
        signedInAs(ME);
        final Transaction cfpw = boundRow(FakeData.CFPW_ORG_ID, past.getId());
        final Transaction acme = boundRow(FakeData.ACME_ORG_ID, past.getId());
        final Transaction orgless = boundRow(null, past.getId());
        final Transaction unbound = boundRow(FakeData.CFPW_ORG_ID, null);
        Mockito.when(transactions.getTransactions(ME)).thenReturn(List.of(cfpw, acme, orgless, unbound));

        final List<TransactionDto> read = rows(resource.forPerson(ME.getValue(), null, null));

        Assert.assertFalse(past.getPeople().contains(ME), "the reader is not on the trip");
        Assert.assertEquals(read.get(0).tripTitle(), "Spring 2025");
        Assert.assertEquals(read.get(0).tripStartDate(), start);
        Assert.assertEquals(read.get(0).tripEndDate(), end);
        Assert.assertNull(read.get(1).tripTitle(), "another organization's trip is never named");
        Assert.assertNull(read.get(1).tripStartDate());
        Assert.assertEquals(read.get(2).tripTitle(), "Spring 2025", "a row with no organization takes any trip");
        Assert.assertNull(read.get(3).tripTitle(), "a row on no trip names none");
        Assert.assertEquals(read.get(0).withoutGroupPeople().tripTitle(), "Spring 2025", "it survives redaction");
    }

    /** One of ME's rows with an organization, bound (by the mocked bindings) to {@code tripId} or to none. */
    private Transaction boundRow(final String orgId, final String tripId) {
        final Transaction row = tx(ME);
        row.setOrgId(orgId);
        final String key = "key-" + row.getTxId();
        Mockito.when(bindings.key(ME.getValue(), row.getTxId())).thenReturn(key);
        Mockito.when(bindings.getBindings(key, BindingType.TRANSACTION, BindingType.TRIP))
                .thenReturn(tripId == null ? List.of() : List.of(tripId));
        return row;
    }

    /** Group membership is who ELSE is on the bill: staff-only on the wire. */
    @Test
    public void aTravellersOwnViewOmitsTheGroupMembership() {
        signedInAs(ME);
        Mockito.when(transactions.getTransactions(ME)).thenReturn(List.of(tx(ME)));
        Mockito.when(transactions.getUserAmount(ArgumentMatchers.any())).thenReturn(250f);

        final TransactionDto dto = (TransactionDto)
                ((List<?>) resource.forPerson(ME.getValue(), null, null).getEntity()).get(0);

        Assert.assertNull(dto.groupPeople(), "Fellow group members are not a traveller's to see");
    }

    @Test
    public void anUnknownTransactionIs404() {
        signedInAs(ME);
        Mockito.when(transactions.getTransaction(ME, "gone")).thenReturn(null);

        assertError(resource.get(ME.getValue(), "gone", null), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void theTripsBooksNeedFinanceStaff() {
        signedInAs(ME);

        assertError(resource.forTrip(TRIP_ID), 403, ApiErrors.FORBIDDEN);
        Mockito.verifyNoInteractions(transactions);
    }

    @Test
    public void financeStaffReadTheTripsBooksWithMembershipIncluded() {
        signedInAsSiteAdmin(ME);
        Mockito.when(transactions.getTripTransactions(TRIP_ID)).thenReturn(List.of(tx(OTHER)));
        Mockito.when(transactions.getUserAmount(ArgumentMatchers.any())).thenReturn(500f);

        final Response response = resource.forTrip(TRIP_ID);

        assertOk(response);
        Assert.assertEquals(((List<?>) response.getEntity()).size(), 1);
    }

    @Test
    public void groupMembersNeedsFinanceStaffAndAnswers404OnAMiss() {
        signedInAsSiteAdmin(ME);
        Mockito.when(transactions.getGroupTransactionForUser(ArgumentMatchers.any(), ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());

        assertError(resource.groupMembers("group-1", ME.getValue(), TRIP_ID), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void groupMembersListsTheGroup() {
        signedInAsSiteAdmin(ME);
        final Transaction shared = tx(ME);
        Mockito.when(transactions.getGroupTransactionForUser(ME, "group-1")).thenReturn(Optional.of(shared));
        Mockito.when(transactions.getUserIdsForGroup(shared)).thenReturn(List.of(ME, OTHER));

        final Response response = resource.groupMembers("group-1", ME.getValue(), TRIP_ID);

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("people"), List.of(ME.getValue(), OTHER.getValue()));
    }

    @Test
    public void writesNeedCsrfAndFinanceAdmin() {
        signedInAsSiteAdmin(ME);
        assertError(resource.create(ME.getValue(), null, TRIP_ID, null), 403, ApiErrors.CSRF);
        assertError(resource.update(ME.getValue(), "tx-1", null, TRIP_ID, null), 403, ApiErrors.CSRF);
        assertError(resource.saveGroup(null, TRIP_ID, null), 403, ApiErrors.CSRF);

        signedInAs(ME);
        final TransactionsResource ordinary = resource(new TransactionsResource());
        assertError(ordinary.create(ME.getValue(), CSRF_OK, TRIP_ID, null), 403, ApiErrors.FORBIDDEN);
        assertError(ordinary.update(ME.getValue(), "tx-1", CSRF_OK, TRIP_ID, null), 403, ApiErrors.FORBIDDEN);
        assertError(ordinary.saveGroup(CSRF_OK, TRIP_ID, null), 403, ApiErrors.FORBIDDEN);
        Mockito.verifyNoInteractions(transactions);
    }

    @Test
    public void createAppliesTheBodySavesAndAudits() {
        signedInAsSiteAdmin(ME);
        final Transaction created = tx(OTHER);
        Mockito.when(transactions.createTransaction(OTHER)).thenReturn(created);
        // The trip-carrying save: ?trip= stamps the row's org (tenancy), as the page's save does.
        Mockito.when(transactions.saveOnTrip(ArgumentMatchers.same(created), tripWithId(TRIP_ID),
                ArgumentMatchers.eq(true))).thenReturn(true);
        Mockito.when(transactions.getUserAmount(created)).thenReturn(75f);

        final TransactionDto body = new TransactionDto(null, null, null, null, "Bill", null, 75f, null,
                "lodging", "deposit", false, null, null, null, null, null, null, null);
        assertOk(resource.create(OTHER.getValue(), CSRF_OK, TRIP_ID, body));
        // Bound to the trip the way the page's Save binds it: the ledger's trip filter sees the new row.
        Mockito.verify(transactions).saveOnTrip(ArgumentMatchers.same(created), tripWithId(TRIP_ID),
                ArgumentMatchers.eq(true));

        Assert.assertEquals(created.getAmount(), 75f);
        Assert.assertEquals(created.getTxType(), Transaction.TransactionType.Bill);
        Assert.assertEquals(created.getCategory(), "lodging");
        Mockito.verify(audit).transaction(ArgumentMatchers.any(), ArgumentMatchers.eq(created),
                ArgumentMatchers.any());
    }

    @Test
    public void aFailedCreateIsReportedAndNotAudited() {
        signedInAsSiteAdmin(ME);
        Mockito.when(transactions.createTransaction(OTHER)).thenReturn(tx(OTHER));
        Mockito.when(transactions.saveOnTrip(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyBoolean())).thenReturn(false);

        assertError(resource.create(OTHER.getValue(), CSRF_OK, TRIP_ID, null), 500, ApiErrors.STORE_FAILED);
        Mockito.verifyNoInteractions(audit);
    }

    /** A blank txId makes getTransaction MINT a transaction, so the guard has to fire first. */
    @Test
    public void updateRefusesABlankTransactionId() {
        signedInAsSiteAdmin(ME);

        assertError(resource.update(ME.getValue(), "  ", CSRF_OK, TRIP_ID, null), 400, ApiErrors.BAD_REQUEST);
        Mockito.verify(transactions, Mockito.never())
                .getTransaction(ArgumentMatchers.any(), ArgumentMatchers.anyString());
    }

    @Test
    public void updateAnswers404BeforeSavingAnything() {
        signedInAsSiteAdmin(ME);
        Mockito.when(transactions.getTransaction(ME, "gone")).thenReturn(null);

        assertError(resource.update(ME.getValue(), "gone", CSRF_OK, TRIP_ID, null), 404, ApiErrors.NOT_FOUND);
        Mockito.verify(transactions, Mockito.never()).saveOnTrip(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyBoolean());
    }

    @Test
    public void updateMutatesTheObjectItReadAndAudits() {
        signedInAsSiteAdmin(ME);
        final Transaction existing = tx(ME);
        Mockito.when(transactions.getTransaction(ME, existing.getTxId())).thenReturn(existing);
        Mockito.when(transactions.saveOnTrip(ArgumentMatchers.same(existing), tripWithId(TRIP_ID),
                ArgumentMatchers.eq(true))).thenReturn(true);
        Mockito.when(transactions.getUserAmount(existing)).thenReturn(80f);

        final TransactionDto body = new TransactionDto(null, null, null, null, null, null, 80f, null,
                null, "amended", false, null, null, null, null, null, null, null);
        assertOk(resource.update(ME.getValue(), existing.getTxId(), CSRF_OK, TRIP_ID, body));

        Assert.assertEquals(existing.getAmount(), 80f);
        Assert.assertEquals(existing.getNote(), "amended");
        // A row on no trip is given ?trip= by the save itself.
        Mockito.verify(transactions).saveOnTrip(ArgumentMatchers.same(existing), tripWithId(TRIP_ID),
                ArgumentMatchers.eq(true));
        Mockito.verify(audit).transaction(ArgumentMatchers.any(), ArgumentMatchers.eq(existing),
                ArgumentMatchers.any());
    }

    @Test
    public void aGroupSaveNeedsAtLeastOnePerson() {
        signedInAsSiteAdmin(ME);

        assertError(resource.saveGroup(CSRF_OK, TRIP_ID, null), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.saveGroup(CSRF_OK, TRIP_ID, group(null, List.of())), 400, ApiErrors.BAD_REQUEST);
    }

    /** The client must end up knowing the group id, or it has created something it can never fetch again. */
    @Test
    public void aGroupSaveMintsAndReturnsTheGroupIdWhenTheClientSentNone() {
        signedInAsSiteAdmin(ME);
        Mockito.when(transactions.saveGroup(ArgumentMatchers.anyString(), ArgumentMatchers.anyList(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyList())).thenReturn(new TransactionsCommands.GroupSave(true, null));

        final Response response = resource.saveGroup(CSRF_OK, TRIP_ID, group(null, List.of(ME.getValue())));

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertNotNull(body.get("groupId"), "A minted group id must be returned to the client");

        final Response reuse = resource.saveGroup(CSRF_OK, TRIP_ID, group("group-9", List.of(ME.getValue())));
        @SuppressWarnings("unchecked")
        final Map<String, Object> reuseBody = (Map<String, Object>) reuse.getEntity();
        Assert.assertEquals(reuseBody.get("groupId"), "group-9", "A supplied id is kept, not replaced");
    }

    @Test
    public void aFailedGroupSaveIsReported() {
        signedInAsSiteAdmin(ME);
        Mockito.when(transactions.saveGroup(ArgumentMatchers.anyString(), ArgumentMatchers.anyList(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyList())).thenReturn(new TransactionsCommands.GroupSave(false, null));

        assertError(resource.saveGroup(CSRF_OK, TRIP_ID, group(null, List.of(ME.getValue()))),
                500, ApiErrors.STORE_FAILED);
    }

    /**
     * Emptying an EXISTING group is how a client deletes the transaction (0 members is no transaction), so the
     * blanket refusal only applies to creating one that would be empty from birth.
     */
    @Test
    public void emptyingAnExistingGroupIsAllowedAndDeletesIt() {
        signedInAsSiteAdmin(ME);
        Mockito.when(transactions.saveGroup(ArgumentMatchers.anyString(), ArgumentMatchers.anyList(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyList())).thenReturn(new TransactionsCommands.GroupSave(true, null));

        assertOk(resource.saveGroup(CSRF_OK, TRIP_ID, group("group-9", List.of())));
        Mockito.verify(transactions).saveGroup(ArgumentMatchers.eq("group-9"),
                ArgumentMatchers.anyList(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq(List.of()));
    }

    @Test
    public void deleteIsGuardedLikeEveryOtherWrite() {
        signedInAsSiteAdmin(ME);
        assertError(resource.delete(ME.getValue(), "tx-1", null, TRIP_ID, false), 403, ApiErrors.CSRF);

        final TransactionsResource ordinary = resource(new TransactionsResource());
        signedInAs(ME);
        assertError(ordinary.delete(ME.getValue(), "tx-1", CSRF_OK, TRIP_ID, false), 403, ApiErrors.FORBIDDEN);
    }

    /** Blank makes getTransaction MINT a row rather than miss, so "delete" would act on a brand new object. */
    @Test
    public void deleteRefusesABlankTransactionIdAnd404sAnUnknownOne() {
        signedInAsSiteAdmin(ME);

        assertError(resource.delete(ME.getValue(), " ", CSRF_OK, TRIP_ID, false), 400, ApiErrors.BAD_REQUEST);
        Mockito.verify(transactions, Mockito.never())
                .getTransaction(ArgumentMatchers.any(), ArgumentMatchers.anyString());

        Mockito.when(transactions.getTransaction(ME, "gone")).thenReturn(null);
        assertError(resource.delete(ME.getValue(), "gone", CSRF_OK, TRIP_ID, false), 404, ApiErrors.NOT_FOUND);
    }

    /**
     * Removing the LAST member of a Shared group undoes a payment that may really have happened, so a client
     * has to say it means it. Every other delete goes straight through.
     */
    @Test
    public void deletingTheLastMemberOfASharedGroupNeedsConfirmUndo() {
        signedInAsSiteAdmin(ME);
        final Transaction shared = tx(ME);
        Mockito.when(transactions.getTransaction(ME, shared.getTxId())).thenReturn(shared);
        Mockito.when(transactions.getUserIdsForGroup(shared)).thenReturn(List.of(ME));

        assertError(resource.delete(ME.getValue(), shared.getTxId(), CSRF_OK, TRIP_ID, false), 409,
                ApiErrors.CONFLICT);
        Mockito.verify(transactions, Mockito.never())
                .deleteForPerson(ArgumentMatchers.any(), ArgumentMatchers.any());

        Mockito.when(transactions.deleteForPerson(ArgumentMatchers.eq(shared), ArgumentMatchers.any()))
                .thenReturn(true);
        assertOk(resource.delete(ME.getValue(), shared.getTxId(), CSRF_OK, TRIP_ID, true));
    }

    @Test
    public void deletingAnyOtherRowGoesStraightThroughAndReportsAFailure() {
        signedInAsSiteAdmin(ME);
        final Transaction shared = tx(ME);
        Mockito.when(transactions.getTransaction(ME, shared.getTxId())).thenReturn(shared);
        // Two members left, so this is an ordinary removal from the group.
        Mockito.when(transactions.getUserIdsForGroup(shared)).thenReturn(List.of(ME, OTHER));
        Mockito.when(transactions.deleteForPerson(ArgumentMatchers.eq(shared), ArgumentMatchers.any()))
                .thenReturn(true);

        final Response response = resource.delete(ME.getValue(), shared.getTxId(), CSRF_OK, TRIP_ID, false);
        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("txId"), shared.getTxId());

        Mockito.when(transactions.deleteForPerson(ArgumentMatchers.eq(shared), ArgumentMatchers.any()))
                .thenReturn(false);
        assertError(resource.delete(ME.getValue(), shared.getTxId(), CSRF_OK, TRIP_ID, false),
                500, ApiErrors.STORE_FAILED);
    }

    // ------------------------------------------------------------------ the books rule (F3)

    private static Trip storedTrip(final Person.Id... roster) throws IOException {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Books " + System.nanoTime())
                .startDate(LocalDateTime.now().plusDays(30)).people(new ArrayList<>(List.of(roster))).build();
        trip.setOrgId(FakeData.CFPW_ORG_ID);     // a ledger row needs a trip that has an organization
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private static Person.Id holderOf(final String privilege, final Trip trip) {
        final Person.Id who = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.getOrCreate(privilege, trip.getId(), privilege)
                .withNewPerson(who), null));
        return who;
    }

    /** A plain row for {@code who}, on the books of {@code trip} the way the page's Save puts it there. */
    private Transaction rowOnBooks(final Person.Id who, final Trip trip) {
        final Transaction row = new Transaction(who, null, null);
        row.setAmount(-120f);
        final BindingCommands real = new BindingCommands();
        real.setBindings(real.key(who.getValue(), row.getTxId()), BindingType.TRANSACTION, BindingType.TRIP,
                List.of(trip.getId()), true);
        Mockito.when(transactions.getTransaction(who, row.getTxId())).thenReturn(row);
        return row;
    }

    private TransactionsResource as(final Person.Id who) {
        signedInAs(who);
        return resource(new TransactionsResource());
    }

    private static List<String> tripsOf(final Transaction row) {
        final BindingCommands real = new BindingCommands();
        return real.getBindings(real.key(row.getUserId().getValue(), row.getTxId()), BindingType.TRANSACTION,
                BindingType.TRIP);
    }

    /**
     * The finding itself: finance admin of one small trip, a row on ANOTHER trip's books. It used to be
     * rewritten, deleted and read; now it does not exist as far as that caller can tell.
     */
    @Test
    public void aFinanceAdminOfOneTripCannotReachARowOnAnotherTripsBooks() throws IOException {
        final Person.Id traveler = Person.Id.newInstance();
        final Trip mine = storedTrip();
        final Trip theirs = storedTrip(traveler);
        final Transaction row = rowOnBooks(traveler, theirs);
        Mockito.when(transactions.getTransactions(traveler)).thenReturn(List.of(row));
        final TransactionsResource admin = as(holderOf(PrivilegeCommands.TRIP_FIN_ADMIN, mine));
        final TransactionDto zero = new TransactionDto(null, null, null, null, null, null, 0f, null,
                null, "zeroed", false, null, null, null, null, null, null, null);

        assertError(admin.update(traveler.getValue(), row.getTxId(), CSRF_OK, mine.getId(), zero),
                404, ApiErrors.NOT_FOUND);
        assertError(admin.delete(traveler.getValue(), row.getTxId(), CSRF_OK, mine.getId(), true),
                404, ApiErrors.NOT_FOUND);
        assertError(admin.get(traveler.getValue(), row.getTxId(), mine.getId()), 404, ApiErrors.NOT_FOUND);
        final Response ledger = admin.forPerson(traveler.getValue(), mine.getId(), null);
        assertOk(ledger);
        Assert.assertEquals(((List<?>) ledger.getEntity()).size(), 0, "nothing of the person's other trips");

        Assert.assertEquals(row.getAmount(), -120f, "untouched");
        Mockito.verify(transactions, Mockito.never()).saveOnTrip(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyBoolean());
        Mockito.verify(transactions, Mockito.never()).deleteForPerson(ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.verifyNoInteractions(audit);
        // Naming the OTHER trip does not help either: the privilege is not on it.
        assertError(admin.update(traveler.getValue(), row.getTxId(), CSRF_OK, theirs.getId(), zero),
                403, ApiErrors.FORBIDDEN);
        assertError(admin.forPerson(traveler.getValue(), theirs.getId(), null), 403, ApiErrors.FORBIDDEN);
        // Nor does a trip id nobody created, or none at all.
        assertError(admin.forPerson(traveler.getValue(), UUID.randomUUID().toString(), null), 403, ApiErrors.FORBIDDEN);
        assertError(admin.forPerson(traveler.getValue(), null, null), 403, ApiErrors.FORBIDDEN);
        assertError(admin.delete(traveler.getValue(), row.getTxId(), CSRF_OK, null, true), 403, ApiErrors.FORBIDDEN);
    }

    @Test
    public void aFinanceAdminWorksTheRowsOnTheirOwnTripsBooks() throws IOException {
        final Person.Id traveler = Person.Id.newInstance();
        final Trip mine = storedTrip(traveler);
        final Transaction row = rowOnBooks(traveler, mine);
        bind(BindingCommands.class, new BindingCommands());     // the real binding, so the DTO names the trip
        Mockito.when(transactions.saveOnTrip(ArgumentMatchers.same(row), tripWithId(mine.getId()),
                ArgumentMatchers.eq(false))).thenReturn(true);
        Mockito.when(transactions.deleteForPerson(ArgumentMatchers.eq(row), ArgumentMatchers.any()))
                .thenReturn(true);
        final TransactionsResource admin = as(holderOf(PrivilegeCommands.TRIP_FIN_ADMIN, mine));

        final Response read = admin.get(traveler.getValue(), row.getTxId(), mine.getId());
        assertOk(read);
        Assert.assertEquals(((TransactionDto) read.getEntity()).tripIds(), List.of(mine.getId()));
        final TransactionDto amended = new TransactionDto(null, null, null, null, null, null, -150f, null,
                null, "amended", false, null, null, null, null, null, null, null);
        assertOk(admin.update(traveler.getValue(), row.getTxId(), CSRF_OK, mine.getId(), amended));
        Assert.assertEquals(row.getAmount(), -150f);
        Assert.assertEquals(tripsOf(row), List.of(mine.getId()), "still on the same books");
        assertOk(admin.delete(traveler.getValue(), row.getTxId(), CSRF_OK, mine.getId(), false));
    }

    /** Reads: a finance viewer sees the rows on their trip's books, one person at a time or all at once. */
    @Test
    public void aFinanceViewerReadsOnlyTheRowsOnTheirTripsBooksAndWritesNothing() throws IOException {
        final Person.Id traveler = Person.Id.newInstance();
        final Trip mine = storedTrip(traveler);
        final Trip other = storedTrip(traveler);
        final Transaction here = rowOnBooks(traveler, mine);
        final Transaction elsewhere = rowOnBooks(traveler, other);
        final Transaction unbound = new Transaction(traveler, null, null);
        Mockito.when(transactions.getTransactions(traveler)).thenReturn(List.of(here, elsewhere, unbound));
        Mockito.when(transactions.getTripTransactions(mine.getId())).thenReturn(List.of(here, elsewhere, unbound));
        final TransactionsResource viewer = as(holderOf(PrivilegeCommands.TRIP_FIN_VIEW, mine));

        final List<?> ledger = (List<?>) viewer.forPerson(traveler.getValue(), mine.getId(), null).getEntity();
        Assert.assertEquals(ledger.size(), 1);
        Assert.assertEquals(((TransactionDto) ledger.get(0)).txId(), here.getTxId());
        final List<?> books = (List<?>) viewer.forTrip(mine.getId()).getEntity();
        Assert.assertEquals(books.size(), 1, "the roster's rows from other trips are not this trip's books");
        Assert.assertEquals(((TransactionDto) books.get(0)).txId(), here.getTxId());
        assertError(viewer.get(traveler.getValue(), elsewhere.getTxId(), mine.getId()), 404, ApiErrors.NOT_FOUND);

        assertError(viewer.update(traveler.getValue(), here.getTxId(), CSRF_OK, mine.getId(), null),
                403, ApiErrors.FORBIDDEN);
        assertError(viewer.create(traveler.getValue(), CSRF_OK, mine.getId(), null), 403, ApiErrors.FORBIDDEN);
        assertError(viewer.forTrip(other.getId()), 403, ApiErrors.FORBIDDEN);
    }

    /** A person acting for themselves keeps their whole ledger, whatever {@code ?trip=} says. */
    @Test
    public void yourOwnLedgerIsWholeEvenWhenYouAreFinanceStaffSomewhere() throws IOException {
        final Trip mine = storedTrip();
        final Person.Id me = holderOf(PrivilegeCommands.TRIP_FIN_VIEW, mine);
        final Trip other = storedTrip(me);
        final Transaction shared = new Transaction(me, "group-own", Transaction.Type.Shared);
        shared.setGroupPeople(List.of(me, OTHER));
        final BindingCommands real = new BindingCommands();
        real.setBindings(real.key(me.getValue(), shared.getTxId()), BindingType.TRANSACTION, BindingType.TRIP,
                List.of(other.getId()), true);
        Mockito.when(transactions.getTransactions(me)).thenReturn(List.of(shared));
        Mockito.when(transactions.getTransaction(me, shared.getTxId())).thenReturn(shared);

        final TransactionsResource self = as(me);
        final List<?> ledger = (List<?>) self.forPerson(me.getValue(), mine.getId(), null).getEntity();
        Assert.assertEquals(ledger.size(), 1, "my row on another trip is still mine to read");
        Assert.assertNull(((TransactionDto) ledger.get(0)).groupPeople(),
                "but its group is not my trip's business: I am not staff on that row's trip");
        final Response one = self.get(me.getValue(), shared.getTxId(), mine.getId());
        assertOk(one);
        Assert.assertNull(((TransactionDto) one.getEntity()).groupPeople());
    }

    /** A new charge lands only on someone who is on the trip: its roster, a registrant, or the trip itself. */
    @Test
    public void aChargeNeedsItsPersonOnTheTrip() throws IOException {
        final Person.Id onRoster = Person.Id.newInstance();
        final Person.Id pending = Person.Id.newInstance();
        final Person.Id withdrawn = Person.Id.newInstance();
        final Person.Id stranger = Person.Id.newInstance();
        final Trip mine = storedTrip(onRoster);
        Assert.assertTrue(DAO.getInstance().saveRegistration(new Registration(mine.getId(), pending, null,
                Registration.Status.PENDING, new HashMap<>())));
        Assert.assertTrue(DAO.getInstance().saveRegistration(new Registration(mine.getId(), withdrawn, null,
                Registration.Status.NOT_REGISTERED, new HashMap<>())));
        Mockito.when(transactions.createTransaction(ArgumentMatchers.any()))
                .thenAnswer(call -> new Transaction(call.getArgument(0), null, null));
        Mockito.when(transactions.saveOnTrip(ArgumentMatchers.any(), tripWithId(mine.getId()),
                ArgumentMatchers.eq(true))).thenReturn(true);
        final TransactionsResource admin = as(holderOf(PrivilegeCommands.TRIP_FIN_ADMIN, mine));

        assertError(admin.create(stranger.getValue(), CSRF_OK, mine.getId(), null), 403, ApiErrors.FORBIDDEN);
        assertError(admin.create(withdrawn.getValue(), CSRF_OK, mine.getId(), null), 403, ApiErrors.FORBIDDEN);
        Mockito.verify(transactions, Mockito.never()).createTransaction(ArgumentMatchers.any());

        assertOk(admin.create(onRoster.getValue(), CSRF_OK, mine.getId(), null));
        assertOk(admin.create(pending.getValue(), CSRF_OK, mine.getId(), null));
        assertOk(admin.create(mine.getId(), CSRF_OK, mine.getId(), null));      // the trip's own ledger
    }

    /** A save binds a row that has no trip; it never lifts one off the books it is on (setBindings replaces). */
    @Test
    public void anUpdateNeverMovesARowBetweenTripsBooks() throws IOException {
        final Person.Id traveler = Person.Id.newInstance();
        final Trip home = storedTrip(traveler);
        final Trip named = storedTrip();
        final Transaction row = rowOnBooks(traveler, home);
        bind(BindingCommands.class, new BindingCommands());
        Mockito.when(transactions.saveOnTrip(ArgumentMatchers.same(row), ArgumentMatchers.any(),
                ArgumentMatchers.anyBoolean())).thenReturn(true);
        signedInAsSiteAdmin(ME);

        assertOk(resource.update(traveler.getValue(), row.getTxId(), CSRF_OK, named.getId(), null));

        Assert.assertEquals(tripsOf(row), List.of(home.getId()), "a site admin's ?trip= does not re-home the row");
        // Nor does it give the row ITS organization: the row is saved on the trip it is on, and not re-bound.
        Mockito.verify(transactions).saveOnTrip(ArgumentMatchers.same(row), tripWithId(home.getId()),
                ArgumentMatchers.eq(false));
    }

    /**
     * A save GIVES a stored row that is on no trip its {@code ?trip=}, and that bind is held to the write
     * invariant: a trip that exists, has an organization, and is of the row's own organization.
     */
    @Test
    public void anUpdateBindsATripLessRowOnlyToARealTripOfItsOwnOrganization() throws IOException {
        final Person.Id traveler = Person.Id.newInstance();
        final Trip home = storedTrip(traveler);
        final Trip foreign = storedTrip(traveler);
        foreign.setOrgId(FakeData.ACME_ORG_ID);
        Assert.assertTrue(DAO.getInstance().saveTrip(foreign));
        final Trip orgless = Trip.builder().id(UUID.randomUUID().toString()).title("No org").build();
        Assert.assertTrue(DAO.getInstance().saveTrip(orgless));
        final Transaction row = new Transaction(traveler, null, null);
        row.setOrgId(FakeData.CFPW_ORG_ID);
        Mockito.when(transactions.getTransaction(traveler, row.getTxId())).thenReturn(row);
        bind(BindingCommands.class, new BindingCommands());
        signedInAsSiteAdmin(ME);

        for (final String refused : List.of(UUID.randomUUID().toString(), orgless.getId(), foreign.getId())) {
            assertError(resource.update(traveler.getValue(), row.getTxId(), CSRF_OK, refused, null), 400,
                    ApiErrors.VALIDATION_FAILED);
        }
        // No ?trip= at all: a row on no trip is not edited until its trip is named.
        assertError(resource.update(traveler.getValue(), row.getTxId(), CSRF_OK, null, null), 400,
                ApiErrors.VALIDATION_FAILED);
        assertError(resource.update(traveler.getValue(), row.getTxId(), CSRF_OK, " ", null), 400,
                ApiErrors.VALIDATION_FAILED);
        Mockito.verify(transactions, Mockito.never()).saveOnTrip(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyBoolean());
        Assert.assertEquals(tripsOf(row), List.of(), "a refused bind writes nothing");

        Mockito.when(transactions.saveOnTrip(ArgumentMatchers.same(row), tripWithId(home.getId()),
                ArgumentMatchers.eq(true))).thenReturn(true);
        assertOk(resource.update(traveler.getValue(), row.getTxId(), CSRF_OK, home.getId(), null));
        Mockito.verify(transactions).saveOnTrip(ArgumentMatchers.same(row), tripWithId(home.getId()),
                ArgumentMatchers.eq(true));
    }

    /** The group door: a group id is all it takes to name somebody else's shared transaction. */
    @Test
    public void aGroupSaveIsHeldToTheTripsRosterAndBooks() throws IOException {
        final Person.Id member = Person.Id.newInstance();
        final Person.Id stranger = Person.Id.newInstance();
        final Trip mine = storedTrip(member);
        final Trip theirs = storedTrip(stranger);
        final Transaction foreign = new Transaction(stranger, "group-theirs", Transaction.Type.Shared);
        final BindingCommands real = new BindingCommands();
        real.setBindings(real.key(stranger.getValue(), foreign.getTxId()), BindingType.TRANSACTION,
                BindingType.TRIP, List.of(theirs.getId()), true);
        Mockito.when(transactions.getGroupTransactionForUser(ArgumentMatchers.any(), ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        Mockito.when(transactions.getGroupTransactionForUser(stranger, "group-theirs"))
                .thenReturn(Optional.of(foreign));
        Mockito.when(transactions.getUserIdsForGroup(foreign)).thenReturn(List.of(stranger));
        final TransactionsResource admin = as(holderOf(PrivilegeCommands.TRIP_FIN_ADMIN, mine));

        // Charging someone who is not on the trip.
        assertError(admin.saveGroup(CSRF_OK, mine.getId(), group(null, List.of(stranger.getValue()))),
                403, ApiErrors.FORBIDDEN);
        // Emptying (deleting) another trip's group by naming its id and its member.
        assertError(admin.saveGroup(CSRF_OK, mine.getId(), new TransactionsResource.GroupTransactionRequest(
                "group-theirs", List.of(stranger.getValue()), List.of(), "Shared", "Bill", LocalDateTime.now(),
                100f, "lodging", "note", null)), 403, ApiErrors.FORBIDDEN);
        assertError(admin.groupMembers("group-theirs", stranger.getValue(), mine.getId()),
                404, ApiErrors.NOT_FOUND);
        Mockito.verify(transactions, Mockito.never()).saveGroup(ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());

        // Their own roster, a new group and an amended one: through.
        Mockito.when(transactions.saveGroup(ArgumentMatchers.anyString(), ArgumentMatchers.anyList(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyList())).thenReturn(new TransactionsCommands.GroupSave(true, null));
        assertOk(admin.saveGroup(CSRF_OK, mine.getId(), group(null, List.of(member.getValue()))));
        assertOk(admin.saveGroup(CSRF_OK, mine.getId(), group("group-mine", List.of(member.getValue()))));
        // A kept member's row was judged once, by mayKeepInGroup; only a dropped member's is read again.
        Mockito.verify(transactions, Mockito.never()).getGroupTransactionForUser(member, "group-mine");
        assertError(admin.groupMembers("group-mine", member.getValue(), theirs.getId()), 403, ApiErrors.FORBIDDEN);
    }

    // ------------------------------------------------------------------ the per-organization ledger

    private static Transaction storedRow(final Person.Id who, final String orgId, final float amount)
            throws IOException {
        final Transaction tx = new Transaction(who, null, null);
        tx.setOrgId(orgId);
        tx.setAmount(amount);
        Assert.assertTrue(DAO.getInstance().saveTransaction(tx));
        return tx;
    }

    /** A person's balances name one organization each; theirs to read, a stranger's to be refused. */
    @Test
    public void balancesAreOneLinePerOrganization() throws IOException {
        FakeData.addFakeData();
        final Person.Id traveler = Person.Id.newInstance();
        final Person.Id stranger = Person.Id.newInstance();
        storedRow(traveler, FakeData.CFPW_ORG_ID, -300f);
        storedRow(traveler, FakeData.CFPW_ORG_ID, 100f);
        storedRow(traveler, FakeData.ACME_ORG_ID, 50f);

        @SuppressWarnings("unchecked")
        final List<OrgBalanceDto> mine = (List<OrgBalanceDto>) as(traveler).balances(traveler.getValue())
                .getEntity();
        Assert.assertEquals(mine.stream().map(OrgBalanceDto::orgId).toList(),
                List.of(FakeData.ACME_ORG_ID, FakeData.CFPW_ORG_ID), "sorted by organization name");
        final OrgBalanceDto acme = mine.get(0);
        Assert.assertEquals(acme.orgName(), "Acme Inc");
        Assert.assertEquals(acme.balanceCents(), 5000L);
        Assert.assertEquals(acme.state(), "CREDIT");
        Assert.assertEquals(acme.transactions(), 1);
        final OrgBalanceDto cfpw = mine.get(1);
        Assert.assertEquals(cfpw.balance(), -200d, 1e-9);
        Assert.assertEquals(cfpw.balanceCents(), -20000L);
        Assert.assertEquals(cfpw.state(), "OWES");
        Assert.assertEquals(cfpw.transactions(), 2);

        assertError(as(stranger).balances(traveler.getValue()), 403, ApiErrors.FORBIDDEN);
        signedInAsSiteAdmin(ME);
        assertOk(resource(new TransactionsResource()).balances(traveler.getValue()));
    }

    /** {@code ?org=} narrows the ledger to one organization's rows, each of which names its organization. */
    @Test
    public void theLedgerNamesEachRowsOrganizationAndFiltersByIt() throws IOException {
        FakeData.addFakeData();
        final Person.Id traveler = Person.Id.newInstance();
        final Transaction cfpw = storedRow(traveler, FakeData.CFPW_ORG_ID, -300f);
        final Transaction acme = storedRow(traveler, FakeData.ACME_ORG_ID, 50f);
        final Transaction orgless = storedRow(traveler, null, 1f);
        Mockito.when(transactions.getTransactions(traveler)).thenReturn(List.of(cfpw, acme, orgless));
        final TransactionsResource own = as(traveler);

        Assert.assertEquals(rows(own.forPerson(traveler.getValue(), null, null)).size(), 3, "no filter: all");
        Assert.assertEquals(rows(own.forPerson(traveler.getValue(), null, " ")).size(), 3);
        final List<TransactionDto> acmes = rows(own.forPerson(traveler.getValue(), null, FakeData.ACME_ORG_ID));
        Assert.assertEquals(acmes.stream().map(TransactionDto::txId).toList(), List.of(acme.getTxId()));
        Assert.assertEquals(acmes.get(0).orgId(), FakeData.ACME_ORG_ID);
        Assert.assertEquals(acmes.get(0).orgName(), "Acme Inc");
        Assert.assertEquals(acmes.get(0).withoutGroupPeople().orgName(), "Acme Inc", "it survives redaction");
        final List<TransactionDto> stray = rows(own.forPerson(traveler.getValue(), null, "unassigned"));
        Assert.assertEquals(stray.stream().map(TransactionDto::txId).toList(), List.of(orgless.getTxId()));
        Assert.assertNull(stray.get(0).orgId());
        Assert.assertNull(stray.get(0).orgName(), "a row with no organization names none");
        Assert.assertEquals(rows(own.forPerson(traveler.getValue(), null, "no-such-org")), List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<TransactionDto> rows(final Response response) {
        return (List<TransactionDto>) response.getEntity();
    }

    /**
     * A new row must name a real trip that has an organization. Only a site admin gets as far as being told so:
     * everyone else's finance role IS a trip, and no trip is their 403.
     */
    @Test
    public void creatingARowOrAGroupWithoutARealTripIsRefused() throws IOException {
        final Person.Id traveler = Person.Id.newInstance();
        final Trip orgless = Trip.builder().id(UUID.randomUUID().toString()).title("No org").build();
        Assert.assertTrue(DAO.getInstance().saveTrip(orgless));
        signedInAsSiteAdmin(ME);
        // The group's trip rule is the save's own to judge (it answers why it refused), so the real save runs
        // here: a refusal comes before anything is written.
        Mockito.when(transactions.saveGroup(ArgumentMatchers.anyString(), ArgumentMatchers.anyList(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyList())).thenCallRealMethod();

        for (final String noTrip : new String[] {null, "", "none", "no-such-trip", orgless.getId()}) {
            assertError(resource.create(traveler.getValue(), CSRF_OK, noTrip, null), 400,
                    ApiErrors.VALIDATION_FAILED);
            assertError(resource.saveGroup(CSRF_OK, noTrip, group(null, List.of(traveler.getValue()))), 400,
                    ApiErrors.VALIDATION_FAILED);
        }
        Mockito.verify(transactions, Mockito.never()).createTransaction(ArgumentMatchers.any());
        Mockito.verify(transactions, Mockito.never()).saveTransaction(ArgumentMatchers.any());
    }

    @Test
    public void theProducedTypeIsTheTransactionsMediaType() {
        Assert.assertEquals(new TransactionsResource().versionedType(), ApiMediaTypes.TRANSACTIONS_V1);
    }

    private static TransactionsResource.GroupTransactionRequest group(
            final String groupId, final List<String> people) {
        return new TransactionsResource.GroupTransactionRequest(groupId, people, people, "Shared", "Bill",
                LocalDateTime.now(), 100f, "lodging", "note", null);
    }
}
