package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.media.LocalObjectStore;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.BadgeImage;
import org.paulsens.trip.model.Trip;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.CroppedImage;

/**
 * Custom itinerary-badge pictures, exposed as {@code #{badgePhotos}}: the badge page's Add dialog backing
 * bean (the badge side of the shared upload+crop dialog -- {@link PhotoUploadBean} carries the contract and
 * the token flow) plus the delete action and URL resolution its thumbnails and the badge itself use.
 *
 * <p>The inventory is {@link Trip#getBadgeImages()} -- the uploaded object belongs to exactly ONE trip, and
 * saving the trip is what publishes it. Bytes go to the managed-media bucket under
 * {@code badgeImages/{tripId}/{version}.jpg} (CDN-served, public-by-URL like profile pictures); local mode
 * keeps them in a capped in-memory store behind the badge-photo servlet's GET, the same two-store split as
 * profile and chat photos and for the same reason.
 *
 * <p>Authorization is per-call and manager-shaped: a site admin or anyone holding the {@code tripMgr}
 * privilege for THE trip being edited -- which is also why every custom image is visible to every manager of
 * that trip: the trip row carries the list, not the uploader.
 */
@Slf4j
@Named("badgePhotos")
@ApplicationScoped
public class BadgePhotoCommands extends PhotoUploadBean {

    /** What a pending badge upload is marked as, so a profile/chat token cannot become a badge picture. */
    static final String PURPOSE = "badge";

    /** Session key remembering the uploaded FILENAME between upload and crop confirm -- the label default. */
    static final String LABEL_DEFAULT_KEY = "badgeLabelDefault";

    /** The badge page's session attribute naming the selected image (a built-in name or one of our keys). */
    static final String SELECTED_KEY = "badgeImg";

    /** ViewScope key the Add dialog's optional name field writes (blank falls back to the filename). */
    static final String LABEL_VIEW_KEY = "badgeLabel";

    /** Object-key prefix in the managed-media bucket; doubles as the "is this one of ours" test. */
    static final String PREFIX = "badgeImages/";

    /**
     * What the badge page's square picture slot renders at ({@code width="1275"}, and the print CSS maps
     * pixels 1:1), so this is full print resolution -- matching the page's own "ideally 1275px" instruction.
     */
    public static final int BADGE_SIZE = 1275;

    /** Keeps the dropdown and the trip row bounded; 20 pictures is already an implausibly indecisive trip. */
    static final int MAX_IMAGES = 20;

    /** Same day-long cache as profile pictures -- safe because a replacement is always a NEW key. */
    static final long CACHE_SECONDS = 86_400L;

    /** Local-store budget; badge renditions are ~300 KB, so this is around a hundred pictures. */
    static final long LOCAL_STORE_MAX_BYTES = 32L * 1024 * 1024;

    private final LocalObjectStore localStore = new LocalObjectStore(LOCAL_STORE_MAX_BYTES);

    @Inject
    private MediaCommands media;

    @Inject
    private TripCommands trips;

    @Inject
    private PrivilegeCommands priv;

