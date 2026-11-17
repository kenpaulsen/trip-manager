package org.paulsens.trip.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@link PhotoProcessor#processPicture}: trip pictures cut to a shape at several widths, plus their average color. */
public class PhotoProcessorPictureTest {

    private final PhotoProcessor processor = new PhotoProcessor();

    @Test
    public void everyWidthIsWrittenAtTheShapeAndNothingIsEnlarged() throws Exception {
        final PhotoProcessor.PictureRenditions big =
                processor.processPicture(PhotoFixtures.jpeg(4000, 3000), null, 1.5d, List.of(480, 960, 1920));
        Assert.assertEquals(big.jpegs().size(), 3);
        Assert.assertEquals(ImageIO.read(new ByteArrayInputStream(big.jpegs().get(0))).getWidth(), 480);
        Assert.assertEquals(ImageIO.read(new ByteArrayInputStream(big.jpegs().get(1))).getHeight(), 640);
        Assert.assertEquals(big.width(), 1920);
        Assert.assertEquals(big.height(), 1280);

        final PhotoProcessor.PictureRenditions small =
                processor.processPicture(PhotoFixtures.png(600, 600, true), null, 1.5d, List.of(480, 960));
        Assert.assertEquals(small.width(), 600, "The 960 rendition stays at the cut's own width");
        Assert.assertEquals(small.height(), 400);
    }

    @Test
    public void garbageIsRejected() {
        Assert.expectThrows(PhotoRejectedException.class,
                () -> processor.processPicture(new byte[] {1, 2, 3}, null, 1.5d, List.of(480)));
    }

    @Test
    public void aPositionSlidesTheLargestRegionAndUntrustedFractionsAreTamed() {
        // A 1000x2000 portrait holds a 1000x667 region of 3:2 with 1333px of vertical travel and none sideways.
        Assert.assertEquals(PhotoProcessor.positionedRect(1000, 2000, 1.5d, 0.5d, 0d),
                new PhotoProcessor.CropRect(0, 0, 1000, 667), "Top");
        Assert.assertEquals(PhotoProcessor.positionedRect(1000, 2000, 1.5d, 0.5d, 0.5d),
                new PhotoProcessor.CropRect(0, 667, 1000, 667), "Centered, where a null rectangle cuts too");
        Assert.assertEquals(PhotoProcessor.positionedRect(1000, 2000, 1.5d, 0.5d, 1d),
                new PhotoProcessor.CropRect(0, 1333, 1000, 667), "Bottom");
        // A 3000x1000 panorama slides sideways instead.
        Assert.assertEquals(PhotoProcessor.positionedRect(3000, 1000, 1.5d, 1d, 0.5d),
                new PhotoProcessor.CropRect(1500, 0, 1500, 1000), "Right");
        Assert.assertEquals(PhotoProcessor.positionedRect(3000, 1000, 1.5d, -4d, 9d),
                new PhotoProcessor.CropRect(0, 0, 1500, 1000), "Out of range is pulled to the nearer end");
        Assert.assertEquals(PhotoProcessor.positionedRect(3000, 1000, 1.5d, Double.NaN, Double.NaN),
                new PhotoProcessor.CropRect(750, 0, 1500, 1000), "Not a number centers");
    }

    @Test
    public void aPictureCutAtAPositionHasTheShapeAndComesFromThatEnd() throws Exception {
        // Top half red, bottom half blue: the cut's average color says which end it came from.
        final BufferedImage halves = new BufferedImage(600, 1200, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = halves.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 600, 600);
        g.setColor(Color.BLUE);
        g.fillRect(0, 600, 600, 600);
        g.dispose();
        final ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(halves, "png", png);

        final PhotoProcessor.PictureRenditions top =
                processor.processPictureAt(png.toByteArray(), 0.5d, 0d, 1.5d, List.of(480, 960));
        Assert.assertEquals(top.width(), 600, "Never enlarged");
        Assert.assertEquals(top.height(), 400);
        Assert.assertEquals(ImageIO.read(new ByteArrayInputStream(top.jpegs().get(0))).getWidth(), 480);
        Assert.assertEquals(top.averageRgb(), 0xFF0000, "The top of the photo");
        Assert.assertEquals(processor.processPictureAt(png.toByteArray(), 0.5d, 1d, 1.5d, List.of(480)).averageRgb(),
                0x0000FF, "The bottom of the photo");
        Assert.expectThrows(PhotoRejectedException.class,
                () -> processor.processPictureAt(new byte[] {1, 2, 3}, 0.5d, 0.5d, 1.5d, List.of(480)));
    }

    @Test
    public void theAverageColorCountsTransparencyAsWhite() {
        final BufferedImage red = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = red.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 64, 32);
        g.dispose();
        Assert.assertEquals(PhotoProcessor.averageRgb(red), 0xFF0000);

        final BufferedImage clear = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Assert.assertEquals(PhotoProcessor.averageRgb(clear), 0xFFFFFF, "Fully transparent reads as white");
    }
}
