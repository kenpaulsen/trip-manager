package org.paulsens.trip.action;

import jakarta.faces.context.FacesContext;
import java.util.Optional;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.ScopeUtil;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.CroppedImage;

/**
 * The bean side of the shared upload+crop dialog ({@code WEB-INF/photoUploadDialog.xhtml}), which five
 * features mount: profile pictures, chat photos, badge images, an organization's branding and a hotel's
 * photos. The dialog asks the same five things of whichever bean it is given -- {@link #handleUpload},
 * {@link #isUploadPending()}, {@link #confirmCrop()}, {@link #confirmFullPhoto()}, {@link #cancelUpload()}
 * -- and this class answers them once. A subclass says what an upload is FOR ({@link #purpose()},
 * {@link #targetId()}), who may make one and how large ({@link #uploadGate()}), and what a confirmed crop
 * becomes ({@link #confirm}); everything between those was the same in all five and lives here.
 *
 * <p><b>Why the dialog spans requests the way it does.</b> The bytes arrive in one request (the upload
 * listener) and the crop decision in a later one, bridged by a {@link PendingUploads} token. The token rides
 * in the SESSION map under one fixed key, never in viewScope: the cropper's image is PrimeFaces dynamic
 * content, and that streaming request runs outside the view (see {@link PendingPhotoView}, which serves it),
 * where viewScope would silently answer null. The token is a short string; the image bytes themselves never
 * enter any serialized scope. It is one key for every feature because the dialog's image source must be
 * reachable through ONE fixed EL name -- PrimeFaces stores the dynamic-content expression as a string and
 * re-evaluates it on the streaming request, where a Facelets {@code ui:param} alias no longer exists.
 *
 * <p><b>Authorization is per call, never inherited from the dialog being open.</b> {@link #uploadGate()} is
 * asked at upload time and each feature asks again at confirm time, because the right can be lost between
 * the two -- and a parked upload records what it was for, so a token minted in one dialog cannot be
 * finalized by another ({@link #claim}).
 *
 * <p>The {@code protected} methods at the bottom are seams: the unit tests subclass a bean and answer the
 * caller, the session map, the view map and the ajax response directly, so the whole flow runs with no
 * FacesContext at all. Their real bodies fail closed without one.
 *
 * <p><b>No {@code final} instance methods anywhere in this hierarchy.</b> The subclasses are
 * {@code @ApplicationScoped}, so Weld hands out a client proxy that subclasses the bean, and it refuses to
 * build one for a class with a non-private final instance method (WELD-001480). The refusal is lazy -- it
 * fires on {@code Beans.get} and injection, not at deployment -- so the pages kept working through EL while
 * every photo servlet and the trip-delete cascade answered 500, and no unit test could see it. Static
 * methods are fine; a shared step that must not be overridden gets a comment, not the keyword.
 */
public abstract class PhotoUploadBean {

    /** Session key holding the pending-upload token while the crop dialog is open (see PendingPhotoView). */
    static final String TOKEN_KEY = PendingUploads.SESSION_TOKEN_KEY;

    /** Belt-and-braces size gate; the real transport cap is the Faces Servlet's multipart-config. */
    static final long MAX_UPLOAD_BYTES = 16L * 1024 * 1024;

    /**
     * What the pre-upload gate answers: a reason to refuse, or the byte cap to enforce. The cap is part of
     * the answer because chat's comes from the channel's settings, not from a constant.
     */
    protected record UploadGate(String denial, long maxBytes) {

        public static UploadGate allow() {
            return new UploadGate(null, MAX_UPLOAD_BYTES);
        }

        public static UploadGate allow(final long maxBytes) {
            return new UploadGate(null, maxBytes);
        }

        public static UploadGate deny(final String reason) {
            return new UploadGate(reason, 0L);
        }
    }

    protected final PhotoProcessor processor = new PhotoProcessor();
    private PendingUploads pendingUploads = PendingUploads.getPendingUploads();

    void setPendingUploadsForTest(final PendingUploads pendingUploads) {
        this.pendingUploads = pendingUploads;
    }

    /** The registry parked uploads live in, for a feature that parks something of its own besides. */
    protected PendingUploads pendingUploads() {
        return pendingUploads;
    }

    // ------------------------------------------------------------------ the dialog's contract

    /**
     * {@code p:fileUpload} listener: validates, builds the browser-renderable preview, and parks the bytes
     * behind a token for the crop step. Errors surface as faces messages -- this runs mid-postback.
     */
    public void handleUpload(final FileUploadEvent event) {
        if (event.getFile() == null || event.getFile().getSize() == 0) {
            error("No " + noun() + ": no " + noun() + " was included in the upload.");
            return;
        }
        final UploadGate gate = uploadGate();
        if (gate.denial() != null) {
            error(gate.denial());
            return;
        }
        if (event.getFile().getSize() > gate.maxBytes()) {
            error("Too large: " + noun() + "s can be at most " + (gate.maxBytes() / (1024 * 1024)) + " MB.");
            return;
        }
        final byte[] bytes = event.getFile().getContent();
        try {
            final PhotoProcessor.PreviewImage preview = processor.preview(bytes);
            final PendingUploads.Pending pending =
                    pendingUploads.put(bytes, preview, callerId(), purpose(), targetId());
            sessionPut(TOKEN_KEY, pending.token());
            parked(event);
        } catch (final PhotoRejectedException ex) {
            error(capitalized(noun()) + " rejected: " + ex.getMessage());
        }
    }

    /** Whether an upload is parked and claimable -- what swaps the dialog from upload to cropper. */
    public boolean isUploadPending() {
        return pending().isPresent();
    }

