package org.paulsens.trip.media;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Programmatic image fixtures. Everything except the HEIC file is generated in memory (there is no free
 * HEIC encoder to generate with — that one is a checked-in file produced by macOS {@code sips}).
 */
public final class PhotoFixtures {

    private PhotoFixtures() {
    }

    public static BufferedImage gradient(final int width, final int height, final boolean alpha) {
        final BufferedImage img = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = img.createGraphics();
        try {
            g.setPaint(new GradientPaint(0, 0, Color.ORANGE, width, height, Color.BLUE));
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return img;
    }

    public static byte[] jpeg(final int width, final int height) {
        return write(gradient(width, height, false), "jpg");
    }

    public static byte[] png(final int width, final int height, final boolean alpha) {
        return write(gradient(width, height, alpha), "png");
    }

    public static byte[] gif(final int width, final int height) {
        return write(gradient(width, height, false), "gif");
    }

    /** Two frames, which is all "animated" means to the detector. */
    public static byte[] animatedGif(final int width, final int height) {
        final ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            writer.writeToSequence(new IIOImage(gradient(width, height, false), null, null), null);
            writer.writeToSequence(new IIOImage(gradient(width, height, false), null, null), null);
            writer.endWriteSequence();
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /**
     * A real JPEG with an EXIF APP1 segment spliced in directly after SOI, carrying only an orientation
     * tag. ImageIO cannot write EXIF, so the segment is built by hand: "Exif\0\0", a little-endian TIFF
     * header, and a single-entry IFD0.
     */
    public static byte[] jpegWithOrientation(final int width, final int height, final int orientation) {
        final byte[] plain = jpeg(width, height);
        final byte[] tiff = new byte[] {
                'I', 'I', 0x2A, 0x00,               // little-endian TIFF magic
                0x08, 0x00, 0x00, 0x00,             // offset of IFD0
                0x01, 0x00,                          // one IFD entry
                0x12, 0x01,                          // tag 0x0112: orientation
                0x03, 0x00,                          // type SHORT
                0x01, 0x00, 0x00, 0x00,             // one value
                (byte) orientation, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,             // no next IFD
        };
        final byte[] exifHeader = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
        final int segmentLength = 2 + exifHeader.length + tiff.length;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2);                      // SOI
        out.write(0xFF);
        out.write(0xE1);                             // APP1
        out.write((segmentLength >> 8) & 0xFF);
        out.write(segmentLength & 0xFF);
        out.writeBytes(exifHeader);
        out.writeBytes(tiff);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    /**
     * A structurally valid PNG whose header claims 20000x20000 (400 MP). Only the IHDR needs to be honest
     * enough to parse — the dimension guard must fire before any pixel is decoded, so the missing image
     * data is the point.
     */
    public static byte[] hugePng() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        final byte[] ihdr = new byte[] {
                0x00, 0x00, 0x4E, 0x20,             // width 20000
                0x00, 0x00, 0x4E, 0x20,             // height 20000
                0x08,                                // bit depth
                0x02,                                // color type: truecolor
                0x00, 0x00, 0x00,                    // deflate, no filter, no interlace
        };
        writeChunk(out, "IHDR", ihdr);
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void writeChunk(final ByteArrayOutputStream out, final String type, final byte[] data) {
        out.write((data.length >> 24) & 0xFF);
        out.write((data.length >> 16) & 0xFF);
        out.write((data.length >> 8) & 0xFF);
        out.write(data.length & 0xFF);
        final byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        final CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        final long value = crc.getValue();
        out.write((int) (value >> 24) & 0xFF);
        out.write((int) (value >> 16) & 0xFF);
        out.write((int) (value >> 8) & 0xFF);
        out.write((int) value & 0xFF);
    }

    /** The checked-in 1200x900 HEIC produced by macOS sips. */
    public static byte[] heic() {
        return resource("/photos/sample-1200x900.heic");
    }

    /** A 600x450 sibling, under the display cap, for the no-resize HEIC branch. */
    public static byte[] smallHeic() {
        return resource("/photos/sample-600x450.heic");
    }

    private static byte[] resource(final String path) {
        try (InputStream in = PhotoFixtures.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static byte[] write(final BufferedImage img, final String format) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, format, bytes);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return bytes.toByteArray();
    }
}
