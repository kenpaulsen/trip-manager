package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.media.LocalObjectStore;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPicture;
import org.primefaces.model.CroppedImage;

/**
 * A trip's pictures (issue 154), exposed as {@code #{tripPictures}}: the cover every listing card shows and up to
 * eight gallery pictures for the trip's own page, one ordered list on {@link Trip#getPictures()} whose position 0 is
 * the cover. This bean is the trip editor's Pictures dialog (the trip side of the shared upload+crop dialog,
 * {@link PhotoUploadBean}) AND the REST edge's implementation: every write takes the {@link Caller} explicitly and
 * answers a {@link PictureResult}, and the dialog's methods are thin wrappers that growl it.
 *
 * <p><b>Storage.</b> Each picture is cut to {@value #ASPECT_LABEL} and stored as three JPEGs
 * ({@link TripPicture#WIDTHS}) under {@code tripPictures/{tripId}/{version}-{width}.jpg} in the managed-media bucket,
 * CDN-served and public by URL like badge and profile pictures. Not under the organization's {@code org/} prefix,
 * although the issue first named one: that prefix is the org media library's own namespace, writable by its media
 * admins, and a trip can move to another organization, so a key there could be overwritten by the wrong tenant.
 * {@code tripPictures/} is reserved instead ({@link MediaCommands#isReservedKey}). Local mode keeps the bytes in a
 * capped in-memory store behind {@code web/TripPictureServlet}.
 *
 * <p><b>Writes go straight to the stored row</b> (the Required Documents pattern, {@code WaiverCommands}), read fresh
 * every time, and {@link TripCommands#saveTrip} keeps the stored list on every other save, so the trip editor's
 * working copy can never put back a picture list that predates an upload. Objects are stored BEFORE the row that
 * names them and removed AFTER it stops naming them, so a failure can leave an unreferenced object (swept with the
 * trip) but never a picture whose bytes are missing.
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

    /** {@link #ASPECT} as people say it, for the page's cropper hint and the docs. */
    static final String ASPECT_LABEL = "3:2";

    /** Below this the 1920 rendition is upscaled by the browser: a warning, never a refusal. */
    static final int MIN_GOOD_WIDTH = 1600;

    /** The height that goes with {@link #MIN_GOOD_WIDTH} at {@link #ASPECT}. */
    static final int MIN_GOOD_HEIGHT = 1067;

    /** The longest alt text kept; a screen reader reads every character of it. */
    static final int MAX_ALT = 200;

    /** ViewScope key the Pictures dialog's "Description" field writes for the NEXT upload. */
    static final String ALT_VIEW_KEY = "picAlt";

    /** Same day-long cache as badge and profile pictures: safe because a replacement is always a NEW key. */
    static final long CACHE_SECONDS = 86_400L;

    /** Local-store budget: a picture's three renditions are about 600 KB, so this is about a hundred pictures. */
    static final long LOCAL_STORE_MAX_BYTES = 64L * 1024 * 1024;

    private final LocalObjectStore localStore = new LocalObjectStore(LOCAL_STORE_MAX_BYTES);

    @Inject
    private MediaCommands media;

    /** How a write turned out; {@code warning} rides along with a success (a small picture is stored anyway). */
    public record PictureResult(Status status, String message, TripPicture picture, String warning) {

        /** What the REST edge maps to an HTTP status. */
        public enum Status { OK, NOT_FOUND, FORBIDDEN, LIMIT, INVALID, FAILED }

        public boolean isOk() {
            return status == Status.OK;
        }

        static PictureResult ok(final TripPicture picture, final String message, final String warning) {
            return new PictureResult(Status.OK, message, picture, warning);
        }

        static PictureResult fail(final Status status, final String message) {
            return new PictureResult(status, message, null, null);
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

    /** Seam: whether the caller administers the trip's organization; a blank org has no admins. */
    protected boolean isOrgAdmin(final Caller caller, final String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return false;
        }
        final Optional<Organization> org = DAO.getInstance().getOrganization(Organization.Id.from(orgId), Cached.YES);
        return org.isPresent() && org.get().isAdmin(caller.personId());
    }

    // ------------------------------------------------------------------ the writes, caller explicit

    /**
     * Adds one picture at the END of the list (the first one is therefore the cover): cut to {@link #ASPECT} from
     * the ORIGINAL bytes (null rect = the largest centered region), stored at every width, then appended to the
     * stored row. A cut region smaller than {@value #MIN_GOOD_WIDTH} x {@value #MIN_GOOD_HEIGHT} is stored with a
     * warning saying so.
     */
    public PictureResult add(final Caller caller, final String tripId, final byte[] original,
            final PhotoProcessor.CropRect rect, final String alt) {
        final Optional<Trip> found = readStored(tripId);
        if (found.isEmpty()) {
            return PictureResult.fail(PictureResult.Status.NOT_FOUND, "Not found: that trip does not exist.");
        }
        final Trip trip = found.get();
        if (!mayEdit(caller, trip)) {
            return PictureResult.fail(PictureResult.Status.FORBIDDEN,
                    "Not allowed: only this trip's managers can change its pictures.");
        }
        if (trip.getPictures().size() >= Trip.MAX_PICTURES) {
            return limitReached();
        }
        final PhotoProcessor.PictureRenditions renditions;
        try {
            renditions = processor.processPicture(original, rect, ASPECT, TripPicture.WIDTHS);
        } catch (final PhotoRejectedException ex) {
            return PictureResult.fail(PictureResult.Status.INVALID, "Picture rejected: " + ex.getMessage());
        }
        final String stem = PREFIX + tripId + "/" + System.currentTimeMillis();
        final TripPicture picture = new TripPicture(stem, cleanAlt(alt), renditions.width(), renditions.height(),
                String.format("#%06x", renditions.averageRgb()));
        if (!storeAll(picture, renditions.jpegs())) {
            removeAll(picture);
            return PictureResult.fail(PictureResult.Status.FAILED, "Not stored: the picture could not be stored. "
                    + "Try again.");
        }
        trip.getPictures().add(picture);
        if (!save(trip)) {
            removeAll(picture);
            return PictureResult.fail(PictureResult.Status.FAILED,
                    "Not saved: the trip could not be updated with the new picture. Try again.");
        }
        audit("Trip picture added", caller, trip, stem);
        final String what = trip.getPictures().size() == 1 ? "Cover picture added." : "Picture added.";
        return PictureResult.ok(picture, what, smallWarning(renditions));
    }

    /** Permanently removes one picture: off the stored row first (the publish), then out of the store and the CDN. */
    public PictureResult remove(final Caller caller, final String tripId, final String key) {
        final Target target = target(caller, tripId, key);
        if (target.refusal() != null) {
            return target.refusal();
        }
        final Trip trip = target.trip();
        final TripPicture picture = target.picture();
        trip.getPictures().remove(picture);
        if (!save(trip)) {
            return PictureResult.fail(PictureResult.Status.FAILED,
                    "Not saved: the trip could not be updated. The picture was not removed.");
        }
        removeAll(picture);
        audit("Trip picture removed", caller, trip, key);
        return PictureResult.ok(picture, "Picture removed.", null);
    }

    /**
     * Moves one picture to {@code index} (clamped into the list), shifting the others; index 0 makes it the cover.
     * The objects do not move, only the row's order.
     */
    public PictureResult move(final Caller caller, final String tripId, final String key, final int index) {
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
                    "Not saved: the trip could not be updated. The pictures were not reordered.");
        }
        audit("Trip picture moved to position " + (position + 1), caller, trip, key);
        return PictureResult.ok(picture, position == 0 ? "Cover picture changed." : "Picture moved.", null);
    }

    /** Sets one picture's description (its alt text); blank clears it, so the trip's title is read instead. */
    public PictureResult describe(final Caller caller, final String tripId, final String key, final String alt) {
        final Target target = target(caller, tripId, key);
        if (target.refusal() != null) {
            return target.refusal();
        }
        final Trip trip = target.trip();
        final TripPicture picture = target.picture();
        picture.setAlt(cleanAlt(alt));
        if (!save(trip)) {
            return PictureResult.fail(PictureResult.Status.FAILED,
                    "Not saved: the trip could not be updated. The description was not changed.");
        }
        audit("Trip picture described", caller, trip, key);
        return PictureResult.ok(picture, "Description saved.", null);
    }

    /** One per-picture write's subject, read once: the trip and its picture, or why the write is refused. */
    private record Target(Trip trip, TripPicture picture, PictureResult refusal) {
    }

    private Target target(final Caller caller, final String tripId, final String key) {
        final Optional<Trip> found = readStored(tripId);
        if (found.isEmpty()) {
            return refused(PictureResult.Status.NOT_FOUND, "Not found: that trip does not exist.");
        }
        if (!mayEdit(caller, found.get())) {
            return refused(PictureResult.Status.FORBIDDEN,
                    "Not allowed: only this trip's managers can change its pictures.");
        }
        final TripPicture picture = found.get().getPicture(key);
        if (picture == null) {
            return refused(PictureResult.Status.NOT_FOUND, "Not found: that picture is no longer on this trip.");
        }
        return new Target(found.get(), picture, null);
    }

    private static Target refused(final PictureResult.Status status, final String message) {
        return new Target(null, null, PictureResult.fail(status, message));
    }

    private static PictureResult limitReached() {
        return PictureResult.fail(PictureResult.Status.LIMIT, "Picture limit reached: a trip has at most "
                + Trip.MAX_PICTURES + " pictures, a cover and " + (Trip.MAX_PICTURES - 1) + " more. Remove one first.");
    }

    /** The warning for a cut region too small for the widest rendition, or null when it is big enough. */
    private static String smallWarning(final PhotoProcessor.PictureRenditions renditions) {
        if (renditions.width() >= MIN_GOOD_WIDTH && renditions.height() >= MIN_GOOD_HEIGHT) {
            return null;
        }
        return "Low resolution: the picture is " + renditions.width() + "×" + renditions.height() + " px, so it may "
                + "look soft on large screens. " + MIN_GOOD_WIDTH + "×" + MIN_GOOD_HEIGHT + " px or larger looks best.";
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

    @Override
    protected UploadGate uploadGate() {
        final Optional<Trip> trip = readStored(targetId());
        if (trip.isEmpty() || !mayEdit(caller(), trip.get())) {
            return UploadGate.deny("Not allowed: only this trip's managers can change its pictures.");
        }
        if (trip.get().getPictures().size() >= Trip.MAX_PICTURES) {
            return UploadGate.deny(limitReached().message());
        }
        return UploadGate.allow();
    }

    /**
     * The dialog's Save (or Use Full Photo): the parked ORIGINAL is cut at the confirmed rectangle, scaled from the
     * preview's coordinates, and added with the description typed above the upload button. A small picture's
     * warning is published as {@code picWarn} for the page to show after the dialog closes.
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
            error(now < 0 ? "Not found: that picture is no longer on this trip." : "Not moved: say where to.");
            publishParam("picChanged", false);
            return;
        }
        growlAndPublish(move(caller(), targetId(), key, target));
    }

    /** The description editor's Save: {@code key} and {@code alt} come from the page's scalar view state. */
    public void describeFromUi(final String key, final String alt) {
        growlAndPublish(describe(caller(), targetId(), key, alt));
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
        final Map<Object, Object> memo = requestMemo();
        final Object known = memo.get(MEMO_PREFIX + tripId);
        if (known instanceof List<?> list) {
            return list.stream().map(TripPicture.class::cast).toList();
        }
        final List<TripPicture> stored = readStored(tripId).map(Trip::getPictures).orElse(List.of());
        memo.put(MEMO_PREFIX + tripId, stored);
        return stored;
    }

    /** Whether the trip already has {@link Trip#MAX_PICTURES}, which disables the dialog's Add button. */
    public boolean isFull(final String tripId) {
        return picturesOf(tripId).size() >= Trip.MAX_PICTURES;
    }

    /** The editor row's summary beside "edit": what a manager needs to know at a glance. */
    public String summary(final String tripId) {
        final int count = picturesOf(tripId).size();
        if (count == 0) {
            return "No pictures";
        }
        return count == 1 ? "Cover only" : "Cover and " + (count - 1) + " more";
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

    /** Local-mode read-back, for the trip-picture servlet's GET. Empty when remote or unknown. */
    public Optional<byte[]> localGet(final String key) {
        return localStore.get(key);
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
        requestMemo().put(MEMO_PREFIX + trip.getId(), List.copyOf(trip.getPictures()));
        return true;
    }

    /**
     * Seam: a map that lives for this request, for {@link #picturesOf}'s memo: the FacesContext's attributes on a
     * page, a throwaway map anywhere else (the REST edge, tests), where nothing asks twice.
     */
    protected Map<Object, Object> requestMemo() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        return ctx == null ? new HashMap<>() : ctx.getAttributes();
    }

    private void audit(final String what, final Caller caller, final Trip trip, final String key) {
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .actor(caller.auditActor())
                .target(AuditEventBuilder.TARGET_MEDIA, key)
                .message(what + " for trip " + trip.getId() + " (" + trip.getTitle() + ")")
                .log();
    }
}
