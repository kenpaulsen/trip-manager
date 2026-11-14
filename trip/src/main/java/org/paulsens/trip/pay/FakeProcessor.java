package org.paulsens.trip.pay;

import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.PaymentProcessorConfig;
import org.paulsens.trip.model.ProcessorType;

/**
 * The local-mode processor: drives the full page &rarr; approve &rarr; capture &rarr; ledger flow with zero
 * network, so webtests and laptop runs exercise the REAL payment machinery. Two shapes, picked per config:
 * REDIRECT (the default) sends the payer to {@code trip/fakeCheckout.xhtml}, which just bounces to the return
 * or cancel URL like PayPal; INLINE ({@code publicConfig.inline=true}) answers a client secret like Stripe,
 * and the page mounts {@code trip-js/fakeStripe.js} in place of Stripe.js. The fake checkout page doubles as
 * the fake processor's back end for the inline flow: "pay as bank" marks the order async (a settling PENDING,
 * what a US bank debit looks like) and "settle" is the stand-in for Stripe's webhook.
 *
 * <p>Orders live in a static map (one per JVM/webapp classloader, like the local photo store); fees mirror
 * PayPal so the math on screen is realistic.
 */
public final class FakeProcessor implements PaymentProcessor {
    /** orderRef -> the order as created; consulted at capture like a real processor would. */
    private static final Map<String, Order> ORDERS = new ConcurrentHashMap<>();
    /** The orders whose capture reports one cent less than the order was for (see {@link #captureShort}). */
    private static final Set<String> SHORT_CAPTURES = ConcurrentHashMap.newKeySet();

    /**
     * {@code bank} = the payer paid "as bank" (the fake checkout's async door) at any point; {@code onlyMethod}
     * = the one method a re-price locked the order to, null for none.
     */
    private record Order(long cents, String paymentId, boolean async, long refundedCents, boolean bank,
            String onlyMethod) {
        Order(final long cents, final String paymentId) {
            this(cents, paymentId, false, 0L, false, null);
        }

        String methodType() {
            return bank ? BANK : "card";
        }
    }

    /** Stripe's name for a US bank debit: the one method the fake's bank pricing is for. */
    private static final String BANK = "us_bank_account";

    private final boolean inline;

    /** The redirect-shaped fake (the PayPal stand-in). */
    public FakeProcessor() {
        this(false);
    }

    public FakeProcessor(final boolean inline) {
        this.inline = inline;
    }

    /** Whether a FAKE config asks for the inline (Stripe-shaped) fake: {@code publicConfig.inline=true}. */
    public static boolean isInline(final PaymentProcessorConfig config) {
        return config != null && "true".equalsIgnoreCase(config.getPublicConfig().get("inline"));
    }

    @Override
    public ProcessorType type() {
        return ProcessorType.FAKE;
    }

    @Override
    public CreatedOrder createOrder(final Payment payment, final String description,
            final String returnUrl, final String cancelUrl) {
        final String orderRef = "FAKE-" + UUID.randomUUID();
        ORDERS.put(orderRef, new Order(payment.getTotalChargedCents(), payment.getPaymentId()));
        if (inline) {
            // The order ref doubles as the "client secret"; null script = the page's own fake Stripe.js.
            return CreatedOrder.inline(orderRef, "pk_fake", orderRef, null);
        }
        // The fake checkout page carries the real return/cancel URLs through as query params.
        final String url = "/trip/fakeCheckout.jsf?token=" + orderRef
                + "&return=" + java.net.URLEncoder.encode(returnUrl, java.nio.charset.StandardCharsets.UTF_8)
                + "&cancel=" + java.net.URLEncoder.encode(cancelUrl, java.nio.charset.StandardCharsets.UTF_8);
        return CreatedOrder.redirect(orderRef, url);
    }

