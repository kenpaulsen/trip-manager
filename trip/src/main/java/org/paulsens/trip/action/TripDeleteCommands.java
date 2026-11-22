package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.CompositeKey;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;

/**
 * The destructive "Delete Trip" feature (trip edit page): authorization, the not-deletable conditions,
 * and the full cascade over every table that references a trip. There is deliberately no partial or soft
 * variant -- the page's confirm dialog (type-'delete' challenge) is the only caller.
 *
 * <p><b>Who may delete:</b> the trip's Editor Admin ({@code tripMgr}, which a site admin holds implicitly)
 * who is ALSO an admin of the organization that owns the trip ({@code OrgCommands.canManageOrg}, which
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
 * per-person todo-status and room row in {@code person_data}, trip-scoped privilege rows, lodging offers and
 * reservations (then the hotels forget the trip and its rate plans unlink it), trip events (before the trip
 * row -- they carry no tripId), badge images, and finally the trip row and its cache/index entries.
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
    private final Supplier<TripPictureCommands> pictureSource;

    public TripDeleteCommands() {
        this(Caller::current,
                () -> org.paulsens.trip.api.Beans.get(OrgCommands.class),
                () -> org.paulsens.trip.api.Beans.get(BadgePhotoCommands.class),
                () -> org.paulsens.trip.api.Beans.get(TripPictureCommands.class));
    }

    /** Explicit-collaborator constructor -- the {@link OrgCommands} test seam; trip pictures come from CDI. */
    TripDeleteCommands(final Supplier<Caller> callerSource, final Supplier<OrgCommands> orgSource,
            final Supplier<BadgePhotoCommands> badgeSource) {
        this(callerSource, orgSource, badgeSource,
                () -> org.paulsens.trip.api.Beans.get(TripPictureCommands.class));
    }

    /** Every collaborator explicit, for a test that also answers the trip-picture cascade. */
    TripDeleteCommands(final Supplier<Caller> callerSource, final Supplier<OrgCommands> orgSource,
            final Supplier<BadgePhotoCommands> badgeSource, final Supplier<TripPictureCommands> pictureSource) {
        this.callerSource = callerSource;
        this.orgSource = orgSource;
        this.badgeSource = badgeSource;
        this.pictureSource = pictureSource;
    }

    /** Whether the signed-in user may delete this trip at all (state conditions are separate). */
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
        return RequestMemo.get(BLOCKERS_MEMO_PREFIX + trip.getId(), () -> computeBlockers(trip));
    }

    /** The lighter-red button's click: say WHY the delete cannot happen, one growl message per reason. */
    public void explainBlockers(final Trip trip) {
        if (trip == null) {
            return;
        }
        final List<String> blockers = computeBlockers(trip);
        if (blockers.isEmpty()) {
            PageFeedback.info(Text.get("feedback.tripDeleteCommands.deletableNow"));
            return;
        }
        for (final String blocker : blockers) {
            PageFeedback.warn(Text.get("feedback.tripDeleteCommands.cannotDelete", blocker));
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
        if (messages >= MESSAGE_COUNT_CAP) {
            final String cap = String.valueOf(MESSAGE_COUNT_CAP);
            return photos == 0 ? Text.get("feedback.tripDeleteCommands.chatMessagesCapped", cap)
                    : Text.get("feedback.tripDeleteCommands.chatMessagesCappedAndPhotos", cap, photos);
        }
        return photos == 0 ? Text.get("feedback.tripDeleteCommands.chatMessages", messages)
                : Text.get("feedback.tripDeleteCommands.chatMessagesAndPhotos", messages, photos);
    }

    /**
     * Permanently deletes the trip and everything that references it. The page passes its draft copy,
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
            PageFeedback.warn(Text.get("feedback.tripDeleteCommands.typeToConfirm", CHALLENGE));
            return false;
        }
        final Trip fresh = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElse(null);
        if (fresh == null) {
            // Someone else finished the job (or a retried cascade already took the row): goal state reached.
            PageFeedback.info(Text.get("feedback.tripDeleteCommands.tripGone"));
            return true;
        }
        if (!canDelete(fresh)) {
            audit(fresh, AuditOutcome.FAILURE, "refused: not an Editor Admin + organization admin");
            PageFeedback.error(Text.get("feedback.tripDeleteCommands.notAllowed"));
            return false;
        }
        final Blocking blocking = blocking(fresh);
        final List<String> blockers = blocking.sentences(RequestContext.currentLocale());
        if (!blockers.isEmpty()) {
            // The audit trail stays English whatever language the growls below are in.
            audit(fresh, AuditOutcome.FAILURE, "refused: " + String.join(" | ", blocking.sentences(Locale.ENGLISH)));
            for (final String blocker : blockers) {
                PageFeedback.warn(Text.get("feedback.tripDeleteCommands.cannotDelete", blocker));
            }
            return false;
        }
        try {
            final String summary = cascade(fresh);
            audit(fresh, AuditOutcome.SUCCESS, summary);
            log.warn("Trip {} ('{}') permanently deleted: {}", fresh.getId(), fresh.getTitle(), summary);
            PageFeedback.info(Text.get("feedback.tripDeleteCommands.deleted", fresh.getTitle()));
            return true;
        } catch (final RuntimeException ex) {
            log.error("Trip delete for {} did not finish", fresh.getId(), ex);
            audit(fresh, AuditOutcome.FAILURE, "cascade failed: " + ex.getMessage());
            PageFeedback.error(Text.get("feedback.tripDeleteCommands.deleteUnfinished"));
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
        // Lodging offers and reservations: both trip partitions. Their Bill rows stay (soft-deleted by the
        // "live transactions" blocker before this ran), like every other transaction. The offers are read
        // FIRST: once the rows are gone, the hotels they named forget the trip and the rate plans they
        // followed unlink it (both tolerate a hotel or plan that has meanwhile vanished).
        final List<ReservationOffer> offers = DAO.getInstance().getReservationOffers(tripId, Cached.NO);
        final int lodging = DAO.getInstance().deleteLodgingForTrip(tripId);
        HotelUsage.forgetTripEverywhere(tripId, offers);
        final int plans = new RatePlanCommands(callerSource).unlinkAll(tripId, offers);
        final boolean budget = DAO.getInstance().getTripBudget(tripId, Cached.NO).isPresent();
        DAO.getInstance().deleteTripBudget(tripId);
        // The scheduled messages and their send history go with the trip: no mail can follow a deleted trip.
        DAO.getInstance().deleteRemindersForTrip(tripId);
        final List<String> eventIds = trip.getTripEventIds();
        for (final String eventId : eventIds) {
            DAO.getInstance().deleteTripEvent(eventId);
        }
        final int badges = badgePhotos().deleteAllForTrip(trip);
        final int pictures = pictureSource.get().deleteAllForTrip(trip);
        DAO.getInstance().deleteTrip(trip);
        return "deleted trip '" + trip.getTitle() + "' with " + regs + " registrations, " + todoIds.size()
                + " todos (" + pdvRows + " person-data rows), " + privs + " privilege rows, "
                + eventIds.size() + " events, " + lodging + " lodging rows (" + plans + " rate plans unlinked), "
                + (budget ? "the budget, " : "") + badges + " badge objects, " + pictures
                + " picture objects; chat purged";
    }

    private List<String> computeBlockers(final Trip trip) {
        return blocking(trip).sentences(RequestContext.currentLocale());
    }

    /**
     * What stands in the way of a delete, as counts: the growl says them in the reader's language and the audit
     * line in English, from one read of the tables.
     */
    private record Blocking(int people, long approved, long livePayments, int liveTxs) {

        List<String> sentences(final Locale locale) {
            final List<String> blockers = new ArrayList<>();
            if (people > 0) {
                blockers.add(Text.forLocale(locale, "feedback.tripDeleteCommands.peopleOnTrip", people));
            }
            if (approved > 0) {
                blockers.add(Text.forLocale(locale, "feedback.tripDeleteCommands.approvedRegistrations", approved));
            }
            if (livePayments > 0) {
                blockers.add(Text.forLocale(locale, "feedback.tripDeleteCommands.livePayments", livePayments));
            }
            if (liveTxs > 0) {
                blockers.add(Text.forLocale(locale, "feedback.tripDeleteCommands.liveTransactions", liveTxs));
            }
            return blockers;
        }
    }

    private Blocking blocking(final Trip trip) {
        final long approved = DAO.getInstance().getRegistrations(trip.getId(), Cached.NO).stream()
                .filter(reg -> reg.getStatus() == Registration.Status.CONFIRMED)
                .count();
        final long livePayments = DAO.getInstance().getAllPayments().stream()
                .filter(payment -> trip.getId().equals(payment.getTripId()))
                .filter(TripDeleteCommands::isLivePayment)
                .count();
        return new Blocking(trip.getPeople().size(), approved, livePayments, countLiveTransactions(trip.getId()));
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
                .org(trip.getOrgId())
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
