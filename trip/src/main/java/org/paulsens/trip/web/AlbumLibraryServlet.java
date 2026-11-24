package org.paulsens.trip.web;

import java.util.Optional;
import org.paulsens.trip.action.AlbumLibrary;
import org.paulsens.trip.api.Beans;

/**
 * GET-only local-mode serving for album photos copied into the media library
 * ({@code /album-library/[org/{orgId}/]album-photos/{number}.jpg}; {@link AlbumLibrary}). In production the media
 * CDN serves these keys and this path 404s, exactly like the trip stylesheet servlet. Public by URL on purpose: a
 * library picture is what public pages show.
 */
public class AlbumLibraryServlet extends LocalKeyServlet {

    private transient AlbumLibrary library;

    void setLibrary(final AlbumLibrary library) {
        this.library = library;
    }

    // The key's shape is the whole validation, and the library checks it: only one of our own keys can name any
    // bytes at all, so every key is asked for.
    @Override
    protected String contentTypeOf(final String key) {
        return "image/jpeg";
    }

    @Override
    protected Optional<byte[]> localGet(final String key) {
        return store().localGet(key);
    }

    @Override
    protected String cacheControl() {
        return PUBLIC_HOUR;
    }

    private AlbumLibrary store() {
        AlbumLibrary store = library;
        if (store == null) {
            // Resolved lazily so tests can inject; Beans.get selects with Any (the @FacesConfig trap).
            store = Beans.get(AlbumLibrary.class);
            library = store;
        }
        return store;
    }
}
