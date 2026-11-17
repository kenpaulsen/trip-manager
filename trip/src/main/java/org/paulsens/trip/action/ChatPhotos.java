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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.media.LocalObjectStore;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.media.ChatPhotoStaging;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.media.ProcessedPhoto;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatAttachment;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.model.chat.ChatMessage;
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
    /** Trip albums as a source of references; resolved lazily, or set by a test. */
    private AlbumPhotoSource albums;
    /** Who is sending, for the album rules: the request's own caller. */
    private Supplier<Caller> callerSource = Caller::bound;
    private final SecureRandom random = new SecureRandom();
    /** Local-mode object store, the media bucket's stand-in when none is configured. */
    private final LocalObjectStore localStore = new LocalObjectStore(LOCAL_STORE_MAX_BYTES);

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
        localStore.maxBytes(maxBytes);
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
     * <p>An {@code album:{mediaId}} reference ("Choose from Albums") never touches staging: it is checked against
     * the album rules ({@link AlbumPhotoSource#offered} in this trip's scope) and becomes a reference to the
     * original. It CAN fail without tampering (the photo was hidden or removed after it was chosen, or a restored
     * draft outlived it), and fails the send the same way, so the composer says so and keeps the message.
     *
     * <p>Nothing is consumed here: validation happens before the message is saved, consumption after (see
     * {@link #consume}), so a send that fails downstream leaves the attachments attached in the composer.
     */
    public List<ChatAttachment> resolveStaged(final String tripId, final Person.Id uploader,
            final List<AttachmentRef> refs) {
        final List<ChatAttachment> attachments = new ArrayList<>();
        for (final AttachmentRef ref : refs) {
            if (isAlbumRef(ref.key())) {
                attachments.add(albumPhoto(tripId, ref).map(photo -> referenceTo(photo, ref))
                        .orElseThrow(() -> new PhotoRejectedException("A photo chosen from an album is no longer"
                                + " available. Remove it and choose it again.")));
                continue;
            }
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
     * The subset of {@code refs} still claimable by this person for this trip, each with its thumbnail key
     * for a composer preview. The non-throwing sibling of {@link #resolveStaged}: a draft outlives its
     * photos by design (each photo expires 24h after ITS upload), so an expired reference here is ordinary
     * aging to prune quietly, not the tampering the send path fails loudly on. An {@code album:} reference is
     * checked against the album rules instead of staging, and lapses the same quiet way.
     */
    public List<StagedRef> peekStaged(final String tripId, final Person.Id uploader,
            final List<AttachmentRef> refs) {
        final List<StagedRef> out = new ArrayList<>();
        for (final AttachmentRef ref : refs) {
            if (isAlbumRef(ref.key())) {
                albumPhoto(tripId, ref).ifPresent(photo -> out.add(new StagedRef(ref, photo.getDisplayKey())));
            } else {
                staging.peek(ref.key(), tripId, uploader.getValue())
                        .ifPresent(staged -> out.add(new StagedRef(ref, staged.smallKey())));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ references to album photos

    /**
     * What an attachment reference's key starts with when it names a photo ALREADY in a trip album, by its media
     * id, instead of a staged upload ("Choose from Albums" in the chat). Such a photo is attached as a REFERENCE
     * to the original ({@link ChatAttachment#isRef()}), never copied (owner, 2026-11-17).
     */
    public static final String ALBUM_REF_PREFIX = "album:";

    /** Whether a reference's key names an album photo rather than a staged upload. */
    public static boolean isAlbumRef(final String key) {
        return key != null && key.startsWith(ALBUM_REF_PREFIX);
    }

    /**
     * The album row an album reference names, when the person sending may use it in THIS trip's chat: the same
     * question, in the same scope, every other "Choose from Albums" asks ({@link AlbumPhotoSource#offered}: the
     * trip's own organization, an album they may open, nothing kept out of public view). Never staged: the
     * staging registry's sweep deletes the objects of whatever it holds, and these are somebody else's.
     */
    private Optional<MediaItem> albumPhoto(final String tripId, final AttachmentRef ref) {
        return albumSource().offered(callerSource.get(), AlbumPhotoSource.tripScope(tripId),
                ref.key().substring(ALBUM_REF_PREFIX.length()));
    }

    /**
     * The attachment a reference becomes: the ORIGINAL's keys and facts, marked as a reference, with the caption
     * typed for this message. It carries no public-site flag of its own: that choice belongs to the original.
     */
    private ChatAttachment referenceTo(final MediaItem photo, final AttachmentRef ref) {
        final Optional<ChatAttachment> original = originalOf(photo);
        return new ChatAttachment("image", photo.getS3Key(), photo.getContentType(), photo.getSize(),
                original.map(ChatAttachment::getWidth).orElse(null),
                // The DISPLAY key, never a bare getSmallKey(): a photo small enough to need no reduced copy has
                // none in its album row, and an attachment with no thumbnail key is one the chat page cannot draw.
                original.map(ChatAttachment::getHeight).orElse(null), photo.getDisplayKey(),
                blankToNull(ref.title()), null, null, null, photo.getRev(), Boolean.TRUE);
    }

    /**
     * A page whose references show their photos as they are NOW. A reference copies the photo's size, small
     * rendition and {@code rev} once, at send, and nothing indexes references, so when the photo is turned where it
     * was first shared no reference can be rewritten: each is brought up to date here, on the way out, from its
     * media row ({@link ChatAttachment#followingAlbum}). A reference whose photo has NO media row left (the
     * original's message or trip was removed, which removes the row) goes out as a tombstone that stays a reference
     * ({@link ChatAttachment#gone}), so a client that cached the photo drops it rather than keep showing a removed
     * photo. A photo HIDDEN since it was referenced (its sharer or a moderator kept it off the public site,
     * {@link MediaItem#getHidden}) goes out as the same tombstone: {@link AlbumPhotoSource} would no longer offer it,
     * because a reference shows it to another audience, and that rule must not lapse once the reference exists.
     * Nothing is rewritten, so showing the photo again brings the reference back. A row moved out of its trip album
     * to a library slot still counts: the photo is still there. A page with
     * no reference costs nothing; one with any reads the media table once, however many albums its references come
     * from, and when that read fails every reference goes out as it was sent (a failed read must never look like
     * every photo removed).
     */
    public ChatPage withCurrentReferences(final ChatPage page) {
        if (page.getMessages().stream().noneMatch(ChatPhotos::carriesReference)) {
            return page;
        }
        return mediaRowsByKey().map(rows -> withCurrentReferences(page, rows)).orElse(page);
    }

    private static ChatPage withCurrentReferences(final ChatPage page, final Map<String, MediaItem> rows) {
        return page.withMessages(page.getMessages().stream()
                .map(message -> carriesReference(message) ? current(message, rows) : message)
                .toList());
    }

    private static boolean carriesReference(final ChatMessage message) {
        return message.getAttachments().stream().anyMatch(a -> a.isRef() && !a.isDeleted());
    }

    private static ChatMessage current(final ChatMessage message, final Map<String, MediaItem> rows) {
        return message.withAttachments(message.getAttachments().stream()
                .map(a -> a.isRef() && !a.isDeleted() ? current(a, rows.get(a.getS3Key())) : a)
                .toList());
    }

    private static ChatAttachment current(final ChatAttachment ref, final MediaItem row) {
        return row == null || Boolean.TRUE.equals(row.getHidden()) ? ref.gone()
                : ref.followingAlbum(row.getRev(), row.getSize(), row.getDisplayKey());
    }

    /**
     * Every media row by its photo's key (the first, when a key has two), from one read of the media table; EMPTY
     * when the table cannot be read. Empty is not "no rows": it leaves every reference as it was sent, where an
     * empty map would turn every one of them into a tombstone.
     */
    Optional<Map<String, MediaItem>> mediaRowsByKey() {
        try {
            return Optional.of(allMedia().stream()
                    .filter(row -> row.getS3Key() != null)
                    .collect(Collectors.toMap(MediaItem::getS3Key, row -> row, (first, second) -> first)));
        } catch (final RuntimeException ex) {
            log.error("Unable to read the media table to refresh chat references", ex);
            return Optional.empty();
        }
    }

    /** Seam: the whole media table, from the cache. */
    protected List<MediaItem> allMedia() {
        return DAO.getInstance().getAllMedia(Cached.YES);
    }

    /**
     * Seam: the attachment that first carried an album photo, in its own trip's chat, for the dimensions the
     * album row does not hold (a client lays a photo out from them before it arrives). Empty when that message is
     * gone, which leaves the reference without dimensions, as an old photo is.
     */
    protected Optional<ChatAttachment> originalOf(final MediaItem photo) {
        final String key = photo.getS3Key();
        final ChatChannel channel = ChatCommands.getChatCommands().getChannel(tripOfSlot(photo.getSlot()));
        return ChatPhotoEdits.findMessageWithPhoto(DAO.getInstance(), channel, key, null)
                .flatMap(message -> message.getAttachments().stream()
                        .filter(a -> !a.isDeleted() && !a.isRef() && key.equals(a.getS3Key())).findFirst());
    }

    private AlbumPhotoSource albumSource() {
        AlbumPhotoSource source = albums;
        if (source == null) {
            // Resolved lazily: this class is one static instance, not a bean (see getChatPhotos).
            source = org.paulsens.trip.api.Beans.get(AlbumPhotoSource.class);
            albums = source;
        }
        return source;
    }

    void albumsForTest(final AlbumPhotoSource source, final Supplier<Caller> caller) {
        this.albums = source;
        this.callerSource = caller;
    }

    /** The attachments that OWN their photo: everything a delete or an album row may act on. */
    private static List<ChatAttachment> owned(final List<ChatAttachment> attachments) {
        return attachments.stream().filter(attachment -> !attachment.isRef()).toList();
    }

    /** A draft-time view of one still-claimable staged reference: the ref plus its thumbnail key. */
    public record StagedRef(AttachmentRef ref, String smallKey) {
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

    /** The trip id a chat slot names, or null for any other slot. */
    public static String tripOfSlot(final String slot) {
        return isChatSlot(slot) ? slot.substring("tripChat-".length()) : null;
    }

    /** The trip's organization for the album rows (a cached read; an unknown trip stamps nothing). */
    static String orgOfTrip(final String tripId) {
        return DAO.getInstance().getTrip(tripId, Cached.YES).map(Trip::getOrgId).orElse(null);
    }

    /**
     * Records one media row per sent photo, which is what puts them on the trip's media page. Called AFTER
     * the message is durably saved; a row that fails to save is logged and skipped rather than failing the
     * send — the message is already delivered, and the photo still renders in the chat from its keys.
     */
    public void recordAlbumRows(final String tripId, final String tripTitle, final Person.Id author,
            final String authorName, final List<ChatAttachment> sent,
            final AuditActor actor) {
        // A reference adds nothing to this album: the photo already has its row, in the album it was shared in,
        // and a second row for one key is what every by-key lookup and delete would then trip over.
        final List<ChatAttachment> attachments = owned(sent);
        if (attachments.isEmpty()) {
            return;
        }
        // A chat photo belongs to the trip's organization, whatever host the pilgrim sent it from.
        final String orgId = orgOfTrip(tripId);
        for (final ChatAttachment attachment : attachments) {
            final MediaItem item = new MediaItem(UUID.randomUUID().toString(),
                    attachment.getS3Key(), titleFor(attachment),
                    "Uploaded by " + authorName + " in the " + tripTitle + " chat",
                    attachment.getContentType(), attachment.getSize(), slotFor(tripId), 0,
                    LocalDateTime.now(), author.getValue(), attachment.getThumbKey(),
                    attachment.isHidden(), orgId);
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
     * Flips the album row(s) of one chat photo, matched by key the way {@link #deleteEverywhere} matches.
     * A write-through save under the same row id keeps the media cache coherent. True when every matching
     * row saved (none matching is also true: an album row that was never recorded has nothing to flip, and
     * the message copy is what the chat renders).
     */
    public boolean setAlbumHidden(final String tripId, final String s3Key, final boolean hidden) {
        final List<MediaItem> rows;
        try {
            rows = DAO.getInstance().getMediaInSlot(slotFor(tripId), Cached.NO);
        } catch (final RuntimeException ex) {
            log.error("The album rows could not be listed to change a photo's visibility: " + s3Key, ex);
            return false;
        }
        boolean ok = true;
        for (final MediaItem row : rows) {
            if (!s3Key.equals(row.getS3Key()) || row.getHidden() == hidden) {
                continue;
            }
            try {
                ok &= DAO.getInstance().saveMedia(row.withHidden(hidden));
            } catch (final RuntimeException ex) {
                log.error("Unable to save a visibility change for chat photo " + s3Key, ex);
                ok = false;
            }
        }
        return ok;
    }

    /**
     * The album half of a rotation: every album row of this photo gets the new full-size {@code size}, the
     * small rendition's key, and a {@code rev} one higher, so the Photos page and the apps' album listings
     * bust their caches too. Matched by key like {@link #setAlbumHidden}; true when every matching row saved
     * (none matching is also true -- the message copy is what the chat renders).
     */
    public boolean setAlbumRotated(final String tripId, final String s3Key, final long size,
            final String smallKey) {
        final List<MediaItem> rows;
        try {
            rows = DAO.getInstance().getMediaInSlot(slotFor(tripId), Cached.NO);
        } catch (final RuntimeException ex) {
            log.error("The album rows could not be listed to record a photo's rotation: " + s3Key, ex);
            return false;
        }
        boolean ok = true;
        for (final MediaItem row : rows) {
            if (s3Key.equals(row.getS3Key())) {
                ok &= saveRotatedRow(row.withRotated(size, smallKey));
            }
        }
        return ok;
    }

    private static boolean saveRotatedRow(final MediaItem row) {
        try {
            return DAO.getInstance().saveMedia(row);
        } catch (final RuntimeException ex) {
            log.error("Unable to save a rotation for chat photo " + row.getS3Key(), ex);
            return false;
        }
    }

    /** The stored bytes of one photo's full rendition, from S3 or the local store; empty when unavailable. */
    public Optional<byte[]> currentBytes(final String s3Key) {
        if (isRemoteStore()) {
            return media.getObject(s3Key);
        }
        return localStore.get(s3Key);
    }

    /**
     * Overwrites a posted photo's renditions IN PLACE with freshly processed ones -- the key is the photo's
     * identity (its comment thread, reactions and album row all join on it), so a rotation cannot mint a new
     * one the way a profile picture does. The small rendition keeps its key when it has one: a turn that no
     * longer needs a separate copy writes the full bytes there too rather than moving the key. A photo whose
     * small rendition WAS the full one, and now needs its own, gets the {@code -small} key an upload would
     * have minted. Then ONE wildcard CDN invalidation covers both objects (CloudFront bills a wildcard as one
     * path, and its cache key ignores the {@code ?r=} the clients add).
     *
     * @return the small rendition's key after the write.
     * @throws IllegalStateException when the store refused the bytes.
     */
    public String replaceRenditions(final ChatAttachment attachment, final ProcessedPhoto renditions) {
        final String fullKey = attachment.getS3Key();
        final String stem = stemOf(fullKey);
        final String oldThumb = attachment.getThumbKey();
        final boolean hadSmall = oldThumb != null && !oldThumb.equals(fullKey);
        final String thumbKey;
        if (hadSmall) {
            thumbKey = oldThumb;
        } else {
            thumbKey = renditions.smallIsFull() ? fullKey : stem + "-small." + renditions.smallExtension();
        }
        store(fullKey, renditions.fullBytes(), renditions.fullContentType(), true);
        if (!thumbKey.equals(fullKey)) {
            store(thumbKey, renditions.smallBytes(), renditions.smallContentType(), false);
        }
        invalidate(List.of("/" + stem + "*"));
        return thumbKey;
    }

    /** A key without its extension: {@code chat/{trip}/{stamp}-{rand}}, the prefix both renditions share. */
    private static String stemOf(final String key) {
        final int dot = key.lastIndexOf('.');
        final int slash = key.lastIndexOf('/');
        return dot > slash ? key.substring(0, dot) : key;
    }

    /**
     * When a photo was staged, read from its key ({@code chat/{trip}/{yyyyMMdd-HHmmssSSS}-{rand}.ext}); 0 for
     * a key not in that shape. A message is sent after its photos were staged, so this bounds the search for
     * the message that carries one.
     */
    public static long stagedAtMillis(final String s3Key) {
        if (s3Key == null) {
            return 0L;
        }
        final int slash = s3Key.lastIndexOf('/');
        final String name = s3Key.substring(slash + 1);
        if (name.length() < 18) {
            return 0L;
        }
        try {
            return Instant.from(STAMP.parse(name.substring(0, 18))).toEpochMilli();
        } catch (final RuntimeException ex) {
            return 0L;
        }
    }

    /**
     * The full moderation cascade for a removed message's photos: stored renditions, CDN cache, media rows.
     * Row deletion works off the cached media inventory by key — chat rows are always written with the full
     * rendition as {@code s3Key}, so that is the join.
     */
    public void deleteEverywhere(final List<ChatAttachment> removed) {
        // A reference owns nothing: removing it (its message, or the one photo) must leave the original's
        // objects, album row and comment thread exactly as they are.
        final List<ChatAttachment> attachments = removed == null ? List.of() : owned(removed);
        if (attachments.isEmpty()) {
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

    /**
     * The trip-delete cascade for photos: every album row in the trip's chat slot (row + comment thread +
     * REMOVED event), then a store sweep of everything left under {@code chat/{tripId}/} — thumbnails,
     * renditions whose media row was never recorded, uploads that were staged but never sent. The prefix
     * sweep is what makes the delete complete: rows and messages only know about photos that made it into
     * the album, the object store is the ground truth.
     *
     * @return how many album rows were removed.
     */
    public int deleteAllForTrip(final String tripId) {
        final List<MediaItem> rows = DAO.getInstance().getMediaInSlot(slotFor(tripId), Cached.NO);
        for (final MediaItem row : rows) {
            deleteAlbumRow(row);
            PhotoChatCommands.purgePhotoThread(row.getS3Key());
        }
        final String prefix = KEY_PREFIX + tripId + "/";
        if (isRemoteStore()) {
            final List<String> keys = media.listKeys(prefix);
            for (final String key : keys) {
                media.deleteObject(key);
            }
            if (!keys.isEmpty()) {
                // One wildcard invalidation instead of a path per object; CloudFront bills per path.
                media.invalidateCdn(List.of("/" + prefix + "*"));
            }
        } else {
            localStore.removeByPrefix(prefix);
        }
        return rows.size();
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

    /**
     * The album row's title is the caption the sender wrote, or nothing. It used to fall back to the file
     * name, which both apps then showed under the picture as if someone had written it (2026-10-16). The
     * media page and the apps already treat a null title as "no caption"; rows written with a file name
     * before this change are hidden client-side (the apps drop a title equal to the key's last segment).
     */
    private static String titleFor(final ChatAttachment attachment) {
        if (attachment.getCaption() != null && !attachment.getCaption().isBlank()) {
            return attachment.getCaption().trim();
        }
        return null;
    }

    /**
     * Removes both renditions of each attachment from the store and invalidates their CDN paths. Used by the
     * admin remove-message cascade; media rows are the caller's problem (they live in MediaCommands).
     */
    public void deleteRenditions(final List<ChatAttachment> removed) {
        final List<String> paths = new ArrayList<>();
        for (final ChatAttachment attachment : owned(removed)) {
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
        return localStore.stored(key).map(stored -> new ServedPhoto(stored.bytes(), stored.contentType()));
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
        localStore.put(key, bytes, contentType);
    }

    private void deleteStored(final String key) {
        if (isRemoteStore()) {
            media.deleteObject(key);
            return;
        }
        localStore.remove(key);
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
