package org.paulsens.trip.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/** {@link PhotoProcessor#rotate} and {@link PhotoProcessor#rotateJpegWithin}: the turn every image kind uses. */
public class PhotoProcessorRotateTest {

    private final PhotoProcessor processor = new PhotoProcessor();

    @Test
    public void directionsParseInAnyCaseAndNothingElse() {
        assertEquals(QuarterTurn.parse("left"), Optional.of(QuarterTurn.LEFT));
        assertEquals(QuarterTurn.parse(" RIGHT "), Optional.of(QuarterTurn.RIGHT));
        assertEquals(QuarterTurn.parse("up"), Optional.empty());
        assertEquals(QuarterTurn.parse(null), Optional.empty());
        assertEquals(QuarterTurn.LEFT.exifOrientation(), 8);
        assertEquals(QuarterTurn.RIGHT.exifOrientation(), 6);
    }

    @Test
    public void aJpegTurnsBothWaysAndSwapsItsDimensions() {
        final byte[] input = PhotoFixtures.jpeg(300, 200);
        for (final QuarterTurn turn : QuarterTurn.values()) {
            final PhotoProcessor.RotatedImage out = processor.rotate(input, turn);
            assertEquals(out.width(), 200, turn.name());
            assertEquals(out.height(), 300, turn.name());
            assertEquals(out.contentType(), "image/jpeg");
            assertEquals(out.extension(), "jpg");
            final BufferedImage decoded = decode(out.bytes());
            assertEquals(decoded.getWidth(), 200);
            assertEquals(decoded.getHeight(), 300);
        }
    }

    /** R G / B W turned right is B R / W G; turned left it is G W / R B (the EXIF 6 and 8 rows). */
    @Test
    public void everyQuadrantLandsWhereTheTurnPutsIt() {
        final byte[] marker = PhotoProcessor.encodePng(quadrantMarker());
        assertQuadrants(decode(processor.rotate(marker, QuarterTurn.RIGHT).bytes()),
                Color.BLUE, Color.RED, Color.WHITE, Color.GREEN);
        assertQuadrants(decode(processor.rotate(marker, QuarterTurn.LEFT).bytes()),
                Color.GREEN, Color.WHITE, Color.RED, Color.BLUE);
    }

    @Test
    public void aPngStaysAPngWithItsAlpha() {
        final PhotoProcessor.RotatedImage out = processor.rotate(PhotoFixtures.png(120, 40, true), QuarterTurn.LEFT);
        assertEquals(out.contentType(), "image/png");
        assertEquals(out.extension(), "png");
        assertEquals(ImageFormat.detect(out.bytes()).orElseThrow(), ImageFormat.PNG);
        final BufferedImage decoded = decode(out.bytes());
        assertTrue(decoded.getColorModel().hasAlpha());
        assertEquals(decoded.getWidth(), 40);
        assertEquals(decoded.getHeight(), 120);
    }

    @Test
    public void aStaticGifStaysAGif() {
        final PhotoProcessor.RotatedImage out = processor.rotate(PhotoFixtures.gif(90, 30), QuarterTurn.RIGHT);
        assertEquals(out.contentType(), "image/gif");
        assertEquals(ImageFormat.detect(out.bytes()).orElseThrow(), ImageFormat.GIF);
        assertEquals(decode(out.bytes()).getWidth(), 30);
    }

    @Test
    public void anAnimatedGifIsRefusedRatherThanFlattened() {
        final PhotoRejectedException ex = expectThrows(PhotoRejectedException.class,
                () -> processor.rotate(PhotoFixtures.animatedGif(60, 30), QuarterTurn.LEFT));
        assertEquals(ex.getMessage(), "Animated GIFs can't be rotated.");
    }

    /** Stored as 200x100 with EXIF 6 it DISPLAYS 100x200; a left turn from there is upright 200x100. */
    @Test
    public void theTurnIsRelativeToWhatPeopleSeeNotTheRawPixels() {
        final PhotoProcessor.RotatedImage out =
                processor.rotate(PhotoFixtures.jpegWithOrientation(200, 100, 6), QuarterTurn.LEFT);
        assertEquals(out.width(), 200);
        assertEquals(out.height(), 100);
        assertEquals(PhotoProcessor.readExifOrientation(out.bytes()), 1, "the re-encode carries no stale tag");
    }

    @Test
    public void aCappedTurnHonorsItsByteBudget() {
        final byte[] input = PhotoFixtures.jpeg(1600, 900);
        final byte[] out = processor.rotateJpegWithin(input, QuarterTurn.RIGHT, 40_000L);
        assertTrue(out.length <= 40_000L, "got " + out.length);
        final BufferedImage decoded = decode(out);
        assertTrue(decoded.getHeight() > decoded.getWidth(), "turned to portrait");
        assertEquals(ImageFormat.detect(out).orElseThrow(), ImageFormat.JPEG);
    }

    @Test
    public void garbageIsRejectedByBothEntryPoints() {
        final byte[] junk = "Not an image at all, sorry".getBytes(StandardCharsets.US_ASCII);
        expectThrows(PhotoRejectedException.class, () -> processor.rotate(junk, QuarterTurn.LEFT));
        expectThrows(PhotoRejectedException.class, () -> processor.rotateJpegWithin(junk, QuarterTurn.LEFT, 1000L));
    }

    @Test
    public void aGifOfAnAlphaImageEncodes() {
        final BufferedImage argb = PhotoFixtures.gradient(20, 10, true);
        final byte[] gif = PhotoProcessor.encodeGif(argb);
        assertEquals(ImageFormat.detect(gif).orElseThrow(), ImageFormat.GIF);
    }

    private static void assertQuadrants(final BufferedImage out, final Color topLeft, final Color topRight,
            final Color bottomLeft, final Color bottomRight) {
        assertEquals(new Color(out.getRGB(1, 1)), topLeft, "top-left");
        assertEquals(new Color(out.getRGB(out.getWidth() - 2, 1)), topRight, "top-right");
        assertEquals(new Color(out.getRGB(1, out.getHeight() - 2)), bottomLeft, "bottom-left");
        assertEquals(new Color(out.getRGB(out.getWidth() - 2, out.getHeight() - 2)), bottomRight, "bottom-right");
    }

    /** 4x4, quadrants: RED GREEN / BLUE WHITE. PNG so the colors survive exactly. */
    private static BufferedImage quadrantMarker() {
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

    private static BufferedImage decode(final byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
