package org.paulsens.trip.pay;

import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.ProcessorType;

/**
 * The local-mode processor: drives the full page &rarr; approve &rarr; capture &rarr; ledger flow with zero
 * network, so webtests and laptop runs exercise the REAL payment machinery. The "checkout" is
 * {@code trip/fakeCheckout.xhtml}, which just bounces to the return or cancel URL. Orders live in a static
 * map (one per JVM/webapp classloader, like the local photo store); fees mirror PayPal so the math on screen
 * is realistic.
 */
public final class FakeProcessor implements PaymentProcessor {
    /** orderRef -> the cents the order was created for; consulted at capture like a real processor would. */
    private static final Map<String, Long> ORDERS = new ConcurrentHashMap<>();

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
        ORDERS.put(orderRef, payment.getTotalChargedCents());
        // The fake checkout page carries the real return/cancel URLs through as query params.
        final String url = "/trip/fakeCheckout.jsf?token=" + orderRef
                + "&return=" + java.net.URLEncoder.encode(returnUrl, java.nio.charset.StandardCharsets.UTF_8)
                + "&cancel=" + java.net.URLEncoder.encode(cancelUrl, java.nio.charset.StandardCharsets.UTF_8);
        return new CreatedOrder(orderRef, url);
    }

    @Override
    public CaptureResult capture(final String orderRef) {
        final Long cents = ORDERS.get(orderRef);
        if (cents == null) {
            return CaptureResult.failed();
        }
        final long fee = MoneyMath.donationFeeCents(cents, ProcessorType.FAKE.getDefaultFeeBps())
                + ProcessorType.FAKE.getDefaultFeeFixedCents();
        return new CaptureResult(CaptureResult.Status.COMPLETED, "CAP-" + orderRef.substring(5),
                cents, OptionalLong.of(fee));
    }
}
