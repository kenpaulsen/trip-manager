package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.LocalMode;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.media.LocalObjectStore;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPicture;
import org.primefaces.model.CroppedImage;
import org.primefaces.model.ResponsiveOption;

/**
 * A trip's pictures (issue 154), exposed as {@code #{tripPictures}}: the cover every listing card shows and up to
 * eight gallery pictures for the trip's own page, one ordered list on {@link Trip#getPictures()} whose position 0 is
 * the cover. This bean is the trip editor's Pictures dialog (the trip side of the shared upload+crop dialog,
 * {@link PhotoUploadBean}) AND the REST edge's implementation: every write takes the {@link Caller} explicitly and
 * answers a {@link PictureResult}, and the dialog's methods are thin wrappers that growl it.
 *
 * <p><b>Storage.</b> Each picture is cut to 3:2 ({@link #ASPECT}) and stored as three JPEGs
 * ({@link TripPicture#WIDTHS}) under {@code tripPictures/{tripId}/{version}-{width}.jpg} in the managed-media bucket,
 * CDN-served and public by URL like badge and profile pictures. Not under the organization's {@code org/} prefix,
 * although the issue first named one: that prefix is the org media library's own namespace, writable by its media
 * admins, and a trip can move to another organization, so a key there could be overwritten by the wrong tenant.
 * {@code tripPictures/} is reserved instead ({@link MediaCommands#isReservedKey}). Local mode keeps the bytes in a
 * capped in-memory store behind {@code web/TripPictureServlet}.
 *
 * <p><b>Writes go straight to the stored row</b> (the Required Documents pattern, {@code WaiverCommands}), read fresh
 * every time, and {@link TripCommands#saveTrip} keeps the stored list on every other save, so the trip editor's
 * working copy can never put back a picture list that predates an upload. Each write's read-modify-write runs under
 * a per-trip {@link ReentrantLock}, and an add re-reads the row only AFTER its slow processing and storing, so the
 * row it saves is never seconds old. Objects are stored BEFORE the row that names them and removed AFTER it stops
 * naming them, so a failure can leave an unreferenced object (swept with the trip) but never a picture whose bytes
 * are missing.
 *
 * <p><b>Who.</b> A site admin, a holder of {@code tripMgr} for THE trip, or an admin of the trip's organization:
 * the three the trip editor itself admits. Asked on every call, never inherited from the dialog being open.
 *
 * <p>No rotate: unlike a badge or a profile picture, a trip picture is cut to a fixed landscape shape, and a quarter
 * turn of that shape is a portrait no card can show. EXIF orientation is applied at upload, so a phone photo arrives
 * upright; one that is still wrong is removed and uploaded again.
 */
@Slf4j
@Named("tripPictures")
@ApplicationScoped
public class TripPictureCommands extends PhotoUploadBean {

    /** What a pending upload is marked as, so a token minted by another dialog cannot become a trip picture. */
    static final String PURPOSE = "tripPicture";

    /** Object-key prefix in the managed-media bucket; doubles as the "is this one of ours" test. */
    public static final String PREFIX = "tripPictures/";

    /** {@link #picturesOf}'s per-request memo key, followed by the trip id. */
    private static final String MEMO_PREFIX = "tripPictures.memo:";

    /** The one shape every picture is cut to (width / height), which every card and the dialog's cropper use. */
    public static final double ASPECT = 1.5d;

    /** Below this the 1920 rendition is upscaled by the browser: a warning, never a refusal. */
    static final int MIN_GOOD_WIDTH = 1600;

    /** The height that goes with {@link #MIN_GOOD_WIDTH} at {@link #ASPECT}. */
    static final int MIN_GOOD_HEIGHT = 1067;

    /** The longest alt text kept; a screen reader reads every character of it. */
    static final int MAX_ALT = 200;

    /** ViewScope key the Pictures dialog's "Description" field writes for the NEXT upload. */
    static final String ALT_VIEW_KEY = "picAlt";

    /** The Pictures dialog's description editor: which picture it is open on, and the text typed there. */
    static final String EDIT_KEY_VIEW_KEY = "picEditKey";
    static final String EDIT_ALT_VIEW_KEY = "picEditAlt";

    /** Same day-long cache as badge and profile pictures: safe because a replacement is always a NEW key. */
    static final long CACHE_SECONDS = 86_400L;

    /** Local-store budget: a picture's three renditions are about 600 KB, so this is about a hundred pictures. */
    static final long LOCAL_STORE_MAX_BYTES = 64L * 1024 * 1024;

    /**
     * The classic layout's column while the listing is ONE column, as {@code sizes} steps: the window less 404px
     * from 992px, less 360px from 715px, less 110px below. Stated once: the wide picture and a grid card are
     * the same width there.
     */
    private static final String CLASSIC_ONE_COLUMN = "(min-width: 992px) calc(100vw - 404px),"
            + " (min-width: 715px) calc(100vw - 360px), calc(100vw - 110px)";

    /** The full-width layout's block under a 992px window, where a grid card is the whole block below 688px. */
    private static final String FULL_WIDTH_NARROW = "calc(100vw - 28px)";

    /**
     * The per-trip write locks, striped by trip id ({@link #locked}): a lock per id would grow with every id a REST
     * caller makes up, and two trips sharing a stripe only wait for each other's few-millisecond save.
     */
    private static final ReentrantLock[] TRIP_LOCKS =
            Stream.generate(ReentrantLock::new).limit(64).toArray(ReentrantLock[]::new);

