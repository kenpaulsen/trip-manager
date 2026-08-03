package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.PayCommands;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.model.Person;

/**
 * Starting and completing a PayPal payment from a client that is not a browser.
 *
 * <p>The web checkout ({@code PayCommands.initPayment}) derives its return and cancel addresses from the page
 * the payer was on and then redirects the browser. A native client has neither, so it supplies its own
 * addresses -- a universal link or a custom scheme -- receives the approval URL as data, opens it itself, and
 * comes back to {@code capture} explicitly rather than by loading a page.
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

    /**
     * Creates an order and returns where to send the payer.
     *
     * <p>Only ever for the caller themselves or somebody whose booking they manage. Letting one person start a
     * payment "for" another would attach a stranger's PayPal account to somebody else's balance.
     */
    @POST
    @Path("orders")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response createOrder(
            @HeaderParam(CSRF_HEADER) final String csrf, final OrderRequest body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (body == null || body.amount() == null || body.amount() <= 0) {
            return error(400, ApiErrors.BAD_REQUEST, "A payment amount greater than $0 is required.");
        }
        final Person.Id subject = (body.userId() == null || body.userId().isBlank())
                ? personId() : Person.Id.from(body.userId());
        final Person payer = findPerson(personId());
        if (!subject.equals(personId()) && !privileges().canActFor(payer, subject)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to pay on this person's behalf.");
        }
        final String returnUrl = body.returnUrl();
        final String cancelUrl = body.cancelUrl();
        if (!isAllowed(returnUrl) || !isAllowed(cancelUrl)) {
            return error(400, ApiErrors.BAD_REQUEST,
                    "returnUrl and cancelUrl must start with a configured allowed prefix.");
        }
        final Optional<String> approvalUrl = Beans.get(PayCommands.class)
                .createApprovalUrl(payer, subject, body.amount(), body.description(), returnUrl, cancelUrl);
        if (approvalUrl.isEmpty()) {
            return error(502, ApiErrors.INTERNAL, "PayPal did not return a payment URL.");
        }
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("approvalUrl", approvalUrl.get());
        result.put("userId", subject.getValue());
        return ok(result);
    }

    /**
     * Captures an approved order and records the transaction.
     *
     * <p>{@code captureAndSave} dedupes internally, which matters here more than it does on the web: a mobile
     * client that loses its connection mid-capture has no way to know whether the call landed, so retrying is
     * the correct behaviour and must not charge or record twice.
     */
    @POST
    @Path("orders/{orderId}/capture")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response capture(
            @PathParam("orderId") final String orderId,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final CaptureRequest body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Person.Id subject = (body == null || body.userId() == null || body.userId().isBlank())
                ? personId() : Person.Id.from(body.userId());
        if (!subject.equals(personId()) && !privileges().canActFor(findPerson(personId()), subject)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to capture this payment.");
        }
        final String tripId = body == null ? null : body.tripId();
        if (!Beans.get(PayCommands.class).captureAndSave(orderId, subject, tripId)) {
            return error(502, ApiErrors.STORE_FAILED, "Could not capture the payment.");
        }
        return ok(Map.of("captured", true, "orderId", orderId));
    }

    /** What PayPal will take and what lands, for showing a payer before they commit. Pure arithmetic. */
    @GET
    @Path("fee-estimate")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response feeEstimate(@QueryParam("amount") final String amount) {
        final BigDecimal value;
        try {
            value = new BigDecimal(amount == null ? "" : amount.trim());
        } catch (final NumberFormatException ex) {
            return error(400, ApiErrors.BAD_REQUEST, "A numeric amount is required.");
        }
        final PayCommands pay = Beans.get(PayCommands.class);
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("amount", value);
        result.put("fee", pay.estimateFee(value));
        result.put("net", pay.estimateNet(value));
        return ok(result);
    }

    /**
     * Whether a client-supplied address is one we are willing to send a payer back to.
     *
     * <p>The allowlist is a setting so an app's custom scheme can be added at release time rather than needing
     * a deploy. The matching rule itself lives in {@link RedirectAllowlist}, where it is tested directly.
     */
    private static boolean isAllowed(final String url) {
        return RedirectAllowlist.allows(
                url, Beans.get(ConfigCommands.class).getString(KnownSettings.PAYMENT_RETURN_URL_PREFIXES));
    }

    /** What a client sends to start a payment. */
    public record OrderRequest(
            String userId,
            Float amount,
            String tripId,
            String description,
            String returnUrl,
            String cancelUrl) {
    }

    /** What a client sends to finish one. */
    public record CaptureRequest(String userId, String tripId) {
    }
}
