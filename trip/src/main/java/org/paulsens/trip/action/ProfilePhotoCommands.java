package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.media.BackgroundRemover;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.media.QuarterTurn;
import org.paulsens.trip.media.RotationGuard;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.ScopeUtil;
import org.primefaces.model.CroppedImage;

/**
 * The profile-picture editor's backing bean, exposed as {@code #{profilePhotoEdit}}: the profile side of the
 * shared upload+crop dialog ({@link PhotoUploadBean} carries the dialog contract and the token flow), plus
 * the crop/delete actions and the background-replacement flow behind the dialog on
 * {@code account/person.xhtml}.
 *
 * <p>Authorization is per-call, the same rule as the page itself: yourself, someone whose profile you manage
 * ({@code managedUsers}), or a site admin. The upload listener cannot receive extra EL arguments, so the
 * SUBJECT (whose profile is being edited) is read from the page's {@code viewScope.person} -- the value the
 * page already resolved through {@code people.getSubject}.
 */
@Slf4j
@Named("profilePhotoEdit")
@ApplicationScoped
public class ProfilePhotoCommands extends PhotoUploadBean {

    /** What a pending profile upload is marked as, so a chat token cannot be finalized as a profile photo. */
    static final String PURPOSE = "profile";

    /** The background-removal dialog's own token key + purpose (its pending entry is a cutout PNG). */
    static final String BG_TOKEN_KEY = "profileBgCutoutToken";
    public static final String BG_PURPOSE = "profileBg";

    @Inject
    private PersonCommands people;

    @Inject
    private ProfilePhotos profilePhotos;

    @Inject
    private ConfigCommands config;

    @Override
    protected String purpose() {
        return PURPOSE;
    }

    @Override
    protected String targetId() {
        final Person subject = subject();
        return (subject == null || subject.getId() == null) ? null : subject.getId().getValue();
    }

    @Override
    protected String noun() {
        return "photo";
    }

    @Override
    protected UploadGate uploadGate() {
        return mayEdit(caller(), subject()) ? UploadGate.allow()
                : UploadGate.deny("Not allowed: you may not change this profile picture.");
    }

    /**
     * The shared dialog's Crop action: reads the dialog-local state -- the page's
     * {@code viewScope.photoTargetSlot} beside the cropper's rectangle -- and reports the outcome to the
     * dialog's {@code oncomplete} JS via the {@code cropStored} callback param, which is what closes the
     * dialog only on success.
     */
    @Override
    protected void confirm(final CroppedImage crop) {
        publishOutcome(applyCrop(subject(), crop, targetSlot()));
    }

    /**
     * Why a store or delete was refused, in a vocabulary the REST edge maps onto statuses. {@code null} when
     * the operation succeeded; the message is the same text the page growls.
     */
    public record PhotoResult(String code, String message, int slot) {

        public static final String NOT_ALLOWED = "not_allowed";
        public static final String NO_FREE_SLOT = "no_free_slot";
        public static final String REJECTED = "rejected";
        public static final String STORE_FAILED = "store";
        public static final String EMPTY_SLOT = "empty_slot";
        public static final String BAD_DIRECTION = "bad_direction";
        public static final String BUSY = "busy";

        static PhotoResult ok(final int slot) {
            return new PhotoResult(null, null, slot);
        }

        static PhotoResult refused(final String code, final String message) {
            return new PhotoResult(code, message, 0);
        }

        public boolean isOk() {
            return code == null;
        }
    }

    /**
     * Applies the confirmed crop: coordinates arrive in preview-pixel space (the image the cropper showed),
     * are scaled to the full-resolution space, forced square, and re-cropped from the ORIGINAL bytes.
     *
     * @param subject    whose profile (the page's {@code viewScope.person})
     * @param crop       the cropper's submitted value
     * @param targetSlot the slot to replace, or null/0 to take the next free slot
     * @return true when stored; false leaves the dialog open with a message explaining why.
     */
    public boolean applyCrop(final Person subject, final CroppedImage crop, final Integer targetSlot) {
        if (!mayEdit(caller(), subject)) {
            error("Not allowed: you may not change this profile picture.");
            return false;
        }
        final Optional<PendingUploads.Pending> claimed = claim(subject.getId().getValue());
        if (claimed.isEmpty()) {
            return false;
        }
        final PendingUploads.Pending pending = claimed.get();
        final PhotoResult result =
                storeFor(caller(), subject, pending.original(), scaledRect(crop, pending), targetSlot);
        if (!result.isOk()) {
            error(result.message());
            return false;
        }
        finish(pending);
        info("Profile picture saved: slot " + result.slot() + " updated.");
        return true;
    }