    /** The last key-stem number handed out ({@link #nextStamp}). */
    private static final AtomicLong STAMP = new AtomicLong();

    private final LocalObjectStore localStore = new LocalObjectStore(LOCAL_STORE_MAX_BYTES);

    @Inject
    private MediaCommands media;

    /**
     * How a write turned out; {@code warning} rides along with a success (a small picture is stored anyway), and
     * {@code pictures} is the trip's list exactly as that write saved it (empty on a refusal), so a caller answers
     * from it instead of re-reading a row that may still be stale in the cache.
     */
    public record PictureResult(Status status, String message, TripPicture picture, String warning,
            List<TripPicture> pictures) {

        /** What the REST edge maps to an HTTP status. */
        public enum Status { OK, NOT_FOUND, FORBIDDEN, LIMIT, INVALID, FAILED }

        public boolean isOk() {
            return status == Status.OK;
        }

        static PictureResult ok(final TripPicture picture, final String message, final String warning,
                final Trip saved) {
            return new PictureResult(Status.OK, message, picture, warning, List.copyOf(saved.getPictures()));
        }

        static PictureResult fail(final Status status, final String message) {
            return new PictureResult(status, message, null, null, List.of());
        }
    }

    void localStoreMaxBytesForTest(final long maxBytes) {
        localStore.maxBytes(maxBytes);
    }

    // ------------------------------------------------------------------ who

    /** Whether {@code caller} may change this trip's pictures. Pages gate on it too. */
    public boolean mayEdit(final Caller caller, final Trip trip) {
        if (trip == null || trip.getId() == null || caller == null || !caller.isAuthenticated()) {
            return false;
        }
        return caller.isSiteAdmin() || caller.has("tripMgr", trip.getId()) || isOrgAdmin(caller, trip.getOrgId());
    }

    /** {@link #mayEdit(Caller, Trip)} for the signed-in user, for the editor's {@code rendered=}. */
    public boolean mayEdit(final Trip trip) {
        return mayEdit(caller(), trip);
    }

    /**
     * Seam: whether the caller manages the trip's organization, asked of {@link OrgCommands#canManageOrg} as every
     * other trip-side gate does, so a change to who manages an org reaches the pictures too.
     */
    protected boolean isOrgAdmin(final Caller caller, final String orgId) {
        return new OrgCommands(() -> caller).canManageOrg(orgId);
    }

    // ------------------------------------------------------------------ the writes, caller explicit

    /**
     * Adds one picture at the END of the list (the first one is therefore the cover): cut to {@link #ASPECT} from
     * the ORIGINAL bytes (null rect = the largest centered region), stored at every width, then appended to the
     * stored row. A cut region smaller than {@value #MIN_GOOD_WIDTH} x {@value #MIN_GOOD_HEIGHT} is stored with a
     * warning saying so.
     *
     * <p>The first read only refuses early, before seconds of decoding and storing; the row that is saved is read
     * AGAIN afterwards, under the trip's lock, and the limit and the caller are re-checked against it. Saving the
     * first read instead would put back whatever trip write landed during the processing (a registration approval,
     * another upload) and let concurrent uploads pass {@link Trip#MAX_PICTURES}.
     */
    public PictureResult add(final Caller caller, final String tripId, final byte[] original,
            final PhotoProcessor.CropRect rect, final String alt) {
        final PictureResult refusal = earlyRefusal(caller, readStored(tripId));
        if (refusal != null) {
            return refusal;
        }
        final Prepared prepared = cutAndStore(tripId, alt,
                () -> processor.processPicture(original, rect, ASPECT, TripPicture.WIDTHS));
        if (prepared.failure() != null) {
            return prepared.failure();
        }
        final TripPicture picture = prepared.picture();
        final Appended appended = locked(tripId, () -> appendToStored(caller, tripId, List.of(picture)));
        if (appended.kept().isEmpty()) {
            // Outside the lock: deleting three objects and invalidating the CDN is network work.
            removeAll(picture);
            return appended.refusal();
        }
        return PictureResult.ok(picture, addedMessage(1, appended.cover()), prepared.warning(), appended.saved());
    }

    /**
     * How a batch from the albums went: how many pictures were added, how many of those are small enough to look
     * soft, the first reason any pick was NOT added (null when every one was), and whether the batch's first
     * picture became the cover (the trip had none before it).
     */
    public record AlbumResult(int added, int lowResolution, String refusal, boolean cover) {

        AlbumResult(final int added, final int lowResolution, final String refusal) {
            this(added, lowResolution, refusal, false);
        }

        /** What the manager is told: the same words a single upload uses ({@link #addedMessage(int, boolean)}). */
        public String addedMessage() {
            return TripPictureCommands.addedMessage(added, cover);
        }
    }

    /**
     * What a manager is told when pictures were added, however they arrived (one upload, or a batch from the
     * albums): one place for the words, so the same action never reads two ways.
     */
    static String addedMessage(final int added, final boolean cover) {
        return cover ? Text.get("feedback.tripPictureCommands.addedWithCover", added)
                : Text.get("feedback.tripPictureCommands.added", added);
    }

