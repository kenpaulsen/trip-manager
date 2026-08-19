package org.paulsens.trip.pay;

import com.paypal.sdk.Environment;
import com.paypal.sdk.PaypalServerSdkClient;
import com.paypal.sdk.authentication.ClientCredentialsAuthModel;
import com.paypal.sdk.exceptions.ApiException;
import com.paypal.sdk.models.AmountWithBreakdown;
import com.paypal.sdk.models.CaptureOrderInput;
import com.paypal.sdk.models.CheckoutPaymentIntent;
import com.paypal.sdk.models.CreateOrderInput;
import com.paypal.sdk.models.GetOrderInput;
import com.paypal.sdk.models.LinkDescription;
import com.paypal.sdk.models.Order;
import com.paypal.sdk.models.OrderApplicationContext;
import com.paypal.sdk.models.OrderRequest;
import com.paypal.sdk.models.OrderStatus;
import com.paypal.sdk.models.OrdersCapture;
import com.paypal.sdk.models.PurchaseUnitRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.ProcessorType;

/**
 * PayPal Checkout (orders v2) behind the {@link PaymentProcessor} SPI -- constructed PER
 * {@code PaymentProcessorConfig} with that config's credentials (multi-tenant; the env-var singleton
 * {@link PayPalClient} is the legacy flow awaiting deletion).
 *
 * <p>{@link #capture} closes the prototype's known gap: it answers COMPLETED only for an order whose status
 * IS completed and whose first capture carries a real id and amount -- anything unparsable is FAILED, never a
 * $0 success. A 422 {@code ORDER_ALREADY_CAPTURED} (the double-click/retry shape) re-reads the order and
 * recovers the original capture, so retries converge instead of erroring.
 */
@Slf4j
public final class PayPalProcessor implements PaymentProcessor {
    private static final String USD = "USD";

    private final PaypalServerSdkClient sdk;

