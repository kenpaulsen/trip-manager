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
    FAKE(349, 49),
    /**
     * Not a processor: a registration paid outside one (a check, cash, a comp) or costing nothing, run through the
     * same {@code Payment} pipeline so it records and registers exactly like a processor payment
     * (docs/checkout.md). Never configurable, never charged, no fees, no capture step.
     */
    OFFLINE(0, 0, false);

    private final int defaultFeeBps;
    private final int defaultFeeFixedCents;
    private final boolean configurable;

    ProcessorType(final int defaultFeeBps, final int defaultFeeFixedCents) {
        this(defaultFeeBps, defaultFeeFixedCents, true);
    }

    ProcessorType(final int defaultFeeBps, final int defaultFeeFixedCents, final boolean configurable) {
        this.defaultFeeBps = defaultFeeBps;
        this.defaultFeeFixedCents = defaultFeeFixedCents;
        this.configurable = configurable;
    }

    /** Whether an organization can set this type up as a processor config (everything but OFFLINE). */
    public boolean isConfigurable() {
        return configurable;
    }

    /** The types an organization may configure, in declaration order: the processor settings picker. */
    public static java.util.List<ProcessorType> configurableTypes() {
        return java.util.Arrays.stream(values()).filter(ProcessorType::isConfigurable).toList();
    }

    public int getDefaultFeeBps() {
        return defaultFeeBps;
    }

    public int getDefaultFeeFixedCents() {
        return defaultFeeFixedCents;
    }
}