    /**
     * Adds pictures COPIED from trip albums (the editor's "Choose from Albums"), in the order picked: each album
     * photo's full-size bytes are cut to {@link #ASPECT} at the pick's position ({@code fx}, {@code fy}: 0 to 1
     * across the room the largest such region has to move, 0.5 = centered) and stored like an upload. The trip
     * keeps its own copies, so removing a photo from its album later, or the album's trip itself, changes nothing
     * here. {@code alt} describes every picture of the batch; the page passes one only for a single pick.
     *
     * <p>A pick is a bare media id from a browser, so whether THIS caller may use THAT photo for THIS trip is
     * asked here, of {@link AlbumPhotoSource#offered}: same organization, an album the caller may open, a photo
     * not kept out of public view. One answer covers every one of those refusals, which tells a prober nothing.
     *
     * <p>All the cutting and storing happens first; then the whole batch is appended in ONE read-modify-write
     * under the trip's lock. One append per picture would re-read the row moments after this request's own save
     * of it, and a read that close behind a write is not promised to see it.
     */
    public AlbumResult addFromAlbum(final Caller caller, final String tripId, final List<AlbumPicks.Pick> picks,
            final String alt) {
        final Optional<Trip> found = readStored(tripId);
        final PictureResult early = earlyRefusal(caller, found);
        if (early != null) {
            return new AlbumResult(0, 0, early.message());
        }
        final int room = Trip.MAX_PICTURES - found.get().getPictures().size();
        String refusal = picks.size() > room ? limitReached().message() : null;
        final List<Prepared> prepared = new ArrayList<>();
        for (final AlbumPicks.Pick pick : picks.subList(0, Math.min(room, picks.size()))) {
            final Prepared one = prepareFromAlbum(caller, found.get(), pick, alt);
            if (one.failure() == null) {
                prepared.add(one);
            } else if (refusal == null) {
                refusal = one.failure().message();
            }
        }
        if (prepared.isEmpty()) {
            return new AlbumResult(0, 0, refusal);
        }
        final List<TripPicture> pictures = prepared.stream().map(Prepared::picture).toList();
        final Appended appended = locked(tripId, () -> appendToStored(caller, tripId, pictures));
        // Outside the lock, like a single add: whatever the row did not take is deleted again.
        pictures.stream().filter(picture -> !appended.kept().contains(picture)).forEach(this::removeAll);
        final long soft = prepared.stream()
                .filter(one -> one.warning() != null && appended.kept().contains(one.picture()))
                .count();
        final String why = appended.refusal() != null ? appended.refusal().message() : refusal;
        return new AlbumResult(appended.kept().size(), (int) soft, why, appended.cover());
    }

    /** One album pick, checked, read, cut and stored; a failure names why this pick adds nothing. */
    private Prepared prepareFromAlbum(final Caller caller, final Trip trip, final AlbumPicks.Pick pick,
            final String alt) {
        final Optional<MediaItem> photo =
                albums().offered(caller, AlbumPhotoSource.tripScope(trip.getId()), pick.mediaId());
        if (photo.isEmpty()) {
            return Prepared.failed(PictureResult.fail(PictureResult.Status.FORBIDDEN,
                    Text.get("feedback.tripPictureCommands.albumPhotoNotAvailable")));
        }
        final Optional<byte[]> original = albums().bytesOf(photo.get());
        if (original.isEmpty()) {
            return Prepared.failed(PictureResult.fail(PictureResult.Status.FAILED,
                    Text.get("feedback.albumLibrary.notRead")));
        }
        return cutAndStore(trip.getId(), alt,
                () -> processor.processPictureAt(original.get(), pick.fx(), pick.fy(), ASPECT, TripPicture.WIDTHS));
    }

    /** Why an add is refused before any image work (no trip, not this caller's, already full), or null. */
    private PictureResult earlyRefusal(final Caller caller, final Optional<Trip> found) {
        if (found.isEmpty()) {
            return notFound();
        }
        if (!mayEdit(caller, found.get())) {
            return forbidden();
        }
        return found.get().getPictures().size() >= Trip.MAX_PICTURES ? limitReached() : null;
    }

    /** A picture whose renditions are stored and waiting for the row to name it, or why there is none. */
    private record Prepared(TripPicture picture, String warning, PictureResult failure) {

        static Prepared failed(final PictureResult failure) {
            return new Prepared(null, null, failure);
        }
    }

    /** The first half of every add: cut (the slow part) and store the renditions. Nothing names them yet. */
    private Prepared cutAndStore(final String tripId, final String alt,
            final Supplier<PhotoProcessor.PictureRenditions> cut) {
        final PhotoProcessor.PictureRenditions renditions;
        try {
            renditions = cut.get();
        } catch (final PhotoRejectedException ex) {
            return Prepared.failed(
                    PictureResult.fail(PictureResult.Status.INVALID,
                            Text.get("feedback.photoUploadBean.rejected.picture", ex.getMessage())));
        }
        final String stem = PREFIX + tripId + "/" + nextStamp();
        final TripPicture picture = new TripPicture(stem, cleanAlt(alt), renditions.width(), renditions.height(),
                String.format("#%06x", renditions.averageRgb()));
        if (!storeAll(picture, renditions.jpegs())) {
            removeAll(picture);
            return Prepared.failed(PictureResult.fail(PictureResult.Status.FAILED,
                    Text.get("feedback.tripPictureCommands.notStored")));
        }
        return new Prepared(picture, smallWarning(renditions), null);
    }

