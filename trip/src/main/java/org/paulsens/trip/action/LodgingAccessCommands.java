package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Trip;

/**
 * Who may do what with lodging, exposed to pages as {@code #{lodgingAccess}}. The gates
 * {@link LodgingCommands} answers ({@code canManageTripLodging}, {@code canEditAccommodation}, ...) are
 * computed here from the caller's privilege rows; {@link #forTrip} folds them, together with the hotels IN
 * PLAY on a trip, into one {@link LodgingAccess} a page reads for the length of a request.
 *
 * <p>The answer is memoized in the Faces request map under {@code lodgingAccess:{tripId}}, so the many
 * {@code #{access.*}} reads of one render cost one computation. A REST call or a test has no FacesContext
 * and gets a bare compute every time, which is also why a write made earlier in the SAME page request can
 * be invisible to the memo: callers that must see it ask {@link LodgingCommands#canAssignAt} rather than
 * {@link LodgingAccess#mayOpenBoard}.
 */
@Named("lodgingAccess")
@ApplicationScoped
public class LodgingAccessCommands {
    static final String MEMO_PREFIX = "lodgingAccess:";

    private final Supplier<Caller> callerSource;

    public LodgingAccessCommands() {
        this(Caller::current);
    }

    /** Test seam (the {@link HotelCommands} pattern): {@code Caller.current()} needs a FacesContext. */
    public LodgingAccessCommands(final Supplier<Caller> callerSource) {
        this.callerSource = callerSource;
    }

    // ================================================================== the per-trip access object

    /** This caller's reach on one trip's lodging; {@link LodgingAccess#none} for a blank trip or a visitor. */
    public LodgingAccess forTrip(final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return LodgingAccess.none(tripId);
        }
        final Map<String, Object> memo = requestMap();
        final String key = MEMO_PREFIX + tripId;
        if (memo != null && memo.get(key) instanceof LodgingAccess cached) {
            return cached;
        }
        final LodgingAccess access = compute(tripId);
        if (memo != null) {
            memo.put(key, access);
        }
        return access;
    }

    /** Whether this caller operates (holds {@code accommodationAdmin} on) a hotel in play on the trip. */
    public boolean operatorOfAnyHotelOn(final String tripId) {
        return forTrip(tripId).isOperatorHere();
    }

    /**
     * The naming rule for a read that crosses trips (the board's "other trips in this room", the hotel's
     * day detail): a trip is NAMED to someone internal on it or to an operator of the hotel in question;
     * anyone else is told "another organization's trip".
     */
    public boolean mayNameTrip(final String tripId, final String accId) {
        return canManageTripLodging(tripId) || canEditAccommodation(accId);
    }

    LodgingAccess compute(final String tripId) {
        final Caller current = caller();
        if (!current.isAuthenticated()) {
            return LodgingAccess.none(tripId);
        }
        final Set<String> inPlay = inPlayHotels(tripId);
        final Set<String> operated = new LinkedHashSet<>();
        for (final String accId : inPlay) {
            if (current.has(PrivilegeCommands.ACCOMMODATION_ADMIN, accId)) {
                operated.add(accId);
            }
        }
        return new LodgingAccess(tripId, canManageTripLodging(tripId), operated, inPlay);
    }

    /** The hotels named by the trip's lodging options or by its ACTIVE reservations, in that order. */
    static Set<String> inPlayHotels(final String tripId) {
        final Set<String> ids = new LinkedHashSet<>();
        final DAO dao = DAO.getInstance();
        for (final ReservationOffer offer : dao.getReservationOffers(tripId, Cached.YES)) {
            if (offer.getAccommodationId() != null) {
                ids.add(offer.getAccommodationId().getValue());
            }
        }
        for (final Reservation res : dao.getReservations(tripId, Cached.YES)) {
            if (res.isActive() && res.getAccommodationId() != null) {
                ids.add(res.getAccommodationId().getValue());
            }
        }
        return ids;
    }

    // ================================================================== the gates

    /** Site admin or GLOBAL lodging admin: every hotel, every trip. */
    public boolean isGlobalLodgingAdmin() {
        final Caller current = caller();
        return current.isAuthenticated() && (current.isSiteAdmin() || current.has(PrivilegeCommands.LODGING_ADMIN));
    }

    /** May edit THIS accommodation's data: global admins, or a holder of {@code accommodationAdmin@acc}. */
    public boolean canEditAccommodation(final String accId) {
        if (accId == null || accId.isBlank()) {
            return false;
        }
        return isGlobalLodgingAdmin() || caller().has(PrivilegeCommands.ACCOMMODATION_ADMIN, accId);
    }

    /** May create an accommodation: global admins, or a lodging admin of ANY organization. */
    public boolean canCreateAccommodation() {
        if (isGlobalLodgingAdmin()) {
            return true;
        }
        final Caller current = caller();
        if (!current.isAuthenticated()) {
            return false;
        }
        for (final Organization org : DAO.getInstance().getOrganizations(Cached.YES)) {
            if (current.has(PrivilegeCommands.LODGING_ADMIN, org.getId().getValue())) {
                return true;
            }
        }
        return false;
    }

    /** May manage offers and reservations on this trip: global, the trip's org's lodging admin, or its tripMgr. */
    public boolean canManageTripLodging(final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return false;
        }
        final Caller current = caller();
        if (!current.isAuthenticated()) {
            return false;
        }
        if (isGlobalLodgingAdmin() || current.has(PrivilegeCommands.TRIP_MGR, tripId)) {
            return true;
        }
        final Trip trip = DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
        return trip != null && trip.getOrgId() != null
                && current.has(PrivilegeCommands.LODGING_ADMIN, trip.getOrgId());
    }

    // ================================================================== plumbing

    private Caller caller() {
        return callerSource.get();
    }

    private static Map<String, Object> requestMap() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        return (ctx == null) ? null : ctx.getExternalContext().getRequestMap();
    }
}
