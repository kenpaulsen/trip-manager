package org.paulsens.trip.model;

/**
 * What a {@link ReservationOffer} offers. Only lodging exists today; the enum is the seam for future kinds
 * (transport, excursions) so the offer/reservation tables need no reshaping when one arrives.
 */
public enum OfferType {
    LODGING
}
