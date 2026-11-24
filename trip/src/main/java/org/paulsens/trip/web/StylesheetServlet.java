package org.paulsens.trip.web;

import java.util.Optional;
import org.paulsens.trip.action.TripStylesheets;
import org.paulsens.trip.api.Beans;

/**
 * GET-only local-mode serving for a trip page's own stylesheets ({@code /trip-stylesheets/org/{orgId}/stylesheets/
 * {slug}.css}; docs/conference.md "Look and feel"). In production the media CDN serves these keys and this path
 * 404s, exactly like the branding-photo servlet. Public by URL on purpose: a page's stylesheet is fetched by every
 * anonymous browser. Never cached: the editor overwrites the same key, and the link's {@code ?r=} is for browsers
 * talking to the CDN.
 */
public class StylesheetServlet extends LocalKeyServlet {

    private transient TripStylesheets stylesheets;

    void setStylesheets(final TripStylesheets stylesheets) {
        this.stylesheets = stylesheets;
    }

    // The key's shape is the whole validation: only one of our stylesheet keys can name any bytes at all.
    @Override
    protected String contentTypeOf(final String key) {
        return TripStylesheets.isStylesheetKey(key) ? "text/css; charset=utf-8" : null;
    }

    @Override
    protected Optional<byte[]> localGet(final String key) {
        return store().localGet(key);
    }

    @Override
    protected String cacheControl() {
        return "no-cache";
    }

    private TripStylesheets store() {
        TripStylesheets store = stylesheets;
        if (store == null) {
            // Resolved lazily so tests can inject; Beans.get selects with Any (the @FacesConfig trap).
            store = Beans.get(TripStylesheets.class);
            stylesheets = store;
        }
        return store;
    }
}
