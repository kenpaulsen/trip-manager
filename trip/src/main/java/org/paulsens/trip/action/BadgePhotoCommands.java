package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.paulsens.trip.model.BadgeImage;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.ScopeUtil;
import org.primefaces.PrimeFaces;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.CroppedImage;

/**
 * Custom itinerary-badge pictures, exposed as {@code #{badgePhotos}}: the badge page's Add dialog backing
 * bean (honoring the shared upload+crop dialog's contract — see {@code photoUploadDialog.xhtml}) plus the
 * delete action and URL resolution its thumbnails and the badge itself use.
 *
 * <p>The inventory is {@link Trip#getBadgeImages()} — the uploaded object belongs to exactly ONE trip, and
 * saving the trip is what publishes it. Bytes go to the managed-media bucket under
 * {@code badgeImages/{tripId}/{version}.jpg} (CDN-served, public-by-URL like profile pictures); local mode
 * keeps them in a capped in-memory map behind the badge-photo servlet's GET, the same two-store split as
 * profile and chat photos and for the same reason.
 *
 * <p>Authorization is per-call and manager-shaped: a site admin or anyone holding the {@code tripMgr}
 * privilege for THE trip being edited — which is also why every custom image is visible to every manager of
 * that trip: the trip row carries the list, not the uploader.
 */
@Slf4j
@Named("badgePhotos")
@ApplicationScoped
public class BadgePhotoCommands {

    /** Session key holding the pending-upload token while the crop dialog is open (see PendingPhotoView). */
    static final String TOKEN_KEY = PendingUploads.SESSION_TOKEN_KEY;

    /** What a pending badge upload is marked as, so a profile/chat token cannot become a badge picture. */
    static final String PURPOSE = "badge";

    /** Session key remembering the uploaded FILENAME between upload and crop confirm — the label default. */
    static final String LABEL_DEFAULT_KEY = "badgeLabelDefault";

    /** The badge page's session attribute naming the selected image (a built-in name or one of our keys). */
    static final String SELECTED_KEY = "badgeImg";

    /** ViewScope key the Add dialog's optional name field writes (blank falls back to the filename). */
    static final String LABEL_VIEW_KEY = "badgeLabel";

    /** Object-key prefix in the managed-media bucket; doubles as the "is this one of ours" test. */
    static final String PREFIX = "badgeImages/";

    /**
     * What the badge page's square picture slot renders at ({@code width="1275"}, and the print CSS maps
     * pixels 1:1), so this is full print resolution — matching the page's own "ideally 1275px" instruction.
     */
    public static final int BADGE_SIZE = 1275;

    /** Keeps the dropdown and the trip row bounded; 20 pictures is already an implausibly indecisive trip. */
    static final int MAX_IMAGES = 20;

    /** Belt-and-braces size gate; the real transport cap is the Faces Servlet's multipart-config. */
    static final long MAX_UPLOAD_BYTES = 16L * 1024 * 1024;

    /** Same day-long cache as profile pictures — safe because a replacement is always a NEW key. */
    static final long CACHE_SECONDS = 86_400L;

    /** Local-store budget; badge renditions are ~300 KB, so this is around a hundred pictures. */
    static final long LOCAL_STORE_MAX_BYTES = 32L * 1024 * 1024;

    private final PhotoProcessor processor = new PhotoProcessor();
    private PendingUploads pendingUploads = PendingUploads.getPendingUploads();

    /** Local-mode object store: key -> jpeg bytes. Guarded by its own monitor; access is rare and brief. */
    private final Map<String, byte[]> localObjects = new LinkedHashMap<>();
    private long localBytes;
    /** Instance rather than the constant so a test can exercise eviction without 32 MB of fixtures. */
    private long localStoreMaxBytes = LOCAL_STORE_MAX_BYTES;

    @Inject
    private MediaCommands media;

    @Inject
    private TripCommands trips;

    @Inject
    private PrivilegeCommands priv;

    void setPendingUploadsForTest(final PendingUploads pendingUploads) {
        this.pendingUploads = pendingUploads;
    }

    void localStoreMaxBytesForTest(final long maxBytes) {
        this.localStoreMaxBytes = maxBytes;
    }

