package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Trip;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CreateInvalidationRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import org.paulsens.trip.cache.Cached;

/**
 * Managed media, exposed to pages as {@code #{media}}.
 *
 * <p>Uploads go to S3 and are served to visitors by CloudFront at {@code TRIP_MEDIA_BASE_URL}; this app is
 * never in the read path for serving a file, only for managing one. Every upload sets a long {@code
 * Cache-Control} so the CDN and browsers actually keep the file, which is the entire point of moving these off
 * the container.
 *
 * <p>When {@code TRIP_MEDIA_BUCKET} is unset -- local runs and tests -- uploads are refused with a clear
 * message and everything else still works. That keeps the admin page renderable without AWS, the same way the
 * settings page works against fake persistence.
 *
 * <p>The S3 client here is the SYNCHRONOUS one, unlike the async clients used elsewhere. An upload is a
 * user-initiated action on a request thread that has to block until the file is stored anyway (there is nothing
 * useful to do concurrently, and the outcome must be reported in the response), so the async client's
 * complexity would buy nothing.
 */
@Slf4j
@Named("media")
@ApplicationScoped
public class MediaCommands {

    static final String BUCKET_VAR = "TRIP_MEDIA_BUCKET";
    static final String BASE_URL_VAR = "TRIP_MEDIA_BASE_URL";
    static final String DISTRIBUTION_VAR = "TRIP_MEDIA_DISTRIBUTION";
    /** A day. Long enough to be worth caching; short enough that a same-key replacement rolls out on its own. */
    private static final long CACHE_SECONDS = 86_400L;

    private volatile S3Client s3;
    private volatile CloudFrontClient cloudFront;
    private final ConfigCommands config = new ConfigCommands();

