package org.paulsens.trip.action;

import java.util.function.Consumer;
import java.util.function.Supplier;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.CompositeKey;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;

import static org.paulsens.trip.action.PageFeedback.refuse;

/**
 * The one spelling of who may read and write a trip's planning money: the budget ({@link BudgetCommands}) and
 * the expense report ({@link ExpenseCommands}) both ask it, so the two pages can never disagree about a
 * person. Edit = site admin, {@code tripMgr@trip} or {@code tripFinAdmin@trip}; view = an editor or whoever
 * {@link #seesMoney}; settle (record payments against an expense report) = site admin or
 * {@code tripFinAdmin@trip} only, so a trip manager, usually the one being paid, cannot mark their own report
 * paid. All answer false for an unknown trip or nobody signed in.
 *
 * <p>TWO read rules, on purpose. {@link #seesMoney} is the MONEY pages' (the trip's ledger, the payment and
 * lodging-cost reports, the balances on the contacts page, the check-in money row): a site admin,
 * {@code tripFinView@trip}, or {@code viewFinances} scoped to the organization that owns the trip.
 * {@link #canView} is the planning money's (budget, expense report, a balance in a mail merge or a reminder):
 * {@code seesMoney} plus the editors. Do not merge them: a trip manager edits the budget and has never read the
 * travelers' payments, and one rule would hand them every money page. {@code viewFinances} was a GLOBAL grant
 * until 2026-11, which let its holder read every tenant's trip finances; a global row now grants nothing.
 *
 * <p>It is also the rule for the travelers' LEDGERS ({@code TransactionsResource}), which needs a second
 * half the planning money never did. A budget is keyed by its trip, so a role on the trip is the whole
 * question; a ledger row is keyed by its PERSON, so the role only reaches the rows on that trip's books
 * ({@link #onBooks}). Checking the role alone let a finance role on one small trip read, zero and delete
 * anybody's rows on every other trip (audit 2026-11-11). Read = {@code tripFinView@trip} or
 * {@code tripFinAdmin@trip}; write = {@code tripFinAdmin@trip} (the settle rule: a trip manager is not a
 * finance admin). A site admin reads and writes every ledger, with or without a trip.
 *
 * <p>One deliberate exception to "only the rows on that trip's books" (owner decision 2026-11-20): Trip Contacts
 * shows a traveler's balance with the trip's organization beside the trip amount to every viewer who passes
 * {@link #seesMoney} on that trip, and to the traveler ({@code LedgerCommands.tripAndOrgBalances}).
 */
public final class TripFinanceGate {
    private final Supplier<Caller> callerSource;
    private final Supplier<TripCommands> tripSource;

    TripFinanceGate(final Supplier<Caller> callerSource, final Supplier<TripCommands> tripSource) {
        this.callerSource = callerSource;
        this.tripSource = tripSource;
    }

    /** The gate for one caller, resolving trips the way {@link BudgetCommands} does (the REST edge's way in). */
    public static TripFinanceGate forCaller(final Supplier<Caller> callerSource) {
        return new TripFinanceGate(callerSource, () -> new TripCommands(() -> new OrgCommands(callerSource)));
    }

    // ------------------------------------------------------------------ travelers' ledgers

    /** May read ledger rows at all: a site admin, or the trip's finance viewers and finance admins. */
    public boolean canReadLedgers(final String tripId) {
        final Caller caller = callerSource.get();
        return caller.isSiteAdmin() || (signedInOnKnownTrip(tripId)
                && (caller.has(PrivilegeCommands.TRIP_FIN_VIEW, tripId)
                        || caller.has(PrivilegeCommands.TRIP_FIN_ADMIN, tripId)));
    }

    /** May write ledger rows at all: a site admin, or the trip's finance admins. */
    public boolean canWriteLedgers(final String tripId) {
        return callerSource.get().isSiteAdmin() || canSettle(tripId);
    }

    /** May read THIS row: {@link #canReadLedgers} and, short of a site admin, the row is on that trip's books. */
    public boolean mayReadRow(final String tripId, final Transaction tx) {
        return canReadLedgers(tripId) && reaches(tripId, tx);
    }

    /**
     * {@link #mayReadRow} for a LIST, whose endpoint has already asked {@link #canReadLedgers} once: only the
     * books half, and from the cache. Per row, the full rule re-resolved the trip and read the row's binding
     * fresh (hundreds of shared-cache round trips for one trip's ledger); a list is a read, and the single-row
     * write gates still read fresh.
     */
    public boolean listsRow(final String tripId, final Transaction tx) {
        return callerSource.get().isSiteAdmin() || onBooks(tripId, tx, Cached.YES);
    }

