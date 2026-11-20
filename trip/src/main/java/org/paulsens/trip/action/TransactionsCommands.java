package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.api.Beans;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Transaction.Type;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.site.ListingScope;

@Slf4j
@Named("txCmds")
@ApplicationScoped
public class TransactionsCommands {
    @Inject
    private BindingCommands bind;

    @Inject
    private AuditCommands audit;

    /** For {@link #getTransactionsInPeopleReach}: whose people data the viewer may see. */
    private final Supplier<OrgCommands> orgSource;

    public TransactionsCommands() {
        this(() -> Beans.get(OrgCommands.class));
    }

    /** Test seam (the {@code TripCommands} pattern): {@code Beans.get} needs a CDI container. */
    TransactionsCommands(final Supplier<OrgCommands> orgSource) {
        this.orgSource = orgSource;
    }

    /**
     * What a trip menu's blank row submits ({@code itemValue="none"}): no trip chosen, never a trip id. THE one
     * definition, with {@link #isNoTrip}: the trip pickers ({@code TripCommands}) ask it too.
     */
    public static final String NO_TRIP = "none";

    /**
     * The refusal for a row with no trip: a NEW one, or an edit of a stored one that is on no trip. Every row is
     * recorded on a trip, which is what gives it its org.
     */
    public static final String NEEDS_TRIP = "Choose the trip this transaction is for. Every transaction is recorded "
            + "on a trip, which is what ties it to that trip's organization.";
    /** The refusal for a trip that cannot carry a ledger row. */
    public static final String NOT_A_LEDGER_TRIP = "That trip does not exist or has no organization, so a "
            + "transaction cannot be recorded on it.";
    /** The refusal for re-binding a stored row to a trip of another organization. */
    public static final String OTHER_ORG_TRIP = "This transaction belongs to a different organization than that "
            + "trip. Delete it and record it on the other trip instead.";

    public Transaction createTransaction(final Person.Id userId) {
        return new Transaction(userId, null, null);
    }

    // ------------------------------------------------------------------ the write invariants

    /** No trip at all: null, blank, or a trip menu's {@link #NO_TRIP} row. */
    public static boolean isNoTrip(final String tripId) {
        return tripId == null || tripId.isBlank() || NO_TRIP.equalsIgnoreCase(tripId.trim());
    }

    /**
     * The editors' Save, asked first: true (and the page is told why) when no trip is chosen, in which case the
     * page saves nothing. Refused here for EVERY row and group, a stored one included: the save methods let a
     * stored row keep "the trip it is on", which for one on no trip is none. One rule and one wording for the
     * transaction page and the group editor, which each used to spell both out. The wording is the saves' own
     * ({@link #NEEDS_TRIP}), so the page guard and the save it stands in front of can never say it two ways.
     */
    public boolean refuseWithoutTrip(final String tripId) {
        if (!isNoTrip(tripId)) {
            return false;
        }
        PageFeedback.refuse("Not saved", NEEDS_TRIP);
        return true;
    }

    /**
     * The trip a ledger row may be recorded on, or null: it must exist and belong to an organization, because a
     * row's organization IS its trip's. Read fresh: the answer stamps the tenancy of a money row.
     */
    public static Trip ledgerTrip(final String tripId) {
        if (isNoTrip(tripId)) {
            return null;
        }
        final Trip trip = DAO.getInstance().getTrip(tripId, Cached.NO).orElse(null);
        return trip == null || isNullOrBlank(trip.getOrgId()) ? null : trip;
    }

    /**
     * Why a row may not be saved against {@code tripId}, or null when it may. THE write invariant of the per-org
     * ledger, asked by every door that takes a trip from a person (the transaction page, the group editor, the
     * REST resource): a NEW row ({@code storedOrNull} null) needs a real trip that has an organization; a stored
     * row saved with no trip chosen keeps the trip it is on, so one that is on NO trip (a row from before the
     * rule) cannot be edited until its trip is named; and a stored row is never moved to a trip of another
     * organization (its org stamp would then disagree with its trip). Not enforced in the DAO: the imports and
     * the payment recorder write through it with a trip already in hand, and the re-saves that are not edits
     * (a delete, a group's membership restamp) must keep working on the rows that still await their trip.
     * {@code chosen} is {@link #ledgerTrip} of {@code tripId}, already read by a caller that goes on to save the
     * row on it ({@link #saveOnTrip}) without reading it again.
     */
    public static String tripProblem(final Transaction storedOrNull, final String tripId, final Trip chosen) {
        if (isNoTrip(tripId)) {
            return boundTripOf(storedOrNull) == null ? NEEDS_TRIP : null;
        }
        return chosenTripProblem(storedOrNull, chosen);
    }

    /**
     * {@link #tripProblem} for a trip that WAS chosen and is already read ({@link #ledgerTrip}: null when it does
     * not exist or has no organization), so a caller that goes on to stamp the row from it does not read it
     * twice.
     */
    static String chosenTripProblem(final Transaction storedOrNull, final Trip trip) {
        if (trip == null) {
            return NOT_A_LEDGER_TRIP;
        }
        return storedOrNull != null && isOtherOrgs(storedOrNull, trip) ? OTHER_ORG_TRIP : null;
    }

    /**
     * What a group save's trip question came to: why the group may not be saved, or else the trip its rows go
     * on. {@code trip} is the chosen trip, or with none chosen the trip the group is already on when a member
     * is being added (null when nobody is: the stored rows keep the trip they have and nothing reads it). So a
     * new group, and a stored group on no trip, must name its trip. Shrinking or deleting a trip-less group is
     * not a save of it: {@link #removeFromGroup} and the delete paths never come through here.
     */
    record GroupTrip(String problem, Trip trip) {
    }

