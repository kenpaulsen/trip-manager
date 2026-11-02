package org.paulsens.trip.pay;

import com.stripe.Stripe;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.Balance;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethodDomain;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.PaymentIntentUpdateParams;
import com.stripe.param.PaymentMethodDomainCreateParams;
import java.util.OptionalLong;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.ProcessorType;

/**
 * Stripe behind the {@link PaymentProcessor} SPI, INLINE-shaped: {@link #createOrder} makes a PaymentIntent
 * and answers its client secret, which the page's Payment Element or the apps' PaymentSheet confirm in place
 * (card fields are Stripe's iframes / native sheet, so card data never touches this server -- PCI SAQ A).
 * There is no redirect and no capture call: {@link #capture} is a verification READ of the intent, which
 * Stripe settled (or is settling) on confirmation.
 *
 * <p>Constructed PER {@code PaymentProcessorConfig} with that organization's keys. Every call carries its key
 * in {@link RequestOptions}; the SDK's process-global {@code Stripe.apiKey} is never set, because two
 * organizations' requests can be in flight at once ({@code ArchitectureTest} greps for it).
 *
 * <p>Outcome mapping, with the money rules in mind: {@code succeeded} with a real amount is the ONLY
 * COMPLETED; {@code processing} (a bank debit in flight) is the "settling" PENDING that tells the payer
 * nothing more is needed; the pre-confirmation statuses are the plain PENDING (the form is still open or
 * a card was declined and the payer may retry); {@code canceled} is FAILED. A transport failure on the read
 * is PENDING too: FAILED is terminal and leaves the reconciliation list, which would strand paid money.
 */
@Slf4j
public final class StripeProcessor implements PaymentProcessor {
    private static final String USD = "usd";
    private static final String TEST_MARKER = "_test_";
    private static final String LIVE_MARKER = "_live_";

    static {
        // Identifies the integration in Stripe's logs. Global by design and credential-free.
        Stripe.setAppInfo("Trip Manager", null, "https://unitetrip.com");
    }

    private final String publishableKey;
    private final String secretKey;
    private final String baseUrl;

    public StripeProcessor(final String publishableKey, final String secretKey, final boolean testMode) {
        this(publishableKey, secretKey, testMode, null);
    }

    /** Test seam: point the SDK at a local fake instead of api.stripe.com. */
    StripeProcessor(final String publishableKey, final String secretKey, final boolean testMode,
            final String baseUrl) {
        if (publishableKey == null || publishableKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new ProcessorException("Stripe keys are not configured.");
        }
        final String problem = keyModeProblem(publishableKey, testMode, "publishable");
        final String secretProblem = keyModeProblem(secretKey, testMode, "secret");
        if (problem != null) {
            throw new ProcessorException(problem);
        }
        if (secretProblem != null) {
            throw new ProcessorException(secretProblem);
        }
        this.publishableKey = publishableKey;
        this.secretKey = secretKey;
        this.baseUrl = baseUrl;
    }

    /**
     * Null when {@code key} belongs to the expected Stripe mode, else a human message. Stripe keys carry
     * their mode in the prefix ({@code sk_test_}, {@code pk_live_}, {@code rk_test_} for restricted keys);
     * the sandbox credential set must hold TEST keys and the live set LIVE keys, or a "sandbox" payment
     * would move real money.
     */
    static String keyModeProblem(final String key, final boolean testMode, final String kind) {
        if (key == null || key.isBlank()) {
            return null;
        }
        final boolean looksTest = key.contains(TEST_MARKER);
        final boolean looksLive = key.contains(LIVE_MARKER);
        if (!looksTest && !looksLive) {
            return "The Stripe " + kind + " key does not look like a Stripe key (expected sk_/pk_/rk_ plus "
                    + "_test_ or _live_).";
        }
        if (testMode && !looksTest) {
            return "The sandbox set needs a Stripe TEST-mode " + kind + " key (" + kind.charAt(0)
                    + "k_test_...); a live key was given.";
        }
        if (!testMode && !looksLive) {
            return "The live set needs a Stripe LIVE-mode " + kind + " key (" + kind.charAt(0)
                    + "k_live_...); a test key was given.";
        }
        return null;
    }

    /**
     * The "Test connection" probe: a balance read proves the secret key authenticates without creating
     * anything. Null on success, otherwise a short message for the growl.
     */
    static String pingKey(final String secretKey, final boolean testMode, final String baseUrl) {
        final String problem = keyModeProblem(secretKey, testMode, "secret");
        if (problem != null) {
            return problem;
        }
        try {
            Balance.retrieve(options(secretKey, baseUrl, null));
            return null;
        } catch (final AuthenticationException ex) {
            return "Stripe rejected the secret key (HTTP " + ex.getStatusCode() + ").";
        } catch (final ApiConnectionException ex) {
            return "Could not reach Stripe: " + ex.getMessage();
        } catch (final StripeException ex) {
            return "Stripe answered: " + ex.getMessage();
        }
    }

