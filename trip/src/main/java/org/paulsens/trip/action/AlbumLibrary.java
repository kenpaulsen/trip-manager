package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.media.LocalObjectStore;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.MediaItem;

/**
 * Album photos COPIED into the media library, exposed as {@code #{albumLibrary}}: the "Choose from Albums" source
 * of the library page itself (several photos at once) and of every place that takes an image by URL (a content
 * section's picture, the rich-text editor's image button), which get one photo copied in and its URL handed back.
 * A library item is what those places can already use, and what the Trip Page and Appearance backgrounds already
 * pick from, so one copy path serves all of them.
 *
 * <p><b>A copy, kept whole.</b> The photo keeps its own shape (nothing here knows the shape its user wants), is
 * re-encoded as a JPEG no wider than {@value #MAX_WIDTH} pixels (a fresh encode, so the camera's and the phone's
 * metadata, GPS included, do not ride along) and stored under the owner's {@code album-photos/} folder, which only
 * this class writes. The library keeps its own copy: hiding or removing the photo in its album later changes
 * nothing here, which is what a page that shows it needs.
 *
 * <p><b>Which photos</b> is {@link AlbumPhotoSource}'s question, asked in {@link AlbumPhotoSource#SCOPE_SITE}: the
 * albums of the trips the site this request came to serves, that this person may open, minus anything kept out
 * of public view. <b>Who may copy</b> ({@link #mayAdd}): whoever may add to this site's library (its media
 * admins), an editor of its content, or an admin of its organization.
 *
 * <p>Local mode has no bucket, so the bytes sit in a capped in-memory store behind
 * {@code web/AlbumLibraryServlet} ({@link MediaCommands#localServedPath}), the trip stylesheets' arrangement.
 */
@Slf4j
@Named("albumLibrary")
@ApplicationScoped
public class AlbumLibrary {

    /** The folder, under the owner's namespace, that only this class writes. */
    public static final String FOLDER = "album-photos/";

    /** Where {@code web/AlbumLibraryServlet} serves a copy in local mode. */
    public static final String LOCAL_PATH = "/album-library/";

    /** The widest a copy is stored: enough for a full-width band on a large screen. */
    static final int MAX_WIDTH = 2400;

    /** The most a copy may weigh; the encoder steps quality down, then size, to fit. */
    static final long MAX_BYTES = 2L * 1024 * 1024;

    /** How many photos one Add may copy: each is a decode and an encode on the request's thread. */
    static final int MAX_PER_ADD = 20;

    /** A copy's library title: neutral, never the sender's caption (an admin may rename it). */
    static final String COPY_TITLE = "Album photo";

    /** What a copy's library row says about it: where it came from, and nothing about who shared it. */
    static final String COPY_DESCRIPTION = "Copied from a trip album";

    static final long CACHE_SECONDS = 86_400L;
    static final long LOCAL_STORE_MAX_BYTES = 64L * 1024 * 1024;

    /**
     * The two shapes a copy's key has: in an organization's namespace, or bare on a shared site. Two plain patterns
     * rather than one with an optional group, which the security scan reads as open to backtracking.
     */
    private static final Pattern ORG_KEY = Pattern.compile(
            MediaCommands.ORG_KEY_PREFIX + "[A-Za-z0-9-]+/" + FOLDER + "[0-9]+\\.jpg");
    private static final Pattern SITE_KEY = Pattern.compile(FOLDER + "[0-9]+\\.jpg");

    /** The last key number handed out: the clock's milliseconds, pushed forward when two copies share one. */
    private static final AtomicLong STAMP = new AtomicLong();

    private final PhotoProcessor processor = new PhotoProcessor();
    private final LocalObjectStore localStore = new LocalObjectStore(LOCAL_STORE_MAX_BYTES);

    @Inject
    private MediaCommands media;

    @Inject
    private AlbumPhotoSource albums;

    void wireForTest(final MediaCommands mediaCommands, final AlbumPhotoSource source) {
        this.media = mediaCommands;
        this.albums = source;
    }

    /** How a copy went: the library items made, and the first reason any pick made none (null when all did). */
    public record CopyResult(List<MediaItem> items, String refusal) {
    }

