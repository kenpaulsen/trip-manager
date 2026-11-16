package org.paulsens.trip.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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
