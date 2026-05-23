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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.paulsens.trip.model.Person;

@Slf4j
public class PayPalClient {
    private static final PayPalClient INSTANCE = new PayPalClient();
    private static final String USD = "USD";
    private static final String DEFAULT_EMAIL = "ken@centerforpeacewest.com";

    // Environment variable names used to wire up credentials and routing.
    static final String ENV_PROD_CLIENT_ID    = "PAYPAL_CLIENT_ID";
    static final String ENV_PROD_SECRET       = "PAYPAL_SECRET";
    static final String ENV_SANDBOX_CLIENT_ID = "PAYPAL_SANDBOX_CLIENT_ID";
    static final String ENV_SANDBOX_SECRET    = "PAYPAL_SANDBOX_SECRET";
    static final String ENV_TEST_EMAILS       = "PAYPAL_TEST_EMAILS";
    /** Legacy global override: when set to {@code "SANDBOX"}, every payer routes to sandbox. */
    static final String ENV_ENVIRONMENT       = "PAYPAL_ENVIRONMENT";

    /** Production SDK client. {@code null} when {@link #ENV_PROD_CLIENT_ID}/{@link #ENV_PROD_SECRET}
     *  are not configured (typical on dev laptops). */
    private final PaypalServerSdkClient productionClient;

    /** Sandbox SDK client. {@code null} when the {@code PAYPAL_SANDBOX_*} env vars are not
     *  configured (typical on hosts that only ever speak to production). */
    private final PaypalServerSdkClient sandboxClient;

    /** Lowercase email allow-list — payers whose email matches one of these route to sandbox. */
    private final Set<String> testEmails;

    /** Legacy global override: {@code true} when {@code PAYPAL_ENVIRONMENT=SANDBOX} routes
     *  every payer to sandbox regardless of allow-list (dev-machine compatibility). */
    private final boolean defaultUseSandbox;

    private PayPalClient() {
        this(System.getenv());
    }

    /**
     * Package-private constructor used by both the singleton (with {@code System.getenv()}) and
     * by tests (with a controlled fake env map). Reads {@link #ENV_PROD_CLIENT_ID} et al. from
     * the supplied map and configures the two SDK clients + routing accordingly.
     */
    PayPalClient(final Map<String, String> env) {
        this.productionClient = buildClient(
                env.getOrDefault(ENV_PROD_CLIENT_ID, ""),
                env.getOrDefault(ENV_PROD_SECRET, ""),
                Environment.PRODUCTION);
        this.sandboxClient = buildClient(
                env.getOrDefault(ENV_SANDBOX_CLIENT_ID, ""),
                env.getOrDefault(ENV_SANDBOX_SECRET, ""),
                Environment.SANDBOX);
        this.testEmails = parseTestEmails(env.getOrDefault(ENV_TEST_EMAILS, ""));
        this.defaultUseSandbox = "SANDBOX".equalsIgnoreCase(
                env.getOrDefault(ENV_ENVIRONMENT, "PRODUCTION"));
        if ((productionClient == null) && (sandboxClient == null)) {
            log.warn("PayPal credentials not set! Configure {}/{} (production) and/or {}/{} (sandbox).",
                    ENV_PROD_CLIENT_ID, ENV_PROD_SECRET, ENV_SANDBOX_CLIENT_ID, ENV_SANDBOX_SECRET);
        }
        log.info("PayPalClient initialized — production={}, sandbox={}, testEmails={}, defaultUseSandbox={}",
                (productionClient != null), (sandboxClient != null), testEmails.size(), defaultUseSandbox);
    }

    public static PayPalClient getInstance() {
        return INSTANCE;
    }

    /**
     * @return {@code true} if PayPal calls for the given person should be routed to the sandbox
     *         SDK client. Routing rules (in order):
     *         <ol>
     *           <li>If {@link #ENV_ENVIRONMENT}{@code =SANDBOX}, every payer is sandbox.</li>
     *           <li>If {@code person} or their email is {@code null}, production.</li>
     *           <li>If the email (case-insensitive) is on {@link #ENV_TEST_EMAILS}, sandbox.</li>
     *           <li>Otherwise production.</li>
     *         </ol>
     */
    public boolean useSandboxFor(final Person person) {
        final boolean result;
        if (defaultUseSandbox) {
            result = true;
        } else if ((person == null) || (person.getEmail() == null)) {
            result = false;
        } else {
            result = testEmails.contains(person.getEmail().trim().toLowerCase());
        }
        return result;
    }

