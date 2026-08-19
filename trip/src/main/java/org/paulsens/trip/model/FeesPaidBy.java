package org.paulsens.trip.model;

/**
 * Who covers the processor fee on a payment. Entered amounts are ALWAYS the amounts credited; this only
 * decides whether the trip-portion fee is added ON TOP of the charge ({@link #PAYER}) or absorbed upstream
 * ({@link #ORGANIZATION}). A donation's fee share is always absorbed by the organization, in either mode.
 */
public enum FeesPaidBy {
    ORGANIZATION, PAYER
}
