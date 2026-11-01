package org.paulsens.trip.pay;

/**
 * The admin page's "Register this site for wallets" button: registers a host with an organization's Stripe
 * account (Apple Pay, Google Pay and Link in the Payment Element are per-domain). A separate public door
 * because the processor's helpers stay package-private; the SDK call itself lives in {@link StripeProcessor}.
 */
public final class StripeDomains {
    private StripeDomains() {
    }

    /** Null never: a one-line summary on success, or a message starting with "Stripe refused". */
    public static String register(final String secretKey, final boolean testMode, final String host) {
        return StripeProcessor.registerDomain(secretKey, testMode, host, null);
    }
}
