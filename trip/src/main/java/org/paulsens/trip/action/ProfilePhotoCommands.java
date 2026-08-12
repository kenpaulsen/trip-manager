package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.media.BackgroundRemover;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.ScopeUtil;
import org.primefaces.PrimeFaces;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.CroppedImage;

/**
 * The profile-picture editor's backing bean, exposed as {@code #{profilePhotoEdit}}: the upload listener, the
 * cropper's image source, and the crop/delete actions behind the dialog on {@code account/person.xhtml}.
 *
 * <p>The dialog spans requests — upload in one, crop confirmation in a later one — bridged by a
 * {@link PendingUploads} token. The token rides in the SESSION map, not viewScope: the cropper's image is
 * PrimeFaces dynamic content, and that streaming request runs outside the view (see {@link PendingPhotoView},
 * which serves it), where viewScope would silently answer null. The token is a short string; the image bytes
 * themselves never enter any serialized scope.
 *
 * <p>Authorization is per-call, the same rule as the page itself: yourself, someone whose profile you manage
 * ({@code managedUsers}), or a site admin. The upload listener cannot receive extra EL arguments, so the
 * SUBJECT (whose profile is being edited) is read from the page's {@code viewScope.person} — the value the
 * page already resolved through {@code people.getSubject}.
 */
@Slf4j
@Named("profilePhotoEdit")
@ApplicationScoped
public class ProfilePhotoCommands {

    /** Session key holding the pending-upload token while the crop dialog is open (see PendingPhotoView). */
    static final String TOKEN_KEY = PendingUploads.SESSION_TOKEN_KEY;

    /** What a pending profile upload is marked as, so a chat token cannot be finalized as a profile photo. */
    static final String PURPOSE = "profile";

    /** The background-removal dialog's own token key + purpose (its pending entry is a cutout PNG). */
    static final String BG_TOKEN_KEY = "profileBgCutoutToken";
    public static final String BG_PURPOSE = "profileBg";

    /** Belt-and-braces size gate; the real transport cap is the Faces Servlet's multipart-config. */
    static final long MAX_UPLOAD_BYTES = 16L * 1024 * 1024;

    private final PhotoProcessor processor = new PhotoProcessor();
    private PendingUploads pendingUploads = PendingUploads.getPendingUploads();

    @Inject
    private PersonCommands people;

    @Inject
    private ProfilePhotos profilePhotos;

    @Inject
    private ConfigCommands config;

    void setPendingUploadsForTest(final PendingUploads pendingUploads) {
        this.pendingUploads = pendingUploads;
    }

    /**
     * {@code p:fileUpload} listener: validates, builds the browser-renderable preview, and parks the bytes
     * behind a token for the crop step. Errors surface as faces messages — this runs mid-postback.
     */
    public void handleUpload(final FileUploadEvent event) {
        final Person subject = subject();
        if (!mayEdit(subject)) {
            error("Not allowed", "You may not change this profile picture.");
            return;
        }
        if (event.getFile() == null || event.getFile().getSize() == 0) {
            error("No photo", "No photo was included in the upload.");
            return;
        }
        if (event.getFile().getSize() > MAX_UPLOAD_BYTES) {
            error("Too large", "Photos can be at most " + (MAX_UPLOAD_BYTES / (1024 * 1024)) + " MB.");
            return;
        }
        final byte[] bytes = event.getFile().getContent();
        try {
            final PhotoProcessor.PreviewImage preview = processor.preview(bytes);
            final PendingUploads.Pending pending = pendingUploads.put(bytes, preview,
                    callerId(), PURPOSE, subject.getId().getValue());
            sessionPut(TOKEN_KEY, pending.token());
        } catch (final PhotoRejectedException ex) {
            error("Photo rejected", ex.getMessage());
        }
    }

    /** Whether an upload is parked and claimable — what swaps the dialog from upload to cropper. */
    public boolean isUploadPending() {
        return pending().isPresent();
    }

