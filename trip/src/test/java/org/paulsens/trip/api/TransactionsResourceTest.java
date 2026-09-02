package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.TransactionsCommands;
import org.paulsens.trip.api.dto.TransactionDto;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link TransactionsResource}: money owed and money paid.
 *
 * <p>Three behaviours carry the risk here. A traveller reads their OWN ledger and nobody else's without
 * privilege; a shared amount is presented as this person's SHARE, not the whole bill; and the group membership
 * list is staff-only on the wire.
 */
public class TransactionsResourceTest extends ResourceTestSupport {

    private static final Person.Id ME = Person.Id.from("tx-me");
    private static final Person.Id OTHER = Person.Id.from("tx-other");
    private static final String TRIP_ID = "trip-tx";

    private TransactionsCommands transactions;
    private AuditCommands audit;
    private TransactionsResource resource;

    @BeforeMethod
    public void bindBeans() {
        transactions = bindMock(TransactionsCommands.class);
        audit = bindMock(AuditCommands.class);
        bindMock(PersonCommands.class);
        resource = resource(new TransactionsResource());
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

        final Response response = resource.forPerson(ME.getValue(), null);

        assertOk(response);
        Assert.assertEquals(((List<?>) response.getEntity()).size(), 1);
    }

    @Test
    public void aTravellerCannotReadAnybodyElsesLedger() {
        signedInAs(ME);

        assertError(resource.forPerson(OTHER.getValue(), TRIP_ID), 403, ApiErrors.FORBIDDEN);
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
                ((List<?>) resource.forPerson(ME.getValue(), null).getEntity()).get(0);

        Assert.assertEquals(dto.userAmount(), 250f);
        Assert.assertEquals(dto.amount(), 1000f, "The full amount stays present for context");
    }

    /** Group membership is who ELSE is on the bill: staff-only on the wire. */
    @Test
    public void aTravellersOwnViewOmitsTheGroupMembership() {
        signedInAs(ME);
        Mockito.when(transactions.getTransactions(ME)).thenReturn(List.of(tx(ME)));
        Mockito.when(transactions.getUserAmount(ArgumentMatchers.any())).thenReturn(250f);

        final TransactionDto dto = (TransactionDto)
                ((List<?>) resource.forPerson(ME.getValue(), null).getEntity()).get(0);

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
        Mockito.when(transactions.saveTransaction(created, TRIP_ID)).thenReturn(true);
        Mockito.when(transactions.getUserAmount(created)).thenReturn(75f);

        final TransactionDto body = new TransactionDto(null, null, null, null, "Bill", null, 75f, null,
                "lodging", "deposit", false, null);
        assertOk(resource.create(OTHER.getValue(), CSRF_OK, TRIP_ID, body));

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
        Mockito.when(transactions.saveTransaction(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(false);

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
        Mockito.verify(transactions, Mockito.never()).saveTransaction(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    public void updateMutatesTheObjectItReadAndAudits() {
        signedInAsSiteAdmin(ME);
        final Transaction existing = tx(ME);
        Mockito.when(transactions.getTransaction(ME, existing.getTxId())).thenReturn(existing);
        Mockito.when(transactions.saveTransaction(existing, TRIP_ID)).thenReturn(true);
        Mockito.when(transactions.getUserAmount(existing)).thenReturn(80f);

        final TransactionDto body = new TransactionDto(null, null, null, null, null, null, 80f, null,
                null, "amended", false, null);
        assertOk(resource.update(ME.getValue(), existing.getTxId(), CSRF_OK, TRIP_ID, body));

        Assert.assertEquals(existing.getAmount(), 80f);
        Assert.assertEquals(existing.getNote(), "amended");
        Mockito.verify(transactions).saveTransaction(ArgumentMatchers.same(existing), ArgumentMatchers.eq(TRIP_ID));
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
        Mockito.when(transactions.saveGroupTransaction(ArgumentMatchers.anyString(), ArgumentMatchers.anyList(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyList())).thenReturn(true);

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
        Mockito.when(transactions.saveGroupTransaction(ArgumentMatchers.anyString(), ArgumentMatchers.anyList(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyList())).thenReturn(false);

        assertError(resource.saveGroup(CSRF_OK, TRIP_ID, group(null, List.of(ME.getValue()))),
                500, ApiErrors.STORE_FAILED);
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