    /** @return the SDK client matching the requested environment, falling back to whichever
     *  is configured if the requested one isn't. */
    private PaypalServerSdkClient pickClient(final boolean useSandbox) {
        final PaypalServerSdkClient result;
        if (useSandbox) {
            if (sandboxClient != null) {
                result = sandboxClient;
            } else {
                log.warn("Sandbox SDK requested but not configured — falling back to production client.");
                result = productionClient;
            }
        } else {
            if (productionClient != null) {
                result = productionClient;
            } else {
                log.warn("Production SDK requested but not configured — falling back to sandbox client.");
                result = sandboxClient;
            }
        }
        return result;
    }

    private static PaypalServerSdkClient buildClient(
            final String clientId, final String secret, final Environment env) {
        final PaypalServerSdkClient result;
        if (clientId.isEmpty() || secret.isEmpty()) {
            result = null;
        } else {
            result = new PaypalServerSdkClient.Builder()
                    .clientCredentialsAuth(new ClientCredentialsAuthModel.Builder(clientId, secret).build())
                    .environment(env)
                    .build();
        }
        return result;
    }

    /** Parses the comma-separated {@link #ENV_TEST_EMAILS} value into a normalized lowercase set. */
    static Set<String> parseTestEmails(final String csv) {
        final Set<String> result;
        if ((csv == null) || csv.isBlank()) {
            result = Collections.emptySet();
        } else {
            result = Arrays.stream(csv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .collect(Collectors.toUnmodifiableSet());
        }
        return result;
    }

    public CompletableFuture<ApiResponse<Order>> createOrder(
            final Person person,
            final Person.Id id,
            final BigDecimal amountDue,
            final String invoiceId,
            final String orgAbbr,
            final String description,
            final String returnUrl,
            final String cancelUrl) {
        final boolean useSandbox = useSandboxFor(person);
        final PaypalServerSdkClient client = pickClient(useSandbox);
        final List<PurchaseUnitRequest> purchases = toPurchaseUnitRequests(
                List.of(amountDue), id, invoiceId, orgAbbr, description);
        log.info("PayPal createOrder env={} payer={} id={} amount={}",
                useSandbox ? "SANDBOX" : "PRODUCTION",
                (person == null) ? "<none>" : person.getEmail(), id, amountDue);
        return client.getOrdersController().createOrderAsync(
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

    /**
     * Captures a previously-created order. The caller must specify which environment the order
     * was created in (PayPal order IDs are environment-scoped — a sandbox order ID rejected by
     * production and vice versa). Use {@link #useSandboxFor(Person)} at the call site to decide.
     */
    public CompletableFuture<ApiResponse<Order>> captureOrder(
            final String orderId, final boolean useSandbox) {
        final PaypalServerSdkClient client = pickClient(useSandbox);
        log.info("PayPal captureOrder env={} id={}", useSandbox ? "SANDBOX" : "PRODUCTION", orderId);
        return client.getOrdersController().captureOrderAsync(
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
            final List<BigDecimal> amounts,
            final Person.Id id,
            final String invoiceId,
            final String orgAbbr,
            final String description) {
        final List<PurchaseUnitRequest> result = new ArrayList<>();
        for (final BigDecimal amount : amounts) {
            log.info("PayPal PU Request: {}|{}|{}|{}", invoiceId, amount, orgAbbr, description);
            result.add(new PurchaseUnitRequest.Builder()
                    .referenceId(id.getValue())
                    .amount(toAmount(amount))
                    // FIXME: Pass this in
                    .softDescriptor("2026 Medj Conf Orlando")
                    .description(description)
                    .customId(id.getValue())
                    .invoiceId(invoiceId)
                    .softDescriptor(orgAbbr)
                    .build());
        }
        return result;
    }

    private AmountWithBreakdown toAmount(final BigDecimal amount) {
        return new AmountWithBreakdown.Builder()
                .currencyCode(USD)
                .value(amount.setScale(2, RoundingMode.HALF_UP).toPlainString())
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
