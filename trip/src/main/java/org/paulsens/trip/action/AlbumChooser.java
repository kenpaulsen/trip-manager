package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.primefaces.PrimeFaces;

/**
 * The server side of the ONE "Choose from Albums" dialog ({@code WEB-INF/albumPickerDialog.xhtml}, mounted by the
 * template for every signed-in page), exposed as {@code #{albumChooser}}. A page opens the dialog for a
 * {@link Use}: a name for what the chosen photos are for. Everything that matters is then decided HERE from that
 * name, never from the page: which bean takes the picks, and in which scope the albums are listed and every pick
 * is checked again. The browser chooses only how the dialog looks (one photo or several, the tile shape), which
 * can do no harm.
 *
 * <p>Why a dispatcher instead of a bean per mount: the dialog has fixed component ids, so a view can hold it once,
 * and several pages take album photos for more than one purpose (the trip editor's pictures and its rich-text
 * editors, an organization's link preview and its content). One mount with a named use serves them all.
 *
 * <p>Each host re-derives its own target from its own seam (the trip a dialog is open on, the role being
 * uploaded), exactly as it does for a file upload, so a {@code use} the page did not really open finds no target
 * and is refused there. A target is not enough to LIST albums, though: the trip a view remembers is also set by
 * read-only pages (a trip's public page, its join page) for anyone signed in, so every host answers its scope
 * only to a caller its own authorization admits ({@link PhotoUploadBean#getAlbumScope}).
 */
@Named("albumChooser")
@ApplicationScoped
public class AlbumChooser {

    /** What chosen album photos are for; {@link #key} is what a page passes to {@code TripAlbumPicker.open}. */
    public enum Use {
        /** A trip's cover and gallery: several photos, each cut to 3:2 ({@link TripPictureCommands}). */
        TRIP_PICTURES("tripPictures"),
        /** A custom itinerary-badge image: one photo, then the square crop ({@link BadgePhotoCommands}). */
        BADGE("badge"),
        /** A profile picture: one photo, then the square crop ({@link ProfilePhotoCommands}). */
        PROFILE("profile"),
        /** An organization's link preview image: one photo, then the 1.91:1 crop. */
        LINK_PREVIEW("linkPreview"),
        /** A hotel's or a room type's photo: one photo, then the free crop ({@link LodgingUploadCommands}). */
        LODGING("lodging"),
        /** The media library: several photos, each copied whole ({@link AlbumLibrary}). */
        LIBRARY("library"),
        /** A field that takes an image by URL: one photo, copied into the library, its URL handed back. */
        URL("url"),
        /** A chat message's attachments: several photos, REFERENCED where they are; the page sends them. */
        CHAT("chat");

        private final String key;

        Use(final String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }

        static Optional<Use> of(final String key) {
            return Arrays.stream(values()).filter(use -> use.key.equals(key)).findFirst();
        }
    }

    private static final String MEMO_PREFIX = "albumChooser.scope:";

    @Inject
    private AlbumPhotoSource albums;
    @Inject
    private AlbumLibrary library;
    @Inject
    private TripPictureCommands tripPictures;
    @Inject
    private BadgePhotoCommands badgePhotos;
    @Inject
    private ProfilePhotoCommands profilePhotos;
    @Inject
    private BrandingUploadCommands branding;
    @Inject
    private LodgingUploadCommands lodgingUpload;
    @Inject
    private ChatPhotoUpload chatUpload;

    void wireForTest(final AlbumPhotoSource source, final AlbumLibrary albumLibrary,
            final TripPictureCommands pictures, final BadgePhotoCommands badges, final ProfilePhotoCommands profiles,
            final BrandingUploadCommands brandingUpload, final LodgingUploadCommands lodging,
            final ChatPhotoUpload chat) {
        this.albums = source;
        this.library = albumLibrary;
        this.tripPictures = pictures;
        this.badgePhotos = badges;
        this.profilePhotos = profiles;
        this.branding = brandingUpload;
        this.lodgingUpload = lodging;
        this.chatUpload = chat;
    }

    // ------------------------------------------------------------------ what the dialog renders

    /**
     * The scope a use lists and checks its albums in, from the host's own target; null when it has none. Worked out
     * once per request: answering it asks the host's authorization (a trip or chat-membership read), and the dialog
     * asks it many times per render (its {@code albumShown} alias is re-evaluated at every use).
     */
    public String scopeOf(final String use) {
        return RequestMemo.get(requestMap(), MEMO_PREFIX + use, () -> Use.of(use).map(this::scope).orElse(null));
    }