    /**
     * Applies the confirmed crop: coordinates arrive in preview-pixel space (the image the cropper showed),
     * are scaled to the full-resolution space, forced square, and re-cropped from the ORIGINAL bytes — the
     * cropper's own cropped bytes come from the small preview and are ignored.
     *
     * @param subject    whose profile (the page's {@code viewScope.person})
     * @param crop       the cropper's submitted value
     * @param targetSlot the slot to replace, or null/0 to take the next free slot
     * @return true when stored; false leaves the dialog open with a message explaining why.
     */
    public boolean applyCrop(final Person subject, final CroppedImage crop, final Integer targetSlot) {
        if (!mayEdit(subject)) {
            error("Not allowed", "You may not change this profile picture.");
            return false;
        }
        final Optional<PendingUploads.Pending> found = pending();
        if (found.isEmpty()) {
            error("Upload expired", "The uploaded photo is no longer available. Upload it again.");
            return false;
        }
        final PendingUploads.Pending pending = found.get();
        if (!PURPOSE.equals(pending.purpose()) || !subject.getId().getValue().equals(pending.targetId())) {
            error("Upload mismatch", "The uploaded photo is no longer available. Upload it again.");
            return false;
        }
        final int slot = (targetSlot == null || targetSlot < 1)
                ? profilePhotos.nextFreeSlot(subject.getId().getValue()) : targetSlot;
        if (slot < 1 || slot > ProfilePhotos.MAX_SLOTS) {
            error("No free slot", "All " + ProfilePhotos.MAX_SLOTS + " picture slots are in use. "
                    + "Replace or delete one instead.");
            return false;
        }
        final byte[] jpeg;
        try {
            jpeg = processor.processProfile(pending.original(), scaledRect(crop, pending));
        } catch (final PhotoRejectedException ex) {
            error("Photo rejected", ex.getMessage());
            return false;
        }
        if (!profilePhotos.store(subject.getId().getValue(), slot, jpeg)) {
            error("Not stored", "The photo could not be stored. Try again.");
            return false;
        }
        pendingUploads.consume(pending.token());
        sessionPut(TOKEN_KEY, null);
        audit("Profile picture stored", subject, slot);
        info("Profile picture saved", "Slot " + slot + " updated.");
        return true;
    }

    /**
     * Deletes one slot's picture (the typed-"delete" dialog's action; the typed challenge is the PAGE's
     * server-side check — by the time this runs the challenge already passed).
     */
    public boolean deleteSlot(final Person subject, final int slot) {
        if (!mayEdit(subject)) {
            error("Not allowed", "You may not change this profile picture.");
            return false;
        }
        if (!profilePhotos.deleteSlot(subject.getId().getValue(), slot)) {
            return false;
        }
        audit("Profile picture deleted", subject, slot);
        info("Profile picture deleted", "Slot " + slot + " removed.");
        return true;
    }

    /**
     * The shared dialog's Crop action: reads the dialog-local state — the cropper's value from
     * {@code viewScope.photoCrop} and the page's {@code viewScope.photoTargetSlot} — applies it, and reports
     * the outcome to the dialog's {@code oncomplete} JS via the {@code cropStored} callback param, which is
     * what closes the dialog only on success.
     */
    public void confirmCrop() {
        publishOutcome(applyCrop(subject(), viewMap("photoCrop"), targetSlot()));
    }

    /** "Use full photo": {@link #confirmCrop} with no rect, which crops the centered maximum square. */
    public void confirmFullPhoto() {
        publishOutcome(applyCrop(subject(), null, targetSlot()));
    }

    /** EL number literals land in viewScope as Long, setPropertyActionListener values as Integer — accept both. */
    private Integer targetSlot() {
        final Number slot = viewMap("photoTargetSlot");
        return slot == null ? null : slot.intValue();
    }

    /** Cancels the dialog: the parked bytes are dropped immediately rather than waiting out their TTL. */
    public void cancelUpload() {
        pending().ifPresent(this::drop);
        sessionPut(TOKEN_KEY, null);
    }

