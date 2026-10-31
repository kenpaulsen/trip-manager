package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.media.ImageFormat;
import org.paulsens.trip.media.LocalObjectStore;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.media.ProcessedPhoto;
import org.paulsens.trip.media.QuarterTurn;
import org.paulsens.trip.media.RotationGuard;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.ScopeUtil;
import org.primefaces.event.FileUploadEvent;

import static org.paulsens.trip.action.PageFeedback.info;
import static org.paulsens.trip.action.PageFeedback.refuse;

/**
 * The receipts of a trip's expense report ({@code docs/expense-report.md}, "Receipts"): images and PDFs stored
 * in the media bucket under {@code receipts/{tripId}/}, one hidden {@link MediaItem} row each in slot
 * {@code receipts-{tripId}}, linked by id from the report or one of its lines ({@link ExpenseCommands} owns the
 * links). Receipts are financial documents, so they are never served by the media CDN: every URL is this app's
 * own {@code /expense-receipts/{id}}, whose servlet checks the report's view gate first.
 *
 * <p>Not a {@link PhotoUploadBean}: that base crops, and its upload rejects anything that is not an image. A
 * receipt is stored as it came (an image gets the photo pipeline's two renditions, so HEIC becomes JPEG and the
 * page has a thumbnail; a PDF is one object), the moment it is uploaded.
 *
 * <p>A trip delete leaves the receipts, like the report itself: they are the record of money that was spent
 * ({@code docs/trip-delete.md}, "Financial history is never destroyed").
 *
 * <p>The local-mode store is STATIC, unlike the photo beans': {@link ExpenseCommands} and the servlet reach
 * this class through fresh instances as well as the CDI one, and all of them must see the same bytes.
 */
@Slf4j
@Named("expenseReceipts")
@ApplicationScoped
public class ExpenseReceipts {
    /** The servlet path every receipt URL starts with; never ends in {@code .pdf} (PdfRedirectFilter). */
    public static final String SERVLET = "/expense-receipts/";
    /** Appended to a receipt's URL for its small rendition. */
    public static final String THUMB = "/thumb";
    /** The viewScope key naming the line the receipt dialog is for; blank = the report itself. */
    public static final String LINE_KEY = "receiptLineId";
    static final String KEY_PREFIX = "receipts/";
    static final String SLOT_PREFIX = "receipts-";
    static final String PDF = "application/pdf";
    static final long MAX_BYTES = PhotoUploadBean.MAX_UPLOAD_BYTES;
    /** The longest side, in pixels, of a stored receipt image: legible on any screen, a fraction of a phone photo. */
    static final int MAX_IMAGE_SIDE = 1280;
    static final long LOCAL_STORE_MAX_BYTES = 64L * 1024 * 1024;
    /** The object never changes under its key; the servlet still answers {@code private, no-store}. */
    static final long CACHE_SECONDS = 31_536_000L;
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final LocalObjectStore LOCAL = new LocalObjectStore(LOCAL_STORE_MAX_BYTES);

    private final Supplier<Caller> callerSource;
    private MediaCommands media = new MediaCommands();
    private final PhotoProcessor processor = new PhotoProcessor();

    /** What one upload came to: the stored receipt's id, or why it was refused. */
    public record Outcome(String mediaId, String problem) {
        public boolean ok() {
            return mediaId != null;
        }
    }

    /** One receipt's bytes as the servlet streams them. */
    public record Served(byte[] bytes, String contentType) {
    }

    public ExpenseReceipts() {
        this(Caller::current);
    }

    public ExpenseReceipts(final Supplier<Caller> callerSource) {
        this.callerSource = callerSource;
    }

    void setMediaForTest(final MediaCommands mediaCommands) {
        this.media = mediaCommands;
    }

    static void localStoreMaxBytesForTest(final long maxBytes) {
        LOCAL.maxBytes(maxBytes);
    }

    /** The media slot every receipt of one trip's report lives in. */
    public static String slotFor(final String tripId) {
        return SLOT_PREFIX + tripId;
    }

    public static boolean isReceiptSlot(final String slot) {
        return slot != null && slot.startsWith(SLOT_PREFIX);
    }

    /** The trip a receipt slot belongs to; null for any other slot. */
    public static String tripOfSlot(final String slot) {
        return isReceiptSlot(slot) ? slot.substring(SLOT_PREFIX.length()) : null;
    }

    /** The app path that serves a receipt (context-relative; pages prefix the context path). */
    public static String path(final String mediaId) {
        return SERVLET + mediaId;
    }

    private ExpenseCommands expenses() {
        return new ExpenseCommands(callerSource);
    }

    // ================================================================== upload

