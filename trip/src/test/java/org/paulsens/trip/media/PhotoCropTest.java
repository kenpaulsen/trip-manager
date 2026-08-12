package org.paulsens.trip.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The crop layer on top of {@link PhotoProcessor}: preview geometry (the two coordinate spaces), the
 * exactly-512-square profile rendition, rect clamping/squaring, and cropped chat renditions.
 */
public class PhotoCropTest {

    private final PhotoProcessor processor = new PhotoProcessor();

    @Test
    public void previewReportsBothCoordinateSpaces() throws IOException {
        final PhotoProcessor.PreviewImage preview = processor.preview(PhotoFixtures.jpeg(1600, 1200));
        Assert.assertEquals(preview.fullWidth(), 1600);
        Assert.assertEquals(preview.fullHeight(), 1200);
        Assert.assertEquals(preview.previewWidth(), PhotoProcessor.MAX_SMALL_WIDTH);
        Assert.assertEquals(preview.previewHeight(), 600);
        final BufferedImage decoded = decode(preview.previewJpeg());
        Assert.assertEquals(decoded.getWidth(), PhotoProcessor.MAX_SMALL_WIDTH);
        Assert.assertEquals(decoded.getHeight(), 600);
    }

    @Test
    public void smallImagePreviewIsItsOwnSpace() throws IOException {
        final PhotoProcessor.PreviewImage preview = processor.preview(PhotoFixtures.jpeg(400, 300));
        Assert.assertEquals(preview.previewWidth(), 400);
        Assert.assertEquals(preview.fullWidth(), 400);
        Assert.assertEquals(decode(preview.previewJpeg()).getWidth(), 400);
    }

    /** EXIF orientation 6 transposes: the preview must report the ORIENTED space, not the pixel data's. */
    @Test
    public void previewReportsOrientedDimensions() {
        final PhotoProcessor.PreviewImage preview =
                processor.preview(PhotoFixtures.jpegWithOrientation(800, 400, 6));
        Assert.assertEquals(preview.fullWidth(), 400);
        Assert.assertEquals(preview.fullHeight(), 800);
    }

    @Test
    public void profileRenditionIsExactly512Square() throws IOException {
        final byte[] jpeg = processor.processProfile(PhotoFixtures.jpeg(1600, 1200),
                new PhotoProcessor.CropRect(100, 100, 600, 600));
        final BufferedImage decoded = decode(jpeg);
        Assert.assertEquals(decoded.getWidth(), PhotoProcessor.PROFILE_SIZE);
        Assert.assertEquals(decoded.getHeight(), PhotoProcessor.PROFILE_SIZE);
    }

    /** A small source scales UP to 512 — the spec pins the stored size, not a maximum. */
    @Test
    public void profileRenditionUpscalesSmallSources() throws IOException {
        final byte[] jpeg = processor.processProfile(PhotoFixtures.jpeg(300, 200), null);
        Assert.assertEquals(decode(jpeg).getWidth(), PhotoProcessor.PROFILE_SIZE);
        Assert.assertEquals(decode(jpeg).getHeight(), PhotoProcessor.PROFILE_SIZE);
    }

    @Test
    public void profileCropsWorkInTheOrientedSpace() throws IOException {
        // Oriented space is 400x800; a rect valid only there must be accepted as-is.
        final byte[] jpeg = processor.processProfile(PhotoFixtures.jpegWithOrientation(800, 400, 6),
                new PhotoProcessor.CropRect(0, 500, 300, 300));
        Assert.assertEquals(decode(jpeg).getWidth(), PhotoProcessor.PROFILE_SIZE);
    }

    @Test
    public void profileAcceptsPngAndFlattensToJpeg() throws IOException {
        final byte[] jpeg = processor.processProfile(PhotoFixtures.png(600, 600, true),
                new PhotoProcessor.CropRect(0, 0, 600, 600));
        Assert.assertEquals(ImageFormat.detect(jpeg).orElseThrow(), ImageFormat.JPEG);
        Assert.assertEquals(decode(jpeg).getWidth(), PhotoProcessor.PROFILE_SIZE);
    }

    @Test
    public void garbageIsRejectedForProfilesToo() {
        Assert.assertThrows(PhotoRejectedException.class,
                () -> processor.processProfile("not an image".getBytes(), null));
    }

    @Test
    public void clampPullsWildRectsInsideTheImage() {
        final PhotoProcessor.CropRect wild =
                PhotoProcessor.clamp(new PhotoProcessor.CropRect(-10, -10, 10_000, 10_000), 800, 600);
        Assert.assertEquals(wild, new PhotoProcessor.CropRect(0, 0, 800, 600));

        final PhotoProcessor.CropRect corner =
                PhotoProcessor.clamp(new PhotoProcessor.CropRect(790, 590, 100, 100), 800, 600);
        Assert.assertEquals(corner, new PhotoProcessor.CropRect(790, 590, 10, 10));

        final PhotoProcessor.CropRect degenerate =
                PhotoProcessor.clamp(new PhotoProcessor.CropRect(10, 10, 0, -5), 800, 600);
        Assert.assertEquals(degenerate, new PhotoProcessor.CropRect(10, 10, 1, 1));
    }

