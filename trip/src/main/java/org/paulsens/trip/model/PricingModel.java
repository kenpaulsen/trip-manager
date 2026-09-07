package org.paulsens.trip.model;

/**
 * How a {@link ReservationOffer}'s nightly price is charged.
 *
 * <ul>
 *   <li>{@link #PER_ROOM}: the nightly price is for the ROOM; each night it is divided equally among everyone
 *       occupying that room that night (across every active reservation on the room). A sole occupant pays
 *       the whole room, so the single supplement never applies.</li>
 *   <li>{@link #PER_PERSON}: the nightly price is charged to each person; on a night the person is the ONLY
 *       occupant of their assigned room the offer's single supplement is added (unless waived on the
 *       reservation).</li>
 * </ul>
 */
public enum PricingModel {
    PER_ROOM, PER_PERSON
}
