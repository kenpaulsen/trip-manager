package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.paulsens.trip.action.ChatCommands;
import org.paulsens.trip.chat.InviteLinks;

/**
 * Redeeming a chat invite link from the app. A phone that opens an invite link (a universal / app link to
 * {@code /i/...} or to the older {@code /trip/chatInvite.jsf?trip=&token=}) sends the two pieces here
 * instead of landing on the website, then opens the trip's chat.
 *
 * <p>Off the channel path on purpose: the short link carries the trip id packed ({@link InviteLinks}), and
 * the phone should not have to unpack it to name a channel. The server expands whatever spelling it is sent
 * and answers with the full trip id, which is what the phone needs next.</p>
 */
@Path("chat/invites")
@TripApi
public class ChatInviteResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.CHAT_V1;

    @Override
    protected String versionedType() {
        return V1;
    }

    /**
     * {@code {"trip": "<as in the link>", "token": "<selector>.<validator>"}} ->
     * {@code {"result": ..., "tripId": "<full id>"}}, where {@code ok} means joined (or already in) and
     * anything else names the refusal the way the website's landing page does ({@code invalid},
     * {@code disabled}, {@code archived}, {@code removed}); the client words the message. Always 200 once
     * the request is well formed: a refused token is an answer, not a failure of the call.
     */
    @POST
    @Path("redeem")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response redeem(
            @HeaderParam(ChatCommands.CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ChatErrors.CSRF, "Missing " + ChatCommands.CSRF_HEADER + " header.");
        }
        final String trip = body == null ? null : stringOf(body.get("trip"));
        final String token = body == null ? null : stringOf(body.get("token"));
        if (trip == null || trip.isBlank() || token == null || token.isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, "The invite's trip and token are both required.");
        }
        final String tripId = InviteLinks.expandTripId(trip.trim());
        final String result = ChatCommands.getChatCommands().redeemInvite(tripId, token.trim(), personId(), actor());
        return ok(Map.of("result", result, "tripId", tripId));
    }

    private static String stringOf(final Object value) {
        return value instanceof String s ? s : null;
    }
}
