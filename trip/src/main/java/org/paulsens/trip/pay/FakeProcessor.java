package org.paulsens.trip.pay;

import java.util.Map;
import java.util.OptionalLong;
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

    private record Order(long cents, String paymentId, boolean async) {
    }

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
    public String displayName() {
        return "Test Processor";
    }

    @Override
    public CreatedOrder createOrder(final Payment payment, final String description,
            final String returnUrl, final String cancelUrl) {
        final String orderRef = "FAKE-" + UUID.randomUUID();
        ORDERS.put(orderRef, new Order(payment.getTotalChargedCents(), payment.getPaymentId(), false));
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
        final long fee = MoneyMath.donationFeeCents(order.cents(), ProcessorType.FAKE.getDefaultFeeBps())
                + ProcessorType.FAKE.getDefaultFeeFixedCents();
        return new CaptureResult(CaptureResult.Status.COMPLETED, "CAP-" + orderRef.substring(5),
                order.cents(), OptionalLong.of(fee));
    }

    @Override
    public void cancel(final String orderRef) {
        if (orderRef != null) {
            ORDERS.remove(orderRef);
        }
    }

    /** "Pay as bank": the order is accepted but settling; captures answer the settling PENDING until settled. */
    public static boolean markAsync(final String orderRef) {
        final Order order = (orderRef == null) ? null : ORDERS.get(orderRef);
        if (order == null) {
            return false;
        }
        ORDERS.put(orderRef, new Order(order.cents(), order.paymentId(), true));
        return true;
    }

    /** The webhook stand-in: the bank debit cleared. Answers the payment id to complete, or null. */
    public static String settle(final String orderRef) {
        final Order order = (orderRef == null) ? null : ORDERS.get(orderRef);
        if (order == null) {
            return null;
        }
        ORDERS.put(orderRef, new Order(order.cents(), order.paymentId(), false));
        return order.paymentId();
    }
}