    /** May change or delete THIS row: {@link #canWriteLedgers} and the same books rule as a read. */
    public boolean mayWriteRow(final String tripId, final Transaction tx) {
        return canWriteLedgers(tripId) && reaches(tripId, tx);
    }

    /**
     * May record a NEW row for {@code subject} against this trip: {@link #canWriteLedgers} and, short of a
     * site admin, the subject is on the trip ({@link TripCommands#isOnTrip}) or IS the trip (the trip's own
     * ledger keys its rows by the trip id, the transaction page's "trip as person").
     */
    public boolean mayCharge(final String tripId, final Person.Id subject) {
        if (subject == null || !canWriteLedgers(tripId)) {
            return false;
        }
        return callerSource.get().isSiteAdmin() || tripId.equals(subject.getValue())
                || TripCommands.isOnTrip(knownTrip(tripId), subject);
    }

    /**
     * May open a traveler's Balance page with this trip named ({@code trip/transactions.jsf?trip=}), as the trip's
     * staff: a site admin, or a finance viewer of THIS trip (an exact {@code tripFinView@trip}, which Finance
     * Admin does not imply). Narrower than {@link #seesMoney}: an organization's finance viewer opens the trip's
     * ledger, not a traveler's page through it. THE rule the pages that link to that page ask (the trip ledger,
     * Trip Contacts) and the page itself, so a link is never shown to a viewer the page then refuses.
     */
    public boolean opensTripLedger(final String tripId) {
        final Caller caller = callerSource.get();
        return caller.isAuthenticated() && (caller.isSiteAdmin()
                || (tripId != null && !tripId.isBlank() && caller.has(PrivilegeCommands.TRIP_FIN_VIEW, tripId)));
    }

    /**
     * Whether {@code person} belongs on the Balance page's {@code ?trip=} form: on the trip's roster, or with a row
     * on its books. A traveler who paid a deposit and then left the roster keeps their rows on the trip's books
     * (the trip ledger lists them), so their name there must still open their page rather than send the viewer
     * home. Read from the cache: this admits a READ, which the page then narrows to this trip's rows.
     */
    public static boolean onTripLedger(final Trip trip, final Person.Id person) {
        if (trip == null || person == null) {
            return false;
        }
        if (TripCommands.isOnTrip(trip, person)) {
            return true;
        }
        return DAO.getInstance().getBindings(trip.getId(), BindingType.TRIP, BindingType.TRANSACTION, Cached.YES)
                .stream().anyMatch(key -> isRowOf(key, person));
    }

    /**
     * Whether a ledger row's binding key names this person, read through {@link CompositeKey} (a TRANSACTION key is
     * always stored composite, {@code BindingDAO}), never by spelling the separator here.
     */
    private static boolean isRowOf(final String key, final Person.Id person) {
        return CompositeKey.from(key).getPartitionKey().equals(person.getValue());
    }

    private boolean reaches(final String tripId, final Transaction tx) {
        return callerSource.get().isSiteAdmin() || onBooks(tripId, tx);
    }

    /**
     * Whether a ledger row is on this trip's books: its TRANSACTION to TRIP binding names the trip (what the
     * website's trip ledger lists by), or the row is the trip's own. A row bound to no trip is on nobody's
     * books, so only its person and a site admin reach it. Read fresh: the answer authorizes a money write.
     */
    static boolean onBooks(final String tripId, final Transaction tx) {
        return onBooks(tripId, tx, Cached.NO);
    }

    /** {@link #onBooks(String, Transaction)} with the caller's choice of freshness: a LIST filter may read cached. */
    static boolean onBooks(final String tripId, final Transaction tx, final Cached cached) {
        if (tripId == null || tx == null || tx.getUserId() == null || tx.getTxId() == null) {
            return false;
        }
        final String owner = tx.getUserId().getValue();
        if (tripId.equals(owner)) {
            return true;
        }
        return DAO.getInstance().getBindings(LedgerStatements.keyOf(tx), BindingType.TRANSACTION, BindingType.TRIP,
                cached).contains(tripId);
    }

    // ------------------------------------------------------------------ planning money

    /** May edit: site admin, the trip's managers or its finance admins. */
    boolean canEdit(final String tripId) {
        if (!signedInOnKnownTrip(tripId)) {
            return false;
        }
        final Caller caller = callerSource.get();
        return caller.has(PrivilegeCommands.TRIP_MGR, tripId) || caller.has(PrivilegeCommands.TRIP_FIN_ADMIN, tripId);
    }