    /**
     * Background removal, step 1 (flag-gated by {@code profile.bgRemoval.enabled}): runs the model over the
     * slot's current picture and parks the RGBA cutout behind a token. The dialog previews it entirely
     * client-side (canvas: fill color, draw cutout via the published {@code cutoutUrl}); nothing is stored
     * until {@link #applyBackground}.
     */
    public void startBgRemoval(final Person subject, final int slot) {
        if (!mayEdit(subject) || !bgRemovalEnabled()) {
            error("Not available", "Background replacement is not available.");
            publishParam("bgReady", false);
            return;
        }
        final Optional<byte[]> current = profilePhotos.currentBytes(subject.getId().getValue(), slot);
        if (current.isEmpty()) {
            error("No picture", "That slot has no picture to edit.");
            publishParam("bgReady", false);
            return;
        }
        final Optional<byte[]> cutout;
        try {
            cutout = removeBackground(current.get());
        } catch (final PhotoRejectedException ex) {
            error("Photo rejected", ex.getMessage());
            publishParam("bgReady", false);
            return;
        }
        if (cutout.isEmpty()) {
            error("Busy", "Background replacement is busy or unavailable right now. Try again shortly.");
            publishParam("bgReady", false);
            return;
        }
        final PhotoProcessor.PreviewImage preview = new PhotoProcessor.PreviewImage(cutout.get(),
                PhotoProcessor.PROFILE_SIZE, PhotoProcessor.PROFILE_SIZE,
                PhotoProcessor.PROFILE_SIZE, PhotoProcessor.PROFILE_SIZE);
        final PendingUploads.Pending pending = pendingUploads.put(cutout.get(), preview,
                callerId(), BG_PURPOSE, subject.getId().getValue() + "#" + slot);
        sessionPut(BG_TOKEN_KEY, pending.token());
        publishParam("bgReady", true);
        publishParam("cutoutUrl", contextPath() + "/profile-photos/preview/" + pending.token());
    }

    /**
     * Background removal, step 2: composites the parked cutout over the chosen color
     * ({@code viewScope.bgPhotoColor}, the dialog's p:colorPicker) and stores the result as a NEW versioned
     * key in the same slot — the original object is replaced only here, on explicit confirm.
     */
    public boolean applyBackground(final Person subject) {
        if (!mayEdit(subject) || !bgRemovalEnabled()) {
            error("Not available", "Background replacement is not available.");
            publishParam("bgApplied", false);
            return false;
        }
        final String token = sessionGet(BG_TOKEN_KEY);
        final Optional<PendingUploads.Pending> found =
                token == null ? Optional.empty() : pendingUploads.peek(token, callerId());
        final String targetPrefix = subject.getId().getValue() + "#";
        if (found.isEmpty() || !BG_PURPOSE.equals(found.get().purpose())
                || !found.get().targetId().startsWith(targetPrefix)) {
            error("Expired", "The background preview is no longer available. Start over.");
            publishParam("bgApplied", false);
            return false;
        }
        final PendingUploads.Pending pending = found.get();
        final Integer rgb = parseColor(viewMap("bgPhotoColor"));
        if (rgb == null) {
            error("Pick a color", "Choose a background color first.");
            publishParam("bgApplied", false);
            return false;
        }
        final int slot = Integer.parseInt(pending.targetId().substring(targetPrefix.length()));
        final byte[] jpeg;
        try {
            jpeg = PhotoProcessor.compositeOnColor(pending.original(), rgb);
        } catch (final PhotoRejectedException ex) {
            error("Photo rejected", ex.getMessage());
            publishParam("bgApplied", false);
            return false;
        }
        if (!profilePhotos.store(subject.getId().getValue(), slot, jpeg)) {
            error("Not stored", "The picture could not be stored. Try again.");
            publishParam("bgApplied", false);
            return false;
        }
        pendingUploads.consume(pending.token());
        sessionPut(BG_TOKEN_KEY, null);
        audit("Profile picture background replaced", subject, slot);
        info("Background replaced", "Slot " + slot + " updated.");
        publishParam("bgApplied", true);
        return true;
    }

