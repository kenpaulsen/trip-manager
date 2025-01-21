package org.paulsens.trip.pay;

import com.paypal.sdk.Environment;
import com.paypal.sdk.PaypalServerSdkClient;
import com.paypal.sdk.authentication.ClientCredentialsAuthModel;
import com.paypal.sdk.http.response.ApiResponse;
import com.paypal.sdk.models.AmountWithBreakdown;
import com.paypal.sdk.models.CheckoutPaymentIntent;
import com.paypal.sdk.models.Name;
import com.paypal.sdk.models.Order;
import com.paypal.sdk.models.OrderApplicationContext;
import com.paypal.sdk.models.OrderRequest;
import com.paypal.sdk.models.OrdersCaptureInput;
import com.paypal.sdk.models.OrdersCreateInput;
import com.paypal.sdk.models.Payer;
import com.paypal.sdk.models.PhoneNumber;
import com.paypal.sdk.models.PhoneType;
import com.paypal.sdk.models.PhoneWithType;
import com.paypal.sdk.models.PurchaseUnitRequest;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.paulsens.trip.model.Person;

public class PayPalClient {
    private static final PayPalClient INSTANCE = new PayPalClient();
    private static final String USD = "USD";
    private final PaypalServerSdkClient sdkClient;

    private PayPalClient() {
        final String clientId = "";
        final String secret   = "";
        this.sdkClient = new PaypalServerSdkClient.Builder()
                .clientCredentialsAuth(new ClientCredentialsAuthModel.Builder(clientId, secret).build())
                .environment(Environment.SANDBOX)
                //.environment(Environment.PRODUCTION)
                //.httpCallback()       // See http calls before and after requests
                //.httpClientConfig()   // get builder to change http settings
                .loggingConfig()        // Turn on console logging
                .build();
    }

    public static PayPalClient getInstance() {
        return INSTANCE;
    }

    public CompletableFuture<ApiResponse<Order>> createOrder(
            final Person person,
            final Person.Id id,
            final Float amountDue,       // List supported, but for now only support: amountDue
            final String invoiceId,      // Optional. Maybe use Trip Name in most cases? Visible to Payer.
            final String orgAbbr,        // Eg. CFPW - shows on the credit card statement
            final String description) {
        final List<PurchaseUnitRequest> purchases = toPurchaseUnitRequests(
                List.of(amountDue), id, invoiceId, orgAbbr, description);
        return sdkClient.getOrdersController().ordersCreateAsync(
                new OrdersCreateInput.Builder()
                        .body(createOrderRequest(person, purchases))
                        .build())
                .whenComplete((resp, ex) -> {
                    if (ex == null) {
                        ex.printStackTrace();
                    } else {
                        System.out.println("Response code: " + resp.getStatusCode());
                        System.out.println(resp.getResult());
                    }
                });
    }

    public CompletableFuture<ApiResponse<Order>> captureOrder(final String orderId) {
        return sdkClient.getOrdersController().ordersCaptureAsync(
                    new OrdersCaptureInput.Builder().id(orderId).build())
                .whenComplete((resp, ex) -> {
                    if (ex == null) {
                        System.out.println("Response code: " + resp.getStatusCode());
                        System.out.println(resp.getResult());
                    } else {
                        ex.printStackTrace();
                    }
                });
    }

    private Payer toPayer(final Person person) {
        if (person == null || (person.getEmail() == null && person.getFirst() == null && person.getCell() == null)) {
            return null;
        }
        final Payer.Builder result = new Payer.Builder();
        if (person.getEmail() != null) {
            result.emailAddress(person.getEmail());
        }
        if (person.getFirst() != null) {
            result.name(new Name.Builder().givenName(person.getFirst()).surname(person.getLast()).build());
        }
        if (person.getCell() != null) {
            result.phone(new PhoneWithType.Builder()
                        .phoneNumber(new PhoneNumber(person.getCell()))
                        .phoneType(PhoneType.MOBILE)
                        .build());
        }
        if (person.getBirthdate() != null) {
            result.birthDate(person.getBirthdate().format(DateTimeFormatter.ISO_LOCAL_DATE));
        }
        // FIXME: If this is needed, more work to do...
        //.address(new Address.Builder().build()) -- requires strict validation
        return result.build();
    }

    private List<PurchaseUnitRequest> toPurchaseUnitRequests(
            final List<Float> amounts,   // Although a List, our use case should likely only pass 1 amount: total due
            final Person.Id id,
            final String invoiceId,      // Optional. Maybe use Trip Name in most cases? Visible to Payer.
            final String orgAbbr,        // Eg. CFPW - shows on the credit card statement
            final String description) {
        final List<PurchaseUnitRequest> result = new ArrayList<>();
        for (final Float amount : amounts) {
            result.add(new PurchaseUnitRequest.Builder()
                    .referenceId(id.getValue()) // Verify this is ok to reuse userId
                    .amount(toAmount(amount))
                    .description(description)
                    .customId(id.getValue()) // Instead of UserId, should this be Tx Id? We'd have to create it early.
                    .invoiceId(invoiceId)
                    .softDescriptor(orgAbbr)
                    //.items(items)          // List the items being purchased to user during checkout. Not supported.
                    //.shipping()            // Shipping details. Not supported.
                    //.supplementaryData()   // Card info and shipping info. Not supported.
                    .build());
        }
        return result;
    }

    private AmountWithBreakdown toAmount(final Float amount) {
        return new AmountWithBreakdown.Builder()
                .currencyCode(USD)
                .value(String.format("%.2f", amount))
                .build();
    }

    private OrderRequest createOrderRequest(final Person person, final List<PurchaseUnitRequest> items) {
        return new OrderRequest.Builder()
                .intent(CheckoutPaymentIntent.CAPTURE)
                .payer(toPayer(person))
                .purchaseUnits(items)
                // Add the payment source details. Not supported for now.
                /*
                .paymentSource(new PaymentSource.Builder()
                        .paypal(new PaypalWallet.Builder()
                                .experienceContext(new PaypalWalletExperienceContext.Builder()
                                        .brandName("Center for Peace West")
                                        .build())
                                .build())
                        .build())
                 */
                .applicationContext(getOrderApplicationContext("en")) // Add preferred language to Person
                .build();
    }

    private static OrderApplicationContext getOrderApplicationContext(final String lang) {
        return new OrderApplicationContext.Builder()
                .locale(lang)
                .build();
    }
}
