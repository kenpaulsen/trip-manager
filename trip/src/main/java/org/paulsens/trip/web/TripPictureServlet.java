package org.paulsens.trip.web;

import java.util.Optional;
import org.paulsens.trip.action.TripPictureCommands;
import org.paulsens.trip.api.Beans;

/**
 * GET-only local-mode serving for trip pictures ({@code /trip-pictures/tripPictures/...}); in production the CDN
 * serves these keys and this path 404s, exactly like the badge-photo servlet. Public by URL on purpose: the
 * production CDN serves them to anyone holding the URL, a trip's cover is shown to anonymous visitors, and local mode
 * must behave like production. Uploads do NOT come through here: they ride the shared JSF dialog's multipart form
 * into {@code TripPictureCommands}, or the REST edge's raw body, so there is no POST and no multipart-config.
 */
public class TripPictureServlet extends LocalKeyServlet {

    private transient TripPictureCommands tripPictures;

    void setTripPictures(final TripPictureCommands tripPictures) {
        this.tripPictures = tripPictures;
    }

    @Override
    protected String contentTypeOf(final String key) {
        return key.startsWith(TripPictureCommands.PREFIX) ? "image/jpeg" : null;
    }

    @Override
    protected Optional<byte[]> localGet(final String key) {
        return pictures().localGet(key);
    }

    // public, unlike the badge and lodging servlets' private: a trip picture is a cover anonymous visitors
    // see (Discover, the listing), and the production CDN caches it shared, which local mode mirrors.
    @Override
    protected String cacheControl() {
        return PUBLIC_HOUR;
    }

    private TripPictureCommands pictures() {
        TripPictureCommands pictures = tripPictures;
        if (pictures == null) {
            // Resolved lazily so tests can inject; Beans.get selects with Any (the @FacesConfig trap).
            pictures = Beans.get(TripPictureCommands.class);
            tripPictures = pictures;
        }
        return pictures;
    }
}