    /** Accepts the picker's "#rrggbb" or bare "rrggbb"; anything else is a refusal, not a guess. */
    static Integer parseColor(final String raw) {
        if (raw == null) {
            return null;
        }
        final String hex = raw.startsWith("#") ? raw.substring(1) : raw;
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            return null;
        }
        return Integer.parseInt(hex, 16);
    }

    private void drop(final PendingUploads.Pending pending) {
        pendingUploads.consume(pending.token());
    }

    /** Preview-space cropper coords scaled into the full-resolution space processProfile crops in. */
    private static PhotoProcessor.CropRect scaledRect(final CroppedImage crop,
            final PendingUploads.Pending pending) {
        if (crop == null) {
            return null;
        }
        return pending.scaleToFull(crop.getLeft(), crop.getTop(), crop.getWidth(), crop.getHeight());
    }

    private Optional<PendingUploads.Pending> pending() {
        final String token = sessionGet(TOKEN_KEY);
        return token == null ? Optional.empty() : pendingUploads.peek(token, callerId());
    }

    /** Yourself, someone you manage, or a site admin — the same rule the page enforces for its fields. */
    private boolean mayEdit(final Person subject) {
        if (subject == null || subject.getId() == null) {
            return false;
        }
        final Caller caller = caller();
        if (!caller.isAuthenticated()) {
            return false;
        }
        if (caller.isSiteAdmin() || subject.getId().equals(caller.personId())) {
            return true;
        }
        return people.canAccessUserId(people.getPerson(caller.personId()), subject.getId());
    }

    private String callerId() {
        final Person.Id id = caller().personId();
        return id == null ? null : id.getValue();
    }

    /** Seam: tests hand back a constructed {@link Caller} instead of the FacesContext-resolved one. */
    protected Caller caller() {
        return Caller.current();
    }

    /** Seam: the page's subject ({@code viewScope.person}); tests override. */
    protected Person subject() {
        return ScopeUtil.getInstance().getViewMap("person");
    }

    /** Seam: dialog-local viewScope reads; tests override. */
    protected <T> T viewMap(final String key) {
        return ScopeUtil.getInstance().getViewMap(key);
    }

    /** Seam: the model call — a test substitutes a canned cutout so inference is not a unit-test concern. */
    protected Optional<byte[]> removeBackground(final byte[] source) {
        return BackgroundRemover.cutout(source);
    }

    /** Seam: the feature flag ({@code KnownSettings.PROFILE_BG_REMOVAL_ENABLED}); tests override. */
    protected boolean bgRemovalEnabled() {
        return config != null && config.getBoolean(KnownSettings.PROFILE_BG_REMOVAL_ENABLED);
    }

    /** Seam: a single named ajax callback param, guarded like {@link #publishOutcome}. */
    protected void publishParam(final String name, final Object value) {
        if (FacesContext.getCurrentInstance() == null) {
            return;
        }
        final PrimeFaces pf = PrimeFaces.current();
        if (pf.isAjaxRequest()) {
            pf.ajax().addCallbackParam(name, value);
        }
    }

    private String contextPath() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        return ctx == null ? "" : ctx.getExternalContext().getRequestContextPath();
    }

    /** Seam: hands the outcome to the dialog's oncomplete JS; a test records it instead. */
    protected void publishOutcome(final boolean stored) {
        if (FacesContext.getCurrentInstance() == null) {
            return;
        }
        final PrimeFaces pf = PrimeFaces.current();
        if (pf.isAjaxRequest()) {
            pf.ajax().addCallbackParam("cropStored", stored);
        }
    }

    /** Seams around the session map — the FacesContext is absent in unit tests. */
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

    private void audit(final String what, final Person subject, final int slot) {
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .actor(caller().auditActor())
                .target(AuditEventBuilder.TARGET_MEDIA, "profilePics/" + subject.getId().getValue())
                .message(what + " (slot " + slot + ") for " + subject.getPreferredName())
                .log();
    }

    private static void info(final String summary, final String detail) {
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_INFO, summary, detail);
    }

    private static void error(final String summary, final String detail) {
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, summary, detail);
    }
}
