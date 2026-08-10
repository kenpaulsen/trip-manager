package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.action.ChatCommands;
import org.paulsens.trip.action.ChatPhotos;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.chat.ChatNudgeRegistry;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.model.chat.ChatReactionSummary;

/**
 * Chat REST edge. Versioning is by {@code Accept} media type only — no {@code /v1} in the path.
 * Every response sends {@code Vary: Accept}.
 */
@Slf4j
@Path("chat/channels/{channelId}")
@TripApi
public class ChatResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.CHAT_V1;
    /** Caps the hold well inside the ALB's 60s idle timeout, whatever a client asks for. */
    private static final int MAX_WAIT_SECONDS = 25;

    @Override
    protected String versionedType() {
        return V1;
    }

    /**
     * The feed. Returns immediately when there is anything to return; with {@code wait=N} an empty poll blocks its
     * virtual thread for up to N seconds and completes early on a nudge, which is what turns this into sub-second
     * delivery without a WebSocket.
     *
     * <p>Plain blocking, now that every request runs on a virtual thread: a parked poller costs a few KB of heap,
     * not a pool thread, so the {@code @Suspended}/{@code AsyncResponse} machinery — and the resume-exactly-once
     * races between its three exit paths — is gone. There is one exit: fall through the await and read the cursor.
     */
    @GET
    @Path("messages")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response feed(
            @PathParam("channelId") final String channelId,
            @QueryParam("since") final String since,
            @QueryParam("before") final String before,
            @QueryParam("order") final String order,
            @QueryParam("wait") @DefaultValue("0") final int wait,
            @QueryParam("limit") @DefaultValue("200") final int limit,
            @QueryParam("rver") final Long rver,
            @QueryParam("mver") final Long mver) {
        try {
            return feedNow(channelId, since, before, order, wait, limit, rver, mver);
        } catch (final NotAuthorizedException ex) {
            // personId() throws this when the session vanished mid-request. Answered here rather than left to the
            // default mapper so a long-polling client gets the versioned error body it knows how to stop on.
            return error(401, ChatErrors.NOT_AUTHENTICATED, "Sign in required.");
        } catch (final RuntimeException ex) {
            log.error("Chat feed failed for channel {}", channelId, ex);
            return error(500, ChatErrors.INTERNAL, "Internal server error.");
        }
    }

    private Response feedNow(
            final String channelId,
            final String since,
            final String before,
            final String order,
            final int wait,
            final int limit,
            final Long rver,
            final Long mver) {
        final Person.Id me = personId();
        final String tripId = tripIdOf(channelId);
        if (tripId == null) {
            return error(400, ChatErrors.BAD_CHANNEL, "Invalid channel id.");
        }
        final ChatCommands chat = ChatCommands.getChatCommands();
        // Checked before the channel lookup so it holds even for a trip whose channel was never created: without
        // this, a disabled chat with no channel answers 200-empty and a long-polling client keeps asking forever
        // instead of stopping on a 403.
        if (!chat.chatEnabledForTrip(tripId)) {
            return error(403, ChatErrors.CHAT_DISABLED, "Chat is turned off for this trip.");
        }
        final ChatChannel channel = chat.getChannel(tripId);
        if (channel == null) {
            // A GET must not create anything. Creating the channel here made an ordinary poll a write, and
            // emitted a "channel created" CHAT_ADMIN audit record attributed to whoever happened to poll first.
            // An empty feed is the honest answer; the channel is created by the first send, or by an admin.
            return chat.isTripMember(tripId, me)
                    ? ok(ChatPage.empty())
                    : error(403, ChatErrors.NOT_A_TRIP_MEMBER, "Not a member of this trip.");
        }
        final String denial = chat.readDenial(channel, me);
        if (denial != null) {
            return error(403, denial, denialMessage(denial));
        }

        // Two directions, chosen explicitly. `order=newest` is the initial load and asks for the LATEST page;
        // `before=<id>` pages further back. Neither can be inferred from an empty `before`, which is why the
        // client states its intent: reading intent from a blank parameter silently fell through to the poll path
        // and served the oldest messages in the channel as if they were the newest.
        final boolean wantsHistory = "newest".equalsIgnoreCase(order) || (before != null && !before.isBlank());
        if (wantsHistory) {
            final ChatMessage.Id beforeId = (before == null || before.isBlank())
                    ? null : ChatMessage.Id.from(before);
            return ok(chat.history(tripId, me, beforeId, Math.min(limit, 50)));
        }

        final ChatMessage.Id sinceId = (since == null || since.isBlank()) ? null : ChatMessage.Id.from(since);
        final ChatPage page = chat.feed(tripId, me, sinceId, limit);
        if (!page.isEmpty() || wait <= 0) {
            return ok(page);
        }
        // A reaction, edit or tombstone writes no message, so it moves only the version counters -- and the
        // client polls with a gap between requests. Land in that gap and the nudge is published while nobody
        // is parked, while this read (which already sees the new counter) has no message to return: without
        // this check the client parks and learns nothing for a full timeout. Photo reactions made that
        // routine, because the person reacting is looking straight at the chip that should change.
        if (staleVersions(page, rver, mver)) {
            return ok(emptyPageAt(sinceId, page));
        }
        return awaitNudge(chat, tripId, me, sinceId, limit, wait, rver, mver);
    }

    /**
     * Whether the counters this read reports have moved past the ones the client says it holds.
     *
     * <p>ABSENT and zero are different answers, which is why these are boxed. Absent is an older client that
     * sends no counters at all, and it keeps the previous behaviour. A sent zero is a current client saying "I
     * have seen no reactions on this channel" — the ordinary state of a channel whose FIRST reaction is the one
     * being waited for. Reading that zero as "not reported" is what left the first reaction on a quiet channel
     * waiting out the entire timeout.
     *
     * <p>A zero from the SERVER does still mean "not reported" (a cold or unavailable cache) and never counts as
     * a change, so a client with no number to catch up to cannot be spun. Counters only ever increase, but this
     * compares for INEQUALITY: a cache rebuild restarts them, and a client left holding a larger number would
     * otherwise never be told anything again.
     */
    static boolean staleVersions(final ChatPage page, final Long rver, final Long mver) {
        if (page == null) {
            return false;
        }
        return (rver != null && page.getReactionsVersion() > 0 && page.getReactionsVersion() != rver)
                || (mver != null && page.getMutationsVersion() > 0 && page.getMutationsVersion() != mver);
    }

    /**
     * Blocks this request's virtual thread until a nudge arrives for the channel or the wait elapses.
     *
     * <p>Wake and timeout converge on one final cursor read, which is the actual source of truth — the nudge is
     * only advisory. That single exit is also the lost-nudge fallback: a message whose nudge was dropped still
     * comes back when the wait elapses, so a lost nudge costs latency, never a message and never a hang. An empty
     * final read echoes the caller's cursor so an empty response cannot make a client lose its place.
     */
    private Response awaitNudge(
            final ChatCommands chat,
            final String tripId,
            final Person.Id me,
            final ChatMessage.Id sinceId,
            final int limit,
            final int wait,
            final Long rver,
            final Long mver) {
        final String channelId = ChatChannel.Id.forTrip(tripId).getValue();
        final CountDownLatch nudged = new CountDownLatch(1);
        final AutoCloseable parked = ChatNudgeRegistry.getInstance()
                .park(channelId, upTo -> nudged.countDown());
        try {
            // Re-read after parking. A message written between the first read and the park would have published
            // its nudge while nobody was listening, so without this check the request waits out the full timeout
            // for a message that already exists. The counters carry the same race for changes that write no
            // message at all.
            final ChatPage afterPark = chat.feed(tripId, me, sinceId, limit);
            if (!afterPark.isEmpty()) {
                return ok(afterPark);
            }
            if (staleVersions(afterPark, rver, mver)) {
                return ok(emptyPageAt(sinceId, afterPark));
            }
            nudged.await(cappedWaitSeconds(wait), TimeUnit.SECONDS);
            final ChatPage woken = chat.feed(tripId, me, sinceId, limit);
            return ok(woken.isEmpty() ? emptyPageAt(sinceId, woken) : woken);
        } catch (final InterruptedException ex) {
            // The container interrupted the request thread -- the client is gone. Answer something valid for the
            // dying connection and re-assert the flag for the container's own cleanup.
            Thread.currentThread().interrupt();
            return ok(emptyPageAt(sinceId, null));
        } finally {
            unpark(parked);
        }
    }

    /** Caps the hold well inside the ALB's 60s idle timeout, whatever a client asks for. */
    static int cappedWaitSeconds(final int wait) {
        return Math.min(Math.max(wait, 1), MAX_WAIT_SECONDS);
    }

    private static void unpark(final AutoCloseable parked) {
        try {
            parked.close();
        } catch (final Exception ex) {
            log.warn("Unable to unpark a chat long-poll waiter", ex);
        }
    }

    /**
     * An empty page that preserves the caller's cursor, so a timeout does not reset their position — but
     * carrying the {@code source} read's version counters, never zeroing them. A reaction, an edit or a
     * photo-reaction roll-up creates NO message, so a parked poll on a quiet channel wakes to an empty read;
     * the versions on that empty page are the ONLY path by which the change reaches a client that already
     * holds the message (see chat-design: "an empty page must still carry the version"). Zeroing them here
     * made a nudged wake indistinguishable from a timeout, and real-time reactions on a quiet channel
     * silently did not work while every individual piece looked correct.
     */
    private static ChatPage emptyPageAt(final ChatMessage.Id cursor, final ChatPage source) {
        return new ChatPage(List.of(), Map.of(), cursor,
                source == null ? 0L : source.getReactionsVersion(),
                source == null ? 0L : source.getMutationsVersion(),
                false, false, Map.of(), Instant.now());
    }

    @POST
    @Path("messages")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response send(
            @PathParam("channelId") final String channelId,
            @HeaderParam(ChatCommands.CSRF_HEADER) final String csrf,
            @HeaderParam("Content-Type") final String contentType,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ChatErrors.CSRF, "Missing " + ChatCommands.CSRF_HEADER + " header.");
        }
        if (contentType == null || !(contentType.contains("vnd.trip.chat") || contentType.contains("json"))) {
            return error(415, ChatErrors.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be chat v1 JSON.");
        }
        final Person.Id me = personId();
        final String tripId = tripIdOf(channelId);
        if (tripId == null) {
            return error(400, ChatErrors.BAD_CHANNEL, "Invalid channel id.");
        }
        final String text = body == null ? null : string(body.get("body"));
        final String clientMessageId = body == null ? null : string(body.get("clientMessageId"));
        final String replyTo = body == null ? null : string(body.get("replyTo"));
        final ChatMessage.Id replyId = replyTo == null || replyTo.isBlank()
                ? null : ChatMessage.Id.from(replyTo);
        final List<ChatPhotos.AttachmentRef> attachments =
                parseAttachments(body == null ? null : body.get("attachments"));

        final ChatCommands chat = ChatCommands.getChatCommands();
        final ChatCommands.SendResult result = chat.send(
                tripId, me, text, clientMessageId, replyId, actor(), attachments);
        if (result.isOk()) {
            return ok(result.getMessageObj());
        }
        final String code = ChatErrors.forSendResult(result.getCode());
        final String message = result.getMessage() == null ? "Send failed." : result.getMessage();
        if (ChatErrors.RATE_LIMITED.equals(code) || ChatErrors.SLOW_MODE.equals(code)) {
            // 429 is NOT terminal: the client keeps polling, keeps the user's text, and shows the countdown.
            return Response.status(429)
                    .type(MediaType.APPLICATION_JSON)
                    .header("Vary", "Accept")
                    .header("Retry-After", result.getDecision() == null
                            ? "5" : Integer.toString(result.getDecision().getRetryAfterSeconds()))
                    .entity(Map.of("error", code, "message", message))
                    .build();
        }
        if (ChatErrors.MUTED.equals(code) || ChatErrors.FORBIDDEN.equals(code)
                || ChatErrors.CHANNEL_ARCHIVED.equals(code)) {
            return error(403, code, message);
        }
        if (ChatErrors.MESSAGE_EMPTY.equals(code) || ChatErrors.MESSAGE_TOO_LONG.equals(code)
                || ChatErrors.BAD_ATTACHMENT.equals(code)) {
            return error(400, code, message);
        }
        return error(500, code, message);
    }

    /**
     * Attachment references from the wire: {@code [{key, title}, ...]}. Tolerant of shape garbage (an entry
     * that is not an object, a missing key) by dropping it — the send then validates what remains against the
     * staging registry, which is the check that actually matters.
     */
    private static List<ChatPhotos.AttachmentRef> parseAttachments(final Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        final List<ChatPhotos.AttachmentRef> refs = new ArrayList<>();
        for (final Object entry : list) {
            if (entry instanceof Map<?, ?> map && map.get("key") != null) {
                final Object title = map.get("title");
                refs.add(new ChatPhotos.AttachmentRef(
                        map.get("key").toString(), title == null ? null : title.toString()));
            }
        }
        return refs;
    }

    @DELETE
    @Path("messages/{msgId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response delete(
            @PathParam("channelId") final String channelId,
            @PathParam("msgId") final String msgId,
            @HeaderParam(ChatCommands.CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ChatErrors.CSRF, "Missing " + ChatCommands.CSRF_HEADER + " header.");
        }
        final Person.Id me = personId();
        final String tripId = tripIdOf(channelId);
        if (tripId == null) {
            return error(400, ChatErrors.BAD_CHANNEL, "Invalid channel id.");
        }
        final ChatCommands chat = ChatCommands.getChatCommands();
        // An author may remove their own; anyone else needs to administer the chat. The bean re-derives both from
        // the stored message rather than trusting anything here, so this only shapes the error the client sees.
        if (!chat.canDelete(tripId, ChatMessage.Id.from(msgId), me)
                && !chat.canAdminister(tripId, caller())) {
            return error(403, ChatErrors.FORBIDDEN, "You can only remove your own messages.");
        }
        final boolean ok = chat.deleteMessage(tripId, ChatMessage.Id.from(msgId), caller());
        if (!ok) {
            return error(404, ChatErrors.NOT_FOUND, "Message not found.");
        }
        return ok(Map.of("deleted", true, "msgId", msgId));
    }

    /**
     * Author self-edit. {@code PATCH} because it replaces one field of an existing message rather than creating
     * anything — a {@code PUT} would imply the client is supplying the whole resource, including fields
     * ({@code sentAt}, {@code authorId}, the quote) that are immutable by design.
     */
    @PATCH
    @Path("messages/{msgId}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response edit(
            @PathParam("channelId") final String channelId,
            @PathParam("msgId") final String msgId,
            @HeaderParam(ChatCommands.CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ChatErrors.CSRF, "Missing " + ChatCommands.CSRF_HEADER + " header.");
        }
        final Person.Id me = personId();
        final String tripId = tripIdOf(channelId);
        if (tripId == null) {
            return error(400, ChatErrors.BAD_CHANNEL, "Invalid channel id.");
        }
        final ChatCommands chat = ChatCommands.getChatCommands();
        final ChatCommands.ReactResult result = chat.editMessage(
                tripId, me, ChatMessage.Id.from(msgId), string(body == null ? null : body.get("body")));
        if (result.ok()) {
            return ok(Map.of("edited", true, "msgId", msgId));
        }
        return editError(result);
    }

    private Response editError(final ChatCommands.ReactResult result) {
        final String code = ChatErrors.forEditResult(result.code());
        final String message = result.message() == null ? "Edit failed." : result.message();
        if (ChatErrors.MESSAGE_EMPTY.equals(code) || ChatErrors.MESSAGE_TOO_LONG.equals(code)) {
            return error(400, code, message);
        }
        if (ChatErrors.NOT_FOUND.equals(code)) {
            return error(404, code, message);
        }
        if (ChatErrors.NOT_AUTHENTICATED.equals(code)) {
            return error(401, code, message);
        }
        if (ChatErrors.STORE_FAILED.equals(code) || ChatErrors.INTERNAL.equals(code)) {
            return error(500, code, message);
        }
        // Includes EDIT_WINDOW_CLOSED and EDIT_DISABLED: the request was understood and refused, not malformed.
        return error(403, code, message);
    }

    /**
     * Records how far the caller has read. Fire-and-forget from the client's point of view: a lost cursor costs an
     * unread dot, never a message, which is why it is not part of the feed response.
     */
    @POST
    @Path("read")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response markRead(
            @PathParam("channelId") final String channelId,
            @HeaderParam(ChatCommands.CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ChatErrors.CSRF, "Missing " + ChatCommands.CSRF_HEADER + " header.");
        }
        final String tripId = tripIdOf(channelId);
        if (tripId == null) {
            return error(400, ChatErrors.BAD_CHANNEL, "Invalid channel id.");
        }
        final String cursor = string(body == null ? null : body.get("cursor"));
        if (cursor == null || cursor.isBlank()) {
            return error(400, ChatErrors.BAD_CHANNEL, "cursor is required.");
        }
        final boolean ok = ChatCommands.getChatCommands()
                .markRead(tripId, personId(), ChatMessage.Id.from(cursor));
        return ok(Map.of("read", ok));
    }

    /**
     * Adds a reaction. {@code PUT} rather than {@code POST} because it is idempotent by construction — the row's key
     * is {@code (message, person, emoji)} — so a retry after a dropped response is safe and needs no dedupe token.
     *
     * <p>The emoji travels percent-encoded in the path; JAX-RS decodes it before this sees it.
     */
    @PUT
    @Path("reactions/{msgId}/{emoji}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response react(
            @PathParam("channelId") final String channelId,
            @PathParam("msgId") final String msgId,
            @PathParam("emoji") final String emoji,
            @HeaderParam(ChatCommands.CSRF_HEADER) final String csrf) {
        return toggleReaction(channelId, msgId, emoji, csrf, true);
    }

    @DELETE
    @Path("reactions/{msgId}/{emoji}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response unreact(
            @PathParam("channelId") final String channelId,
            @PathParam("msgId") final String msgId,
            @PathParam("emoji") final String emoji,
            @HeaderParam(ChatCommands.CSRF_HEADER) final String csrf) {
        return toggleReaction(channelId, msgId, emoji, csrf, false);
    }

    private Response toggleReaction(
            final String channelId,
            final String msgId,
            final String emoji,
            final String csrf,
            final boolean add) {
        if (csrfMissing(csrf)) {
            return error(403, ChatErrors.CSRF, "Missing " + ChatCommands.CSRF_HEADER + " header.");
        }
        final Person.Id me = personId();
        final String tripId = tripIdOf(channelId);
        if (tripId == null) {
            return error(400, ChatErrors.BAD_CHANNEL, "Invalid channel id.");
        }
        final ChatCommands chat = ChatCommands.getChatCommands();
        final ChatMessage.Id target = ChatMessage.Id.from(msgId);
        final ChatCommands.ReactResult result = add
                ? chat.react(tripId, me, target, emoji)
                : chat.unreact(tripId, me, target, emoji);
        if (result.ok()) {
            return ok(Map.of("reacted", add, "msgId", msgId, "emoji", emoji,
                    "reactionsVersion", chat.reactionsVersion(tripId)));
        }
        return reactionError(result);
    }

    private Response reactionError(final ChatCommands.ReactResult result) {
        final String code = ChatErrors.forReactResult(result.code());
        final String message = result.message() == null ? "Reaction failed." : result.message();
        if (ChatErrors.BAD_EMOJI.equals(code)) {
            return error(400, code, message);
        }
        if (ChatErrors.NOT_FOUND.equals(code)) {
            return error(404, code, message);
        }
        if (ChatErrors.NOT_AUTHENTICATED.equals(code)) {
            return error(401, code, message);
        }
        if (ChatErrors.STORE_FAILED.equals(code) || ChatErrors.INTERNAL.equals(code)) {
            return error(500, code, message);
        }
        return error(403, code, message);
    }

    /**
     * Summaries for the window a client has on screen, for a refetch after {@code reactionsVersion} changed.
     *
     * <p>This exists because reactions do not ride the message cursor: they attach to messages the client already
     * has, so there is no cursor advance that could carry them. Without this endpoint the only way to see a reaction
     * on an older message would be to re-read the whole page.
     */
    @GET
    @Path("reactions")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response reactions(
            @PathParam("channelId") final String channelId,
            @QueryParam("from") final String from,
            @QueryParam("to") final String to) {
        final Person.Id me = personId();
        final String tripId = tripIdOf(channelId);
        if (tripId == null) {
            return error(400, ChatErrors.BAD_CHANNEL, "Invalid channel id.");
        }
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            return error(400, ChatErrors.BAD_CHANNEL, "from and to message ids are required.");
        }
        final ChatCommands chat = ChatCommands.getChatCommands();
        final ChatChannel channel = chat.getChannel(tripId);
        if (channel == null) {
            return ok(Map.of("reactions", Map.of(), "reactionsVersion", 0L));
        }
        final String denial = chat.readDenial(channel, me);
        if (denial != null) {
            return error(403, denial, denialMessage(denial));
        }
        final Map<ChatMessage.Id, ChatReactionSummary> summaries = chat.reactionWindow(
                tripId, me, ChatMessage.Id.from(from), ChatMessage.Id.from(to));
        // Names come with the summaries: a reactor is often not an author on the client's current page, so without
        // them the "who reacted" tooltip renders raw person ids.
        return ok(Map.of(
                "reactions", summaries,
                "displayNames", chat.reactorNames(summaries),
                "reactionsVersion", chat.reactionsVersion(tripId)));
    }

    /** The reaction palette, so the client renders exactly the set the server will accept. */
    @GET
    @Path("emoji")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response emoji(@PathParam("channelId") final String channelId) {
        personId();
        return ok(Map.of("palette", ChatCommands.getChatCommands().getEmojiPalette()));
    }

    @POST
    @Path("join")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response join(
            @PathParam("channelId") final String channelId,
            @HeaderParam(ChatCommands.CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ChatErrors.CSRF, "Missing " + ChatCommands.CSRF_HEADER + " header.");
        }
        final String tripId = tripIdOf(channelId);
        if (tripId == null) {
            return error(400, ChatErrors.BAD_CHANNEL, "Invalid channel id.");
        }
        final boolean ok = ChatCommands.getChatCommands().rejoin(tripId, personId(), actor());
        return ok ? ok(Map.of("joined", true))
                : error(403, ChatErrors.REMOVED_FROM_CHANNEL, "An administrator must re-add you.");
    }

    @POST
    @Path("leave")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response leave(
            @PathParam("channelId") final String channelId,
            @HeaderParam(ChatCommands.CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ChatErrors.CSRF, "Missing " + ChatCommands.CSRF_HEADER + " header.");
        }
        final String tripId = tripIdOf(channelId);
        if (tripId == null) {
            return error(400, ChatErrors.BAD_CHANNEL, "Invalid channel id.");
        }
        final boolean ok = ChatCommands.getChatCommands().leave(tripId, personId(), actor());
        return ok ? ok(Map.of("left", true)) : error(500, ChatErrors.INTERNAL, "Leave failed.");
    }

    @GET
    @Path("export")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response export(
            @PathParam("channelId") final String channelId,
            @QueryParam("limit") @DefaultValue("500") final int limit) {
        final Person.Id me = personId();
        final String tripId = tripIdOf(channelId);
        if (tripId == null) {
            return error(400, ChatErrors.BAD_CHANNEL, "Invalid channel id.");
        }
        final ChatCommands chat = ChatCommands.getChatCommands();
        if (!chat.canAdminister(tripId, caller())) {
            return error(403, ChatErrors.FORBIDDEN, "Chat manager required.");
        }
        final ChatPage page = chat.history(tripId, me, null, Math.min(limit, 1000));
        // An export is a bulk disclosure -- a single re-shareable file of everything everyone said. That is a
        // materially different act from scrolling, so it is audited with the count, per the design.
        chat.auditExport(tripId, page.getMessages().size(), actor());
        return ok(page);
    }

    // --- roster, preferences and moderation ---
    //
    // These live here, on the channel path, rather than in a second resource rooted at "chat". A resource at
    // "chat" declaring a "channels/..." sub-path would overlap this one's "chat/channels/{channelId}", which is
    // a routing ambiguity nobody wants to debug. The non-channel-scoped listing is ChatAdminResource's.

    /** Who is in this chat, and in what state. Members see the roster; it is how mentions get built. */
    @GET
    @Path("roster")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response roster(@PathParam("channelId") final String channelId) {
        final String tripId = tripIdOf(channelId);
        if (tripId == null) {
            return error(400, ChatErrors.BAD_CHANNEL, "Invalid channel id.");
        }
        final ChatCommands chat = ChatCommands.getChatCommands();
        if (!chat.isTripMember(tripId, personId())) {
            return error(403, ChatErrors.NOT_A_TRIP_MEMBER, "Not a member of this trip.");
        }
        return ok(chat.roster(tripId));
    }

    /** This caller's own email preferences for this chat. Never anybody else's. */
    @GET
    @Path("prefs")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response prefs(@PathParam("channelId") final String channelId) {
        final String tripId = tripIdOf(channelId);
        if (tripId == null) {
            return error(400, ChatErrors.BAD_CHANNEL, "Invalid channel id.");
        }
        final ChatCommands chat = ChatCommands.getChatCommands();
        final Person.Id me = personId();
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("mentionEmail", chat.mentionEmailForTrip(tripId, me));
        result.put("dailyDigest", chat.dailyDigestForTrip(tripId, me));
        return ok(result);
    }

    @PUT
    @Path("prefs")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response savePrefs(
            @PathParam("channelId") final String channelId,
            @HeaderParam(ChatCommands.CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ChatErrors.CSRF, "Missing " + ChatCommands.CSRF_HEADER + " header.");
        }
        final String tripId = tripIdOf(channelId);
        if (tripId == null) {
            return error(400, ChatErrors.BAD_CHANNEL, "Invalid channel id.");
        }
        final ChatCommands chat = ChatCommands.getChatCommands();
        final Person.Id me = personId();
        final boolean mention = bool(body, "mentionEmail", chat.mentionEmailForTrip(tripId, me));
        final boolean digest = bool(body, "dailyDigest", chat.dailyDigestForTrip(tripId, me));
        if (!chat.setEmailPrefs(tripId, me, mention, digest)) {
            return error(403, ChatErrors.FORBIDDEN, "Could not save preferences for this chat.");
        }
        return ok(Map.of("mentionEmail", mention, "dailyDigest", digest));
    }

    /** Mutes somebody for a number of minutes. Chat managers and site administrators. */
    @POST
    @Path("members/{personId}/mute")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response mute(
            @PathParam("channelId") final String channelId,
            @PathParam("personId") final String targetId,
            @HeaderParam(ChatCommands.CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ChatErrors.CSRF, "Missing " + ChatCommands.CSRF_HEADER + " header.");
        }
        final String tripId = tripIdOf(channelId);
        if (tripId == null) {
            return error(400, ChatErrors.BAD_CHANNEL, "Invalid channel id.");
        }
        final int minutes = intValue(body, "minutes", 0);
        if (minutes <= 0) {
            return error(400, ChatErrors.FORBIDDEN, "A positive number of minutes is required.");
        }
        final Instant until = Instant.now().plusSeconds(minutes * 60L);
        final String reason = string(body == null ? null : body.get("reason"));
        // caller() carries the session-derived identity AND role: the bean's own role check reads FacesContext
        // and reports false here, so without this a site administrator is refused every moderation action.
        final boolean ok = ChatCommands.getChatCommands()
                .mute(tripId, Person.Id.from(targetId), until, reason, caller());
        return ok ? ok(Map.of("muted", true, "until", until.toString()))
                : error(403, ChatErrors.FORBIDDEN, "Chat manager required.");
    }

    @POST
    @Path("members/{personId}/unmute")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response unmute(
            @PathParam("channelId") final String channelId,
            @PathParam("personId") final String targetId,
            @HeaderParam(ChatCommands.CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ChatErrors.CSRF, "Missing " + ChatCommands.CSRF_HEADER + " header.");
        }
        final String tripId = tripIdOf(channelId);
        if (tripId == null) {
            return error(400, ChatErrors.BAD_CHANNEL, "Invalid channel id.");
        }
        final boolean ok = ChatCommands.getChatCommands()
                .unmute(tripId, Person.Id.from(targetId), caller());
        return ok ? ok(Map.of("unmuted", true))
                : error(403, ChatErrors.FORBIDDEN, "Chat manager required.");
    }

    /** Removes somebody from the chat. They cannot rejoin themselves -- only an admin can undo this. */
    @DELETE
    @Path("members/{personId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response removeMember(
            @PathParam("channelId") final String channelId,
            @PathParam("personId") final String targetId,
            @QueryParam("reason") final String reason,
            @HeaderParam(ChatCommands.CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ChatErrors.CSRF, "Missing " + ChatCommands.CSRF_HEADER + " header.");
        }
        final String tripId = tripIdOf(channelId);
        if (tripId == null) {
            return error(400, ChatErrors.BAD_CHANNEL, "Invalid channel id.");
        }
        final boolean ok = ChatCommands.getChatCommands()
                .removeMember(tripId, Person.Id.from(targetId), reason, caller());
        return ok ? ok(Map.of("removed", true))
                : error(403, ChatErrors.FORBIDDEN, "Chat manager required.");
    }

    /**
     * Re-adds somebody an administrator previously removed.
     *
     * <p>{@code acknowledgement} is required by the bean and is not ceremony: the rule is that nobody is put
     * back into a chat without their permission, so an add with nothing confirmed fails rather than being
     * recorded as unacknowledged.
     */
    @POST
    @Path("members/{personId}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response addMember(
            @PathParam("channelId") final String channelId,
            @PathParam("personId") final String targetId,
            @HeaderParam(ChatCommands.CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ChatErrors.CSRF, "Missing " + ChatCommands.CSRF_HEADER + " header.");
        }
        final String tripId = tripIdOf(channelId);
        if (tripId == null) {
            return error(400, ChatErrors.BAD_CHANNEL, "Invalid channel id.");
        }
        final String acknowledgement = string(body == null ? null : body.get("acknowledgement"));
        final boolean ok = ChatCommands.getChatCommands()
                .addMember(tripId, Person.Id.from(targetId), acknowledgement, caller());
        return ok ? ok(Map.of("added", true))
                : error(403, ChatErrors.FORBIDDEN,
                        "Chat manager required, and the person's permission must be confirmed.");
    }

    private static boolean bool(final Map<String, Object> body, final String key, final boolean fallback) {
        final Object value = body == null ? null : body.get(key);
        return (value instanceof Boolean flag) ? flag : fallback;
    }

    private static int intValue(final Map<String, Object> body, final String key, final int fallback) {
        final Object value = body == null ? null : body.get(key);
        return (value instanceof Number number) ? number.intValue() : fallback;
    }

    private static String denialMessage(final String code) {
        if (ChatErrors.LEFT_CHANNEL.equals(code)) {
            return "You left this chat. You can rejoin.";
        }
        if (ChatErrors.REMOVED_FROM_CHANNEL.equals(code)) {
            return "You were removed from this chat by an administrator.";
        }
        if (ChatErrors.NOT_A_TRIP_MEMBER.equals(code)) {
            return "You no longer have access to this chat.";
        }
        if (ChatErrors.CHAT_DISABLED.equals(code)) {
            return "Chat is turned off for this trip.";
        }
        return "Not permitted.";
    }

    /**
     * The trip behind a channel id. Requires the {@code trip:} prefix rather than accepting a bare trip id: the
     * prefix is what lets {@code dm:} and topic channels share this table later, and a lenient parse would read a
     * future {@code dm:a:b} id as a trip called "dm:a:b" -- resolving a DM to the wrong authorization scope.
     */
    private static String tripIdOf(final String channelId) {
        if (channelId == null || !channelId.startsWith("trip:")) {
            return null;
        }
        final String tripId = channelId.substring("trip:".length());
        return tripId.isBlank() ? null : tripId;
    }

    private static String string(final Object o) {
        return o == null ? null : o.toString();
    }
}