    /** Whether the signed-in caller may add or delete badge pictures on this trip. Pages gate on it too. */
    public boolean mayEdit(final Trip trip) {
        if (trip == null || trip.getId() == null) {
            return false;
        }
        final Caller caller = caller();
        if (!caller.isAuthenticated()) {
            return false;
        }
        return caller.isSiteAdmin() || priv.check("tripMgr", trip.getId(), caller.personId());
    }

    /**
     * {@code p:fileUpload} listener: validates, builds the browser-renderable preview, and parks the bytes
     * behind a token for the crop step. Errors surface as faces messages — this runs mid-postback.
     */
    public void handleUpload(final FileUploadEvent event) {
        final Trip trip = subjectTrip();
        if (!mayEdit(trip)) {
            error("Not allowed: only this trip's managers can add badge images.");
            return;
        }
        if (trip.getBadgeImages().size() >= MAX_IMAGES) {
            error("Image limit reached: this trip already has " + MAX_IMAGES
                    + " custom badge images. Delete one first.");
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
        final byte[] bytes = event.getFile().getContent();
        try {
            final PhotoProcessor.PreviewImage preview = processor.preview(bytes);
            final PendingUploads.Pending pending = pendingUploads.put(bytes, preview,
                    callerId(), PURPOSE, trip.getId());
            sessionPut(TOKEN_KEY, pending.token());
            sessionPut(LABEL_DEFAULT_KEY, baseName(event.getFile().getFileName()));
        } catch (final PhotoRejectedException ex) {
            error("Image rejected: " + ex.getMessage());
        }
    }

    /** Whether an upload is parked and claimable — what swaps the dialog from upload to cropper. */
    public boolean isUploadPending() {
        return pending().isPresent();
    }

    /**
     * The shared dialog's Save action: applies the cropper's rectangle ({@code viewScope.photoCrop}) and
     * reports the outcome in the {@code cropStored} ajax param, which is what closes the dialog on success.
     */
    public void confirmCrop() {
        publishOutcome(applyCrop(subjectTrip(), viewMap("photoCrop")));
    }

    /** "Use full photo": {@link #confirmCrop} with no rect, which crops the centered maximum square. */
    public void confirmFullPhoto() {
        publishOutcome(applyCrop(subjectTrip(), null));
    }

    /** Cancels the dialog: the parked bytes are dropped immediately rather than waiting out their TTL. */
    public void cancelUpload() {
        pending().ifPresent(p -> pendingUploads.consume(p.token()));
        sessionPut(TOKEN_KEY, null);
        sessionPut(LABEL_DEFAULT_KEY, null);
    }

    /**
     * Applies the confirmed crop: coordinates arrive in preview-pixel space, are scaled to full resolution,
     * forced square, cut from the ORIGINAL bytes, and scaled to exactly {@value #BADGE_SIZE} px. The object
     * is stored FIRST and the trip saved second — a failed save deletes the orphan object, whereas the other
     * order could publish a list entry whose bytes do not exist.
     *
     * <p>An undersized source is a warning, not a refusal: the image is upscaled and saved, and the growl
     * says (with the numbers) that it may print poorly — the manager holding a once-in-a-lifetime group
     * photo gets to decide.
     *
     * @return true when stored; false leaves the dialog open with a message explaining why.
     */
    public boolean applyCrop(final Trip trip, final CroppedImage crop) {
        if (!mayEdit(trip)) {
            error("Not allowed: only this trip's managers can add badge images.");
            return false;
        }
        final Optional<PendingUploads.Pending> found = pending();
        if (found.isEmpty()) {
            error("Upload expired: the uploaded image is no longer available. Upload it again.");
            return false;
        }
        final PendingUploads.Pending pending = found.get();
        if (!PURPOSE.equals(pending.purpose()) || !trip.getId().equals(pending.targetId())) {
            error("Upload mismatch: the uploaded image is no longer available. Upload it again.");
            return false;
        }
        if (trip.getBadgeImages().size() >= MAX_IMAGES) {
            error("Image limit reached: this trip already has " + MAX_IMAGES
                    + " custom badge images. Delete one first.");
            return false;
        }
        final PhotoProcessor.CropRect rect = scaledRect(crop, pending);
        final byte[] jpeg;
        try {
            jpeg = processor.processSquare(pending.original(), rect, BADGE_SIZE);
        } catch (final PhotoRejectedException ex) {
            error("Image rejected: " + ex.getMessage());
            return false;
        }
        warnIfSmall(rect, pending);
        final String key = PREFIX + trip.getId() + "/" + System.currentTimeMillis() + ".jpg";
        if (!storeBytes(key, jpeg)) {
            error("Not stored: the image could not be stored. Try again.");
            return false;
        }
        trip.getBadgeImages().add(new BadgeImage(key, label()));
        if (!saveTrip(trip)) {
            removeStored(key);
            trip.getBadgeImages().removeIf(img -> key.equals(img.getKey()));
            error("Not saved: the trip could not be updated with the new image. Try again.");
            return false;
        }
        pendingUploads.consume(pending.token());
        sessionPut(TOKEN_KEY, null);
        sessionPut(LABEL_DEFAULT_KEY, null);
        // Select the new picture, so the reload the dialog triggers shows it on the badge immediately.
        sessionPut(SELECTED_KEY, key);
        audit("Badge image added", trip, key);
        info("Badge image added.");
        return true;
    }

    /**
     * Permanently deletes one custom image: off the trip (the publish), then out of the store and the CDN.
     * If it was the badge currently being previewed, the selection falls back to the page default.
     *
     * @return true when deleted; false when refused or the key is not one of this trip's images.
     */
    public boolean delete(final Trip trip, final String key) {
        if (!mayEdit(trip)) {
            error("Not allowed: only this trip's managers can delete badge images.");
            return false;
        }
        final BadgeImage image = trip.getBadgeImage(key);
        if (image == null) {
            error("Not found: that badge image is no longer on this trip.");
            return false;
        }
        trip.getBadgeImages().remove(image);
        if (!saveTrip(trip)) {
            trip.getBadgeImages().add(image);
            error("Not saved: the trip could not be updated. The image was not deleted.");
            return false;
        }
        removeStored(key);
        if (key.equals(sessionGet(SELECTED_KEY))) {
            sessionPut(SELECTED_KEY, null);
        }
        audit("Badge image deleted", trip, key);
        info("Badge image deleted.");
        return true;
    }

    /**
     * The delete button's action: void (a boolean action outcome would be fed to navigation), reporting
     * instead through the {@code badgeDeleted} ajax param — the page reloads only on success, so a refusal's
     * growl actually gets seen. The trip comes from {@link #subjectTrip()}, not a page-supplied object: the
     * view holds only the id, and the delete's read-modify-write wants a fresh read anyway.
     */
    public void deleteFromUi(final String key) {
        publishParam("badgeDeleted", delete(subjectTrip(), key));
    }

    /** Whether the given dropdown value is one of THIS trip's custom images (vs a built-in name). */
    public boolean isCustom(final Trip trip, final String selected) {
        return trip != null && trip.getBadgeImage(selected) != null;
    }

    /** The URL a browser loads this key from: the CDN when configured, the badge-photo servlet locally. */
    public String url(final String key) {
        if (media.isUploadEnabled()) {
            return media.publicUrl(key);
        }
        final FacesContext ctx = FacesContext.getCurrentInstance();
        final String contextPath = ctx == null ? "" : ctx.getExternalContext().getRequestContextPath();
        return contextPath + "/badge-photos/" + key;
    }

    /** Local-mode read-back, for the badge-photo servlet's GET. Empty when remote or unknown. */
    public Optional<byte[]> localGet(final String key) {
        synchronized (localObjects) {
            return Optional.ofNullable(localObjects.get(key));
        }
    }

    /** The label to store: the Add dialog's name field when filled, else the uploaded filename. */
    private String label() {
        final String typed = viewMap(LABEL_VIEW_KEY);
        if (typed != null && !typed.isBlank()) {
            return typed.trim();
        }
        final String fallback = sessionGet(LABEL_DEFAULT_KEY);
        return (fallback == null || fallback.isBlank()) ? "Custom image" : fallback;
    }

    /** The uploaded filename with its path and extension stripped — the label nobody typed over. */
    static String baseName(final String fileName) {
        if (fileName == null) {
            return null;
        }
        String name = fileName;
        final int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        final int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.isBlank() ? null : name.trim();
    }

    /**
     * When the cut region must be upscaled to print size: growls (summary-only — details never render) AND
     * publishes the text as the {@code badgeWarn} ajax param. The dialog's after-save JS reloads the page —
     * which destroys the growl — so the page re-raises the warning from its {@code ?warn=} parameter.
     */
    private void warnIfSmall(final PhotoProcessor.CropRect rect, final PendingUploads.Pending pending) {
        final int side = PhotoProcessor.squareSideOf(rect, pending.fullWidth(), pending.fullHeight());
        if (side < BADGE_SIZE) {
            final String message = "Low resolution: the selected area is " + side + "×" + side
                    + " px, but the badge prints from " + BADGE_SIZE + "×" + BADGE_SIZE
                    + " px — it may look blurry when printed.";
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_WARN, message, "");
            publishParam("badgeWarn", message);
        }
    }

