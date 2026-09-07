package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.util.ScopeUtil;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.primefaces.PrimeFaces;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.CroppedImage;

/**
 * Uploading an accommodation's photos, a room type's photos and a floor's plan, exposed as
 * {@code #{lodgingUpload}}: the shared upload+crop dialog's contract ({@code WEB-INF/photoUploadDialog.xhtml})
 * for the three KINDS, chosen when the dialog opens ({@link #startUpload}) and parked on the session beside
 * the pending-upload token -- the {@code BrandingUploadCommands} shape, for the same reason (one dialog per
 * view, fixed ids).
 *
 * <p><b>Where the bytes and the row go.</b> A stored image is a media-library row ({@link MediaItem}) in the
 * accommodation's slot {@code lodging-{accId}} with no org (hotels are global), so the media manager can
 * caption, hide and delete it; the accommodation then records the row's id in the right gallery list or as
 * the floor's plan. Bytes go to S3 through the media bucket when one is configured, else to this bean's
 * in-memory local store served by {@code /lodging-photos/*} (the badge-photo arrangement).
 *
 * <p>Authorization is per call: every entry point re-asks {@link LodgingCommands#canEditAccommodation}.
 */
@Slf4j
@Named("lodgingUpload")
@ApplicationScoped
public class LodgingUploadCommands {

    static final String TOKEN_KEY = PendingUploads.SESSION_TOKEN_KEY;
    static final String PURPOSE = "lodging";
    static final String KIND_KEY = "lodgingUploadKind";
    static final String ACC_KEY = "lodgingUploadAcc";
    static final String TARGET_KEY = "lodgingUploadTarget";
    static final String KIND_PHOTO = "accPhoto";
    static final String KIND_ROOM_TYPE = "roomTypePhoto";
    static final String KIND_FLOOR = "floorMap";
    static final String KEY_PREFIX = "lodging/";
    static final String SLOT_PREFIX = "lodging-";
    static final long MAX_UPLOAD_BYTES = 16L * 1024 * 1024;
    /** A phone photo of a floor plan is 5-12 MB; 2000 px on the long edge keeps room labels legible. */
    static final int FLOOR_MAX_WIDTH = 2000;
    static final int PHOTO_MAX_WIDTH = 1600;
    static final long MAX_STORED_BYTES = 1_800_000L;
    static final long LOCAL_STORE_MAX_BYTES = 32L * 1024 * 1024;
    static final long CACHE_SECONDS = 31_536_000L;

    private final PhotoProcessor processor = new PhotoProcessor();
    private PendingUploads pendingUploads = PendingUploads.getPendingUploads();
    private final Map<String, byte[]> localObjects = new LinkedHashMap<>();
    private long localBytes;
    /** Instance rather than the constant so a test can exercise eviction without 32 MB of fixtures. */
    private long localStoreMaxBytes = LOCAL_STORE_MAX_BYTES;
    /** Instance for the same reason: a one-byte budget is how a test reaches the rejection path. */
    private long storedMaxBytes = MAX_STORED_BYTES;

    @Inject
    private MediaCommands media;

    @Inject
    private LodgingCommands lodging;

    void setPendingUploadsForTest(final PendingUploads pendingUploads) {
        this.pendingUploads = pendingUploads;
    }

    void localStoreMaxBytesForTest(final long maxBytes) {
        this.localStoreMaxBytes = maxBytes;
    }

    void storedMaxBytesForTest(final long maxBytes) {
        this.storedMaxBytes = maxBytes;
    }

    void setCollaboratorsForTest(final MediaCommands mediaCommands, final LodgingCommands lodgingCommands) {
        this.media = mediaCommands;
        this.lodging = lodgingCommands;
    }

    /** The media slot every image of one accommodation lives in. */
    public static String slotFor(final String accId) {
        return SLOT_PREFIX + accId;
    }

    /** Whether a media slot is a lodging one -- the media manager's "Include lodging photos" toggle. */
    public static boolean isLodgingSlot(final String slot) {
        return slot != null && slot.startsWith(SLOT_PREFIX);
    }

    /**
     * Opens the dialog for one kind: {@code accPhoto} (target unused), {@code roomTypePhoto} (target = the
     * room type id) or {@code floorMap} (target = the floor). Refuses, with a message and no state change, for
     * a caller who may not edit the accommodation.
     */
    public void startUpload(final String kind, final String accId, final String target) {
        cancelUpload();
        sessionPut(KIND_KEY, null);
        sessionPut(ACC_KEY, null);
        sessionPut(TARGET_KEY, null);
        if (!KIND_PHOTO.equals(kind) && !KIND_ROOM_TYPE.equals(kind) && !KIND_FLOOR.equals(kind)) {
            error("Unknown upload kind.");
            return;
        }
        if (!lodging.canEditAccommodation(accId)) {
            error("Not allowed: you do not manage this accommodation.");
            return;
        }
        sessionPut(KIND_KEY, kind);
        sessionPut(ACC_KEY, accId);
        sessionPut(TARGET_KEY, target);
    }

    /** {@code p:fileUpload} listener: validates, previews, parks the bytes behind a token for the crop step. */
    public void handleUpload(final FileUploadEvent event) {
        final String accId = accId();
        if (kind() == null || !lodging.canEditAccommodation(accId)) {
            error("Not allowed: you do not manage this accommodation.");
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
            sessionPut(TOKEN_KEY, pendingUploads.put(bytes, preview, callerId(), PURPOSE, targetId()).token());
        } catch (final PhotoRejectedException ex) {
            error("Image rejected: " + ex.getMessage());
        }
    }

    public boolean isUploadPending() {
        return pending().isPresent();
    }