    /**
     * The store step on its own, for a caller that already holds the bytes (the REST upload endpoint; the
     * page reaches it through {@link #applyCrop} after claiming its parked upload): authorization, slot
     * choice, the square profile rendition, the store, the audit record. Reports through the returned
     * {@link PhotoResult} only -- no growl, so an edge without a page is not talking to nobody.
     *
     * @param who        the caller, explicit because the REST edge resolves its own
     * @param subject    whose profile
     * @param original   the uploaded bytes, untouched
     * @param rect       the crop in source-pixel space, or null for the whole image (forced square either way)
     * @param targetSlot the slot to replace, or null/0 to take the next free slot
     */
    public PhotoResult storeFor(final Caller who, final Person subject, final byte[] original,
            final PhotoProcessor.CropRect rect, final Integer targetSlot) {
        if (!mayEdit(who, subject)) {
            return PhotoResult.refused(PhotoResult.NOT_ALLOWED,
                    "Not allowed: you may not change this profile picture.");
        }
        final int slot = (targetSlot == null || targetSlot < 1)
                ? profilePhotos.nextFreeSlot(subject.getId().getValue()) : targetSlot;
        if (slot < 1 || slot > ProfilePhotos.MAX_SLOTS) {
            return PhotoResult.refused(PhotoResult.NO_FREE_SLOT, "No free slot: all " + ProfilePhotos.MAX_SLOTS
                    + " picture slots are in use. Replace or delete one instead.");
        }
        final byte[] jpeg;
        try {
            jpeg = processor.processProfile(original, rect);
        } catch (final PhotoRejectedException ex) {
            return PhotoResult.refused(PhotoResult.REJECTED, "Photo rejected: " + ex.getMessage());
        }
        if (!profilePhotos.store(subject.getId().getValue(), slot, jpeg)) {
            return PhotoResult.refused(PhotoResult.STORE_FAILED,
                    "Not stored: the photo could not be stored. Try again.");
        }
        audit(who, "Profile picture stored", subject, slot);
        return PhotoResult.ok(slot);
    }

    /** The word the delete dialog asks for; see {@link #isDeleteConfirmed}. */
    static final String DELETE_CHALLENGE = "delete";

    /**
     * The typed-"delete" dialog's action. The challenge is checked HERE, server-side, so a re-enabled button
     * cannot bypass it. The outcome goes to the dialog as the {@code photoDeleted} callback param: only a
     * delete closes it, and a refusal stays open with its reason showing IN the dialog (a growl renders
     * under a modal dialog's overlay, where nobody can see it).
     *
     * @return true when the picture was deleted.
     */
    public boolean deleteSlot(final Person subject, final int slot, final String challenge) {
        final boolean deleted = deleteConfirmed(subject, slot, challenge);
        publishParam("photoDeleted", deleted);
        return deleted;
    }

    private boolean deleteConfirmed(final Person subject, final int slot, final String challenge) {
        if (!isDeleteConfirmed(challenge)) {
            error("Not deleted: type 'delete' in the box to confirm.");
            return false;
        }
        final PhotoResult result = deleteSlotFor(caller(), subject, slot);
        if (!result.isOk()) {
            error(result.message());
            return false;
        }
        info("Profile picture deleted: picture " + slot + " removed.");
        return true;
    }

    /**
     * Case and surrounding space do not matter, as on the trip-delete dialog: a phone's keyboard capitalizes
     * the first letter of a text box, so an exact match refused "Delete" (and the refusal was never shown,
     * so the dialog just closed and nothing happened -- reported from production 2026-09-26).
     */
    static boolean isDeleteConfirmed(final String challenge) {
        return challenge != null && DELETE_CHALLENGE.equalsIgnoreCase(challenge.trim());
    }

    /** {@link #deleteSlot} with the caller explicit and the outcome returned rather than growled. */
    public PhotoResult deleteSlotFor(final Caller who, final Person subject, final int slot) {
        if (!mayEdit(who, subject)) {
            return PhotoResult.refused(PhotoResult.NOT_ALLOWED,
                    "Not allowed: you may not change this profile picture.");
        }
        if (!profilePhotos.deleteSlot(subject.getId().getValue(), slot)) {
            // The dialog offers only occupied slots, but a page can be stale (another tab, a family manager).
            return PhotoResult.refused(PhotoResult.EMPTY_SLOT,
                    "Not deleted: that picture was already removed. Reload the page to see your pictures.");
        }
        audit(who, "Profile picture deleted", subject, slot);
        return PhotoResult.ok(slot);
    }

