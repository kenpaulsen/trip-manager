package org.paulsens.trip.api;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.ChatCommands;
import org.paulsens.trip.action.PhotoChatCommands;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.model.chat.ChatReactionSummary;
import org.paulsens.trip.model.chat.PhotoChatMeta;
import org.paulsens.trip.web.LocalRedirect;
import org.paulsens.trip.web.Sessions;

/**
 * Per-photo comment threads and image reactions.
 *
 * <p><b>Deliberately NOT {@code @TripApi}</b>: the auth filter is name-bound, so this resource is open and the
 * GETs serve anonymous readers — the read rule is "comments follow the photo", enforced in
 * {@code PhotoChatCommands.readDenialFor}. Mutations call {@link #personId()}, which throws for an anonymous
 * caller and is answered here as a 401 JSON body (never {@code sendError} — the catch-all error page would turn
 * it into an HTML home page).
 *
 * <p>The photo's s3Key travels as a query parameter or body field, never a path segment: keys contain
 * {@code /}, and Tomcat rejects an encoded slash in a path by default.
 */
@Slf4j
@Path("photo-chat")
public class PhotoChatResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.PHOTO_CHAT_V1;

    private static final int MAX_MENTION_RESULTS = 8;

    @Override
    protected String versionedType() {
        return V1;
    }

    // --- reads (anonymous-tolerant) ---

    /**
     * Batch per-photo meta for badges: {@code {"keys": [s3Key, ...]}} → per-key comment count and reaction
     * counts. A POST because an album page's worth of keys does not fit a URL, but it is a READ — no CSRF
     * sentinel, no session required. Keys the caller may not see are absent from the response.
     */
    @POST
    @Path("meta")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response meta(final Map<String, Object> body) {
        final List<String> keys = stringList(body == null ? null : body.get("keys"));
        if (keys.size() > PhotoChatCommands.MAX_META_KEYS) {
            return error(400, ApiErrors.BAD_REQUEST,
                    "At most " + PhotoChatCommands.MAX_META_KEYS + " keys per call.");
        }
        final Caller caller = caller();
        final PhotoChatCommands photoChat = PhotoChatCommands.getPhotoChatCommands();
        final Map<String, PhotoChatMeta> meta = photoChat.batchMeta(keys, caller);
        final Map<String, Object> photos = new LinkedHashMap<>();
        for (final Map.Entry<String, PhotoChatMeta> e : meta.entrySet()) {
            photos.put(e.getKey(), metaBody(e.getValue(), caller.personId()));
        }
        return ok(Map.of("photos", photos));
    }

    /**
     * One page of a photo's comment thread, newest-first. Anonymous callers get comments, display names and
     * reaction COUNTS; reactor identities ({@code who}) are member-level detail.
     */
    @GET
    @Path("thread")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response thread(
            @QueryParam("key") final String key,
            @QueryParam("before") final String before,
            @QueryParam("limit") final int limit) {
        final Caller caller = caller();
        final PhotoChatCommands photoChat = PhotoChatCommands.getPhotoChatCommands();
        final String denial = photoChat.readDenialFor(key, caller);
        if (denial != null) {
            return error(404, ApiErrors.NOT_FOUND, "No such photo.");
        }
        final ChatPage page = photoChat.thread(key,
                before == null || before.isBlank() ? null : ChatMessage.Id.from(before), limit);
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("messages", page.getMessages());
        out.put("displayNames", page.getDisplayNames());
        out.put("cursor", page.getCursor() == null ? null : page.getCursor().getValue());
        out.put("hasMore", page.isHasMore());
        out.put("reactionsVersion", page.getReactionsVersion());
        out.put("mutationsVersion", page.getMutationsVersion());
        out.put("photo", photoBody(key, caller, photoChat));
        return ok(out);
    }

    /** The image's own reaction summary, shaped for the panel's chip row. */
    private Map<String, Object> photoBody(
            final String key, final Caller caller, final PhotoChatCommands photoChat) {
        final ChatReactionSummary root = photoChat.rootSummary(key);
        final Map<String, Object> body = metaBody(new PhotoChatMeta(null, root), caller.personId());
        final String tripId = PhotoChatCommands.tripIdOfKey(key);
        if (tripId != null && photoChat.canSeeIdentities(tripId, caller)) {
            body.put("who", whoNames(root, photoChat));
        }
        return body;
    }

    @GET
    @Path("emoji")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response emoji() {
        return ok(Map.of("palette", PhotoChatCommands.getPhotoChatCommands().palette()));
    }

    // --- mutations (session + CSRF) ---

    /** Posts a comment: {@code {"key", "body", "clientMessageId"?}}. */
    @POST
    @Path("comments")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response comment(
            @HeaderParam(CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ChatErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Person.Id me;
        try {
            me = personId();
        } catch (final NotAuthorizedException ex) {
            return error(401, ChatErrors.NOT_AUTHENTICATED, "Sign in required.");
        }
        final String key = string(body == null ? null : body.get("key"));
        final String text = string(body == null ? null : body.get("body"));
        final String clientMessageId = string(body == null ? null : body.get("clientMessageId"));
        final ChatCommands.SendResult result = PhotoChatCommands.getPhotoChatCommands()
                .comment(key, me, text, clientMessageId, caller());
        if (result.isOk()) {
            return ok(result.getMessageObj());
        }
        return sendFailure(result);
    }

    /** Deletes (tombstones) a comment — the author's own, or moderation. */
    @DELETE
    @Path("comments/{msgId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response deleteComment(
            @PathParam("msgId") final String msgId,
            @QueryParam("key") final String key,
            @HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ChatErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        try {
            personId();
        } catch (final NotAuthorizedException ex) {
            return error(401, ChatErrors.NOT_AUTHENTICATED, "Sign in required.");
        }
        final boolean deleted = PhotoChatCommands.getPhotoChatCommands()
                .deleteComment(key, msgId == null ? null : ChatMessage.Id.from(msgId), caller());
        if (!deleted) {
            return error(403, ChatErrors.FORBIDDEN, "You cannot delete that comment.");
        }
        return ok(Map.of("deleted", true, "msgId", msgId));
    }

    /** Adds this person's emoji on the photo. PUT because the row key (photo, person, emoji) is idempotent. */
    @PUT
    @Path("reactions/{emoji}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response react(
            @PathParam("emoji") final String emoji,
            @QueryParam("key") final String key,
            @HeaderParam(CSRF_HEADER) final String csrf) {
        return toggleReaction(emoji, key, csrf, true);
    }

    @DELETE
    @Path("reactions/{emoji}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response unreact(
            @PathParam("emoji") final String emoji,
            @QueryParam("key") final String key,
            @HeaderParam(CSRF_HEADER) final String csrf) {
        return toggleReaction(emoji, key, csrf, false);
    }

    private Response toggleReaction(
            final String emoji, final String key, final String csrf, final boolean add) {
        if (csrfMissing(csrf)) {
            return error(403, ChatErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Person.Id me;
        try {
            me = personId();
        } catch (final NotAuthorizedException ex) {
            return error(401, ChatErrors.NOT_AUTHENTICATED, "Sign in required.");
        }
        final PhotoChatCommands photoChat = PhotoChatCommands.getPhotoChatCommands();
        final ChatCommands.ReactResult result = photoChat.react(key, me, emoji, add, caller());
        if (!result.ok()) {
            return reactFailure(result);
        }
        // Fresh counts from the just-invalidated meta, so the client can render without a second call.
        final Map<String, Object> out = metaBody(
                photoChat.batchMeta(List.of(key), caller()).get(key), me);
        out.put("reacted", add);
        out.put("emoji", emoji);
        return ok(out);
    }

    /**
     * Stashes the page to return to after login, for the anonymous "log in to comment" flow. Open by design —
     * the caller IS anonymous — but CSRF-guarded so a cross-site form cannot plant a post-login redirect, and
     * the target must be a plain same-origin path.
     */
    @POST
    @Path("login-return")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response loginReturn(
            @HeaderParam(CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ChatErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final String target = LocalRedirect.sanitizeLocalTarget(
                string(body == null ? null : body.get("target")));
        if (target == null) {
            return error(400, ApiErrors.BAD_REQUEST, "target must be a same-site path.");
        }
        request.getSession(true).setAttribute(Sessions.AFTER_LOGIN_URL, target);
        return ok(Map.of("ok", true));
    }

    /**
     * All-users mention typeahead for the album/landing composers (chat's own composer stays roster-scoped).
     * Signed-in only, two-character minimum, few results, rate-limited — see
     * {@code PhotoChatCommands.mentionSearch} for why each guard exists.
     */
    @GET
    @Path("mention-search")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response mentionSearch(@QueryParam("q") final String q) {
        final Person.Id me;
        try {
            me = personId();
        } catch (final NotAuthorizedException ex) {
            return error(401, ChatErrors.NOT_AUTHENTICATED, "Sign in required.");
        }
        if (q == null || q.strip().length() < 2) {
            return error(400, ApiErrors.BAD_REQUEST, "Type at least 2 characters.");
        }
        final PhotoChatCommands photoChat = PhotoChatCommands.getPhotoChatCommands();
        if (!photoChat.mentionSearchAllowed(me)) {
            return error(429, ChatErrors.RATE_LIMITED, "Too many lookups. Slow down.");
        }
        return ok(Map.of("people", photoChat.mentionSearch(q, MAX_MENTION_RESULTS)));
    }

    // --- helpers ---

    /** Counts-only reaction shape everyone may see; {@code myReacted} is this caller's own rows. */
    private static Map<String, Object> metaBody(final PhotoChatMeta meta, final Person.Id me) {
        final PhotoChatMeta safe = meta == null ? PhotoChatMeta.empty() : meta;
        final ChatReactionSummary root = safe.getRootReactions();
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final List<String> mine = new ArrayList<>();
        for (final String emoji : root.getByEmoji().keySet()) {
            counts.put(emoji, root.count(emoji));
            if (root.mine(emoji, me)) {
                mine.add(emoji);
            }
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("commentCount", safe.getCommentCount());
        out.put("reactions", counts);
        out.put("lastReactedAt", root.getLastReactedAt());
        out.put("myReacted", mine);
        return out;
    }

    /** Reactor names per emoji (names, not ids — the tooltip needs nothing more). Member-level detail. */
    private static Map<String, List<String>> whoNames(
            final ChatReactionSummary root, final PhotoChatCommands photoChat) {
        final Map<String, String> names = photoChat.reactorNames(root);
        final Map<String, List<String>> who = new LinkedHashMap<>();
        for (final Map.Entry<String, List<Person.Id>> e : root.getByEmoji().entrySet()) {
            final List<String> list = new ArrayList<>(e.getValue().size());
            for (final Person.Id id : e.getValue()) {
                list.add(names.getOrDefault(id.getValue(), id.getValue()));
            }
            who.put(e.getKey(), list);
        }
        return who;
    }

    private Response sendFailure(final ChatCommands.SendResult result) {
        final String code = result.getCode();
        final String message = result.getMessage() == null ? "Comment failed." : result.getMessage();
        if (result.getDecision() != null) {
            return Response.status(429)
                    .type(MediaType.APPLICATION_JSON)
                    .header("Vary", "Accept")
                    .header("Retry-After",
                            Integer.toString(result.getDecision().getRetryAfterSeconds()))
                    .entity(Map.of("error", ChatErrors.RATE_LIMITED, "message", message))
                    .build();
        }
        return switch (code == null ? "" : code) {
            case "disabled" -> error(403, ChatErrors.PHOTO_COMMENTS_DISABLED, message);
            case "not_found" -> error(404, ApiErrors.NOT_FOUND, message);
            case "empty" -> error(400, ChatErrors.MESSAGE_EMPTY, message);
            case "too_long" -> error(400, ChatErrors.MESSAGE_TOO_LONG, message);
            default -> error(500, ChatErrors.STORE_FAILED, message);
        };
    }

    private Response reactFailure(final ChatCommands.ReactResult result) {
        final String message = result.message() == null ? "Reaction failed." : result.message();
        return switch (result.code() == null ? "" : result.code()) {
            case "disabled" -> error(403, ChatErrors.PHOTO_COMMENTS_DISABLED, message);
            case "bad_emoji" -> error(400, ChatErrors.BAD_EMOJI, message);
            case "not_found" -> error(404, ApiErrors.NOT_FOUND, message);
            default -> error(500, ChatErrors.STORE_FAILED, message);
        };
    }

    private static String string(final Object raw) {
        return raw == null ? null : raw.toString();
    }

    private static List<String> stringList(final Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        final List<String> out = new ArrayList<>(list.size());
        for (final Object entry : list) {
            if (entry != null) {
                out.add(entry.toString());
            }
        }
        return out;
    }
}