    public PayPalProcessor(final String clientId, final String clientSecret, final boolean sandbox) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new ProcessorException("PayPal credentials are not configured.");
        }
        this.sdk = new PaypalServerSdkClient.Builder()
                .clientCredentialsAuth(new ClientCredentialsAuthModel.Builder(clientId, clientSecret).build())
                .environment(sandbox ? Environment.SANDBOX : Environment.PRODUCTION)
                .build();
    }

    /** Test seam: hand in a stubbed SDK client. */
    PayPalProcessor(final PaypalServerSdkClient sdk) {
        this.sdk = sdk;
    }

    @Override
    public ProcessorType type() {
        return ProcessorType.PAYPAL;
    }

    @Override
    public String displayName() {
        return "PayPal";
    }

    @Override
    public CreatedOrder createOrder(final Payment payment, final String description,
            final String returnUrl, final String cancelUrl) {
        final OrderRequest request = new OrderRequest.Builder()
                .intent(CheckoutPaymentIntent.CAPTURE)
                .purchaseUnits(List.of(new PurchaseUnitRequest.Builder()
                        .referenceId(payment.getPaymentId())
                        .customId(payment.getPaymentId())
                        .invoiceId(payment.getPaymentId())
                        .description(truncate(description, 127))
                        .amount(new AmountWithBreakdown.Builder()
                                .currencyCode(USD)
                                .value(centsToValue(payment.getTotalChargedCents()))
                                .build())
                        .build()))
                .applicationContext(new OrderApplicationContext.Builder()
                        .locale("en-US")
                        .returnUrl(returnUrl)
                        .cancelUrl(cancelUrl)
                        .build())
                .build();
        final Order order;
        try {
            order = sdk.getOrdersController()
                    .createOrder(new CreateOrderInput.Builder().body(request).build()).getResult();
        } catch (final ApiException | IOException ex) {
            log.error("PayPal createOrder failed for payment {}", payment.getPaymentId(), ex);
            throw new ProcessorException("PayPal could not start the payment.", ex);
        }
        final String approval = (order == null || order.getLinks() == null) ? null
                : order.getLinks().stream()
                        .filter(link -> "approve".equals(link.getRel()))
                        .map(LinkDescription::getHref)
                        .findFirst().orElse(null);
        if (order == null || order.getId() == null || approval == null) {
            throw new ProcessorException("PayPal did not answer an approval link.");
        }
        return new CreatedOrder(order.getId(), approval);
    }

    @Override
    public CaptureResult capture(final String orderRef) {
        try {
            final Order order = sdk.getOrdersController()
                    .captureOrder(new CaptureOrderInput.Builder().id(orderRef).build()).getResult();
            return verified(order, CaptureResult.Status.COMPLETED);
        } catch (final ApiException ex) {
            if (isAlreadyCaptured(ex)) {
                return recoverAlreadyCaptured(orderRef);
            }
            log.error("PayPal capture failed for order {}", orderRef, ex);
            return CaptureResult.failed();
        } catch (final IOException ex) {
            log.error("PayPal capture transport failure for order {}", orderRef, ex);
            return CaptureResult.failed();
        }
    }

    /** The safe-retry path: the money already moved once; re-read the order and report THAT capture. */
    private CaptureResult recoverAlreadyCaptured(final String orderRef) {
        try {
            final Order order = sdk.getOrdersController()
                    .getOrder(new GetOrderInput.Builder().id(orderRef).build()).getResult();
            return verified(order, CaptureResult.Status.ALREADY_CAPTURED);
        } catch (final ApiException | IOException ex) {
            log.error("PayPal already-captured recovery failed for order {}", orderRef, ex);
            return CaptureResult.failed();
        }
    }

    /**
     * The verification the prototype lacked: completed order + a capture with a REAL id and amount, or no
     * deal. An approved-but-uncaptured order answers PENDING so the caller can leave the payment retryable.
     */
    private CaptureResult verified(final Order order, final CaptureResult.Status successStatus) {
        if (order == null) {
            return CaptureResult.failed();
        }
        final OrdersCapture capture = firstCapture(order);
        if (order.getStatus() != OrderStatus.COMPLETED || capture == null) {
            log.warn("PayPal order {} not completed (status {}, capture {})",
                    order.getId(), order.getStatus(), capture != null);
            return (order.getStatus() == OrderStatus.APPROVED)
                    ? new CaptureResult(CaptureResult.Status.PENDING, null, 0L, OptionalLong.empty())
                    : CaptureResult.failed();
        }
        final String captureId = capture.getId();
        final Long grossCents = parseCents(capture.getAmount() == null ? null
                : capture.getAmount().getValue());
        if (captureId == null || captureId.isBlank() || grossCents == null || grossCents <= 0L) {
            log.error("PayPal order {} capture unparsable (id {}, amount {})", order.getId(), captureId,
                    capture.getAmount() == null ? null : capture.getAmount().getValue());
            return CaptureResult.failed();
        }
        final OptionalLong fee = Optional.ofNullable(capture.getSellerReceivableBreakdown())
                .map(breakdown -> breakdown.getPaypalFee())
                .map(money -> parseCents(money.getValue()))
                .map(cents -> cents == null ? OptionalLong.empty() : OptionalLong.of(cents))
                .orElse(OptionalLong.empty());
        return new CaptureResult(successStatus, captureId, grossCents, fee);
    }

    private static OrdersCapture firstCapture(final Order order) {
        if (order.getPurchaseUnits() == null || order.getPurchaseUnits().isEmpty()
                || order.getPurchaseUnits().getFirst().getPayments() == null
                || order.getPurchaseUnits().getFirst().getPayments().getCaptures() == null
                || order.getPurchaseUnits().getFirst().getPayments().getCaptures().isEmpty()) {
            return null;
        }
        return order.getPurchaseUnits().getFirst().getPayments().getCaptures().getFirst();
    }

    static boolean isAlreadyCaptured(final ApiException ex) {
        final String body = String.valueOf(ex.getMessage());
        return ex.getResponseCode() == 422 && body.contains("ORDER_ALREADY_CAPTURED");
    }

    static String centsToValue(final long cents) {
        return String.format("%d.%02d", cents / 100, cents % 100);
    }

    static Long parseCents(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value).movePointRight(2).longValueExact();
        } catch (final ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }

    private static String truncate(final String text, final int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
