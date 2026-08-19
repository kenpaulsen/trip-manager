package org.paulsens.trip.pay;

import com.paypal.sdk.PaypalServerSdkClient;
import com.paypal.sdk.controllers.OrdersController;
import com.paypal.sdk.exceptions.ApiException;
import com.paypal.sdk.http.response.ApiResponse;
import com.paypal.sdk.models.LinkDescription;
import com.paypal.sdk.models.Money;
import com.paypal.sdk.models.Order;
import com.paypal.sdk.models.OrderStatus;
import com.paypal.sdk.models.OrdersCapture;
import com.paypal.sdk.models.PaymentCollection;
import com.paypal.sdk.models.PurchaseUnit;
import com.paypal.sdk.models.SellerReceivableBreakdown;
import java.util.List;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ProcessorType;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/**
 * The capture-verification rules -- the regression tests for the prototype's known gap (a parse failure
 * used to write a $0 transaction and report success).
 */
public class PayPalProcessorTest {

    @Test
    public void credentialsAreRequiredToConstruct() {
        assertThrows(PaymentProcessor.ProcessorException.class,
                () -> new PayPalProcessor(null, "secret", true));
        assertThrows(PaymentProcessor.ProcessorException.class,
                () -> new PayPalProcessor("id", "  ", true));
        assertEquals(new PayPalProcessor("id", "secret", true).type(), ProcessorType.PAYPAL);
        assertEquals(new PayPalProcessor("id", "secret", false).displayName(), "PayPal");
    }

    @Test
    public void createOrderAnswersTheApprovalLinkOrRefuses() throws Exception {
        final Order order = new Order.Builder()
                .id("ORDER-1")
                .links(List.of(new LinkDescription.Builder().rel("approve")
                        .href("https://paypal.example/approve").build()))
                .build();
        final ApiResponse<Order> orderResponse = response(order);
        final PayPalProcessor processor = processorAnswering(controller -> Mockito
                .when(controller.createOrder(ArgumentMatchers.any())).thenReturn(orderResponse));
        final PaymentProcessor.CreatedOrder created =
                processor.createOrder(payment(), "desc", "http://r", "http://c");
        assertEquals(created.orderRef(), "ORDER-1");
        assertEquals(created.approvalUrl(), "https://paypal.example/approve");

        final Order noApprove = new Order.Builder().id("ORDER-2").links(List.of()).build();
        final ApiResponse<Order> noApproveResponse = response(noApprove);
        final PayPalProcessor refusing = processorAnswering(controller -> Mockito
                .when(controller.createOrder(ArgumentMatchers.any())).thenReturn(noApproveResponse));
        assertThrows(PaymentProcessor.ProcessorException.class,
                () -> refusing.createOrder(payment(), "desc", "http://r", "http://c"));
    }

    /** THE regression: a completed order with a real capture id and amount is the ONLY success shape. */
    @Test
    public void captureVerifiesCompletedWithARealCapture() throws Exception {
        final PayPalProcessor processor = capturing(completedOrder("CAP-9", "2500.00", "125.00"));
        final PaymentProcessor.CaptureResult result = processor.capture("ORDER-1");
        assertEquals(result.status(), PaymentProcessor.CaptureResult.Status.COMPLETED);
        assertEquals(result.captureId(), "CAP-9");
        assertEquals(result.grossCents(), 250000L);
        assertEquals(result.actualFeeCents().getAsLong(), 12500L);
    }

    @Test
    public void nonCompletedAndUnparsableCapturesNeverSucceedAtZero() throws Exception {
        // Approved but not captured: PENDING (retryable), not a success and never $0.
        final Order approved = new Order.Builder().id("O").status(OrderStatus.APPROVED).build();
        assertEquals(capturing(approved).capture("O").status(),
                PaymentProcessor.CaptureResult.Status.PENDING);

        // Completed but no capture id: FAILED.
        assertEquals(capturing(completedOrder(null, "10.00", null)).capture("O").status(),
                PaymentProcessor.CaptureResult.Status.FAILED);

        // Completed but garbage amount: FAILED.
        assertEquals(capturing(completedOrder("CAP-1", "not-a-number", null)).capture("O").status(),
                PaymentProcessor.CaptureResult.Status.FAILED);

        // Null order: FAILED.
        assertEquals(capturing(null).capture("O").status(),
                PaymentProcessor.CaptureResult.Status.FAILED);
    }

    @Test
    public void alreadyCapturedRecoversTheOriginalCapture() throws Exception {
        final ApiException alreadyCaptured = Mockito.mock(ApiException.class);
        Mockito.when(alreadyCaptured.getResponseCode()).thenReturn(422);
        Mockito.when(alreadyCaptured.getMessage()).thenReturn("{\"issue\":\"ORDER_ALREADY_CAPTURED\"}");
        assertTrue(PayPalProcessor.isAlreadyCaptured(alreadyCaptured));

        final ApiResponse<Order> recovered = response(completedOrder("CAP-OLD", "50.00", null));
        final PayPalProcessor processor = processorAnswering(controller -> {
            Mockito.when(controller.captureOrder(ArgumentMatchers.any())).thenThrow(alreadyCaptured);
            Mockito.when(controller.getOrder(ArgumentMatchers.any())).thenReturn(recovered);
        });
        final PaymentProcessor.CaptureResult result = processor.capture("ORDER-1");
        assertEquals(result.status(), PaymentProcessor.CaptureResult.Status.ALREADY_CAPTURED);
        assertEquals(result.captureId(), "CAP-OLD");
        assertEquals(result.grossCents(), 5000L);
    }

