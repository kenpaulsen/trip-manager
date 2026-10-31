package org.paulsens.trip.action;

import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.media.ProcessedPhoto;
import org.paulsens.trip.media.QuarterTurn;
import org.paulsens.trip.media.RotationGuard;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.chat.ChatAttachment;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;

/**
 * Turning a posted chat photo a quarter, persisted: the ONE rule both the chat page and the trip Photos page
 * (through REST) and the apps call. The photo keeps its key -- that key is its identity: the
 * {@code photo:{s3Key}} comment thread, its reactions, {@code PhotoChatMeta} and the album row all join on it
 * -- so the renditions are overwritten in place and {@code rev} on the attachment and the album row is what
 * moves every client's URL ({@code ?r=N}).
 *
 * <p>Never fires {@code MediaEvents.REMOVED} for the key: {@code ChatLifecycleListener} answers that event
 * by purging the photo's comment thread, which a rotation must not do.
 *
 * <p>Its own class because {@link ChatCommands} is at its checkstyle length limit (the {@link ChatPhotoEdits}
 * arrangement). Authorization is the visibility flag's: the author of the carrying message, or a chat admin.
 */
@Slf4j
public final class ChatPhotoRotation {

    /** What a rotation came to; {@code attachment} is the rewritten one on {@link #OK}. */
    public record Outcome(String code, String message, ChatAttachment attachment) {
        public static final String OK = "ok";
        public static final String NOT_FOUND = "not_found";
        public static final String FORBIDDEN = "forbidden";
        public static final String REJECTED = "rejected";
        public static final String BUSY = "busy";
        public static final String STORE_FAILED = "store";

        static Outcome refused(final String code, final String message) {
            return new Outcome(code, message, null);
        }

        public boolean ok() {
            return OK.equals(code);
        }
    }

    private static final PhotoProcessor PROCESSOR = new PhotoProcessor();

    private ChatPhotoRotation() {
    }

    /**
     * Turns one posted photo a quarter. {@code hint} is the message the client believes carries it (tried
     * first). Refusals: NOT_FOUND (no live message carries the key, or its bytes are gone), FORBIDDEN (neither
     * the author nor a chat admin; audited like any refused moderation), REJECTED (an animated GIF), BUSY
     * (another rotation of the same photo is running), STORE_FAILED.
     */
    public static Outcome rotate(final ChatCommands chat, final String tripId, final String s3Key,
            final QuarterTurn turn, final ChatMessage.Id hint, final Caller caller) {
        return rotate(chat, ChatPhotos.getChatPhotos(), tripId, s3Key, turn, hint, caller);
    }

    /** {@link #rotate(ChatCommands, String, String, QuarterTurn, ChatMessage.Id, Caller)} on a given store. */
    static Outcome rotate(final ChatCommands chat, final ChatPhotos photos, final String tripId,
            final String s3Key, final QuarterTurn turn, final ChatMessage.Id hint, final Caller caller) {
        final ChatChannel channel = chat.getChannel(tripId);
        final Optional<ChatMessage> carrier = ChatPhotoEdits.findMessageWithPhoto(chat.dao(), channel, s3Key, hint);
        if (carrier.isEmpty()) {
            return Outcome.refused(Outcome.NOT_FOUND, "Photo not found.");
        }
        final AuditActor who = ChatCommands.actorOf(caller);
        final ChatMessage.Id msgId = carrier.get().getId();
        if (!chat.isOwnMessage(carrier, who)
                && chat.denyUnlessAdmin(tripId, "rotate photo in message " + msgId.getValue(), caller)) {
            return Outcome.refused(Outcome.FORBIDDEN, "You can only rotate photos you posted.");
        }
        if (!RotationGuard.begin(s3Key)) {
            return Outcome.refused(Outcome.BUSY, "That photo is already being rotated. Try again in a moment.");
        }
        try {
            return rotateOwned(chat, photos, tripId, channel, carrier.get(), s3Key, turn, who);
        } finally {
            RotationGuard.end(s3Key);
        }
    }

    private static Outcome rotateOwned(final ChatCommands chat, final ChatPhotos photos, final String tripId,
            final ChatChannel channel, final ChatMessage carrier, final String s3Key, final QuarterTurn turn,
            final AuditActor who) {
        final ChatAttachment attachment = carrier.getAttachments().stream()
                .filter(a -> !a.isDeleted() && s3Key.equals(a.getS3Key())).findFirst().orElse(null);
        final Optional<byte[]> current = (attachment == null) ? Optional.empty() : photos.currentBytes(s3Key);
        if (current.isEmpty()) {
            return Outcome.refused(Outcome.NOT_FOUND, "Photo not found.");
        }
        final ProcessedPhoto renditions;
        try {
            renditions = PROCESSOR.process(PROCESSOR.rotate(current.get(), turn).bytes());
        } catch (final PhotoRejectedException ex) {
            return Outcome.refused(Outcome.REJECTED, ex.getMessage());
        }
        final String thumbKey;
        try {
            thumbKey = photos.replaceRenditions(attachment, renditions);
        } catch (final IllegalStateException ex) {
            log.error("Chat photo {} could not be stored after rotating", s3Key, ex);
            return Outcome.refused(Outcome.STORE_FAILED, "The photo could not be stored. Try again.");
        }
        final long size = renditions.fullBytes().length;
        final Optional<ChatMessage> updated = chat.dao().rotateChatAttachment(channel.getId(), carrier.getId(),
                s3Key, renditions.width(), renditions.height(), size, thumbKey);
        if (updated.isEmpty()) {
            log.error("Chat photo {} rotated in the store but its message was not rewritten", s3Key);
            return Outcome.refused(Outcome.STORE_FAILED, "The photo could not be stored. Try again.");
        }
        if (!photos.setAlbumRotated(tripId, s3Key, size, thumbKey)) {
            log.error("Chat photo {} rotated in its message but not in the album", s3Key);
        }
        chat.audit(AuditAction.CHAT_ADMIN, who, AuditEventBuilder.TARGET_CHAT_MESSAGE, carrier.getId().getValue(),
                "photo rotated " + turn.name().toLowerCase(Locale.ROOT) + "; key=" + s3Key);
        return new Outcome(Outcome.OK, null, rotatedIn(updated.get(), s3Key));
    }

    private static ChatAttachment rotatedIn(final ChatMessage message, final String s3Key) {
        return message.getAttachments().stream()
                .filter(a -> !a.isDeleted() && s3Key.equals(a.getS3Key())).findFirst().orElseThrow();
    }
}
