package org.paulsens.trip.model.chat;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Value;

/**
 * One page of chat history returned to a client. <b>Must be Serializable</b> — the same class shape that took
 * the site down as {@code AuditPage} when placed in viewScope without it. Prefer keeping the live message list
 * client-side; this type is for API responses and any residual view-scope needs.
 */
@Value
public class ChatPage implements Serializable {

    List<ChatMessage> messages;
    Map<ChatMessage.Id, ChatReactionSummary> reactions;
    /**
     * Continuation token — the id of the last message <em>examined</em> (not the last returned; filtered-out
     * messages still advance it, or a poll would re-read them forever).
     *
     * <p><b>Its direction depends on {@link #newestFirst}, and the two uses are not interchangeable.</b> On an
     * oldest-first poll ({@code newestFirst == false}) this is the newest id seen: feed it back as {@code since}.
     * On a newest-first history page ({@code newestFirst == true}) it is the <em>oldest</em> id seen: feed it back
     * as {@code before}. Polling with a token taken from a history page would re-deliver the entire channel.
     */
    ChatMessage.Id cursor;
    /**
     * Bumped on every reaction write in this channel, so a client can tell that reactions changed without
     * refetching messages. Reactions do not ride the message cursor — they attach to messages a client may
     * already have — so this counter is the only thing that tells it to refetch summaries.
     *
     * <p><b>Zero means "not reported", never "no reactions".</b> The counter lives in the cache, so it is 0 when
     * the cache is cold or down, and pages built without a channel context (a long-poll timeout echoing a cursor)
     * also send 0. A client must therefore treat 0 as no information and leave its last known version alone —
     * reading it as a real value makes every such response look like a change and triggers a refetch loop.
     */
    long reactionsVersion;
    /**
     * Bumped on every edit and every tombstone in this channel. Same rule as {@link #reactionsVersion}: zero means
     * "not reported", and it tells the client to re-read the messages it is showing rather than its cursor.
     *
     * <p>Needed for the same reason, too — an edit or a delete changes a message the client already holds, so no
     * cursor advance can carry it. A separate counter from reactions so a reaction does not trigger a message
     * refetch and vice versa.
     */
    long mutationsVersion;
    boolean hasMore;
    /** Which direction {@link #cursor} points; see its comment. True for a "load older" page. */
    boolean newestFirst;
    /**
     * personId to display name, for every author, quote author and @mention on this page.
     *
     * <p>Resolved server-side and sent with the page because the client has no way to look a person up, and
     * because the alternative -- rendering the raw id -- puts a UUID on screen where a name belongs. Mentions are
     * stored as {@code @{personId}} (see {@link ChatMentions}) precisely so the name is resolved at render time
     * and a rename is reflected everywhere at once.
     */
    Map<String, String> displayNames;
    Instant serverTime;

    public ChatPage(
            final List<ChatMessage> messages,
            final Map<ChatMessage.Id, ChatReactionSummary> reactions,
            final ChatMessage.Id cursor,
            final long reactionsVersion,
            final long mutationsVersion,
            final boolean hasMore,
            final boolean newestFirst,
            final Map<String, String> displayNames,
            final Instant serverTime) {
        this.messages = messages == null ? List.of() : List.copyOf(messages);
        this.reactions = reactions == null ? Map.of() : Map.copyOf(reactions);
        this.cursor = cursor;
        this.reactionsVersion = reactionsVersion;
        this.mutationsVersion = mutationsVersion;
        this.hasMore = hasMore;
        this.newestFirst = newestFirst;
        this.displayNames = displayNames == null ? Map.of() : Map.copyOf(displayNames);
        this.serverTime = serverTime == null ? Instant.now() : serverTime;
    }

    public static ChatPage empty() {
        return new ChatPage(List.of(), Map.of(), null, 0L, 0L, false, false, Map.of(), Instant.now());
    }

    /** A copy of this page with display names attached; the DAO builds pages, the action layer resolves people. */
    public ChatPage withDisplayNames(final Map<String, String> names) {
        return new ChatPage(messages, reactions, cursor, reactionsVersion, mutationsVersion, hasMore, newestFirst,
                names, serverTime);
    }

    /** A copy carrying reaction summaries and both channel version counters; the DAO attaches all three. */
    public ChatPage withReactions(
            final Map<ChatMessage.Id, ChatReactionSummary> summaries,
            final long newReactionsVersion,
            final long newMutationsVersion) {
        return new ChatPage(messages, summaries, cursor, newReactionsVersion, newMutationsVersion, hasMore,
                newestFirst, displayNames, serverTime);
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }
}