    private String scope(final Use use) {
        return switch (use) {
            case TRIP_PICTURES -> tripPictures.getAlbumScope();
            case BADGE -> badgePhotos.getAlbumScope();
            case PROFILE -> profilePhotos.getAlbumScope();
            case LINK_PREVIEW -> branding.getAlbumScope();
            case LODGING -> lodgingUpload.getAlbumScope();
            case LIBRARY, URL -> library.getAlbumScope();
            case CHAT -> chatUpload.getAlbumScope();
        };
    }

    /** The albums the dialog lists for a use; empty for an unknown one or a host with no target. */
    public List<AlbumPhotoSource.Album> albumsOf(final String use) {
        return albums.albumsOf(scopeOf(use));
    }

    /** The album the dialog shows for a use: {@link AlbumPhotoSource#albumOf}. */
    public AlbumPhotoSource.Album albumOf(final String use, final String albumTripId) {
        return albums.albumOf(scopeOf(use), albumTripId);
    }

    /**
     * How many photos a use lets a person choose: what a trip still has room for, the library's batch size, one
     * for a use that takes one. Chat's own count (its cap less what is already attached) is the page's to say; the
     * server holds the cap at send.
     */
    public int roomOf(final String use) {
        final Optional<Use> found = Use.of(use);
        if (found.isEmpty()) {
            return 0;
        }
        return switch (found.get()) {
            case TRIP_PICTURES -> tripPictures.roomFor(tripPictures.targetId());
            case LIBRARY, CHAT -> AlbumLibrary.MAX_PER_ADD;
            default -> 1;
        };
    }

    // ------------------------------------------------------------------ what the dialog sends

    /** The dialog's Add, for a use that takes several: the host re-checks every pick and reports albumAdded. */
    public void add(final String use, final String picks) {
        // A switch EXPRESSION, like scope's: the compiler insists on every Use, so a new one cannot fall through.
        final Runnable action = switch (Use.of(use).orElse(null)) {
            case TRIP_PICTURES -> () -> tripPictures.addFromAlbumUi(picks);
            case LIBRARY -> () -> library.addFromAlbumUi(picks);
            case BADGE, PROFILE, LINK_PREVIEW, LODGING, URL, CHAT -> () -> refuse("albumAdded");
            case null -> () -> refuse("albumAdded");
        };
        action.run();
    }

    /**
     * A one-photo pick: the host takes it and reports albumPicked. A host on the shared crop dialog has then parked
     * the photo, so that dialog's two step panels are re-rendered here for it to open on its cropper.
     */
    public void pick(final String use, final String mediaId) {
        final Runnable action = switch (Use.of(use).orElse(null)) {
            case BADGE -> () -> pickToCrop(badgePhotos, mediaId);
            case PROFILE -> () -> pickToCrop(profilePhotos, mediaId);
            case LINK_PREVIEW -> () -> pickToCrop(branding, mediaId);
            case LODGING -> () -> pickToCrop(lodgingUpload, mediaId);
            case URL -> () -> library.pickFromAlbum(mediaId);
            case TRIP_PICTURES, LIBRARY, CHAT -> () -> refuse("albumPicked");
            case null -> () -> refuse("albumPicked");
        };
        action.run();
    }

    /** A host on the shared crop dialog parks the photo; its two step panels then open on the cropper. */
    private void pickToCrop(final PhotoUploadBean host, final String mediaId) {
        host.pickFromAlbum(mediaId);
        refreshCropSteps();
    }

    private void refuse(final String outcomeParam) {
        PageFeedback.error("Not available: album photos cannot be used here.");
        publish(outcomeParam, false);
    }

    // ------------------------------------------------------------------ seams

    /** Seam: the map that lives for this request (none off a page, where nothing is remembered). */
    protected Map<String, Object> requestMap() {
        return RequestMemo.facesRequestMap();
    }

    /** Seam: re-renders the shared crop dialog's two steps, which every crop host's page mounts. */
    protected void refreshCropSteps() {
        if (FacesContext.getCurrentInstance() != null) {
            PrimeFaces.current().ajax().update("photoUploadForm:photoUploadStep", "photoCropForm:photoCropStep");
        }
    }

    /** Seam: one ajax callback param for the page. */
    protected void publish(final String name, final Object value) {
        PageFeedback.callbackParam(name, value);
    }
}
