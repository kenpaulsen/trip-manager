package org.paulsens.trip.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The {@code Content-Disposition} of a download, spelled once (RFC 6266): {@code filename="..."} reduced to
 * quote-free printable ASCII, which every client reads, and {@code filename*=UTF-8''...} carrying the real name
 * for every current browser.
 *
 * <p>The download servlets built both forms and the REST API only the ASCII one, so the same PDF saved as
 * "Međugorje.pdf" from the website and "Me_ugorje.pdf" from the API. The ASCII form is never dropped: it is the
 * fallback for a client that does not read the starred one.
 */
public final class ContentDisposition {

    private ContentDisposition() {
    }

    /** A file the browser saves under this name. */
    public static String attachment(final String fileName) {
        return of("attachment", fileName);
    }

    /** A file the browser shows in place, and names this if saved. */
    public static String inline(final String fileName) {
        return of("inline", fileName);
    }

    private static String of(final String type, final String fileName) {
        // Outside 0x20-0x7E goes (CR and LF with it, so a name can never split the header); a quote would end it.
        final String ascii = fileName.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'");
        final String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return type + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
