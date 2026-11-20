package org.paulsens.trip.action;

import java.time.Instant;
import java.util.List;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatVisibility;

/**
 * How many messages a person has not read in one chat: the number the apps' badges show (owner, 2026-11-20; the
 * flag alone showed "1" for a chat with four new messages).
 *
 * <p>Other people's live messages after the person's read cursor, each judged by the feed's own visibility rule
 * ({@link ChatVisibility}), never re-derived here. Read through {@link DAO#getRawChatMessagesAfter}, which never
 * refills the chat's hot buffer: the feed's poll path does, and rows a count pulled in would leave a gap that made
 * another reader's poll skip messages. A page of {@link #CAP} rows or more reports {@link #CAP}, which the apps draw
 * as "99+", since whatever the page filtered out the real number is at least that.
 *
 * <p>No cursor counts as ONE, the old dot, with no read at all: the cursor lives only in Valkey and expires a week
 * after the last read ({@code CacheKeys.GC_TTL}), so "no cursor" usually means "read, then quiet for a week", and
 * counting from the start of the chat turned every such chat into "99+".
 */
final class ChatUnreadCounter {

    /** The most a count reports; the apps show anything above 99 as "99+". */
    static final int CAP = 100;

    private ChatUnreadCounter() {
    }

    /** Called only for a chat {@code myChats} already found unread. */
    static int count(final DAO dao, final ChatChannel channel, final Trip trip, final Person.Id me, final Instant now) {
        if (channel == null || me == null) {
            return 0;
        }
        final ChatMessage.Id cursor = dao.getChatCursor(channel.getId(), me, Cached.NO).orElse(null);
        if (cursor == null) {
            return 1;
        }
        final List<ChatMessage> rows = dao.getRawChatMessagesAfter(channel.getId(), cursor, CAP);
        if (rows.size() >= CAP) {
            return CAP;
        }
        final ChatMembership member = dao.getChatMembership(channel.getId(), me, Cached.NO).orElse(null);
        return (int) rows.stream()
                .filter(message -> message.getDeletedAt() == null && !me.equals(message.getAuthorId()))
                .filter(message -> ChatVisibility.isVisible(message, member, channel, trip, now))
                .count();
    }
}