    @Test
    public void squareRectNeverStretches() {
        final PhotoProcessor.CropRect square =
                PhotoProcessor.squareRect(new PhotoProcessor.CropRect(0, 0, 600, 400), 800, 600);
        Assert.assertEquals(square.width(), 400);
        Assert.assertEquals(square.height(), 400);

        final PhotoProcessor.CropRect centered = PhotoProcessor.squareRect(null, 800, 600);
        Assert.assertEquals(centered, new PhotoProcessor.CropRect(100, 0, 600, 600));
    }

    @Test
    public void chatCropReencodesBothRenditions() throws IOException {
        final ProcessedPhoto photo = processor.process(PhotoFixtures.jpeg(2400, 1200),
                new PhotoProcessor.CropRect(0, 0, 1200, 1200));
        Assert.assertEquals(photo.width(), 1200);
        Assert.assertEquals(photo.height(), 1200);
        Assert.assertEquals(decode(photo.fullBytes()).getWidth(), 1200);
        Assert.assertEquals(decode(photo.smallBytes()).getWidth(), PhotoProcessor.MAX_SMALL_WIDTH);
        Assert.assertEquals(photo.fullContentType(), ImageFormat.JPEG.getContentType());
    }

    @Test
    public void chatCropOfPngStaysPng() {
        final ProcessedPhoto photo = processor.process(PhotoFixtures.png(900, 900, true),
                new PhotoProcessor.CropRect(0, 0, 500, 500));
        Assert.assertEquals(photo.fullContentType(), ImageFormat.PNG.getContentType());
        Assert.assertEquals(photo.width(), 500);
        Assert.assertTrue(photo.smallIsFull(), "500px wide needs no second rendition");
    }

    /** Cropping an animation would silently discard it; the rect is ignored by design. */
    @Test
    public void animatedGifIgnoresTheRect() {
        final byte[] gif = PhotoFixtures.animatedGif(900, 400);
        final ProcessedPhoto photo = processor.process(gif, new PhotoProcessor.CropRect(0, 0, 100, 100));
        Assert.assertEquals(photo.width(), 900);
        Assert.assertEquals(photo.fullBytes(), gif, "The original animation must survive untouched");
    }

    @Test
    public void nullRectIsTheUncroppedPipeline() {
        final byte[] jpeg = PhotoFixtures.jpeg(500, 400);
        final ProcessedPhoto photo = processor.process(jpeg, null);
        Assert.assertEquals(photo.fullBytes(), jpeg, "Null rect must keep the original bytes");
    }

    @Test
    public void chatCropAtOrUnderTheCapNeedsOneRendition() {
        final ProcessedPhoto photo = processor.process(PhotoFixtures.jpeg(1000, 1000),
                new PhotoProcessor.CropRect(0, 0, 600, 600));
        Assert.assertTrue(photo.smallIsFull());
        Assert.assertEquals(photo.width(), 600);
    }

    @Test
    public void chatCropOfALargePngResizesItsSmallRendition() throws IOException {
        final ProcessedPhoto photo = processor.process(PhotoFixtures.png(2000, 1600, false),
                new PhotoProcessor.CropRect(0, 0, 1800, 1200));
        Assert.assertEquals(photo.fullContentType(), ImageFormat.PNG.getContentType());
        Assert.assertEquals(decode(photo.smallBytes()).getWidth(), PhotoProcessor.MAX_SMALL_WIDTH);
    }

    @Test
    public void profileFromAStaticGifWorks() throws IOException {
        final byte[] jpeg = processor.processProfile(PhotoFixtures.gif(700, 500), null);
        Assert.assertEquals(decode(jpeg).getWidth(), PhotoProcessor.PROFILE_SIZE);
    }

    /** A crop side more than twice the target walks the halving loop before the final bilinear step. */
    @Test
    public void bigCropsDownscaleThroughHalving() throws IOException {
        final byte[] jpeg = processor.processProfile(PhotoFixtures.jpeg(3000, 2800),
                new PhotoProcessor.CropRect(0, 0, 2500, 2500));
        Assert.assertEquals(decode(jpeg).getWidth(), PhotoProcessor.PROFILE_SIZE);
        Assert.assertEquals(decode(jpeg).getHeight(), PhotoProcessor.PROFILE_SIZE);
    }

    private static BufferedImage decode(final byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }
}
