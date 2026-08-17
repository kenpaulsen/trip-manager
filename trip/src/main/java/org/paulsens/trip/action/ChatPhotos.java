package org.paulsens.trip.action;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.media.ChatPhotoStaging;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.media.ProcessedPhoto;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatAttachment;
import org.paulsens.trip.cache.Cached;

/**
 * Chat photo storage, exposed to pages as {@code #{chatPhotos}}. Sits between the upload servlet / send path
 * and the raw stores: it processes an upload into its two renditions ({@link PhotoProcessor}), stores them,
 * and remembers who staged what ({@link ChatPhotoStaging}) so a later send can only reference photos its
 * author actually uploaded for that trip.
 *
 * <p><b>Two stores, chosen by configuration, never inferred:</b> with {@code TRIP_MEDIA_BUCKET} set the bytes
 * go to S3 (served by CloudFront, like all managed media); without it — local runs, unit tests, webtests —
 * they go to a capped in-memory map served back by the upload servlet's GET. The in-memory store is what
 * makes the whole flow exercisable with no AWS, which the media admin page never needed (its uploads simply
 * refuse locally) but chat photos do: a webtest must attach, send, render and download.
 *
 * <p>Chat photos get a ONE HOUR CDN/browser cache, not the day used for curated media: removal must actually
 * remove (the design doc's moderation objection to long caches), and on delete the CloudFront paths are also
 * invalidated when {@code TRIP_MEDIA_DISTRIBUTION} is configured. Without that env var, deletion still
 * deletes from S3 and the cache runs out within the hour.
 */
@Slf4j
@Named("chatPhotos")
@ApplicationScoped
public class ChatPhotos {

    /** Where every chat photo lives: {@code chat/{tripId}/{stamp}-{rand}[.ext|-small.ext]}. */
    public static final String KEY_PREFIX = "chat/";
    /** An hour, so moderation takes effect quickly even where invalidation is not configured. */
    static final long CACHE_SECONDS = 3_600L;
    /** Local-store budget. Enough for a long local session; evicts oldest first. */
    static final long LOCAL_STORE_MAX_BYTES = 200L * 1024 * 1024;