    /**
     * Registers {@code host} with the Stripe account behind {@code secretKey} so Apple Pay, Google Pay and
     * Link may render in the Payment Element on that host (Stripe checks the domain; for Apple Pay it also
     * fetches {@code /.well-known/apple-developer-merchantid-domain-association}, which every host here
     * serves). Idempotent at Stripe. Answers a one-line summary for the growl, or a failure message
     * prefixed "Stripe refused".
     */
    static String registerDomain(final String secretKey, final boolean testMode, final String host,
            final String baseUrl) {
        final String problem = keyModeProblem(secretKey, testMode, "secret");
        if (problem != null) {
            return "Stripe refused: " + problem;
        }
        if (host == null || host.isBlank() || host.contains("/") || host.contains(":")) {
            return "Stripe refused: '" + host + "' is not a bare host name.";
        }
        try {
            final PaymentMethodDomain domain = PaymentMethodDomain.create(
                    PaymentMethodDomainCreateParams.builder().setDomainName(host).setEnabled(true).build(),
                    options(secretKey, baseUrl, null));
            return "Registered " + domain.getDomainName() + " -- Apple Pay: " + walletStatus(domain.getApplePay())
                    + ", Google Pay: " + walletStatus(domain.getGooglePay()) + ", Link: "
                    + walletStatus(domain.getLink()) + ".";
        } catch (final StripeException ex) {
            return "Stripe refused: " + ex.getMessage();
        }
    }

    private static String walletStatus(final PaymentMethodDomain.ApplePay wallet) {
        return (wallet == null || wallet.getStatus() == null) ? "unknown" : wallet.getStatus();
    }

    private static String walletStatus(final PaymentMethodDomain.GooglePay wallet) {
        return (wallet == null || wallet.getStatus() == null) ? "unknown" : wallet.getStatus();
    }

    private static String walletStatus(final PaymentMethodDomain.Link wallet) {
        return (wallet == null || wallet.getStatus() == null) ? "unknown" : wallet.getStatus();
    }

    private static RequestOptions options(final String secretKey, final String baseUrl,
            final String idempotencyKey) {
        final RequestOptions.RequestOptionsBuilder builder = RequestOptions.builder().setApiKey(secretKey);
        if (baseUrl != null) {
            builder.setBaseUrl(baseUrl);
        }
        if (idempotencyKey != null) {
            builder.setIdempotencyKey(idempotencyKey);
        }
        return builder.build();
    }

    @Override
    public ProcessorType type() {
        return ProcessorType.STRIPE;
    }

    @Override
    public String displayName() {
        return "Stripe";
    }

