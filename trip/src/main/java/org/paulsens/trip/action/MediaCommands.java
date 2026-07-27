package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.MediaItem;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

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
    /** A day. Long enough to be worth caching; short enough that a same-key replacement rolls out on its own. */
    private static final long CACHE_SECONDS = 86_400L;

    private volatile S3Client s3;

    /** @return every media item, newest first, for the admin page. */
    public List<MediaItem> getAll() {
        try {
            return DAO.getInstance().getAllMedia().join().stream()
                    .sorted(Comparator.comparing(MediaItem::getUploaded,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        } catch (final RuntimeException ex) {
            log.error("Unable to list media", ex);
            return List.of();
        }
    }

    /**
     * @return the items placed in {@code slot}, ordered for display. Used by pages (e.g. the home page) so
     *         content is rendered from data instead of hardcoded markup.
     */
    public List<MediaItem> getInSlot(final String slot) {
        try {
            return DAO.getInstance().getMediaInSlot(slot).join();
        } catch (final RuntimeException ex) {
            // A page must still render if the media table is unavailable; it just shows nothing in this slot.
            log.error("Unable to list media in slot: " + slot, ex);
            return List.of();
        }
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
                description, contentType, size, slot, position, LocalDateTime.now(), uploadedBy);
        try {
            if (!DAO.getInstance().saveMedia(item).join()) {
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
        Audit.log(uploadedBy, "MEDIA", "Uploaded " + cleanKey + " (" + size + " bytes)");
        return true;
    }

    /** @return the public URL for an arbitrary media key. */
    public String publicUrl(final String key) {
        return baseUrl() + "/" + key;
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
            found = DAO.getInstance().getMedia(id).join();
        } catch (final RuntimeException ex) {
            log.error("Unable to look up media for delete: " + id, ex);
            return false;
        }
        if (found.isEmpty()) {
            return false;
        }
        final MediaItem item = found.get();
        try {
            if (!DAO.getInstance().deleteMedia(id).join()) {
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
        Audit.log(deletedBy, "MEDIA", "Deleted " + item.getS3Key());
        return true;
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

    private static String env(final String name) {
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