    /**
     * The slot menu's "Rotate left" / "Rotate right": turns the slot's picture a quarter and growls the
     * outcome. {@code direction} is {@code "left"} or {@code "right"}.
     *
     * @return true when rotated.
     */
    public boolean rotate(final Person subject, final int slot, final String direction) {
        final PhotoResult result = QuarterTurn.parse(direction)
                .map(turn -> rotateFor(caller(), subject, slot, turn))
                .orElseGet(ProfilePhotoCommands::badDirection);
        if (!result.isOk()) {
            error(result.message());
            return false;
        }
        info("Profile picture rotated: picture " + slot + " updated.");
        return true;
    }

    private static PhotoResult badDirection() {
        return PhotoResult.refused(PhotoResult.BAD_DIRECTION, "Not rotated: say left or right.");
    }

    /**
     * Turns one slot's picture a quarter, with the caller explicit and the outcome returned (the REST edge).
     * The turned picture is stored like any replacement -- a NEW versioned key in the same slot, the old one
     * deleted and invalidated -- so every cache sees a new URL and the selection is untouched. A second
     * rotation of the same slot while one is running is refused as BUSY rather than racing it.
     */
    public PhotoResult rotateFor(final Caller who, final Person subject, final int slot, final QuarterTurn turn) {
        if (!mayEdit(who, subject)) {
            return PhotoResult.refused(PhotoResult.NOT_ALLOWED,
                    "Not allowed: you may not change this profile picture.");
        }
        final String personId = subject.getId().getValue();
        final String guardKey = ProfilePhotos.PREFIX + personId + "/" + slot;
        if (!RotationGuard.begin(guardKey)) {
            return PhotoResult.refused(PhotoResult.BUSY,
                    "Not rotated: that picture is already being rotated. Try again in a moment.");
        }
        try {
            return rotateGuarded(who, subject, slot, turn);
        } finally {
            RotationGuard.end(guardKey);
        }
    }

    private PhotoResult rotateGuarded(final Caller who, final Person subject, final int slot,
            final QuarterTurn turn) {
        final String personId = subject.getId().getValue();
        final Optional<byte[]> current = (slot < 1 || slot > ProfilePhotos.MAX_SLOTS)
                ? Optional.empty() : profilePhotos.currentBytes(personId, slot);
        if (current.isEmpty()) {
            return PhotoResult.refused(PhotoResult.EMPTY_SLOT,
                    "Not rotated: that picture is no longer there. Reload the page to see your pictures.");
        }
        final byte[] jpeg;
        try {
            jpeg = processor.rotate(current.get(), turn).bytes();
        } catch (final PhotoRejectedException ex) {
            return PhotoResult.refused(PhotoResult.REJECTED, "Photo rejected: " + ex.getMessage());
        }
        if (!profilePhotos.store(personId, slot, jpeg)) {
            return PhotoResult.refused(PhotoResult.STORE_FAILED,
                    "Not stored: the photo could not be stored. Try again.");
        }
        audit(who, "Profile picture rotated " + turn.name().toLowerCase(Locale.ROOT), subject, slot);
        return PhotoResult.ok(slot);
    }

    /** EL number literals land in viewScope as Long, setPropertyActionListener values as Integer -- accept both. */
    private Integer targetSlot() {
        final Number slot = viewMap("photoTargetSlot");
        return slot == null ? null : slot.intValue();
    }

    /**
     * Background removal, step 1 (flag-gated by {@code profile.bgRemoval.enabled}): runs the model over the
     * slot's current picture and parks the RGBA cutout behind a token. The dialog previews it entirely
     * client-side (canvas: fill color, draw cutout via the published {@code cutoutUrl}); nothing is stored
     * until {@link #applyBackground}.
     */
    public void startBgRemoval(final Person subject, final int slot) {
        if (!mayEdit(caller(), subject) || !bgRemovalEnabled()) {
            error("Not available: background replacement is not available.");
            publishParam("bgReady", false);
            return;
        }
        final Optional<byte[]> current = profilePhotos.currentBytes(subject.getId().getValue(), slot);
        if (current.isEmpty()) {
            error("No picture: that slot has no picture to edit.");
            publishParam("bgReady", false);
            return;
        }
        final Optional<byte[]> cutout;
        try {
            cutout = removeBackground(current.get());
        } catch (final PhotoRejectedException ex) {
            error("Photo rejected: " + ex.getMessage());
            publishParam("bgReady", false);
            return;
        }
        if (cutout.isEmpty()) {
            error("Busy: background replacement is busy or unavailable right now. Try again shortly.");
            publishParam("bgReady", false);
            return;
        }
        final PhotoProcessor.PreviewImage preview = new PhotoProcessor.PreviewImage(cutout.get(),
                PhotoProcessor.PROFILE_SIZE, PhotoProcessor.PROFILE_SIZE,
                PhotoProcessor.PROFILE_SIZE, PhotoProcessor.PROFILE_SIZE);
        final PendingUploads.Pending pending = pendingUploads().put(cutout.get(), preview,
                callerId(), BG_PURPOSE, subject.getId().getValue() + "#" + slot);
        sessionPut(BG_TOKEN_KEY, pending.token());
        publishParam("bgReady", true);
        publishParam("cutoutUrl", contextPath() + "/profile-photos/preview/" + pending.token());
    }

