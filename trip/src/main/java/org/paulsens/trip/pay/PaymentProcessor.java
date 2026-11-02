package org.paulsens.trip.pay;

import java.util.OptionalLong;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.ProcessorType;

/**
 * One payment processor integration, instantiated per {@code PaymentProcessorConfig} (credentials injected
 * at construction -- never process-global env vars, which cannot be multi-tenant). Two shapes share the
 * SPI: REDIRECT (PayPal, the fake) -- create an order, send the payer to {@code approvalUrl}, capture on
 * return -- and INLINE (Stripe) -- create a PaymentIntent and hand its client secret to the page's Payment
 * Element or the apps' PaymentSheet, which confirm it in place; the "capture" is then a verification read.
 * A future record-only kind (Zeffy) simply never answers a capture.
 */
public interface PaymentProcessor {

    ProcessorType type();

    /** Human label for descriptions and email ("PayPal", "Stripe", "Test Processor"). */
    String displayName();

    /**
     * Creates the processor-side order for {@code payment.getTotalChargedCents()} and answers where to send
     * the payer. Throws {@link ProcessorException} on refusal -- the caller surfaces it, nothing is charged.
     */
    CreatedOrder createOrder(Payment payment, String description, String returnUrl, String cancelUrl);

    /**
     * Verifies and captures the approved order. MUST answer {@link CaptureResult.Status#COMPLETED} only for
     * a verified, completed capture with a real capture id and amount -- a parse failure or a non-completed
     * order is FAILED/PENDING, never a $0 success (the mir2026 gap this interface exists to close).
     */
    CaptureResult capture(String orderRef);

    /**
     * Gives up an order the payer walked away from (page Cancel, "Change amounts", the admin's Abandon).
     * Redirect processors have nothing to release; an inline one cancels its PaymentIntent so the payer's
     * saved form can never complete it later. Failures are logged by the implementation, never thrown: the
     * local CANCELLED transition does not depend on the processor agreeing.
     */
    default void cancel(final String orderRef) {
    }

    /**
     * How much of a captured order the processor has REFUNDED so far (cumulative, cents), re-read from the
     * processor rather than trusted from any event. Empty = unknown right now (the processor could not be
     * reached, or this kind has no refund read), which a caller treats as "ask again later", never as zero.
     * The default is the "no refund read" answer every redirect processor gives today.
     */
    default OptionalLong refundedCents(final String orderRef) {
        return OptionalLong.empty();
    }

    /**
     * Re-prices a not-yet-confirmed order (the payer picked a payment method whose fee differs, so the
     * grossed-up total changed). True when the processor accepted the new amount; false when this kind has
     * no re-pricing (a redirect processor, whose amount is final once the payer is sent off). Throws
     * {@link ProcessorException} when the processor refused or could not be reached, so the caller keeps the
     * old total rather than showing a figure the processor will not charge.
     */
    default boolean updateAmount(final String orderRef, final long cents) {
        return false;
    }

    /**
     * The processor order reference plus HOW the payer completes it: {@code approvalUrl} for a redirect
     * processor, {@code inline} for one whose UI runs inside our page or app. Exactly one is non-null.
     */
    record CreatedOrder(String orderRef, String approvalUrl, Inline inline) {
        /** A redirect-shaped order (the original two-argument form every redirect processor uses). */
        public CreatedOrder(final String orderRef, final String approvalUrl) {
            this(orderRef, approvalUrl, null);
        }

        public static CreatedOrder redirect(final String orderRef, final String approvalUrl) {
            return new CreatedOrder(orderRef, approvalUrl, null);
        }

        /** A Stripe-shaped inline order: the page loads Stripe.js from Stripe (never self-hosted, per Stripe). */
        public static CreatedOrder inline(final String orderRef, final String publishableKey,
                final String clientSecret) {
            return new CreatedOrder(orderRef, null, new Inline(publishableKey, clientSecret, Inline.STRIPE_JS));
        }

        /** An inline order whose browser script is {@code scriptUrl}; null = the page's local fake. */
        public static CreatedOrder inline(final String orderRef, final String publishableKey,
                final String clientSecret, final String scriptUrl) {
            return new CreatedOrder(orderRef, null, new Inline(publishableKey, clientSecret, scriptUrl));
        }

        public boolean isInline() {
            return inline != null;
        }

        /**
         * What an in-page / in-app payment UI needs: the account's publishable key, the intent's client
         * secret, and the browser script that mounts the form. Neither key is a secret in the API-key sense
         * (both are designed to reach the browser), but the client secret is still per-payment and must only
         * go to the payer who started it.
         */
        public record Inline(String publishableKey, String clientSecret, String scriptUrl) {
            public static final String STRIPE_JS = "https://js.stripe.com/v3/";
        }
    }

    /**
     * The capture outcome. {@code grossCents} is the processor-verified captured amount;
     * {@code actualFeeCents} is present when the processor reported its fee (often absent in sandboxes).
     *
     * <p>PENDING has two meanings, told apart by {@code captureId}: with a reference, the payer's payment
     * method was ACCEPTED and settlement is outstanding (a bank debit in flight -- nothing more is needed
     * from the payer, the webhook finishes it); with none, the payer simply has not finished at the
     * processor yet (approved but uncaptured, or the form is still open) and may retry.
     */
    record CaptureResult(Status status, String captureId, long grossCents, OptionalLong actualFeeCents) {
        public enum Status {
            COMPLETED, ALREADY_CAPTURED, PENDING, FAILED
        }

        public static CaptureResult failed() {
            return new CaptureResult(Status.FAILED, null, 0L, OptionalLong.empty());
        }

        /** @param reference the processor's id for the in-flight payment, or null when nothing was accepted yet. */
        public static CaptureResult pending(final String reference) {
            return new CaptureResult(Status.PENDING, reference, 0L, OptionalLong.empty());
        }

        /** Whether this is the "accepted, settling" flavor of PENDING (see the class javadoc). */
        public boolean isSettling() {
            return status == Status.PENDING && captureId != null && !captureId.isBlank();
        }
    }

    /** A processor refusal with a payer-safe message. */
    class ProcessorException extends RuntimeException {
        public ProcessorException(final String message) {
            super(message);
        }

        public ProcessorException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
