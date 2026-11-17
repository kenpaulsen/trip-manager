package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.site.ListingScope;

/**
 * Trip albums as a SOURCE of pictures, exposed as {@code #{albumPhotos}}: which albums a person may pick from while
 * editing one trip, which of their photos are offered, and the bytes of a picked one. A trip's album is the photos
 * shared in its chat (media rows in slot {@code tripChat-{tripId}}), the same rows {@code trip/tripMedia.xhtml}
 * shows its members. The "Choose from Albums" dialog reads this class; what a picked photo becomes is the host's
 * business ({@link TripPictureCommands#addFromAlbum} cuts it into a trip picture).
 *
 * <p>Three rules decide what is offered, and every one of them is asked again when a pick is turned into bytes
 * ({@link #offered}), because the pick arrives from the browser as a bare media id:
 * <ol>
 * <li><b>The scope's organization.</b> A host says where a picked photo is going ({@link #tripScope},
 *     {@link #orgScope}, {@link #SCOPE_MINE}), and only albums of THAT organization are offered: a photo picked for
 *     a trip or an organization lands on its public pages, so an album of another organization, even one this
 *     person belongs to, would move one tenant's photo onto another tenant's page. No exception for a site admin:
 *     the rule is about whose photo it is, not about who is asking. Only a host that belongs to no organization (a
 *     person's own profile picture, a hotel's gallery, both global records by design) offers every album the
 *     person may open.</li>
 * <li><b>The person may open that album</b> ({@link #mayOpen}).</li>
 * <li><b>Not kept private.</b> A photo whose sharer (or a moderator) hid it from the public site is never offered:
 *     the album's members still see it, but everything a pick becomes is public or shown to another audience. An
 *     album says how many were held back ({@link Album#withheld}) so a missing photo is explained rather than
 *     mysterious.</li>
 * </ol>
 * Only images are offered; an album row of any other type is skipped without being counted.
 */
@Slf4j
@Named("albumPhotos")
@ApplicationScoped
public class AlbumPhotoSource {

    /**
     * How many of the most recent trips are looked through for albums, beside the caller's own
     * ({@link #candidateTrips}). The window is the whole store's, not one organization's.
     */
    static final int TRIP_WINDOW = 400;

    private static final String MEMO_PREFIX = "albumPhotos.albums:";

    /** One offered photo: the media row's id (what a pick sends back), the reduced copy's URL, and who shared it. */
    public record Photo(String id, String url, String label) {
    }

    /**
     * One trip's album as the dialog lists it. {@code current} marks the trip being edited, which is listed even
     * while it has no photos. {@code withheld} counts the photos kept out of public view.
     */
    public record Album(String tripId, String title, String dates, boolean current, List<Photo> photos,
            int withheld) {

        /** The first offered photo's URL, the album's thumbnail in the list; null when it offers none. */
        public String getCoverUrl() {
            return photos.isEmpty() ? null : photos.getFirst().url();
        }

        /** "12 photos", "1 photo" or "No photos yet", the line under the album's title. */
        public String getCountLabel() {
            if (photos.isEmpty()) {
                return "No photos yet";
            }
            return photos.size() == 1 ? "1 photo" : photos.size() + " photos";
        }

        /** Why some photos are missing, or blank when none are. */
        public String getWithheldNote() {
            if (withheld == 0) {
                return "";
            }
            return withheld == 1
                    ? "1 photo in this album is not offered: it was kept out of public view."
                    : withheld + " photos in this album are not offered: they were kept out of public view.";
        }
    }

    // ------------------------------------------------------------------ scopes

    /** The scope of a host with no trip and no organization of its own: every album the person may open. */
    public static final String SCOPE_MINE = "mine";

    /**
     * The scope of a host that belongs to the SITE a request came to (its media library, a page's content): the
     * albums of the trips that site serves ({@link ListingScope#reachable}), which on an organization's own site
     * is that organization's and on a shared site is the organizations it hosts.
     */
    public static final String SCOPE_SITE = "site";

