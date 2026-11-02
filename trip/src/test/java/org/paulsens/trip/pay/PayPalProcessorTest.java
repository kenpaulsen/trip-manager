package org.paulsens.trip.pay;

import com.paypal.sdk.PaypalServerSdkClient;
import com.paypal.sdk.controllers.OrdersController;
import com.paypal.sdk.exceptions.ApiException;
import com.paypal.sdk.exceptions.ErrorException;
import com.paypal.sdk.http.response.ApiResponse;
import com.paypal.sdk.models.ErrorDetails;
import com.paypal.sdk.models.LinkDescription;
import com.paypal.sdk.models.Money;
import com.paypal.sdk.models.Order;
import com.paypal.sdk.models.OrderStatus;
import com.paypal.sdk.models.OrdersCapture;
import com.paypal.sdk.models.PaymentCollection;
import com.paypal.sdk.models.PurchaseUnit;
import com.paypal.sdk.models.SellerReceivableBreakdown;
import io.apimatic.coreinterfaces.http.Context;
import io.apimatic.coreinterfaces.http.response.Response;
import java.util.List;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ProcessorType;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/**
 * The capture-verification rules -- the regression tests for the prototype's known gap (a parse failure
 * used to write a $0 transaction and report success).
 */
public class PayPalProcessorTest {
    /** The SDK's message for EVERY 422 -- the issue code is never in it (see PayPalProcessor.isAlreadyCaptured). */
    private static final String SDK_422_REASON = "The requested action could not be performed, semantically "
            + "incorrect, or failed business validation.";

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
        final ErrorException alreadyCaptured = paypalRefusal(422, "ORDER_ALREADY_CAPTURED");
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

    /**
     * The 2026-11-01 regression: the SDK's message is a fixed reason string, so the code must read the parsed
     * details (or the raw body), and a message-only match is the OLD false positive, not a success.
     */
    @Test
    public void isAlreadyCapturedReadsTheParsedIssueNotTheMessage() {
        // (a) the production shape: fixed message, issue in the details.
        assertTrue(PayPalProcessor.isAlreadyCaptured(paypalRefusal(422, "ORDER_ALREADY_CAPTURED")));

        // (b) the code ONLY in the message (the old test's shape) is not a match.
        final ApiException messageOnly = Mockito.mock(ApiException.class);
        Mockito.when(messageOnly.getResponseCode()).thenReturn(422);
        Mockito.when(messageOnly.getMessage()).thenReturn("ORDER_ALREADY_CAPTURED");
        assertFalse(PayPalProcessor.isAlreadyCaptured(messageOnly));

        // (c) a different 422 issue, and (f) the right issue on a non-422, are not matches.
        assertFalse(PayPalProcessor.isAlreadyCaptured(paypalRefusal(422, "INSTRUMENT_DECLINED")));
        assertFalse(PayPalProcessor.isAlreadyCaptured(paypalRefusal(400, "ORDER_ALREADY_CAPTURED")));

        // (d) no parsed details: the raw response body is the fallback.
        // Mocks are built BEFORE the when() (Mockito nesting rule).
        final Context rawContext = contextWithBody(
                "{\"name\":\"UNPROCESSABLE_ENTITY\",\"details\":[{\"issue\":\"ORDER_ALREADY_CAPTURED\"}]}");
        final ApiException rawOnly = Mockito.mock(ApiException.class);
        Mockito.when(rawOnly.getResponseCode()).thenReturn(422);
        Mockito.when(rawOnly.getHttpContext()).thenReturn(rawContext);
        assertTrue(PayPalProcessor.isAlreadyCaptured(rawOnly));

        // (e) null details and null context: false, never an NPE.
        final ErrorException bare = Mockito.mock(ErrorException.class);
        Mockito.when(bare.getResponseCode()).thenReturn(422);
        assertFalse(PayPalProcessor.isAlreadyCaptured(bare));
        final Context emptyContext = Mockito.mock(Context.class);
        final ApiException noResponse = Mockito.mock(ApiException.class);
        Mockito.when(noResponse.getResponseCode()).thenReturn(422);
        Mockito.when(noResponse.getHttpContext()).thenReturn(emptyContext);
        assertFalse(PayPalProcessor.isAlreadyCaptured(noResponse));
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
        final ErrorException alreadyCaptured = paypalRefusal(422, "ORDER_ALREADY_CAPTURED");
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

    /** A PayPal refusal in the shape the SDK really throws: fixed message, issue code in the parsed details. */
    private static ErrorException paypalRefusal(final int status, final String issue) {
        final ErrorDetails detail = new ErrorDetails();
        detail.setIssue(issue);
        final ErrorException ex = Mockito.mock(ErrorException.class);
        Mockito.when(ex.getResponseCode()).thenReturn(status);
        Mockito.when(ex.getMessage()).thenReturn(SDK_422_REASON);
        Mockito.when(ex.getDetails()).thenReturn(List.of(detail));
        return ex;
    }

    private static Context contextWithBody(final String body) {
        final Response response = Mockito.mock(Response.class);
        Mockito.when(response.getBody()).thenReturn(body);
        final Context context = Mockito.mock(Context.class);
        Mockito.when(context.getResponse()).thenReturn(response);
        return context;
    }

    private static Payment payment() {
        return Payment.builder().payerId(Person.Id.newInstance()).totalChargedCents(250000L).build();
    }
    /** The SPI defaults a redirect processor inherits: no refund read, no re-pricing, cancel is a no-op. */
    @Test
    public void redirectProcessorsHaveNoRefundReadAndNoRePricing() {
        final PayPalProcessor paypal = new PayPalProcessor("id", "secret", true);
        assertTrue(paypal.refundedCents("ORDER-1").isEmpty());
        assertEquals(paypal.updateAmount("ORDER-1", 100L), false);
        paypal.cancel("ORDER-1");
    }
}
