package org.paulsens.trip.web;

import java.util.Optional;
import org.paulsens.trip.action.LodgingUploadCommands;
import org.paulsens.trip.api.Beans;

/**
 * GET-only local-mode serving for lodging images ({@code /lodging-photos/lodging/...}): a hotel's photos,
 * its room-type photos and its floor plans. In production the media CDN serves these keys and this path
 * 404s, exactly like the badge-photo servlet. Public-by-URL on purpose (the CDN is), and uploads never come
 * through here -- they ride the shared JSF dialog into {@link LodgingUploadCommands}.
 */
public class LodgingPhotoServlet extends LocalKeyServlet {

    private transient LodgingUploadCommands uploads;

    void setUploads(final LodgingUploadCommands uploads) {
        this.uploads = uploads;
    }

    @Override
    protected String contentTypeOf(final String key) {
        return key.startsWith("lodging/") ? "image/jpeg" : null;
    }

    @Override
    protected Optional<byte[]> localGet(final String key) {
        return photos().localGet(key);
    }

    private LodgingUploadCommands photos() {
        LodgingUploadCommands photos = uploads;
        if (photos == null) {
            // Resolved lazily so tests can inject; Beans.get selects with Any (the @FacesConfig trap).
            photos = Beans.get(LodgingUploadCommands.class);
            uploads = photos;
        }
        return photos;
    }
}