    private static final String TRIP_PREFIX = "trip:";
    private static final String ORG_PREFIX = "org:";

    /** The scope of a host editing one trip: the albums of that trip's organization, its own first. */
    public static String tripScope(final String tripId) {
        return TRIP_PREFIX + tripId;
    }

    /** The scope of a host that belongs to an organization (its media library, its branding). */
    public static String orgScope(final String orgId) {
        return ORG_PREFIX + orgId;
    }

    /** Whose albums a scope offers: one organization's, the current site's, or anyone's. */
    private enum Reach { ORG, SITE, ANY }

    /**
     * A scope, read: the trip being edited (listed first even when empty, and its own album always usable by
     * whoever may edit it), and whose albums are offered ({@code orgId} only for {@link Reach#ORG}).
     */
    private record Scope(Trip current, String orgId, Reach reach) {

        boolean isCurrent(final Trip trip) {
            return current != null && current.getId().equals(trip.getId());
        }
    }

    /** Whether a scope offers this trip's album at all, before asking whether the caller may open it. */
    private boolean admits(final Scope scope, final Trip trip) {
        return switch (scope.reach()) {
            case ANY -> true;
            case SITE -> reachableHere(orgOf(trip));
            case ORG -> Objects.equals(scope.orgId(), orgOf(trip));
        };
    }

    /** The scope a text names; empty for none, an unknown trip or anything malformed (which offers nothing). */
    private Optional<Scope> scopeOf(final String scope) {
        if (scope == null || scope.isBlank()) {
            return Optional.empty();
        }
        if (SCOPE_MINE.equals(scope)) {
            return Optional.of(new Scope(null, null, Reach.ANY));
        }
        if (SCOPE_SITE.equals(scope)) {
            return Optional.of(new Scope(null, null, Reach.SITE));
        }
        if (scope.startsWith(TRIP_PREFIX)) {
            return tripOf(scope.substring(TRIP_PREFIX.length()))
                    .map(trip -> new Scope(trip, orgOf(trip), Reach.ORG));
        }
        final String orgId = scope.startsWith(ORG_PREFIX) ? scope.substring(ORG_PREFIX.length()).trim() : "";
        return orgId.isEmpty() ? Optional.empty() : Optional.of(new Scope(null, orgId, Reach.ORG));
    }

    // ------------------------------------------------------------------ what a page asks

    /**
     * The albums the signed-in person may pick from in {@code scope}: the trip being edited first (a trip scope),
     * then every other album the scope admits that they may open and that has at least one image, newest trip
     * first. Empty for an unknown scope or nobody signed in. Memoized for the request: the dialog asks for the
     * list and then for one album of it.
     */
    public List<Album> albumsOf(final String scope) {
        if (scope == null || scope.isBlank()) {
            return List.of();
        }
        return RequestMemo.get(MEMO_PREFIX + scope, () -> albumsFor(caller(), scope));
    }

    /**
     * The album the dialog shows: the one asked for when it is among {@link #albumsOf}, else the first that has
     * photos, else the first (the trip's own, empty). Null only when there are none at all.
     */
    public Album albumOf(final String scope, final String albumTripId) {
        final List<Album> albums = albumsOf(scope);
        for (final Album album : albums) {
            if (album.tripId().equals(albumTripId)) {
                return album;
            }
        }
        for (final Album album : albums) {
            if (!album.photos().isEmpty()) {
                return album;
            }
        }
        return albums.isEmpty() ? null : albums.getFirst();
    }

    // ------------------------------------------------------------------ the rules, caller explicit

