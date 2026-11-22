package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.BindingCommands;
import org.paulsens.trip.action.LedgerCommands;
import org.paulsens.trip.action.LedgerStatements;
import org.paulsens.trip.action.TransactionsCommands;
import org.paulsens.trip.action.TripFinanceGate;
import org.paulsens.trip.api.dto.OrgBalanceDto;
import org.paulsens.trip.api.dto.TransactionDto;
import org.paulsens.trip.api.mapper.TransactionMapper;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;

/**
 * Money owed and money paid.
 *
 * <p>Two authorization scopes, and they are not the same. A traveller may read THEIR OWN ledger without holding
 * any privilege -- being shown what you owe is the point. Reading anybody else's, or the trip's books as a
 * whole, needs {@code tripFinView}; writing needs {@code tripFinAdmin}. Nothing here lets a traveller write their
 * own balance, which would be the obvious symmetry and an obviously bad idea.
 *
 * <p>A finance role is a role on ONE trip, named by {@code ?trip=}, and it reaches only the rows on that
 * trip's books: every staff read and write asks {@link TripFinanceGate}, which binds the row (or, for a new
 * one, its person) to the trip the privilege is on. The role alone used to be the whole check, so a finance
 * admin of any trip could read, rewrite and delete every person's rows on every other.
 */