    /** Preview-space cropper coords scaled into the full-resolution space processSquare crops in. */
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

    private boolean storeBytes(final String key, final byte[] jpeg) {
        if (media.isUploadEnabled()) {
            return media.putObject(key, jpeg, "image/jpeg", CACHE_SECONDS, false);
        }
        synchronized (localObjects) {
            localBytes += jpeg.length;
            localObjects.put(key, jpeg);
            final var iterator = localObjects.entrySet().iterator();
            while (localBytes > localStoreMaxBytes && iterator.hasNext()) {
                localBytes -= iterator.next().getValue().length;
                iterator.remove();
            }
        }
        return true;
    }

    /** Deletes a stored object and (privacy, not correctness) invalidates its CDN path. */
    private void removeStored(final String key) {
        if (media.isUploadEnabled()) {
            media.deleteObject(key);
            media.invalidateCdn(List.of("/" + key));
        } else {
            synchronized (localObjects) {
                final byte[] removed = localObjects.remove(key);
                if (removed != null) {
                    localBytes -= removed.length;
                }
            }
        }
    }

    private String callerId() {
        final Person.Id id = caller().personId();
        return id == null ? null : id.getValue();
    }

    /** Seam: tests hand back a constructed {@link Caller} instead of the FacesContext-resolved one. */
    protected Caller caller() {
        return Caller.current();
    }