    /**
     * The number a new picture's key stem ends in: the clock's milliseconds, pushed forward by one whenever two
     * uploads would otherwise share a millisecond. Uploads to one trip run in parallel, and two pictures with one
     * stem would overwrite each other's objects and be listed twice.
     */
    static long nextStamp() {
        return STAMP.updateAndGet(prev -> Math.max(prev + 1, System.currentTimeMillis()));
    }

    /**
     * How an append went: the pictures the row now names (in order), whether the first of them became the cover,
     * why any others were left out (null when none were), and the trip as saved (null when nothing was).
     */
    private record Appended(List<TripPicture> kept, boolean cover, PictureResult refusal, Trip saved) {
    }

    /**
     * Every add's read-modify-write, under the trip's lock: the FRESH row, re-checked, plus as many of the new
     * pictures as it still has room for, saved once.
     */
    private Appended appendToStored(final Caller caller, final String tripId, final List<TripPicture> pictures) {
        final Optional<Trip> found = readStored(tripId);
        final PictureResult refusal = earlyRefusal(caller, found);
        if (refusal != null) {
            return new Appended(List.of(), false, refusal, null);
        }
        final Trip trip = found.get();
        final boolean cover = trip.getPictures().isEmpty();
        final int room = Trip.MAX_PICTURES - trip.getPictures().size();
        final List<TripPicture> kept = List.copyOf(pictures.subList(0, Math.min(room, pictures.size())));
        trip.getPictures().addAll(kept);
        if (!save(trip)) {
            return new Appended(List.of(), false, PictureResult.fail(PictureResult.Status.FAILED,
                    Text.get("feedback.tripPictureCommands.notSavedAdd")), null);
        }
        kept.forEach(picture -> audit("Trip picture added", caller, trip, picture.getKey()));
        return new Appended(kept, cover, kept.size() < pictures.size() ? limitReached() : null, trip);
    }

    /** Permanently removes one picture: off the stored row first (the publish), then out of the store and the CDN. */
    public PictureResult remove(final Caller caller, final String tripId, final String key) {
        final PictureResult result = locked(tripId, () -> removeFromStored(caller, tripId, key));
        if (result.isOk()) {
            removeAll(result.picture());
        }
        return result;
    }

    private PictureResult removeFromStored(final Caller caller, final String tripId, final String key) {
        final Target target = target(caller, tripId, key);
        if (target.refusal() != null) {
            return target.refusal();
        }
        final Trip trip = target.trip();
        final TripPicture picture = target.picture();
        trip.getPictures().remove(picture);
        if (!save(trip)) {
            return PictureResult.fail(PictureResult.Status.FAILED,
                    Text.get("feedback.tripPictureCommands.notSavedRemove"));
        }
        audit("Trip picture removed", caller, trip, key);
        return PictureResult.ok(picture, Text.get("feedback.tripPictureCommands.removed"), null, trip);
    }

    /**
     * Moves one picture to {@code index} (clamped into the list), shifting the others; index 0 makes it the cover.
     * The objects do not move, only the row's order.
     */
    public PictureResult move(final Caller caller, final String tripId, final String key, final int index) {
        return locked(tripId, () -> moveInStored(caller, tripId, key, index));
    }

    private PictureResult moveInStored(final Caller caller, final String tripId, final String key, final int index) {
        final Target target = target(caller, tripId, key);
        if (target.refusal() != null) {
            return target.refusal();
        }
        final Trip trip = target.trip();
        final TripPicture picture = target.picture();
        final List<TripPicture> pictures = new ArrayList<>(trip.getPictures());
        pictures.remove(picture);
        final int position = Math.max(0, Math.min(index, pictures.size()));
        pictures.add(position, picture);
        trip.setPictures(pictures);
        if (!save(trip)) {
            return PictureResult.fail(PictureResult.Status.FAILED,
                    Text.get("feedback.tripPictureCommands.notSavedMove"));
        }
        audit("Trip picture moved to position " + (position + 1), caller, trip, key);
        return PictureResult.ok(picture, position == 0 ? Text.get("feedback.tripPictureCommands.coverChanged")
                : Text.get("feedback.tripPictureCommands.moved"), null, trip);
    }

    /** Sets one picture's description (its alt text); blank clears it, so the trip's title is read instead. */
    public PictureResult describe(final Caller caller, final String tripId, final String key, final String alt) {
        return locked(tripId, () -> describeInStored(caller, tripId, key, alt));
    }

    private PictureResult describeInStored(final Caller caller, final String tripId, final String key,
            final String alt) {
        final Target target = target(caller, tripId, key);
        if (target.refusal() != null) {
            return target.refusal();
        }
        final Trip trip = target.trip();
        final TripPicture picture = target.picture();
        picture.setAlt(cleanAlt(alt));
        if (!save(trip)) {
            return PictureResult.fail(PictureResult.Status.FAILED,
                    Text.get("feedback.tripPictureCommands.notSavedDescribe"));
        }
        audit("Trip picture described", caller, trip, key);
        return PictureResult.ok(picture, Text.get("feedback.tripPictureCommands.descriptionSaved"), null, trip);
    }

