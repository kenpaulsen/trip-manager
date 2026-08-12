package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.ScopeUtil;
import org.primefaces.PrimeFaces;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.CroppedImage;

/**
 * The chat side of the shared upload+crop dialog, exposed as {@code #{chatPhotoUpload}} — the same bean
 * contract {@code ProfilePhotoCommands} honors (handleUpload / uploadPending / confirm / cancel), applied to
 * chat's rules: trip membership via {@code ChatCommands.checkAttach}, the channel's per-photo byte cap, and
 * the per-person upload rate limit that used to live in the retired upload servlet's POST path.
 *
 * <p>A confirmed crop runs {@link ChatPhotos#stage} — everything downstream (the composer tray, send-time
 * claiming through {@code ChatPhotoStaging}, album rows, retention) is untouched; the dialog merely replaced
 * how bytes reach {@code stage}. The staged photo's details are handed to the page's tray JS through ajax
 * callback params ({@code photoKey}, {@code photoUrl}, ...) read in the dialog's {@code afterStored} hook.
 */
@Slf4j
@Named("chatPhotoUpload")
@ApplicationScoped
public class ChatPhotoUpload {

    /** Uploads per person per window — far above honest use (10/message), low enough to stop a loop. */
    static final int UPLOADS_PER_WINDOW = 40;
    static final int WINDOW_SECONDS = 600;

    /** What a pending chat upload is marked as, so a profile token cannot be staged into a chat. */
    static final String PURPOSE = "chat";

    private final PhotoProcessor processor = new PhotoProcessor();
    private PendingUploads pendingUploads = PendingUploads.getPendingUploads();

    /** Per-person upload timestamps. Process-local like the staging registry, and the same trade. */
    private final Map<String, Deque<Long>> recentUploads = new ConcurrentHashMap<>();

    void setPendingUploadsForTest(final PendingUploads pendingUploads) {
        this.pendingUploads = pendingUploads;
    }

    /** {@code p:fileUpload} listener: chat's gate checks, then park the bytes behind a token. */
    public void handleUpload(final FileUploadEvent event) {
        final String tripId = tripId();
        final Person.Id me = caller().personId();
        if (tripId == null || me == null) {
            error("Not allowed", "Sign in and open a trip chat to attach photos.");
            return;
        }
        if (event.getFile() == null || event.getFile().getSize() == 0) {
            error("No photo", "No photo was included in the upload.");
            return;
        }
        if (!allowUpload(me.getValue())) {
            error("Too many uploads", "Too many uploads. Wait a minute and try again.");
            return;
        }
        final ChatCommands.AttachGate gate = checkAttach(tripId, me);
        if (gate.denial() != null) {
            error("Not allowed", gate.denial());
            return;
        }
        final long cap = gate.channel().getSettings().getMaxAttachmentBytes();
        if (event.getFile().getSize() > cap) {
            error("Too large", "That photo is too large. Photos can be at most "
                    + (cap / (1024 * 1024)) + " MB.");
            return;
        }
        final byte[] bytes = event.getFile().getContent();
        try {
            final PhotoProcessor.PreviewImage preview = processor.preview(bytes);
            final PendingUploads.Pending pending = pendingUploads.put(bytes, preview,
                    me.getValue(), PURPOSE, tripId);
            sessionPut(PendingUploads.SESSION_TOKEN_KEY, pending.token());
        } catch (final PhotoRejectedException ex) {
            error("Photo rejected", ex.getMessage());
        }
    }

    /** Whether an upload is parked and claimable — what swaps the dialog from upload to cropper. */
    public boolean isUploadPending() {
        return pending().isPresent();
    }

    /** The dialog's Save action: stage the cropped photo and hand its details to the tray JS. */
    public void confirmCrop() {
        stageWith(viewMap("photoCrop"));
    }

    /** "Use full photo": stage uncropped — also the only crop an animated GIF can have. */
    public void confirmFullPhoto() {
        stageWith(null);
    }

    /** Cancels the dialog: the parked bytes are dropped immediately rather than waiting out their TTL. */
    public void cancelUpload() {
        pending().ifPresent(this::drop);
        sessionPut(PendingUploads.SESSION_TOKEN_KEY, null);
    }

    private void drop(final PendingUploads.Pending pending) {
        pendingUploads.consume(pending.token());
    }