    /**
     * Creates the PaymentIntent. Payment methods are NOT pinned here: the organization chooses them in its
     * Stripe dashboard ({@code automatic_payment_methods}), which is what lets a bank debit or a wallet
     * appear without a code change. The return URLs are unused by an inline processor (the page passes its
     * own to {@code confirmPayment}); the paymentId rides in the metadata so the webhook can find our row.
     */
    @Override
    public CreatedOrder createOrder(final Payment payment, final String description,
            final String returnUrl, final String cancelUrl) {
        final PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(payment.getTotalChargedCents())
                .setCurrency(USD)
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .build())
                .setDescription(truncate(description, 1000))
                .putMetadata("paymentId", payment.getPaymentId())
                .putMetadata("tripId", payment.getTripId())
                .putMetadata("sandbox", Boolean.toString(payment.isSandbox()))
                .build();
        final PaymentIntent intent;
        try {
            intent = PaymentIntent.create(params, options(secretKey, baseUrl, payment.getPaymentId()));
        } catch (final StripeException ex) {
            log.error("Stripe PaymentIntent create failed for payment {}", payment.getPaymentId(), ex);
            throw new ProcessorException("Stripe could not start the payment.", ex);
        }
        if (intent == null || intent.getId() == null || intent.getClientSecret() == null) {
            throw new ProcessorException("Stripe did not answer a usable payment intent.");
        }
        return CreatedOrder.inline(intent.getId(), publishableKey, intent.getClientSecret());
    }

    @Override
    public CaptureResult capture(final String orderRef) {
        final PaymentIntentRetrieveParams params = PaymentIntentRetrieveParams.builder()
                .addExpand("latest_charge.balance_transaction")
                .build();
        try {
            return verified(PaymentIntent.retrieve(orderRef, params, options(secretKey, baseUrl, null)));
        } catch (final InvalidRequestException ex) {
            // The intent does not exist on this account (or the id is malformed): definitive, not transient.
            log.error("Stripe PaymentIntent {} could not be read: {}", orderRef, ex.getMessage());
            return CaptureResult.failed();
        } catch (final ApiConnectionException | RateLimitException ex) {
            log.warn("Stripe unreachable while verifying {}: {}", orderRef, ex.getMessage());
            return CaptureResult.pending(null);
        } catch (final ApiException ex) {
            log.warn("Stripe answered {} while verifying {}: {}", ex.getStatusCode(), orderRef, ex.getMessage());
            return CaptureResult.pending(null);
        } catch (final StripeException ex) {
            log.error("Stripe verification of {} failed", orderRef, ex);
            return CaptureResult.failed();
        }
    }

    /** The one success shape: {@code succeeded} with a positive USD amount received; everything else is honest. */
    private static CaptureResult verified(final PaymentIntent intent) {
        if (intent == null || intent.getStatus() == null) {
            return CaptureResult.failed();
        }
        switch (intent.getStatus()) {
            case "succeeded" -> {
                final Long received = intent.getAmountReceived();
                if (received == null || received <= 0L || !USD.equalsIgnoreCase(intent.getCurrency())) {
                    log.error("Stripe intent {} succeeded with an unusable amount ({} {})", intent.getId(),
                            received, intent.getCurrency());
                    return CaptureResult.failed();
                }
                return new CaptureResult(CaptureResult.Status.COMPLETED, intent.getId(), received,
                        feeOf(intent));
            }
            case "processing" -> {
                return CaptureResult.pending(intent.getId());
            }
            case "requires_payment_method", "requires_confirmation", "requires_action", "requires_capture" -> {
                return CaptureResult.pending(null);
            }
            default -> {
                // "canceled", or a status this build does not know: never a success.
                log.warn("Stripe intent {} is {}: treated as failed", intent.getId(), intent.getStatus());
                return CaptureResult.failed();
            }
        }
    }

    private static OptionalLong feeOf(final PaymentIntent intent) {
        final Charge charge = intent.getLatestChargeObject();
        final BalanceTransaction balance = (charge == null) ? null : charge.getBalanceTransactionObject();
        return (balance == null || balance.getFee() == null) ? OptionalLong.empty()
                : OptionalLong.of(balance.getFee());
    }

    @Override
    public void cancel(final String orderRef) {
        if (orderRef == null || orderRef.isBlank()) {
            return;
        }
        try {
            final PaymentIntent intent = PaymentIntent.retrieve(orderRef, options(secretKey, baseUrl, null));
            intent.cancel(options(secretKey, baseUrl, null));
        } catch (final StripeException ex) {
            // Already canceled or already succeeded both land here; the caller's own state wins.
            log.info("Stripe PaymentIntent {} not canceled: {}", orderRef, ex.getMessage());
        }
    }

    /**
     * The cumulative refunded amount on the intent's latest charge, re-read from Stripe (never trusted from
     * the {@code charge.refunded} event itself). Empty when Stripe could not be reached or the intent has no
     * charge yet; the caller treats empty as "ask again", never as zero.
     */
    @Override
    public OptionalLong refundedCents(final String orderRef) {
        if (orderRef == null || orderRef.isBlank()) {
            return OptionalLong.empty();
        }
        final PaymentIntentRetrieveParams params = PaymentIntentRetrieveParams.builder()
                .addExpand("latest_charge")
                .build();
        try {
            final PaymentIntent intent = PaymentIntent.retrieve(orderRef, params, options(secretKey, baseUrl, null));
            final Charge charge = (intent == null) ? null : intent.getLatestChargeObject();
            if (charge == null || charge.getAmountRefunded() == null) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(charge.getAmountRefunded());
        } catch (final StripeException ex) {
            log.warn("Stripe refund read for {} failed: {}", orderRef, ex.getMessage());
            return OptionalLong.empty();
        }
    }

    /**
     * Re-prices an unconfirmed intent (the payer picked a bank debit, whose fee is lower). Stripe only
     * accepts a new amount while the intent is still awaiting a payment method or confirmation, which is
     * exactly when the page asks; anything else surfaces as a refusal and the page keeps the old total.
     */
    @Override
    public boolean updateAmount(final String orderRef, final long cents) {
        if (orderRef == null || orderRef.isBlank() || cents <= 0) {
            throw new ProcessorException("Stripe could not re-price the payment.");
        }
        try {
            final PaymentIntent intent = PaymentIntent.retrieve(orderRef, options(secretKey, baseUrl, null));
            intent.update(PaymentIntentUpdateParams.builder().setAmount(cents).build(),
                    options(secretKey, baseUrl, null));
            return true;
        } catch (final StripeException ex) {
            log.warn("Stripe PaymentIntent {} could not be re-priced to {}: {}", orderRef, cents, ex.getMessage());
            throw new ProcessorException("Stripe could not re-price the payment.", ex);
        }
    }

    private static String truncate(final String text, final int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