    /**
     * The trip being edited, resolved FRESH from the page's pinned id ({@code viewScope.theTripId}) on
     * every call: badge add/delete are read-modify-write saves of the whole trip, so the seed must come
     * from the store (never the near-cache, never a view-held snapshot — the session-scope policy bans
     * the latter outright). Null when the id is absent or resolves to nothing ({@code getTripForEdit}
     * answers a blank trip with a FRESH id, so a same-id probe is the existence test) — {@code mayEdit}
     * then refuses cleanly instead of letting an admin save a junk row. Tests override.
     */
    protected Trip subjectTrip() {
        final Object id = viewMap("theTripId");
        if (id == null) {
            return null;
        }
        final Trip fresh = trips.getTripForEdit(id.toString());
        return id.toString().equals(fresh.getId()) ? fresh : null;
    }

    /** Seam: dialog-local viewScope reads; tests override. */
    protected <T> T viewMap(final String key) {
        return ScopeUtil.getInstance().getViewMap(key);
    }

    /** Seam: the trip save; tests record instead of hitting the DAO chain. */
    protected boolean saveTrip(final Trip trip) {
        return trips.saveTrip(trip);
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

    private void audit(final String what, final Trip trip, final String key) {
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .actor(caller().auditActor())
                .target(AuditEventBuilder.TARGET_MEDIA, key)
                .message(what + " for trip " + trip.getId() + " (" + trip.getTitle() + ")")
                .log();
    }

    private static void info(final String summary) {
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_INFO, summary, "");
    }

    private static void error(final String summary) {
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, summary, "");
    }
}
