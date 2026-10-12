package org.paulsens.trip.action;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatAttachment;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;

/**
 * What may happen to ONE photo after the message that sent it is posted: its removal, and its public-site
 * flag revisited -- the uploader (or a chat admin) may change their mind at any time. The flag lives twice,
 * in the attachment of the message and in the trip album's media row, and a change must move both or the
 * chat and the public pages disagree.
 *
 * <p>Its own class rather than more of {@link ChatCommands}, which is at its checkstyle length limit; the
 * bean keeps the public entry points, the authorization helpers and the audit.
 */
@Slf4j
public final class ChatPhotoEdits {

    /** How many 200-message pages {@link #findMessageWithPhoto} walks forward from the staging time. */
    private static final int LOOKUP_PAGES = 25;

    private ChatPhotoEdits() {
    }

    /**
     * Removes one photo from a message, leaving the rest and the body. The author may, or a chat admin.
     *
     * <p>Deliberately notification-free. Nothing here posts a message or a comment, so none of the chat
     * mails (mention, reply, photo-comment) can fire for an image removal -- per the feature request, the
     * note must not email anyone.
     */
    static boolean delete(final ChatCommands chat, final String tripId, final ChatMessage.Id msgId,
            final String s3Key, final Caller caller) {
        final AuditActor who = ChatCommands.actorOf(caller);
        final ChatChannel channel = chat.getChannel(tripId);
        if (channel == null || msgId == null || s3Key == null || s3Key.isBlank()) {
            return false;
        }
        final Optional<ChatMessage> before = chat.dao().getChatMessage(channel.getId(), msgId, Cached.NO);
        if (!chat.isOwnMessage(before, who)
                && chat.denyUnlessAdmin(tripId, "delete photo from message " + msgId.getValue(), caller)) {
            return false;
        }
        // Captured BEFORE the tombstone write clears its keys: the cascade below needs the real s3/thumb keys.
        final ChatAttachment target = before.map(ChatMessage::getAttachments).orElse(List.of()).stream()
                .filter(a -> !a.isDeleted() && s3Key.equals(a.getS3Key()))
                .findFirst().orElse(null);
        if (target == null) {
            return false;
        }
        final Optional<ChatMessage> updated = chat.dao().removeChatAttachment(
                channel.getId(), msgId, s3Key, deleterName(who));
        if (updated.isEmpty()) {
            return false;
        }
        final String author = before.map(m -> m.getAuthorId() == null ? "?" : m.getAuthorId().getValue())
                .orElse("?");
        chat.audit(AuditAction.CHAT_ADMIN, who, AuditEventBuilder.TARGET_CHAT_MESSAGE, msgId.getValue(),
                "photo deleted; author=" + author + "; key=" + s3Key);
        // Album semantics (user decision 2026-08-07): photos SURVIVE retention expiry but NOT removal.
        ChatPhotos.getChatPhotos().deleteEverywhere(List.of(target));
        return true;
    }

    /**
     * The remover's display name for the tombstone note, captured at delete time. Falls back to the actor's
     * email, then a role word, rather than ever rendering a raw person id in the chat.
     */
    private static String deleterName(final AuditActor who) {
        if (who == null || who.id() == null) {
            return who == null || who.email() == null ? "an administrator" : who.email();
        }
        return ChatOnBehalf.displayNameOrId(PersonCommands.getPersonCommands(), who.id());
    }

    /**
     * Changes whether one posted photo may appear on the public site. Both copies of the flag move: the
     * attachment in the message (what the chat renders, and what polling clients refetch on the mutation
     * bump) and the album row (what the public pages filter on). Not an edit: {@code editedAt} stays.
     * Authorization is the delete's: the author of the message that sent the photo, or a chat admin.
     * {@code hint} is the message the client believes carries the photo. False when the photo is unknown
     * or the caller may not change it.
     */
    static boolean setVisibility(final ChatCommands chat, final String tripId, final String s3Key,
            final boolean hidden, final ChatMessage.Id hint, final Caller caller) {
        final AuditActor who = ChatCommands.actorOf(caller);
        final ChatChannel channel = chat.getChannel(tripId);
        final Optional<ChatMessage> carrier = findMessageWithPhoto(chat.dao(), channel, s3Key, hint);
        if (carrier.isEmpty()) {
            return false;
        }
        final ChatMessage.Id msgId = carrier.get().getId();
        if (!chat.isOwnMessage(carrier, who)
                && chat.denyUnlessAdmin(tripId, "change visibility of photo in message " + msgId.getValue(), caller)) {
            return false;
        }
        if (chat.dao().setChatAttachmentHidden(channel.getId(), msgId, s3Key, hidden).isEmpty()) {
            return false;
        }
        if (!ChatPhotos.getChatPhotos().setAlbumHidden(tripId, s3Key, hidden)) {
            log.error("Chat photo {} flag changed in its message but not in the album", s3Key);
        }
        chat.audit(AuditAction.CHAT_ADMIN, who, AuditEventBuilder.TARGET_CHAT_MESSAGE, msgId.getValue(),
                (hidden ? "photo kept off the public site" : "photo shown on the public site") + "; key=" + s3Key);
        return true;
    }

    /** Whether {@code me} wrote the message that carries this photo. */
    static boolean isAuthorOfPhoto(final ChatCommands chat, final String tripId, final String s3Key,
            final ChatMessage.Id hint, final Person.Id me) {
        return findMessageWithPhoto(chat.dao(), chat.getChannel(tripId), s3Key, hint)
                .map(ChatMessage::getAuthorId).filter(Objects::nonNull).map(me::equals).orElse(false);
    }

    /**
     * The message-copy half of a flip the trip's media page already made to the album row (and already
     * authorized by {@code MediaCommands.mayManage}). Quietly a no-op for a photo whose message cannot be found.
     */
    public static void sync(final DAO dao, final ChatChannel channel, final String s3Key, final boolean hidden) {
        if (channel == null) {
            return;
        }
        findMessageWithPhoto(dao, channel, s3Key, null)
                .ifPresent(m -> dao.setChatAttachmentHidden(channel.getId(), m.getId(), s3Key, hidden));
    }

    /**
     * The live message that carries a chat photo, by key. {@code hint} (the client's idea of the message) is
     * tried first and must actually carry the key; otherwise the channel is searched forward from the photo's
     * staging time, which the key names -- the Photos page knows a photo by its key alone.
     */
    public static Optional<ChatMessage> findMessageWithPhoto(final DAO dao, final ChatChannel channel,
            final String s3Key, final ChatMessage.Id hint) {
        if (channel == null || s3Key == null || s3Key.isBlank()) {
            return Optional.empty();
        }
        if (hint != null) {
            final Optional<ChatMessage> hinted = dao.getChatMessage(channel.getId(), hint, Cached.NO)
                    .filter(m -> !m.isDeleted() && carries(m, s3Key));
            if (hinted.isPresent()) {
                return hinted;
            }
        }
        return dao.findChatMessageWithAttachment(channel.getId(), s3Key,
                Math.max(0L, ChatPhotos.stagedAtMillis(s3Key) - 1), LOOKUP_PAGES);
    }

    private static boolean carries(final ChatMessage message, final String s3Key) {
        return message.getAttachments().stream().anyMatch(a -> !a.isDeleted() && s3Key.equals(a.getS3Key()));
    }
}
