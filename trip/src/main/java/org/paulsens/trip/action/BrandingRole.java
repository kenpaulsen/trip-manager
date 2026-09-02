package org.paulsens.trip.action;

import java.util.Optional;
import lombok.Getter;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.model.SettingDef;

/**
 * The four org-site images an administrator can upload on the Appearance page, each with the shape, the
 * output format and the size budget its job actually requires. One enum rather than four beans because the
 * shared crop dialog ({@code WEB-INF/photoUploadDialog.xhtml}) has fixed component ids and one
 * {@code widgetVar}: a view may mount it exactly once, so the role has to travel WITH the upload rather than
 * with the include.
 *
 * <p>Why each role differs:
 *
 * <ul>
 * <li><b>Logo</b> — free crop (a logo is rarely square) and PNG output, because both the square pipeline and
 *     the JPEG encoder flatten alpha onto white, which would draw a white box behind a transparent logo on
 *     every page.</li>
 * <li><b>Favicon</b> — square, and a small PNG. Browsers accept PNG for {@code rel="icon"}, and this
 *     codebase's sniffer supports neither ICO nor SVG, so PNG is the only honest answer.</li>
 * <li><b>Link preview</b> — 1.91:1, the Open Graph shape (1200x630 ideal, 600x315 the minimum worth
 *     sending), as JPEG.</li>
 * <li><b>Background</b> — free crop, wide (it is stretched across a whole page), and hard-capped in BYTES:
 *     it loads on every page of the site.</li>
 * </ul>
 */
@Getter
public enum BrandingRole {

    /** The top bar's logo: any shape, transparency kept, no bigger than the recommended 512 box. */
    LOGO("logo", KnownSettings.SITE_LOGO_URL, "Logo image", 0d, 512, 512, "png", "image/png", 0L, 512),

    /** The browser tab icon: square, and one sound single size rather than a family of them. */
    FAVICON("favicon", KnownSettings.SITE_FAVICON_URL, "Favicon", 1d, 48, 48, "png", "image/png", 0L, 48),

    /** What chat apps and social sites show for a link to the site: Open Graph's 1200x630. */
    OG_IMAGE("ogImage", KnownSettings.SITE_OG_IMAGE_URL, "Link preview image", 1200d / 630d,
            1200, 630, "jpg", "image/jpeg", 1024L * 1024L, 600),

    /** The picture behind every page: wide, and never more than a megabyte. */
    BACKGROUND("background", KnownSettings.SITE_BACKGROUND_URL, "Page background image", 0d,
            1920, 1920, "jpg", "image/jpeg", 1024L * 1024L, 1920);

    /**
     * The key that names this role in a URL, an object key and the session while the dialog is open. Short,
     * stable and safe in an S3 key — never the enum's own {@code name()}, which would put {@code OG_IMAGE}
     * in stored keys and make renaming the constant a data migration.
     */
    private final String key;

    /** The Branding setting this role's uploaded URL is written into. */
    private final SettingDef setting;

    /** What the dialog header and the page's controls call it. */
    private final String label;

    /** The crop's forced width/height ratio; zero or less means the admin crops freely. */
    private final double aspect;

    /** The stored image's size caps: a bound for the free roles, the exact size for the favicon. */
    private final int maxWidth;
    private final int maxHeight;

    /** The stored object's file extension and content type. */
    private final String extension;
    private final String contentType;

    /** Byte budget for the encoded result, or 0 for "no byte cap, only the pixel caps above". */
    private final long maxBytes;

    /**
     * The longest side, in SOURCE pixels, below which the chosen area is worth a warning. A warning and not
     * a refusal: the person holding the only copy of their own logo gets to decide (the rule badge pictures
     * already follow).
     */
    private final int recommendedLongSide;

    BrandingRole(final String key, final SettingDef setting, final String label, final double aspect,
            final int maxWidth, final int maxHeight, final String extension, final String contentType,
            final long maxBytes, final int recommendedLongSide) {
        this.key = key;
        this.setting = setting;
        this.label = label;
        this.aspect = aspect;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.extension = extension;
        this.contentType = contentType;
        this.maxBytes = maxBytes;
        this.recommendedLongSide = recommendedLongSide;
    }

    /** @return the role with this {@link #getKey() key}, or empty — a page-supplied name is untrusted. */
    public static Optional<BrandingRole> of(final String key) {
        if (key == null) {
            return Optional.empty();
        }
        final String wanted = key.trim();
        for (final BrandingRole role : values()) {
            if (role.key.equals(wanted)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }

    /**
     * The crop dialog's {@code cropAspect} parameter: the ratio as a string, or blank for a free crop. Blank
     * rather than zero because the dialog picks its cropper variant on emptiness — {@code aspectRatio} is a
     * primitive double, so "unset" cannot be expressed as a value.
     */
    public String getCropAspect() {
        return aspect <= 0 ? "" : String.valueOf(aspect);
    }
}