    /**
     * The dialog's Save action: the cropper's rectangle ({@code viewScope.photoCrop}) goes to
     * {@link #confirm}, which reports the outcome in the {@code cropStored} ajax param -- what closes the
     * dialog only on success.
     */
    public void confirmCrop() {
        confirm(viewMap("photoCrop"));
    }

    /** "Use full photo": {@link #confirm} with no rectangle, which takes the largest well-shaped region. */
    public void confirmFullPhoto() {
        confirm(null);
    }

    /** Cancels the dialog: the parked bytes are dropped immediately rather than waiting out their TTL. */
    public void cancelUpload() {
        pending().ifPresent(this::drop);
        sessionPut(TOKEN_KEY, null);
        cancelled();
    }

    // ------------------------------------------------------------------ what a feature supplies

    /** What a parked upload is marked as, so a token minted for another feature cannot be finalized here. */
    protected abstract String purpose();

    /**
     * What the open dialog is uploading FOR (a person, a trip, an org and role...), recorded on the parked
     * upload and checked again by {@link #claim()}. Null when nothing is open, which {@link #claim} refuses.
     */
    protected abstract String targetId();

    /** Whether the caller may upload right now, and how large. Asked before any bytes are read. */
    protected abstract UploadGate uploadGate();

    /**
     * What a confirmed crop becomes -- process, store, link -- ending in {@link #publishOutcome}. Reached
     * only through the dialog's confirm buttons; the crop is null for "use the full photo".
     */
    protected abstract void confirm(CroppedImage crop);

    /** The word the messages use for what is being uploaded: "image", unless a feature says "photo". */
    protected String noun() {
        return "image";
    }

    /** Hook: an upload has just been parked (badge images remember the filename as the label default). */
    protected void parked(final FileUploadEvent event) {
    }

    /** Hook: the dialog was cancelled, for a feature with dialog state of its own to clear. */
    protected void cancelled() {
    }

    // ------------------------------------------------------------------ the shared steps

    /** The parked upload, when there is one and it is the caller's own. */
    protected Optional<PendingUploads.Pending> pending() {
        final String token = sessionGet(TOKEN_KEY);
        return token == null ? Optional.empty() : pendingUploads.peek(token, callerId());
    }

    /** {@link #claim(String)} against {@link #targetId()}. */
    protected Optional<PendingUploads.Pending> claim() {
        return claim(targetId());
    }

    /**
     * The parked upload, once it is verified to be this feature's and for {@code expectedTargetId}; empty,
     * with the reason already growled, otherwise. The target is a parameter rather than always
     * {@link #targetId()} because a feature can be asked to confirm for an explicit subject -- an admin who
     * uploaded for one person and is now on another's page -- and it is THAT subject the token must match.
     */
    protected Optional<PendingUploads.Pending> claim(final String expectedTargetId) {
        final Optional<PendingUploads.Pending> found = pending();
        if (found.isEmpty()) {
            error("Upload expired: the uploaded " + noun() + " is no longer available. Upload it again.");
            return Optional.empty();
        }
        final PendingUploads.Pending pending = found.get();
        if (!purpose().equals(pending.purpose()) || expectedTargetId == null
                || !expectedTargetId.equals(pending.targetId())) {
            error("Upload mismatch: the uploaded " + noun() + " is no longer available. Upload it again.");
            return Optional.empty();
        }
        return Optional.of(pending);
    }

    /** A confirmed crop is stored: drop the parked bytes and the token that named them. */
    protected void finish(final PendingUploads.Pending pending) {
        drop(pending);
        sessionPut(TOKEN_KEY, null);
    }

    private void drop(final PendingUploads.Pending pending) {
        pendingUploads.consume(pending.token());
    }

    /**
     * Preview-space cropper coordinates scaled into the full-resolution space the crop is applied in: the
     * cropper showed the small preview, and its own cropped bytes come from that preview and are ignored.
     */
    protected static PhotoProcessor.CropRect scaledRect(final CroppedImage crop,
            final PendingUploads.Pending pending) {
        return crop == null ? null
                : pending.scaleToFull(crop.getLeft(), crop.getTop(), crop.getWidth(), crop.getHeight());
    }

    protected String callerId() {
        final Person.Id id = caller().personId();
        return id == null ? null : id.getValue();
    }

    // ------------------------------------------------------------------ seams

    /** Seam: tests hand back a constructed {@link Caller} instead of the FacesContext-resolved one. */
    protected Caller caller() {
        return Caller.current();
    }

    /** Seam: dialog-local viewScope reads; tests override. */
    protected <T> T viewMap(final String key) {
        return ScopeUtil.getInstance().getViewMap(key);
    }

    /** Seams around the session map -- the FacesContext is absent in unit tests. */
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

    /** Seam: the deployment's context path, which local-mode photo URLs are prefixed with. */
    protected String contextPath() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        return ctx == null ? "" : ctx.getExternalContext().getRequestContextPath();
    }

    /** Seam: hands the outcome to the dialog's oncomplete JS; a test records it instead. */
    protected void publishOutcome(final boolean stored) {
        publishParam("cropStored", stored);
    }

    /** Seam: a single named ajax callback param; a test records it instead. */
    protected void publishParam(final String name, final Object value) {
        PageFeedback.callbackParam(name, value);
    }

    // ------------------------------------------------------------------ feedback

    protected static void info(final String message) {
        PageFeedback.info(message);
    }

    protected static void warn(final String message) {
        PageFeedback.warn(message);
    }

    protected static void error(final String message) {
        PageFeedback.error(message);
    }

    private static String capitalized(final String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