    /** May record payments: site admin or the trip's finance admins, never a trip manager as such. */
    boolean canSettle(final String tripId) {
        return signedInOnKnownTrip(tripId) && callerSource.get().has(PrivilegeCommands.TRIP_FIN_ADMIN, tripId);
    }

    /** May read the planning money: an editor, or whoever {@link #seesMoney}. */
    public boolean canView(final String tripId) {
        return canEdit(tripId) || seesMoney(tripId);
    }

    /**
     * May open the trip's MONEY pages: a site admin, the trip's finance viewers ({@code tripFinView@trip}), or a
     * finance viewer of the organization that owns the trip ({@code viewFinances@org}). Not the editors: see the
     * class comment. False for nobody signed in and for a trip that is unknown or that this site does not serve,
     * whoever asks.
     */
    public boolean seesMoney(final String tripId) {
        return signedInOnKnownTrip(tripId) && seesMoney(callerSource.get(), knownTrip(tripId));
    }

    /**
     * {@link #seesMoney(String)} over a trip the caller has ALREADY resolved on this site (the PDF servlet and the
     * check-in bean hold theirs). The organization is read from the trip, never from the request's host or a
     * parameter, so a grant in one organization cannot be carried to another's trip. A trip with no organization
     * has no org finance viewers. The global row is deliberately not asked: it grants nothing.
     */
    public static boolean seesMoney(final Caller caller, final Trip trip) {
        if (caller == null || trip == null || !caller.isAuthenticated()) {
            return false;
        }
        if (caller.isSiteAdmin() || caller.has(PrivilegeCommands.TRIP_FIN_VIEW, trip.getId())) {
            return true;
        }
        final String orgId = trip.getOrgId();
        return orgId != null && !orgId.isBlank() && caller.has(PrivilegeCommands.VIEW_FINANCES, orgId);
    }

    /** The trip, or null for an unknown one: {@link #knownTrip(TripCommands, String)} through this gate's trips. */
    Trip knownTrip(final String tripId) {
        return knownTrip(tripSource.get(), tripId);
    }

    /**
     * The trip as {@code trips} serves it on this site, or null for none, an unknown one or one out of the site
     * ({@code getTrip} answers a miss with a blank trip and a fresh id). THE one "is this a trip here" test of
     * the money pages: this gate, the Balance page ({@code LedgerCommands}) and the pay page
     * ({@code PayPageCommands}) each had a copy, and the copies disagreed about a blank or padded id, so one
     * {@code ?trip=} was a trip to the pay page and no trip to the ledger. A pasted id's padding is forgiven.
     */
    static Trip knownTrip(final TripCommands trips, final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return null;
        }
        final String id = tripId.trim();
        final Trip trip = trips.getTrip(id);
        return (trip == null || !id.equals(trip.getId())) ? null : trip;
    }

    /**
     * The edit gate, the form, and the trip read for edit; null (after refusing) when any of them fails. A
     * denial growls {@code notAllowed}, since the caller's form may not even be on the page; an unknown trip
     * goes to {@code refusal}.
     */
    Trip editableTrip(final String tripId, final Object form, final Consumer<String> refusal,
            final String notAllowed) {
        return canEdit(tripId) ? tripForEdit(tripId, form, refusal) : notAllowed(notAllowed);
    }

    /** {@link #editableTrip} behind the settle gate instead of the edit gate. */
    Trip settleableTrip(final String tripId, final Object form, final Consumer<String> refusal,
            final String notAllowed) {
        return canSettle(tripId) ? tripForEdit(tripId, form, refusal) : notAllowed(notAllowed);
    }

    private static Trip notAllowed(final String notAllowed) {
        refuse(notAllowed);
        return null;
    }

    private Trip tripForEdit(final String tripId, final Object form, final Consumer<String> refusal) {
        if (form == null) {
            refuse(Text.get("feedback.shared.nothingToSave"));
            return null;
        }
        final Trip trip = tripSource.get().getTripForEdit(tripId);
        if (trip == null || !tripId.equals(trip.getId())) {
            refusal.accept(Text.get("feedback.shared.unknownTrip"));
            return null;
        }
        return trip;
    }

    private boolean signedInOnKnownTrip(final String tripId) {
        return tripId != null && !tripId.isBlank() && callerSource.get().isAuthenticated() && knownTrip(tripId) != null;
    }
}