    /** One per-picture write's subject, read once: the trip and its picture, or why the write is refused. */
    private record Target(Trip trip, TripPicture picture, PictureResult refusal) {
    }

    private Target target(final Caller caller, final String tripId, final String key) {
        final Optional<Trip> found = readStored(tripId);
        if (found.isEmpty()) {
            return refused(notFound());
        }
        if (!mayEdit(caller, found.get())) {
            return refused(forbidden());
        }
        final TripPicture picture = found.get().getPicture(key);
        if (picture == null) {
            return refused(PictureResult.fail(PictureResult.Status.NOT_FOUND,
                    Text.get("feedback.tripPictureCommands.pictureGone")));
        }
        return new Target(found.get(), picture, null);
    }

    private static Target refused(final PictureResult refusal) {
        return new Target(null, null, refusal);
    }

    private static PictureResult notFound() {
        return PictureResult.fail(PictureResult.Status.NOT_FOUND, Text.get("feedback.tripPictureCommands.noSuchTrip"));
    }

    private static PictureResult forbidden() {
        return PictureResult.fail(PictureResult.Status.FORBIDDEN,
                Text.get("feedback.tripPictureCommands.notAllowed"));
    }

    /**
     * Runs one read-modify-write of a trip's row while holding that trip's lock, so two writes to the same trip
     * (two uploads, a move during an upload) cannot both start from the same row and the later save erase the
     * earlier. A JVM lock is enough because the app runs as ONE task, deliberately. A {@link ReentrantLock}, not
     * {@code synchronized}: requests run on virtual threads.
     */
    private <T> T locked(final String tripId, final Supplier<T> write) {
        final ReentrantLock lock = TRIP_LOCKS[Math.floorMod(Objects.hashCode(tripId), TRIP_LOCKS.length)];
        lock.lock();
        try {
            return write.get();
        } finally {
            lock.unlock();
        }
    }

    private static PictureResult limitReached() {
        return PictureResult.fail(PictureResult.Status.LIMIT, Text.get("feedback.tripPictureCommands.limitReached",
                Trip.MAX_PICTURES, Trip.MAX_PICTURES - 1));
    }

    /** The warning for a cut region too small for the widest rendition, or null when it is big enough. */
    private static String smallWarning(final PhotoProcessor.PictureRenditions renditions) {
        if (renditions.width() >= MIN_GOOD_WIDTH && renditions.height() >= MIN_GOOD_HEIGHT) {
            return null;
        }
        return Text.get("feedback.tripPictureCommands.lowResolution", String.valueOf(renditions.width()),
                String.valueOf(renditions.height()), String.valueOf(MIN_GOOD_WIDTH), String.valueOf(MIN_GOOD_HEIGHT));
    }

    /** The alt text to store: trimmed, at most {@value #MAX_ALT} characters, blank as null. */
    static String cleanAlt(final String alt) {
        if (alt == null || alt.isBlank()) {
            return null;
        }
        final String trimmed = alt.trim();
        return trimmed.length() > MAX_ALT ? trimmed.substring(0, MAX_ALT) : trimmed;
    }

    // ------------------------------------------------------------------ the dialog

    @Override
    protected String purpose() {
        return PURPOSE;
    }

    @Override
    protected String noun() {
        return "picture";
    }

    @Override
    protected String targetId() {
        final Object id = viewMap("theTripId");
        return id == null ? null : id.toString();
    }

    /** A trip's pictures come from the albums of its own organization, its own album first. */
    @Override
    protected String albumScope() {
        final String tripId = targetId();
        return tripId == null ? null : AlbumPhotoSource.tripScope(tripId);
    }

    /** Only the trip's managers; the album listing asks this too, so a full gallery still lists (and says so). */
    @Override
    protected UploadGate authorization() {
        final String tripId = targetId();
        final Optional<Trip> trip = tripId == null ? Optional.empty() : readStored(tripId);
        return trip.isPresent() && mayEdit(caller(), trip.get()) ? UploadGate.allow()
                : UploadGate.deny(Text.get("feedback.tripPictureCommands.notAllowed"));
    }

    @Override
    protected String limit() {
        return picturesOf(targetId()).size() >= Trip.MAX_PICTURES ? limitReached().message() : null;
    }

    /**
     * The dialog's Save (or Use Full Photo): the parked ORIGINAL is cut at the confirmed rectangle, scaled from the
     * preview's coordinates, and added with the description typed above the upload button, which the picture
     * then takes (it is cleared here, as an album Add clears it: one place, so the two ways in cannot differ). A
     * small picture's warning is published as {@code picWarn} for the page to show after the dialog closes.
     */
    @Override
    protected void confirm(final CroppedImage crop) {
        final String tripId = targetId();
        final Optional<PendingUploads.Pending> claimed = claim(tripId);
        if (claimed.isEmpty()) {
            publishOutcome(false);
            return;
        }
        final PendingUploads.Pending pending = claimed.get();
        final PictureResult result = add(caller(), tripId, pending.original(), scaledRect(crop, pending),
                viewMap(ALT_VIEW_KEY));
        if (result.isOk()) {
            finish(pending);
            viewRemove(ALT_VIEW_KEY);
            info(result.message());
            if (result.warning() != null) {
                warn(result.warning());
                publishParam("picWarn", result.warning());
            }
        } else {
            error(result.message());
        }
        publishOutcome(result.isOk());
    }

