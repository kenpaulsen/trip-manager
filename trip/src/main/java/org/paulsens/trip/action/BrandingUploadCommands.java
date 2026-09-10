package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
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
import org.primefaces.model.CroppedImage;

/**
 * Uploading an organization's own logo, favicon, link-preview image or page background, exposed as
 * {@code #{brandingUpload}}: the branding side of the shared upload+crop dialog ({@link PhotoUploadBean}
 * carries the contract and the token flow), the per-role processing, and the step that points the org's
 * setting at what was stored.
 *
 * <p><b>One bean, four roles.</b> The shared dialog ({@code WEB-INF/photoUploadDialog.xhtml}) has fixed
 * component ids and a single {@code widgetVar}, so a view can mount it exactly once -- four includes with
 * four {@code uploadBean} params would be four dialogs fighting over one id. The role is therefore chosen
 * when the dialog OPENS ({@link #startUpload}) and rides the session beside the pending-upload token, which
 * is also where the token itself lives and for the same reason (see the base class). Only the crop ASPECT
 * is role-specific, and the dialog takes it as a parameter ({@link #getCropAspect()}) rather than choosing
 * between square and free.
 *
 * <p><b>Where the URL lands.</b> A stored image is not a saved setting: the public URL is written into the
 * Appearance page's own PREVIEW ({@code BrandCommands.preview}) and the browser is sent back to the page,
 * so an upload previews exactly like a typed URL and the page's existing Save / Cancel decide its fate. The
 * dialog's forms post without the page's {@code orgId} parameter -- they are siblings of the main form, not
 * part of it -- so the preview is read and written through {@code BrandCommands.previewFor(orgId)}, which
 * keys on the organization rather than on the request being a render of its page.
 *
 * <p>Authorization is per call, never inherited from the dialog being open: every entry point re-asks
 * {@code OrgCommands.canManageOrg}, and the storage bean asks again before it writes.
 */
@Slf4j
@Named("brandingUpload")
@ApplicationScoped
public class BrandingUploadCommands extends PhotoUploadBean {

    /** What a pending branding upload is marked as, so no other dialog's token can be finalized here. */
    static final String PURPOSE = "branding";

    /** Session keys naming what the open dialog is uploading, and for whom. Scalars only. */
    static final String ROLE_KEY = "brandingUploadRole";
    static final String ORG_KEY = "brandingUploadOrg";

    @Inject
    private BrandingPhotos brandingPhotos;

    @Inject
    private BrandCommands brand;

    @Inject
    private OrgCommands orgs;

    /**
     * Opens the dialog for one role: remembers what is being uploaded and drops anything left parked from a
     * dialog that was abandoned. Refuses -- with a message and no state change -- for a caller who may not
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

    @Override
    protected String purpose() {
        return PURPOSE;
    }

    @Override
    protected String targetId() {
        final BrandingRole role = role();
        return role == null ? null : targetIdFor(orgId(), role);
    }

    @Override
    protected UploadGate uploadGate() {
        return (role() != null && orgs.canManageOrg(orgId())) ? UploadGate.allow()
                : UploadGate.deny("Not allowed: only this organization's administrators can change its "
                        + "appearance.");
    }

    @Override
    protected void confirm(final CroppedImage crop) {
        publishOutcome(applyCrop(crop));
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

    /** The stored versions of one role, newest first -- the page's "Previous images" disclosure. */
    public List<BrandingPhotos.Version> history(final String orgId, final String roleKey) {
        return brandingPhotos.history(orgId, roleKey);
    }

    /**
     * Processes the confirmed crop for the open role, stores it as a NEW version, and points the
     * organization's setting at it through the Appearance page's preview -- so the image shows immediately
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
        final Optional<PendingUploads.Pending> claimed = claim();
        if (claimed.isEmpty()) {
            return false;
        }
        final PendingUploads.Pending pending = claimed.get();
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
        finish(pending);
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
     * When the chosen area is smaller than the role's recommendation: growls it rather than refusing. The
     * image is still stored, at whatever size it really is.
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

    /** What the parked upload is FOR, so a token minted for one role or org cannot be spent on another. */
    private static String targetIdFor(final String orgId, final BrandingRole role) {
        return orgId + "#" + role.getKey();
    }

    private BrandingRole role() {
        return BrandingRole.of(sessionGet(ROLE_KEY)).orElse(null);
    }

    private String orgId() {
        return sessionGet(ORG_KEY);
    }

    private void audit(final String orgId, final BrandingRole role, final String key, final String what) {
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .actor(caller().auditActor())
                .target(AuditEventBuilder.TARGET_MEDIA, key)
                .message("Organization " + orgId + " " + role.getKey() + " image " + what)
                .log();
    }
}
