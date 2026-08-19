package org.paulsens.trip.pay;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The payment flow's money arithmetic: ALL computation in long cents; {@code Transaction.amount}'s Float is
 * produced exactly once at the boundary ({@link #toFloatDollars} -- exact below ~$167k, inherited debt).
 *
 * <p>The fee model (user-locked 2026-08-17): entered amounts are always the amounts CREDITED. When the payer
 * covers fees, the trip-portion fee is added ON TOP by grossing up the credit portion --
 * {@code charged = ceil((credits + fixed) / (1 - rate))} -- so the organization nets exactly the credits and
 * the fee is exact by construction. A donation's fee share ({@code rate x donation}, informational) is NEVER
 * added to the charge and never deducted from the donation credit: the organization absorbs it. A
 * donation-only payment charges exactly the donation.
 *
 * <p>Worked example (flat 5%, no fixed fee): credits 3 x $475 = $1,425 &rarr; charged portion
 * $1,425/0.95 = $1,500, fee $75; donation $1,000 &rarr; informational fee $50, absorbed; total charged
 * $2,500. The processor takes $125; the org nets $2,375.
 */
public final class MoneyMath {
    private static final int BPS_SCALE = 10_000;

    private MoneyMath() {
    }

    /** Dollars (user input, max 2 decimals, non-negative) to cents. Throws on anything else. */
    public static long toCents(final BigDecimal dollars) {
        if (dollars == null) {
            return 0L;
        }
        if (dollars.signum() < 0) {
            throw new IllegalArgumentException("Amounts cannot be negative: " + dollars);
        }
        if (dollars.scale() > 2 && dollars.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Amounts cannot be finer than cents: " + dollars);
        }
        return dollars.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    /** The one Float conversion, for the Transaction boundary. */
    public static float toFloatDollars(final long cents) {
        return cents / 100f;
    }

    /** Cents formatted as {@code $1,234.56} for descriptions and email tokens. */
    public static String formatCents(final long cents) {
        return String.format("$%,d.%02d", cents / 100, Math.abs(cents % 100));
    }

    /**
     * The fee added ON TOP when the payer covers fees: gross-up of the credit portion.
     * {@code chargedPortion = ceil((credits + fixedCents) / (1 - rateBps/10000))}; the fee is the exact
     * difference, so the ledger reconciles to the charge to the penny. Zero when there are no credits.
     */
    public static long creditFeeCents(final long creditCents, final int rateBps, final int fixedCents) {
        if (creditCents <= 0) {
            return 0L;
        }
        final long numerator = Math.addExact(creditCents, fixedCents) * BPS_SCALE;
        final long denominator = BPS_SCALE - rateBps;
        if (denominator <= 0) {
            throw new IllegalArgumentException("Fee rate must be below 100%: " + rateBps + " bps");
        }
        final long charged = Math.ceilDiv(numerator, denominator);
        return charged - creditCents;
    }

    /**
     * The donation's INFORMATIONAL fee share ({@code rate x donation}, half-up): shown to the payer and
     * noted in descriptions, never added to the charge, never deducted from the credit -- the organization
     * absorbs it. No fixed-fee share: the fixed fee rides the credit portion (or is absorbed outright on a
     * donation-only payment).
     */
    public static long donationFeeCents(final long donationCents, final int rateBps) {
        if (donationCents <= 0) {
            return 0L;
        }
        return BigDecimal.valueOf(donationCents).multiply(BigDecimal.valueOf(rateBps))
                .divide(BigDecimal.valueOf(BPS_SCALE), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Total to charge: credits + (payer-pays ? creditFee : 0) + donation. */
    public static long totalChargedCents(final long creditCents, final long creditFeeCents,
            final long donationCents, final boolean payerPaysFee) {
        return creditCents + (payerPaysFee ? creditFeeCents : 0L) + donationCents;
    }

    /**
     * Deterministic even split (documented for any future per-person materialization; the live SHARED rows
     * divide at read time instead): base share everywhere, the remainder cents to the FIRST {@code r}
     * positions of the caller's (already deterministically ordered) member list.
     */
    public static long[] splitEvenly(final long totalCents, final int ways) {
        if (ways <= 0) {
            throw new IllegalArgumentException("Cannot split across " + ways + " people");
        }
        final long base = totalCents / ways;
        final long remainder = totalCents % ways;
        final long[] shares = new long[ways];
        for (int i = 0; i < ways; i++) {
            shares[i] = base + (i < remainder ? 1 : 0);
        }
        return shares;
    }
}