    private static volatile ChatPhotos shared;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneOffset.UTC);

    private final MediaCommands media;
    private final PhotoProcessor processor = new PhotoProcessor();
    private final ChatPhotoStaging staging;
    private final SecureRandom random = new SecureRandom();
    /** Instance rather than the constant so a test can exercise eviction without 200 MB of fixtures. */
    private long localStoreMaxBytes = LOCAL_STORE_MAX_BYTES;

    /** Local-mode object store: key -> bytes+type. Guarded by its own monitor; access is rare and brief. */
    private final Map<String, LocalObject> localObjects = new LinkedHashMap<>();
    private long localBytes;

    private record LocalObject(byte[] bytes, String contentType) {
    }

    /** One staged photo, as the upload endpoint reports it back to the composer. */
    public record StagedPhoto(
            String key, String smallKey, String contentType, long size, int width, int height) {
    }

    public ChatPhotos() {
        this(new MediaCommands());
    }

    ChatPhotos(final MediaCommands media) {
        this(media, new ChatPhotoStaging());
    }

    ChatPhotos(final MediaCommands media, final ChatPhotoStaging staging) {
        this.media = media;
        this.staging = staging;
    }

    void localStoreMaxBytesForTest(final long maxBytes) {
        this.localStoreMaxBytes = maxBytes;
    }

    /**
     * The shared instance. ONE static, deliberately NOT the FacesContext/application-map dance
     * {@code ChatCommands.getChatCommands()} does: the upload servlet has no FacesContext and the JSF send
     * does, so a context-sensitive lookup hands them two different instances — and with the staging
     * registry split across two maps, every photo "is no longer available" the moment someone presses Send.
     * That is not hypothetical; it is how the first end-to-end run failed.
     */
    public static ChatPhotos getChatPhotos() {
        ChatPhotos instance = shared;
        if (instance == null) {
            synchronized (ChatPhotos.class) {
                instance = shared;
                if (instance == null) {
                    instance = new ChatPhotos();
                    shared = instance;
                }
            }
        }
        return instance;
    }

    /** @return whether photos are stored remotely (S3/CloudFront) rather than in this process. */
    public boolean isRemoteStore() {
        return media.isUploadEnabled();
    }

    /**
     * The URL prefix a browser puts in front of a photo key, ending in {@code /}. Null in local mode, where
     * the page uses the upload servlet's GET path (context-relative, which only the page can resolve).
     */
    public String getPublicBase() {
        return isRemoteStore() ? media.publicUrl("") : null;
    }

    /**
     * The same base, resolved for a rendering JSF page: the CDN when configured, this deployment's
     * {@code /chat-photos/} servlet path otherwise. Only callable where a FacesContext exists — the trip
     * photos page — which is why {@link #getPublicBase} (nullable, context-free) also exists for the JS.
     */
    public String getPhotoPageBase() {
        final String base = getPublicBase();
        if (base != null) {
            return base;
        }
        final FacesContext ctx = FacesContext.getCurrentInstance();
        final String contextPath = ctx == null ? "" : ctx.getExternalContext().getRequestContextPath();
        return contextPath + "/chat-photos/";
    }

    /** EL-friendly alias of {@link #slotFor} — JSFT expressions cannot reach statics. */
    public String slotForTrip(final String tripId) {
        return slotFor(tripId);
    }

    /**
     * Processes and stores one upload, staging it for a later send.
     *
     * @throws PhotoRejectedException with a user-facing message when the bytes are refused.
     * @throws IllegalStateException when the store refused the bytes (S3 failure) — an operational error,
     *         distinct from a rejected photo.
     */
    public StagedPhoto stage(final String tripId, final Person.Id uploader, final byte[] bytes) {
        return stage(tripId, uploader, bytes, null);
    }

    /**
     * As {@link #stage(String, Person.Id, byte[])}, with an optional crop applied before the renditions are
     * built. Null crops nothing; an animated GIF ignores the rect (a cropped frame would drop the animation).
     */
    public StagedPhoto stage(final String tripId, final Person.Id uploader, final byte[] bytes,
            final PhotoProcessor.CropRect rect) {
        sweepExpired();
        final ProcessedPhoto photo = processor.process(bytes, rect);
        final String base = KEY_PREFIX + tripId + "/" + STAMP.format(Instant.now()) + "-" + randomSuffix();
        final String fullKey = base + "." + photo.fullExtension();
        final String smallKey = photo.smallIsFull() ? fullKey : base + "-small." + photo.smallExtension();
        // Full first, and the small rendition only after the full one stored: a photo whose display copy
        // exists but whose download 404s would look fine right up until someone wants it.
        store(fullKey, photo.fullBytes(), photo.fullContentType(), true);
        if (!photo.smallIsFull()) {
            store(smallKey, photo.smallBytes(), photo.smallContentType(), false);
        }
        staging.put(new ChatPhotoStaging.Staged(tripId, uploader.getValue(), fullKey, smallKey,
                photo.fullContentType(), photo.fullBytes().length, photo.width(), photo.height(),
                Instant.now()));
        return new StagedPhoto(fullKey, smallKey, photo.fullContentType(), photo.fullBytes().length,
                photo.width(), photo.height());
    }

    /**
     * Resolves send-time attachment references against the staging registry. Unknown, expired, foreign-trip
     * or foreign-uploader keys throw — the composer can only get into that state by being tampered with, so
     * the send should fail loudly rather than quietly drop a photo.
     *
     * <p>Nothing is consumed here: validation happens before the message is saved, consumption after (see
     * {@link #consume}), so a send that fails downstream leaves the attachments attached in the composer.
     */
    public List<ChatAttachment> resolveStaged(final String tripId, final Person.Id uploader,
            final List<AttachmentRef> refs) {
        final List<ChatAttachment> attachments = new ArrayList<>();
        for (final AttachmentRef ref : refs) {
            final ChatPhotoStaging.Staged staged = staging.peek(ref.key(), tripId, uploader.getValue())
                    .orElseThrow(() -> new PhotoRejectedException(
                            "An attached photo is no longer available. Remove it and attach it again."));
            attachments.add(new ChatAttachment("image", staged.key(), staged.contentType(), staged.size(),
                    staged.width(), staged.height(), staged.smallKey(), blankToNull(ref.title()),
                    ref.hidden()));
        }
        return attachments;
    }

    /**
     * A send-time reference: the staged full-rendition key plus the title and public-visibility choice made
     * in the attach dialog ({@code hidden} null = visible; like the title, it is send-time metadata, not
     * upload-time state).
     */
    public record AttachmentRef(String key, String title, Boolean hidden) {
        /** Compatibility constructor predating the {@code hidden} flag. */
        public AttachmentRef(final String key, final String title) {
            this(key, title, null);
        }
    }

    /**
     * Parses the composer's serialised tray — {@code [{"key":"...","title":"..."}]} — tolerantly: the JSF
     * send path stores it as an opaque viewScope string, and malformed content (an interrupted postback, a
     * hand-edited request) becomes an empty list rather than an error. The staging registry re-validates
     * every surviving entry anyway.
     */
    public static List<AttachmentRef> parseRefs(final String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            final JsonNode root = REFS_MAPPER.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            final List<AttachmentRef> refs = new ArrayList<>();
            for (final JsonNode node : root) {
                final JsonNode key = node.get("key");
                if (key != null && key.isTextual()) {
                    final JsonNode title = node.get("title");
                    final JsonNode hidden = node.get("hidden");
                    refs.add(new AttachmentRef(key.asText(),
                            title != null && title.isTextual() ? title.asText() : null,
                            hidden != null && hidden.isBoolean() ? hidden.asBoolean() : null));
                }
            }
            return refs;
        } catch (final JacksonException ex) {
            log.warn("Unparseable attachment tray; sending without photos: {}", ex.getMessage());
            return List.of();
        }
    }

    private static final ObjectMapper REFS_MAPPER =
            new ObjectMapper();

    /** Marks attachments as belonging to a saved message; staged entries are single-use. */
    public void consume(final List<ChatAttachment> attachments) {
        for (final ChatAttachment attachment : attachments) {
            staging.consume(attachment.getS3Key());
        }
    }

    /** The media slot every photo from this trip's chat is placed in — one slot per trip, one album page. */
    public static String slotFor(final String tripId) {
        return "tripChat-" + tripId;
    }

    /** @return whether this slot (nullable) is a trip-chat album slot rather than curated media. */
    public static boolean isChatSlot(final String slot) {
        return slot != null && slot.startsWith("tripChat-");
    }

    /**
     * Records one media row per sent photo, which is what puts them on the trip's media page. Called AFTER
     * the message is durably saved; a row that fails to save is logged and skipped rather than failing the
     * send — the message is already delivered, and the photo still renders in the chat from its keys.
     */
    public void recordAlbumRows(final String tripId, final String tripTitle, final Person.Id author,
            final String authorName, final List<ChatAttachment> attachments,
            final AuditActor actor) {
        for (final ChatAttachment attachment : attachments) {
            final MediaItem item = new MediaItem(UUID.randomUUID().toString(),
                    attachment.getS3Key(), titleFor(attachment),
                    "Uploaded by " + authorName + " in the " + tripTitle + " chat",
                    attachment.getContentType(), attachment.getSize(), slotFor(tripId), 0,
                    LocalDateTime.now(), author.getValue(), attachment.getThumbKey(),
                    attachment.isHidden());
            try {
                if (DAO.getInstance().saveMedia(item)) {
                    MediaEvents.fire(MediaEvents.Change.ADDED, attachment.getS3Key());
                } else {
                    log.error("Chat photo sent but its media row was not saved: {}", attachment.getS3Key());
                }
            } catch (final RuntimeException ex) {
                log.error("Chat photo sent but its media row was not saved: " + attachment.getS3Key(), ex);
            }
        }
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .actor(actor)
                .target(AuditEventBuilder.TARGET_MEDIA, slotFor(tripId))
                .message(attachments.size() + " chat photo(s) added to the " + tripTitle + " album")
                .log();
    }

    /**
     * The full moderation cascade for a removed message's photos: stored renditions, CDN cache, media rows.
     * Row deletion works off the cached media inventory by key — chat rows are always written with the full
     * rendition as {@code s3Key}, so that is the join.
     */
    public void deleteEverywhere(final List<ChatAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        deleteRenditions(attachments);
        final Set<String> keys = new HashSet<>();
        for (final ChatAttachment attachment : attachments) {
            keys.add(attachment.getS3Key());
        }
        final List<MediaItem> rows;
        try {
            rows = DAO.getInstance().getAllMedia(Cached.NO);
        } catch (final RuntimeException ex) {
            log.error("Photo objects deleted, but the media rows could not be listed for cleanup", ex);
            return;
        }
        for (final MediaItem row : rows) {
            if (keys.contains(row.getS3Key())) {
                deleteAlbumRow(row);
            }
        }
        // Belt-and-braces for a photo whose media row was never recorded: the REMOVED event above only fires
        // per existing row, so its comment thread would survive the photo. The purge is idempotent, so
        // double-purging alongside the MediaEvents listener is harmless.
        for (final String key : keys) {
            PhotoChatCommands.purgePhotoThread(key);
        }
    }

    private void deleteAlbumRow(final MediaItem row) {
        try {
            if (DAO.getInstance().deleteMedia(row.getId())) {
                MediaEvents.fire(MediaEvents.Change.REMOVED, row.getS3Key());
            }
        } catch (final RuntimeException ex) {
            log.error("Photo objects deleted, but their media row was not: " + row.getId(), ex);
        }
    }

    private static String titleFor(final ChatAttachment attachment) {
        if (attachment.getCaption() != null && !attachment.getCaption().isBlank()) {
            return attachment.getCaption().trim();
        }
        final String key = attachment.getS3Key();
        return key.substring(key.lastIndexOf('/') + 1);
    }

    /**
     * Removes both renditions of each attachment from the store and invalidates their CDN paths. Used by the
     * admin remove-message cascade; media rows are the caller's problem (they live in MediaCommands).
     */
    public void deleteRenditions(final List<ChatAttachment> attachments) {
        final List<String> paths = new ArrayList<>();
        for (final ChatAttachment attachment : attachments) {
            deleteStored(attachment.getS3Key());
            paths.add("/" + attachment.getS3Key());
            if (attachment.getThumbKey() != null && !attachment.getThumbKey().equals(attachment.getS3Key())) {
                deleteStored(attachment.getThumbKey());
                paths.add("/" + attachment.getThumbKey());
            }
        }
        invalidate(paths);
    }

    /**
     * Seeds one object into the local store, bypassing the photo processor -- for {@code FakeData}'s album
     * photos. Without servable bytes behind each seeded media row, every broken {@code img} on the local
     * landing page 404s into the app's error page, and THOSE are JSF views: eleven broken thumbnails evict
     * the page's own view from Mojarra's logical-view LRU and the first postback dies ViewExpired.
     * (Production never sees this: photos are served by the CDN, a different origin.)
     */
    public void seedLocalObject(final String key, final byte[] bytes, final String contentType) {
        if (!isRemoteStore()) {
            store(key, bytes, contentType, false);
        }
    }

    /** Local-mode read-back, for the upload servlet's GET. Empty when remote or unknown. */
    public Optional<ServedPhoto> localGet(final String key) {
        synchronized (localObjects) {
            final LocalObject stored = localObjects.get(key);
            return stored == null
                    ? Optional.empty() : Optional.of(new ServedPhoto(stored.bytes(), stored.contentType()));
        }
    }

    public record ServedPhoto(byte[] bytes, String contentType) {
    }

    private void store(final String key, final byte[] bytes, final String contentType,
            final boolean asDownload) {
        if (isRemoteStore()) {
            if (!media.putObject(key, bytes, contentType, CACHE_SECONDS, asDownload)) {
                throw new IllegalStateException("The photo could not be stored. Try again.");
            }
            return;
        }
        synchronized (localObjects) {
            localBytes += bytes.length;
            localObjects.put(key, new LocalObject(bytes, contentType));
            final var iterator = localObjects.entrySet().iterator();
            while (localBytes > localStoreMaxBytes && iterator.hasNext()) {
                localBytes -= iterator.next().getValue().bytes().length;
                iterator.remove();
            }
        }
    }

    private void deleteStored(final String key) {
        if (isRemoteStore()) {
            media.deleteObject(key);
            return;
        }
        synchronized (localObjects) {
            final LocalObject removed = localObjects.remove(key);
            if (removed != null) {
                localBytes -= removed.bytes().length;
            }
        }
    }

    /** Deletes the stored objects of staged photos nobody ever sent. Called from the upload path. */
    private void sweepExpired() {
        for (final ChatPhotoStaging.Staged expired : staging.drainExpired()) {
            log.info("Removing never-sent chat photo: {}", expired.key());
            deleteStored(expired.key());
            if (!expired.smallKey().equals(expired.key())) {
                deleteStored(expired.smallKey());
            }
        }
    }

    /** CDN invalidation lives with the other AWS clients in {@link MediaCommands}; local mode has no CDN. */
    private void invalidate(final List<String> paths) {
        if (isRemoteStore()) {
            media.invalidateCdn(paths);
        }
    }

    private String randomSuffix() {
        final char[] alphabet = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
        final StringBuilder suffix = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            suffix.append(alphabet[random.nextInt(alphabet.length)]);
        }
        return suffix.toString();
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
