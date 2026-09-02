package org.paulsens.trip.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import javax.imageio.ImageIO;
import org.paulsens.trip.media.PhotoProcessor.CropRect;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The processing the four org-site branding images need and the chat/profile pipelines could not give them:
 * a crop that KEEPS transparency, a crop forced to an arbitrary aspect ratio, and an encode held under a
 * byte budget.
 */
public class BrandingPhotoProcessingTest {

    private static final long ONE_MB = 1024L * 1024L;
    private final PhotoProcessor processor = new PhotoProcessor();

    // --- transparency: the whole reason a logo cannot use the square/JPEG pipeline ---

    @Test
    public void aLogoCropKeepsItsTransparencyWhereTheJpegPipelineWouldPaintItWhite() throws IOException {
        final byte[] source = transparentLeftHalf(400, 200);
        final BufferedImage logo = decode(processor.cropToPng(source, null, 512, 512));

        Assert.assertTrue(logo.getColorModel().hasAlpha(), "PNG output must carry an alpha channel");
        Assert.assertEquals(new Color(logo.getRGB(10, 100), true).getAlpha(), 0,
                "the transparent half must still be transparent, not a white box behind the logo");
        Assert.assertEquals(new Color(logo.getRGB(390, 100), true).getAlpha(), 255, "the drawn half stays");

        // The same bytes through the square/JPEG path: opaque, which is exactly the bug this method avoids.
        final BufferedImage flattened = decode(processor.processSquare(source, null, 64));
        Assert.assertFalse(flattened.getColorModel().hasAlpha(), "JPEG has no alpha to keep");
        final Color flat = new Color(flattened.getRGB(4, 32));
        Assert.assertTrue(flat.getRed() > 230 && flat.getGreen() > 230 && flat.getBlue() > 230,
                "the encoder flattens what was transparent onto white: " + flat);
    }

    @Test
    public void aLogoIsBoundedByTheRecommendationButIsNeverEnlarged() throws IOException {
        final BufferedImage big = decode(processor.cropToPng(PhotoFixtures.png(2000, 1000, false),
                null, 512, 512));
        Assert.assertEquals(big.getWidth(), 512, "the long side lands on the cap");
        Assert.assertEquals(big.getHeight(), 256, "and the shape is kept, not squared");

        final BufferedImage small = decode(processor.cropToPng(PhotoFixtures.png(120, 60, false),
                null, 512, 512));
        Assert.assertEquals(small.getWidth(), 120, "a source under the cap is stored as it is");
        Assert.assertEquals(small.getHeight(), 60, "inventing pixels would only make it blurrier");
    }

    @Test
    public void aFaviconIsAnExactSquarePngWhateverTheSourceShape() throws IOException {
        final BufferedImage icon = decode(processor.cropToSquarePng(transparentLeftHalf(900, 300), null, 48));
        Assert.assertEquals(icon.getWidth(), 48);
        Assert.assertEquals(icon.getHeight(), 48);
        Assert.assertTrue(icon.getColorModel().hasAlpha(), "a tab icon may legitimately be transparent");
    }

    // --- the aspect ratio, which used to be a choice between square and free ---

    @Test
    public void anAspectCropShrinksTheSelectionToTheShapeAndNeverStretchesIt() {
        final double og = 1200d / 630d;
        final CropRect centered = PhotoProcessor.cutRegion(null, 2000, 2000, og);
        Assert.assertEquals(centered.width(), 2000, "the widest 1.91:1 region of a square is its full width");
        Assert.assertEquals(centered.height(), 1050);
        Assert.assertEquals(centered.x(), 0);
        Assert.assertEquals(centered.y(), (2000 - 1050) / 2, "a rectless request is centered");

        final CropRect chosen = PhotoProcessor.cutRegion(new CropRect(100, 50, 800, 800), 2000, 2000, og);
        Assert.assertEquals(chosen.width(), 800, "the selection is only ever shrunk to fit the shape");
        Assert.assertEquals(chosen.height(), 420);
        Assert.assertEquals(chosen.x(), 100, "an explicit selection keeps the corner the user put it at");
        Assert.assertEquals(chosen.y(), 50);

        final CropRect free = PhotoProcessor.cutRegion(new CropRect(10, 10, 5000, 5000), 400, 300, 0d);
        Assert.assertEquals(free, new CropRect(10, 10, 390, 290), "no ratio means clamped and nothing else");
        Assert.assertEquals(PhotoProcessor.cutRegion(null, 400, 300, 0d), new CropRect(0, 0, 400, 300),
                "and no rect either means the whole image");
    }

