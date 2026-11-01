package org.paulsens.trip.api;

import com.stripe.net.Webhook;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.OrgCommands;
import org.paulsens.trip.action.PaymentCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.FeesPaidBy;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.PaymentProcessorConfig;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ProcessorType;
import org.paulsens.trip.pay.PaymentMailer;
import org.paulsens.trip.pay.PaymentProcessor;
import org.paulsens.trip.pay.PaymentRecorder;
import org.paulsens.trip.security.ProcessorSecrets;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The webhook door: real {@code Stripe-Signature} headers over real payloads against the in-memory secret
 * store, a real {@code PaymentCommands} over the in-memory payment table, and a stubbed processor standing in
 * for Stripe's verification read. Only the mailer is mocked, because the race this closes is a double mail.
 */
public class StripeWebhookResourceTest extends ResourceTestSupport {
    private static final String LIVE_SECRET = "whsec_live_test_value_1";
    private static final String TEST_SECRET = "whsec_test_test_value_2";

    private StripeWebhookResource resource;
    private PaymentProcessor stripe;
    private PaymentMailer mailer;
    private PaymentProcessorConfig config;

    @BeforeClass
    public void seed() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    @BeforeMethod
    public void wire() throws IOException {
        config = PaymentProcessorConfig.builder()
                .orgId(Organization.Id.from(FakeData.CFPW_ORG_ID))
                .label("CFPW Stripe").type(ProcessorType.STRIPE)
                .mode(PaymentProcessorConfig.ProcessorMode.LIVE).enabled(true)
                .publicConfig(Map.of("clientId", "pk_live_1"))
                .sandboxPublicConfig(Map.of("clientId", "pk_test_1"))
                .createdBy(Person.Id.from(FakeData.ADMIN_PERSON_ID)).created(LocalDateTime.now())
                .build();
        Assert.assertTrue(DAO.getInstance().savePaymentProcessorConfig(config));
        Assert.assertTrue(ProcessorSecrets.getInstance().put(config.getId().getValue(), Map.of(
                OrgCommands.WEBHOOK_SECRET, LIVE_SECRET, OrgCommands.WEBHOOK_SECRET_SANDBOX, TEST_SECRET)));

        stripe = Mockito.mock(PaymentProcessor.class);
        mailer = Mockito.mock(PaymentMailer.class);
        Mockito.when(mailer.sendConfirmation(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(true);
        Mockito.when(mailer.sendPaymentFailed(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(true);
        final java.util.function.Supplier<Caller> system = () -> Caller.forActor(AuditActor.system());
        bind(PaymentCommands.class, new PaymentCommands(system, new OrgCommands(system), new AuditCommands(),
                new PaymentRecorder(), (cfg, sandbox) -> stripe, () -> mailer));
        resource = resource(new StripeWebhookResource());
    }

    // ------------------------------------------------------------------ the gate

    @Test
    public void aBadOrMissingSignatureIs400AndReadsNothing() throws IOException {
        final Payment payment = created("pi_sig", false);
        final String body = succeeded(payment, "pi_sig");
        assertError(post(configId(), null, body), 400, ApiErrors.BAD_REQUEST);
        assertError(post(configId(), "t=1,v1=deadbeef", body), 400, ApiErrors.BAD_REQUEST);
        assertError(post(configId(), sign(body, "whsec_someone_elses"), body), 400, ApiErrors.BAD_REQUEST);
        assertError(post("no-such-config", sign(body, LIVE_SECRET), body), 400, ApiErrors.BAD_REQUEST);
        final long stale = (System.currentTimeMillis() / 1000) - 3600;
        assertError(post(configId(), sign(body, LIVE_SECRET, stale), body), 400, ApiErrors.BAD_REQUEST);
        Assert.assertEquals(DAO.getInstance().getPayment(payment.getPaymentId()).orElseThrow().getStatus(),
                Payment.Status.CREATED, "nothing moved");
        Mockito.verifyNoInteractions(stripe);
    }

    @Test
    public void anOversizeBodyIs413() throws IOException {
        final byte[] big = new byte[(int) StripeWebhookResource.MAX_BYTES + 1];
        assertError(resource.receive(configId(), "t=1,v1=x", (long) big.length, new ByteArrayInputStream(big)),
                413, ApiErrors.PAYLOAD_TOO_LARGE);
        assertError(resource.receive(configId(), "t=1,v1=x", null, new ByteArrayInputStream(big)),
                413, ApiErrors.PAYLOAD_TOO_LARGE);
    }

    // ------------------------------------------------------------------ events that are not ours

    @Test
    public void unrelatedUnknownAndMismatchedEventsAre200Ignored() throws IOException {
        final Payment payment = created("pi_m", false);
        final String other = event("charge.succeeded", "{\"id\":\"ch_1\",\"object\":\"charge\"}", true);
        Assert.assertEquals(entity(post(configId(), sign(other, LIVE_SECRET), other)).get("ignored"),
                "not a payment_intent event");

        final String noMeta = event(StripeWebhookResource.SUCCEEDED, intent("pi_x", "succeeded", null, null, null),
                true);
        Assert.assertTrue(((String) entity(post(configId(), sign(noMeta, LIVE_SECRET), noMeta)).get("ignored"))
                .contains("no paymentId"), "stripe trigger shapes have no metadata");

        final String unknown = event(StripeWebhookResource.SUCCEEDED,
                intent("pi_x", "succeeded", "no-such-payment", null, null), true);
        Assert.assertTrue(((String) entity(post(configId(), sign(unknown, LIVE_SECRET), unknown)).get("ignored"))
                .contains("unknown payment"));

        // The right payment, the wrong intent id: a crossed wire, audited and dropped.
        final String wrongIntent = succeeded(payment, "pi_someone_else");
        Assert.assertTrue(((String) entity(post(configId(), sign(wrongIntent, LIVE_SECRET), wrongIntent))
                .get("ignored")).contains("is not the payment's"));

        // A live payment reported through the TEST endpoint's secret.
        final String testSigned = succeeded(payment, "pi_m");
        Assert.assertTrue(((String) entity(post(configId(), sign(testSigned, TEST_SECRET), testSigned))
                .get("ignored")).contains("test-endpoint event for a live payment"));

        // livemode=false on a live config.
        final String notLive = event(StripeWebhookResource.SUCCEEDED,
                intent("pi_m", "succeeded", payment.getPaymentId(), null, null), false);
        Assert.assertTrue(((String) entity(post(configId(), sign(notLive, LIVE_SECRET), notLive)).get("ignored"))
                .contains("livemode"));

        Assert.assertEquals(DAO.getInstance().getPayment(payment.getPaymentId()).orElseThrow().getStatus(),
                Payment.Status.CREATED);
        Mockito.verifyNoInteractions(stripe);
    }

    @Test
    public void anotherConfigsEventForOurPaymentIsIgnored() throws IOException {
        final Payment payment = created("pi_other", false);
        final PaymentProcessorConfig second = PaymentProcessorConfig.builder()
                .orgId(Organization.Id.from(FakeData.CFPW_ORG_ID)).label("Second").type(ProcessorType.STRIPE)
                .mode(PaymentProcessorConfig.ProcessorMode.LIVE).enabled(true)
                .publicConfig(Map.of("clientId", "pk_live_2")).build();
        Assert.assertTrue(DAO.getInstance().savePaymentProcessorConfig(second));
        ProcessorSecrets.getInstance().put(second.getId().getValue(), Map.of(OrgCommands.WEBHOOK_SECRET, "whsec_2"));
        final String body = succeeded(payment, "pi_other");
        Assert.assertTrue(((String) entity(post(second.getId().getValue(), sign(body, "whsec_2"), body))
                .get("ignored")).contains("is not the payment's"));
    }

    // ------------------------------------------------------------------ the real work

    @Test
    public void aSucceededEventRecordsThePaymentOnceAndRedeliveryIsANoOp() throws IOException {
        final Payment payment = created("pi_ok", false);
        Mockito.when(stripe.capture("pi_ok")).thenReturn(new PaymentProcessor.CaptureResult(
                PaymentProcessor.CaptureResult.Status.COMPLETED, "pi_ok", payment.getTotalChargedCents(),
                OptionalLong.of(175L)));
        final String body = succeeded(payment, "pi_ok");

        final Response first = post(configId(), sign(body, LIVE_SECRET), body);
        assertOk(first);
        Assert.assertEquals(entity(first).get("status"), "recorded");
        final Payment stored = DAO.getInstance().getPayment(payment.getPaymentId()).orElseThrow();
        Assert.assertEquals(stored.getStatus(), Payment.Status.RECORDED);
        Assert.assertEquals(stored.getCaptureId(), "pi_ok");
        Assert.assertEquals(stored.getActualFeeCents(), Long.valueOf(175L));

        final Response again = post(configId(), sign(body, LIVE_SECRET), body);
        assertOk(again);
        Assert.assertEquals(entity(again).get("status"), "recorded");
        Mockito.verify(mailer, Mockito.times(1)).sendConfirmation(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());
        Mockito.verify(stripe, Mockito.times(1)).capture("pi_ok");
    }

    @Test
    public void aSucceededEventWeCannotVerifyAsksStripeToRetry() throws IOException {
        final Payment payment = created("pi_down", false);
        Mockito.when(stripe.capture("pi_down")).thenReturn(PaymentProcessor.CaptureResult.pending(null));
        final String body = succeeded(payment, "pi_down");
        final Response response = post(configId(), sign(body, LIVE_SECRET), body);
        Assert.assertEquals(response.getStatus(), 503, "non-2xx = Stripe redelivers");
        Assert.assertEquals(DAO.getInstance().getPayment(payment.getPaymentId()).orElseThrow().getStatus(),
                Payment.Status.CREATED);
    }

    @Test
    public void processingIsAcknowledgedWithoutChangingTheRow() throws IOException {
        final Payment payment = created("pi_proc", false);
        final String body = event(StripeWebhookResource.PROCESSING,
                intent("pi_proc", "processing", payment.getPaymentId(), null, null), true);
        Assert.assertEquals(entity(post(configId(), sign(body, LIVE_SECRET), body)).get("status"), "processing");
        Assert.assertEquals(DAO.getInstance().getPayment(payment.getPaymentId()).orElseThrow().getStatus(),
                Payment.Status.CREATED);
        Mockito.verifyNoInteractions(stripe);
    }

    @Test
    public void aReturnedBankDebitFailsAndMailsButACardDeclineOnlyAudits() throws IOException {
        final Payment card = created("pi_card", false);
        final String declined = event(StripeWebhookResource.FAILED,
                intent("pi_card", "requires_payment_method", card.getPaymentId(), "card", "Your card was declined."),
                true);
        Assert.assertEquals(entity(post(configId(), sign(declined, LIVE_SECRET), declined)).get("status"),
                "retryable");
        Assert.assertEquals(DAO.getInstance().getPayment(card.getPaymentId()).orElseThrow().getStatus(),
                Payment.Status.CREATED, "the payer retries in the form");

        final Payment bank = created("pi_bank", false);
        final String returned = event(StripeWebhookResource.FAILED,
                intent("pi_bank", "requires_payment_method", bank.getPaymentId(), "us_bank_account",
                        "The customer's account has insufficient funds."), true);
        Assert.assertEquals(entity(post(configId(), sign(returned, LIVE_SECRET), returned)).get("status"), "failed");
        Assert.assertEquals(DAO.getInstance().getPayment(bank.getPaymentId()).orElseThrow().getStatus(),
                Payment.Status.FAILED);
        Mockito.verify(mailer).sendPaymentFailed(ArgumentMatchers.argThat(p -> bank.getPaymentId()
                .equals(p.getPaymentId())), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq("Stripe"), ArgumentMatchers.eq("The customer's account has insufficient funds."),
                ArgumentMatchers.any());
        Assert.assertEquals(entity(post(configId(), sign(returned, LIVE_SECRET), returned)).get("status"),
                "unchanged", "redelivery of the failure changes nothing and mails nobody again");
        Mockito.verify(mailer, Mockito.times(1)).sendPaymentFailed(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());
    }

    @Test
    public void aCanceledIntentCancelsACreatedPayment() throws IOException {
        final Payment payment = created("pi_c", false);
        final String body = event(StripeWebhookResource.CANCELED,
                intent("pi_c", "canceled", payment.getPaymentId(), null, null), true);
        Assert.assertEquals(entity(post(configId(), sign(body, LIVE_SECRET), body)).get("status"), "cancelled");
        Assert.assertEquals(DAO.getInstance().getPayment(payment.getPaymentId()).orElseThrow().getStatus(),
                Payment.Status.CANCELLED);
        Assert.assertEquals(entity(post(configId(), sign(body, LIVE_SECRET), body)).get("status"), "unchanged");
    }

    @Test
    public void aSandboxPaymentIsFinishedByTheTestEndpointsSecret() throws IOException {
        final Payment payment = created("pi_sb", true);
        Mockito.when(stripe.capture("pi_sb")).thenReturn(new PaymentProcessor.CaptureResult(
                PaymentProcessor.CaptureResult.Status.COMPLETED, "pi_sb", payment.getTotalChargedCents(),
                OptionalLong.empty()));
        final String body = event(StripeWebhookResource.SUCCEEDED,
                intent("pi_sb", "succeeded", payment.getPaymentId(), null, null), false);
        Assert.assertEquals(entity(post(configId(), sign(body, TEST_SECRET), body)).get("status"), "sandbox");
        Mockito.verifyNoInteractions(mailer);
    }

    @Test
    public void anUnhandledIntentEventIsIgnored() throws IOException {
        final Payment payment = created("pi_u", false);
        final String body = event("payment_intent.created",
                intent("pi_u", "requires_payment_method", payment.getPaymentId(), null, null), true);
        Assert.assertTrue(((String) entity(post(configId(), sign(body, LIVE_SECRET), body)).get("ignored"))
                .contains("unhandled"));
    }

    // ------------------------------------------------------------------ helpers

    private String configId() {
        return config.getId().getValue();
    }

    private Payment created(final String intentId, final boolean sandbox) throws IOException {
        final Person payer = Person.builder().first("Pay").last(RandomData.genAlpha(6))
                .email("hook." + RandomData.genAlpha(8) + "@example.com").build();
        Assert.assertTrue(DAO.getInstance().savePerson(payer));
        final Payment payment = Payment.builder()
                .tripId(FakeData.FAKE_TRIP_ID).orgId(FakeData.CFPW_ORG_ID).payerId(payer.getId())
                .processorConfigId(configId()).processorType(ProcessorType.STRIPE)
                .allocations(List.of(new Payment.Allocation(payer.getId(), 5000L)))
                .feesPaidBy(FeesPaidBy.ORGANIZATION).creditFeeCents(175L).totalChargedCents(5000L)
                .sandbox(sandbox).orderRef(intentId).createdAt(LocalDateTime.now())
                .build();
        DAO.getInstance().createPayment(payment);
        return DAO.getInstance().getPayment(payment.getPaymentId()).orElseThrow();
    }

    private Response post(final String configId, final String signature, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        final InputStream in = new ByteArrayInputStream(bytes);
        return resource.receive(configId, signature, (long) bytes.length, in);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entity(final Response response) {
        Assert.assertEquals(response.getStatus(), 200, String.valueOf(response.getEntity()));
        return (Map<String, Object>) response.getEntity();
    }

    private static String succeeded(final Payment payment, final String intentId) {
        return event(StripeWebhookResource.SUCCEEDED, intent(intentId, "succeeded", payment.getPaymentId(),
                null, null), true);
    }

    private static String event(final String type, final String object, final boolean livemode) {
        return "{\"id\":\"evt_" + RandomData.genAlpha(8) + "\",\"object\":\"event\",\"api_version\":\"2099-01-01\","
                + "\"type\":\"" + type + "\",\"livemode\":" + livemode + ",\"created\":1700000000,"
                + "\"data\":{\"object\":" + object + "}}";
    }

    private static String intent(final String id, final String status, final String paymentId,
            final String failedMethod, final String failureMessage) {
        final StringBuilder json = new StringBuilder("{\"id\":\"" + id + "\",\"object\":\"payment_intent\","
                + "\"status\":\"" + status + "\",\"amount\":5000,\"currency\":\"usd\"");
        if (paymentId != null) {
            json.append(",\"metadata\":{\"paymentId\":\"").append(paymentId).append("\"}");
        }
        if (failedMethod != null) {
            json.append(",\"last_payment_error\":{\"message\":\"").append(failureMessage)
                    .append("\",\"payment_method\":{\"id\":\"pm_1\",\"type\":\"").append(failedMethod).append("\"}}");
        }
        return json.append('}').toString();
    }

    private static String sign(final String payload, final String secret) {
        return sign(payload, secret, System.currentTimeMillis() / 1000);
    }

    /** Stripe's header shape: {@code t=<unix seconds>,v1=<hex hmac-sha256(secret, t + "." + payload)>}. */
    private static String sign(final String payload, final String secret, final long timestamp) {
        try {
            return "t=" + timestamp + ",v1=" + Webhook.Util.computeHmacSha256(secret, timestamp + "." + payload);
        } catch (final java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The Cached import is used by the DAO reads above; keep the compiler honest. */
    static Cached unused() {
        return Cached.NO;
    }
}
