package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.CompositeKey;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;

/**
 * The destructive "Delete Pilgrimage" feature (trip edit page): authorization, the not-deletable conditions,
 * and the full cascade over every table that references a trip. There is deliberately no partial or soft
 * variant -- the page's confirm dialog (type-'delete' challenge) is the only caller.
 *
 * <p><b>Who may delete:</b> the trip's Editor Admin ({@code tripMgr}, which a site admin holds implicitly)
 * who is ALSO an admin of the organization that owns the pilgrimage ({@code OrgCommands.canManageOrg}, which
 * a site admin also passes). Org admins live on {@code Organization.adminIds} -- roster-style, NOT a
 * privilege row -- per the locked tenancy design.
 *
 * <p><b>What blocks a delete:</b> people still on the trip, approved (CONFIRMED) registrations, payment rows
 * not in a terminal state, and trip-bound transactions that are not soft-deleted. Financial history is never
 * destroyed here: once every bound transaction is soft-deleted and every payment is terminal, the delete
 * proceeds and LEAVES those rows (and their {@code bindings} links) dangling on purpose, as the surviving
 * record of money that once moved.
 *
 * <p><b>What the cascade removes:</b> chat (channel, messages, reactions, members incl. guests' reverse-index
 * rows, invites, photo channels, album rows, stored photos, CDN copies), registrations, todo items plus every
 * per-person todo-status and room row in {@code person_data}, trip-scoped privilege rows, trip events (before
 * the trip row -- they carry no tripId), badge images, and finally the trip row and its cache/index entries.
 */
@Slf4j
@Named("tripDelete")
@ApplicationScoped
public class TripDeleteCommands {

    /** What the confirm dialog makes the admin type. */
    static final String CHALLENGE = "delete";
    /** Counting cap for the confirm dialog's message tally; enough to say "a lot" honestly. */
    private static final int MESSAGE_COUNT_CAP = 1000;
    private static final String BLOCKERS_MEMO_PREFIX = "tripDeleteBlockers:";

    private final Supplier<Caller> callerSource;
    private final Supplier<OrgCommands> orgSource;
    private final Supplier<BadgePhotoCommands> badgeSource;

    public TripDeleteCommands() {
        this(Caller::current,
                () -> org.paulsens.trip.api.Beans.get(OrgCommands.class),
                () -> org.paulsens.trip.api.Beans.get(BadgePhotoCommands.class));
    }

    /** Explicit-collaborator constructor -- the {@link OrgCommands} test seam. */
    TripDeleteCommands(final Supplier<Caller> callerSource, final Supplier<OrgCommands> orgSource,
            final Supplier<BadgePhotoCommands> badgeSource) {
        this.callerSource = callerSource;
        this.orgSource = orgSource;
        this.badgeSource = badgeSource;
    }

    /** Whether the signed-in user may delete this pilgrimage at all (state conditions are separate). */
    public boolean canDelete(final Trip trip) {
        if (trip == null || trip.getId() == null || trip.getId().isBlank()) {
            return false;
        }
        final Caller caller = callerSource.get();
        if (!caller.isAuthenticated()) {
            return false;
        }
        if (!caller.has(PrivilegeCommands.TRIP_MGR, trip.getId())) {
            return false;
        }
        // canManageOrg refuses a blank org id for everyone (by design), which would make a never-tenanted
        // trip permanently undeletable -- so an org-less trip falls back to site admins only.
        final String orgId = trip.getOrgId();
        if (orgId == null || orgId.isBlank()) {
            return caller.isSiteAdmin();
        }
        return orgCommands().canManageOrg(orgId);
    }

    /** Whether any not-deletable condition currently holds (drives the lighter-red button state). */
    public boolean isBlocked(final Trip trip) {
        return !blockers(trip).isEmpty();
    }

