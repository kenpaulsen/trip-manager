package org.paulsens.trip.web;

import java.util.Optional;
import org.paulsens.trip.action.BadgePhotoCommands;
import org.paulsens.trip.api.Beans;

/**
 * GET-only local-mode serving for custom badge images ({@code /badge-photos/badgeImages/...}) — in
 * production the CDN serves these keys and this path 404s, exactly like the profile-photo servlet.
 * Public-by-URL on purpose: the production CDN serves them to anyone holding the URL, and local mode must
 * behave like production. Uploads do NOT come through here — they ride the shared JSF dialog's multipart
 * form into {@code BadgePhotoCommands} — so there is no POST, no CSRF header, and no multipart-config.
 */
public class BadgePhotoServlet extends LocalKeyServlet {

    private transient BadgePhotoCommands badgePhotos;

    void setBadgePhotos(final BadgePhotoCommands badgePhotos) {
        this.badgePhotos = badgePhotos;
    }

    @Override
    protected String contentTypeOf(final String key) {
        return key.startsWith("badgeImages/") ? "image/jpeg" : null;
    }

    @Override
    protected Optional<byte[]> localGet(final String key) {
        return photos().localGet(key);
    }

    private BadgePhotoCommands photos() {
        BadgePhotoCommands photos = badgePhotos;
        if (photos == null) {
            // Resolved lazily so tests can inject; Beans.get selects with Any (the @FacesConfig trap).
            photos = Beans.get(BadgePhotoCommands.class);
            badgePhotos = photos;
        }
        return photos;
    }
}
