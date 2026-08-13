package org.paulsens.trip.web;

import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.util.LinkedHashMap;
import java.util.Map;
import org.primefaces.util.Constants;

/**
 * The session-held registry PrimeFaces uses to serve dynamic content, in a shape that survives Kryo.
 *
 * <p>PrimeFaces renders every streamed resource — the upload dialog's crop preview, a {@code p:barcode} QR, any
 * {@code p:graphicImage} bound to a {@code StreamedContent} — as a URL carrying an md5 {@code pfdrid}, and parks
 * the resource's EL string in the session under {@link Constants#DYNAMIC_RESOURCES_MAPPING}. The later image
 * request resolves the {@code pfdrid} through that map; miss it and {@code StreamedContentHandler} writes an
 * empty <b>200</b> and the browser shows a broken image.
 *
 * <p>PrimeFaces' own map for this is {@code LimitedSizeHashMap}, whose cap is a {@code private final int} set
 * only by its one-argument constructor. Redisson serialises sessions with Kryo, which writes a Map subclass
 * through {@code MapSerializer} — entries only, no fields — and rebuilds it via Objenesis, so the cap comes back
 * as <b>0</b>. {@code removeEldestEntry} then answers {@code size() > 0} and evicts every entry the instant it is
 * added: the map is silently, permanently empty for the rest of that session's life. Proven against the deployed
 * jars (kryo 5.6.2, redisson 4.6.1, PrimeFaces 15.0.13); it took production down on 2026-08-13, where every
 * cropper request after an 18:03 UTC task replacement answered 200 with an empty body while the same requests
 * minutes earlier on the previous task served ~92 KB.
 *
 * <p>The fix is the cap being a <b>constant rather than a field</b>. There is nothing for Kryo to drop, so this
 * map behaves identically however it is reconstructed. {@link #repair} seats it in the session before PrimeFaces
 * would create its own — PrimeFaces only builds one when the attribute is absent, so ours is the one that gets
 * used.
 *
 * <p>The wider rule this is an instance of: <b>a class that reaches the session must not depend on a constructor
 * having run.</b> Kryo sets no {@code final} field it did not write and calls no constructor. Defaults assigned
 * at construction come back as 0/false/null, which is not a crash — it is behaviour that is quietly wrong,
 * exactly like an empty map that accepts every put. Prefer constants, or values recomputed on read.
 */
public final class DynamicResourceMap extends LinkedHashMap<String, String> {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Matches PrimeFaces' own default ({@code primefaces.DYNAMIC_CONTENT_LIMIT}). Deliberately a constant and
     * not a field — a field is the entire bug this class exists for.
     */
    static final int MAX_ENTRIES = 1000;

    @Override
    protected boolean removeEldestEntry(final Map.Entry<String, String> eldest) {
        return size() > MAX_ENTRIES;
    }

    /**
     * Installs this map, replacing anything that is not already one.
     *
     * <p>Runs per request rather than once at session creation so that sessions <b>already</b> holding a
     * flattened {@code LimitedSizeHashMap} — every session that outlived a task replacement — are healed on their
     * next request instead of staying broken until they expire. A healthy session costs one identity check.
     *
     * <p>Nothing is carried over from the replaced map: the only map this replaces is one that cannot hold an
     * entry, so there is never anything in it to keep.
     */
    static void repair(final HttpSession session) {
        if (session == null) {
            return;
        }
        if (!(session.getAttribute(Constants.DYNAMIC_RESOURCES_MAPPING) instanceof DynamicResourceMap)) {
            // setAttribute, not an in-place mutation: that is also what tells Redisson the session changed.
            session.setAttribute(Constants.DYNAMIC_RESOURCES_MAPPING, new DynamicResourceMap());
        }
    }
}