    /**
     * The current not-deletable conditions, as user-readable sentences. Memoized per request (the page asks
     * several times per render, and the payment check is a table scan).
     */
    public List<String> blockers(final Trip trip) {
        if (trip == null || trip.getId() == null) {
            return List.of();
        }
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx == null) {
            return computeBlockers(trip);
        }
        final Map<Object, Object> attrs = ctx.getAttributes();
        final String key = BLOCKERS_MEMO_PREFIX + trip.getId();
        @SuppressWarnings("unchecked")
        List<String> memo = (List<String>) attrs.get(key);
        if (memo == null) {
            memo = computeBlockers(trip);
            attrs.put(key, memo);
        }
        return memo;
    }

    /** The lighter-red button's click: say WHY the delete cannot happen, one growl message per reason. */
    public void explainBlockers(final Trip trip) {
        if (trip == null) {
            return;
        }
        final List<String> blockers = computeBlockers(trip);
        if (blockers.isEmpty()) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_INFO,
                    "This pilgrimage can be deleted now -- reload the page to get the delete button.", null);
            return;
        }
        for (final String blocker : blockers) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_WARN, "Cannot delete: " + blocker, null);
        }
    }

    /** How many registration rows the delete would remove (the confirm dialog says so out loud). */
    public int registrationCount(final Trip trip) {
        if (trip == null || trip.getId() == null) {
            return 0;
        }
        return DAO.getInstance().getRegistrations(trip.getId(), Cached.NO).size();
    }

    /**
     * A phrase like {@code "17 chat messages and 4 photos"} when the trip's chat holds content the delete
     * would erase, or {@code ""} when there is nothing -- the confirm dialog shows the purge warning only
     * when this is non-empty, which is the "let the admin decide to purge or cancel" prompt.
     */
    public String chatContentSummary(final Trip trip) {
        if (trip == null || trip.getId() == null) {
            return "";
        }
        final ChatChannel.Id channelId = ChatChannel.Id.forTrip(trip.getId());
        int messages = 0;
        if (DAO.getInstance().getChatChannel(channelId, Cached.NO).isPresent()) {
            messages = DAO.getInstance()
                    .getRawChatMessagesBefore(channelId, null, MESSAGE_COUNT_CAP, Cached.NO).size();
        }
        final int photos = DAO.getInstance()
                .getMediaInSlot(ChatPhotos.slotFor(trip.getId()), Cached.NO).size();
        if (messages == 0 && photos == 0) {
            return "";
        }
        final String messagePart = (messages >= MESSAGE_COUNT_CAP ? MESSAGE_COUNT_CAP + "+" : "" + messages)
                + " chat message" + (messages == 1 ? "" : "s");
        if (photos == 0) {
            return messagePart;
        }
        return messagePart + " and " + photos + " photo" + (photos == 1 ? "" : "s");
    }

    /**
     * Permanently deletes the pilgrimage and everything that references it. The page passes its draft copy,
     * but only the ID is trusted: authorization, the blocking conditions, and the cascade all work from a
     * fresh uncached read, so a stale edit-page render cannot slip a delete past a condition that has since
     * become true.
     *
     * @param trip      the trip to delete (the edit page's draft; only its id is used).
     * @param challenge the word the admin typed in the confirm dialog; must be {@value #CHALLENGE}.
     * @return true when the trip is gone (the page then leaves the edit view); false with a growl otherwise.
     */
    public boolean deleteTrip(final Trip trip, final String challenge) {
        if (trip == null || trip.getId() == null || trip.getId().isBlank()) {
            return false;
        }
        if (!CHALLENGE.equalsIgnoreCase(challenge == null ? "" : challenge.trim())) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_WARN,
                    "Not deleted: type '" + CHALLENGE + "' in the box to confirm.", null);
            return false;
        }
        final Trip fresh = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElse(null);
        if (fresh == null) {
            // Someone else finished the job (or a retried cascade already took the row): goal state reached.
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_INFO,
                    "This pilgrimage no longer exists.", null);
            return true;
        }
        if (!canDelete(fresh)) {
            audit(fresh, AuditOutcome.FAILURE, "refused: not an Editor Admin + organization admin");
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Not allowed: deleting a pilgrimage requires its Editor Admin role AND being an admin "
                            + "of the organization it belongs to.", null);
            return false;
        }
        final List<String> blockers = computeBlockers(fresh);
        if (!blockers.isEmpty()) {
            audit(fresh, AuditOutcome.FAILURE, "refused: " + String.join(" | ", blockers));
            for (final String blocker : blockers) {
                TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_WARN, "Cannot delete: " + blocker, null);
            }
            return false;
        }
        try {
            final String summary = cascade(fresh);
            audit(fresh, AuditOutcome.SUCCESS, summary);
            log.warn("Trip {} ('{}') permanently deleted: {}", fresh.getId(), fresh.getTitle(), summary);
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_INFO,
                    "Pilgrimage '" + fresh.getTitle() + "' was permanently deleted.", null);
            return true;
        } catch (final RuntimeException ex) {
            log.error("Trip delete for {} did not finish", fresh.getId(), ex);
            audit(fresh, AuditOutcome.FAILURE, "cascade failed: " + ex.getMessage());
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                    "The delete did not finish: " + ex.getMessage()
                            + " Some data may already be removed; running the delete again is safe.", null);
            return false;
        }
    }

    /**
     * The delete itself, in dependency order: rows that can only be FOUND through something else go while
     * that something still exists (events via the trip's id list, todo statuses via the todo items' dataIds,
     * guest chat rows via the member list), and the trip row goes LAST so a crash mid-cascade leaves a
     * findable trip and a re-run finishes the job (every step is idempotent).
     *
     * <p>{@code bindings} rows and soft-deleted transactions/payments are deliberately NOT touched -- see
     * the class comment: they are the surviving record of money.
     *
     * @return a human-readable tally for the audit record.
     */
    private String cascade(final Trip trip) {
        final String tripId = trip.getId();
        ChatCommands.getChatCommands().purgeTripChat(tripId);
        // People the trip ever knew about, captured BEFORE their rows go: they seed the person_data sweep.
        final Set<Person.Id> candidates = new HashSet<>(trip.getPeople());
        DAO.getInstance().getRegistrations(tripId, Cached.NO)
                .forEach(reg -> candidates.add(reg.getUserId()));
        final List<Privilege> tripPrivs = DAO.getInstance().getTripPrivileges(tripId, Cached.NO);
        tripPrivs.forEach(priv -> candidates.addAll(priv.getPeople()));
        final int regs = DAO.getInstance().deleteRegistrationsForTrip(tripId);
        final List<DataId> todoIds = DAO.getInstance().deleteTodoItemsForTrip(tripId);
        final Set<DataId> pdvTargets = new HashSet<>(todoIds);
        pdvTargets.add(RegistrationCommands.tripRoomDataId(tripId));
        final int pdvRows = DAO.getInstance().deletePersonDataValuesByDataIds(pdvTargets, candidates);
        int privs = 0;
        for (final Privilege priv : tripPrivs) {
            if (DAO.getInstance().deletePrivilege(priv.getId())) {
                privs++;
            }
        }
        final List<String> eventIds = trip.getTripEventIds();
        for (final String eventId : eventIds) {
            DAO.getInstance().deleteTripEvent(eventId);
        }
        final int badges = badgePhotos().deleteAllForTrip(trip);
        DAO.getInstance().deleteTrip(trip);
        return "deleted trip '" + trip.getTitle() + "' with " + regs + " registrations, " + todoIds.size()
                + " todos (" + pdvRows + " person-data rows), " + privs + " privilege rows, "
                + eventIds.size() + " events, " + badges + " badge objects; chat purged";
    }

    private List<String> computeBlockers(final Trip trip) {
        final List<String> blockers = new ArrayList<>();
        final int people = trip.getPeople().size();
        if (people > 0) {
            blockers.add(people + (people == 1 ? " person is" : " people are")
                    + " still part of this pilgrimage. Remove everyone from the trip first.");
        }
        final long approved = DAO.getInstance().getRegistrations(trip.getId(), Cached.NO).stream()
                .filter(reg -> reg.getStatus() == Registration.Status.CONFIRMED)
                .count();
        if (approved > 0) {
            blockers.add(approved + " registration" + (approved == 1 ? " is" : "s are")
                    + " approved. Un-approve or move them first (unapproved registrations are deleted "
                    + "with the pilgrimage).");
        }
        final long livePayments = DAO.getInstance().getAllPayments().stream()
                .filter(payment -> trip.getId().equals(payment.getTripId()))
                .filter(TripDeleteCommands::isLivePayment)
                .count();
        if (livePayments > 0) {
            blockers.add(livePayments + " payment record" + (livePayments == 1 ? " is" : "s are")
                    + " not cancelled. Financial records are never destroyed; cancel them (or wait for "
                    + "them to fail) first.");
        }
        final int liveTxs = countLiveTransactions(trip.getId());
        if (liveTxs > 0) {
            blockers.add(liveTxs + " financial transaction" + (liveTxs == 1 ? " is" : "s are")
                    + " still linked to this pilgrimage. Delete them on the transactions page first "
                    + "(they are kept as soft-deleted records).");
        }
        return blockers;
    }

    private static boolean isLivePayment(final Payment payment) {
        return payment.getStatus() != Payment.Status.CANCELLED && payment.getStatus() != Payment.Status.FAILED;
    }

    /** Trip-bound transactions that are not soft-deleted; a malformed binding is logged and skipped. */
    private int countLiveTransactions(final String tripId) {
        int live = 0;
        for (final String bound : DAO.getInstance()
                .getBindings(tripId, BindingType.TRIP, BindingType.TRANSACTION, Cached.NO)) {
            try {
                final CompositeKey key = CompositeKey.from(bound);
                final Transaction tx = DAO.getInstance()
                        .getTransaction(Person.Id.from(key.getPartitionKey()), key.getSortKey(), Cached.NO)
                        .orElse(null);
                if (tx != null && tx.getDeleted() == null) {
                    live++;
                }
            } catch (final IllegalArgumentException ex) {
                log.warn("Skipping malformed trip->transaction binding '{}' for trip {}", bound, tripId, ex);
            }
        }
        return live;
    }

    private void audit(final Trip trip, final AuditOutcome outcome, final String message) {
        Audit.builder(AuditAction.TRIP_DELETE, outcome)
                .target(AuditEventBuilder.TARGET_TRIP, trip.getId())
                .message(message)
                .log();
    }

    private OrgCommands orgCommands() {
        return orgSource.get();
    }

    private BadgePhotoCommands badgePhotos() {
        return badgeSource.get();
    }
}
