package org.paulsens.trip.pay;

import org.paulsens.trip.model.CostExtra;

/**
 * The arithmetic of a {@link CostExtra}, pure and deterministic like {@link LodgingPricing}. An extra is a
 * unit amount times a quantity the caller has already counted for the option: guest-nights (sum over active
 * stays of occupants x nights), room-nights (distinct room x night pairs), stays (active reservations with at
 * least one night). FIXED is once for the option group whatever the counts. Amounts are signed: a coupon is a
 * negative extra and comes out negative here too.
 */
public final class CostExtras {
    private CostExtras() {
    }

    /** How many units the extra's basis selects out of the three counts; 1 for FIXED, never negative. */
    public static int quantity(final CostExtra extra, final int guestNights, final int roomNights,
            final int stays) {
        if (extra == null) {
            return 0;
        }
        switch (extra.getBasis()) {
            case PER_PERSON_NIGHT:
                return Math.max(0, guestNights);
            case PER_ROOM_NIGHT:
                return Math.max(0, roomNights);
            case PER_STAY:
                return Math.max(0, stays);
            case FIXED:
            default:
                return 1;
        }
    }

    /** The extra's total for the option: unit amount x {@link #quantity}; 0 for a null extra. */
    public static long amountCents(final CostExtra extra, final int guestNights, final int roomNights,
            final int stays) {
        if (extra == null) {
            return 0L;
        }
        return extra.getAmountCents() * quantity(extra, guestNights, roomNights, stays);
    }

    /**
     * The invoice wording: {@code "City tax: $2.50 × 12 guest-nights"}, {@code "Cleaning: $30.00 × 1 stay"},
     * {@code "Booking fee: $15.00"}, {@code "Coupon: -$20.00"}.
     */
    public static String describe(final CostExtra extra, final int guestNights, final int roomNights,
            final int stays) {
        if (extra == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        if (extra.getLabel() != null && !extra.getLabel().isBlank()) {
            sb.append(extra.getLabel()).append(": ");
        }
        sb.append(formatSigned(extra.getAmountCents()));
        if (extra.getBasis() != CostExtra.Basis.FIXED) {
            final int quantity = quantity(extra, guestNights, roomNights, stays);
            sb.append(" × ").append(quantity).append(' ').append(extra.unit(quantity));
        }
        return sb.toString();
    }

    /** {@code MoneyMath.formatCents} with the sign in front: {@code "-$20.00"}, not {@code "$-20.00"}. */
    public static String formatSigned(final long cents) {
        return (cents < 0) ? "-" + MoneyMath.formatCents(-cents) : MoneyMath.formatCents(cents);
    }
}
