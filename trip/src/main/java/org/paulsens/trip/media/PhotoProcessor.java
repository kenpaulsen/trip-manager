package org.paulsens.trip.media;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns one uploaded chat photo into its two stored renditions: the full-size original and a display copy no
 * wider than {@link #MAX_SMALL_WIDTH}. Stateless and thread-safe.
 *
 * <p>The rules, one per format, chosen so the ORIGINAL BYTES survive wherever possible (the user's explicit
 * decision — metadata included — 2026-08-07):
 * <ul>
 * <li><b>JPEG / PNG:</b> full = the untouched upload. The display copy is a re-encode only when the oriented
 *     width exceeds the cap; otherwise it IS the original and only one object gets stored.</li>
 * <li><b>GIF:</b> an animated GIF is never resized — a scaled first frame would silently discard the
 *     animation, so the original serves as both renditions and keeps playing in the chat (CSS caps the
 *     display width). A static GIF behaves like a PNG, with the display copy encoded as PNG.</li>
 * <li><b>HEIC:</b> decoded natively (libheif via the ImageIO plugin) and transcoded to a full-resolution
 *     ~q95 JPEG, because a .heic download is unopenable for half its recipients (user decision). libheif
 *     applies the container's rotation boxes during decode, so no EXIF correction is layered on top —
 *     doing both would rotate iPhone portraits twice.</li>
 * </ul>
 *
 * <p>JPEG re-encodes bake the EXIF orientation into the pixels: metadata does not survive a re-encode here,
 * so a display copy that relied on its (gone) orientation tag would render every portrait sideways.
 */
@Slf4j
public final class PhotoProcessor {

    /** Display rendition cap. Width only, per the feature spec — a tall portrait may exceed 800 high. */
    public static final int MAX_SMALL_WIDTH = 800;

    /**
     * Decode-bomb guard, checked from the header BEFORE allocating pixel buffers: a 10 MB upload cap does
     * not bound decoded size (a tiny PNG can claim absurd dimensions and the decoder will faithfully
     * allocate them). 100 MP clears every phone and stitched panorama by a wide margin.
     */
    static final long MAX_PIXELS = 100_000_000L;

    private static final float SMALL_JPEG_QUALITY = 0.85f;
    private static final float FULL_JPEG_QUALITY = 0.95f;

    /**
     * @param original the uploaded bytes, already size-capped by the servlet.
     * @return both renditions.
     * @throws PhotoRejectedException with a user-facing message when the bytes are not a supported image,
     *         claim absurd dimensions, or are HEIC on a host with no working libheif.
     */
    public ProcessedPhoto process(final byte[] original) {
        final ImageFormat format = ImageFormat.detect(original).orElseThrow(PhotoProcessor::notAnImage);
        return switch (format) {
            case JPEG -> processJpeg(original);
            case PNG -> processPng(original);
            case GIF -> processGif(original);
            case HEIC -> processHeic(original);
        };
    }

    private ProcessedPhoto processJpeg(final byte[] original) {
        final int orientation = readExifOrientation(original);
        final BufferedImage decoded = decode(original, ImageFormat.JPEG);
        final BufferedImage oriented = applyOrientation(decoded, orientation);
        if (oriented.getWidth() <= MAX_SMALL_WIDTH) {
            return passThrough(original, ImageFormat.JPEG, oriented);
        }
        final byte[] small = encodeJpeg(resizeToWidth(oriented, MAX_SMALL_WIDTH), SMALL_JPEG_QUALITY);
        return new ProcessedPhoto(original, ImageFormat.JPEG.getContentType(), ImageFormat.JPEG.getExtension(),
                small, ImageFormat.JPEG.getContentType(), ImageFormat.JPEG.getExtension(),
                oriented.getWidth(), oriented.getHeight());
    }

    private ProcessedPhoto processPng(final byte[] original) {
        final BufferedImage decoded = decode(original, ImageFormat.PNG);
        if (decoded.getWidth() <= MAX_SMALL_WIDTH) {
            return passThrough(original, ImageFormat.PNG, decoded);
        }
        final byte[] small = encodePng(resizeToWidth(decoded, MAX_SMALL_WIDTH));
        return new ProcessedPhoto(original, ImageFormat.PNG.getContentType(), ImageFormat.PNG.getExtension(),
                small, ImageFormat.PNG.getContentType(), ImageFormat.PNG.getExtension(),
                decoded.getWidth(), decoded.getHeight());
    }

    private ProcessedPhoto processGif(final byte[] original) {
        if (isAnimatedGif(original)) {
            final BufferedImage firstFrame = decode(original, ImageFormat.GIF);
            return passThrough(original, ImageFormat.GIF, firstFrame);
        }
        final BufferedImage decoded = decode(original, ImageFormat.GIF);
        if (decoded.getWidth() <= MAX_SMALL_WIDTH) {
            return passThrough(original, ImageFormat.GIF, decoded);
        }
        final byte[] small = encodePng(resizeToWidth(decoded, MAX_SMALL_WIDTH));
        return new ProcessedPhoto(original, ImageFormat.GIF.getContentType(), ImageFormat.GIF.getExtension(),
                small, ImageFormat.PNG.getContentType(), ImageFormat.PNG.getExtension(),
                decoded.getWidth(), decoded.getHeight());
    }

    private ProcessedPhoto processHeic(final byte[] original) {
        final BufferedImage decoded;
        try {
            decoded = decode(original, ImageFormat.HEIC);
        } catch (final PhotoRejectedException ex) {
            throw ex;
        } catch (final RuntimeException | Error ex) {
            // The plugin's native layer can fail in ways plain ImageIO never does (UnsatisfiedLinkError and
            // friends). Every one of them means the same thing to the person uploading.
            log.warn("HEIC decode failed", ex);
            throw heicUnavailable();
        }
        final byte[] full = encodeJpeg(decoded, FULL_JPEG_QUALITY);
        if (decoded.getWidth() <= MAX_SMALL_WIDTH) {
            return new ProcessedPhoto(full, ImageFormat.JPEG.getContentType(), ImageFormat.JPEG.getExtension(),
                    full, ImageFormat.JPEG.getContentType(), ImageFormat.JPEG.getExtension(),
                    decoded.getWidth(), decoded.getHeight());
        }
        final byte[] small = encodeJpeg(resizeToWidth(decoded, MAX_SMALL_WIDTH), SMALL_JPEG_QUALITY);
        return new ProcessedPhoto(full, ImageFormat.JPEG.getContentType(), ImageFormat.JPEG.getExtension(),
                small, ImageFormat.JPEG.getContentType(), ImageFormat.JPEG.getExtension(),
                decoded.getWidth(), decoded.getHeight());
    }

    /** Both renditions are the untouched original; the caller stores one object. */
    private static ProcessedPhoto passThrough(final byte[] original, final ImageFormat format,
            final BufferedImage oriented) {
        return new ProcessedPhoto(original, format.getContentType(), format.getExtension(),
                original, format.getContentType(), format.getExtension(),
                oriented.getWidth(), oriented.getHeight());
    }

    /**
     * Decodes with the dimension guard applied from the header first. Returns the first image; throws the
     * appropriate user-facing rejection when no reader volunteers (for HEIC that means "no libheif here").
     */
    private static BufferedImage decode(final byte[] bytes, final ImageFormat format) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw (format == ImageFormat.HEIC) ? heicUnavailable() : notAnImage();
            }
            final ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                final long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw new PhotoRejectedException("That image's dimensions are too large to process.");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (final PhotoRejectedException ex) {
            throw ex;
        } catch (final IOException | RuntimeException ex) {
            // RuntimeException too: a corrupt file can surface from deep inside a decoder as almost
            // anything, and every one of them means the same thing to the person uploading.
            log.info("Rejecting undecodable {} upload", format, ex);
            throw new PhotoRejectedException("That file could not be read as an image. It may be corrupted.",
                    ex);
        }
    }

    /** @return true when the GIF has more than one frame. */
    private static boolean isAnimatedGif(final byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw notAnImage();
            }
            final ImageReader reader = readers.next();
            try {
                reader.setInput(in, false, true);
                return reader.getNumImages(true) > 1;
            } finally {
                reader.dispose();
            }
        } catch (final PhotoRejectedException ex) {
            throw ex;
        } catch (final IOException | RuntimeException ex) {
            throw new PhotoRejectedException("That file could not be read as an image. It may be corrupted.",
                    ex);
        }
    }

    /**
     * @return the EXIF orientation (1-8), or 1 (upright) when there is none or it cannot be read. A photo
     *         with unreadable metadata should upload fine and merely risk rendering sideways, not fail.
     */
    static int readExifOrientation(final byte[] bytes) {
        try {
            final Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            final ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir == null || !dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return 1;
            }
            final int orientation = dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            return (orientation >= 1 && orientation <= 8) ? orientation : 1;
        } catch (final Exception ex) {
            log.debug("Unreadable EXIF; assuming upright", ex);
            return 1;
        }
    }

    /** Bakes an EXIF orientation into the pixels. Orientation 1 returns the image untouched. */
    static BufferedImage applyOrientation(final BufferedImage img, final int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return img;
        }
        final int w = img.getWidth();
        final int h = img.getHeight();
        // 5-8 involve a 90-degree rotation, so the canvas transposes.
        final boolean transposed = orientation >= 5;
        final AffineTransform tx = orientationTransform(orientation, w, h);
        final BufferedImage out = new BufferedImage(transposed ? h : w, transposed ? w : h, pixelType(img));
        final Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, tx, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** The standard eight EXIF cases, expressed as the transform that puts pixel (0,0) where it belongs. */
    private static AffineTransform orientationTransform(final int orientation, final int w, final int h) {
        final AffineTransform tx = new AffineTransform();
        switch (orientation) {
            case 2 -> {
                tx.scale(-1, 1);
                tx.translate(-w, 0);
            }
            case 3 -> {
                tx.translate(w, h);
                tx.rotate(Math.PI);
            }
            case 4 -> {
                tx.scale(1, -1);
                tx.translate(0, -h);
            }
            case 5 -> {
                tx.rotate(Math.PI / 2);
                tx.scale(1, -1);
            }
            case 6 -> {
                tx.translate(h, 0);
                tx.rotate(Math.PI / 2);
            }
            case 7 -> {
                tx.scale(-1, 1);
                tx.translate(-h, 0);
                tx.translate(0, w);
                tx.rotate(3 * Math.PI / 2);
            }
            case 8 -> {
                tx.translate(0, w);
                tx.rotate(3 * Math.PI / 2);
            }
            default -> throw new IllegalArgumentException("orientation " + orientation);
        }
        return tx;
    }

    /**
     * High-quality downscale to exactly {@code targetWidth}: successive halvings while the image is more
     * than twice the target, then one final bilinear step. A single-step {@code drawImage} from, say, 4000px
     * to 800px drops most source pixels entirely and shows it as aliasing; halving keeps every step within
     * the interpolator's reach. Cheaper than area-averaging and visually indistinguishable at chat sizes.
     */
    static BufferedImage resizeToWidth(final BufferedImage src, final int targetWidth) {
        BufferedImage current = src;
        while (current.getWidth() / 2 > targetWidth) {
            current = scale(current, current.getWidth() / 2,
                    Math.max(1, current.getHeight() / 2), pixelType(src));
        }
        final int targetHeight = Math.max(1,
                Math.round(src.getHeight() * (targetWidth / (float) src.getWidth())));
        return scale(current, targetWidth, targetHeight, pixelType(src));
    }

    private static BufferedImage scale(final BufferedImage src, final int w, final int h, final int type) {
        final BufferedImage out = new BufferedImage(w, h, type);
        final Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static int pixelType(final BufferedImage src) {
        return src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    }

    /** JPEG has no alpha: transparent sources are composited onto white first, not left to the encoder. */
    static byte[] encodeJpeg(final BufferedImage img, final float quality) {
        final BufferedImage opaque = img.getColorModel().hasAlpha() ? flattenOntoWhite(img) : img;
        final ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            final ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(opaque, null, null), param);
        } catch (final IOException ex) {
            throw new IllegalStateException("In-memory JPEG encode failed", ex);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static BufferedImage flattenOntoWhite(final BufferedImage img) {
        final BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.drawImage(img, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    static byte[] encodePng(final BufferedImage img) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", bytes);
        } catch (final IOException ex) {
            throw new IllegalStateException("In-memory PNG encode failed", ex);
        }
        return bytes.toByteArray();
    }

    /** @return whether this JVM can decode HEIC right now (i.e. the plugin found a working libheif). */
    public static boolean isHeicSupported() {
        return ImageIO.getImageReadersByFormatName("heif").hasNext();
    }

    private static PhotoRejectedException notAnImage() {
        return new PhotoRejectedException("That file is not a supported image. Use JPG, PNG, GIF, or HEIC.");
    }

    private static PhotoRejectedException heicUnavailable() {
        return new PhotoRejectedException(
                "HEIC photos can't be processed here right now. Please convert it to JPEG and try again.");
    }
}