    @Test
    public void otherApiFailuresJustFail() throws Exception {
        final ApiException refused = Mockito.mock(ApiException.class);
        Mockito.when(refused.getResponseCode()).thenReturn(401);
        Mockito.when(refused.getMessage()).thenReturn("unauthorized");
        assertEquals(processorAnswering(c -> Mockito.when(c.captureOrder(ArgumentMatchers.any()))
                        .thenThrow(refused)).capture("O").status(),
                PaymentProcessor.CaptureResult.Status.FAILED);
    }

    @Test
    public void transportFailuresAreContainedEverywhere() throws Exception {
        final java.io.IOException transport = new java.io.IOException("no route");
        assertEquals(processorAnswering(c -> Mockito.when(c.captureOrder(ArgumentMatchers.any()))
                        .thenThrow(transport)).capture("O").status(),
                PaymentProcessor.CaptureResult.Status.FAILED);
        final PayPalProcessor broken = processorAnswering(c -> Mockito
                .when(c.createOrder(ArgumentMatchers.any())).thenThrow(transport));
        assertThrows(PaymentProcessor.ProcessorException.class,
                () -> broken.createOrder(payment(), "a".repeat(300), "http://r", "http://c"));

        // Already-captured whose recovery ALSO fails: FAILED, never a guess.
        final ApiException alreadyCaptured = Mockito.mock(ApiException.class);
        Mockito.when(alreadyCaptured.getResponseCode()).thenReturn(422);
        Mockito.when(alreadyCaptured.getMessage()).thenReturn("ORDER_ALREADY_CAPTURED");
        assertEquals(processorAnswering(c -> {
            Mockito.when(c.captureOrder(ArgumentMatchers.any())).thenThrow(alreadyCaptured);
            Mockito.when(c.getOrder(ArgumentMatchers.any())).thenThrow(transport);
        }).capture("O").status(), PaymentProcessor.CaptureResult.Status.FAILED);
    }

    @Test
    public void aCompletedOrderWithNoCapturesFails() throws Exception {
        final Order noCaptures = new Order.Builder().id("O").status(OrderStatus.COMPLETED)
                .purchaseUnits(List.of(new PurchaseUnit.Builder().build())).build();
        assertEquals(capturing(noCaptures).capture("O").status(),
                PaymentProcessor.CaptureResult.Status.FAILED);
    }

    @Test
    public void moneyParsingHelpers() {
        assertEquals(PayPalProcessor.centsToValue(250000L), "2500.00");
        assertEquals(PayPalProcessor.centsToValue(5L), "0.05");
        assertEquals(PayPalProcessor.parseCents("2500.00"), Long.valueOf(250000L));
        assertNull(PayPalProcessor.parseCents("abc"));
        assertNull(PayPalProcessor.parseCents(null));
        assertNull(PayPalProcessor.parseCents("1.005"), "Sub-cent precision cannot round silently");
    }

    // ------------------------------------------------------------------ fixtures

    private interface ControllerStubbing {
        void stub(OrdersController controller) throws Exception;
    }

    private static PayPalProcessor processorAnswering(final ControllerStubbing stubbing) throws Exception {
        final PaypalServerSdkClient sdk = Mockito.mock(PaypalServerSdkClient.class);
        final OrdersController controller = Mockito.mock(OrdersController.class);
        Mockito.when(sdk.getOrdersController()).thenReturn(controller);
        stubbing.stub(controller);
        return new PayPalProcessor(sdk);
    }

    /** Capture-stub shorthand that builds the response mock BEFORE the when() (Mockito nesting rule). */
    private static PayPalProcessor capturing(final Order order) throws Exception {
        final ApiResponse<Order> prebuilt = response(order);
        return processorAnswering(controller ->
                Mockito.when(controller.captureOrder(ArgumentMatchers.any())).thenReturn(prebuilt));
    }

    @SuppressWarnings("unchecked")
    private static ApiResponse<Order> response(final Order order) {
        final ApiResponse<Order> response = Mockito.mock(ApiResponse.class);
        Mockito.when(response.getResult()).thenReturn(order);
        return response;
    }

    private static Order completedOrder(final String captureId, final String amount, final String fee) {
        final OrdersCapture.Builder capture = new OrdersCapture.Builder()
                .id(captureId)
                .amount(new Money.Builder().currencyCode("USD").value(amount).build());
        if (fee != null) {
            capture.sellerReceivableBreakdown(new SellerReceivableBreakdown.Builder()
                    .paypalFee(new Money.Builder().currencyCode("USD").value(fee).build()).build());
        }
        return new Order.Builder()
                .id("ORDER-1")
                .status(OrderStatus.COMPLETED)
                .purchaseUnits(List.of(new PurchaseUnit.Builder()
                        .payments(new PaymentCollection.Builder()
                                .captures(List.of(capture.build())).build())
                        .build()))
                .build();
    }

    private static Payment payment() {
        return Payment.builder().payerId(Person.Id.newInstance()).totalChargedCents(250000L).build();
    }
}
