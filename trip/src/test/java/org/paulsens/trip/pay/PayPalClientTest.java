package org.paulsens.trip.pay;

import com.paypal.sdk.http.response.ApiResponse;
import com.paypal.sdk.models.CaptureOrderInput;
import com.paypal.sdk.models.CreateOrderInput;
import com.paypal.sdk.models.LinkDescription;
import com.paypal.sdk.models.Money;
import com.paypal.sdk.models.Order;
import com.paypal.sdk.models.OrderRequest;
import com.paypal.sdk.models.OrdersCapture;
import com.paypal.sdk.models.PaymentCollection;
import com.paypal.sdk.models.PurchaseUnit;
import com.paypal.sdk.models.SellerReceivableBreakdown;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

public class PayPalClientTest {
    private final PayPalClient payPalClient = PayPalClient.getInstance();

    // Integration test – requires live PayPal credentials; disabled by default.
    @Test(enabled = false)
    void canCreateOrder() {
        final String id = RandomData.genAlpha(5);
        ApiResponse<Order> order = payPalClient
                .createOrder(null, Person.Id.from(id), 123.45f, "some trip", "CFPW", "blah blah blah",
                        "https://example.com/return", "https://example.com/cancel")
                .orTimeout(1000, TimeUnit.MILLISECONDS)
                .join();
        Assert.assertNotNull(order);
    }

    // -------------------------------------------------------------------------
    // getApprovalUrl
    // -------------------------------------------------------------------------

    @Test
    public void getApprovalUrl_nullOrder_returnsEmpty() {
        Assert.assertEquals(payPalClient.getApprovalUrl(null), Optional.empty());
    }

    @Test
    public void getApprovalUrl_nullLinks_returnsEmpty() {
        final Order order = new Order.Builder().build();
        Assert.assertEquals(payPalClient.getApprovalUrl(order), Optional.empty());
    }

    @Test
    public void getApprovalUrl_noApproveLink_returnsEmpty() {
        final LinkDescription selfLink = new LinkDescription.Builder("https://api.paypal.com/v2/orders/123", "self").build();
        final Order order = new Order.Builder().links(List.of(selfLink)).build();
        Assert.assertEquals(payPalClient.getApprovalUrl(order), Optional.empty());
    }

    @Test
    public void getApprovalUrl_withApproveLink_returnsUrl() {
        final String approveUrl = "https://www.paypal.com/checkoutnow?token=123";
        final LinkDescription approveLink = new LinkDescription.Builder(approveUrl, "approve").build();
        final LinkDescription selfLink = new LinkDescription.Builder("https://api.paypal.com/v2/orders/123", "self").build();
        final Order order = new Order.Builder().links(List.of(selfLink, approveLink)).build();
        Assert.assertEquals(payPalClient.getApprovalUrl(order), Optional.of(approveUrl));
    }

    // -------------------------------------------------------------------------
    // getCapturedAmount
    // -------------------------------------------------------------------------

    @Test
    public void getCapturedAmount_validOrder_returnsAmount() {
        final Order order = buildOrderWithCapture("50.00", null);
        Assert.assertEquals(payPalClient.getCapturedAmount(order), 50.0f, 0.001f);
    }

    @Test
    public void getCapturedAmount_nullPurchaseUnits_returnsZero() {
        final Order order = new Order.Builder().build();
        Assert.assertEquals(payPalClient.getCapturedAmount(order), 0f, 0.001f);
    }

    @Test
    public void getCapturedAmount_nullPayments_returnsZero() {
        final PurchaseUnit unit = new PurchaseUnit.Builder().build();
        final Order order = new Order.Builder().purchaseUnits(List.of(unit)).build();
        Assert.assertEquals(payPalClient.getCapturedAmount(order), 0f, 0.001f);
    }

    // -------------------------------------------------------------------------
    // getPayPalFee
    // -------------------------------------------------------------------------

    @Test
    public void getPayPalFee_withFee_returnsFee() {
        final Money fee = new Money.Builder("USD", "1.74").build();
        final SellerReceivableBreakdown breakdown = new SellerReceivableBreakdown.Builder()
                .paypalFee(fee)
                .build();
        final Order order = buildOrderWithCapture("50.00", breakdown);
        final Optional<Float> result = payPalClient.getPayPalFee(order);
        Assert.assertTrue(result.isPresent());
        Assert.assertEquals(result.get(), 1.74f, 0.001f);
    }

    @Test
    public void getPayPalFee_noBreakdown_returnsEmpty() {
        final Order order = buildOrderWithCapture("50.00", null);
        Assert.assertEquals(payPalClient.getPayPalFee(order), Optional.empty());
    }

    @Test
    public void getPayPalFee_nullOrder_returnsEmpty() {
        // getCapturedAmount and getPayPalFee both catch all exceptions; null order → exception → empty/0
        final Order order = new Order.Builder().build();
        Assert.assertEquals(payPalClient.getPayPalFee(order), Optional.empty());
    }

    // -------------------------------------------------------------------------
    // getCapturedAmount — additional edge cases
    // -------------------------------------------------------------------------

    @Test
    public void getCapturedAmount_emptyCaptures_returnsZero() {
        final PaymentCollection payments = new PaymentCollection.Builder()
                .captures(Collections.emptyList())
                .build();
        final PurchaseUnit unit = new PurchaseUnit.Builder().payments(payments).build();
        final Order order = new Order.Builder().purchaseUnits(List.of(unit)).build();
        Assert.assertEquals(payPalClient.getCapturedAmount(order), 0f, 0.001f);
    }