    /** Whether {@code key} is one of ours: an owner's {@code album-photos/} folder, a number, {@code .jpg}. */
    public static boolean isAlbumCopyKey(final String key) {
        return key != null && !key.contains("..")
                && (SITE_KEY.matcher(key).matches() || ORG_KEY.matcher(key).matches());
    }

    // ------------------------------------------------------------------ who, and from where

    /**
     * Whether the caller may copy album photos into this site's library: one of its media admins, an editor of
     * its content, or an admin of its organization. Pages gate their "Choose from Albums" button on it, and every
     * copy asks again.
     */
    public boolean mayAdd() {
        return mayAdd(caller());
    }

    boolean mayAdd(final Caller caller) {
        if (caller == null || !caller.isAuthenticated()) {
            return false;
        }
        return caller.hasHere(PrivilegeCommands.MEDIA_ADMIN) || caller.hasHere(PrivilegeCommands.CONTENT_ADMIN)
                || isOrgAdmin(caller, siteOrgId());
    }

    /**
     * The scope the dialog lists and every pick is checked in: the trips this site serves, and only for someone who
     * may copy into this library ({@link #mayAdd}), as every host answers its scope only to a caller it admits.
     */
    public String getAlbumScope() {
        return mayAdd() ? AlbumPhotoSource.SCOPE_SITE : null;
    }

    // ------------------------------------------------------------------ the copy

    /**
     * Copies album photos into this site's library, in the order given. Each id is a bare media id from a
     * browser: {@link AlbumPhotoSource#offered} decides, per photo, whether this caller may use it here.
     */
    public CopyResult copy(final Caller caller, final List<String> mediaIds) {
        if (!mayAdd(caller)) {
            return new CopyResult(List.of(), "Not allowed: you may not add to this site's media library.");
        }
        final List<MediaItem> made = new ArrayList<>();
        String refusal = mediaIds.size() > MAX_PER_ADD
                ? "Too many: at most " + MAX_PER_ADD + " photos can be added at once." : null;
        for (final String mediaId : mediaIds.subList(0, Math.min(MAX_PER_ADD, mediaIds.size()))) {
            final Copied one = copyOne(caller, mediaId);
            if (one.item() != null) {
                made.add(one.item());
            } else if (refusal == null) {
                refusal = one.refusal();
            }
        }
        return new CopyResult(List.copyOf(made), refusal);
    }

    /** One pick's outcome: the library item made, or why there is none. */
    private record Copied(MediaItem item, String refusal) {
    }

    private Copied copyOne(final Caller caller, final String mediaId) {
        final Optional<MediaItem> photo = albums.offered(caller, AlbumPhotoSource.SCOPE_SITE, mediaId);
        if (photo.isEmpty()) {
            return new Copied(null, "Not available: that album photo cannot be used here.");
        }
        final Optional<byte[]> original = albums.bytesOf(photo.get());
        if (original.isEmpty()) {
            return new Copied(null, "Not read: an album photo could not be read. Try again.");
        }
        final byte[] jpeg;
        try {
            jpeg = processor.cropToBoundedJpeg(original.get(), null, 0d, MAX_WIDTH, MAX_BYTES);
        } catch (final PhotoRejectedException ex) {
            return new Copied(null, "Photo rejected: " + ex.getMessage());
        }
        final String orgId = siteOrgId();
        final String key = MediaCommands.keyForOwner(orgId, FOLDER + nextStamp() + ".jpg");
        if (!store(key, jpeg)) {
            return new Copied(null, "Not stored: the photo could not be stored. Try again.");
        }
        // Neither the album row's title nor its description: the title is the caption the sender typed for the
        // trip's members, the description names who shared it and the trip's chat, and a library row is public
        // (the media API returns both to anyone). An admin may rename the copy; the audit line keeps its source.
        final MediaItem item = new MediaItem(UUID.randomUUID().toString(), key, COPY_TITLE,
                COPY_DESCRIPTION, "image/jpeg", jpeg.length, null, 0, LocalDateTime.now(),
                caller.auditActor().email(), null, null, orgId);
        if (!record(item)) {
            return new Copied(null, "Not listed: the photo was stored but could not be added to the library.");
        }
        MediaEvents.fire(MediaEvents.Change.ADDED, key);
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .actor(caller.auditActor())
                .target(AuditEventBuilder.TARGET_MEDIA, key)
                .message("Copied album photo " + photo.get().getS3Key() + " into the library as " + key)
                .log();
        return new Copied(item, null);
    }