    /**
     * The album dialog's Add button: every pick is copied in, in order, and ONE message says how it went (with
     * the first reason, when some were not added). The description typed above the buttons goes to a single pick
     * only: with several there is no telling which one it describes, so it is left for the next picture; when one
     * picture took it, it is cleared here, so the page only redraws. Reports {@code albumAdded} (whether at least
     * one was added) so the page knows to close the dialog.
     */
    public void addFromAlbumUi(final String picks) {
        final List<AlbumPicks.Pick> chosen = AlbumPicks.parse(picks, Trip.MAX_PICTURES);
        if (chosen.isEmpty()) {
            error(Text.get("feedback.albumLibrary.chooseSome"));
            publishParam("albumAdded", false);
            return;
        }
        final AlbumResult result = addFromAlbum(caller(), targetId(), chosen,
                chosen.size() == 1 ? viewMap(ALT_VIEW_KEY) : null);
        if (result.refusal() == null) {
            info(result.addedMessage());
        } else {
            error(result.added() == 0 ? result.refusal()
                    : Text.get("feedback.tripPictureCommands.someAdded", result.added(), chosen.size(),
                            result.refusal()));
        }
        if (result.lowResolution() > 0) {
            warn(Text.get("feedback.tripPictureCommands.someLowResolution", result.lowResolution()));
        }
        publishParam("albumAdded", result.added() > 0);
        if (chosen.size() == 1 && result.added() > 0) {
            viewRemove(ALT_VIEW_KEY);
        }
    }

    /** How many more pictures the trip has room for: what the album dialog lets a manager choose. */
    public int roomFor(final String tripId) {
        return Math.max(0, Trip.MAX_PICTURES - picturesOf(tripId).size());
    }

    /** A picture's Remove button; reports through the {@code picChanged} ajax param. */
    public void removeFromUi(final String key) {
        growlAndPublish(remove(caller(), targetId(), key));
    }

    /** A picture's Make Cover / Move Earlier / Move Later; {@code where} is first, earlier or later. */
    public void moveFromUi(final String key, final String where) {
        final Optional<Trip> trip = readStored(targetId());
        final int now = trip.map(t -> t.getPictures().indexOf(t.getPicture(key))).orElse(-1);
        final Integer target = switch (where == null ? "" : where) {
            case "first" -> 0;
            case "earlier" -> now - 1;
            case "later" -> now + 1;
            default -> null;
        };
        if (target == null || now < 0) {
            error(now < 0 ? Text.get("feedback.tripPictureCommands.pictureGone")
                    : Text.get("feedback.tripPictureCommands.notMoved"));
            publishParam("picChanged", false);
            return;
        }
        growlAndPublish(move(caller(), targetId(), key, target));
    }

    /** The description editor's Save: {@code key} and {@code alt} come from the page's scalar view state. */
    public void describeFromUi(final String key, final String alt) {
        final PictureResult result = describe(caller(), targetId(), key, alt);
        growlAndPublish(result);
        // The editor closes only on success: a refused save keeps it open with what was typed (the dialog says
        // why inside it), so nothing has to be typed again.
        if (result.isOk()) {
            viewRemove(EDIT_KEY_VIEW_KEY);
            viewRemove(EDIT_ALT_VIEW_KEY);
        }
    }

    private void growlAndPublish(final PictureResult result) {
        if (result.isOk()) {
            info(result.message());
        } else {
            error(result.message());
        }
        publishParam("picChanged", result.isOk());
    }

    // ------------------------------------------------------------------ reads

    /**
     * The STORED trip's pictures, read fresh: the dialog and the editor's Pictures row show what is saved, not the
     * editor's working copy, which never changes them. Empty for no or an unknown trip. Memoized for the request
     * ({@link #requestMemo}): one render asks several times, and a write this request already put its saved list
     * there, which is also what a page must show after it (a read right after a write can be stale).
     */
    public List<TripPicture> picturesOf(final String tripId) {
        if (tripId == null) {
            return List.of();
        }
        return RequestMemo.get(requestMemo(), MEMO_PREFIX + tripId,
                () -> readStored(tripId).map(Trip::getPictures).orElse(List.of()));
    }

    /** Whether the trip already has {@link Trip#MAX_PICTURES}, which disables the dialog's Add button. */
    public boolean isFull(final String tripId) {
        return picturesOf(tripId).size() >= Trip.MAX_PICTURES;
    }

    /** The editor row's summary beside "edit": what a manager needs to know at a glance. */
    public String summary(final String tripId) {
        final int count = picturesOf(tripId).size();
        if (count == 0) {
            return Text.get("feedback.tripPictureCommands.noPictures");
        }
        return count == 1 ? Text.get("feedback.tripPictureCommands.coverOnly")
                : Text.get("feedback.tripPictureCommands.coverAndMore", count - 1);
    }

    /** The URL a browser loads one rendition from: the CDN when configured, the trip-picture servlet locally. */
    public String url(final TripPicture picture, final int width) {
        if (picture == null) {
            return null;
        }
        final String key = picture.keyAt(width);
        return media.isUploadEnabled() ? media.publicUrl(key) : contextPath() + "/trip-pictures/" + key;
    }

