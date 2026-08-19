package org.paulsens.trip.pay;

import java.util.OptionalLong;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.ProcessorType;

/**
 * One payment processor integration, instantiated per {@code PaymentProcessorConfig} (credentials injected
 * at construction -- never process-global env vars, which cannot be multi-tenant). Redirect-shaped: create
 * an order, send the payer to {@code approvalUrl}, capture on return. A future record-only kind (Zeffy)
 * simply never answers a capture.
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

    /** The processor order/session reference plus the URL the payer must approve at. */
    record CreatedOrder(String orderRef, String approvalUrl) {
    }

    /**
     * The capture outcome. {@code grossCents} is the processor-verified captured amount;
     * {@code actualFeeCents} is present when the processor reported its fee (often absent in sandboxes).
     */
    record CaptureResult(Status status, String captureId, long grossCents, OptionalLong actualFeeCents) {
        public enum Status {
            COMPLETED, ALREADY_CAPTURED, PENDING, FAILED
        }

        public static CaptureResult failed() {
            return new CaptureResult(Status.FAILED, null, 0L, OptionalLong.empty());
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