    /**
     * {@link #tripProblem} for every row of a group at once. The trip is read ONCE through {@code tripReader}
     * and each stored row's organization compared in memory: asking {@link #tripProblem} per member made one
     * fresh read of the same trip for each of them, so a 50-member Batch cost 50 reads to judge and 50 more to
     * write. The answer's trip is what {@link #persistTx} then stamps and binds from, with no further read.
     */
    static GroupTrip groupTrip(final String tripId, final List<Optional<Transaction>> rows,
            final Function<String, Trip> tripReader) {
        final List<Transaction> stored = rows.stream().flatMap(Optional::stream).toList();
        final boolean adding = stored.size() < rows.size();
        if (isNoTrip(tripId)) {
            final String onTrip = tripOfGroup(stored);
            if (onTrip == null) {
                return new GroupTrip(NEEDS_TRIP, null);
            }
            final Trip joined = adding ? tripReader.apply(onTrip) : null;
            return new GroupTrip(adding && joined == null ? NOT_A_LEDGER_TRIP : null, joined);
        }
        if (rows.isEmpty()) {
            return new GroupTrip(null, null);
        }
        final Trip trip = tripReader.apply(tripId);
        if (trip == null) {
            return new GroupTrip(NOT_A_LEDGER_TRIP, null);
        }
        final boolean foreign = stored.stream().anyMatch(row -> isOtherOrgs(row, trip));
        return new GroupTrip(foreign ? OTHER_ORG_TRIP : null, trip);
    }

    /** Whether a stored row carries another organization than the trip's (one with none stamped does not). */
    private static boolean isOtherOrgs(final Transaction stored, final Trip trip) {
        return !isNullOrBlank(stored.getOrgId()) && !stored.getOrgId().equals(trip.getOrgId());
    }

    /** The trip a group's stored rows are on: the first row's that has one (a group lives on ONE trip), or null. */
    private static String tripOfGroup(final List<Transaction> stored) {
        return stored.stream().map(TransactionsCommands::boundTripOf).filter(trip -> trip != null)
                .findFirst().orElse(null);
    }

    /** The trip this row's TRANSACTION to TRIP binding names, or null. Read fresh: it decides a money write. */
    static String boundTripOf(final Transaction tx) {
        if (tx == null || tx.getUserId() == null || tx.getTxId() == null) {
            return null;
        }
        return DAO.getInstance().getBindings(LedgerStatements.keyOf(tx), BindingType.TRANSACTION, BindingType.TRIP,
                Cached.NO).stream().findFirst().orElse(null);
    }

    /**
     * THE organization stamp of a ledger row, for every door that writes one from a trip already read (the
     * transaction page, the group editor, the REST resource): a row that carries an organization keeps it
     * (re-saving an old row must not re-tenant it), and one with none, null or blank, takes its trip's. With no
     * trip, or a trip with no organization, nothing is stamped.
     */
    static void stampOrg(final Transaction tx, final Trip trip) {
        if (isNullOrBlank(tx.getOrgId()) && trip != null && !isNullOrBlank(trip.getOrgId())) {
            tx.setOrgId(trip.getOrgId());
        }
    }