    private void stageWith(final CroppedImage crop) {
        final String tripId = tripId();
        final Person.Id me = caller().personId();
        final Optional<PendingUploads.Pending> found = pending();
        if (tripId == null || me == null || found.isEmpty()) {
            error("Upload expired", "The uploaded photo is no longer available. Upload it again.");
            publishOutcome(false, null, null);
            return;
        }
        final PendingUploads.Pending pending = found.get();
        if (!PURPOSE.equals(pending.purpose()) || !tripId.equals(pending.targetId())) {
            error("Upload mismatch", "The uploaded photo is no longer available. Upload it again.");
            publishOutcome(false, null, null);
            return;
        }
        // Cropper coords are in PREVIEW space; only the Pending knows the ratio to the full image.
        final PhotoProcessor.CropRect rect = (crop == null) ? null
                : pending.scaleToFull(crop.getLeft(), crop.getTop(), crop.getWidth(), crop.getHeight());
        try {
            final ChatPhotos photos = chatPhotos();
            final ChatPhotos.StagedPhoto staged = photos.stage(tripId, me, pending.original(), rect);
            pendingUploads.consume(pending.token());
            sessionPut(PendingUploads.SESSION_TOKEN_KEY, null);
            publishOutcome(true, staged, photos.getPhotoPageBase());
        } catch (final PhotoRejectedException ex) {
            error("Photo rejected", ex.getMessage());
            publishOutcome(false, null, null);
        } catch (final IllegalStateException ex) {
            log.error("Chat photo store failed for trip {}", tripId, ex);
            error("Not stored", "The photo could not be stored. Try again.");
            publishOutcome(false, null, null);
        }
    }

    private Optional<PendingUploads.Pending> pending() {
        final String token = sessionGet(PendingUploads.SESSION_TOKEN_KEY);
        final Person.Id me = caller().personId();
        return (token == null || me == null)
                ? Optional.empty() : pendingUploads.peek(token, me.getValue());
    }

    private boolean allowUpload(final String personId) {
        final long now = System.currentTimeMillis();
        final Deque<Long> times = recentUploads.computeIfAbsent(personId, key -> new ArrayDeque<>());
        synchronized (times) {
            final long cutoff = now - WINDOW_SECONDS * 1_000L;
            while (!times.isEmpty() && times.peekFirst() < cutoff) {
                times.pollFirst();
            }
            if (times.size() >= UPLOADS_PER_WINDOW) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }

    /** Seams (the ProfilePhotoCommands pattern): tests supply collaborators and scopes directly. */
    protected Caller caller() {
        return Caller.current();
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

    protected <T> T viewMap(final String key) {
        return ScopeUtil.getInstance().getViewMap(key);
    }

    protected String sessionGet(final String key) {
        return ScopeUtil.getInstance().getSessionMap(key);
    }

    protected void sessionPut(final String key, final String value) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx == null) {
            return;
        }
        if (value == null) {
            ctx.getExternalContext().getSessionMap().remove(key);
        } else {
            ctx.getExternalContext().getSessionMap().put(key, value);
        }
    }

    /** Seam: reports the outcome (and on success the staged photo's details) to the dialog's oncomplete JS. */
    protected void publishOutcome(final boolean stored, final ChatPhotos.StagedPhoto staged,
            final String publicBase) {
        if (FacesContext.getCurrentInstance() == null) {
            return;
        }
        final PrimeFaces pf = PrimeFaces.current();
        if (!pf.isAjaxRequest()) {
            return;
        }
        pf.ajax().addCallbackParam("cropStored", stored);
        if (stored && staged != null) {
            final String base = (publicBase != null) ? publicBase : contextPath() + "/chat-photos/";
            pf.ajax().addCallbackParam("photoKey", staged.key());
            pf.ajax().addCallbackParam("photoSmallKey", staged.smallKey());
            pf.ajax().addCallbackParam("photoUrl", base + staged.key());
            pf.ajax().addCallbackParam("photoSmallUrl", base + staged.smallKey());
            pf.ajax().addCallbackParam("photoWidth", staged.width());
            pf.ajax().addCallbackParam("photoHeight", staged.height());
        }
    }

    private String contextPath() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        return ctx == null ? "" : ctx.getExternalContext().getRequestContextPath();
    }

    private static void error(final String summary, final String detail) {
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, summary, detail);
    }
}