    @Override
    public CaptureResult capture(final String orderRef) {
        final Order order = ORDERS.get(orderRef);
        if (order == null) {
            return CaptureResult.failed();
        }
        if (order.async()) {
            return CaptureResult.pending("FAKE-PI-" + orderRef.substring(5));
        }
        if (order.onlyMethod() != null && !order.onlyMethod().equals(order.methodType())) {
            // What Stripe does with a card on a bank-locked intent: it refuses the confirm, so nothing was paid.
            return CaptureResult.pending(null);
        }
        final long fee = MoneyMath.donationFeeCents(order.cents(), ProcessorType.FAKE.getDefaultFeeBps())
                + ProcessorType.FAKE.getDefaultFeeFixedCents();
        final long gross = SHORT_CAPTURES.contains(orderRef) ? order.cents() - 1 : order.cents();
        return new CaptureResult(CaptureResult.Status.COMPLETED, "CAP-" + orderRef.substring(5),
                gross, OptionalLong.of(fee), order.methodType());
    }

    /** Like Stripe: a debit in flight cannot be canceled; anything else is released. */
    @Override
    public OrderState cancel(final String orderRef) {
        final Order order = (orderRef == null) ? null : ORDERS.get(orderRef);
        if (order != null && order.async()) {
            return OrderState.SETTLING;
        }
        if (order != null) {
            ORDERS.remove(orderRef);
        }
        return OrderState.OPEN;
    }

    /** "Pay as bank": the order is accepted but settling; captures answer the settling PENDING until settled. */
    public static boolean markAsync(final String orderRef) {
        final Order order = (orderRef == null) ? null : ORDERS.get(orderRef);
        if (order == null) {
            return false;
        }
        ORDERS.put(orderRef, new Order(order.cents(), order.paymentId(), true, order.refundedCents(), true,
                order.onlyMethod()));
        return true;
    }

    /**
     * A processor that captures a different amount than it was asked for: from now on this order's capture
     * reports one cent less, so completing it takes the HELD path (the reconciliation page's "Held:" line) the way
     * a real gross-vs-expected mismatch does. Answers false for an unknown order.
     */
    public static boolean captureShort(final String orderRef) {
        if (orderRef == null || !ORDERS.containsKey(orderRef)) {
            return false;
        }
        SHORT_CAPTURES.add(orderRef);
        return true;
    }

    /** The webhook stand-in: the bank debit cleared. Answers the payment id to complete, or null. */
    public static String settle(final String orderRef) {
        final Order order = (orderRef == null) ? null : ORDERS.get(orderRef);
        if (order == null) {
            return null;
        }
        ORDERS.put(orderRef, new Order(order.cents(), order.paymentId(), false, order.refundedCents(), order.bank(),
                order.onlyMethod()));
        return order.paymentId();
    }

    /**
     * The refund stand-in (what a dashboard refund plus Stripe's {@code charge.refunded} event amount to):
     * the order's CUMULATIVE refunded total becomes {@code cents}. Answers the payment id, or null.
     */
    public static String refund(final String orderRef, final long cents) {
        final Order order = (orderRef == null) ? null : ORDERS.get(orderRef);
        if (order == null || cents < 0 || cents > order.cents()) {
            return null;
        }
        ORDERS.put(orderRef, new Order(order.cents(), order.paymentId(), order.async(), cents, order.bank(),
                order.onlyMethod()));
        return order.paymentId();
    }

    @Override
    public OptionalLong refundedCents(final String orderRef) {
        final Order order = (orderRef == null) ? null : ORDERS.get(orderRef);
        return (order == null) ? OptionalLong.empty() : OptionalLong.of(order.refundedCents());
    }

    /** Re-prices like Stripe would, locking the order to {@code onlyMethod} when the price is for one method. */
    @Override
    public Repriced reprice(final String orderRef, final long cents, final String onlyMethod) {
        final Order order = (orderRef == null) ? null : ORDERS.get(orderRef);
        if (order == null || cents <= 0) {
            throw new ProcessorException("The test processor could not re-price the payment.");
        }
        ORDERS.put(orderRef, new Order(cents, order.paymentId(), order.async(), order.refundedCents(), order.bank(),
                onlyMethod));
        return (onlyMethod == null) ? Repriced.REPRICED : Repriced.REPRICED_LOCKED;
    }
}