    void localStoreMaxBytesForTest(final long maxBytes) {
        localStore.maxBytes(maxBytes);
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

    @Override
    protected String purpose() {
        return PURPOSE;
    }

    /**
     * The trip being edited, through the same seam the authorization uses ({@link #subjectTrip()}), so the
     * two can never name different trips -- and so a test that answers the seam directly is answered here too.
     */
    @Override
    protected String targetId() {
        final Trip trip = subjectTrip();
        return trip == null ? null : trip.getId();
    }

    @Override
    protected UploadGate uploadGate() {
        final Trip trip = subjectTrip();
        if (!mayEdit(trip)) {
            return UploadGate.deny("Not allowed: only this trip's managers can add badge images.");
        }
        if (trip.getBadgeImages().size() >= MAX_IMAGES) {
            return UploadGate.deny("Image limit reached: this trip already has " + MAX_IMAGES
                    + " custom badge images. Delete one first.");
        }
        return UploadGate.allow();
    }

    /** The uploaded filename becomes the label default, until the manager types one over it. */
    @Override
    protected void parked(final FileUploadEvent event) {
        sessionPut(LABEL_DEFAULT_KEY, baseName(event.getFile().getFileName()));
    }

    @Override
    protected void cancelled() {
        sessionPut(LABEL_DEFAULT_KEY, null);
    }

    @Override
    protected void confirm(final CroppedImage crop) {
        publishOutcome(applyCrop(subjectTrip(), crop));
    }

    /**
     * Applies the confirmed crop: coordinates arrive in preview-pixel space, are scaled to full resolution,
     * forced square, cut from the ORIGINAL bytes, and scaled to exactly {@value #BADGE_SIZE} px. The object
     * is stored FIRST and the trip saved second -- a failed save deletes the orphan object, whereas the other
     * order could publish a list entry whose bytes do not exist.
     *
     * <p>An undersized source is a warning, not a refusal: the image is upscaled and saved, and the growl
     * says (with the numbers) that it may print poorly -- the manager holding a once-in-a-lifetime group
     * photo gets to decide.
     *
     * @return true when stored; false leaves the dialog open with a message explaining why.
     */
    public boolean applyCrop(final Trip trip, final CroppedImage crop) {
        if (!mayEdit(trip)) {
            error("Not allowed: only this trip's managers can add badge images.");
            return false;
        }
        final Optional<PendingUploads.Pending> claimed = claim(trip.getId());
        if (claimed.isEmpty()) {
            return false;
        }
        final PendingUploads.Pending pending = claimed.get();
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
        finish(pending);
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
     * instead through the {@code badgeDeleted} ajax param -- the page reloads only on success, so a refusal's
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
        return media.isUploadEnabled() ? media.publicUrl(key) : contextPath() + "/badge-photos/" + key;
    }

    /** Local-mode read-back, for the badge-photo servlet's GET. Empty when remote or unknown. */
    public Optional<byte[]> localGet(final String key) {
        return localStore.get(key);
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

    /** The uploaded filename with its path and extension stripped -- the label nobody typed over. */
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
     * When the cut region must be upscaled to print size: growls AND publishes the text as the
     * {@code badgeWarn} ajax param. The dialog's after-save JS reloads the page -- which destroys the growl --
     * so the page re-raises the warning from its {@code ?warn=} parameter.
     */
    private void warnIfSmall(final PhotoProcessor.CropRect rect, final PendingUploads.Pending pending) {
        final int side = PhotoProcessor.squareSideOf(rect, pending.fullWidth(), pending.fullHeight());
        if (side < BADGE_SIZE) {
            final String message = "Low resolution: the selected area is " + side + "×" + side
                    + " px, but the badge prints from " + BADGE_SIZE + "×" + BADGE_SIZE
                    + " px — it may look blurry when printed.";
            warn(message);
            publishParam("badgeWarn", message);
        }
    }

    private boolean storeBytes(final String key, final byte[] jpeg) {
        if (media.isUploadEnabled()) {
            return media.putObject(key, jpeg, "image/jpeg", CACHE_SECONDS, false);
        }
        localStore.put(key, jpeg);
        return true;
    }

    /**
     * The trip-delete cascade for badge images: every image the trip row lists, then a store sweep of
     * anything left under {@code badgeImages/{tripId}/} (superseded versions, uploads that never got saved
     * onto the trip). No per-image growl or audit, no trip save -- the row is about to be deleted; the
     * caller records one audit event for the whole cascade.
     *
     * @return how many stored objects were removed.
     */
    public int deleteAllForTrip(final Trip trip) {
        int removed = 0;
        for (final BadgeImage image : trip.getBadgeImages()) {
            removeStored(image.getKey());
            removed++;
        }
        final String prefix = PREFIX + trip.getId() + "/";
        if (media.isUploadEnabled()) {
            final List<String> keys = media.listKeys(prefix);
            for (final String key : keys) {
                media.deleteObject(key);
            }
            removed += keys.size();
            if (!keys.isEmpty()) {
                media.invalidateCdn(List.of("/" + prefix + "*"));
            }
        } else {
            removed += localStore.removeByPrefix(prefix);
        }
        return removed;
    }

    /** Deletes a stored object and (privacy, not correctness) invalidates its CDN path. */
    private void removeStored(final String key) {
        if (media.isUploadEnabled()) {
            media.deleteObject(key);
            media.invalidateCdn(List.of("/" + key));
        } else {
            localStore.remove(key);
        }
    }

    /**
     * The trip being edited, resolved FRESH from the page's pinned id ({@code viewScope.theTripId}) on
     * every call: badge add/delete are read-modify-write saves of the whole trip, so the seed must come
     * from the store (never the near-cache, never a view-held snapshot -- the session-scope policy bans
     * the latter outright). Null when the id is absent or resolves to nothing ({@code getTripForEdit}
     * answers a blank trip with a FRESH id, so a same-id probe is the existence test) -- {@code mayEdit}
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

    /** Seam: the trip save; tests record instead of hitting the DAO chain. */
    protected boolean saveTrip(final Trip trip) {
        return trips.saveTrip(trip);
    }

    private void audit(final String what, final Trip trip, final String key) {
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .actor(caller().auditActor())
                .target(AuditEventBuilder.TARGET_MEDIA, key)
                .message(what + " for trip " + trip.getId() + " (" + trip.getTitle() + ")")
                .log();
    }
}