    static long nextStamp() {
        return STAMP.updateAndGet(prev -> Math.max(prev + 1, System.currentTimeMillis()));
    }

    // ------------------------------------------------------------------ the dialog

    /**
     * The library page's Add: the dialog's picks ({@code id@x,y;id@x,y}; the positions are ignored, a copy is
     * whole) are copied in and ONE message says how it went. Reports {@code albumAdded}.
     */
    public void addFromAlbumUi(final String picks) {
        // One more than the batch size is read, so an over-full Add reaches copy() and is named there, not cut.
        final List<String> ids = AlbumPicks.parse(picks, MAX_PER_ADD + 1).stream()
                .map(AlbumPicks.Pick::mediaId).toList();
        if (ids.isEmpty()) {
            PageFeedback.error("Nothing chosen: choose at least one photo first.");
            publish("albumAdded", false);
            return;
        }
        final CopyResult result = copy(caller(), ids);
        final int added = result.items().size();
        final String done = added == 1 ? "Photo added to the library." : added + " photos added to the library.";
        if (result.refusal() == null) {
            PageFeedback.info(done);
            publish("albumDone", done);
        } else {
            final String note = added == 0 ? result.refusal()
                    : added + " of " + ids.size() + " added. " + result.refusal();
            PageFeedback.error(note);
            // The library page reloads after a copy, and a growl does not survive that: the page carries this on.
            publish("albumNote", note);
        }
        publish("albumAdded", added > 0);
    }

    /**
     * The one-photo pick of a place that takes an image by URL: the photo is copied into the library and its
     * public URL reported as {@code albumUrl}, beside {@code albumPicked}, for the page to put in its field.
     */
    public void pickFromAlbum(final String mediaId) {
        final CopyResult result = copy(caller(), mediaId == null ? List.of() : List.of(mediaId));
        if (result.items().isEmpty()) {
            PageFeedback.error(result.refusal() == null ? "Nothing chosen: choose a photo first." : result.refusal());
            publish("albumPicked", false);
            return;
        }
        publish("albumUrl", absolute(media.getUrl(result.items().getFirst())));
        publish("albumPicked", true);
    }

    /**
     * A copy's URL as a field can store it. With a media host it already is absolute. Locally it is a path this
     * webapp serves, and a picture field takes only a full web address, so this request's origin goes in front by
     * the branding uploads' own rule ({@link BrandingPhotos#absoluteAgainst}).
     */
    private String absolute(final String url) {
        return BrandingPhotos.absoluteAgainst(requestOrigin(), url);
    }

    /** Local-mode read-back, for the servlet's GET. Empty when remote, unknown, or not one of our keys. */
    public Optional<byte[]> localGet(final String key) {
        return isAlbumCopyKey(key) ? localStore.get(key) : Optional.empty();
    }

    // ------------------------------------------------------------------ storage and seams

    private boolean store(final String key, final byte[] jpeg) {
        if (media.isUploadEnabled()) {
            return media.putObject(key, jpeg, "image/jpeg", CACHE_SECONDS, false);
        }
        localStore.put(key, jpeg, "image/jpeg");
        return true;
    }

    /** Seam: saves the library row. */
    protected boolean record(final MediaItem item) {
        try {
            return DAO.getInstance().saveMedia(item);
        } catch (final RuntimeException ex) {
            log.error("Unable to save the media row of " + item.getS3Key(), ex);
            return false;
        }
    }

    /** Seam: who is asking (pages and servlets alike: the request-bound caller). */
    protected Caller caller() {
        return Caller.bound();
    }

    /** Seam: the organization this site's library belongs to; null on a shared site. */
    protected String siteOrgId() {
        return MediaCommands.siteOrgId();
    }

    /** Seam: whether the caller administers this organization; a shared site's library has none. */
    protected boolean isOrgAdmin(final Caller caller, final String orgId) {
        return orgId != null && albums.isOrgAdmin(caller, orgId);
    }

    /** Seam: the origin of the request in progress ({@link BrandingPhotos#localBaseUrl}); empty off a page. */
    protected String requestOrigin() {
        return BrandingPhotos.localBaseUrl();
    }

    /** Seam: one ajax callback param for the page. */
    protected void publish(final String name, final Object value) {
        PageFeedback.callbackParam(name, value);
    }
}
