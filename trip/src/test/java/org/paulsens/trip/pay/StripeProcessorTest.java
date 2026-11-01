package org.paulsens.trip.pay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ProcessorType;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/**
 * Drives the REAL Stripe SDK against a local fake of api.stripe.com (the SDK's base URL is a per-request
 * option), so the request shape Stripe will see and the SDK's own deserialization are both exercised.
 */
public class StripeProcessorTest {
    private static final String PK = "pk_test_123";
    private static final String SK = "sk_test_456";

    private HttpServer server;
    private String baseUrl;
    /** path -> canned response (status + JSON body). */
    private final Map<String, Canned> routes = new ConcurrentHashMap<>();
    private final List<Seen> seen = new ArrayList<>();

    private record Canned(int status, String body) {
    }

    private record Seen(String method, String path, String auth, String idempotency, String form) {
    }

    @BeforeClass
    public void startFake() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::serve);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterClass
    public void stopFake() {
        server.stop(0);
    }

    @BeforeMethod
    public void reset() {
        routes.clear();
        synchronized (seen) {
            seen.clear();
        }
    }

    private void serve(final HttpExchange exchange) throws IOException {
        final String path = exchange.getRequestURI().getPath();
        final String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        synchronized (seen) {
            seen.add(new Seen(exchange.getRequestMethod(), path,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    URLDecoder.decode(form, StandardCharsets.UTF_8)));
        }
        final Canned canned = routes.getOrDefault(path, new Canned(404,
                "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"resource_missing\","
                        + "\"message\":\"No such payment_intent\"}}"));
        final byte[] bytes = canned.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(canned.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Seen lastSeen() {
        synchronized (seen) {
            return seen.getLast();
        }
    }

    private StripeProcessor processor() {
        return new StripeProcessor(PK, SK, true, baseUrl);
    }

    @Test
    public void keysAreRequiredAndMustMatchTheMode() {
        assertThrows(PaymentProcessor.ProcessorException.class, () -> new StripeProcessor(null, SK, true));
        assertThrows(PaymentProcessor.ProcessorException.class, () -> new StripeProcessor(PK, " ", true));
        assertThrows("a live key in the test set", PaymentProcessor.ProcessorException.class,
                () -> new StripeProcessor(PK, "sk_live_9", true));
        assertThrows("a test key in the live set", PaymentProcessor.ProcessorException.class,
                () -> new StripeProcessor("pk_live_1", "sk_test_9", false));
        assertThrows(PaymentProcessor.ProcessorException.class,
                () -> new StripeProcessor("pk_live_1", "not-a-key", false));
        assertEquals(new StripeProcessor("pk_live_1", "rk_live_9", false).type(), ProcessorType.STRIPE);
        assertEquals(processor().displayName(), "Stripe");
        assertNull(StripeProcessor.keyModeProblem(null, true, "secret"));
        assertTrue(StripeProcessor.keyModeProblem("sk_live_1", true, "secret").contains("TEST-mode"));
        assertTrue(StripeProcessor.keyModeProblem("sk_test_1", false, "secret").contains("LIVE-mode"));
    }

    @Test
    public void createOrderMakesAnInlinePaymentIntentWithOurMetadata() {
        routes.put("/v1/payment_intents", new Canned(200, "{\"id\":\"pi_1\",\"object\":\"payment_intent\","
                + "\"client_secret\":\"pi_1_secret_x\",\"status\":\"requires_payment_method\"}"));
        final Payment payment = payment();
        final PaymentProcessor.CreatedOrder order =
                processor().createOrder(payment, "Rome - payment", "http://r", "http://c");
        assertTrue(order.isInline());
        assertNull(order.approvalUrl());
        assertEquals(order.orderRef(), "pi_1");
        assertEquals(order.inline().publishableKey(), PK);
        assertEquals(order.inline().clientSecret(), "pi_1_secret_x");

        final Seen sent = lastSeen();
        assertEquals(sent.method(), "POST");
        assertEquals(sent.auth(), "Bearer " + SK, "the key travels per request, never process-global");
        assertEquals(sent.idempotency(), payment.getPaymentId(), "a double-submit creates ONE intent");
        assertTrue(sent.form().contains("amount=250000"), sent.form());
        assertTrue(sent.form().contains("currency=usd"), sent.form());
        assertTrue(sent.form().contains("automatic_payment_methods[enabled]=true"),
                "methods are dashboard-managed: " + sent.form());
        assertFalse(sent.form().contains("payment_method_types"), sent.form());
        assertTrue(sent.form().contains("metadata[paymentId]=" + payment.getPaymentId()), sent.form());
        assertTrue(sent.form().contains("metadata[sandbox]=false"), sent.form());
    }

    @Test
    public void createOrderRefusesWhenStripeDoesNot() {
        routes.put("/v1/payment_intents", new Canned(402, "{\"error\":{\"type\":\"card_error\","
                + "\"message\":\"nope\"}}"));
        assertThrows(PaymentProcessor.ProcessorException.class,
                () -> processor().createOrder(payment(), "d", "http://r", "http://c"));

        routes.put("/v1/payment_intents", new Canned(200, "{\"id\":\"pi_2\",\"object\":\"payment_intent\"}"));
        assertThrows("no client secret = nothing the page could confirm",
                PaymentProcessor.ProcessorException.class,
                () -> processor().createOrder(payment(), "d", "http://r", "http://c"));
    }

    @Test
    public void captureIsAVerificationReadAndSucceededIsTheOnlySuccess() {
        routes.put("/v1/payment_intents/pi_ok", new Canned(200, intent("pi_ok", "succeeded", 250000L, "usd")
                + ",\"latest_charge\":{\"id\":\"ch_1\",\"object\":\"charge\","
                + "\"balance_transaction\":{\"id\":\"txn_1\",\"object\":\"balance_transaction\",\"fee\":7280}}}"));
        final PaymentProcessor.CaptureResult ok = processor().capture("pi_ok");
        assertEquals(ok.status(), PaymentProcessor.CaptureResult.Status.COMPLETED);
        assertEquals(ok.captureId(), "pi_ok", "the PaymentIntent id is the capture reference");
        assertEquals(ok.grossCents(), 250000L);
        assertEquals(ok.actualFeeCents().getAsLong(), 7280L);
        final Seen sent = lastSeen();
        assertEquals(sent.method(), "GET");
        assertTrue(sent.path().endsWith("/v1/payment_intents/pi_ok"));

        routes.put("/v1/payment_intents/pi_nofee", new Canned(200, intent("pi_nofee", "succeeded", 100L, "usd")
                + "}"));
        assertTrue(processor().capture("pi_nofee").actualFeeCents().isEmpty(),
                "no expanded charge = fee unknown, not zero");
    }

    @Test
    public void processingIsTheSettlingPendingAndTheFormStatesArePlainPending() {
        routes.put("/v1/payment_intents/pi_bank", new Canned(200, intent("pi_bank", "processing", 0L, "usd")
                + "}"));
        final PaymentProcessor.CaptureResult settling = processor().capture("pi_bank");
        assertEquals(settling.status(), PaymentProcessor.CaptureResult.Status.PENDING);
        assertTrue(settling.isSettling());
        assertEquals(settling.captureId(), "pi_bank");

        for (final String status : List.of("requires_payment_method", "requires_confirmation",
                "requires_action", "requires_capture")) {
            routes.put("/v1/payment_intents/pi_open", new Canned(200, intent("pi_open", status, 0L, "usd") + "}"));
            final PaymentProcessor.CaptureResult open = processor().capture("pi_open");
            assertEquals(open.status(), PaymentProcessor.CaptureResult.Status.PENDING, status);
            assertFalse(open.isSettling(), status);
        }
    }

    @Test
    public void neverAZeroSuccessAndCanceledOrMissingIsFailed() {
        routes.put("/v1/payment_intents/pi_zero", new Canned(200, intent("pi_zero", "succeeded", 0L, "usd")
                + "}"));
        assertEquals(processor().capture("pi_zero").status(), PaymentProcessor.CaptureResult.Status.FAILED);

        routes.put("/v1/payment_intents/pi_eur", new Canned(200, intent("pi_eur", "succeeded", 500L, "eur")
                + "}"));
        assertEquals(processor().capture("pi_eur").status(), PaymentProcessor.CaptureResult.Status.FAILED);

        routes.put("/v1/payment_intents/pi_gone", new Canned(200, intent("pi_gone", "canceled", 0L, "usd")
                + "}"));
        assertEquals(processor().capture("pi_gone").status(), PaymentProcessor.CaptureResult.Status.FAILED);

        assertEquals(processor().capture("pi_unknown").status(), PaymentProcessor.CaptureResult.Status.FAILED,
                "resource_missing is definitive");
    }

    @Test
    public void aStripeOutageIsPendingNotFailed() {
        routes.put("/v1/payment_intents/pi_down", new Canned(500, "{\"error\":{\"type\":\"api_error\","
                + "\"message\":\"down\"}}"));
        final PaymentProcessor.CaptureResult result = processor().capture("pi_down");
        assertEquals(result.status(), PaymentProcessor.CaptureResult.Status.PENDING);
        assertFalse(result.isSettling());

        final StripeProcessor unreachable = new StripeProcessor(PK, SK, true, "http://127.0.0.1:1");
        assertEquals(unreachable.capture("pi_x").status(), PaymentProcessor.CaptureResult.Status.PENDING,
                "a paid intent must never be marked FAILED because Stripe was unreachable");
    }

    @Test
    public void cancelReleasesTheIntentAndSwallowsRefusals() {
        routes.put("/v1/payment_intents/pi_c", new Canned(200, intent("pi_c", "requires_payment_method", 0L,
                "usd") + "}"));
        routes.put("/v1/payment_intents/pi_c/cancel", new Canned(200, intent("pi_c", "canceled", 0L, "usd")
                + "}"));
        processor().cancel("pi_c");
        final Seen sent = lastSeen();
        assertEquals(sent.method(), "POST");
        assertTrue(sent.path().endsWith("/v1/payment_intents/pi_c/cancel"));

        processor().cancel("pi_missing");    // 404 -> logged, not thrown
        processor().cancel(null);
        processor().cancel(" ");
    }

    @Test
    public void pingReadsTheBalance() {
        routes.put("/v1/balance", new Canned(200, "{\"object\":\"balance\",\"available\":[],\"pending\":[]}"));
        assertNull(StripeProcessor.pingKey(SK, true, baseUrl));
        assertEquals(lastSeen().auth(), "Bearer " + SK);

        routes.put("/v1/balance", new Canned(401, "{\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"Invalid API Key provided\"}}"));
        assertTrue(StripeProcessor.pingKey(SK, true, baseUrl).contains("rejected"));

        assertTrue(StripeProcessor.pingKey("sk_live_1", true, baseUrl).contains("TEST-mode"),
                "the mode check happens before any network call");
        assertTrue(StripeProcessor.pingKey(SK, true, "http://127.0.0.1:1").contains("Could not reach"));
    }

    @Test
    public void registeringAWalletDomainReportsEachWalletsStatus() {
        routes.put("/v1/payment_method_domains", new Canned(200, "{\"id\":\"pmd_1\","
                + "\"object\":\"payment_method_domain\",\"domain_name\":\"acme.unitetrip.com\","
                + "\"apple_pay\":{\"status\":\"active\"},\"google_pay\":{\"status\":\"active\"},"
                + "\"link\":{\"status\":\"inactive\"}}"));
        final String outcome = StripeProcessor.registerDomain(SK, true, "acme.unitetrip.com", baseUrl);
        assertTrue(outcome.startsWith("Registered acme.unitetrip.com"), outcome);
        assertTrue(outcome.contains("Apple Pay: active"), outcome);
        assertTrue(outcome.contains("Link: inactive"), outcome);
        assertTrue(lastSeen().form().contains("domain_name=acme.unitetrip.com"), lastSeen().form());

        routes.put("/v1/payment_method_domains", new Canned(400, "{\"error\":{\"type\":"
                + "\"invalid_request_error\",\"message\":\"Invalid domain\"}}"));
        assertTrue(StripeProcessor.registerDomain(SK, true, "acme.unitetrip.com", baseUrl)
                .startsWith("Stripe refused"));
        assertTrue(StripeProcessor.registerDomain(SK, true, "https://acme", baseUrl).startsWith("Stripe refused"),
                "a bare host is required");
        assertTrue(StripeProcessor.registerDomain("sk_live_1", true, "acme", baseUrl).startsWith("Stripe refused"),
                "the key's mode is checked first");
    }

    @Test
    public void theDomainsFacadeChecksBeforeAnyNetworkCall() {
        assertTrue(StripeDomains.register("sk_live_1", true, "acme.unitetrip.com").startsWith("Stripe refused"),
                "a live key in the sandbox set is refused before api.stripe.com is contacted");
        assertTrue(StripeDomains.register("sk_test_1", true, "https://acme").startsWith("Stripe refused"),
                "a URL is not a bare host");
    }

    private static String intent(final String id, final String status, final long received,
            final String currency) {
        return "{\"id\":\"" + id + "\",\"object\":\"payment_intent\",\"status\":\"" + status
                + "\",\"amount_received\":" + received + ",\"currency\":\"" + currency + "\"";
    }

    private static Payment payment() {
        return Payment.builder().payerId(Person.Id.newInstance()).tripId("trip-1").totalChargedCents(250000L)
                .build();
    }
}
