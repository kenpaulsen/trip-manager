package org.paulsens.trip.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.OrgCommands;
import org.paulsens.trip.action.PaymentCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.PaymentProcessorConfig;
import org.paulsens.trip.model.ProcessorType;
import org.paulsens.trip.security.ProcessorSecrets;

/**
 * Stripe's webhook endpoint, one per processor config: {@code POST /api/webhooks/stripe/{configId}}.
 *
 * <p><b>Deliberately NOT {@code @TripApi}</b>: Stripe's servers hold no session and send no CSRF header. The
 * authority here is the {@code Stripe-Signature} HMAC over the raw body, checked against THIS config's stored
 * signing secret(s) ({@code webhookSecret} for the live endpoint, {@code webhookSecretSandbox} for the test
 * one) -- which is why the config id is in the path: it names the secret to verify with, and nothing is read
 * from the body before the signature passes.
 *
 * <p>What the event is allowed to do: name a payment (a {@code payment_intent.*} event by its object, a
 * {@code charge.refunded} by the charge's intent). Amounts and outcomes are never taken from the event;
 * the trusted {@code PaymentCommands.*FromWebhook} paths re-read the PaymentIntent from Stripe with the
 * organization's own key, so a forged or stale event can at worst trigger a verification read. The fields
 * are read from the event's RAW JSON rather than the SDK's typed object: {@code getObject()} is empty
 * whenever the endpoint's API version differs from the SDK's pinned one, a classic silent failure.
 *
 * <p>Answers: 400 for a bad or missing signature (Stripe will retry, which is right: a misconfigured secret
 * gets noticed in the dashboard); 413 for an oversize body; 200 for events that are not ours, do not match
 * the stored row, or are already handled (retrying cannot fix those, and the state machine makes
 * re-delivery a no-op); 503 ONLY when a succeeded event could not be completed because Stripe itself was
 * unreachable, so that Stripe retries the delivery.
 */
@Slf4j
@Path("webhooks/stripe")
public class StripeWebhookResource extends BaseResource {
    /** A payment_intent event is a few KB; anything bigger is not Stripe. */
    static final long MAX_BYTES = 256 * 1024;
    static final String SUCCEEDED = "payment_intent.succeeded";
    static final String PROCESSING = "payment_intent.processing";
    static final String FAILED = "payment_intent.payment_failed";
    static final String CANCELED = "payment_intent.canceled";
    /** A dashboard refund (full or partial); the event's object is the CHARGE, not the intent. */
    static final String REFUNDED = "charge.refunded";
    /** A refund that changed after creation (failed, canceled): re-read the truth exactly like a refund. */
    static final String REFUND_UPDATED = "charge.refund.updated";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    protected String versionedType() {
        return MediaType.APPLICATION_JSON;
    }

