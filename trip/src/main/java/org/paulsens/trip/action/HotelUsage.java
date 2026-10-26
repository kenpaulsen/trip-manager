package org.paulsens.trip.action;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Trip;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Who USES a hotel, as recorded on the {@link Accommodation} row: the organizations ({@code orgIds}, since
 * the hotels landed) and, since 2026-10, the trips holding a lodging option there ({@code tripIds}). Both are
 * bookkeeping written by the offer save, so an operator finds a trip on their hotel page's Trips tab the
 * moment the trip has an OPTION there -- before anyone is booked, which is when the hotel states its cost.
 * The reservations index alone only knew a trip once it had a stay.
 *
 * <p>Best effort on purpose: a lost race on the hotel row must never fail the offer save that triggered it,
 * so every write here swallows the conditional-put loss into a log line. The next offer save records it again.
 */
@Slf4j
final class HotelUsage {

    private HotelUsage() {
    }

    /**
     * Records the trip (and, the first time, its organization) on the hotel after an offer save. The org's
     * first use also puts the hotel's contact on the org's roster, so the hotel is mailable from that org.
     */
    static void record(final Accommodation acc, final Trip trip, final OrgCommands orgs) {
        final Organization.Id orgId = (trip.getOrgId() == null || trip.getOrgId().isBlank()) ? null
                : Organization.Id.from(trip.getOrgId());
        final boolean orgKnown = orgId == null || acc.usedBy(orgId);
        if (orgKnown && acc.usedByTrip(trip.getId())) {
            return;
        }
        final Accommodation fresh = fresh(acc.getId());
        if (fresh == null) {
            return;
        }
        boolean changed = fresh.recordTripUse(trip.getId());
        boolean orgJoined = false;
        if (orgId != null && !fresh.usedBy(orgId)) {
            fresh.getOrgIds().add(orgId);
            changed = true;
            orgJoined = true;
        }
        if (changed && !store(fresh)) {
            return;
        }
        if (orgJoined && fresh.getContactId() != null) {
            orgs.addLodgingContact(orgId.getValue(), fresh.getContactId());
        }
    }

    /**
     * Forgets the trip on the hotel once no offer of the trip names it any more: after an offer delete
     * ({@code remaining} = the trip's other offers) or a trip delete ({@code remaining} empty).
     */
    static void forgetTrip(final Accommodation.Id accId, final String tripId, final List<ReservationOffer> remaining) {
        if (accId == null) {
            return;
        }
        for (final ReservationOffer other : remaining) {
            if (accId.equals(other.getAccommodationId())) {
                return;
            }
        }
        final Accommodation fresh = fresh(accId);
        if (fresh != null && fresh.forgetTrip(tripId)) {
            store(fresh);
        }
    }

    /** The trip-delete sweep: every hotel the trip's (now deleted) offers named forgets the trip. */
    static void forgetTripEverywhere(final String tripId, final List<ReservationOffer> deletedOffers) {
        final Set<Accommodation.Id> hotels = new LinkedHashSet<>();
        for (final ReservationOffer offer : deletedOffers) {
            if (offer.getAccommodationId() != null) {
                hotels.add(offer.getAccommodationId());
            }
        }
        for (final Accommodation.Id accId : hotels) {
            forgetTrip(accId, tripId, List.of());
        }
    }

    private static Accommodation fresh(final Accommodation.Id accId) {
        return DAO.getInstance().getAccommodation(accId, Cached.NO).orElse(null);
    }

    private static boolean store(final Accommodation acc) {
        try {
            return Boolean.TRUE.equals(DAO.getInstance().saveAccommodation(acc));
        } catch (final ConditionalCheckFailedException ex) {
            log.warn("Hotel {} was saved by someone else while recording its use; the next offer save retries",
                    acc.getId());
            return false;
        } catch (final IOException | IllegalArgumentException ex) {
            // IllegalArgumentException is the DAO's row-size cap: a hotel already at the item limit cannot take
            // one more trip id, and bookkeeping must never fail the offer save it rides on.
            log.error("Unable to record use of hotel {}", acc.getId(), ex);
            return false;
        }
    }
}
