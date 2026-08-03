package org.paulsens.trip.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ChatCommands;

/**
 * The chats this caller belongs to.
 *
 * <p>Rooted at the literal {@code chat/my-channels} rather than at {@code chat}. A resource at {@code chat} with
 * a {@code channels} sub-path would overlap {@code ChatResource}'s {@code chat/channels/{channelId}}, and which
 * one Jersey picks for a given URL is not something to leave to precedence rules. Everything channel-scoped
 * lives on {@code ChatResource}; this holds only what is not.
 */
@Slf4j
@Path("chat/my-channels")
@TripApi
public class ChatAdminResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.CHAT_ADMIN_V1;

    @Override
    protected String versionedType() {
        return V1;
    }

    /**
     * Every chat this person can see, newest activity first, with an unread flag.
     *
     * <p>This is the badge query -- what a mobile client polls to decide whether to show a dot. It deliberately
     * returns summaries rather than messages: a client asking "is there anything new anywhere" should not pull
     * the contents of every conversation to find out.
     */
    @GET
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response myChannels() {
        final List<ChatCommands.ChatSummary> summaries =
                ChatCommands.getChatCommands().myChats(personId());
        return ok(summaries.stream().map(ChatAdminResource::toDto).toList());
    }

    /**
     * Flattened by hand rather than serialized as-is.
     *
     * <p>{@code ChatSummary} carries a whole {@code ChatChannel}, whose settings include moderation thresholds
     * and retention -- administrative detail that has no business in the list a traveller renders to pick a
     * conversation.
     */
    private static Map<String, Object> toDto(final ChatCommands.ChatSummary summary) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("channelId", summary.channel() == null || summary.channel().getId() == null
                ? null : summary.channel().getId().getValue());
        result.put("tripTitle", summary.tripTitle());
        result.put("lastActivityMillis", summary.lastActivityMillis());
        result.put("unread", summary.unread());
        return result;
    }
}
