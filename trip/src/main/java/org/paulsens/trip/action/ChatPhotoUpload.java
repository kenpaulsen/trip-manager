package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.media.UploadRateLimiter;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.ScopeUtil;
import org.primefaces.model.CroppedImage;

/**
 * The chat side of the shared upload+crop dialog, exposed as {@code #{chatPhotoUpload}} --
 * {@link PhotoUploadBean}'s contract applied to chat's rules: trip membership via
 * {@code ChatCommands.checkAttach}, the channel's per-photo byte cap, and the per-person upload rate limit
 * ({@link UploadRateLimiter#chatPhotos()}, shared with the REST upload endpoint so the allowance is one).
 *
 * <p>A confirmed crop runs {@link ChatPhotos#stage} -- everything downstream (the composer tray, send-time
 * claiming through {@code ChatPhotoStaging}, album rows, retention) is untouched; the dialog merely replaced
 * how bytes reach {@code stage}. The staged photo's details are handed to the page's tray JS through ajax
 * callback params ({@code photoKey}, {@code photoUrl}, ...) read in the dialog's {@code afterStored} hook.
 */
@Slf4j
@Named("chatPhotoUpload")
@ApplicationScoped
public class ChatPhotoUpload extends PhotoUploadBean {

    static final int UPLOADS_PER_WINDOW = UploadRateLimiter.CHAT_UPLOADS_PER_WINDOW;

    /** What a pending chat upload is marked as, so a profile token cannot be staged into a chat. */
    static final String PURPOSE = "chat";

    @Override
    protected String purpose() {
        return PURPOSE;
    }

    @Override
    protected String targetId() {
        return tripId();
    }

    @Override
    protected String noun() {
        return "photo";
    }

    /**
     * A chat's "Choose from Albums" lists the albums of the trip's own organization, its own album first. Chat
     * does not copy a chosen photo the way the other hosts do: its page attaches a REFERENCE to the original
     * ({@code ChatPhotos.albumRef}), and the send checks each one again in this same scope.
     */
    @Override
    protected String albumScope() {
        final String tripId = tripId();
        return tripId == null ? null : AlbumPhotoSource.tripScope(tripId);
    }

    /**
     * Who may attach photos in this chat (and so list albums for it): signed in, in a trip chat, and let in by
     * {@code checkAttach}; the cap is the channel's. The album listing asks this too, so it never spends the upload
     * rate limit. Whose albums are then listed is still each album's own rule ({@link AlbumPhotoSource#mayOpen}).
     */
    @Override
    protected UploadGate authorization() {
        final String tripId = tripId();
        final Person.Id me = caller().personId();
        if (tripId == null || me == null) {
            return UploadGate.deny(Text.get("feedback.chatPhotoUpload.signInFirst"));
        }
        final ChatCommands.AttachGate gate = checkAttach(tripId, me);
        if (gate.denial() != null) {
            return UploadGate.deny(Text.get("feedback.chatPhotoUpload.notAllowed", gate.denial()));
        }
        return UploadGate.allow(gate.channel().getSettings().getMaxAttachmentBytes());
    }

    /** The per-person upload rate limit, spent only by someone {@link #authorization} admits. */
    @Override
    protected String limit() {
        return limiter().allow(caller().personId().getValue())
                ? null : Text.get("api.chat.tooManyUploads");
    }

    /** Stage the (possibly cropped) photo and hand its details to the tray JS; a failure reports itself. */
    @Override
    protected void confirm(final CroppedImage crop) {
        final Optional<PendingUploads.Pending> claimed = claim();
        if (claimed.isEmpty()) {
            publishOutcome(false, null, null);
            return;
        }
        final PendingUploads.Pending pending = claimed.get();
        final String tripId = tripId();
        try {
            final ChatPhotos photos = chatPhotos();
            final ChatPhotos.StagedPhoto staged =
                    photos.stage(tripId, caller().personId(), pending.original(), scaledRect(crop, pending));
            finish(pending);
            publishOutcome(true, staged, photos.getPhotoPageBase());
        } catch (final PhotoRejectedException ex) {
            error(Text.get("feedback.photoUploadBean.rejected.photo", ex.getMessage()));
            publishOutcome(false, null, null);
        } catch (final IllegalStateException ex) {
            log.error("Chat photo store failed for trip {}", tripId, ex);
            error(Text.get("feedback.albumLibrary.notStored"));
            publishOutcome(false, null, null);
        }
    }

    /** Seams: tests supply the trip, the gate, the limiter and the store directly. */
    protected UploadRateLimiter limiter() {
        return UploadRateLimiter.chatPhotos();
    }

    protected String tripId() {
        final Object tripId = ScopeUtil.getInstance().getViewMap("theTripId");
        return tripId == null ? null : tripId.toString();
    }

    protected ChatCommands.AttachGate checkAttach(final String tripId, final Person.Id me) {
        return ChatCommands.getChatCommands().checkAttach(tripId, me);
    }

    protected ChatPhotos chatPhotos() {
        return ChatPhotos.getChatPhotos();
    }

    /**
     * Seam: reports the outcome and, on success, the staged photo's details to the dialog's oncomplete JS.
     * The plain {@code cropStored} goes through the base seam so the dialog closes on it like any other.
     */
    protected void publishOutcome(final boolean stored, final ChatPhotos.StagedPhoto staged,
            final String publicBase) {
        publishOutcome(stored);
        if (stored && staged != null) {
            final String base = (publicBase != null) ? publicBase : contextPath() + "/chat-photos/";
            publishParam("photoKey", staged.key());
            publishParam("photoSmallKey", staged.smallKey());
            publishParam("photoUrl", base + staged.key());
            publishParam("photoSmallUrl", base + staged.smallKey());
            publishParam("photoWidth", staged.width());
            publishParam("photoHeight", staged.height());
        }
    }
}
