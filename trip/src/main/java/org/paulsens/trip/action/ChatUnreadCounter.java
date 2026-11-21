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
 * Whether, and how many, messages a person has not read in one chat: the number the apps' badges show (owner,
 * 2026-11-20; the flag alone showed "1" for a chat with four new messages). It builds {@code myChats}' rows, so the
 * count reuses the trip that call already loaded and the ONE cursor read the flag needed, instead of reading both
 * again per chat.
 *
 * <p>The count is other people's live messages after the person's read cursor, each judged by the feed's own
 * visibility rule ({@link ChatVisibility}), never re-derived here. It reads ONE page of at most {@link #CAP} rows
 * through {@link DAO#getRawChatMessagesAfter}, which never refills the chat's hot buffer: the feed's poll path does,
 * and rows a count pulled in would leave a gap that made another reader's poll skip messages. The page is filtered
 * before anything is reported, so {@link #CAP} (which the apps draw as "99+") means a full page in which every row
 * was unread; a full page with rows filtered out reports what it found, a lower bound.
 *
 * <p>No cursor counts as ONE, the old dot, with no read at all: the cursor lives only in Valkey and expires a week
 * after the last read ({@code CacheKeys.GC_TTL}), so "no cursor" usually means "read, then quiet for a week", and
 * counting from the start of the chat turned every such chat into "99+".
 */
final class ChatUnreadCounter {

    /** The page size, so the most a count reports; the apps show anything above 99 as "99+". */
    static final int CAP = 100;

    private ChatUnreadCounter() {
    }

    /**
     * The unread rule the flag and the count share. No cursor means never opened (or quiet for a week): anything
     * at all is unread, which is the correct first-time signal, and why an absent cursor must not read as
     * "caught up".
     */
    static boolean isUnread(final long activity, final ChatMessage.Id cursor) {
        return activity > 0L && (cursor == null || cursor.getEpochMilli() < activity);
    }

    /**
     * One row of {@code myChats}. Uncounted (the web page, account deletion) an unread chat reports 1, the dot,
     * and reads nothing beyond the cursor.
     */
    static ChatCommands.ChatSummary summarize(final DAO dao, final ChatChannel channel, final Trip trip,
            final Person.Id me, final long activity, final boolean draft, final boolean counted) {
        final ChatMessage.Id cursor =
                activity > 0L ? dao.getChatCursor(channel.getId(), me, Cached.NO).orElse(null) : null;
        final boolean unread = isUnread(activity, cursor);
        final int count = !unread ? 0 : counted ? count(dao, channel, trip, me, cursor, Instant.now()) : 1;
        return new ChatCommands.ChatSummary(channel, trip.getTitle(), activity, unread, draft, count);
    }

    /** Called only for a chat {@link #isUnread} found unread. */
    static int count(final DAO dao, final ChatChannel channel, final Trip trip, final Person.Id me,
            final ChatMessage.Id cursor, final Instant now) {
        if (cursor == null) {
            return 1;
        }
        final List<ChatMessage> rows = dao.getRawChatMessagesAfter(channel.getId(), cursor, CAP, Cached.NO);
        final ChatMembership member = dao.getChatMembership(channel.getId(), me, Cached.NO).orElse(null);
        return (int) rows.stream()
                .filter(message -> message.getDeletedAt() == null && !me.equals(message.getAuthorId()))
                .filter(message -> ChatVisibility.isVisible(message, member, channel, trip, now))
                .count();
    }
}