    /** The receipt dialog's {@code p:fileUpload} listener; the dialog's line comes from the view. */
    public void handleUpload(final FileUploadEvent event) {
        final ScopeUtil scope = ScopeUtil.getInstance();
        final String tripId = scope.getViewMap("theTripId");
        final String lineId = scope.getViewMap(LINE_KEY);
        final byte[] bytes = (event.getFile() == null) ? null : event.getFile().getContent();
        final Outcome outcome = store(tripId, lineId, bytes,
                (event.getFile() == null) ? null : event.getFile().getFileName());
        if (outcome.ok()) {
            info("Receipt uploaded.");
        } else {
            refuse(outcome.problem());
        }
    }

    /**
     * Stores one receipt and links it to {@code lineId} (blank = the report itself): checks the edit gate and
     * the size, sniffs the bytes (never the declared type), writes the object(s) and the media row, then links
     * it with the report's versioned put, undoing the files and the row when the link is refused.
     */
    public Outcome store(final String tripId, final String lineId, final byte[] bytes, final String fileName) {
        final ExpenseCommands expenses = expenses();
        if (!expenses.canEditExpenses(tripId)) {
            return new Outcome(null, "Not allowed: only the trip's managers and finance admins can add receipts.");
        }
        if (bytes == null || bytes.length == 0) {
            return new Outcome(null, "No file was received.");
        }
        if (bytes.length > MAX_BYTES) {
            return new Outcome(null, "A receipt is at most " + (MAX_BYTES / (1024 * 1024)) + " MB.");
        }
        final Rendered rendered;
        try {
            rendered = render(bytes);
        } catch (final PhotoRejectedException ex) {
            return new Outcome(null, ex.getMessage());
        }
        if (rendered == null) {
            return new Outcome(null, "That file is not a receipt this site can keep: use a JPG, PNG, GIF, HEIC or "
                    + "PDF.");
        }
        final String id = UUID.randomUUID().toString();
        final String key = KEY_PREFIX + tripId + "/" + id + "." + rendered.extension();
        final String smallKey = (rendered.small() == null) ? null
                : KEY_PREFIX + tripId + "/" + id + "-small." + rendered.smallExtension();
        if (!storeBytes(key, rendered.full(), rendered.contentType())
                || (smallKey != null && !storeBytes(smallKey, rendered.small(), typeOf(smallKey)))) {
            deleteObjects(key, smallKey);
            return new Outcome(null, "The receipt could not be stored. Try again.");
        }
        final Trip trip = DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
        final MediaItem item = new MediaItem(id, key, title(fileName), null, rendered.contentType(),
                rendered.full().length, slotFor(tripId), 0, LocalDateTime.now(), uploader(), smallKey, Boolean.TRUE,
                (trip == null) ? null : trip.getOrgId());
        if (!saveRow(item)) {
            deleteObjects(key, smallKey);
            return new Outcome(null, "The receipt could not be stored. Try again.");
        }
        final List<String> problems = new ArrayList<>();
        if (!expenses.attachReceipt(tripId, lineId, id, problems::add)) {
            deleteRow(item);
            return new Outcome(null, problems.isEmpty() ? "The receipt was not added." : problems.get(0));
        }
        audit(item.getId(), "Expense receipt uploaded for trip " + tripId + " (" + key + ")");
        return new Outcome(id, null);
    }

    /**
     * The bytes to store: an image scaled down to {@link #MAX_IMAGE_SIDE} on its longest side (aspect kept, never
     * cropped) with a small rendition for the thumbnail; a PDF as it came.
     */
    private record Rendered(byte[] full, String contentType, String extension, byte[] small, String smallExtension) {
    }

    /** Null when the bytes are neither a supported image nor a PDF. */
    private Rendered render(final byte[] bytes) {
        if (isPdf(bytes)) {
            return new Rendered(bytes, PDF, "pdf", null, null);
        }
        if (ImageFormat.detect(bytes).isEmpty()) {
            return null;
        }
        final ProcessedPhoto photo = processor.processWithin(bytes, MAX_IMAGE_SIDE);
        return new Rendered(photo.fullBytes(), photo.fullContentType(), photo.fullExtension(),
                photo.smallIsFull() ? null : photo.smallBytes(), photo.smallExtension());
    }

    static boolean isPdf(final byte[] bytes) {
        return bytes != null && bytes.length >= PDF_MAGIC.length
                && Arrays.equals(Arrays.copyOf(bytes, PDF_MAGIC.length), PDF_MAGIC);
    }

