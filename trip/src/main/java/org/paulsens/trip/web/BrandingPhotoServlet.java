package org.paulsens.trip.web;

import java.util.Optional;
import org.paulsens.trip.action.BrandingPhotos;
import org.paulsens.trip.api.Beans;

/**
 * GET-only local-mode serving for an organization's branding images
 * ({@code /branding-photos/org/{orgId}/branding/...}) — in production the CDN serves these keys and this
 * path 404s, exactly like the profile- and badge-photo servlets. Public-by-URL on purpose: a logo, favicon
 * and link-preview image are fetched by anonymous browsers and by other sites' link unfurlers, so local mode
 * must behave the same way. Uploads do NOT come through here — they ride the shared JSF dialog's multipart
 * form into {@code BrandingUploadCommands} — so there is no POST, no CSRF header, and no multipart-config.
 */
public class BrandingPhotoServlet extends LocalKeyServlet {

    private transient BrandingPhotos brandingPhotos;

    void setBrandingPhotos(final BrandingPhotos brandingPhotos) {
        this.brandingPhotos = brandingPhotos;
    }

    // The parse is the whole validation: only a well-formed branding key of a known role can name bytes.
    @Override
    protected String contentTypeOf(final String key) {
        return BrandingPhotos.contentTypeOf(key);
    }

    @Override
    protected Optional<byte[]> localGet(final String key) {
        return photos().localGet(key);
    }

    private BrandingPhotos photos() {
        BrandingPhotos photos = brandingPhotos;
        if (photos == null) {
            // Resolved lazily so tests can inject; Beans.get selects with Any (the @FacesConfig trap).
            photos = Beans.get(BrandingPhotos.class);
            brandingPhotos = photos;
        }
        return photos;
    }
}
