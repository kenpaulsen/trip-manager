package org.paulsens.trip.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class PhotoProcessorTest {

    private final PhotoProcessor processor = new PhotoProcessor();

    @Test
    public void jpegAtOrUnderTheCapPassesThroughUntouched() throws IOException {
        final byte[] input = PhotoFixtures.jpeg(600, 400);
        final ProcessedPhoto result = processor.process(input);
        assertSame(result.fullBytes(), input);
        assertTrue(result.smallIsFull());
        assertEquals(result.fullContentType(), "image/jpeg");
        assertEquals(result.width(), 600);
        assertEquals(result.height(), 400);
    }

    @Test
    public void jpegOverTheCapKeepsFullBytesAndResizesSmall() throws IOException {
        final byte[] input = PhotoFixtures.jpeg(1600, 1200);
        final ProcessedPhoto result = processor.process(input);
        assertSame(result.fullBytes(), input, "The stored original must be byte-identical to the upload");
        assertTrue(!result.smallIsFull());
        final BufferedImage small = decode(result.smallBytes());
        assertEquals(small.getWidth(), 800);
        assertEquals(small.getHeight(), 600);
        assertEquals(ImageFormat.detect(result.smallBytes()).orElseThrow(), ImageFormat.JPEG);
        assertEquals(result.width(), 1600);
        assertEquals(result.height(), 1200);
    }

    @Test
    public void exifPortraitReportsOrientedDimensionsAndResizesByThem() throws IOException {
        // 1600x1000 stored pixels, orientation 6 (90 CW): displays as 1000x1600.
        final byte[] input = PhotoFixtures.jpegWithOrientation(1600, 1000, 6);
        final ProcessedPhoto result = processor.process(input);
        assertEquals(result.width(), 1000);
        assertEquals(result.height(), 1600);
        final BufferedImage small = decode(result.smallBytes());
        assertEquals(small.getWidth(), 800);
        assertEquals(small.getHeight(), 1280);
        assertSame(result.fullBytes(), input);
    }

    @Test
    public void exifOrientationIsReadAndBoundsChecked() {
        assertEquals(PhotoProcessor.readExifOrientation(PhotoFixtures.jpegWithOrientation(20, 10, 6)), 6);
        assertEquals(PhotoProcessor.readExifOrientation(PhotoFixtures.jpegWithOrientation(20, 10, 9)), 1);
        assertEquals(PhotoProcessor.readExifOrientation(PhotoFixtures.jpeg(20, 10)), 1);
        assertEquals(PhotoProcessor.readExifOrientation(new byte[] {1, 2, 3}), 1);
    }

    /**
     * All eight EXIF cases against a quadrant-marker image: R G / B W. Each case asserts where every
     * quadrant landed, which catches a transform that compiles but rotates the wrong way.
     */
    @Test
    public void allEightOrientationsPutEveryQuadrantWhereItBelongs() {
        final Color r = Color.RED;
        final Color g = Color.GREEN;
        final Color b = Color.BLUE;
        final Color w = Color.WHITE;
        assertQuadrants(1, r, g, b, w);
        assertQuadrants(2, g, r, w, b);
        assertQuadrants(3, w, b, g, r);
        assertQuadrants(4, b, w, r, g);
        assertQuadrants(5, r, b, g, w);
        assertQuadrants(6, b, r, w, g);
        assertQuadrants(7, w, g, b, r);
        assertQuadrants(8, g, w, r, b);
    }

    @Test
    public void pngWithAlphaResizesToPngAndKeepsItsAlphaChannel() throws IOException {
        final byte[] input = PhotoFixtures.png(1600, 900, true);
        final ProcessedPhoto result = processor.process(input);
        assertSame(result.fullBytes(), input);
        assertEquals(ImageFormat.detect(result.smallBytes()).orElseThrow(), ImageFormat.PNG);
        final BufferedImage small = decode(result.smallBytes());
        assertEquals(small.getWidth(), 800);
        assertTrue(small.getColorModel().hasAlpha());
    }

    @Test
    public void smallPngPassesThrough() {
        final byte[] input = PhotoFixtures.png(300, 200, false);
        final ProcessedPhoto result = processor.process(input);
        assertTrue(result.smallIsFull());
        assertEquals(result.width(), 300);
    }

    @Test
    public void animatedGifIsNeverResized() {
        final byte[] input = PhotoFixtures.animatedGif(1000, 500);
        final ProcessedPhoto result = processor.process(input);
        assertSame(result.fullBytes(), input);
        assertTrue(result.smallIsFull(), "Resizing an animation would discard every frame but one");
        assertEquals(result.fullContentType(), "image/gif");
        assertEquals(result.width(), 1000);
    }

    @Test
    public void staticGifOverTheCapResizesToPng() throws IOException {
        final byte[] input = PhotoFixtures.gif(1000, 500);
        final ProcessedPhoto result = processor.process(input);
        assertSame(result.fullBytes(), input);
        assertEquals(result.fullContentType(), "image/gif");
        assertEquals(result.smallContentType(), "image/png");
        assertEquals(decode(result.smallBytes()).getWidth(), 800);
    }

    @Test
    public void smallStaticGifPassesThrough() {
        final byte[] input = PhotoFixtures.gif(120, 80);
        final ProcessedPhoto result = processor.process(input);
        assertTrue(result.smallIsFull());
        assertEquals(result.fullContentType(), "image/gif");
    }

    @Test
    public void garbageIsRejectedWithAWordAboutFormats() {
        final PhotoRejectedException ex = expectThrows(PhotoRejectedException.class,
                () -> processor.process("Not an image at all, sorry".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(ex.getMessage().contains("JPG"), ex.getMessage());
    }

    @Test
    public void dimensionBombIsRejectedBeforeDecoding() {
        final PhotoRejectedException ex = expectThrows(PhotoRejectedException.class,
                () -> processor.process(PhotoFixtures.hugePng()));
        assertTrue(ex.getMessage().contains("dimensions"), ex.getMessage());
    }

    @Test
    public void truncatedJpegIsRejected() {
        final byte[] whole = PhotoFixtures.jpeg(600, 400);
        final byte[] truncated = new byte[24];
        System.arraycopy(whole, 0, truncated, 0, truncated.length);
        assertThrows(PhotoRejectedException.class, () -> processor.process(truncated));
    }

    /**
     * Runs whichever HEIC branch this machine has: with a working libheif the full transcode pipeline is
     * asserted; without one, the graceful rejection is. Both are real behaviors of real deployments — the
     * container installs libheif, a bare laptop may not.
     */
    @Test
    public void heicEitherTranscodesOrRejectsHelpfully() throws IOException {
        final byte[] input = PhotoFixtures.heic();
        if (PhotoProcessor.isHeicSupported()) {
            final ProcessedPhoto result = processor.process(input);
            assertEquals(result.fullContentType(), "image/jpeg");
            assertEquals(result.fullExtension(), "jpg");
            assertEquals(result.width(), 1200);
            assertEquals(result.height(), 900);
            assertEquals(ImageFormat.detect(result.fullBytes()).orElseThrow(), ImageFormat.JPEG);
            assertNotSame(result.fullBytes(), input);
            final BufferedImage small = decode(result.smallBytes());
            assertEquals(small.getWidth(), 800);
            assertEquals(small.getHeight(), 600);
        } else {
            final PhotoRejectedException ex = expectThrows(PhotoRejectedException.class,
                    () -> processor.process(input));
            assertTrue(ex.getMessage().contains("HEIC"), ex.getMessage());
        }
    }

    /** The under-cap HEIC branch: one transcode serves as both renditions. Decodes only where libheif is. */
    @Test
    public void aSmallHeicTranscodesOnceForBothRenditions() {
        if (!PhotoProcessor.isHeicSupported()) {
            assertThrows(PhotoRejectedException.class, () -> processor.process(PhotoFixtures.smallHeic()));
            return;
        }
        final ProcessedPhoto result = processor.process(PhotoFixtures.smallHeic());
        assertTrue(result.smallIsFull());
        assertEquals(result.fullContentType(), "image/jpeg");
        assertEquals(result.width(), 600);
        assertEquals(result.height(), 450);
    }

    @Test
    public void jpegEncodeFlattensTransparencyOntoWhite() throws IOException {
        final BufferedImage transparent = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        final byte[] jpeg = PhotoProcessor.encodeJpeg(transparent, 0.9f);
        final BufferedImage decoded = decode(jpeg);
        final Color corner = new Color(decoded.getRGB(0, 0));
        assertTrue(corner.getRed() > 240 && corner.getGreen() > 240 && corner.getBlue() > 240,
                "Fully transparent pixels should decode as (near-)white, got " + corner);
    }

    @Test
    public void resizeHalvesRepeatedlyDownToTheTarget() {
        final BufferedImage big = PhotoFixtures.gradient(4000, 3000, false);
        final BufferedImage small = PhotoProcessor.resizeToWidth(big, 800);
        assertEquals(small.getWidth(), 800);
        assertEquals(small.getHeight(), 600);
    }

    private void assertQuadrants(final int orientation, final Color topLeft, final Color topRight,
            final Color bottomLeft, final Color bottomRight) {
        final BufferedImage marker = quadrantMarker();
        final BufferedImage out = PhotoProcessor.applyOrientation(marker, orientation);
        final String label = "orientation " + orientation;
        assertEquals(new Color(out.getRGB(1, 1)), topLeft, label + " top-left");
        assertEquals(new Color(out.getRGB(out.getWidth() - 2, 1)), topRight, label + " top-right");
        assertEquals(new Color(out.getRGB(1, out.getHeight() - 2)), bottomLeft, label + " bottom-left");
        assertEquals(new Color(out.getRGB(out.getWidth() - 2, out.getHeight() - 2)), bottomRight,
                label + " bottom-right");
    }

    /** 4x4, quadrants: RED GREEN / BLUE WHITE. */
    private BufferedImage quadrantMarker() {
        final BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.RED);
            g.fillRect(0, 0, 2, 2);
            g.setColor(Color.GREEN);
            g.fillRect(2, 0, 2, 2);
            g.setColor(Color.BLUE);
            g.fillRect(0, 2, 2, 2);
            g.setColor(Color.WHITE);
            g.fillRect(2, 2, 2, 2);
        } finally {
            g.dispose();
        }
        return img;
    }

    private BufferedImage decode(final byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }
}