    /** The full-width rendition's URL. */
    public String url(final TripPicture picture) {
        return url(picture, TripPicture.WIDTHS.getLast());
    }

    /**
     * A picture's alternative text, the ONE rule for it: the picture's own description when it has one, else the
     * trip's short title (blank for no trip). A listing card and the trip page show the same picture, so they must
     * not each decide what a screen reader says for it.
     */
    public String altOf(final TripPicture picture, final Trip trip) {
        return altText(picture, trip);
    }

    /** {@link #altOf} for code that holds no bean: a listing card is a plain value and must not look one up. */
    static String altText(final TripPicture picture, final Trip trip) {
        if (picture != null && picture.getAlt() != null && !picture.getAlt().isBlank()) {
            return picture.getAlt();
        }
        return trip == null ? "" : trip.getShortTitle();
    }

    /**
     * The {@code sizes} attribute of a picture drawn at the page's full content width: the listing's featured
     * card, the trip page's hero and its gallery. The widths are medjugorje's layout, measured in a browser and
     * stated ONLY in this class (here, {@link #gridSizes} and the two constants they share), so a page never
     * restates them:
     * <ul>
     * <li>Classic layout (a page with its sidebar): the column is the window less 110px; less 360px from a 715px
     *     window, where the 250px sidebar appears ({@code site.css}); less 404px from 992px, where the theme's
     *     content padding grows; and 1068px from a 1472px window, where the page's 1400px grid stops
     *     ({@code mainTemplate.xhtml}).</li>
     * <li>Full-width layout: the block is the window less its side insets, 28px up to 991px and 72px from 992px,
     *     capped at 1200px ({@code tripCards.css}), which a 1272px window reaches.</li>
     * </ul>
     * Change the layout and these change with it; medjugorje's {@code TripCardsPwIT} and {@code OrgSubdomainPwIT}
     * hold every card's drawn width against its {@code sizes}, in both layouts.
     */
    public String wideSizes(final boolean fullWidth) {
        return fullWidth
                ? "(min-width: 1272px) 1200px, (min-width: 992px) calc(100vw - 72px), " + FULL_WIDTH_NARROW
                : "(min-width: 1472px) 1068px, " + CLASSIC_ONE_COLUMN;
    }

    /**
     * The {@code sizes} attribute of a card in the listing's grid: {@link #wideSizes}' column split into the
     * grid's columns. The grid is one column until its container is 660px wide, two until 980px, then three, with
     * a 16px gap ({@code tripCards.css}, which names this method): restated here as the window widths at which the
     * column reaches those, per layout. The classic column reaches 660px at a 1064px window and 980px at 1384px;
     * the full-width block at 688px and 1052px. Each inset below is the column's own inset plus the gaps between
     * the cards, so they move together: classic 436 = 404 + 2 x 16 and 420 = 404 + 16; full width 104 = 72 + 2 x
     * 16, 88 = 72 + 16 and 44 = 28 + 16. The capped card is (1068 - 32) / 3 = 346px, or (1200 - 32) / 3 = 390px.
     */
    public String gridSizes(final boolean fullWidth) {
        return fullWidth
                ? "(min-width: 1272px) 390px, (min-width: 1052px) calc((100vw - 104px) / 3),"
                        + " (min-width: 992px) calc((100vw - 88px) / 2), (min-width: 688px) calc((100vw - 44px) / 2), "
                        + FULL_WIDTH_NARROW
                : "(min-width: 1472px) 346px, (min-width: 1384px) calc((100vw - 436px) / 3),"
                        + " (min-width: 1064px) calc((100vw - 420px) / 2), " + CLASSIC_ONE_COLUMN;
    }

    /**
     * The trip page gallery's one responsive rule: three thumbnails from a 480px window down, not the strip's
     * five. Five slots on a phone leave each thumbnail about 35px wide, too small to tell apart or to tap; and
     * the strip cannot simply be hidden there, because PrimeFaces binds the gallery's swipe on the thumbnail
     * strip, not on the picture.
     */
    public List<ResponsiveOption> getGalleryResponsive() {
        return List.of(new ResponsiveOption("480px", 3));
    }

    /**
     * Local-mode read-back, for the trip-picture servlet's GET. Empty when remote or unknown. A bundled demo
     * picture ({@link DemoTripPictures}, the local fixtures' covers) has its renditions made on first request,
     * and ONLY in local mode, for a key that is exactly one of a demo picture's three renditions: the servlet is
     * anonymous, so any other miss must cost a store read and nothing more.
     */
    public Optional<byte[]> localGet(final String key) {
        final Optional<byte[]> stored = localStore.get(key);
        final boolean demo = stored.isEmpty() && !media.isUploadEnabled() && LocalMode.isLocal()
                && DemoTripPictures.isRendition(key);
        return demo ? demoRendition(key) : stored;
    }

    /** One rendition of a bundled demo picture, after running its source through the real pipeline once. */
    private Optional<byte[]> demoRendition(final String key) {
        final Optional<byte[]> source = DemoTripPictures.demoOf(key).flatMap(DemoTripPictures::source);
        if (source.isEmpty()) {
            return Optional.empty();
        }
        final PhotoProcessor.PictureRenditions renditions;
        try {
            renditions = processor.processPicture(source.get(), null, ASPECT, TripPicture.WIDTHS);
        } catch (final PhotoRejectedException ex) {
            log.warn("Bundled demo picture {} could not be processed: {}", key, ex.getMessage());
            return Optional.empty();
        }
        final String stem = DemoTripPictures.stemOf(key);
        for (int i = 0; i < TripPicture.WIDTHS.size(); i++) {
            localStore.put(TripPicture.variantKey(stem, TripPicture.WIDTHS.get(i)), renditions.jpegs().get(i));
        }
        return localStore.get(key);
    }

