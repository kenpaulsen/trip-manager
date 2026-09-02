package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
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
 * Uploading an organization's own logo, favicon, link-preview image or page background, exposed as
 * {@code #{brandingUpload}}: the shared upload+crop dialog's contract, the per-role processing, and the step
 * that points the org's setting at what was stored.
 *
 * <p><b>One bean, four roles.</b> The shared dialog ({@code WEB-INF/photoUploadDialog.xhtml}) has fixed
 * component ids and a single {@code widgetVar}, so a view can mount it exactly once — four includes with
 * four {@code uploadBean} params would be four dialogs fighting over one id. The role is therefore chosen
 * when the dialog OPENS ({@link #startUpload}) and rides the session beside the pending-upload token, which
 * is also where {@code ProfilePhotoCommands} keeps its own dialog state and for the same reason: the
 * cropper's image is streamed OUTSIDE the view, where viewScope answers null. Everything the dialog asks of
 * the bean — {@link #handleUpload}, {@link #isUploadPending()}, {@link #confirmCrop()},
 * {@link #confirmFullPhoto()}, {@link #cancelUpload()} — is unchanged; only the crop ASPECT is new, and the
 * dialog now takes it as a parameter ({@link #getCropAspect()}) rather than choosing between square and free.
 *
 * <p><b>Where the URL lands.</b> A stored image is not a saved setting: the public URL is written into the
 * Appearance page's own PREVIEW ({@code BrandCommands.preview}) and the browser is sent back to the page,
 * so an upload previews exactly like a typed URL and the page's existing Save / Cancel decide its fate. The
 * dialog's forms post without the page's {@code orgId} parameter — they are siblings of the main form, not
 * part of it — so the preview is read and written through {@code BrandCommands.previewFor(orgId)}, which
 * keys on the organization rather than on the request being a render of its page.
 *
 * <p>Authorization is per call, never inherited from the dialog being open: every entry point re-asks
 * {@code OrgCommands.canManageOrg}, and the storage bean asks again before it writes.
 */
@Slf4j
@Named("brandingUpload")
@ApplicationScoped
public class BrandingUploadCommands {

    /** Session key holding the pending-upload token while the crop dialog is open (see PendingPhotoView). */
    static final String TOKEN_KEY = PendingUploads.SESSION_TOKEN_KEY;

    /** What a pending branding upload is marked as, so no other dialog's token can be finalized here. */
    static final String PURPOSE = "branding";

    /** Session keys naming what the open dialog is uploading, and for whom. Scalars only. */
    static final String ROLE_KEY = "brandingUploadRole";
    static final String ORG_KEY = "brandingUploadOrg";

    /** Belt-and-braces size gate; the real transport cap is the Faces Servlet's multipart-config. */
    static final long MAX_UPLOAD_BYTES = 16L * 1024 * 1024;

    private final PhotoProcessor processor = new PhotoProcessor();
    private PendingUploads pendingUploads = PendingUploads.getPendingUploads();

    @Inject
    private BrandingPhotos brandingPhotos;

    @Inject
    private BrandCommands brand;

    @Inject
    private OrgCommands orgs;

    void setPendingUploadsForTest(final PendingUploads pendingUploads) {
        this.pendingUploads = pendingUploads;
    }

    /**
     * Opens the dialog for one role: remembers what is being uploaded and drops anything left parked from a
     * dialog that was abandoned. Refuses — with a message and no state change — for a caller who may not
     * manage the organization, so the dialog cannot be opened into someone else's site.
     */
    public void startUpload(final String orgId, final String roleKey) {
        cancelUpload();
        final Optional<BrandingRole> role = BrandingRole.of(roleKey);
        if (role.isEmpty()) {
            error("Unknown image: that is not one of this site's images.");
            return;
        }
        if (!orgs.canManageOrg(orgId)) {
            error("Not allowed: only this organization's administrators can change its appearance.");
            return;
        }
        sessionPut(ORG_KEY, orgId);
        sessionPut(ROLE_KEY, role.get().getKey());
    }

    /**
     * {@code p:fileUpload} listener: validates, builds the browser-renderable preview, and parks the bytes
     * behind a token for the crop step. Errors surface as faces messages — this runs mid-postback.
     */
    public void handleUpload(final FileUploadEvent event) {
        final BrandingRole role = role();
        final String orgId = orgId();
        if (role == null || !orgs.canManageOrg(orgId)) {
            error("Not allowed: only this organization's administrators can change its appearance.");
            return;
        }
        if (event.getFile() == null || event.getFile().getSize() == 0) {
            error("No image: no image was included in the upload.");
            return;
        }
        if (event.getFile().getSize() > MAX_UPLOAD_BYTES) {
            error("Too large: images can be at most " + (MAX_UPLOAD_BYTES / (1024 * 1024)) + " MB.");
            return;
        }
        try {
            final byte[] bytes = event.getFile().getContent();
            final PhotoProcessor.PreviewImage preview = processor.preview(bytes);
            sessionPut(TOKEN_KEY, pendingUploads.put(bytes, preview, callerId(), PURPOSE,
                    targetId(orgId, role)).token());
        } catch (final PhotoRejectedException ex) {
            error("Image rejected: " + ex.getMessage());
        }
    }

    /** Whether an upload is parked and claimable — what swaps the dialog from upload to cropper. */
    public boolean isUploadPending() {
        return pending().isPresent();
    }

    /** The dialog's title while this role is being uploaded. */
    public String getDialogHeader() {
        final BrandingRole role = role();
        return role == null ? "Upload Image" : role.getLabel();
    }

    /** The dialog's crop ratio for this role: blank crops free, a number locks the shape. */
    public String getCropAspect() {
        final BrandingRole role = role();
        return role == null ? "" : role.getCropAspect();
    }

    /** The role the open dialog is uploading, for the page's own wording. Blank when nothing is open. */
    public String getRoleKey() {
        final BrandingRole role = role();
        return role == null ? "" : role.getKey();
    }

    /** The stored versions of one role, newest first — the page's "Previous images" disclosure. */
    public List<BrandingPhotos.Version> history(final String orgId, final String roleKey) {
        return brandingPhotos.history(orgId, roleKey);
    }

    /**
     * The shared dialog's Save action: applies the cropper's rectangle ({@code viewScope.photoCrop}) and
     * reports the outcome in the {@code cropStored} ajax param, which is what closes the dialog on success.
     */
    public void confirmCrop() {
        publishOutcome(applyCrop(viewMap("photoCrop")));
    }

    /** "Use full photo": {@link #confirmCrop} with no rect, which takes the largest well-shaped region. */
    public void confirmFullPhoto() {
        publishOutcome(applyCrop(null));
    }

    /** Cancels the dialog: the parked bytes are dropped immediately rather than waiting out their TTL. */
    public void cancelUpload() {
        pending().ifPresent(this::drop);
        sessionPut(TOKEN_KEY, null);
    }

    /**
     * Processes the confirmed crop for the open role, stores it as a NEW version, and points the
     * organization's setting at it through the Appearance page's preview — so the image shows immediately
     * and the page's own Save is still what commits it.
     *
     * @return true when stored; false leaves the dialog open with a message explaining why.
     */
    public boolean applyCrop(final CroppedImage crop) {
        final BrandingRole role = role();
        final String orgId = orgId();
        if (role == null || !orgs.canManageOrg(orgId)) {
            error("Not allowed: only this organization's administrators can change its appearance.");
            return false;
        }
        final Optional<PendingUploads.Pending> found = pending();
        if (found.isEmpty() || !PURPOSE.equals(found.get().purpose())
                || !targetId(orgId, role).equals(found.get().targetId())) {
            error("Upload expired: the uploaded image is no longer available. Upload it again.");
            return false;
        }
        final PendingUploads.Pending pending = found.get();
        final PhotoProcessor.CropRect rect = scaledRect(crop, pending);
        final byte[] stored;
        try {
            stored = render(role, pending.original(), rect);
        } catch (final PhotoRejectedException ex) {
            error("Image rejected: " + ex.getMessage());
            return false;
        }
        final String key = brandingPhotos.store(orgId, role.getKey(), stored, role.getContentType(),
                role.getExtension());
        if (key == null) {
            error("Not stored: the image could not be stored. Try again.");
            return false;
        }
        warnIfSmall(role, rect, pending);
        applyToPreview(orgId, role, brandingPhotos.urlFor(key));
        pendingUploads.consume(pending.token());
        sessionPut(TOKEN_KEY, null);
        audit(orgId, role, key, "uploaded");
        info(role.getLabel() + " uploaded. It is not saved yet: check it, then use Save.");
        return true;
    }

    /**
     * Recovery: points the organization's setting back at a version it used before, again through the
     * page's preview rather than straight into a row. The KEY carries the organization and the role, so a
     * key belonging to another tenant simply does not match and is refused.
     *
     * @return true when the setting was repointed; false with a message otherwise.
     */
    public boolean useVersion(final String orgId, final String key) {
        final BrandingPhotos.BrandingKey parsed = BrandingPhotos.parse(key);
        if (parsed == null || orgId == null || !orgId.equals(parsed.orgId())) {
            error("Not found: that image does not belong to this organization.");
            return false;
        }
        final Optional<BrandingRole> role = BrandingRole.of(parsed.role());
        if (role.isEmpty() || !orgs.canManageOrg(orgId)) {
            error("Not allowed: only this organization's administrators can change its appearance.");
            return false;
        }
        applyToPreview(orgId, role.get(), brandingPhotos.urlFor(key));
        audit(orgId, role.get(), key, "restored");
        info(role.get().getLabel() + " restored. It is not saved yet: check it, then use Save.");
        return true;
    }

    /** Each role's own pipeline; the whole reason a shared "crop and store" would not have done. */
    private byte[] render(final BrandingRole role, final byte[] original,
            final PhotoProcessor.CropRect rect) {
        return switch (role) {
            case LOGO -> processor.cropToPng(original, rect, role.getMaxWidth(), role.getMaxHeight());
            case FAVICON -> processor.cropToSquarePng(original, rect, role.getMaxWidth());
            case OG_IMAGE, BACKGROUND -> processor.cropToBoundedJpeg(original, rect, role.getAspect(),
                    role.getMaxWidth(), role.getMaxBytes());
        };
    }

    /**
     * Writes the stored URL into the Appearance page's unsaved values, on top of whatever else the admin has
     * in flight, and leaves the page's Save / Cancel to decide. Choosing a background IMAGE also moves the
     * background chooser to Image, or the page would come back showing a colour and hiding the URL it just
     * set.
     */
    private void applyToPreview(final String orgId, final BrandingRole role, final String url) {
        final Map<String, String> values = brand.appearanceEdit(orgId);
        values.put(role.getSetting().getName(), url);
        if (role == BrandingRole.BACKGROUND) {
            values.put(BrandCommands.BG_MODE_KEY, BrandCommands.BG_MODE_IMAGE);
        }
        brand.preview(orgId, values, null);
        // The dialog's forms are siblings of the page's, so nothing they update can redraw the theme link or
        // the background: the browser goes back to the page, which the server renders with the preview on.
        publishParam("brandUrl", contextPath() + brand.appearanceUrl(orgId));
    }

    /**
     * When the chosen area is smaller than the role's recommendation: growls it (summary only — details
     * never render) rather than refusing. The image is still stored, at whatever size it really is.
     */
    private void warnIfSmall(final BrandingRole role, final PhotoProcessor.CropRect rect,
            final PendingUploads.Pending pending) {
        final PhotoProcessor.CropRect cut = PhotoProcessor.cutRegion(rect, pending.fullWidth(),
                pending.fullHeight(), role.getAspect());
        final int longSide = Math.max(cut.width(), cut.height());
        if (longSide < role.getRecommendedLongSide()) {
            warn("Low resolution: the selected area is " + cut.width() + "×" + cut.height()
                    + " px, and the " + role.getLabel().toLowerCase(Locale.ROOT)
                    + " is recommended at " + role.getRecommendedLongSide()
                    + " px or more on its longest side. It was stored anyway and may look soft.");
        }
    }

    /** Preview-space cropper coords scaled into the full-resolution space the crop is applied in. */
    private static PhotoProcessor.CropRect scaledRect(final CroppedImage crop,
            final PendingUploads.Pending pending) {
        return crop == null ? null
                : pending.scaleToFull(crop.getLeft(), crop.getTop(), crop.getWidth(), crop.getHeight());
    }

    /** What the parked upload is FOR, so a token minted for one role or org cannot be spent on another. */
    private static String targetId(final String orgId, final BrandingRole role) {
        return orgId + "#" + role.getKey();
    }

    private void drop(final PendingUploads.Pending pending) {
        pendingUploads.consume(pending.token());
    }

    private Optional<PendingUploads.Pending> pending() {
        final String token = sessionGet(TOKEN_KEY);
        return token == null ? Optional.empty() : pendingUploads.peek(token, callerId());
    }

    private BrandingRole role() {
        return BrandingRole.of(sessionGet(ROLE_KEY)).orElse(null);
    }

    private String orgId() {
        return sessionGet(ORG_KEY);
    }

    private String callerId() {
        final Person.Id id = caller().personId();
        return id == null ? null : id.getValue();
    }

    /** Seam: tests hand back a constructed {@link Caller} instead of the FacesContext-resolved one. */
    protected Caller caller() {
        return Caller.current();
    }

    /** Seam: dialog-local viewScope reads; tests override. */
    protected <T> T viewMap(final String key) {
        return ScopeUtil.getInstance().getViewMap(key);
    }

    /** Seam: hands the outcome to the dialog's oncomplete JS; a test records it instead. */
    protected void publishOutcome(final boolean stored) {
        publishParam("cropStored", stored);
    }

    /** Seam: a single named ajax callback param; a test records it instead. */
    protected void publishParam(final String name, final Object value) {
        if (FacesContext.getCurrentInstance() == null) {
            return;
        }
        final PrimeFaces pf = PrimeFaces.current();
        if (pf.isAjaxRequest()) {
            pf.ajax().addCallbackParam(name, value);
        }
    }

    /** Seam: the deployment's context path, which the dialog's JS prefixes onto the page URL. */
    protected String contextPath() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        return ctx == null ? "" : ctx.getExternalContext().getRequestContextPath();
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

    private void audit(final String orgId, final BrandingRole role, final String key, final String what) {
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .actor(caller().auditActor())
                .target(AuditEventBuilder.TARGET_MEDIA, key)
                .message("Organization " + orgId + " " + role.getKey() + " image " + what)
                .log();
    }

    private static void info(final String summary) {
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_INFO, summary, "");
    }

    private static void warn(final String summary) {
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_WARN, summary, "");
    }

    private static void error(final String summary) {
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, summary, "");
    }
}
