package org.paulsens.trip.pay;

import com.paypal.sdk.Environment;
import com.paypal.sdk.PaypalServerSdkClient;
import com.paypal.sdk.authentication.ClientCredentialsAuthModel;
import com.paypal.sdk.http.response.ApiResponse;
import com.paypal.sdk.models.AmountWithBreakdown;
import com.paypal.sdk.models.CaptureOrderInput;
import com.paypal.sdk.models.CheckoutPaymentIntent;
import com.paypal.sdk.models.CreateOrderInput;
import com.paypal.sdk.models.LinkDescription;
import com.paypal.sdk.models.Money;
import com.paypal.sdk.models.Name;
import com.paypal.sdk.models.Order;
import com.paypal.sdk.models.OrderApplicationContext;
import com.paypal.sdk.models.OrderRequest;
import com.paypal.sdk.models.OrdersCapture;
import com.paypal.sdk.models.Payer;
import com.paypal.sdk.models.PhoneNumber;
import com.paypal.sdk.models.PhoneType;
import com.paypal.sdk.models.PhoneWithType;
import com.paypal.sdk.models.PurchaseUnitRequest;
import com.paypal.sdk.models.SellerReceivableBreakdown;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.paulsens.trip.model.Person;

@Slf4j
public class PayPalClient {
    private static final PayPalClient INSTANCE = new PayPalClient();
    private static final String USD = "USD";
    private static final String DEFAULT_EMAIL = "ken@centerforpeacewest.com";

    private final PaypalServerSdkClient sdkClient;

    private PayPalClient() {
        final Map<String, String> env = System.getenv();
        final String clientId = env.getOrDefault("PAYPAL_CLIENT_ID", "");
        final String secret = env.getOrDefault("PAYPAL_SECRET", "");
        if (clientId.isEmpty() || secret.isEmpty()) {
            log.warn("PayPal credentials not set! Configure PAYPAL_CLIENT_ID and PAYPAL_SECRET environment variables.");
        }
        final boolean production = "PRODUCTION".equalsIgnoreCase(env.getOrDefault("PAYPAL_ENVIRONMENT", "SANDBOX"));
        this.sdkClient = new PaypalServerSdkClient.Builder()
                .clientCredentialsAuth(new ClientCredentialsAuthModel.Builder(clientId, secret).build())
                .environment(production ? Environment.PRODUCTION : Environment.SANDBOX)
                .build();
        log.info("PayPalClient initialized in {} mode.", production ? "PRODUCTION" : "SANDBOX");
    }

    public static PayPalClient getInstance() {
        return INSTANCE;
    }

    public CompletableFuture<ApiResponse<Order>> createOrder(
            final Person person,
            final Person.Id id,
            final Float amountDue,
            final String invoiceId,
            final String orgAbbr,
            final String description,
            final String returnUrl,
            final String cancelUrl) {
        final List<PurchaseUnitRequest> purchases = toPurchaseUnitRequests(
                List.of(amountDue), id, invoiceId, orgAbbr, description);
        return sdkClient.getOrdersController().createOrderAsync(
                new CreateOrderInput.Builder()
                        .body(createOrderRequest(person, purchases, returnUrl, cancelUrl))
                        .build())
                .whenComplete((resp, ex) -> {
                    if (ex == null) {
                        log.info("Create order response: {}", resp.getStatusCode());
                    } else {
                        log.error("Error creating PayPal order", ex);
                    }
                });
    }

    public CompletableFuture<ApiResponse<Order>> captureOrder(final String orderId) {
        return sdkClient.getOrdersController().captureOrderAsync(
                    new CaptureOrderInput.Builder().id(orderId).build())
                .whenComplete((resp, ex) -> {
                    if (ex == null) {
                        log.info("Capture order ({}) response: {}", orderId, resp.getStatusCode());
                    } else {
                        log.error("Error capturing PayPal order: {}", orderId, ex);
                    }
                });
    }

    /**
     * Extracts the PayPal "approve" redirect URL from the order's HATEOAS links.
     * Redirect the user's browser to this URL to complete payment.
     */
    public Optional<String> getApprovalUrl(final Order order) {
        if (order == null || order.getLinks() == null) {
            return Optional.empty();
        }
        return order.getLinks().stream()
                .filter(link -> "approve".equals(link.getRel()))
                .map(LinkDescription::getHref)
                .findFirst();
    }

    /**
     * Returns the gross amount captured from a completed order, or 0 if unavailable.
     */
    public float getCapturedAmount(final Order order) {
        val capture = getOrderCapture(order);
        if (capture == null || capture.getAmount() == null) {
            return 0f;
        }
        try {
            final String value = capture.getAmount().getValue();
            return Float.parseFloat(value);
        } catch (final NumberFormatException ex) {
            log.error("Could not extract captured amount from PayPal order", ex);
            return 0f;
        }
    }