    @Test
    public void theSquareCropIsTheAspectCropAtOneToOne() {
        Assert.assertEquals(PhotoProcessor.squareRect(null, 900, 300), new CropRect(300, 0, 300, 300));
        Assert.assertEquals(PhotoProcessor.squareRect(new CropRect(10, 20, 400, 100), 900, 300),
                new CropRect(10, 20, 100, 100), "still min(w, h) of the clamped rect, anchored where it was");
    }

    @Test
    public void aLinkPreviewComesOutAtOpenGraphsShapeUnderItsByteBudget() throws IOException {
        final byte[] preview = processor.cropToBoundedJpeg(PhotoFixtures.jpeg(3000, 2000), null,
                1200d / 630d, 1200, ONE_MB);
        Assert.assertTrue(preview.length <= ONE_MB, "encoded size: " + preview.length);
        final BufferedImage image = decode(preview);
        Assert.assertEquals(image.getWidth(), 1200);
        Assert.assertEquals(image.getHeight(), 630, "1200x630 is the shape link unfurlers expect");
        Assert.assertFalse(image.getColorModel().hasAlpha(), "JPEG, so no alpha to carry");
    }

    @Test
    public void aBackgroundIsCappedInWidthAndInBytesAndIsNotEnlarged() throws IOException {
        final byte[] wide = processor.cropToBoundedJpeg(PhotoFixtures.jpeg(4000, 2250), null, 0d, 1920,
                ONE_MB);
        Assert.assertTrue(wide.length <= ONE_MB, "encoded size: " + wide.length);
        Assert.assertEquals(decode(wide).getWidth(), 1920);

        final byte[] small = processor.cropToBoundedJpeg(PhotoFixtures.jpeg(800, 600), null, 0d, 1920,
                ONE_MB);
        Assert.assertEquals(decode(small).getWidth(), 800, "a narrow source is never stretched to the cap");
    }

    // --- the byte cap itself ---

    @Test
    public void theByteCapDropsQualityFirstAndOnlyThenTheSize() {
        final byte[] generous = PhotoProcessor.encodeJpegWithin(PhotoFixtures.gradient(1600, 1200, false),
                ONE_MB);
        Assert.assertTrue(generous.length <= ONE_MB);
        Assert.assertEquals(read(generous).getWidth(), 1600,
                "a budget the quality ladder alone can meet must not shrink the picture");

        final byte[] tight = PhotoProcessor.encodeJpegWithin(noise(1600, 1200), 60_000L);
        Assert.assertTrue(tight.length <= 60_000L, "encoded size: " + tight.length);
        Assert.assertTrue(read(tight).getWidth() < 1600, "a budget it cannot meet costs pixels, not the cap");
    }

    @Test
    public void anImpossibleBudgetIsRefusedWithAMessageThatNamesIt() {
        final PhotoRejectedException refused = Assert.expectThrows(PhotoRejectedException.class,
                () -> PhotoProcessor.encodeJpegWithin(noise(2000, 1500), 700L));
        Assert.assertTrue(refused.getMessage().contains(String.valueOf(PhotoProcessor.MIN_CAP_WIDTH)),
                "it says how far it got: " + refused.getMessage());
        Assert.assertTrue(refused.getMessage().contains("KB"), refused.getMessage());
    }

    @Test
    public void unsupportedBytesAreRejectedOnEveryNewPathToo() {
        final byte[] junk = "not an image at all".getBytes(StandardCharsets.UTF_8);
        Assert.expectThrows(PhotoRejectedException.class, () -> processor.cropToPng(junk, null, 64, 64));
        Assert.expectThrows(PhotoRejectedException.class, () -> processor.cropToSquarePng(junk, null, 48));
        Assert.expectThrows(PhotoRejectedException.class,
                () -> processor.cropToBoundedJpeg(junk, null, 0d, 800, ONE_MB));
    }

    // --- fixtures ---

    /** A PNG whose left half is fully transparent and whose right half is solid, so alpha is checkable. */
    private static byte[] transparentLeftHalf(final int width, final int height) {
        final BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.RED);
            g.fillRect(width / 2, 0, width - width / 2, height);
        } finally {
            g.dispose();
        }
        return write(img, "png");
    }

    /**
     * Pixel noise, which no JPEG encoder can compress away — the only honest way to make the byte cap do
     * something. A gradient at 1600x1200 already fits a megabyte at full quality.
     */
    private static BufferedImage noise(final int width, final int height) {
        final BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        return img;
    }

    private static BufferedImage decode(final byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static BufferedImage read(final byte[] bytes) {
        try {
            return decode(bytes);
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
