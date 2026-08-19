package org.paulsens.trip.model;

/**
 * The kinds of payment processor an {@link Organization} can configure. Fee numbers are the ADVERTISED
 * defaults used for estimates when a config does not override them; the processor's actual fee is whatever
 * the capture reports.
 */
public enum ProcessorType {
    /** PayPal Checkout (orders v2, redirect + capture). */
    PAYPAL(349, 49),
    /** Stripe Checkout Session (redirect + webhook/capture). Not yet implemented -- configs can be staged. */
    STRIPE(290, 30),
    /** Zeffy hosted forms: no capture API; record-only reconciliation. Not yet implemented. */
    ZEFFY(0, 0),
    /** Local-mode fake: drives the full flow in tests with no network. PayPal-like fees so math is real. */
    FAKE(349, 49);

    private final int defaultFeeBps;
    private final int defaultFeeFixedCents;

    ProcessorType(final int defaultFeeBps, final int defaultFeeFixedCents) {
        this.defaultFeeBps = defaultFeeBps;
        this.defaultFeeFixedCents = defaultFeeFixedCents;
    }

    public int getDefaultFeeBps() {
        return defaultFeeBps;
    }

    public int getDefaultFeeFixedCents() {
        return defaultFeeFixedCents;
    }
}