    @POST
    @Path("{configId}")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response receive(@PathParam("configId") final String configId,
            @HeaderParam("Stripe-Signature") final String signature,
            @HeaderParam("Content-Length") final Long contentLength, final InputStream body) throws IOException {
        final Optional<byte[]> bytes = readUpload(body, contentLength, MAX_BYTES);
        if (bytes.isEmpty()) {
            return error(413, ApiErrors.PAYLOAD_TOO_LARGE, "Webhook body too large.");
        }
        final String payload = new String(bytes.get(), StandardCharsets.UTF_8);
        if (configId == null || configId.isBlank() || signature == null || signature.isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, "Missing Stripe-Signature.");
        }
        final Verified verified = verify(payload, signature, configId.trim());
        if (verified == null) {
            log.warn("Stripe webhook for config {} failed signature verification", configId);
            return error(400, ApiErrors.BAD_REQUEST, "Signature verification failed.");
        }
        final Event event = verified.event();
        final String type = event.getType() == null ? "" : event.getType();
        final boolean chargeEvent = REFUNDED.equals(type) || REFUND_UPDATED.equals(type);
        if (!type.startsWith("payment_intent.") && !chargeEvent) {
            return ignored(event, "not a payment_intent event");
        }
        final JsonNode object = JSON.readTree(event.getDataObjectDeserializer().getRawJson());
        // A payment_intent event's object IS the intent; a charge or refund event's object names it.
        final String intentId = chargeEvent
                ? object.path("payment_intent").asText(null) : object.path("id").asText(null);
        final Payment payment = paymentOf(object, intentId);
        if (payment == null) {
            return ignored(event, "no payment matches the event (metadata.paymentId "
                    + object.path("metadata").path("paymentId").asText(null) + ", intent " + intentId + ")");
        }
        final String mismatch = mismatch(payment, configId.trim(), intentId, event, verified.sandbox());
        if (mismatch != null) {
            audit().payment(payment, false, "Webhook event " + event.getId() + " (" + type
                    + ") did not match the stored payment: " + mismatch, AuditActor.system());
            return ignored(event, mismatch);
        }
        return dispatch(type, payment, object, event);
    }

    /**
     * Our row for the event: by {@code metadata.paymentId} (set on the intent at creation; Stripe copies the
     * intent's metadata onto its charge), else by the intent id, which every row stores as its
     * {@code orderRef} (a refund object carries no metadata of ours). The scan is bounded by the payments
     * table, which is small, and only runs when the metadata is absent.
     */
    private static Payment paymentOf(final JsonNode object, final String intentId) {
        final String paymentId = object.path("metadata").path("paymentId").asText(null);
        if (paymentId != null && !paymentId.isBlank()) {
            return DAO.getInstance().getPayment(paymentId).orElse(null);
        }
        if (intentId == null || intentId.isBlank()) {
            return null;
        }
        return DAO.getInstance().getAllPayments().stream()
                .filter(candidate -> intentId.equals(candidate.getOrderRef()))
                .findFirst().orElse(null);
    }

    private Response dispatch(final String type, final Payment payment, final JsonNode intent,
            final Event event) {
        final PaymentCommands commands = Beans.get(PaymentCommands.class);
        switch (type) {
            case SUCCEEDED -> {
                final PaymentCommands.Completion outcome = commands.completeFromWebhook(payment.getPaymentId());
                if ("pending".equals(outcome.getStatus())) {
                    // Stripe says succeeded but our verification read could not confirm it (Stripe unreachable,
                    // or a stale read): tell Stripe to deliver again rather than dropping the event.
                    log.warn("Stripe webhook {}: succeeded event but verification answered pending; asking "
                            + "for a retry", event.getId());
                    return Response.status(503).type(MediaType.APPLICATION_JSON)
                            .entity(Map.of("status", "pending", "retry", true)).build();
                }
                return ok(Map.of("status", outcome.getStatus()));
            }
            case PROCESSING -> {
                return ok(Map.of("status", "processing"));
            }
            case FAILED -> {
                final String method = intent.path("last_payment_error").path("payment_method").path("type")
                        .asText("");
                final String reason = intent.path("last_payment_error").path("message")
                        .asText("the payment method was declined");
                if ("us_bank_account".equals(method)) {
                    // Accepted days ago, now returned by the bank: terminal, and the payer must be told.
                    return ok(Map.of("status", commands.failFromWebhook(payment.getPaymentId(), reason)
                            ? "failed" : "unchanged"));
                }
                commands.noteFailure(payment.getPaymentId(), reason);
                return ok(Map.of("status", "retryable"));
            }
            case CANCELED -> {
                return ok(Map.of("status", commands.cancelFromWebhook(payment.getPaymentId())
                        ? "cancelled" : "unchanged"));
            }
            case REFUNDED, REFUND_UPDATED -> {
                // The amount is re-read from Stripe inside; a read that fails asks for a redelivery.
                final PaymentCommands.Completion outcome = commands.refundFromWebhook(payment.getPaymentId());
                if ("pending".equals(outcome.getStatus())) {
                    log.warn("Stripe webhook {}: refund event but the refund read answered pending; asking "
                            + "for a retry", event.getId());
                    return Response.status(503).type(MediaType.APPLICATION_JSON)
                            .entity(Map.of("status", "pending", "retry", true)).build();
                }
                return ok(Map.of("status", outcome.getStatus()));
            }
            default -> {
                return ignored(event, "unhandled type " + type);
            }
        }
    }

    /**
     * Why a verified event does not belong to {@code payment}, or null when it does. A mismatch is a
     * misconfiguration (two configs sharing an endpoint, a live event on a sandbox row), never something a
     * retry fixes, so the caller audits it and answers 200.
     */
    static String mismatch(final Payment payment, final String configId, final String intentId,
            final Event event, final boolean verifiedWithSandboxSecret) {
        if (!configId.equals(payment.getProcessorConfigId())) {
            return "config " + configId + " is not the payment's " + payment.getProcessorConfigId();
        }
        if (payment.getProcessorType() != ProcessorType.STRIPE) {
            return "the payment is not a Stripe payment";
        }
        if (intentId == null || !intentId.equals(payment.getOrderRef())) {
            return "intent " + intentId + " is not the payment's " + payment.getOrderRef();
        }
        if (payment.isSandbox() != verifiedWithSandboxSecret) {
            return "a " + (verifiedWithSandboxSecret ? "test" : "live") + "-endpoint event for a "
                    + (payment.isSandbox() ? "sandbox" : "live") + " payment";
        }
        final PaymentProcessorConfig config = configOf(payment);
        if (config == null) {
            return "the payment's processor config no longer exists";
        }
        final boolean testMode = verifiedWithSandboxSecret
                || config.getMode() == PaymentProcessorConfig.ProcessorMode.SANDBOX;
        if (event.getLivemode() != null && event.getLivemode() == testMode) {
            return "livemode=" + event.getLivemode() + " disagrees with the config's mode";
        }
        return null;
    }

    private static PaymentProcessorConfig configOf(final Payment payment) {
        if (payment.getOrgId() == null || payment.getProcessorConfigId() == null) {
            return null;
        }
        return DAO.getInstance().getPaymentProcessorConfig(Organization.Id.from(payment.getOrgId()),
                PaymentProcessorConfig.Id.from(payment.getProcessorConfigId()), Cached.NO).orElse(null);
    }

    /** The event, and WHICH secret verified it (the test endpoint's or the live one's); null when neither. */
    static Verified verify(final String payload, final String signature, final String configId) {
        final Map<String, String> secrets = ProcessorSecrets.getInstance().get(configId);
        final Event live = tryVerify(payload, signature, secrets.get(OrgCommands.WEBHOOK_SECRET));
        if (live != null) {
            return new Verified(live, false);
        }
        final Event test = tryVerify(payload, signature, secrets.get(OrgCommands.WEBHOOK_SECRET_SANDBOX));
        return (test == null) ? null : new Verified(test, true);
    }

    private static Event tryVerify(final String payload, final String signature, final String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        try {
            return Webhook.constructEvent(payload, signature, secret);
        } catch (final SignatureVerificationException ex) {
            return null;
        }
    }

    private Response ignored(final Event event, final String why) {
        log.info("Stripe webhook {} ({}) ignored: {}", event.getId(), event.getType(), why);
        return ok(Map.of("ignored", why));
    }

    private static AuditCommands audit() {
        return new AuditCommands();
    }

    record Verified(Event event, boolean sandbox) {
    }
}