    /**
     * {@link #saveTransaction(Transaction)} stamped by {@link #stampOrg} from {@code tripId}'s trip (read cached),
     * for the writers that hold only the trip's id: the payment recorder, the bills, the expense report.
     */
    public boolean saveTransaction(final Transaction tx, final String tripId) {
        // Read the trip only when it can stamp something: the payment recorder stamps its rows first.
        if (isNullOrBlank(tx.getOrgId()) && !isNoTrip(tripId)) {
            stampOrg(tx, DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null));
        }
        return saveTransaction(tx);
    }

    /**
     * The save of one row through a door that holds its trip (the REST resource's create and update, the
     * transaction page): stamped from {@code trip} ({@link #stampOrg}), saved, and, when {@code bind}, put on that
     * trip's books in both directions (a no-op for a row already there). {@code trip} is the one the caller already
     * read and judged: the trip the request names for a row on no trip, or the trip the row is already on, whatever
     * the request names (a binding is only ever given, never moved, so neither is the organization the row takes).
     */
    public boolean saveOnTrip(final Transaction tx, final Trip trip, final boolean bind) {
        stampOrg(tx, trip);
        if (!saveTransaction(tx)) {
            return false;
        }
        if (bind && trip != null) {
            bindToTrip(tx, trip.getId());
        }
        return true;
    }

    public boolean saveTransaction(final Transaction tx) {
        // FIXME: Maybe move sending email to here? Be careful on batch Tx to not spam yourself!
        // FIXME: Look where this is called from
        // FIXME: Add Validations
        boolean result;
        try {
            result = DAO.getInstance().saveTransaction(tx);
        } catch (final RuntimeException ex) {
            PageFeedback.failure("Unable to save transaction for userId: " + tx.getUserId().getValue());
            log.error("Error while saving transaction: ", ex);
            result = false;
        } catch (final IOException ex) {
            PageFeedback.failure("Unable to save transaction for userId: "
                    + tx.getUserId().getValue());
            log.error("Error while saving transaction: ", ex);
            result = false;
        }
        return result;
    }

    /**
     * This person's ledger rows AS LISTED ON THE SITE this request is for: only the rows whose organization
     * the site reaches ({@link #reachesHere}) -- an org host lists that org's rows, a shared host the rows of
     * the orgs it lists plus org-less legacy rows. Every listing, total and balance on this bean derives from
     * this read, so a balance shown on a site is the balance of the rows that site lists (documented in
     * {@code docs/payments.md}). The ledger itself is untouched: writes and the group-transaction helpers
     * read the store directly.
     */
    public List<Transaction> getTransactions(final Person.Id userId) {
        if (userId == null) {
            return List.of();
        }
        try {
            return DAO.getInstance().getTransactions(userId, Cached.NO).stream()
                    .filter(TransactionsCommands::reachesHere)
                    .toList();
        } catch (final RuntimeException ex) {
            log.error("Error querying transactions for user {}: ", userId.getValue(), ex);
            return Collections.emptyList();
        }
    }

    /**
     * {@link #getTransactions} narrowed by {@link PeopleReach}: a site admin gets every row the site lists; anyone
     * else only the rows of an organization they manage or hold {@code peopleAdmin} in, and never a row with no
     * organization (legacy content). For the admin person page's transaction count and balance, whose gate is per
     * subject: an org's people admin reaches a member who may also travel with another organization, whose ledger
     * rows are not theirs to read (owner decision 2026-11-14).
     */
    public List<Transaction> getTransactionsInPeopleReach(final Person.Id userId) {
        final PeopleReach reach = PeopleReach.of(orgSource.get());
        return reach.isNone() ? List.of() : reach.filter(getTransactions(userId), Transaction::getOrgId);
    }

    /**
     * Whether the site this request is for lists this row: {@code ListingScope.reaches} on the row's
     * {@code orgId} (stamped from the trip on every write; the migration backfilled legacy rows, and a row
     * still carrying none is org-less legacy content, which the shared sites keep listing).
     */
    static boolean reachesHere(final Transaction tx) {
        return ListingScope.reachable(tx.getOrgId());
    }

    public List<Transaction> getTripTransactions(final String tripId) {
        try {
            return DAO.getInstance().getTrip(tripId, Cached.NO)
                    .map(Trip::getPeople)
                    .orElse(List.of())
                    .stream()
                    .flatMap(id -> getTransactions(id).stream())
                    .toList();
        } catch (final RuntimeException ex) {
            // Same contract as every other read here: a store failure logs and renders empty rather
            // than blank-paging the finance view. This was the one read that propagated raw.
            log.error("Error querying transactions for trip {}: ", tripId, ex);
            return Collections.emptyList();
        }
    }

    public Transaction getTransaction(final Person.Id userId, final String txId) {
        if (userId == null) {
            throw new IllegalArgumentException("You must provide the userId!");
        }
        if (isNullOrEmpty(txId)) {
            // Create a new Tx
            return createTransaction(userId);
        }
        try {
            // A row the site does not list reads as absent (null), like a transaction that does not exist.
            return DAO.getInstance().getTransaction(userId, txId, Cached.NO)
                    .filter(TransactionsCommands::reachesHere)
                    .orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Error while getting Tx ({}) for userId: '{}'!", txId, userId, ex);
            return null;
        }
    }

    public boolean hasTransaction(final Person.Id userId, final String txId) {
        if (userId == null || txId == null) {
            throw new IllegalArgumentException("You must provide the userId and a txId!");
        }
        try {
            return DAO.getInstance().getTransaction(userId, txId, Cached.NO).isPresent();
        } catch (final RuntimeException ex) {
            return false;
        }
    }

    public Transaction getBoundTransaction(final String id, final String bindingType) {
        final BindingCommands bind = getBind();
        // Note: Tx's are keyed by people as part of the primary key, so we need both (i.e. "userId:txId")
        return bind.getBoundThing(id, bindingType, BindingType.TRANSACTION,
                comboKey -> bind.compositeKeyGetter(comboKey, (k1, k2) -> getTransaction(Person.Id.from(k1), k2)));
    }

    /**
     * The MONEY pages' gate, for a page: {@code #{txCmds.seesMoney(tripId)}} is {@link TripFinanceGate#seesMoney}
     * for the signed-in user (a site admin, {@code tripFinView@trip}, or {@code viewFinances} in the organization
     * that owns the trip). The pages used to spell this out with {@code priv.check}, and their copy asked for
     * {@code viewFinances} as a GLOBAL row, which opened every organization's trips to one holder. Answers false
     * for an anonymous visitor before reading anything, so the trip tab strip may ask on a public page. One
     * arity only: EL cannot choose between same-arity overloads.
     */
    public boolean seesMoney(final String tripId) {
        return TripFinanceGate.forCaller(Caller::current).seesMoney(tripId);
    }

    /** {@link TripFinanceGate#opensTripLedger} for the signed-in user, for a page. One arity only, as above. */
    public boolean opensTripLedger(final String tripId) {
        return TripFinanceGate.forCaller(Caller::current).opensTripLedger(tripId);
    }

    /** {@link TripFinanceGate#onTripLedger}, for the Balance page's {@code ?trip=} admission. */
    public boolean onTripLedger(final Trip trip, final Person.Id person) {
        return TripFinanceGate.onTripLedger(trip, person);
    }

    /**
     * Saves a Shared/Batch group of transactions. {@code origPeople} is the membership as it was when the page
     * loaded (from {@link #getUserIdsForGroup}); members removed from the new selection get their row deleted.
     * Every surviving row is stamped with the full member list so membership never requires probing other users.
     */
    public boolean saveGroupTx(final String gid, final List<Person.Id> origPeople, final Type type,
                final Transaction.TransactionType txType, final LocalDateTime date, final Float amount,
                final String cat, final String note, final String tripId, final String eventId,
                final Object... objArr) {
        return saveGroupTxAs(TripFinanceGate.forCaller(Caller::current), gid, origPeople, type, txType, date,
                amount, cat, note, tripId, eventId, objArr);
    }

    /**
     * {@link #saveGroupTx} as this gate's caller (the seam: {@code Caller.current()} needs a session). The page's
     * picker is a convenience, not the rule: every member must pass {@link #mayKeepInGroup} (one being added is
     * someone this caller may charge on the trip; one already in the group keeps a row that is on its books),
     * exactly what {@code POST /api/transactions/groups} requires, so the page and the API cannot disagree
     * about whose ledger a trip's Finance Admin may write to.
     */
    boolean saveGroupTxAs(final TripFinanceGate gate, final String gid, final List<Person.Id> origPeople,
                final Type type, final Transaction.TransactionType txType, final LocalDateTime date,
                final Float amount, final String cat, final String note, final String tripId, final String eventId,
                final Object... objArr) {
        final List<Person.Id> txPeople = (objArr == null) ? Collections.emptyList()
                : Arrays.stream(objArr).flatMap(this::castToPersonId).toList();
        final List<String> refused = txPeople.stream()
                .filter(member -> !mayKeepInGroup(gate, tripId, member, gid))
                .map(TransactionsCommands::nameOf)
                .toList();
        if (!refused.isEmpty()) {
            return PageFeedback.refuse("Not saved", "Everyone on a batch or shared transaction must be on this "
                    + "trip. Not on it: " + String.join(", ", refused) + ".");
        }
        return saveGroupTransaction(gid, origPeople, type, txType, date, amount, cat, note, tripId, eventId,
                txPeople);
    }

    /**
     * Whether this member may be on the group as saved: someone who already has a row in it keeps it while that
     * row is on this trip's books ({@code mayWriteRow}), even after leaving the trip (an edit of a deposit must
     * not force the admin to delete a withdrawn traveler's row); anyone being ADDED must be someone this caller
     * may charge on the trip ({@code mayCharge}). The REST door asks the same ({@code TransactionsResource}).
     */
    public static boolean mayKeepInGroup(final TripFinanceGate gate, final String tripId, final Person.Id member,
            final String groupId) {
        final Transaction existing = isNullOrEmpty(groupId) || member == null ? null
                : groupRowOf(member, groupId).orElse(null);
        return existing == null ? gate.mayCharge(tripId, member) : gate.mayWriteRow(tripId, existing);
    }

    private static String nameOf(final Person.Id id) {
        return DAO.getInstance().getPerson(id, Cached.YES)
                .map(person -> person.getPreferredName() + " " + person.getLast())
                .orElse(id.getValue());
    }

    /**
     * The same group transaction save, with the member list already typed.
     *
     * <p>The {@code Object...} form above exists because the batch-transaction page submits its selections
     * through PrimeFaces widgets that hand back a mixture of arrays, collections and bare Strings depending on
     * the control -- {@code castToPersonId} untangles that. A caller that already holds
     * {@code List<Person.Id>}, such as a REST resource deserializing a JSON array, has nothing to untangle, and
     * routing it through the varargs form would mean handing a typed list to a method whose whole job is to
     * guess at untyped input.
     *
     * <p>Deliberately a different NAME rather than an overload: {@code List} is more specific than
     * {@code Object...}, so an overload would silently capture any existing call site that happens to pass a
     * single list, changing which coercion runs without changing the call.
     */
    public boolean saveGroupTransaction(final String gid, final List<Person.Id> origPeople, final Type type,
                final Transaction.TransactionType txType, final LocalDateTime date, final Float amount,
                final String cat, final String note, final String tripId, final String eventId,
                final List<Person.Id> members) {
        final GroupSave save = saveGroup(gid, origPeople, type, txType, date, amount, cat, note, tripId, eventId,
                members);
        return save.problem() == null ? save.saved() : PageFeedback.refuse("Not saved", save.problem());
    }

    /** How a group save ended: written or not, and the trip rule it broke when that is why not (else null). */
    public record GroupSave(boolean saved, String problem) {
    }

    /**
     * {@link #saveGroupTransaction} answering WHY a refused save was refused instead of growling it: the REST
     * resource turns the trip rule's refusal into a 400. One judgment of the group
     * ({@link #groupTrip}), made here and nowhere else: a separate "would this save be refused" question judged
     * every member's row and the trip a second time, and could drift from what the save then did.
     */
    public GroupSave saveGroup(final String gid, final List<Person.Id> origPeople, final Type type,
                final Transaction.TransactionType txType, final LocalDateTime date, final Float amount,
                final String cat, final String note, final String tripId, final String eventId,
                final List<Person.Id> members) {
        final List<Person.Id> txPeople = (members == null) ? Collections.emptyList() : members;
        final String groupId = isNullOrEmpty(gid) ? UUID.randomUUID().toString() : gid;
        final List<Person.Id> existing = (origPeople == null) ? List.of() : origPeople;
        // The actor is captured ONCE, on the request thread, and handed to every record this save writes:
        // AuditActor.current() reads a FacesContext ThreadLocal, so resolving it per row is at best repeated
        // work and at worst (a future off-thread caller) no attribution at all.
        final AuditActor actor = AuditActor.current();
        if (txPeople.isEmpty()) {
            // 0 members is no transaction. Legitimate for a Batch (a set of identical charges, and an empty
            // set is a valid one); for a Shared amount it undoes the whole thing, which is why the pages
            // confirm first (needsGroupDeleteConfirm).
            return new GroupSave(deleteGroupById(groupId, existing, actor), null);
        }
        // Every member's row is found BEFORE anything is written, so the trip rule is judged on the group as
        // it stands and a refusal leaves it untouched (a refusal after the removals below would have deleted
        // rows for a save that then did not happen).
        final List<Optional<Transaction>> rows = txPeople.stream()
                .map(uid -> getGroupTransactionForUser(uid, groupId))
                .toList();
        final GroupTrip judged = groupTrip(tripId, rows, TransactionsCommands::ledgerTrip);
        if (judged.problem() != null) {
            return new GroupSave(false, judged.problem());
        }
        boolean result = true;

        // Members dropped from the selection lose their row -- through deleteRow, so the bindings go with it
        // and the removal is audited, exactly as a removal from the transaction page is.
        for (final Person.Id uid : existing) {
            if (txPeople.contains(uid)) {
                continue;
            }
            final Transaction gone = getGroupTransactionForUser(uid, groupId).orElse(null);
            if ((gone != null) && !deleteRow(gone, actor)) {
                log.error("Unable to delete group tx ({}) with note: {}", groupId, note);
                result = false;
            }
        }

        // Find existing, update or create their tx (our "existing" variable doesn't contain deleted, search 1 by 1)
        for (int idx = 0; idx < txPeople.size(); idx++) {
            final Person.Id uid = txPeople.get(idx);
            final Transaction stored = rows.get(idx).orElse(null);
            final Transaction tx = updateTx(stored != null ? stored : new Transaction(uid, groupId, type), date,
                    amount, txType, cat, note);
            tx.setGroupPeople(txPeople);
            // A stored row is re-bound only to a trip that was actually chosen; a new one is bound either way.
            // (With none chosen, a member being added joins the trip the group is on: judged.trip() either way.)
            final Trip bindTrip = stored == null || !isNoTrip(tripId) ? judged.trip() : null;
            result = persistTx(tx, stored == null, bindTrip, eventId) && result;
        }

        return new GroupSave(result, null);
    }

    /**
     * Deletes a transaction the way its TYPE requires: a plain row outright, a Shared/Batch row through
     * {@link #removeFromGroup}.
     *
     * <p>The ONE front door for every delete affordance. Each of them used to dispatch for itself, and the
     * Delete button on {@code transaction.xhtml} got it wrong -- it deleted a group row as though it were a
     * plain one, which is the bug the group methods below exist to close.
     */
    public boolean deleteForPerson(final Transaction tx) {
        return deleteForPerson(tx, AuditActor.current());
    }

    /** @see #deleteForPerson(Transaction) */
    public boolean deleteForPerson(final Transaction tx, final AuditActor who) {
        if (tx == null) {
            return false;
        }
        return (tx.isBatch() || tx.isShared()) ? removeFromGroup(tx, who) : deleteTransaction(tx, who);
    }

    /**
     * Whether the group editor must CONFIRM before it saves this selection, rather than saving it.
     *
     * <p>An empty selection on an existing group deletes the transaction. Only the page can ask the question,
     * but the rule for when to ask belongs here, so this publishes the {@code confirmGroupDelete} ajax
     * callback param (the {@code showRoleWarning} pattern) and answers true; the page's {@code oncomplete}
     * opens its confirmation, whose own button calls {@link #deleteGroupById}.
     */
    public boolean needsGroupDeleteConfirm(final String groupId, final Collection<?> selection) {
        if (isNullOrEmpty(groupId) || ((selection != null) && !selection.isEmpty())) {
            return false;
        }
        PageFeedback.callbackParam("confirmGroupDelete", true);
        return true;
    }

    /**
     * Removes ONE person from a Shared/Batch group, or deletes the whole transaction when they were its last
     * member.
     *
     * <p>What makes this more than a row delete: a group's membership is stamped on every row
     * ({@code groupPeople}) and IS the divisor {@link #getUserAmount} splits a Shared amount by. Deleting one
     * row and leaving the survivors alone left that divisor too high, so a $150 payment split three ways kept
     * reporting $50 shares after a member was removed -- $50 of it simply stopped appearing in any balance,
     * total or report. The stale list also made the group editor offer the removed person again, and saving
     * there recreated their row under a NEW txId, undoing the delete.
     *
     * <p>Removing the last member leaves no transaction at all: a Batch is a set of identical charges and an
     * empty set is legitimate, while a Shared amount has nobody left to divide between. Both delete the group.
     * A Shared group's last removal undoes a payment that may really have happened, so the pages WARN before
     * calling this; by the time it runs, that decision is made.
     */
    public boolean removeFromGroup(final Transaction tx) {
        return removeFromGroup(tx, AuditActor.current());
    }

    /** @see #removeFromGroup(Transaction) */
    public boolean removeFromGroup(final Transaction tx, final AuditActor who) {
        if (tx == null) {
            return false;
        }
        if (!tx.isBatch() && !tx.isShared()) {
            log.error("Refusing to treat plain tx ({}) as a group row; use deleteForPerson.", tx.getTxId());
            return false;
        }
        final List<Person.Id> remaining = new ArrayList<>(getUserIdsForGroup(tx));
        remaining.remove(tx.getUserId());
        if (remaining.isEmpty()) {
            // Includes the legacy no-membership row: getUserIdsForGroup answers "just this user" (loudly) for
            // one, and without a table scan that is the only membership there is to act on.
            return deleteGroup(tx, who);
        }
        return deleteRow(tx, who) && restampMembership(tx.getGroupId(), remaining);
    }

    /**
     * Deletes an entire Shared/Batch group: every live member row (bindings and per-row record included) plus
     * one record naming the transaction itself as gone.
     */
    public boolean deleteGroup(final Transaction tx) {
        return deleteGroup(tx, AuditActor.current());
    }

    /** @see #deleteGroup(Transaction) */
    public boolean deleteGroup(final Transaction tx, final AuditActor who) {
        if (tx == null) {
            return false;
        }
        return deleteWholeGroup(tx.getGroupId(), getUserIdsForGroup(tx), tx, who);
    }

    /**
     * Deletes a plain (non-group) transaction: soft-delete, drop its bindings, audit. A group row must go
     * through {@link #removeFromGroup} instead, which is why this refuses one rather than quietly corrupting
     * the group's divisor.
     */
    public boolean deleteTransaction(final Transaction tx) {
        return deleteTransaction(tx, AuditActor.current());
    }

    /** @see #deleteTransaction(Transaction) */
    public boolean deleteTransaction(final Transaction tx, final AuditActor who) {
        if (tx == null) {
            return false;
        }
        if (tx.isBatch() || tx.isShared()) {
            log.error("Refusing to delete group tx ({}) as a plain row; use removeFromGroup.", tx.getGroupId());
            return false;
        }
        return deleteRow(tx, who);
    }

    /**
     * Deletes an entire group named by ID, seeded with the membership a page happens to know.
     *
     * <p>The group editor holds the group's id and its member list as scalars, never a {@link Transaction}
     * (the page-state rule: no domain object in the view), so it has nothing to hand {@link
     * #deleteGroup(Transaction)}. The seeds are only used to FIND a live row; the membership acted on is the
     * one stamped on that row, which is the authority and may have changed since the editor opened.
     *
     * <p>Returns true when there was nothing left to delete: an already-empty group is the requested state.
     *
     * @param groupId       the group to delete.
     * @param knownMembers  member ids to look for a live row among; a superset or subset is fine.
     */
    public boolean deleteGroupById(final String groupId, final List<Person.Id> knownMembers) {
        return deleteGroupById(groupId, knownMembers, AuditActor.current());
    }

    /** @see #deleteGroupById(String, List) */
    public boolean deleteGroupById(final String groupId, final List<Person.Id> knownMembers,
            final AuditActor who) {
        if (isNullOrEmpty(groupId)) {
            return false;
        }
        final Transaction describing = ((knownMembers == null) ? List.<Person.Id>of() : knownMembers).stream()
                .map(uid -> getGroupTransactionForUser(uid, groupId).orElse(null))
                .filter(row -> row != null)
                .findFirst()
                .orElse(null);
        return (describing == null)
                || deleteWholeGroup(groupId, getUserIdsForGroup(describing), describing, who);
    }

    /** Every live row of a group, then the one record saying the transaction itself is gone. */
    private boolean deleteWholeGroup(final String groupId, final Collection<Person.Id> members,
            final Transaction describing, final AuditActor who) {
        boolean result = true;
        for (final Person.Id uid : members) {
            final Transaction row = getGroupTransactionForUser(uid, groupId).orElse(null);
            if ((row != null) && !deleteRow(row, who)) {
                result = false;
            }
        }
        getAudit().groupTransactionDeleted(describing, members.size(), who);
        return result;
    }

    /**
     * Rewrites the stamped membership on every live row of a group. Membership is the Shared divisor, so a
     * removal that skipped this shrank the group's accounted total without touching a single amount.
     */
    private boolean restampMembership(final String groupId, final List<Person.Id> members) {
        final List<Person.Id> stamped = List.copyOf(members);
        boolean result = true;
        for (final Person.Id uid : stamped) {
            final Transaction row = getGroupTransactionForUser(uid, groupId).orElse(null);
            if (row == null) {
                continue;
            }
            row.setGroupPeople(stamped);
            if (!saveTransaction(row)) {
                log.error("Unable to restamp membership on group tx ({}) for user {}", groupId, uid.getValue());
                result = false;
            }
        }
        return result;
    }

    /**
     * Soft-deletes one row, drops its trip/event bindings, and audits it.
     *
     * <p>The bindings are the reason this is not two lines at each call site. They outlive the row otherwise:
     * the trip-side ledger walks every TRIP-&gt;TRANSACTION edge and null-checks the misses, so orphans are
     * invisible and accumulate for the life of the trip.
     */
    private boolean deleteRow(final Transaction tx, final AuditActor who) {
        final String bindKey = getBind().key(tx.getUserId().getValue(), tx.getTxId());
        tx.delete();
        if (!saveTransaction(tx)) {
            log.error("Unable to delete tx ({}) with note: {}", tx.getTxId(), tx.getNote());
            return false;
        }
        getBind().setBindings(bindKey, BindingType.TRANSACTION, BindingType.TRIP, List.of(), true);
        getBind().setBindings(bindKey, BindingType.TRANSACTION, BindingType.TRIP_EVENT, List.of(), true);
        getAudit().transactionDeleted(
                DAO.getInstance().getPerson(tx.getUserId(), Cached.YES).orElse(null), tx, who);
        return true;
    }

    public void sortTxByDate(final List<Transaction> txs) {
        txs.sort(Comparator.comparing(Transaction::getTxDate));
    }

    /**
     * {@link #getTransactions} in a DETERMINISTIC date order (mutable copy), for a per-request table whose
     * rows carry commands: the store's order is unspecified, and the ledger table's initial date sort must
     * be a stable no-op so a postback's row decode sees exactly the order the render produced.
     */
    public List<Transaction> getTransactionsSorted(final Person.Id userId) {
        final List<Transaction> txs = new ArrayList<>(getTransactions(userId));
        sortTxByDate(txs);
        return txs;
    }

    /**
     * The rows of one person's ledger that are on ONE trip's books ({@link TripFinanceGate#onBooks}), date
     * sorted: what the trip's finance staff are shown. The ledger page used to load the person's whole
     * ledger for them and merely HIDE the other trips' rows with a style class, so every row was in the page
     * source for anyone holding a finance role on one trip the person was on.
     */
    public List<Transaction> getTransactionsOnBooks(final Person.Id userId, final String tripId) {
        return getTransactionsOnBooks(TripFinanceGate.forCaller(Caller::current), userId, tripId);
    }

    /**
     * {@link #getTransactionsOnBooks(Person.Id, String)} as this gate's caller. The trip is asked about HERE as
     * well as on the page: the page once passed whatever its trip filter menu held, so a finance viewer of one
     * trip chose another trip's books from the menu. No finance role on the trip, no rows.
     */
    List<Transaction> getTransactionsOnBooks(final TripFinanceGate gate, final Person.Id userId,
            final String tripId) {
        if (userId == null || !gate.canReadLedgers(tripId)) {
            return new ArrayList<>();
        }
        return getTransactionsSorted(userId).stream()
                .filter(tx -> TripFinanceGate.onBooks(tripId, tx, Cached.YES))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * The transaction page's Save: {@link #saveOnTrip} once the signed-in user may put
     * this row on this trip's books. A site admin may always. A trip's finance admin may record a NEW row only
     * for someone on that trip, and may change an existing row only while it stays on the books it is on
     * ({@code TripFinanceGate.mayCharge} / {@code mayWriteRow}, the API's rule: an update never re-binds a row
     * to another trip). The page's gate asked about the trip in the URL and then offered a Trip menu, so a
     * finance admin of trip A saved a payment onto trip B's books.
     */
    public boolean saveFromPage(final Transaction tx, final String tripId) {
        return saveFromPage(TripFinanceGate.forCaller(Caller::current), tx, tripId);
    }

    boolean saveFromPage(final TripFinanceGate gate, final Transaction tx, final String tripId) {
        if (tx == null || tx.getUserId() == null) {
            return refuseOffBooks();
        }
        // A Transaction always carries an id (its constructors mint one), so "new" means nothing stored under it.
        final Transaction stored =
                DAO.getInstance().getTransaction(tx.getUserId(), tx.getTxId(), Cached.NO).orElse(null);
        final boolean noTrip = isNoTrip(tripId);
        // A stored row saved with no trip chosen is judged against, and stays on, the trip it is bound to: the
        // menu's blank row ("none") used to reach the binding write and silently took the row off its trip.
        final String judgedTripId = noTrip ? boundTripOf(stored) : tripId;
        if (stored != null && judgedTripId == null) {
            // A stored row on no trip, saved with none chosen: the page refuses this too, but the rule is
            // Java's. Asked before the books gate so a site admin and a trip's staff get the same clear answer.
            return PageFeedback.refuse("Not saved", NEEDS_TRIP);
        }
        if (!mayPutOnBooks(gate, judgedTripId, tx.getUserId(), stored)) {
            return refuseOffBooks();
        }
        // The trip is read ONCE: the same read judges the save and stamps the row.
        final Trip trip = noTrip ? null : ledgerTrip(tripId);
        final String problem = noTrip ? (stored == null ? NEEDS_TRIP : null) : chosenTripProblem(stored, trip);
        if (problem != null) {
            return PageFeedback.refuse("Not saved", problem);
        }
        // The org is never the page's to choose: a stored row keeps its own, a new one takes its trip's (as does
        // a stored row from before rows carried one, when its trip is named).
        tx.setOrgId(stored == null ? null : stored.getOrgId());
        return saveOnTrip(tx, trip, !noTrip);
    }

    private static boolean refuseOffBooks() {
        return PageFeedback.refuse("Not saved", "You can record transactions only on the books of a trip whose "
                + "finances you administer, for the people on that trip.");
    }

    /** Puts the row on the trip's books (both directions), replacing any other trip it was bound to. */
    private void bindToTrip(final Transaction tx, final String tripId) {
        final BindingCommands binder = getBind();
        binder.setBindings(binder.key(tx.getUserId().getValue(), tx.getTxId()), BindingType.TRANSACTION,
                BindingType.TRIP, List.of(tripId), true);
    }

    /** The ONE books rule for both page gates: a new row needs mayCharge, an existing one mayWriteRow. */
    private static boolean mayPutOnBooks(final TripFinanceGate gate, final String tripId, final Person.Id subject,
            final Transaction storedOrNull) {
        return storedOrNull == null ? gate.mayCharge(tripId, subject) : gate.mayWriteRow(tripId, storedOrNull);
    }

    /**
     * Whether a trip's finance role lets the signed-in user open THIS row (or, with no {@code txId}, record a
     * new one for this person) on the transaction page: the role must be on a trip whose books the row is on,
     * and a new row's person must be on that trip. The page checked only the role on the caller's own
     * {@code ?trip=}, so a finance admin of one small trip could open, zero and delete anybody's row by URL
     * (audit 2026-11-11; the REST half is {@code TransactionsResource}).
     */
    public boolean tripStaffMayOpen(final String tripId, final Person.Id subject, final String txId) {
        return tripStaffMayOpen(TripFinanceGate.forCaller(Caller::current), tripId, subject, txId);
    }

    static boolean tripStaffMayOpen(final TripFinanceGate gate, final String tripId, final Person.Id subject,
            final String txId) {
        if (isNullOrEmpty(tripId) || subject == null) {
            return false;
        }
        if (isNullOrEmpty(txId)) {
            return mayPutOnBooks(gate, tripId, subject, null);
        }
        // Opening a txId that names no row is refused: the page edits stored rows, it does not invent them.
        final Transaction row = DAO.getInstance().getTransaction(subject, txId, Cached.NO).orElse(null);
        return row != null && mayPutOnBooks(gate, tripId, subject, row);
    }

    /**
     * Writes one row of a group save. {@code bindTrip} is the trip to put it on, already read and known to have
     * an organization ({@link #groupTrip}), or null to leave a stored row on the trip it has
     * ({@link #saveGroup} has already refused a new row with no trip); a new row takes that trip's organization.
     */
    private boolean persistTx(final Transaction tx, final boolean isNew, final Trip bindTrip, final String eventId) {
        if ((tx.getGroupId() != null) && ((tx.getGroupPeople() == null) || tx.getGroupPeople().isEmpty())) {
            // An EMPTY membership list is worse than a missing one: getUserIdsForGroup reads it as the legacy
            // "just this user" case, which renders a Shared row's FULL amount as one person's share.
            log.error("Refusing to save group tx ({}) with no members", tx.getGroupId());
            return false;
        }
        if (isNew && bindTrip == null) {
            log.error("Refusing to save new group tx ({}) with no trip that has an organization", tx.getGroupId());
            return false;
        }
        final BindingCommands bind = getBind();
        final String txBindKey = bind.key(tx.getUserId().getValue(), tx.getTxId());
        tx.setDeleted(null); // Ensure not deleted
        stampOrg(tx, bindTrip);
        if (!saveTransaction(tx)) {
            log.error("Unable to save group tx ({}) with note: {}", tx.getGroupId(), tx.getNote());
            return false;
        }
        // Now save any binding(s)
        if (bindTrip != null) {
            bind.setBindings(txBindKey, BindingType.TRANSACTION, BindingType.TRIP, List.of(bindTrip.getId()), true);
            if ((eventId != null) && !eventId.isEmpty()) {
                bind.setBindings(txBindKey, BindingType.TRANSACTION, BindingType.TRIP_EVENT, List.of(eventId),
                        true);
            }
        }
        return true;
    }

    private Stream<Person.Id> castToPersonId(final Object thing) {
        if (thing instanceof Person.Id) {
            return Stream.of((Person.Id) thing);
        }
        if (thing instanceof String) {
            return Stream.of(Person.Id.from(thing.toString()));
        }
        if (thing instanceof Object[]) {
            return Arrays.stream((Object[]) thing).flatMap(this::castToPersonId);
        }
        if (thing instanceof Collection<?>) {
            return ((Collection<?>) thing).stream().flatMap(this::castToPersonId);
        }
        throw new IllegalArgumentException("Can't turn " + thing.getClass().getName() + " into a Person.Id");
    }

    /**
     * Returns this user's LIVE row in the given group, if they have one.
     *
     * <p>It does <em>not</em> return deleted rows, whatever this once claimed: {@code TransactionDAO} filters
     * {@code deleted} out of every partition load and evicts a deleted row from the cache. That is why a
     * removed member who is re-added comes back under a NEW txId rather than having their old row revived --
     * and why a group save can never "find" the row it just deleted.
     *
     * @param userId    The user to search.
     * @param groupId   The groupId to match.
     * @return  Optionally the matching live {@code Transaction}.
     */
    public Optional<Transaction> getGroupTransactionForUser(final Person.Id userId, final String groupId) {
        return groupRowOf(userId, groupId);
    }

    /**
     * The one group-row lookup, static so {@link #mayKeepInGroup} judges the very row a group save then edits.
     * {@link #getGroupTransactionForUser} stays an instance method: tests stub and override it.
     */
    private static Optional<Transaction> groupRowOf(final Person.Id userId, final String groupId) {
        return DAO.getInstance().getTransactions(userId, Cached.NO).stream()
                .filter(tx -> groupId.equals(tx.getGroupId())).findAny();
    }

    /**
     * Returns the people who share in the given {@link Type#Batch} or {@link Type#Shared} transaction, read from
     * the membership list stamped on the row itself. Legacy rows saved before membership stamping fall back to
     * just the row's own user -- run the group-tx membership migration (see
     * {@code docs/migrations/group-tx-membership.md}) to fix those.
     */
    public List<Person.Id> getUserIdsForGroup(final Transaction tx) {
        if (tx == null) {
            return List.of();
        }
        if (tx.getGroupPeople() != null && !tx.getGroupPeople().isEmpty()) {
            return tx.getGroupPeople();
        }
        if (tx.getGroupId() != null) {
            log.warn("Group tx '{}' (group '{}') has no groupPeople -- legacy row, run the group-tx migration.",
                    tx.getTxId(), tx.getGroupId());
        }
        return List.of(tx.getUserId());
    }

    /**
     * This returns the portion of the transaction the user is responsible for. Normally it is all of the amount,
     * however, shared transactions are split between people, so it may not be all for this user.
     * @return The amount the user is responsible for.
     */
    public Float getUserAmount(final Transaction tx) {
        if (tx == null || tx.getAmount() == null) {
            return null;
        }
        if (!tx.isShared()) {
            return tx.getAmount();
        }
        return tx.getAmount() / Math.max(1, getUserIdsForGroup(tx).size());
    }

    /**
     * This person's balance: the sum of their per-user amounts (a shared row contributes this person's SHARE).
     * Negative means they owe. The transactions page's running column folds the same numbers in render order;
     * this exists because a SECOND total (the family line) cannot ride a render-order fold, and totals belong
     * in testable Java either way.
     */
    public double getBalance(final Person.Id userId) {
        double total = 0d;
        for (final Transaction tx : getTransactions(userId)) {
            final Float amount = getUserAmount(tx);
            if (amount != null) {
                total += amount;
            }
        }
        return total;
    }

    /** The whole family's balance: each member's own rows, so a shared row counts once per member's share. */
    public double getFamilyBalance(final Collection<Person.Id> ids) {
        double total = 0d;
        if (ids != null) {
            for (final Person.Id id : ids) {
                total += getBalance(id);
            }
        }
        return total;
    }

    /** Merged, date-sorted rows across the family for the "Entire family" transactions view. */
    public List<Transaction> getFamilyTransactions(final Collection<Person.Id> ids) {
        final List<Transaction> all = new ArrayList<>();
        if (ids != null) {
            for (final Person.Id id : ids) {
                all.addAll(getTransactions(id));
            }
        }
        sortTxByDate(all);
        return all;
    }

    /**
     * The three footers of the trip transactions page ({@link TripLedgerTotals}), folded from the very rows its
     * three tables list ({@code trip/tripTransactions.xhtml} gathers them once per view, from the trip's TRIP to
     * TRANSACTION bindings through the site-filtered {@link #getTransaction}). Handed in rather than read again:
     * a second read of every bound row, uncached and on every postback, doubled the page's reads and let a
     * footer disagree with the table above it once another admin changed a row. Each list is folded into its own
     * footer as given, so a footer is the total of its table by construction. The ledger migration's before
     * and after figures use the same {@link TripLedgerTotals} over every live row (system reach).
     */
    public TripLedgerTotals getTripLedgerTotals(final Collection<Transaction> tripRows,
            final Collection<Transaction> invoiceRows, final Collection<Transaction> userRows) {
        return TripLedgerTotals.ofLists(orNone(tripRows), orNone(invoiceRows), orNone(userRows), this::getUserAmount);
    }

    /**
     * Adds one of a trip's rows to the page list it belongs in, by {@link TripLedgerTotals#bucketOf}: THE bucket
     * rule, so the trip ledger's three tables (and the footers folded from them) and the ledger migration's
     * figures sort rows one way. The page used to spell the rule out in its own script.
     */
    public void fileLedgerRow(final String tripId, final Transaction tx, final Collection<Transaction> tripRows,
            final Collection<Transaction> invoiceRows, final Collection<Transaction> userRows) {
        switch (TripLedgerTotals.bucketOf(tripId, tx)) {
            case TRIP -> tripRows.add(tx);
            case INVOICE -> invoiceRows.add(tx);
            case USER -> userRows.add(tx);
        }
    }

    private static Collection<Transaction> orNone(final Collection<Transaction> rows) {
        return rows == null ? List.of() : rows;
    }

    private Transaction updateTx(final Transaction tx, final LocalDateTime date, final Float amount,
            final Transaction.TransactionType txType, final String cat, final String note) {
        tx.setTxDate(date);
        tx.setAmount(amount);
        tx.setTxType(txType);
        tx.setCategory(cat);
        tx.setNote(note);
        return tx;
    }

    public BindingCommands getBind() {
        if (bind == null) {
            log.warn("Did not get BindingCommands injected!");
            bind = new BindingCommands();
        }
        return bind;
    }

    /** As {@link #getBind()}: unit tests and the odd hand-constructed instance have no container behind them. */
    AuditCommands getAudit() {
        if (audit == null) {
            audit = new AuditCommands();
        }
        return audit;
    }

    private static boolean isNullOrEmpty(final String str) {
        return (str == null) || str.isEmpty();
    }

    private static boolean isNullOrBlank(final String str) {
        return (str == null) || str.isBlank();
    }
}
