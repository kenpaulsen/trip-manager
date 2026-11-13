package org.paulsens.trip.action;

import java.util.function.Consumer;
import java.util.function.Supplier;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.CompositeKey;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;

import static org.paulsens.trip.action.PageFeedback.refuse;

/**
 * The one spelling of who may read and write a trip's planning money: the budget ({@link BudgetCommands}) and
 * the expense report ({@link ExpenseCommands}) both ask it, so the two pages can never disagree about a
 * person. Edit = site admin, {@code tripMgr@trip} or {@code tripFinAdmin@trip}; view = an editor,
 * {@code tripFinView@trip} or the global {@code viewFinances}; settle (record payments against an expense
 * report) = site admin or {@code tripFinAdmin@trip} only, so a trip manager, usually the one being paid, cannot
 * mark their own report paid. All answer false for an unknown trip or nobody signed in.
 *
 * <p>It is also the rule for the travelers' LEDGERS ({@code TransactionsResource}), which needs a second
 * half the planning money never did. A budget is keyed by its trip, so a role on the trip is the whole
 * question; a ledger row is keyed by its PERSON, so the role only reaches the rows on that trip's books
 * ({@link #onBooks}). Checking the role alone let a finance role on one small trip read, zero and delete
 * anybody's rows on every other trip (audit 2026-11-11). Read = {@code tripFinView@trip} or
 * {@code tripFinAdmin@trip}; write = {@code tripFinAdmin@trip} (the settle rule: a trip manager is not a
 * finance admin). A site admin reads and writes every ledger, with or without a trip.
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

    private boolean reaches(final String tripId, final Transaction tx) {
        return callerSource.get().isSiteAdmin() || onBooks(tripId, tx);
    }

    /**
     * Whether a ledger row is on this trip's books: its TRANSACTION to TRIP binding names the trip (what the
     * website's trip ledger lists by), or the row is the trip's own. A row bound to no trip is on nobody's
     * books, so only its person and a site admin reach it. Read fresh: the answer authorizes a money write.
     */
    static boolean onBooks(final String tripId, final Transaction tx) {
        if (tripId == null || tx == null || tx.getUserId() == null || tx.getTxId() == null) {
            return false;
        }
        final String owner = tx.getUserId().getValue();
        if (tripId.equals(owner)) {
            return true;
        }
        final String key = CompositeKey.builder().partitionKey(owner).sortKey(tx.getTxId()).build().getValue();
        return DAO.getInstance().getBindings(key, BindingType.TRANSACTION, BindingType.TRIP, Cached.NO)
                .contains(tripId);
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

    /** May read: an editor, the trip's finance viewers, or a global finance viewer. */
    boolean canView(final String tripId) {
        if (canEdit(tripId)) {
            return true;
        }
        if (!signedInOnKnownTrip(tripId)) {
            return false;
        }
        final Caller caller = callerSource.get();
        return caller.has(PrivilegeCommands.TRIP_FIN_VIEW, tripId) || caller.has(PrivilegeCommands.VIEW_FINANCES);
    }

    /** The trip, or null for an unknown one ({@code getTrip} answers a miss with a blank trip and a fresh id). */
    Trip knownTrip(final String tripId) {
        if (tripId == null) {
            return null;
        }
        final Trip trip = tripSource.get().getTrip(tripId);
        return (trip == null || !tripId.equals(trip.getId())) ? null : trip;
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
            refuse("Nothing to save.");
            return null;
        }
        final Trip trip = tripSource.get().getTripForEdit(tripId);
        if (trip == null || !tripId.equals(trip.getId())) {
            refusal.accept("Unknown trip.");
            return null;
        }
        return trip;
    }

    private boolean signedInOnKnownTrip(final String tripId) {
        return tripId != null && !tripId.isBlank() && callerSource.get().isAuthenticated() && knownTrip(tripId) != null;
    }
}
