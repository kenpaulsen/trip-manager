package org.paulsens.trip.media;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

/**
 * The image types a chat photo may be, detected from the leading bytes. Detection deliberately ignores both
 * the declared {@code Content-Type} and the file extension: the browser's claim is attacker-controlled, and
 * an S3 object served with an image content type must actually BE that image, or the upload endpoint becomes
 * a way to host arbitrary files on the site's CDN.
 */
public enum ImageFormat {

    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    GIF("image/gif", "gif"),
    HEIC("image/heic", "heic");

    /**
     * HEIF container brands that mean "a still photo libheif can decode". iPhones write {@code heic}/{@code
     * heix} (and {@code mif1} as the compatible fallback brand). Sequences/video brands are excluded on
     * purpose, as is AVIF: whether the system libheif can decode AV1 depends on which codecs it was built
     * with, and advertising a format that fails at decode time is worse than not listing it.
     */
    private static final Set<String> HEIC_BRANDS = Set.of("heic", "heix", "heim", "heis", "mif1", "msf1");

    private final String contentType;
    private final String extension;

    ImageFormat(final String contentType, final String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String getContentType() {
        return contentType;
    }

    /** @return the filename extension used for stored objects, without the dot. */
    public String getExtension() {
        return extension;
    }

    /** @return the format the bytes actually are, or empty when they are none of the supported ones. */
    public static Optional<ImageFormat> detect(final byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return Optional.empty();
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return Optional.of(JPEG);
        }
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && (bytes[4] & 0xFF) == 0x0D && (bytes[5] & 0xFF) == 0x0A
                && (bytes[6] & 0xFF) == 0x1A && (bytes[7] & 0xFF) == 0x0A) {
            return Optional.of(PNG);
        }
        final String gifHeader = new String(bytes, 0, 6, StandardCharsets.US_ASCII);
        if ("GIF87a".equals(gifHeader) || "GIF89a".equals(gifHeader)) {
            return Optional.of(GIF);
        }
        // ISO BMFF: [4-byte box size]"ftyp"[4-byte major brand]. The box size varies, the offsets do not.
        final String boxType = new String(bytes, 4, 4, StandardCharsets.US_ASCII);
        if ("ftyp".equals(boxType) && HEIC_BRANDS.contains(new String(bytes, 8, 4, StandardCharsets.US_ASCII))) {
            return Optional.of(HEIC);
        }
        return Optional.empty();
    }
}
