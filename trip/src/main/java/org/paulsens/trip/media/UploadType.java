package org.paulsens.trip.media;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Content-Type a library upload is STORED (and therefore served) with, decided here and never taken from
 * the browser.
 *
 * <p>Every organization's files are served from one media host that is same-site with the main site. An
 * upload stored with the type its uploader claimed let an organization's media editor publish
 * {@code guide.html} as {@code text/html} there: stored script on a host all tenants share, able to set cookies
 * for the parent domain and to pose as the platform. So a file is served inline only as something that cannot
 * run: an image or a PDF recognized from its own bytes, or a video or audio type by extension. Everything
 * else is still stored (nothing an editor uploads is refused), but as {@code application/octet-stream} with
 * {@code Content-Disposition: attachment}, which a browser downloads and never renders. SVG is "everything
 * else": it is an image that carries script.
 *
 * @param contentType what S3 (and the CDN in front of it) will answer.
 * @param attachment  whether the object must be served as a download.
 */
public record UploadType(String contentType, boolean attachment) {
    /** The type of anything that is only ever downloaded. */
    public static final String DOWNLOAD = "application/octet-stream";

    /** How many leading bytes {@link #of} needs to recognize a file. */
    public static final int HEAD_BYTES = 64;

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    /** Inert types trusted by extension: a browser plays them or fails, it never runs them as a page. */
    private static final Map<String, String> PLAYABLE = Map.of(
            "mp4", "video/mp4", "m4v", "video/mp4", "mov", "video/quicktime", "webm", "video/webm",
            "mp3", "audio/mpeg", "m4a", "audio/mp4", "wav", "audio/wav");

    /** What a direct (presigned) upload may DECLARE, since its bytes never pass through the server. */
    private static final Set<String> DECLARABLE = Set.of("image/jpeg", "image/png", "image/gif", "image/heic",
            "image/heif", "image/webp", "application/pdf", "video/mp4", "video/quicktime", "video/webm",
            "audio/mpeg", "audio/mp4", "audio/wav", DOWNLOAD);

    /**
     * The stored type of a file named {@code fileName} whose first bytes are {@code head}. The browser's
     * claim is deliberately not a parameter.
     */
    public static UploadType of(final String fileName, final byte[] head) {
        final byte[] bytes = (head == null) ? new byte[0] : head;
        final String image = ImageFormat.detect(bytes).map(ImageFormat::getContentType).orElse(null);
        if (image != null) {
            return new UploadType(image, false);
        }
        if (startsWith(bytes, PDF_MAGIC)) {
            return new UploadType("application/pdf", false);
        }
        final String playable = PLAYABLE.get(extensionOf(fileName));
        return (playable == null) ? new UploadType(DOWNLOAD, true) : new UploadType(playable, false);
    }

    /**
     * Whether a direct upload may be signed for {@code declared}: only types that are inert whatever the
     * bytes turn out to be (an HTML file served as {@code image/png} is a broken image, not a page).
     */
    public static boolean mayDeclare(final String declared) {
        if (declared == null) {
            return false;
        }
        final int params = declared.indexOf(';');
        final String bare = (params < 0 ? declared : declared.substring(0, params)).trim().toLowerCase(Locale.ROOT);
        return DECLARABLE.contains(bare);
    }

    private static boolean startsWith(final byte[] bytes, final byte[] magic) {
        if (bytes.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static String extensionOf(final String fileName) {
        final int dot = (fileName == null) ? -1 : fileName.lastIndexOf('.');
        return (dot < 0) ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
