package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.config.KnownSettings;

/**
 * Starting and completing a payment from a client that is not a browser -- a thin edge over
 * {@code PaymentCommands}, the SAME Java the payment page calls. A native client supplies its own return
 * address (allowlisted), receives the approval URL as data, opens it itself, and comes back to
 * {@code /complete} explicitly rather than by loading a page.
 */
@Slf4j
@Path("payments")
@TripApi
public class PaymentsResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.PAYMENTS_V1;

    @Override
    protected String versionedType() {
        return V1;
    }

    /** Live totals for a native client's payment screen: same math the page shows. POST: a map body. */
    @POST
    @Path("quote")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response quote(final PaymentStart body) {
        final org.paulsens.trip.model.Trip trip = findTrip(body == null ? null : body.tripId());
        if (trip == null) {
            return error(404, ApiErrors.NOT_FOUND, "Unknown trip.");
        }
        final org.paulsens.trip.action.PaymentCommands commands = paymentCommands();
        final org.paulsens.trip.action.PaymentCommands.Quote quote =
                commands.quote(trip, amountsOf(body), body.donation());
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("creditCents", quote.getCreditCents());
        result.put("creditFeeCents", quote.getCreditFeeCents());
        result.put("donationCents", quote.getDonationCents());
        result.put("donationFeeCents", quote.getDonationFeeCents());
        result.put("totalCents", quote.getTotalCents());
        result.put("payerPaysFee", quote.isPayerPays());
        result.put("payable", quote.isPayable());
        return ok(result);
    }

    /** Starts a payment; answers the approval URL to open. The return URL must be allowlisted. */
    @POST
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response start(@HeaderParam(CSRF_HEADER) final String csrf, final PaymentStart body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final org.paulsens.trip.model.Trip trip = findTrip(body == null ? null : body.tripId());
        if (trip == null) {
            return error(404, ApiErrors.NOT_FOUND, "Unknown trip.");
        }
        if (body.returnUrl() == null || !isAllowed(body.returnUrl())) {
            return error(400, ApiErrors.BAD_REQUEST,
                    "returnUrl must start with a configured allowed prefix.");
        }
        final String approvalUrl = paymentCommands().startPayment(trip, amountsOf(body),
                body.donation(), Boolean.TRUE.equals(body.sandbox()), body.returnUrl());
        if (approvalUrl == null) {
            return error(400, ApiErrors.BAD_REQUEST,
                    "The payment could not be started (check the amounts and the trip's configuration).");
        }
        return ok(Map.of("approvalUrl", approvalUrl));
    }

    /** Completes an approved payment (capture + record + mail); safe to retry. */
    @POST
    @Path("{paymentId}/complete")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response complete(@PathParam("paymentId") final String paymentId,
            @HeaderParam(CSRF_HEADER) final String csrf, final CompleteRequest body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final org.paulsens.trip.action.PaymentCommands.Completion outcome =
                paymentCommands().completePayment(paymentId, body == null ? null : body.token());
        if ("notfound".equals(outcome.getStatus())) {
            return error(404, ApiErrors.NOT_FOUND, outcome.getMessage());
        }
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", outcome.getStatus());
        result.put("message", outcome.getMessage());
        result.put("dryRunLines", outcome.getDryRunLines());
        return ok(result);
    }

    /** Cancels a not-yet-captured payment. */
    @POST
    @Path("{paymentId}/cancel")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response cancel(@PathParam("paymentId") final String paymentId,
            @HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        return ok(Map.of("cancelled", paymentCommands().cancelPayment(paymentId)));
    }

    private org.paulsens.trip.action.PaymentCommands paymentCommands() {
        return new org.paulsens.trip.action.PaymentCommands(this::caller);
    }

    /**
     * Whether a client-supplied address is one we are willing to send a payer back to.
     *
     * <p>The allowlist is a setting so an app's custom scheme can be added at release time rather than
     * needing a deploy. The matching rule itself lives in {@link RedirectAllowlist}, where it is tested.
     */
    private static boolean isAllowed(final String url) {
        return RedirectAllowlist.allows(
                url, Beans.get(ConfigCommands.class).getString(KnownSettings.PAYMENT_RETURN_URL_PREFIXES));
    }

    private static Map<String, Object> amountsOf(final PaymentStart body) {
        return (body == null || body.amounts() == null) ? Map.of() : new LinkedHashMap<>(body.amounts());
    }

    /** The v2 start/quote body: per-person dollar amounts keyed by person id, plus an optional donation. */
    public record PaymentStart(String tripId, Map<String, Object> amounts, String donation,
            Boolean sandbox, String returnUrl) {
    }

    /** The v2 complete body: the processor's return token (PayPal's {@code token} query param). */
    public record CompleteRequest(String token) {
    }

}
