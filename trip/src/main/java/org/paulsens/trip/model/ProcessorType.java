package org.paulsens.trip.model;

/**
 * The kinds of payment processor an {@link Organization} can configure. Fee numbers are the ADVERTISED
 * defaults used for estimates when a config does not override them; the processor's actual fee is whatever
 * the capture reports.
 */
public enum ProcessorType {
    /** PayPal Checkout (orders v2, redirect + capture). Cards only; no bank-debit pricing. */
    PAYPAL("PayPal", 349, 49, 0, 0),
    /**
     * Stripe PaymentIntents (Payment Element on the page, PaymentSheet in the apps, webhook). The card rate
     * is the default estimate; a US bank debit is 0.8% capped at $5.00, applied when the payer picks one.
     */
    STRIPE("Stripe", 290, 30, 80, 500),
    /** Zeffy hosted forms: no capture API; record-only reconciliation. Not yet implemented. */
    ZEFFY("Zeffy", 0, 0, 0, 0),
    /**
     * Local-mode fake: drives the full flow in tests with no network. PayPal-like card fees so the math on
     * screen is realistic, Stripe-like bank pricing so the method-aware re-pricing can be exercised too.
     */
    FAKE("Test Processor", 349, 49, 80, 500),
    /**
     * Not a processor: a registration paid outside one (a check, cash, a comp) or costing nothing, run through the
     * same {@code Payment} pipeline so it records and registers exactly like a processor payment
     * (docs/checkout.md). Never configurable, never charged, no fees, no capture step.
     */
    OFFLINE("Offline payment", 0, 0, 0, 0, false);

    private final String label;
    private final int defaultFeeBps;
    private final int defaultFeeFixedCents;
    private final int bankFeeBps;
    private final int bankFeeCapCents;
    private final boolean configurable;

    ProcessorType(final String label, final int defaultFeeBps, final int defaultFeeFixedCents,
            final int bankFeeBps, final int bankFeeCapCents) {
        this(label, defaultFeeBps, defaultFeeFixedCents, bankFeeBps, bankFeeCapCents, true);
    }

    ProcessorType(final String label, final int defaultFeeBps, final int defaultFeeFixedCents,
            final int bankFeeBps, final int bankFeeCapCents, final boolean configurable) {
        this.label = label;
        this.defaultFeeBps = defaultFeeBps;
        this.defaultFeeFixedCents = defaultFeeFixedCents;
        this.bankFeeBps = bankFeeBps;
        this.bankFeeCapCents = bankFeeCapCents;
        this.configurable = configurable;
    }

    /**
     * The name people read in ledger descriptions, refund rows, mail and the audit trail ("PayPal", "Stripe",
     * "Offline payment"): the ONE source of it, so a rename reaches every one of them.
     */
    public String getLabel() {
        return label;
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

    /** The bank-debit (ACH) rate in basis points; zero when this kind prices every method alike. */
    public int getBankFeeBps() {
        return bankFeeBps;
    }

    /** The per-payment cap on the bank-debit fee, in cents (Stripe: $5.00); zero when there is none. */
    public int getBankFeeCapCents() {
        return bankFeeCapCents;
    }

    /** Whether a payer-pays quote should be re-priced when the payer picks a bank debit. */
    public boolean hasBankPricing() {
        return bankFeeBps > 0;
    }
}
