package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.media.UploadType;
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
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import org.paulsens.trip.cache.Cached;
import java.util.function.Supplier;
import lombok.Setter;
import org.paulsens.trip.site.ListingScope;
import org.paulsens.trip.site.SiteContext;

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
    /**
     * How long a presigned upload URL stays usable. S3 checks the signature's expiry when the PUT STARTS, so
     * this does not bound the transfer itself; it only has to cover the gap between asking for the URL and
     * beginning the upload, and staying short keeps a leaked URL from being a standing grant.
     */
    public static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(15);
    /**
     * Where an organization's library uploads live: {@code org/{orgUUID}/...}, the UUID because a slug can be
     * reassigned and a stored key cannot. The shared site's keys stay exactly as they always were (bare
     * {@code downloads/...}), so nothing existing moved and no URL changed -- see {@link #siteKey}.
     */
    public static final String ORG_KEY_PREFIX = "org/";

    private volatile S3Client s3;
    private volatile S3Presigner presigner;
    private volatile CloudFrontClient cloudFront;
    private final ConfigCommands config = new ConfigCommands();

    /**
     * @return every media item this SITE may discover, newest first, for the admin page: an organization's
     *         site sees only its own items, a shared site only site-level ones (see {@link #discoverable}).
     */
    public List<MediaItem> getAll() {
        try {
            // Expense receipts are financial documents served only through their own gated servlet: no
            // library, picker or listing ever shows one.
            return DAO.getInstance().getAllMedia(Cached.NO).stream()
                    .filter(MediaCommands::discoverable)
                    .filter(item -> !ExpenseReceipts.isReceiptSlot(item.getSlot()))
                    .sorted(Comparator.comparing(MediaItem::getUploaded,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        } catch (final RuntimeException ex) {
            log.error("Unable to list media", ex);
            return List.of();
        }
    }

    /**
     * The media tenancy rule: an item is discoverable (listed, picked, managed) on an organization's site
     * only when it is that org's, and on a shared site only when it is site-level -- a tenant's documents
     * and pictures never surface in another site's library, picker or public section, public or not. This
     * is about DISCOVERY; an item's URL is not restricted, and chat albums are scoped by their trip instead
     * (an old null-org chat photo must still show in its own trip's album).
     */
    static boolean discoverable(final MediaItem item) {
        final SiteContext site = SiteContext.current();
        return site.isOrg() ? site.isSiteOf(item.getOrgId()) : !item.isOrgOwned();
    }

    /** The organization to stamp on a library upload made on this site: the org's on its site, else none. */
    static String siteOrgId() {
        final SiteContext site = SiteContext.current();
        return site.isOrg() ? site.orgId().getValue() : null;
    }

    /**
     * The object key an upload made on THIS site is stored under: {@link #keyForOwner} for the site's org.
     * On an organization's site a caller-chosen {@code background.jpg} becomes {@code
     * org/{orgUUID}/background.jpg}; on a shared site it is stored exactly as given. Two organizations
     * uploading the same name therefore never meet at one object, and neither can reach the shared site's
     * keys. Null for a blank key.
     */
    public static String siteKey(final String key) {
        return keyForOwner(siteOrgId(), key);
    }

    /**
     * The key a file owned by {@code orgId} (null = the shared site) is stored under when a caller asks for
     * {@code key}: namespaced under the org's prefix, unless it already is -- so the key the upload-url
     * step hands back can be repeated verbatim at confirm, and a rename keeps an org's file in its
     * namespace whatever the editor typed. A key under ANOTHER org's namespace is left as it is rather than
     * nested, so {@link #isReservedKey} then refuses it on every write path.
     */
    public static String keyForOwner(final String orgId, final String key) {
        final String cleanKey = normalizeKey(key);
        if (cleanKey == null || orgId == null || cleanKey.startsWith(ORG_KEY_PREFIX)) {
            return cleanKey;
        }
        return orgPrefix(orgId) + cleanKey;
    }

    /** The upload namespace of one organization. */
    static String orgPrefix(final String orgId) {
        return ORG_KEY_PREFIX + orgId + "/";
    }

    /** For the upload form: the prefix this site's uploads land under ({@code ""} on a shared site). */
    public String getSiteKeyPrefix() {
        final String orgId = siteOrgId();
        return orgId == null ? "" : orgPrefix(orgId);
    }

    /**
     * Test seam (the ContentCommands pattern): the caller behind the current request. Resolved from the
     * request-bound {@code RequestContext}, not FacesContext, because these writes are reached from pages,
     * the REST API and the upload servlet alike.
     */
    @Setter
    private Supplier<Caller> callerSource = Caller::bound;

    /**
     * The media OWNERSHIP rule every write re-checks, in two halves. The SITE half ({@link #writableHere}):
     * an item can be changed only from the site where it is discoverable -- an org's rows on that org's
     * host, the shared site's rows on a shared host -- whoever asks, site admins included, so a foreign
     * org's row is never writable from any other host and the admin editor on a host can touch only that
     * host's library. The CALLER half: a global mediaAdmin (or site admin) manages every item there, an
     * org-scoped media editor only that org's items -- discovery already hides the rest, and this is what
     * stops a known id from reaching a foreign row; a trip's manager moderates the trip's own chat album
     * whoever owns the rows. Ownership is the ITEM's, never the request's host.
     */
    boolean mayManage(final MediaItem item) {
        // A receipt is managed only through its expense report (ExpenseReceipts), never as library media.
        if (item == null || ExpenseReceipts.isReceiptSlot(item.getSlot()) || !writableHere(item)) {
            return false;
        }
        final Caller caller = callerSource.get();
        if (caller.has(PrivilegeCommands.MEDIA_ADMIN)) {
            return true;
        }
        if (item.isOrgOwned() && caller.has(PrivilegeCommands.MEDIA_ADMIN, item.getOrgId())) {
            return true;
        }
        final String tripId = ChatPhotos.tripOfSlot(item.getSlot());
        return tripId != null && caller.has(PrivilegeCommands.TRIP_MGR, tripId);
    }

    /**
     * The site half of the write rule: writable only where discoverable (see {@link #discoverable}). A chat
     * album is the exception for the same reason it is exempt from discovery -- it belongs to its TRIP and
     * is moderated from the trip's pages, on whichever host those are served.
     */
    static boolean writableHere(final MediaItem item) {
        return ChatPhotos.isChatSlot(item.getSlot()) || discoverable(item);
    }

    /** Whether the caller may add to the library on THIS site (global, or the site's org's media editor). */
    boolean mayUploadHere() {
        return callerSource.get().hasHere(PrivilegeCommands.MEDIA_ADMIN);
    }

    /**
     * Why a NEW object may not be written at {@code storedKey} (already site-keyed) by this caller, or null
     * when it may: a reserved namespace, or a row that already claims the key and is not this caller's to
     * manage HERE. Re-uploading over your own file is the page's replace path and stays allowed; overwriting
     * anybody else's bytes -- another tenant's, or the shared site's from an org host -- is not. With the
     * org namespace the keys of different owners are disjoint by construction; this is what protects the
     * rows that predate it (an org's early uploads stored under bare keys).
     */
    String claimRefusal(final String storedKey) {
        if (isReservedKey(storedKey)) {
            return "That path belongs to another feature or another site.";
        }
        final MediaItem holder = getByKey(storedKey);
        if (holder != null && !mayManage(holder)) {
            return "That path is already in use.";
        }
        return null;
    }

    /**
     * Why a LIBRARY write may not put a row into {@code requested}, or null when it may. Three slot
     * families belong to features that scope their rows some other way than the library's site rule: a
     * trip's chat album ({@code tripChat-{tripId}}, listed whole on the trip's pages with no org filter), a
     * hotel's gallery and an expense report's receipts. A free-text slot on an upload or an edit was a way
     * to drop a file into another organization's album, so a row enters those only through its own feature
     * ({@code ChatPhotos.stage}, {@code LodgingUploadCommands}, {@code ExpenseReceipts}). A row already
     * there may be saved with its slot unchanged ({@code current}): editing an album photo's title is fine.
     */
    public static String slotRefusal(final String requested, final String current) {
        final String slot = (requested == null) ? null : requested.trim();
        final boolean featureSlot = ChatPhotos.isChatSlot(slot) || LodgingUploadCommands.isLodgingSlot(slot)
                || ExpenseReceipts.isReceiptSlot(slot);
        return featureSlot && !slot.equals(current)
                ? "That slot belongs to a trip album, a lodging gallery or an expense report." : null;
    }

    /**
     * The admin's whole-album rule for one trip's chat slot, the slot-level form of {@link #mayManage}'s
     * caller half: a global media admin, the media editor of the trip's own organization, or the trip's
     * manager. A media editor of some OTHER organization is nobody here, which is the point: being a media
     * administrator on the site a request came to says nothing about another tenant's trip.
     */
    public static boolean managesAlbum(final Caller caller, final Trip trip) {
        if (caller == null || trip == null) {
            return false;
        }
        final String orgId = trip.getOrgId();
        return caller.has(PrivilegeCommands.MEDIA_ADMIN)
                || (orgId != null && !orgId.isBlank() && caller.has(PrivilegeCommands.MEDIA_ADMIN, orgId))
                || caller.has(PrivilegeCommands.TRIP_MGR, trip.getId());
    }

    /** One item by row id for the edit page, or null when it is unknown OR not this caller's to manage. */
    public MediaItem getManageable(final String id) {
        final MediaItem item = get(id);
        return item != null && mayManage(item) ? item : null;
    }

    /**
     * The curated inventory: everything EXCEPT trip-chat photos. The admin page lists this by default —
     * pilgrims sharing phone photos would otherwise swamp the travel guides and flyers an admin actually
     * manages there (its toggle brings them back). Chat photos have their own page per trip.
     */
    public List<MediaItem> getCurated() {
        final List<MediaItem> curated = new ArrayList<>();
        for (final MediaItem item : getAll()) {
            if (!ChatPhotos.isChatSlot(item.getSlot()) && !LodgingUploadCommands.isLodgingSlot(item.getSlot())) {
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
     * The items in a slot that THIS SITE may discover, hidden ones included: what an administrator's slot
     * listing shows. Library slots (documents, home images) are shared names ({@code home-docs} exists on
     * every site), so they are site-scoped per item ({@link #discoverable}); the raw {@link #getInSlot}
     * handed a media admin every organization's rows in the slot. A chat slot is one trip's album, scoped
     * by the trip that owns it, so its rows are not filtered here: who may open that album is the caller's
     * question ({@link #managesAlbum}, or membership).
     */
    public List<MediaItem> getDiscoverableInSlot(final String slot) {
        final boolean chatSlot = ChatPhotos.isChatSlot(slot);
        return getInSlot(slot).stream()
                .filter(item -> chatSlot || discoverable(item))
                .toList();
    }

    /**
     * The publicly-visible items in a slot: {@code hidden} rows are excluded, and when {@code maxAgeDays} is
     * positive, so are rows uploaded longer ago than that (or with no upload date at all -- an age window
     * only makes sense against a known age). Used by the landing page's Documents section, where files
     * expire from view three months after upload.
     */
    public List<MediaItem> getVisibleInSlot(final String slot, final int maxAgeDays) {
        final LocalDateTime cutoff = maxAgeDays > 0 ? LocalDateTime.now().minusDays(maxAgeDays) : null;
        return getDiscoverableInSlot(slot).stream()
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
        if (existing == null || slot == null || slot.isBlank() || !mayManage(existing)
                || slotRefusal(slot, existing.getSlot()) != null) {
            return false;
        }
        final String cleanSlot = slot.trim();
        final int position = getInSlot(cleanSlot).stream().mapToInt(MediaItem::getPosition).max().orElse(-1) + 1;
        final MediaItem updated = new MediaItem(existing.getId(), existing.getS3Key(), existing.getTitle(),
                existing.getDescription(), existing.getContentType(), existing.getSize(), cleanSlot, position,
                LocalDateTime.now(), existing.getUploadedBy(), existing.getSmallKey(), existing.getHidden(),
                existing.getOrgId());
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
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    }

    /**
     * The landing page's picture albums, newest trip first. A trip qualifies when it is publicly listed
     * ({@code openToPublic} -- private trips are unlisted everywhere, including here), has started (in
     * progress is fine, not-yet-started is not), ended within the window, and has at least {@code minPhotos}
     * visible photos in its chat-photo slot. Everything reads from caches (trip index + media hash), so this
     * is safe on a public page render. The albums follow the trips through {@link ListingScope}: an
     * organization's site shows only its own, a shared site the orgs it curates -- a tenant's site never
     * shows another tenant's pictures.
     */
    public List<TripAlbum> getHomeAlbums(final int windowDays, final int minPhotos) {
        return getHomeAlbums(windowDays, minPhotos, ListingScope.forSite());
    }

    private List<TripAlbum> getHomeAlbums(final int windowDays, final int minPhotos, final ListingScope scope) {
        final LocalDateTime now = LocalDateTime.now();
        try {
            return DAO.getInstance().getActiveTrips(now.minusDays(windowDays), Cached.YES).stream()
                    .filter(trip -> Boolean.TRUE.equals(trip.getOpenToPublic()))
                    .filter(trip -> scope.shows(trip.getOrgId()))
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

    /** One event's album, when this site reaches the trip and it has any visible photo; else nothing. */
    private List<TripAlbum> albumOf(final String tripId) {
        try {
            return DAO.getInstance().getTrip(tripId, Cached.YES)
                    .filter(trip -> ListingScope.reachable(trip.getOrgId()))
                    .map(this::toAlbum)
                    .filter(album -> !album.photos().isEmpty())
                    .map(List::of)
                    .orElse(List.of());
        } catch (final RuntimeException ex) {
            log.error("Unable to build the album for trip " + tripId, ex);
            return List.of();
        }
    }

    /**
     * The stored-object filename shape ({@code 20260830-212714549-m457ag.jpg}), which is what an untitled
     * chat upload gets as its title (see {@code ChatPhotos.titleFor}'s fallback). Anchored, so a real title
     * that merely mentions a filename keeps its caption.
     */
    private static final Pattern GENERATED_FILENAME_TITLE =
            Pattern.compile("\\d{8}-\\d{9}-[a-z0-9]{6}(-small)?\\.[A-Za-z0-9]{2,5}");

    /**
     * The caption the landing galleria shows under a photo: the uploader's title, or the empty string when
     * the stored title is just the generated filename — a filename under a photo reads as debris, not a
     * caption. Empty (never null) so the page's tight caption facet renders a truly empty element, which
     * its {@code :empty} rule then hides.
     */
    public String photoCaption(final MediaItem photo) {
        if (photo == null || photo.getTitle() == null) {
            return "";
        }
        final String title = photo.getTitle().trim();
        return GENERATED_FILENAME_TITLE.matcher(title).matches() ? "" : title;
    }

    /**
     * {@link #getHomeAlbums(int, int)} driven by a "Photo Albums" programmatic content instance: blank or
     * unparsable properties fall back to the site-wide settings, so an empty instance behaves like v1 did;
     * on a shared site the instance's curated {@code includeOrgs} list picks whose albums appear.
     */
    public List<TripAlbum> getHomeAlbumsFor(final ContentInstance instance) {
        return getHomeAlbumsFor(instance, null);
    }

    /**
     * {@link #getHomeAlbumsFor(ContentInstance)} on a page that may be ONE event's (docs/conference.md): when the
     * instance names an event, or sits on a conference's page ({@code hostTripId}, the host page's trip, for a
     * section inside a container), the answer is that event's album alone, whatever its age or photo count; a
     * conference page shows its own pictures. Anywhere else it is the recent trips' albums as before.
     */
    public List<TripAlbum> getHomeAlbumsFor(final ContentInstance instance, final String hostTripId) {
        if (instance == null) {
            return List.of();
        }
        final String tripId = TripPageCommands.tripIdFor(instance, hostTripId);
        if (tripId != null) {
            return albumOf(tripId);
        }
        final int window = parsePositive(instance.getValues().get("windowDays"),
                config.getInt(KnownSettings.HOME_PHOTOS_WINDOW_DAYS));
        final int minPhotos = parsePositive(instance.getValues().get("minPhotos"),
                config.getInt(KnownSettings.HOME_PHOTOS_MIN_COUNT));
        return getHomeAlbums(window, minPhotos, ListingScope.forInstance(instance.getValues()));
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
        if (existing == null || !mayManage(existing)) {
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
        // A chat photo's flag also lives in the message that sent it; the phones draw their badge from
        // that copy, so the trip's media page must move both.
        final String tripId = ChatPhotos.tripOfSlot(updated.getSlot());
        if (tripId != null) {
            ChatPhotoEdits.sync(DAO.getInstance(), ChatCommands.getChatCommands().getChannel(tripId),
                    updated.getS3Key(), hidden);
        }
        return true;
    }

    /** @return the public URL a visitor uses for this item. */
    public String getUrl(final MediaItem item) {
        return (item == null) ? null : publicUrl(item.getS3Key());
    }

    /**
     * The host media is served from, for pages that want to SHOW it rather than build a link with it.
     *
     * <p>Distinct from {@link #getUrl(MediaItem)}, which needs an item and answers {@code null} without one --
     * {@code admin/media.xhtml} asked it for the base URL with a {@code null} item, got null, and had to carry
     * a {@code rendered="false"} to hide the blank.
     *
     * @return e.g. {@code https://files.visitqueenofpeace.com}, or EMPTY when no media host is configured
     *     (locally and in tests, where media paths resolve against the app itself). Callers that render it
     *     must handle the empty case; there is no host name to show then.
     */
    public String getMediaBaseUrl() {
        return baseUrl();
    }

    /**
     * One organization's library items of one content-type family ({@code image/}, {@code text/css}), by title:
     * the conference page dialog's pick lists (docs/conference.md, "Look and feel"). Keyed by the org explicitly
     * rather than by the host, because the trip editor may run on the shared site while the trip is an org's; the
     * same tenancy answer as {@link #discoverable} on that org's own site. Curated items only: never a chat photo,
     * a receipt or a hidden item.
     */
    public List<MediaItem> libraryItems(final String orgId, final String contentTypePrefix) {
        final String owner = normalizeOrg(orgId);
        try {
            return DAO.getInstance().getAllMedia(Cached.YES).stream()
                    .filter(item -> Objects.equals(item.getOrgId(), owner))
                    .filter(item -> !ChatPhotos.isChatSlot(item.getSlot()))
                    .filter(item -> !ExpenseReceipts.isReceiptSlot(item.getSlot()))
                    .filter(item -> !item.getHidden())
                    .filter(item -> item.getContentType() != null
                            && item.getContentType().startsWith(contentTypePrefix))
                    .sorted(Comparator.comparing(MediaCommands::titleKey))
                    .toList();
        } catch (final RuntimeException ex) {
            log.error("Unable to list the library for organization " + owner, ex);
            return List.of();
        }
    }

    /**
     * The library item a public URL ({@link #getUrl}) names, when that item is {@code orgId}'s; null for any other
     * URL, another org's item, or an item that is not in the library at all. The inverse of {@link #getUrl}: the
     * host (or no host, locally) and then the object key, read fresh ({@link #getByKey}).
     */
    public MediaItem libraryItemFor(final String orgId, final String url) {
        final String key = keyOfUrl(url);
        if (key == null) {
            return null;
        }
        final MediaItem item = getByKey(key);
        return item != null && Objects.equals(item.getOrgId(), normalizeOrg(orgId)) ? item : null;
    }

    /**
     * The object key a public URL names (the inverse of {@link #getUrl}), or null for any other URL: the media
     * host's prefix, or locally the stylesheet servlet's. A trailing query or fragment (a cache buster) is dropped.
     */
    String keyOfUrl(final String url) {
        if (url == null) {
            return null;
        }
        final String bare = stripQuery(url.trim());
        if (baseUrl().isEmpty() && bare.startsWith(LOCAL_STYLESHEET_PATH)) {
            // Checked first: with no media host the library prefix is a bare "/", which this path starts with too.
            final String key = bare.substring(LOCAL_STYLESHEET_PATH.length());
            return TripStylesheets.isStylesheetKey(key) ? key : null;
        }
        final String prefix = baseUrl() + "/";
        return bare.startsWith(prefix) && bare.length() > prefix.length() ? bare.substring(prefix.length()) : null;
    }

    /**
     * {@link #libraryItemFor} over the CACHED media listing, for render paths (the trip page's look and the embed's
     * inlined stylesheet): no live read per page view. A trailing query or fragment on the URL (a cache buster) is
     * ignored.
     */
    public MediaItem cachedLibraryItemFor(final String orgId, final String url) {
        final String key = keyOfUrl(url);
        if (key == null) {
            return null;
        }
        final String owner = normalizeOrg(orgId);
        try {
            return DAO.getInstance().getAllMedia(Cached.YES).stream()
                    .filter(item -> key.equals(item.getS3Key()))
                    .filter(item -> Objects.equals(item.getOrgId(), owner))
                    .findFirst().orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to look up the library item " + key, ex);
            return null;
        }
    }

    /**
     * A pick list's label for one library item: its title, then its file name when the title does not already say
     * it. A title is often a description ("Summer Fest 2026 page stylesheet") and two items may share one, while
     * the file name is what the editor saw on the Media page; the menu's filter searches both (2026-11-09).
     */
    public String pickLabel(final MediaItem item) {
        if (item == null) {
            return "";
        }
        final String file = fileNameOf(item.getS3Key());
        final String title = item.getTitle() == null ? "" : item.getTitle().trim();
        if (title.isEmpty() || title.equalsIgnoreCase(file)) {
            return file;
        }
        return file.isEmpty() ? title : title + " (" + file + ")";
    }

    /**
     * What a chosen URL names, short enough to read at a glance, for the line under a pick list: a media URL is
     * too long for its field, so the file name at its end was never visible (2026-11-09). One of {@code orgId}'s
     * library items reads as its path inside that organization's library; any other URL as its host and path with
     * a note that it is not in the library. No protocol, no query. Blank for no URL. Cached reads only: this renders
     * with the dialog.
     */
    public String describeChoice(final String orgId, final String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        final MediaItem item = cachedLibraryItemFor(orgId, url);
        if (item == null) {
            return SCHEME.matcher(stripQuery(url.trim())).replaceFirst("") + NOT_IN_LIBRARY;
        }
        final String own = ORG_KEY_PREFIX + item.getOrgId() + "/";
        final String key = item.getS3Key();
        return item.getOrgId() != null && key.startsWith(own) ? key.substring(own.length()) : key;
    }

    /** The file part of a key: what follows its last slash. */
    static String fileNameOf(final String key) {
        return key == null ? "" : key.substring(key.lastIndexOf('/') + 1);
    }

    private static String stripQuery(final String url) {
        final int cut = url.indexOf('?') >= 0 ? url.indexOf('?') : url.indexOf('#');
        return cut < 0 ? url : url.substring(0, cut);
    }

    /** Whether an item is a stylesheet: what a trip page may link as its own CSS. */
    public static boolean isStylesheet(final MediaItem item) {
        return item != null && item.getContentType() != null
                && item.getContentType().toLowerCase(Locale.ROOT).startsWith("text/css");
    }

    private static String normalizeOrg(final String orgId) {
        return (orgId == null || orgId.isBlank()) ? null : orgId.trim();
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

    /**
     * As {@link #upload(String, InputStream, long, String, String, String, String, int, String)}, with the
     * uploader's public-visibility choice ({@code null} = visible).
     *
     * <p>{@code claimedType} is what the browser SAID the file is. It is logged and otherwise ignored: the type
     * the object is stored and served with comes from the file's own bytes ({@link UploadType}), because a
     * claimed {@code text/html} on the shared media host is stored script.
     */
    public boolean upload(final String key, final InputStream content, final long size, final String claimedType,
            final String title, final String description, final String slot, final int position,
            final String uploadedBy, final Boolean hidden) {
        // The two entry points (the admin page and the upload servlet) gate media administration for the
        // site themselves (priv.checkHere / Caller.hasHere); the key is namespaced and the row stamped with
        // the site's org below, so what they may write is decided here whatever they asked for.
        final String bucket = bucket();
        if (bucket == null) {
            PageFeedback.error("Uploads unavailable",
                    BUCKET_VAR + " is not configured, so there is nowhere to put the file.");
            return false;
        }
        final String cleanKey = siteKey(key);
        if (cleanKey == null) {
            PageFeedback.error("Not uploaded", "A file name is required.");
            return false;
        }
        final String refusal = claimRefusal(cleanKey);
        if (refusal != null) {
            PageFeedback.error("Not uploaded", refusal);
            return false;
        }
        final String slotRefusal = slotRefusal(slot, null);
        if (slotRefusal != null) {
            PageFeedback.error("Not uploaded", slotRefusal);
            return false;
        }
        final String contentType;
        try {
            final BufferedInputStream buffered = new BufferedInputStream(content);
            final UploadType type = UploadType.of(cleanKey, head(buffered));
            contentType = type.contentType();
            if (!contentType.equals(claimedType)) {
                log.info("Storing {} as {} (the browser called it {})", cleanKey, contentType, claimedType);
            }
            s3().putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(cleanKey)
                            .contentType(contentType)
                            // A file that is not a recognized image, PDF, video or audio is download-only.
                            .contentDisposition(type.attachment() ? "attachment" : null)
                            .contentLength(size)
                            // Set at upload time, because S3 serves object metadata as response headers and
                            // CloudFront honours them. Without this the CDN would still cache, but browsers
                            // would keep re-validating a file that almost never changes.
                            .cacheControl("public, max-age=" + CACHE_SECONDS)
                            .build(),
                    RequestBody.fromInputStream(buffered, size));
        } catch (final IOException | RuntimeException ex) {
            log.error("Unable to store media object: " + cleanKey, ex);
            PageFeedback.failure("Not uploaded");
            return false;
        }

        final MediaItem item = new MediaItem(UUID.randomUUID().toString(), cleanKey,
                (title == null || title.isBlank()) ? cleanKey : title.trim(),
                description, contentType, size, slot, position, LocalDateTime.now(), uploadedBy, null, hidden,
                siteOrgId());
        try {
            if (!DAO.getInstance().saveMedia(item)) {
                // The object is stored but unlisted: say so plainly rather than reporting success, because the
                // file exists at its URL and a retry would upload it again.
                PageFeedback.error("Partly uploaded",
                        "The file was stored but could not be recorded. It is reachable at its URL.");
                return false;
            }
        } catch (final RuntimeException ex) {
            log.error("Stored object but failed to save its media row: " + cleanKey, ex);
            PageFeedback.failure("Partly uploaded");
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

    /** The first bytes of {@code in}, which is left positioned at its start. */
    private static byte[] head(final BufferedInputStream in) throws IOException {
        in.mark(UploadType.HEAD_BYTES);
        final byte[] head = in.readNBytes(UploadType.HEAD_BYTES);
        in.reset();
        return head;
    }

    /** What S3 reports about a stored object, for confirming a direct upload without trusting the caller. */
    public record StoredObject(long size, String contentType) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    }

    /**
     * A short-lived presigned {@code PUT} URL for uploading directly to S3, or null when no bucket is
     * configured or the request is malformed.
     *
     * <p>This is the API's upload path (issue #37): the container never carries the file's bytes -- it issues
     * the URL, the client PUTs straight to S3, and {@link #confirmUpload} records the row afterwards. The
     * content type, exact content length and the standard cache policy are all part of the SIGNATURE, so the
     * URL is permission to put that one object shape at that one key and nothing else. Null too for a declared
     * type {@link UploadType#mayDeclare} does not allow.
     */
    public String presignUpload(final String key, final String contentType, final long size) {
        final String bucket = bucket();
        // Site-keyed, so the signature pins the namespaced key: a URL for an org's upload can put nowhere
        // but under that org's prefix.
        final String cleanKey = siteKey(key);
        if (bucket == null || cleanKey == null || contentType == null || contentType.isBlank() || size <= 0
                || isReservedKey(cleanKey)) {
            return null;
        }
        if (!UploadType.mayDeclare(contentType)) {
            // The bytes go straight to S3, so they cannot be looked at here: only a type that is inert whatever
            // the bytes are may be signed (never text/html, SVG, XML or script on the shared media host).
            log.warn("Refused to presign an upload of {} declared as {}", cleanKey, contentType);
            return null;
        }
        try {
            final PutObjectRequest put = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(cleanKey)
                    .contentType(contentType)
                    .contentLength(size)
                    // Same policy upload() stamps, and signed for the same reason: S3 serves object metadata
                    // as response headers, so a direct upload must carry it or the CDN never caches the file.
                    .cacheControl("public, max-age=" + CACHE_SECONDS)
                    .build();
            final String url = presigner().presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(UPLOAD_URL_TTL)
                    .putObjectRequest(put)
                    .build()).url().toString();
            log.info("Issued a presigned upload URL for {} ({} bytes, {})", cleanKey, size, contentType);
            return url;
        } catch (final RuntimeException ex) {
            log.error("Unable to presign an upload for: " + cleanKey, ex);
            return null;
        }
    }

    /**
     * What is actually stored at {@code key}: empty when there is no such object, no bucket is configured, or
     * S3 could not be asked. The confirm step of a direct upload trusts THIS, never the caller's claims.
     */
    public Optional<StoredObject> statObject(final String key) {
        final String bucket = bucket();
        if (bucket == null || key == null) {
            return Optional.empty();
        }
        try {
            final HeadObjectResponse head = s3().headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(new StoredObject(head.contentLength() == null ? 0L : head.contentLength(),
                    head.contentType()));
        } catch (final NoSuchKeyException absent) {
            return Optional.empty();
        } catch (final RuntimeException ex) {
            // Unreachable and absent answer alike: a confirm must not record a row it cannot verify.
            log.error("Unable to stat object: " + key, ex);
            return Optional.empty();
        }
    }

    /**
     * Records the metadata row for an object a client already PUT to S3 via {@link #presignUpload}, and fires
     * the same events and audit an in-container {@link #upload} would have -- without this, {@code
     * ProfilePhotos} and the slot listings would go stale exactly as the issue warned.
     *
     * <p>Size and content type come from S3, not from the request: the confirm call is just a claim, the
     * object is the fact.
     *
     * @return the saved item, or null when the object is not actually there or the row could not be written.
     */
    public MediaItem confirmUpload(final String key, final String title, final String description,
            final String slot, final int position, final String uploadedBy, final Boolean hidden) {
        final String cleanKey = siteKey(key);
        if (cleanKey == null || !mayUploadHere() || claimRefusal(cleanKey) != null
                || slotRefusal(slot, null) != null) {
            return null;
        }
        final Optional<StoredObject> stored = statObject(cleanKey);
        if (stored.isEmpty()) {
            log.warn("Refused to confirm an upload with no object behind it: {}", cleanKey);
            return null;
        }
        final MediaItem item = new MediaItem(UUID.randomUUID().toString(), cleanKey,
                (title == null || title.isBlank()) ? cleanKey : title.trim(),
                description, stored.get().contentType(), stored.get().size(), slot, position,
                LocalDateTime.now(), uploadedBy, null, hidden, siteOrgId());
        try {
            if (!DAO.getInstance().saveMedia(item)) {
                return null;
            }
        } catch (final RuntimeException ex) {
            log.error("Verified the object but failed to save its media row: " + cleanKey, ex);
            return null;
        }
        MediaEvents.fire(MediaEvents.Change.ADDED, cleanKey);
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .currentActor(uploadedBy)
                .target(AuditEventBuilder.TARGET_MEDIA, cleanKey)
                .message("Uploaded " + cleanKey + " (" + stored.get().size() + " bytes) via presigned URL")
                .log();
        return item;
    }

    /**
     * The item whose object key is {@code key}, or null. Fresh read, because its callers are about to decide
     * whether a key is free to be claimed -- a stale answer here mints a second row over the same object.
     * Deliberately NOT the site-filtered listing: the question is whether ANY row holds the key, and a row
     * this site cannot discover is exactly the one an upload must not overwrite.
     */
    public MediaItem getByKey(final String key) {
        final String cleanKey = normalizeKey(key);
        if (cleanKey == null) {
            return null;
        }
        try {
            return DAO.getInstance().getAllMedia(Cached.NO).stream()
                    .filter(item -> cleanKey.equals(item.getS3Key()))
                    .findFirst().orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to look up media by key: " + cleanKey, ex);
            return null;
        }
    }

    /**
     * Whether {@code key} lives where a DIFFERENT feature or site reads: profile photos, chat photos and
     * badge images index their whole prefixes, so a caller-chosen key under any of them would be writing
     * into someone else's namespace; and {@code org/...} is an organization's namespace, which only that
     * org's own site may write (a shared host never writes under {@code org/} at all). A malformed key
     * counts as reserved -- there is no safe answer for it.
     */
    public static boolean isReservedKey(final String key) {
        final String cleanKey = normalizeKey(key);
        return cleanKey == null
                || cleanKey.startsWith(ProfilePhotos.PREFIX)
                || cleanKey.startsWith(ChatPhotos.KEY_PREFIX)
                || cleanKey.startsWith(BadgePhotoCommands.PREFIX)
                || cleanKey.startsWith(ExpenseReceipts.KEY_PREFIX)
                || isForeignOrgKey(cleanKey);
    }

    /** An {@code org/} key that is not THIS site's own namespace (or is its bare prefix with no name). */
    private static boolean isForeignOrgKey(final String cleanKey) {
        if (!cleanKey.startsWith(ORG_KEY_PREFIX)) {
            return false;
        }
        final String orgId = siteOrgId();
        if (orgId == null) {
            return true;
        }
        final String own = orgPrefix(orgId);
        return !cleanKey.startsWith(own) || cleanKey.length() == own.length();
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
        if (existing == null || !mayManage(existing)) {
            PageFeedback.error("Not saved", "No such media item.");
            return false;
        }
        final String slotRefusal = slotRefusal(slot, existing.getSlot());
        if (slotRefusal != null) {
            PageFeedback.error("Not saved", slotRefusal);
            return false;
        }
        // A rename stays in the OWNER's namespace whatever was typed (an org's file cannot be moved onto a
        // shared key or another tenant's), and never lands on a key some other row holds.
        final String cleanKey = (newKey == null || newKey.isBlank())
                ? existing.getS3Key() : keyForOwner(existing.getOrgId(), newKey);
        final boolean renamed = !cleanKey.equals(existing.getS3Key());
        if (renamed) {
            final String refusal = renameRefusal(existing, cleanKey);
            if (refusal != null) {
                PageFeedback.error("Not renamed", refusal);
                return false;
            }
            if (!moveObject(existing.getS3Key(), cleanKey)) {
                return false;
            }
        }

        final MediaItem updated = new MediaItem(existing.getId(), cleanKey, title, description,
                existing.getContentType(), existing.getSize(), slot,
                (position == null) ? existing.getPosition() : position,
                existing.getUploaded(), existing.getUploadedBy(), existing.getSmallKey(), hidden,
                existing.getOrgId());
        try {
            if (!DAO.getInstance().saveMedia(updated)) {
                PageFeedback.error("Not saved", "The save failed.");
                return false;
            }
        } catch (final RuntimeException ex) {
            log.error("Unable to save media row: " + id, ex);
            PageFeedback.failure("Not saved");
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

    /**
     * Why {@code existing} may not be renamed to {@code target} (already owner-keyed), or null: a reserved
     * namespace, or a DIFFERENT row's key -- a rename never overwrites, not even a file of one's own.
     */
    String renameRefusal(final MediaItem existing, final String target) {
        if (isReservedKey(target)) {
            return "That path belongs to another feature or another site.";
        }
        final MediaItem holder = getByKey(target);
        if (holder != null && !holder.getId().equals(existing.getId())) {
            return "Another file already uses that path.";
        }
        return null;
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
            PageFeedback.error("Not renamed",
                    "The stored file could not be copied to the new name; nothing was changed.");
            return false;
        }
        try {
            s3().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(from).build());
        } catch (final RuntimeException ex) {
            // The copy succeeded, so the new URL works. A leftover object at the old key is untidy, and it also
            // means the OLD url keeps working -- worth reporting, not worth failing the rename for.
            log.error("Copied to " + to + " but could not remove " + from, ex);
            PageFeedback.warn("Renamed, old copy remains",
                    "The file is available at its new name, but the old one could not be removed.");
        }
        return true;
    }

    /** @return the public URL for an arbitrary media key. */
    public String publicUrl(final String key) {
        final String local = localServedPath(key);
        return baseUrl().isEmpty() && local != null ? local : baseUrl() + "/" + key;
    }

    /** The local-mode serving path of a key the webapp serves itself when no media host exists, else null. */
    static String localServedPath(final String key) {
        return TripStylesheets.isStylesheetKey(key) ? LOCAL_STYLESHEET_PATH + key : null;
    }

    /** Where {@code web/StylesheetServlet} serves a trip page stylesheet in local mode. */
    static final String LOCAL_STYLESHEET_PATH = "/trip-stylesheets/";

    /** What {@link #describeChoice} appends to a URL that names no item of the organization's library. */
    static final String NOT_IN_LIBRARY = " (not in the organization's media library)";
    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://");

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
        if (found.isEmpty() || !mayManage(found.get())) {
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
                PageFeedback.warn("Partly deleted",
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
        // AuditActor.current() reads a Faces-request ThreadLocal, so this form is for JSF pages only; the
        // REST edge resolves its actor from the session and must use the overload.
        return refreshFromDatabase(AuditActor.current());
    }

    /** As {@link #refreshFromDatabase()}, with the actor supplied -- the form non-Faces callers must use. */
    public int refreshFromDatabase(final AuditActor actor) {
        DAO.getInstance().clearMediaCache();
        final int count = getAll().size();
        Audit.builder(AuditAction.MEDIA, AuditOutcome.SUCCESS)
                .actor(actor)
                .message("Refreshed the media inventory from the table; " + count + " items")
                .log();
        return count;
    }

    /** Strips leading slashes and lowercases nothing: keys are case-sensitive and become part of the URL. */
    public static String normalizeKey(final String key) {
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

    private S3Presigner presigner() {
        S3Presigner client = presigner;
        if (client == null) {
            synchronized (this) {
                client = presigner;
                if (client == null) {
                    client = S3Presigner.builder()
                            .region(Region.of(regionName()))
                            .credentialsProvider(DefaultCredentialsProvider.builder().build())
                            .build();
                    presigner = client;
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
