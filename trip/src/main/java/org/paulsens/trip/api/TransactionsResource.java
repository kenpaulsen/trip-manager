package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.TransactionsCommands;
import org.paulsens.trip.api.dto.TransactionDto;
import org.paulsens.trip.api.mapper.TransactionMapper;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;

/**
 * Money owed and money paid.
 *
 * <p>Two authorization scopes, and they are not the same. A traveller may read THEIR OWN ledger without holding
 * any privilege -- being shown what you owe is the point. Reading anybody else's, or the trip's books as a
 * whole, needs {@code tripFinView}; writing needs {@code tripFinAdmin}. Nothing here lets a traveller write their
 * own balance, which would be the obvious symmetry and an obviously bad idea.
 */
@Slf4j
@Path("transactions")
@TripApi
public class TransactionsResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.TRANSACTIONS_V1;

    @Override
    protected String versionedType() {
        return V1;
    }

    /** One person's ledger. Theirs, their manager's, or trip finance staff's to read. */
    @GET
    @Path("people/{personId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response forPerson(
            @PathParam("personId") final String personIdParam, @QueryParam("trip") final String tripId) {
        final Person.Id subject = Person.Id.from(personIdParam);
        if (!canReadFinances(subject, tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to read these finances.");
        }
        final boolean staff = isFinanceStaff(tripId);
        return ok(Beans.get(TransactionsCommands.class).getTransactions(subject).stream()
                .map(tx -> dto(tx, staff))
                .toList());
    }

    @GET
    @Path("people/{personId}/{txId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response get(
            @PathParam("personId") final String personIdParam,
            @PathParam("txId") final String txId,
            @QueryParam("trip") final String tripId) {
        final Person.Id subject = Person.Id.from(personIdParam);
        if (!canReadFinances(subject, tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to read these finances.");
        }
        final Transaction tx = Beans.get(TransactionsCommands.class).getTransaction(subject, txId);
        if (tx == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such transaction.");
        }
        return ok(dto(tx, isFinanceStaff(tripId)));
    }

    /** The trip's books. Finance staff only, and the single most sensitive read on this resource. */
    @GET
    @Path("trips/{tripId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response forTrip(@PathParam("tripId") final String tripId) {
        if (!isFinanceStaff(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip finance access required.");
        }
        return ok(Beans.get(TransactionsCommands.class).getTripTransactions(tripId).stream()
                .map(tx -> dto(tx, true))
                .toList());
    }

    /** Who shares a group transaction. */
    @GET
    @Path("groups/{groupId}/people/{personId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response groupMembers(
            @PathParam("groupId") final String groupId,
            @PathParam("personId") final String personIdParam,
            @QueryParam("trip") final String tripId) {
        if (!isFinanceStaff(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip finance access required.");
        }
        final TransactionsCommands transactions = Beans.get(TransactionsCommands.class);
        return transactions.getGroupTransactionForUser(Person.Id.from(personIdParam), groupId)
                .map(tx -> ok(Map.of(
                        "groupId", groupId,
                        "people", transactions.getUserIdsForGroup(tx).stream().map(Person.Id::getValue).toList())))
                .orElseGet(() -> error(404, ApiErrors.NOT_FOUND, "No such group transaction."));
    }

    @POST
    @Path("people/{personId}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response create(
            @PathParam("personId") final String personIdParam,
            @HeaderParam(CSRF_HEADER) final String csrf,
            @QueryParam("trip") final String tripId,
            final TransactionDto body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!isFinanceAdmin(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip finance administrator required.");
        }
        final TransactionsCommands transactions = Beans.get(TransactionsCommands.class);
        final Person.Id subject = Person.Id.from(personIdParam);
        final Transaction tx = transactions.createTransaction(subject);
        apply(body, tx);
        if (!transactions.saveTransaction(tx)) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the transaction.");
        }
        Beans.get(AuditCommands.class).transaction(findPerson(subject), tx, actor());
        return ok(dto(tx, true));
    }

    @PUT
    @Path("people/{personId}/{txId}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response update(
            @PathParam("personId") final String personIdParam,
            @PathParam("txId") final String txId,
            @HeaderParam(CSRF_HEADER) final String csrf,
            @QueryParam("trip") final String tripId,
            final TransactionDto body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!isFinanceAdmin(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip finance administrator required.");
        }
        final TransactionsCommands transactions = Beans.get(TransactionsCommands.class);
        final Person.Id subject = Person.Id.from(personIdParam);
        // Blank txId makes getTransaction MINT a new transaction rather than miss, so an empty path segment
        // would quietly create a row instead of 404ing. The path cannot be empty here, but the guard is cheap.
        if (txId == null || txId.isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, "A transaction id is required.");
        }
        final Transaction tx = transactions.getTransaction(subject, txId);
        if (tx == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such transaction.");
        }
        apply(body, tx);
        if (!transactions.saveTransaction(tx)) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the transaction.");
        }
        Beans.get(AuditCommands.class).transaction(findPerson(subject), tx, actor());
        return ok(dto(tx, true));
    }

    /**
     * Creates or updates a transaction shared across several people.
     *
     * <p>Calls {@code saveGroupTransaction}, the typed entry point, rather than the {@code Object...} form the
     * batch-transaction page uses. That form exists to untangle what PrimeFaces widgets submit -- arrays,
     * collections and bare Strings mixed together -- and a JSON array has nothing to untangle.
     */
    @POST
    @Path("groups")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response saveGroup(
            @HeaderParam(CSRF_HEADER) final String csrf,
            @QueryParam("trip") final String tripId,
            final GroupTransactionRequest body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!isFinanceAdmin(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip finance administrator required.");
        }
        if (body == null || body.people() == null || body.people().isEmpty()) {
            return error(400, ApiErrors.BAD_REQUEST, "At least one person is required.");
        }
        // The group id is minted HERE when the client did not supply one, rather than left to the bean. The
        // bean generates one internally and returns only a boolean, so a client that let it do so would have
        // just created a group transaction it has no way to name, fetch or amend afterwards.
        final String groupId = (body.groupId() == null || body.groupId().isBlank())
                ? UUID.randomUUID().toString() : body.groupId();
        final boolean saved = Beans.get(TransactionsCommands.class).saveGroupTransaction(
                groupId,
                ids(body.originalPeople()),
                Transaction.Type.valueOf(body.type()),
                Transaction.TransactionType.valueOf(body.txType()),
                body.txDate(),
                body.amount(),
                body.category(),
                body.note(),
                tripId,
                body.eventId(),
                ids(body.people()));
        if (!saved) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the group transaction.");
        }
        return ok(Map.of("saved", true, "groupId", groupId));
    }

    /** A group transaction as a client submits it -- typed, unlike the page's widget soup. */
    public record GroupTransactionRequest(
            String groupId,
            List<String> originalPeople,
            List<String> people,
            String type,
            String txType,
            LocalDateTime txDate,
            Float amount,
            String category,
            String note,
            String eventId) {
    }

    private static List<Person.Id> ids(final List<String> values) {
        return values == null ? List.of() : values.stream().map(Person.Id::from).toList();
    }

    /**
     * The wire form, with this person's share filled in.
     *
     * <p>{@code getUserAmount} divides a shared amount across the group; a client that rendered {@code amount}
     * on a traveller's ledger would tell them they owe the whole bill.
     */
    private TransactionDto dto(final Transaction tx, final boolean staff) {
        final TransactionDto mapped = TransactionMapper.INSTANCE.toDto(tx);
        final Float share = Beans.get(TransactionsCommands.class).getUserAmount(tx);
        final TransactionDto withShare = new TransactionDto(mapped.txId(), mapped.userId(), mapped.groupId(),
                mapped.type(), mapped.txType(), mapped.txDate(), mapped.amount(), share, mapped.category(),
                mapped.note(), mapped.deleted(), mapped.groupPeople());
        return staff ? withShare : withShare.withoutGroupPeople();
    }

    private static void apply(final TransactionDto body, final Transaction tx) {
        if (body == null) {
            return;
        }
        if (body.amount() != null) {
            tx.setAmount(body.amount());
        }
        if (body.txDate() != null) {
            tx.setTxDate(body.txDate());
        }
        if (body.category() != null) {
            tx.setCategory(body.category());
        }
        if (body.note() != null) {
            tx.setNote(body.note());
        }
        if (body.txType() != null) {
            tx.setTxType(Transaction.TransactionType.valueOf(body.txType()));
        }
    }

    private boolean isFinanceStaff(final String tripId) {
        final ApiPrivileges privileges = privileges();
        return privileges.has(ApiPrivileges.TRIP_FIN_VIEW, tripId)
                || privileges.has(ApiPrivileges.TRIP_FIN_ADMIN, tripId);
    }

    private boolean isFinanceAdmin(final String tripId) {
        return privileges().has(ApiPrivileges.TRIP_FIN_ADMIN, tripId);
    }

    /** Your own ledger, one you manage, or trip finance staff. */
    private boolean canReadFinances(final Person.Id subject, final String tripId) {
        return subject.equals(personId())
                || isFinanceStaff(tripId)
                || privileges().canActFor(findPerson(personId()), subject);
    }
}