    /**
     * Returns the PayPal processing fee from a captured order, or empty if unavailable.
     */
    public Optional<Float> getPayPalFee(final Order order) {
        val capture = getOrderCapture(order);
        if (capture == null) {
            return Optional.empty();
        }
        final SellerReceivableBreakdown breakdown = capture.getSellerReceivableBreakdown();
        if (breakdown == null) {
            return Optional.empty();
        }
        final Money fee = breakdown.getPaypalFee();
        if (fee == null || fee.getValue() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Float.parseFloat(fee.getValue()));
        } catch (final Exception ex) {
            log.warn("Could not extract PayPal fee from order (fee info may not be present in sandbox)", ex);
            return Optional.empty();
        }
    }

    /**
     * Returns {@code true} if the given string looks like a plausible email address.
     * This is intentionally a lightweight check (not a full RFC 5322 parser) — it
     * just guards against obviously invalid values being sent to the PayPal API.
     *
     * <p>Rules: non-null, no whitespace, exactly one {@code @}, at least one character
     * before the {@code @}, and the domain part contains at least one {@code .} with
     * characters on both sides of it.</p>
     */
    static boolean isValidEmail(final String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        final int at = email.indexOf('@');
        // Must have exactly one '@', with at least one char before it
        if (at < 1 || email.indexOf('@', at + 1) != -1) {
            return false;
        }
        final String domain = email.substring(at + 1);
        final int dot = domain.indexOf('.');
        // Domain must have a dot that isn't first or last, and no whitespace anywhere
        return dot > 0 && dot < domain.length() - 1 && email.chars().noneMatch(Character::isWhitespace);
    }

    private Payer toPayer(final Person person) {
        if (person == null || (person.getEmail() == null && person.getFirst() == null && person.getCell() == null)) {
            return null;
        }
        final Payer.Builder result = new Payer.Builder();
        if (isValidEmail(person.getEmail())) {
            result.emailAddress(person.getEmail());
        } else {
            log.warn("Invalid or missing email for person '{}', using default: {}", person.getId(), DEFAULT_EMAIL);
            result.emailAddress(DEFAULT_EMAIL);
        }
        if (person.getFirst() != null) {
            result.name(new Name.Builder().givenName(person.getFirst()).surname(person.getLast()).build());
        }
        if (person.getCell() != null) {
            final String digitsOnly = person.getCell().replaceAll("[^0-9]", "");
            if (!digitsOnly.isEmpty()) {
                result.phone(new PhoneWithType.Builder()
                            .phoneNumber(new PhoneNumber(digitsOnly))
                            .phoneType(PhoneType.MOBILE)
                            .build());
            }
        }
        if (person.getBirthdate() != null) {
            result.birthDate(person.getBirthdate().format(DateTimeFormatter.ISO_LOCAL_DATE));
        }
        return result.build();
    }

    private List<PurchaseUnitRequest> toPurchaseUnitRequests(
            final List<Float> amounts,
            final Person.Id id,
            final String invoiceId,
            final String orgAbbr,
            final String description) {
        final List<PurchaseUnitRequest> result = new ArrayList<>();
        for (final Float amount : amounts) {
            result.add(new PurchaseUnitRequest.Builder()
                    .referenceId(id.getValue())
                    .amount(toAmount(amount))
                    .description(description)
                    .customId(id.getValue())
                    .invoiceId(invoiceId)
                    .softDescriptor(orgAbbr)
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

    private OrderRequest createOrderRequest(
            final Person person,
            final List<PurchaseUnitRequest> items,
            final String returnUrl,
            final String cancelUrl) {
        return new OrderRequest.Builder()
                .intent(CheckoutPaymentIntent.CAPTURE)
                .payer(toPayer(person))
                .purchaseUnits(items)
                .applicationContext(getOrderApplicationContext(returnUrl, cancelUrl))
                .build();
    }

    private OrdersCapture getOrderCapture(final Order order) {
        if (order == null || order.getPurchaseUnits() == null || order.getPurchaseUnits().isEmpty()) {
            return null;
        }
        val first = order.getPurchaseUnits().getFirst().getPayments();
        if (first == null || first.getCaptures() == null || first.getCaptures().isEmpty()) {
            return null;
        }
        return first.getCaptures().getFirst();
    }

    private OrderApplicationContext getOrderApplicationContext(
            final String returnUrl, final String cancelUrl) {
        return new OrderApplicationContext.Builder()
                .locale("en-US")
                .returnUrl(returnUrl)
                .cancelUrl(cancelUrl)
                .build();
    }
}