    @Test
    public void getCapturedAmount_nullAmountOnCapture_returnsZero() {
        final OrdersCapture capture = new OrdersCapture.Builder().build(); // no amount set
        final PaymentCollection payments = new PaymentCollection.Builder()
                .captures(List.of(capture))
                .build();
        final PurchaseUnit unit = new PurchaseUnit.Builder().payments(payments).build();
        final Order order = new Order.Builder().purchaseUnits(List.of(unit)).build();
        Assert.assertEquals(payPalClient.getCapturedAmount(order), 0f, 0.001f);
    }

    // -------------------------------------------------------------------------
    // getPayPalFee — additional edge cases
    // -------------------------------------------------------------------------

    @Test
    public void getPayPalFee_feeWithNullValue_returnsEmpty() {
        final Money feeNoValue = new Money.Builder("USD", null).build();
        final SellerReceivableBreakdown breakdown = new SellerReceivableBreakdown.Builder()
                .paypalFee(feeNoValue)
                .build();
        final Order order = buildOrderWithCapture("100.00", breakdown);
        Assert.assertEquals(payPalClient.getPayPalFee(order), Optional.empty());
    }

    @Test
    public void getPayPalFee_emptyCaptures_returnsEmpty() {
        final PaymentCollection payments = new PaymentCollection.Builder()
                .captures(Collections.emptyList())
                .build();
        final PurchaseUnit unit = new PurchaseUnit.Builder().payments(payments).build();
        final Order order = new Order.Builder().purchaseUnits(List.of(unit)).build();
        Assert.assertEquals(payPalClient.getPayPalFee(order), Optional.empty());
    }

    // -------------------------------------------------------------------------
    // SDK 2.2.0 input builder smoke tests
    // -------------------------------------------------------------------------

    @Test
    public void createOrderInput_buildsWithoutError() {
        // Verifies the 2.2.0 CreateOrderInput builder compiles and works as expected
        final CreateOrderInput input = new CreateOrderInput.Builder()
                .body(new OrderRequest.Builder().build())
                .paypalMockResponse("CREATED")   // 2.2.0-only sandbox testing field
                .build();
        Assert.assertNotNull(input);
        Assert.assertNotNull(input.getBody());
    }

    @Test
    public void captureOrderInput_buildsWithoutError() {
        // Verifies the 2.2.0 CaptureOrderInput builder compiles and works as expected
        final CaptureOrderInput input = new CaptureOrderInput.Builder()
                .id("ORDER-123")
                .paypalMockResponse("COMPLETED")  // 2.2.0-only sandbox testing field
                .build();
        Assert.assertNotNull(input);
        Assert.assertEquals(input.getId(), "ORDER-123");
    }

    // -------------------------------------------------------------------------
    // isValidEmail
    // -------------------------------------------------------------------------

    @Test
    public void isValidEmail_normalAddress_returnsTrue() {
        Assert.assertTrue(PayPalClient.isValidEmail("user@example.com"));
    }

    @Test
    public void isValidEmail_subdomainAddress_returnsTrue() {
        Assert.assertTrue(PayPalClient.isValidEmail("admin@mail.example.co.uk"));
    }

    @Test
    public void isValidEmail_plusTag_returnsTrue() {
        Assert.assertTrue(PayPalClient.isValidEmail("user+tag@example.com"));
    }

    @Test
    public void isValidEmail_dotsInLocal_returnsTrue() {
        Assert.assertTrue(PayPalClient.isValidEmail("first.last@example.com"));
    }

    @Test
    public void isValidEmail_null_returnsFalse() {
        Assert.assertFalse(PayPalClient.isValidEmail(null));
    }

    @Test
    public void isValidEmail_empty_returnsFalse() {
        Assert.assertFalse(PayPalClient.isValidEmail(""));
    }

    @Test
    public void isValidEmail_blank_returnsFalse() {
        Assert.assertFalse(PayPalClient.isValidEmail("   "));
    }

    @Test
    public void isValidEmail_noAtSign_returnsFalse() {
        Assert.assertFalse(PayPalClient.isValidEmail("userexample.com"));
    }

    @Test
    public void isValidEmail_twoAtSigns_returnsFalse() {
        Assert.assertFalse(PayPalClient.isValidEmail("user@@example.com"));
    }

    @Test
    public void isValidEmail_atSignAtStart_returnsFalse() {
        Assert.assertFalse(PayPalClient.isValidEmail("@example.com"));
    }

    @Test
    public void isValidEmail_noDotInDomain_returnsFalse() {
        Assert.assertFalse(PayPalClient.isValidEmail("user@localhost"));
    }

    @Test
    public void isValidEmail_dotAtStartOfDomain_returnsFalse() {
        Assert.assertFalse(PayPalClient.isValidEmail("user@.example.com"));
    }

    @Test
    public void isValidEmail_dotAtEndOfDomain_returnsFalse() {
        Assert.assertFalse(PayPalClient.isValidEmail("user@example."));
    }

    @Test
    public void isValidEmail_containsWhitespace_returnsFalse() {
        Assert.assertFalse(PayPalClient.isValidEmail("user @example.com"));
    }

    @Test
    public void isValidEmail_twoAtsInDifferentPositions_returnsFalse() {
        Assert.assertFalse(PayPalClient.isValidEmail("user@foo@bar.com"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Order buildOrderWithCapture(final String amount, final SellerReceivableBreakdown breakdown) {
        final Money money = new Money.Builder("USD", amount).build();
        final OrdersCapture capture = new OrdersCapture.Builder()
                .amount(money)
                .sellerReceivableBreakdown(breakdown)
                .build();
        final PaymentCollection payments = new PaymentCollection.Builder()
                .captures(List.of(capture))
                .build();
        final PurchaseUnit unit = new PurchaseUnit.Builder()
                .payments(payments)
                .build();
        return new Order.Builder().purchaseUnits(List.of(unit)).build();
    }
}