    /** @return every media item, newest first, for the admin page. */
    public List<MediaItem> getAll() {
        try {
            return DAO.getInstance().getAllMedia(Cached.NO).stream()
                    .sorted(Comparator.comparing(MediaItem::getUploaded,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        } catch (final RuntimeException ex) {
            log.error("Unable to list media", ex);
            return List.of();
        }
    }

    /**
     * The curated inventory: everything EXCEPT trip-chat photos. The admin page lists this by default —
     * pilgrims sharing phone photos would otherwise swamp the travel guides and flyers an admin actually
     * manages there (its toggle brings them back). Chat photos have their own page per trip.
     */
    public List<MediaItem> getCurated() {
        final List<MediaItem> curated = new ArrayList<>();
        for (final MediaItem item : getAll()) {
            if (!ChatPhotos.isChatSlot(item.getSlot())) {
                curated.add(item);
            }
        }
        return curated;
    }

    /**
     * @return the items placed in {@code slot}, ordered for display. Used by pages (e.g. the home page) so
     *         content is rendered from data instead of hardcoded markup.
     */
    public List<MediaItem> getInSlot(final String slot) {
        try {
            return DAO.getInstance().getMediaInSlot(slot, Cached.YES);
        } catch (final RuntimeException ex) {
            // A page must still render if the media table is unavailable; it just shows nothing in this slot.
            log.error("Unable to list media in slot: " + slot, ex);
            return List.of();
        }
    }

    /**
     * The publicly-visible items in a slot: {@code hidden} rows are excluded, and when {@code maxAgeDays} is
     * positive, so are rows uploaded longer ago than that (or with no upload date at all -- an age window
     * only makes sense against a known age). Used by the landing page's Documents section, where files
     * expire from view three months after upload.
     */
    public List<MediaItem> getVisibleInSlot(final String slot, final int maxAgeDays) {
        final LocalDateTime cutoff = maxAgeDays > 0 ? LocalDateTime.now().minusDays(maxAgeDays) : null;
        return getInSlot(slot).stream()
                .filter(item -> !item.getHidden())
                .filter(item -> cutoff == null
                        || (item.getUploaded() != null && !item.getUploaded().isBefore(cutoff)))
                .toList();
    }

    /**
     * The curated items an editor could place into {@code slot}: everything managed on the admin media page
     * (chat photos excluded with the rest of the curated filter) that is not already in the slot and not
     * flagged {@code hidden} -- placing a hidden item on a public section would be a silent no-op, so the
     * picker does not offer one. Sorted by title for the dropdown.
     */
    public List<MediaItem> getSelectableForSlot(final String slot) {
        return getCurated().stream()
                .filter(item -> !item.getHidden())
                .filter(item -> slot == null || !slot.trim().equalsIgnoreCase(item.getSlot()))
                .sorted(Comparator.comparing(MediaCommands::titleKey))
                .toList();
    }

    private static String titleKey(final MediaItem item) {
        return item.getTitle() == null ? "" : item.getTitle().toLowerCase(Locale.ROOT);
    }

    /**
     * Moves an existing item into {@code slot}, after the slot's current rows, and restarts its upload
     * clock. Bumping {@code uploaded} is deliberate and unique to this path: slot listings age items out a
     * configured number of days after that date, so publishing a months-old file into a section must count
     * from the moment it was placed there or it would never appear at all. (Metadata edits preserve
     * {@code uploaded} for exactly the opposite reason -- see {@link #update}.)
     */
    public boolean assignToSlot(final String id, final String slot, final String actor) {
        final MediaItem existing = getForEdit(id);
        if (existing == null || slot == null || slot.isBlank()) {
            return false;
        }
        final String cleanSlot = slot.trim();
        final int position = getInSlot(cleanSlot).stream().mapToInt(MediaItem::getPosition).max().orElse(-1) + 1;
        final MediaItem updated = new MediaItem(existing.getId(), existing.getS3Key(), existing.getTitle(),
                existing.getDescription(), existing.getContentType(), existing.getSize(), cleanSlot, position,
                LocalDateTime.now(), existing.getUploadedBy(), existing.getSmallKey(), existing.getHidden());
        try {
            if (!DAO.getInstance().saveMedia(updated)) {
                return false;
            }
        } catch (final RuntimeException ex) {
            log.error("Unable to move media " + id + " into slot " + cleanSlot, ex);
            return false;
        }
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .currentActor(actor)
                .target(AuditEventBuilder.TARGET_MEDIA, updated.getS3Key())
                .message("Placed " + updated.getS3Key() + " in slot " + cleanSlot
                        + (cleanSlot.equalsIgnoreCase(existing.getSlot()) ? "" : " (was " + existing.getSlot() + ")"))
                .log();
        return true;
    }

    /** One landing-page album: a qualifying trip and its publicly-visible chat photos. */
    public record TripAlbum(Trip trip, List<MediaItem> photos) implements Serializable {
    }

    /**
     * The landing page's picture albums, newest trip first. A trip qualifies when it is publicly listed
     * ({@code openToPublic} -- private trips are unlisted everywhere, including here), has started (in
     * progress is fine, not-yet-started is not), ended within the window, and has at least {@code minPhotos}
     * visible photos in its chat-photo slot. Everything reads from caches (trip index + media hash), so this
     * is safe on a public page render.
     */
    public List<TripAlbum> getHomeAlbums(final int windowDays, final int minPhotos) {
        final LocalDateTime now = LocalDateTime.now();
        try {
            return DAO.getInstance().getActiveTrips(now.minusDays(windowDays), Cached.YES).stream()
                    .filter(trip -> Boolean.TRUE.equals(trip.getOpenToPublic()))
                    .filter(trip -> trip.getStartDate() != null && !trip.getStartDate().isAfter(now))
                    .map(this::toAlbum)
                    .filter(album -> album.photos().size() >= minPhotos)
                    .sorted(Comparator.comparing(album -> album.trip().getStartDate(),
                            Comparator.reverseOrder()))
                    .toList();
        } catch (final RuntimeException ex) {
            // The landing page must render without its picture section rather than fail with it.
            log.error("Unable to build home-page albums", ex);
            return List.of();
        }
    }

    private TripAlbum toAlbum(final Trip trip) {
        return new TripAlbum(trip, getVisibleInSlot(ChatPhotos.slotFor(trip.getId()), 0));
    }

    /**
     * {@link #getHomeAlbums(int, int)} driven by a "Photo Albums" programmatic content instance: blank or
     * unparsable properties fall back to the site-wide settings, so an empty instance behaves like v1 did.
     */
    public List<TripAlbum> getHomeAlbumsFor(final ContentInstance instance) {
        if (instance == null) {
            return List.of();
        }
        final int window = parsePositive(instance.getValues().get("windowDays"),
                config.getInt(KnownSettings.HOME_PHOTOS_WINDOW_DAYS));
        final int minPhotos = parsePositive(instance.getValues().get("minPhotos"),
                config.getInt(KnownSettings.HOME_PHOTOS_MIN_COUNT));
        return getHomeAlbums(window, minPhotos);
    }

    /**
     * One media item IF it publicly exists: null when unknown or hidden. For the "File" programmatic
     * fragment, which must render nothing (not a broken row) for a hidden or deleted document. Reads the
     * media cache -- safe on a public page render.
     */
    public MediaItem getVisible(final String id) {
        final MediaItem item = get(id);
        return item == null || item.getHidden() ? null : item;
    }

    private static int parsePositive(final String raw, final int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            final int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (final NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * Flips one item's public visibility. A write-through save under the same row id, so the cache stays
     * coherent; members-only pages keep showing the item either way (only public pages filter on it).
     */
    public boolean setHidden(final String id, final boolean hidden, final String actor) {
        final MediaItem existing = getForEdit(id);
        if (existing == null) {
            return false;
        }
        if (existing.getHidden() == hidden) {
            return true;
        }
        final MediaItem updated = existing.withHidden(hidden);
        try {
            if (!DAO.getInstance().saveMedia(updated)) {
                return false;
            }
        } catch (final RuntimeException ex) {
            log.error("Unable to save visibility change for media: " + id, ex);
            return false;
        }
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .currentActor(actor)
                .target(AuditEventBuilder.TARGET_MEDIA, updated.getS3Key())
                .message((hidden ? "Hid " : "Unhid ") + updated.getS3Key() + " on public pages")
                .log();
        return true;
    }

    /** @return the public URL a visitor uses for this item. */
    public String getUrl(final MediaItem item) {
        return (item == null) ? null : baseUrl() + "/" + item.getS3Key();
    }

    /** @return whether uploads are possible (i.e. a bucket is configured). False locally and in tests. */
    public boolean isUploadEnabled() {
        return bucket() != null;
    }

    /**
     * Stores the bytes in S3 and records the metadata row.
     *
     * @param key      the object key, e.g. {@code downloads/guide.pdf}. Doubles as the file's public path.
     * @return true when both the object and its row were written.
     */
    public boolean upload(final String key, final InputStream content, final long size, final String contentType,
            final String title, final String description, final String slot, final int position,
            final String uploadedBy) {
        return upload(key, content, size, contentType, title, description, slot, position, uploadedBy, null);
    }

    /** As {@link #upload(String, InputStream, long, String, String, String, String, int, String)}, with the
     *  uploader's public-visibility choice ({@code null} = visible). */
    public boolean upload(final String key, final InputStream content, final long size, final String contentType,
            final String title, final String description, final String slot, final int position,
            final String uploadedBy, final Boolean hidden) {
        final String bucket = bucket();
        if (bucket == null) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Uploads unavailable",
                    BUCKET_VAR + " is not configured, so there is nowhere to put the file.");
            return false;
        }
        final String cleanKey = normalizeKey(key);
        if (cleanKey == null) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Not uploaded", "A file name is required.");
            return false;
        }
        try {
            s3().putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(cleanKey)
                            .contentType(contentType)
                            .contentLength(size)
                            // Set at upload time, because S3 serves object metadata as response headers and
                            // CloudFront honours them. Without this the CDN would still cache, but browsers
                            // would keep re-validating a file that almost never changes.
                            .cacheControl("public, max-age=" + CACHE_SECONDS)
                            .build(),
                    RequestBody.fromInputStream(content, size));
        } catch (final RuntimeException ex) {
            log.error("Unable to store media object: " + cleanKey, ex);
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Not uploaded", ex.getMessage());
            return false;
        }

        final MediaItem item = new MediaItem(UUID.randomUUID().toString(), cleanKey,
                (title == null || title.isBlank()) ? cleanKey : title.trim(),
                description, contentType, size, slot, position, LocalDateTime.now(), uploadedBy, null, hidden);
        try {
            if (!DAO.getInstance().saveMedia(item)) {
                // The object is stored but unlisted: say so plainly rather than reporting success, because the
                // file exists at its URL and a retry would upload it again.
                TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Partly uploaded",
                        "The file was stored but could not be recorded. It is reachable at its URL.");
                return false;
            }
        } catch (final RuntimeException ex) {
            log.error("Stored object but failed to save its media row: " + cleanKey, ex);
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Partly uploaded", ex.getMessage());
            return false;
        }
        // Announce the change; whoever cares about this prefix reacts. See MediaEvents.
        MediaEvents.fire(MediaEvents.Change.ADDED, cleanKey);
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .currentActor(uploadedBy)
                .target(AuditEventBuilder.TARGET_MEDIA, cleanKey)
                .message("Uploaded " + cleanKey + " (" + size + " bytes)")
                .log();
        return true;
    }

    /**
     * The read the MUTATION helpers start from ({@code assignToSlot}/{@code setHidden}/{@code update}):
     * always fresh, because each re-saves the row it read -- a near-cached copy could resurrect fields
     * another edit just changed. Display lookups stay on {@link #get}.
     */
    private MediaItem getForEdit(final String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return DAO.getInstance().getMedia(id, Cached.NO).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to look up media for editing: " + id, ex);
            return null;
        }
    }

    /** One item by row id, for the edit page. */
    public MediaItem get(final String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return DAO.getInstance().getMedia(id, Cached.YES).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to look up media: " + id, ex);
            return null;
        }
    }

    /**
     * Saves edited metadata, moving the stored object when the key changed.
     *
     * <p>{@code uploaded} is deliberately NOT touched: it records when the BYTES were last written, so editing
     * a title does not make a five-year-old file look new. Who changed the metadata and when is answered by the
     * audit trail, which records every one of these with an actor.
     *
     * <p>Renaming is not a metadata change -- the key IS the public URL. The object is copied to the new key
     * and the old one deleted, and anything already pointing at the old URL (a sent email, a bookmark, another
     * site) breaks. The page says so before you do it.
     *
     * @return true if the row was saved.
     */
    public boolean update(final String id, final String newKey, final String title, final String description,
            final String slot, final Integer position, final String editedBy) {
        final MediaItem existing = getForEdit(id);
        return update(id, newKey, title, description, slot, position,
                existing != null && existing.getHidden(), editedBy);
    }

    /** As the shorter {@code update}, with the public-visibility flag (the admin editor's toggle). */
    public boolean update(final String id, final String newKey, final String title, final String description,
            final String slot, final Integer position, final boolean hidden, final String editedBy) {
        final MediaItem existing = getForEdit(id);
        if (existing == null) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Not saved", "No such media item.");
            return false;
        }
        final String cleanKey = (newKey == null || newKey.isBlank())
                ? existing.getS3Key() : normalizeKey(newKey);
        final boolean renamed = !cleanKey.equals(existing.getS3Key());
        if (renamed && !moveObject(existing.getS3Key(), cleanKey)) {
            return false;
        }

        final MediaItem updated = new MediaItem(existing.getId(), cleanKey, title, description,
                existing.getContentType(), existing.getSize(), slot,
                (position == null) ? existing.getPosition() : position,
                existing.getUploaded(), existing.getUploadedBy(), existing.getSmallKey(), hidden);
        try {
            if (!DAO.getInstance().saveMedia(updated)) {
                TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Not saved", "The save failed.");
                return false;
            }
        } catch (final RuntimeException ex) {
            log.error("Unable to save media row: " + id, ex);
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Not saved", ex.getMessage());
            return false;
        }
        // A rename is a remove-then-add as far as anything watching a path prefix is concerned (see
        // MediaEvents): profile-photo presence is keyed by path, so a moved file must update both ends.
        if (renamed) {
            MediaEvents.fire(MediaEvents.Change.REMOVED, existing.getS3Key());
            MediaEvents.fire(MediaEvents.Change.ADDED, cleanKey);
        }
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .currentActor(editedBy)
                .target(AuditEventBuilder.TARGET_MEDIA, cleanKey)
                .message(renamed
                        ? "Renamed " + existing.getS3Key() + " to " + cleanKey + " and edited its details"
                        : "Edited details for " + cleanKey)
                .log();
        return true;
    }

    /** Copies the object to its new key and removes the old one. S3 has no rename. */
    private boolean moveObject(final String from, final String to) {
        final String bucket = bucket();
        if (bucket == null) {
            // No bucket configured (local mode): the row can still be renamed, there is just no object to move.
            return true;
        }
        try {
            s3().copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket).sourceKey(from)
                    .destinationBucket(bucket).destinationKey(to)
                    .build());
        } catch (final RuntimeException ex) {
            log.error("Unable to copy " + from + " to " + to, ex);
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Not renamed",
                    "The stored file could not be copied to the new name; nothing was changed.");
            return false;
        }
        try {
            s3().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(from).build());
        } catch (final RuntimeException ex) {
            // The copy succeeded, so the new URL works. A leftover object at the old key is untidy, and it also
            // means the OLD url keeps working -- worth reporting, not worth failing the rename for.
            log.error("Copied to " + to + " but could not remove " + from, ex);
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_WARN, "Renamed, old copy remains",
                    "The file is available at its new name, but the old one could not be removed.");
        }
        return true;
    }

    /** @return the public URL for an arbitrary media key. */
    public String publicUrl(final String key) {
        return baseUrl() + "/" + key;
    }

    /**
     * Stores raw bytes at a key with an explicit cache policy — the primitive under chat photos, which need a
     * SHORTER cache than curated media (a moderated-away photo must actually disappear) and, for the
     * full-size rendition, a download disposition (its URL exists only to be the "Download full size" link,
     * and {@code img} tags ignore the disposition, so the one object serves both uses).
     *
     * <p>No faces message on failure: callers are XHR endpoints that report errors as JSON, not pages.
     *
     * @return true when stored; false when no bucket is configured or the put failed.
     */
    public boolean putObject(final String key, final byte[] bytes, final String contentType,
            final long cacheSeconds, final boolean asDownload) {
        final String bucket = bucket();
        if (bucket == null) {
            return false;
        }
        try {
            final PutObjectRequest.Builder put = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .contentLength((long) bytes.length)
                    .cacheControl("public, max-age=" + cacheSeconds);
            if (asDownload) {
                put.contentDisposition("attachment");
            }
            s3().putObject(put.build(), RequestBody.fromBytes(bytes));
            return true;
        } catch (final RuntimeException ex) {
            log.error("Unable to store object: " + key, ex);
            return false;
        }
    }

    /**
     * Best-effort CloudFront invalidation of the given paths (each starting with {@code /}). Used by
     * chat-photo moderation so a removed photo's cached copy dies with the object. No-op unless
     * {@code TRIP_MEDIA_DISTRIBUTION} names the distribution; a failure is logged, not thrown — the objects
     * are already gone from S3, and a CDN hiccup must not abort the moderation that removed them.
     */
    public void invalidateCdn(final List<String> paths) {
        final String distribution = env(DISTRIBUTION_VAR);
        if (distribution == null || paths == null || paths.isEmpty()) {
            return;
        }
        try {
            cloudFront().createInvalidation(CreateInvalidationRequest.builder()
                    .distributionId(distribution)
                    .invalidationBatch(b -> b
                            .callerReference("chat-photo-" + System.currentTimeMillis())
                            .paths(p -> p.items(paths).quantity(paths.size())))
                    .build());
        } catch (final RuntimeException ex) {
            log.error("CloudFront invalidation failed for {} path(s); cached copies expire on their TTL",
                    paths.size(), ex);
        }
    }

    private CloudFrontClient cloudFront() {
        CloudFrontClient client = cloudFront;
        if (client == null) {
            synchronized (this) {
                client = cloudFront;
                if (client == null) {
                    client = CloudFrontClient.builder()
                            .region(Region.AWS_GLOBAL)
                            .credentialsProvider(DefaultCredentialsProvider.builder().build())
                            .build();
                    cloudFront = client;
                }
            }
        }
        return client;
    }

    /**
     * Removes one object, without a media row in mind (chat-photo renditions and staged-upload cleanup).
     *
     * @return true when S3 accepted the delete; also true with no bucket configured (nothing to remove).
     */
    public boolean deleteObject(final String key) {
        final String bucket = bucket();
        if (bucket == null) {
            return true;
        }
        try {
            s3().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (final RuntimeException ex) {
            log.error("Unable to delete object: " + key, ex);
            return false;
        }
    }

    /**
     * @return one object's bytes; empty when no bucket is configured or the read failed. Exists for the rare
     *         write path that must READ a stored object back (profile-photo background removal edits the
     *         current rendition) — serving traffic never comes through here, that is the CDN's job.
     */
    public Optional<byte[]> getObject(final String key) {
        final String bucket = bucket();
        if (bucket == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(s3().getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray());
        } catch (final RuntimeException ex) {
            log.error("Unable to read object: " + key, ex);
            return Optional.empty();
        }
    }

    /**
     * @return every object key under {@code prefix}. One listing rather than a probe per key -- callers use
     *         this to build an index in a single round trip. Empty when no bucket is configured.
     */
    public List<String> listKeys(final String prefix) {
        final String bucket = bucket();
        if (bucket == null) {
            return List.of();
        }
        final List<String> keys = new ArrayList<>();
        s3().listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
                .contents().forEach(obj -> keys.add(obj.key()));
        return keys;
    }

    /** Removes the row and the object. S3 versioning keeps the bytes recoverable. */
    public boolean delete(final String id, final String deletedBy) {
        final Optional<MediaItem> found;
        try {
            found = DAO.getInstance().getMedia(id, Cached.NO);
        } catch (final RuntimeException ex) {
            log.error("Unable to look up media for delete: " + id, ex);
            return false;
        }
        if (found.isEmpty()) {
            return false;
        }
        final MediaItem item = found.get();
        try {
            if (!DAO.getInstance().deleteMedia(id)) {
                return false;
            }
        } catch (final RuntimeException ex) {
            log.error("Unable to delete media row: " + id, ex);
            return false;
        }
        final String bucket = bucket();
        if (bucket != null) {
            try {
                s3().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(item.getS3Key()).build());
            } catch (final RuntimeException ex) {
                // The row is gone, so the site no longer references it; an orphaned object is a tidiness
                // problem, not a correctness one. Report it rather than failing the whole delete.
                log.error("Deleted media row but not the object: " + item.getS3Key(), ex);
                TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_WARN, "Partly deleted",
                        "Removed from the site, but the stored file could not be deleted.");
            }
        }
        MediaEvents.fire(MediaEvents.Change.REMOVED, item.getS3Key());
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .currentActor(deletedBy)
                .target(AuditEventBuilder.TARGET_MEDIA, item.getS3Key())
                .message("Deleted " + item.getS3Key())
                .log();
        return true;
    }

    /**
     * Drops the cached inventory so the next read comes from the table.
     *
     * <p>For rows written WITHOUT going through this bean: a maintenance script, a bulk import, an edit in the
     * console. The cache holds the whole table under one 'loaded' marker and lives in Valkey, so it survives
     * deploys and restarts -- rows written behind its back stay invisible for a day until the soft TTL expires.
     * After the media reconcile the table held 362 rows and the page showed 30, and no amount of redeploying
     * changed that.
     *
     * @return how many items are visible after the refresh, so the page can say what it found.
     */
    public int refreshFromDatabase() {
        DAO.getInstance().clearMediaCache();
        final int count = getAll().size();
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .actor(AuditActor.current())
                .message("Refreshed the media inventory from the table; " + count + " items")
                .log();
        return count;
    }

    /** Strips leading slashes and lowercases nothing: keys are case-sensitive and become part of the URL. */
    static String normalizeKey(final String key) {
        if (key == null) {
            return null;
        }
        final String trimmed = key.trim().replaceAll("^/+", "");
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String baseUrl() {
        final String url = env(BASE_URL_VAR);
        // Falling back to a relative path keeps local pages working (they resolve against the app itself).
        return (url == null) ? "" : url.replaceAll("/+$", "");
    }

    private static String bucket() {
        return env(BUCKET_VAR);
    }

    static String env(final String name) {
        final String sysProp = System.getProperty(name.toLowerCase(Locale.ROOT).replace('_', '.'));
        final String value = (sysProp == null || sysProp.isBlank()) ? System.getenv(name) : sysProp;
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private S3Client s3() {
        S3Client client = s3;
        if (client == null) {
            synchronized (this) {
                client = s3;
                if (client == null) {
                    client = S3Client.builder()
                            .region(Region.of(regionName()))
                            .credentialsProvider(DefaultCredentialsProvider.builder().build())
                            .build();
                    s3 = client;
                }
            }
        }
        return client;
    }

    private static String regionName() {
        final String region = env("TRIP_DYNAMO_REGION");
        return (region == null) ? "us-west-2" : region;
    }
}