    /**
     * Background removal, step 2: composites the parked cutout over the chosen color
     * ({@code viewScope.bgPhotoColor}, the dialog's p:colorPicker) and stores the result as a NEW versioned
     * key in the same slot -- the original object is replaced only here, on explicit confirm.
     */
    public boolean applyBackground(final Person subject) {
        if (!mayEdit(caller(), subject) || !bgRemovalEnabled()) {
            error("Not available: background replacement is not available.");
            publishParam("bgApplied", false);
            return false;
        }
        final String token = sessionGet(BG_TOKEN_KEY);
        final Optional<PendingUploads.Pending> found =
                token == null ? Optional.empty() : pendingUploads().peek(token, callerId());
        final String targetPrefix = subject.getId().getValue() + "#";
        if (found.isEmpty() || !BG_PURPOSE.equals(found.get().purpose())
                || !found.get().targetId().startsWith(targetPrefix)) {
            error("Expired: the background preview is no longer available. Start over.");
            publishParam("bgApplied", false);
            return false;
        }
        final PendingUploads.Pending pending = found.get();
        final Integer rgb = parseColor(viewMap("bgPhotoColor"));
        if (rgb == null) {
            error("Pick a color: choose a background color first.");
            publishParam("bgApplied", false);
            return false;
        }
        final int slot = Integer.parseInt(pending.targetId().substring(targetPrefix.length()));
        final byte[] jpeg;
        try {
            jpeg = PhotoProcessor.compositeOnColor(pending.original(), rgb);
        } catch (final PhotoRejectedException ex) {
            error("Photo rejected: " + ex.getMessage());
            publishParam("bgApplied", false);
            return false;
        }
        if (!profilePhotos.store(subject.getId().getValue(), slot, jpeg)) {
            error("Not stored: the picture could not be stored. Try again.");
            publishParam("bgApplied", false);
            return false;
        }
        pendingUploads().consume(pending.token());
        sessionPut(BG_TOKEN_KEY, null);
        audit(caller(), "Profile picture background replaced", subject, slot);
        info("Background replaced: slot " + slot + " updated.");
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

    /**
     * Yourself, someone you manage, or a site admin -- the same rule the page enforces for its fields.
     * Public with the caller explicit so the REST edge asks the one rule rather than restating it.
     */
    public boolean mayEdit(final Caller who, final Person subject) {
        if (subject == null || subject.getId() == null || who == null || !who.isAuthenticated()) {
            return false;
        }
        if (who.isSiteAdmin() || subject.getId().equals(who.personId())) {
            return true;
        }
        return people.canAccessUserId(people.getPerson(who.personId()), subject.getId());
    }

    /** Seam: the page's subject ({@code viewScope.person}); tests override. */
    protected Person subject() {
        return ScopeUtil.getInstance().getViewMap("person");
    }

    /** Seam: the model call -- a test substitutes a canned cutout so inference is not a unit-test concern. */
    protected Optional<byte[]> removeBackground(final byte[] source) {
        return BackgroundRemover.cutout(source);
    }

    /** Seam: the feature flag ({@code KnownSettings.PROFILE_BG_REMOVAL_ENABLED}); tests override. */
    protected boolean bgRemovalEnabled() {
        return config != null && config.getBoolean(KnownSettings.PROFILE_BG_REMOVAL_ENABLED);
    }

    private void audit(final Caller who, final String what, final Person subject, final int slot) {
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .actor(who.auditActor())
                .target(AuditEventBuilder.TARGET_MEDIA, "profilePics/" + subject.getId().getValue())
                .message(what + " (slot " + slot + ") for " + subject.getPreferredName())
                .log();
    }
}