    /**
     * The {@code srcset} of a picture: each stored rendition with its REAL width, so a picture smaller than 1920
     * (stored at its own width, never enlarged) is not offered as wider than it is, and equal widths appear once.
     */
    public String srcset(final TripPicture picture) {
        return srcsetUpTo(picture, TripPicture.WIDTHS.getLast());
    }

    /**
     * {@link #srcset} without the renditions wider than {@code maxWidth}, so a browser can never pick one, however
     * dense its screen: a listing card is capped at the middle rendition (owner, 2026-11-17), because on a phone the
     * 1920 copy cost several times the bytes for a picture a few inches wide, and slow connections paid for it.
     */
    public String srcsetUpTo(final TripPicture picture, final int maxWidth) {
        if (picture == null) {
            return "";
        }
        final StringBuilder set = new StringBuilder();
        int last = 0;
        for (final int width : TripPicture.WIDTHS) {
            if (width > maxWidth) {
                break;
            }
            final int real = Math.min(width, picture.getWidth());
            if (real > last) {
                set.append(set.isEmpty() ? "" : ", ").append(url(picture, width)).append(' ').append(real).append('w');
                last = real;
            }
        }
        return set.toString();
    }

    // ------------------------------------------------------------------ the trip-delete cascade

    /**
     * Every object the trip's row names, then a store sweep of anything left under {@code tripPictures/{tripId}/}
     * (an upload whose row save failed). No audit, no trip save: the row is about to go, and the caller records one
     * audit event for the whole cascade.
     *
     * @return how many stored objects were removed.
     */
    public int deleteAllForTrip(final Trip trip) {
        int removed = 0;
        for (final TripPicture picture : trip.getPictures()) {
            removeAll(picture);
            removed += TripPicture.WIDTHS.size();
        }
        final String prefix = PREFIX + trip.getId() + "/";
        if (media.isUploadEnabled()) {
            final List<String> keys = media.listKeys(prefix);
            keys.forEach(media::deleteObject);
            removed += keys.size();
            if (!keys.isEmpty()) {
                media.invalidateCdn(List.of("/" + prefix + "*"));
            }
        } else {
            removed += localStore.removeByPrefix(prefix);
        }
        return removed;
    }

    // ------------------------------------------------------------------ storage

    private boolean storeAll(final TripPicture picture, final List<byte[]> jpegs) {
        for (int i = 0; i < TripPicture.WIDTHS.size(); i++) {
            final String key = picture.keyAt(TripPicture.WIDTHS.get(i));
            if (media.isUploadEnabled()) {
                if (!media.putObject(key, jpegs.get(i), "image/jpeg", CACHE_SECONDS, false)) {
                    return false;
                }
            } else {
                localStore.put(key, jpegs.get(i));
            }
        }
        return true;
    }

    /** Deletes every rendition and (privacy, not correctness) invalidates their CDN paths. */
    private void removeAll(final TripPicture picture) {
        if (media.isUploadEnabled()) {
            picture.getKeys().forEach(media::deleteObject);
            media.invalidateCdn(picture.getKeys().stream().map(key -> "/" + key).toList());
        } else {
            picture.getKeys().forEach(localStore::remove);
        }
    }

    // ------------------------------------------------------------------ seams

    /** Seam: the stored trip, read fresh (never the near-cache: every caller is a read-modify-write or an editor). */
    protected Optional<Trip> readStored(final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return Optional.empty();
        }
        return DAO.getInstance().getTrip(tripId, Cached.NO);
    }

    /**
     * Seam: writes the row straight through the DAO. Not {@code TripCommands.saveTrip}, which keeps the STORED
     * pictures by design and would also refuse a picture over an unrelated unfinished setting.
     */
    protected boolean saveStored(final Trip trip) {
        try {
            return Boolean.TRUE.equals(DAO.getInstance().saveTrip(trip));
        } catch (final IOException | RuntimeException ex) {
            log.error("Saving the pictures of trip {} failed", trip.getId(), ex);
            return false;
        }
    }

    /** Every write's save: the row, then this request's memo, so the page re-rendered after it shows the result. */
    private boolean save(final Trip trip) {
        if (!saveStored(trip)) {
            return false;
        }
        RequestMemo.put(requestMemo(), MEMO_PREFIX + trip.getId(), List.copyOf(trip.getPictures()));
        return true;
    }

    /**
     * Seam: {@link #picturesOf}'s memo map, the Faces request map ({@link RequestMemo}); null off a page (the REST
     * edge), where nothing asks twice and every read computes.
     */
    protected Map<String, Object> requestMemo() {
        return RequestMemo.facesRequestMap();
    }

    private void audit(final String what, final Caller caller, final Trip trip, final String key) {
        AuditCommands.media(key, trip.getOrgId(), what + " for trip " + trip.getId() + " (" + trip.getTitle() + ")",
                caller.auditActor());
    }
}