@Slf4j
@Path("transactions")
@TripApi
public class TransactionsResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.TRANSACTIONS_V1;

    private TripFinanceGate cachedGate;
    /** {@link #orgName}'s per-request memo: a ledger is many rows of one or two organizations. */
    private final Map<String, String> orgNames = new HashMap<>();
    /** {@link #shownTrip}'s per-request memo, by trip id: a ledger's rows sit on a handful of trips. */
    private final Map<String, Optional<Trip>> trips = new HashMap<>();

    @Override
    protected String versionedType() {
        return V1;
    }

    /**
     * One person's ledger. Theirs or their manager's to read whole; trip finance staff get the rows on the
     * books of the trip their role is on ({@code ?trip=}), and nothing of the person's other trips.
     */
    @GET
    @Path("people/{personId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response forPerson(
            @PathParam("personId") final String personIdParam, @QueryParam("trip") final String tripId,
            @QueryParam("org") final String orgId) {
        final Person.Id subject = Person.Id.from(personIdParam);
        final boolean own = actsFor(subject);
        final boolean staff = gate().canReadLedgers(tripId);
        if (!own && !staff) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.transactions.readNotPermitted"));
        }
        return ok(Beans.get(TransactionsCommands.class).getTransactions(subject).stream()
                .filter(tx -> own || gate().listsRow(tripId, tx))
                .filter(tx -> isWithOrg(tx, orgId))
                .map(tx -> dto(tx, staff && (!own || gate().listsRow(tripId, tx))))
                .toList());
    }

    /**
     * The optional {@code ?org=} filter: only the rows that are with this organization (the statement behind one
     * line of {@link #balances}); {@code unassigned} names the rows with none. It only ever narrows.
     */
    private static boolean isWithOrg(final Transaction tx, final String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return true;
        }
        final boolean orgless = tx.getOrgId() == null || tx.getOrgId().isBlank();
        return LedgerCommands.UNASSIGNED_ORG_ID.equals(orgId) ? orgless : orgId.equals(tx.getOrgId());
    }

    /**
     * A person's balance with each organization: one line per organization this host lists that they have
     * transactions (or a trip) with. Theirs and their family manager's to read, and a site administrator's;
     * anyone else is shown only the organizations they manage or hold {@code peopleAdmin} or
     * {@code viewFinances} in ({@code LedgerCommands}, the one rule the Balance page uses too), and with none of
     * those is refused.
     */
    @GET
    @Path("people/{personId}/balances")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response balances(@PathParam("personId") final String personIdParam) {
        final Person.Id subject = Person.Id.from(personIdParam);
        final LedgerCommands ledger = new LedgerCommands(this::caller);
        if (!ledger.mayView(subject)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.transactions.readNotPermitted"));
        }
        return ok(ledger.orgBalances(List.of(subject)).stream().map(OrgBalanceDto::of).toList());
    }

    @GET
    @Path("people/{personId}/{txId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response get(
            @PathParam("personId") final String personIdParam,
            @PathParam("txId") final String txId,
            @QueryParam("trip") final String tripId) {
        final Person.Id subject = Person.Id.from(personIdParam);
        final boolean own = actsFor(subject);
        final boolean staff = gate().canReadLedgers(tripId);
        if (!own && !staff) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.transactions.readNotPermitted"));
        }
        final Transaction tx = Beans.get(TransactionsCommands.class).getTransaction(subject, txId);
        // A row on another trip's books reads as absent to staff: "exists, but not yours" would confirm it.
        final boolean onBooks = tx != null && staff && gate().mayReadRow(tripId, tx);
        if (tx == null || !(own || onBooks)) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.transactions.noSuchTransaction"));
        }
        return ok(dto(tx, onBooks));
    }

    /**
     * The trip's books. Finance staff only, and the single most sensitive read on this resource. The roster's
     * rows on THIS trip's books, as the website's trip ledger lists them: a traveler's rows from another trip
     * (another organization's, even) are not this trip's staff's to read.
     */
    @GET
    @Path("trips/{tripId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response forTrip(@PathParam("tripId") final String tripId) {
        if (!gate().canReadLedgers(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.transactions.financeAccessRequired"));
        }
        return ok(Beans.get(TransactionsCommands.class).getTripTransactions(tripId).stream()
                .filter(tx -> gate().listsRow(tripId, tx))
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
        if (!gate().canReadLedgers(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.transactions.financeAccessRequired"));
        }
        final TransactionsCommands transactions = Beans.get(TransactionsCommands.class);
        return transactions.getGroupTransactionForUser(Person.Id.from(personIdParam), groupId)
                .filter(tx -> gate().mayReadRow(tripId, tx))
                .map(tx -> ok(Map.of(
                        "groupId", groupId,
                        "people", transactions.getUserIdsForGroup(tx).stream().map(Person.Id::getValue).toList())))
                .orElseGet(() -> error(404, ApiErrors.NOT_FOUND, Text.get("api.transactions.noSuchGroup")));
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
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        if (!gate().canWriteLedgers(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.transactions.financeAdminRequired"));
        }
        final TransactionsCommands transactions = Beans.get(TransactionsCommands.class);
        final Person.Id subject = Person.Id.from(personIdParam);
        if (!gate().mayCharge(tripId, subject)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.transactions.personNotOnTrip"));
        }
        // A new row needs a real trip that has an organization: only a site admin gets this far without one
        // (everyone else's finance role IS a trip), and theirs used to be saved with no trip and no org.
        final Trip trip = TransactionsCommands.ledgerTrip(tripId);
        final String tripProblem = TransactionsCommands.tripProblem(null, tripId, trip);
        if (tripProblem != null) {
            return error(400, ApiErrors.VALIDATION_FAILED, tripProblem);
        }
        final Transaction tx = transactions.createTransaction(subject);
        apply(body, tx);
        // The save stamps the tenancy boundary (Transaction.orgId) from ?trip=, as the page does, and puts the
        // row on that trip's books: an unstamped row is org-less legacy content, which the shared sites list and
        // an org's own site never would.
        if (!transactions.saveOnTrip(tx, trip, true)) {
            return error(500, ApiErrors.STORE_FAILED, Text.get("api.transactions.notSaved"));
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
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        if (!gate().canWriteLedgers(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.transactions.financeAdminRequired"));
        }
        final TransactionsCommands transactions = Beans.get(TransactionsCommands.class);
        final Person.Id subject = Person.Id.from(personIdParam);
        // Blank txId makes getTransaction MINT a new transaction rather than miss, so an empty path segment
        // would quietly create a row instead of 404ing. The path cannot be empty here, but the guard is cheap.
        if (txId == null || txId.isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, Text.get("api.transactions.idRequired"));
        }
        final Transaction tx = transactions.getTransaction(subject, txId);
        if (tx == null || !gate().mayWriteRow(tripId, tx)) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.transactions.noSuchTransaction"));
        }
        // A stored row on no trip is GIVEN ?trip= by the save, and that bind is held to the write invariant
        // like a new row's: a real trip that has an organization, and the row's own organization when it
        // carries one. Otherwise a site admin's save could put a row on a trip that does not exist, or on
        // another organization's books. With no ?trip= at all the edit is refused: a row on no trip cannot be
        // changed until its trip is named (deleting it still works). A row already on a trip is not moved, so
        // nothing to ask, and it is stamped from THAT trip: ?trip= naming another organization's trip used to
        // give an org-less row that organization while the row stayed on the first trip's books.
        final String onTrip = boundTrips(tx).stream().findFirst().orElse(null);
        final Trip trip = TransactionsCommands.ledgerTrip(onTrip != null ? onTrip : tripId);
        final String tripProblem = onTrip == null ? TransactionsCommands.tripProblem(tx, tripId, trip) : null;
        if (tripProblem != null) {
            return error(400, ApiErrors.VALIDATION_FAILED, tripProblem);
        }
        apply(body, tx);
        if (!transactions.saveOnTrip(tx, trip, onTrip == null)) {    // a row that already carries an org keeps it
            return error(500, ApiErrors.STORE_FAILED, Text.get("api.transactions.notSaved"));
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
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        if (!gate().canWriteLedgers(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.transactions.financeAdminRequired"));
        }
        if (body == null || body.people() == null) {
            return error(400, ApiErrors.BAD_REQUEST, Text.get("api.transactions.membersRequired"));
        }
        if (!groupIsOnTheBooks(body, tripId)) {
            return error(403, ApiErrors.FORBIDDEN,
                    Text.get("api.transactions.membersNotOnTrip"));
        }
        // An EMPTY list is how a group is deleted: 0 members is no transaction (a Batch is a set of identical
        // charges and an empty set is legitimate; a Shared amount has nobody to divide between). It is only
        // meaningful as an AMENDMENT though -- creating an empty group would store nothing under a groupId the
        // client then has no way to use.
        if (body.people().isEmpty() && (body.groupId() == null || body.groupId().isBlank())) {
            return error(400, ApiErrors.BAD_REQUEST, Text.get("api.transactions.personRequiredForGroup"));
        }
        // The group id is minted HERE when the client did not supply one, rather than left to the bean. The
        // bean generates one internally and never returns it, so a client that let it do so would have
        // just created a group transaction it has no way to name, fetch or amend afterwards.
        final String groupId = (body.groupId() == null || body.groupId().isBlank())
                ? UUID.randomUUID().toString() : body.groupId();
        // The save judges the trip rule of every ledger write itself, before anything is touched (a new group,
        // or a member being added, needs a real trip that has an organization, and no row moves to another
        // organization's trip), and says which it broke. Asking first and then saving judged the group twice.
        final TransactionsCommands.GroupSave save = Beans.get(TransactionsCommands.class).saveGroup(
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
        if (save.problem() != null) {
            return error(400, ApiErrors.VALIDATION_FAILED, save.problem());
        }
        if (!save.saved()) {
            return error(500, ApiErrors.STORE_FAILED, Text.get("api.transactions.groupNotSaved"));
        }
        return ok(Map.of("saved", true, "groupId", groupId));
    }

    /**
     * Deletes a transaction. A row belonging to a Shared/Batch group is removed from the GROUP -- the
     * survivors' stamped membership is rewritten, and removing the last member deletes the transaction
     * itself -- because deleting such a row on its own leaves the Shared divisor too high and silently drops
     * that share from every balance and report.
     *
     * <p>{@code confirmUndo=true} is required to remove the LAST member of a Shared group: doing so undoes a
     * payment that may really have happened, which the pages warn about before they call this. Nothing else
     * needs it.
     */
    @DELETE
    @Path("people/{personId}/{txId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response delete(
            @PathParam("personId") final String personIdParam,
            @PathParam("txId") final String txId,
            @HeaderParam(CSRF_HEADER) final String csrf,
            @QueryParam("trip") final String tripId,
            @QueryParam("confirmUndo") final boolean confirmUndo) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        if (!gate().canWriteLedgers(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.transactions.financeAdminRequired"));
        }
        if (txId == null || txId.isBlank()) {
            // Blank makes getTransaction MINT a row rather than miss, so this would "delete" a new object.
            return error(400, ApiErrors.BAD_REQUEST, Text.get("api.transactions.idRequired"));
        }
        final TransactionsCommands transactions = Beans.get(TransactionsCommands.class);
        final Person.Id subject = Person.Id.from(personIdParam);
        final Transaction tx = transactions.getTransaction(subject, txId);
        if (tx == null || !gate().mayWriteRow(tripId, tx)) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.transactions.noSuchTransaction"));
        }
        if (tx.isShared() && !confirmUndo && transactions.getUserIdsForGroup(tx).size() == 1) {
            return error(409, ApiErrors.CONFLICT,
                    Text.get("api.transactions.confirmUndoRequired"));
        }
        if (!transactions.deleteForPerson(tx, actor())) {
            return error(500, ApiErrors.STORE_FAILED, Text.get("api.transactions.notDeleted"));
        }
        return ok(Map.of("deleted", true, "txId", txId));
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
        final List<String> tripIds = boundTrips(tx);
        final Optional<Trip> shown = shownTrip(tripIds, mapped.orgId());
        final TransactionDto withShare = new TransactionDto(mapped.txId(), mapped.userId(), mapped.groupId(),
                mapped.type(), mapped.txType(), mapped.txDate(), mapped.amount(), share, mapped.category(),
                mapped.note(), mapped.deleted(), mapped.groupPeople(), tripIds, mapped.orgId(),
                orgName(mapped.orgId()), shown.map(Trip::getTitle).orElse(null),
                shown.map(Trip::getStartDate).orElse(null), shown.map(Trip::getEndDate).orElse(null));
        return staff ? withShare : withShare.withoutGroupPeople();
    }

    /**
     * The trip a row is shown under, by the website statement's rule ({@code LedgerStatements.place}): its first
     * bound trip, when that trip exists and is the row's organization's (a row with no organization takes any).
     * A row bound to another organization's trip is never shown under that trip's title. Read from the DAO, as the
     * statement reads it, so a trip this site would not otherwise serve still names the person's own charge.
     */
    private Optional<Trip> shownTrip(final List<String> tripIds, final String orgId) {
        if (tripIds.isEmpty()) {
            return Optional.empty();
        }
        return trips.computeIfAbsent(tripIds.getFirst(), id -> DAO.getInstance().getTrip(id, Cached.YES)
                        .filter(trip -> id.equals(trip.getId())))
                .filter(trip -> LedgerStatements.showsUnder(trip, orgId));
    }

    /** The organization's name, read once per request per organization; null for a row that has none. */
    private String orgName(final String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return null;
        }
        return orgNames.computeIfAbsent(orgId, LedgerCommands::nameOf);
    }

    /**
     * The trip(s) a transaction belongs to. A transaction carries no trip of its own; the website's ledger
     * scopes rows by their TRANSACTION→TRIP binding, and a client filtering by trip needs the same edge.
     */
    private static List<String> boundTrips(final Transaction tx) {
        if (tx.getUserId() == null || tx.getTxId() == null) {
            return List.of();
        }
        final BindingCommands bind = Beans.get(BindingCommands.class);
        final List<String> bound = bind.getBindings(bind.key(tx.getUserId().getValue(), tx.getTxId()),
                BindingType.TRANSACTION, BindingType.TRIP);
        return bound == null ? List.of() : bound;
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

    /**
     * The group save's half of the books rule: every member passes {@code TransactionsCommands.mayKeepInGroup}
     * (a member being added is on the trip; one already in the group keeps a row on its books), and every row
     * the group already has (a member being amended, or one being dropped and so deleted) is on this trip's
     * books. A group id is all it takes to name somebody else's shared transaction.
     */
    private boolean groupIsOnTheBooks(final GroupTransactionRequest body, final String tripId) {
        final List<Person.Id> members = ids(body.people());
        if (!members.stream().allMatch(member -> TransactionsCommands.mayKeepInGroup(gate(), tripId, member,
                body.groupId()))) {
            return false;
        }
        if (body.groupId() == null || body.groupId().isBlank()) {
            return true;
        }
        // mayKeepInGroup has already held every kept member's row to mayWriteRow; only the rows of members
        // being dropped are left. Reading the kept members again cost each of them one more uncached query.
        final TransactionsCommands transactions = Beans.get(TransactionsCommands.class);
        return ids(body.originalPeople()).stream()
                .filter(member -> !members.contains(member))
                .distinct()
                .map(member -> transactions.getGroupTransactionForUser(member, body.groupId()))
                .flatMap(Optional::stream)
                .allMatch(row -> gate().mayWriteRow(tripId, row));
    }

    /** The ledger gate for this request's caller; one per request, like {@link #privileges()}. */
    private TripFinanceGate gate() {
        if (cachedGate == null) {
            cachedGate = TripFinanceGate.forCaller(this::caller);
        }
        return cachedGate;
    }
}