    /** A plain name to show for the receipt: the uploaded file's base name, else "Receipt". */
    static String title(final String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "Receipt";
        }
        final String base = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1)
                .trim();
        if (base.isEmpty()) {
            return "Receipt";
        }
        return (base.length() > 120) ? base.substring(0, 120) : base;
    }

    // ================================================================== delete

    /** The receipt dialog's Delete: unlinks it from the report, then removes its files and row; refusals growl. */
    public boolean deleteReceipt(final String tripId, final String mediaId) {
        final String problem = remove(tripId, mediaId);
        if (problem != null) {
            return refuse(problem);
        }
        info("Receipt deleted.");
        return true;
    }

    /** Unlinks and deletes one receipt of this trip's report; null on success, else why not. */
    public String remove(final String tripId, final String mediaId) {
        final ExpenseCommands expenses = expenses();
        if (!expenses.canEditExpenses(tripId)) {
            return "Not allowed: only the trip's managers and finance admins can delete receipts.";
        }
        final MediaItem item = (mediaId == null) ? null : DAO.getInstance().getMedia(mediaId, Cached.NO).orElse(null);
        if (item == null || !slotFor(tripId).equals(item.getSlot())) {
            return "That receipt is not on this report.";
        }
        final List<String> problems = new ArrayList<>();
        if (!expenses.detachReceipt(tripId, mediaId, problems::add)) {
            return problems.isEmpty() ? "The receipt was not deleted." : problems.get(0);
        }
        deleteRow(item);
        audit(item.getId(), "Expense receipt deleted from trip " + tripId + " (" + item.getS3Key() + ")");
        return null;
    }

    /** Deletes these receipts' files and rows (a deleted expense's); ids that are not receipts are left alone. */
    void purge(final List<String> mediaIds) {
        for (final String id : mediaIds) {
            final MediaItem item = DAO.getInstance().getMedia(id, Cached.NO).orElse(null);
            if (item != null && isReceiptSlot(item.getSlot())) {
                deleteRow(item);
            }
        }
    }

    private void deleteRow(final MediaItem item) {
        deleteObjects(item.getS3Key(), item.getSmallKey());
        try {
            DAO.getInstance().deleteMedia(item.getId());
        } catch (final RuntimeException ex) {
            log.error("Receipt objects deleted, but their media row was not: " + item.getId(), ex);
        }
    }

    // ================================================================== rotate

    /** What a rotation came to: {@code item} is the rewritten row on {@link #OK}, else {@code problem} says why. */
    public record RotateOutcome(String code, String problem, MediaItem item) {
        public static final String OK = "ok";
        public static final String NOT_ALLOWED = "not_allowed";
        public static final String NOT_ON_REPORT = "not_on_report";
        public static final String PDF = "pdf";
        public static final String REJECTED = "rejected";
        public static final String STORE_FAILED = "store";
        public static final String BUSY = "busy";
        public static final String BAD_DIRECTION = "bad_direction";

        static RotateOutcome refused(final String code, final String problem) {
            return new RotateOutcome(code, problem, null);
        }

        public boolean ok() {
            return OK.equals(code);
        }
    }

    /**
     * The receipt dialog's Rotate left / Rotate right ({@code direction} "left" or "right"); refusals growl.
     *
     * @return true when rotated.
     */
    public boolean rotateReceipt(final String tripId, final String mediaId, final String direction) {
        final RotateOutcome outcome = QuarterTurn.parse(direction)
                .map(turn -> rotate(tripId, mediaId, turn))
                .orElseGet(ExpenseReceipts::badDirection);
        if (!outcome.ok()) {
            return refuse(outcome.problem());
        }
        info("Receipt rotated.");
        return true;
    }

    private static RotateOutcome badDirection() {
        return RotateOutcome.refused(RotateOutcome.BAD_DIRECTION, "Say which way to rotate: left or right.");
    }

    /**
     * Turns one image receipt a quarter. The object is overwritten IN PLACE: receipts are served only by this
     * app's servlet with {@code private, no-store} (never the CDN), so the only stale copy is an in-page image
     * cache, which the row's new {@code rev} busts through {@link MediaItem#getCacheBuster()} on every URL.
     * The small rendition is re-derived from the turned image (a key is minted if the receipt had none). A
     * PDF is refused: it has no pixels to turn.
     */
    public RotateOutcome rotate(final String tripId, final String mediaId, final QuarterTurn turn) {
        if (!expenses().canEditExpenses(tripId)) {
            return RotateOutcome.refused(RotateOutcome.NOT_ALLOWED,
                    "Not allowed: only the trip's managers and finance admins can rotate receipts.");
        }
        final MediaItem item = (mediaId == null) ? null : DAO.getInstance().getMedia(mediaId, Cached.NO).orElse(null);
        if (item == null || !slotFor(tripId).equals(item.getSlot())) {
            return RotateOutcome.refused(RotateOutcome.NOT_ON_REPORT, "That receipt is not on this report.");
        }
        if (PDF.equals(item.getContentType())) {
            return RotateOutcome.refused(RotateOutcome.PDF, "A PDF receipt cannot be rotated.");
        }
        if (!RotationGuard.begin(item.getId())) {
            return RotateOutcome.refused(RotateOutcome.BUSY,
                    "That receipt is already being rotated. Try again in a moment.");
        }
        try {
            return rotateOwned(tripId, item, turn);
        } finally {
            RotationGuard.end(item.getId());
        }
    }

    private RotateOutcome rotateOwned(final String tripId, final MediaItem item, final QuarterTurn turn) {
        final Optional<Served> current = served(item, false);
        if (current.isEmpty()) {
            return RotateOutcome.refused(RotateOutcome.STORE_FAILED, "The receipt could not be read. Try again.");
        }
        final ProcessedPhoto photo;
        try {
            photo = processor.processWithin(processor.rotate(current.get().bytes(), turn).bytes(), MAX_IMAGE_SIDE);
        } catch (final PhotoRejectedException ex) {
            return RotateOutcome.refused(RotateOutcome.REJECTED, ex.getMessage());
        }
        final String smallKey = photo.smallIsFull() ? null
                : (item.getSmallKey() != null ? item.getSmallKey()
                        : KEY_PREFIX + tripId + "/" + item.getId() + "-small." + photo.smallExtension());
        if (!storeBytes(item.getS3Key(), photo.fullBytes(), typeOf(item.getS3Key()))
                || (smallKey != null && !storeBytes(smallKey, photo.smallBytes(), typeOf(smallKey)))) {
            return RotateOutcome.refused(RotateOutcome.STORE_FAILED, "The receipt could not be stored. Try again.");
        }
        final MediaItem rotated = item.withRotated(photo.fullBytes().length, smallKey);
        if (!saveRow(rotated)) {
            return RotateOutcome.refused(RotateOutcome.STORE_FAILED, "The receipt could not be stored. Try again.");
        }
        if (item.getSmallKey() != null && smallKey == null) {
            deleteObjects(item.getSmallKey(), null);
        }
        audit(item.getId(), "Expense receipt rotated " + turn.name().toLowerCase(Locale.ROOT) + " on trip " + tripId
                + " (" + item.getS3Key() + ")");
        return new RotateOutcome(RotateOutcome.OK, null, rotated);
    }

    // ================================================================== serve

    /**
     * A receipt's bytes for the servlet: the original, or its small rendition when {@code thumb} (an image
     * stored small enough to need none, or a PDF, serves the original). The type comes from the key's
     * extension, never the row: a GIF's small rendition is a PNG.
     */
    public Optional<Served> served(final MediaItem item, final boolean thumb) {
        final String key = (thumb && item.getSmallKey() != null) ? item.getSmallKey() : item.getS3Key();
        if (media.isUploadEnabled()) {
            return media.getObject(key).map(bytes -> new Served(bytes, typeOf(key)));
        }
        return LOCAL.stored(key).map(stored -> new Served(stored.bytes(), typeOf(key)));
    }

    /** The content type a stored key's extension means. */
    static String typeOf(final String key) {
        final String ext = key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "pdf" -> PDF;
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            default -> "image/jpeg";
        };
    }

    // ================================================================== plumbing

    private boolean storeBytes(final String key, final byte[] bytes, final String contentType) {
        if (media.isUploadEnabled()) {
            return media.putObject(key, bytes, contentType, CACHE_SECONDS, false);
        }
        LOCAL.put(key, bytes, contentType);
        return true;
    }

    private void deleteObjects(final String key, final String smallKey) {
        for (final String k : (smallKey == null) ? List.of(key) : List.of(key, smallKey)) {
            if (media.isUploadEnabled()) {
                media.deleteObject(k);
            } else {
                LOCAL.remove(k);
            }
        }
    }

    /** Seam: the media-row write; a test overrides it to fail. */
    protected boolean saveRow(final MediaItem item) {
        try {
            return DAO.getInstance().saveMedia(item);
        } catch (final RuntimeException ex) {
            log.error("Unable to save receipt media row {}", item.getS3Key(), ex);
            return false;
        }
    }

    private String uploader() {
        final Caller caller = callerSource.get();
        return (caller.auditActor() == null) ? null : caller.auditActor().email();
    }

    private void audit(final String mediaId, final String message) {
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .actor(callerSource.get().auditActor())
                .target(AuditEventBuilder.TARGET_MEDIA, mediaId)
                .message(message)
                .log();
    }
}