    public String getDialogHeader() {
        final String kind = kind();
        if (KIND_FLOOR.equals(kind)) {
            return "Floor plan" + (target() == null ? "" : " for floor " + target());
        }
        return KIND_ROOM_TYPE.equals(kind) ? "Room type photo" : "Accommodation photo";
    }

    /** Plans and property photos crop free; the dialog's "Use full photo" is the normal path for a plan. */
    public String getCropAspect() {
        return "";
    }

    public void confirmCrop() {
        publishOutcome(applyCrop(viewMap("photoCrop")));
    }

    public void confirmFullPhoto() {
        publishOutcome(applyCrop(null));
    }

    public void cancelUpload() {
        pending().ifPresent(this::drop);
        sessionPut(TOKEN_KEY, null);
    }

    /**
     * Processes the confirmed crop, stores the bytes, records the media row, and points the accommodation at
     * it (gallery, room-type gallery, or floor plan).
     *
     * @return true when stored; false leaves the dialog open with a message explaining why.
     */
    public boolean applyCrop(final CroppedImage crop) {
        final String kind = kind();
        final String accId = accId();
        if (kind == null || !lodging.canEditAccommodation(accId)) {
            error("Not allowed: you do not manage this accommodation.");
            return false;
        }
        final Optional<PendingUploads.Pending> found = pending();
        if (found.isEmpty() || !PURPOSE.equals(found.get().purpose())
                || !targetId().equals(found.get().targetId())) {
            error("Upload expired: the uploaded image is no longer available. Upload it again.");
            return false;
        }
        final PendingUploads.Pending pending = found.get();
        final byte[] stored;
        try {
            stored = processor.cropToBoundedJpeg(pending.original(), scaledRect(crop, pending), 0,
                    KIND_FLOOR.equals(kind) ? FLOOR_MAX_WIDTH : PHOTO_MAX_WIDTH, storedMaxBytes);
        } catch (final PhotoRejectedException ex) {
            error("Image rejected: " + ex.getMessage());
            return false;
        }
        final String key = KEY_PREFIX + accId + "/" + UUID.randomUUID() + ".jpg";
        if (!storeBytes(key, stored)) {
            error("Not stored: the image could not be stored. Try again.");
            return false;
        }
        final MediaItem item = new MediaItem(UUID.randomUUID().toString(), key, title(kind), null, "image/jpeg",
                stored.length, slotFor(accId), (int) (System.currentTimeMillis() / 1000L), LocalDateTime.now(),
                callerId(), null, null, null);
        if (!saveRow(item)) {
            error("Not stored: the media row could not be saved.");
            return false;
        }
        final boolean linked = KIND_FLOOR.equals(kind) ? lodging.setFloorMap(accId, target(), item.getId())
                : lodging.addPhoto(accId, KIND_ROOM_TYPE.equals(kind) ? target() : null, item.getId());
        if (!linked) {
            return false;
        }
        pendingUploads.consume(pending.token());
        sessionPut(TOKEN_KEY, null);
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .actor(caller().auditActor())
                .target(AuditEventBuilder.TARGET_MEDIA, item.getId())
                .message("Lodging " + kind + " uploaded for accommodation " + accId + " (" + key + ")")
                .log();
        info(getDialogHeader() + " uploaded.");
        return true;
    }

    /** The URL a browser loads this key from: the media CDN when configured, this app's own GET locally. */
    public String urlFor(final String key) {
        if (key == null) {
            return "";
        }
        return media.isUploadEnabled() ? media.publicUrl(key) : contextPath() + "/lodging-photos/" + key;
    }

    /** Local-mode read-back, for the lodging-photo servlet's GET. Empty when remote or unknown. */
    public Optional<byte[]> localGet(final String key) {
        synchronized (localObjects) {
            return Optional.ofNullable(localObjects.get(key));
        }
    }

    /** Seam: the media-row write; a test overrides it to fail. */
    protected boolean saveRow(final MediaItem item) {
        try {
            return DAO.getInstance().saveMedia(item);
        } catch (final RuntimeException ex) {
            log.error("Unable to save lodging media row {}", item.getS3Key(), ex);
            return false;
        }
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

    private static String title(final String kind) {
        if (KIND_FLOOR.equals(kind)) {
            return "Floor plan";
        }
        return KIND_ROOM_TYPE.equals(kind) ? "Room type photo" : "Accommodation photo";
    }

    private static PhotoProcessor.CropRect scaledRect(final CroppedImage crop,
            final PendingUploads.Pending pending) {
        return crop == null ? null
                : pending.scaleToFull(crop.getLeft(), crop.getTop(), crop.getWidth(), crop.getHeight());
    }

    /** What the parked upload is FOR, so a token minted for one hotel or kind cannot be spent on another. */
    private String targetId() {
        return accId() + "#" + kind() + "#" + (target() == null ? "" : target());
    }

    private void drop(final PendingUploads.Pending pending) {
        pendingUploads.consume(pending.token());
    }

    private Optional<PendingUploads.Pending> pending() {
        final String token = sessionGet(TOKEN_KEY);
        return token == null ? Optional.empty() : pendingUploads.peek(token, callerId());
    }

    private String kind() {
        return sessionGet(KIND_KEY);
    }

    private String accId() {
        return sessionGet(ACC_KEY);
    }

    private String target() {
        return sessionGet(TARGET_KEY);
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
        if (FacesContext.getCurrentInstance() == null) {
            return;
        }
        final PrimeFaces pf = PrimeFaces.current();
        if (pf.isAjaxRequest()) {
            pf.ajax().addCallbackParam("cropStored", stored);
        }
    }

    protected String contextPath() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        return ctx == null ? "" : ctx.getExternalContext().getRequestContextPath();
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

    private static void info(final String summary) {
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_INFO, summary, "");
    }

    private static void error(final String summary) {
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, summary, "");
    }
}
