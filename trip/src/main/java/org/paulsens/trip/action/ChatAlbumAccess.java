package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatVisibility;

/**
 * A trip's chat album as seen by someone the album admits ONLY because they read the trip's chat: an invited
 * guest off the roster (perhaps of another organization), or a family member the chat admits. Owner decision
 * 2026-11-19, "Match the chat": such a reader sees exactly the album rows the chat shows them, hidden ones
 * included (they see those in the chat), and no others. With full history for new members off, nothing uploaded
 * before they joined; nothing past the channel's retention. Each row is judged by
 * {@link ChatVisibility#isVisibleAt}, the rule the message that carried it is judged by.
 *
 * <p>The one rule for both doors: {@code GET /api/media/slots/tripChat-{id}} ({@code MediaResource}) and the
 * website's trip photos page ({@code trip/tripMedia.xhtml}, as {@code chatAlbum}). Who READS the chat is the
 * chat's own answer ({@link ChatCommands#canRead}, i.e. {@code readDenial}): never someone who left or was
 * removed, never while the trip's chat is off. Picking a photo for a NEW message is a different question with a
 * deliberately narrower answer ({@code AlbumPhotoSource.mayOpen}).
 */
@Named("chatAlbum")
@ApplicationScoped
public class ChatAlbumAccess {

    private final Supplier<ChatCommands> chat;

    public ChatAlbumAccess() {
        this(ChatCommands::getChatCommands);
    }

    /** Test seam: a chat of the caller's own. */
    public ChatAlbumAccess(final Supplier<ChatCommands> chat) {
        this.chat = chat;
    }

    /** Whether {@code me} reads this trip's chat as it stands: its channel exists and the chat admits them. */
    public boolean readsChat(final String tripId, final Person.Id me) {
        final ChatCommands commands = chat.get();
        final ChatChannel channel = tripId == null ? null : commands.getChannel(tripId);
        return channel != null && commands.canRead(channel, me);
    }

    /**
     * The rows of this trip's chat album ({@code rows}, in their order) that {@code me} sees through the chat, or
     * {@code null} when the chat does not admit them at all. The rows are read only once the chat admits them, so
     * a stranger's request never touches the album. A row's time is its upload, recorded right after the send
     * that carried it.
     */
    public List<MediaItem> visibleThroughChat(final String tripId, final Person.Id me,
            final Supplier<List<MediaItem>> rows) {
        final ChatCommands commands = chat.get();
        final ChatChannel channel = tripId == null ? null : commands.getChannel(tripId);
        if (channel == null || !commands.canRead(channel, me)) {
            return null;
        }
        final ChatMembership row = commands.membershipRow(channel.getId(), me).orElse(null);
        final Trip trip = DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
        final Instant now = Instant.now();
        final List<MediaItem> album = rows.get();
        return (album == null ? List.<MediaItem>of() : album).stream()
                .filter(item -> ChatVisibility.isVisibleAt(uploadedAt(item), row, channel, trip, now))
                .toList();
    }

    /**
     * The trip photos page's album for such a reader: {@link #visibleThroughChat} over the whole album, and an
     * empty album (never null) once the chat no longer admits them. The page asks on every request, so leaving
     * or being removed takes effect on the next one.
     */
    public List<MediaItem> albumFor(final String tripId, final Person.Id me) {
        if (tripId == null) {
            return List.of();
        }
        final List<MediaItem> rows = visibleThroughChat(tripId, me,
                () -> DAO.getInstance().getMediaInSlot(ChatPhotos.slotFor(tripId), Cached.YES));
        return rows == null ? List.of() : rows;
    }

    /**
     * The trip photos page's trip resolution, as {@code ChatCommands.tripForChatPage} is the chat page's: when
     * the page's own resolution did not find the REQUESTED trip and {@code me} reads its chat, that trip. In Java
     * because the jsft parser cannot safely combine {@code ==} with a method call in one condition.
     */
    public Trip tripForAlbumPage(final Trip resolved, final String tripId, final Person.Id me) {
        if (resolved != null && tripId != null && tripId.equals(resolved.getId())) {
            return resolved;
        }
        if (readsChat(tripId, me)) {
            return DAO.getInstance().getTrip(tripId, Cached.NO).orElse(resolved);
        }
        return resolved;
    }

    private static Instant uploadedAt(final MediaItem item) {
        // LocalDateTime.now() at upload (ChatPhotos.recordAlbumRows): the server's own zone reads it back.
        return item.getUploaded() == null ? null : item.getUploaded().atZone(ZoneId.systemDefault()).toInstant();
    }
}