    /** {@link #albumsOf} for an explicit caller. */
    public List<Album> albumsFor(final Caller caller, final String scopeText) {
        final Optional<Scope> found = scopeOf(scopeText);
        if (found.isEmpty() || caller == null || !caller.isAuthenticated()) {
            return List.of();
        }
        final Scope scope = found.get();
        final List<Album> albums = new ArrayList<>();
        if (scope.current() != null) {
            albums.add(albumOf(scope.current(), true));
        }
        candidateTrips(caller).stream()
                .filter(trip -> !scope.isCurrent(trip))
                .filter(trip -> admits(scope, trip) && mayOpen(caller, trip))
                .sorted(Comparator.comparing(Trip::getStartDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(trip -> albumOf(trip, false))
                .filter(album -> !album.photos().isEmpty() || album.withheld() > 0)
                .forEach(albums::add);
        return List.copyOf(albums);
    }

    /**
     * The album row behind a pick, when {@code caller} may use it in {@code scope}: an image, in a trip's chat
     * album, not kept private, of a trip the scope admits whose album the caller may open (or the very trip
     * being edited). Empty for anything else, without saying which rule refused: the id came from a browser.
     */
    public Optional<MediaItem> offered(final Caller caller, final String scopeText, final String mediaId) {
        final Optional<Scope> scope = scopeOf(scopeText);
        if (caller == null || !caller.isAuthenticated() || scope.isEmpty() || mediaId == null || mediaId.isBlank()) {
            return Optional.empty();
        }
        final Optional<MediaItem> item = itemOf(mediaId);
        if (item.isEmpty() || !ChatPhotos.isChatSlot(item.get().getSlot()) || !isOfferable(item.get())) {
            return Optional.empty();
        }
        final Optional<Trip> albumTrip = tripOf(ChatPhotos.tripOfSlot(item.get().getSlot()));
        final boolean allowed = albumTrip.isPresent() && admits(scope.get(), albumTrip.get())
                && (scope.get().isCurrent(albumTrip.get()) || mayOpen(caller, albumTrip.get()));
        return allowed ? item : Optional.empty();
    }

    /**
     * Whether {@code caller} may open this trip's album. Someone on the roster, or a holder of {@code tripView}
     * or {@code tripMgr} for the trip (the rule of the album's own page; a site admin holds both); or someone who
     * already manages the whole album ({@link MediaCommands#managesAlbum}: a media admin, globally or of the
     * trip's organization); or an admin of the trip's organization, whose trips these are (owner, 2026-11-17: an
     * organization's branding and library pick from all of its albums).
     */
    public boolean mayOpen(final Caller caller, final Trip trip) {
        if (caller == null || !caller.isAuthenticated() || trip == null || trip.getId() == null) {
            return false;
        }
        return caller.has(PrivilegeCommands.TRIP_VIEW, trip.getId())
                || trip.getPeople().contains(caller.personId())
                || MediaCommands.managesAlbum(caller, trip)
                || isOrgAdmin(caller, orgOf(trip));
    }

    /** The full-size bytes of an album photo, from wherever chat photos are stored; empty when unreadable. */
    public Optional<byte[]> bytesOf(final MediaItem item) {
        return item == null ? Optional.empty() : storedBytes(item.getS3Key());
    }

    /** A trip's organization id, a blank one read as none (which equals only another trip's none). */
    private static String orgOf(final Trip trip) {
        final String orgId = trip.getOrgId();
        return orgId == null || orgId.isBlank() ? null : orgId;
    }

    private static boolean isImage(final MediaItem item) {
        return item.getContentType() != null && item.getContentType().startsWith("image/");
    }

    private static boolean isOfferable(final MediaItem item) {
        return isImage(item) && !item.getHidden();
    }

    private Album albumOf(final Trip trip, final boolean current) {
        final String base = photoBase();
        final List<Photo> photos = new ArrayList<>();
        int withheld = 0;
        for (final MediaItem item : rowsOf(trip.getId())) {
            if (!isImage(item)) {
                continue;
            }
            if (item.getHidden()) {
                withheld++;
            } else {
                final String url = base + item.getDisplayKey() + item.getCacheBuster();
                photos.add(new Photo(item.getId(), url, labelOf(item)));
            }
        }
        return new Album(trip.getId(), trip.getTitle(), datesOf(trip), current, List.copyOf(photos), withheld);
    }

    /** The trip's date range as its pages spell it; blank for a trip still missing a date. */
    private static String datesOf(final Trip trip) {
        return trip.getStartDate() == null || trip.getEndDate() == null ? "" : trip.getTripDateRange();
    }

    /** What a screen reader says for a photo: who shared it (the row's description), else its title. */
    private static String labelOf(final MediaItem item) {
        if (item.getDescription() != null && !item.getDescription().isBlank()) {
            return item.getDescription();
        }
        return item.getTitle() == null || item.getTitle().isBlank() ? "Photo" : item.getTitle();
    }

    // ------------------------------------------------------------------ seams

    /** Seam: whether the caller administers this organization; none has no admins. */
    protected boolean isOrgAdmin(final Caller caller, final String orgId) {
        if (orgId == null) {
            return false;
        }
        final Optional<Organization> org = DAO.getInstance().getOrganization(Organization.Id.from(orgId), Cached.YES);
        return org.isPresent() && org.get().isAdmin(caller.personId());
    }

    /** Seam: whether the site this request came to serves that organization's trips. */
    protected boolean reachableHere(final String orgId) {
        return ListingScope.reachable(orgId);
    }

    /** Seam: who is asking, on a page. */
    protected Caller caller() {
        return Caller.current();
    }

    /** Seam: one trip by id, from the cache (a listing, not a write). */
    protected Optional<Trip> tripOf(final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return Optional.empty();
        }
        return DAO.getInstance().getTrip(tripId, Cached.YES);
    }

    /**
     * Seam: the trips looked through for albums, each once. Every trip the caller was ever on (the roster's
     * reverse index, so a member's albums are found however old the trip), then the store's most recent trips,
     * which is where a manager or a viewer who never traveled finds theirs. There is no index of trips by
     * organization, so the second list is a window, and the rules above do the filtering.
     */
    protected List<Trip> candidateTrips(final Caller caller) {
        final Map<String, Trip> byId = new LinkedHashMap<>();
        try {
            DAO.getInstance().getTripsForUser(caller.personId(), Cached.YES)
                    .forEach(trip -> byId.putIfAbsent(trip.getId(), trip));
            DAO.getInstance().getRecentTrips(TRIP_WINDOW, Cached.YES)
                    .forEach(trip -> byId.putIfAbsent(trip.getId(), trip));
        } catch (final RuntimeException ex) {
            // The dialog must still open with the trip's own album if a listing is unavailable.
            log.error("Unable to list trips for the album picker", ex);
        }
        return List.copyOf(byId.values());
    }

    /** Seam: one trip's album rows, in album order. */
    protected List<MediaItem> rowsOf(final String tripId) {
        try {
            return DAO.getInstance().getMediaInSlot(ChatPhotos.slotFor(tripId), Cached.YES);
        } catch (final RuntimeException ex) {
            log.error("Unable to list the album of trip " + tripId, ex);
            return List.of();
        }
    }

    /** Seam: one media row by id. */
    protected Optional<MediaItem> itemOf(final String mediaId) {
        try {
            return DAO.getInstance().getMedia(mediaId, Cached.YES);
        } catch (final RuntimeException ex) {
            log.error("Unable to look up album photo " + mediaId, ex);
            return Optional.empty();
        }
    }

    /** Seam: the URL prefix of a chat photo for the rendering page (the CDN, or the local servlet's path). */
    protected String photoBase() {
        return ChatPhotos.getChatPhotos().getPhotoPageBase();
    }

    /** Seam: a chat photo's stored bytes. */
    protected Optional<byte[]> storedBytes(final String s3Key) {
        return ChatPhotos.getChatPhotos().currentBytes(s3Key);
    }
}
