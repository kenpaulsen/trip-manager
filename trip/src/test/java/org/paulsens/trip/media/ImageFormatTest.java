package org.paulsens.trip.media;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class ImageFormatTest {

    @Test
    public void detectsJpeg() {
        assertEquals(ImageFormat.detect(PhotoFixtures.jpeg(20, 10)), Optional.of(ImageFormat.JPEG));
    }

    @Test
    public void detectsPng() {
        assertEquals(ImageFormat.detect(PhotoFixtures.png(20, 10, false)), Optional.of(ImageFormat.PNG));
    }

    @Test
    public void detectsGif() {
        assertEquals(ImageFormat.detect(PhotoFixtures.gif(20, 10)), Optional.of(ImageFormat.GIF));
    }

    @Test
    public void detectsHeicFixture() {
        assertEquals(ImageFormat.detect(PhotoFixtures.heic()), Optional.of(ImageFormat.HEIC));
    }

    @Test
    public void detectsHeicBrandVariants() {
        for (final String brand : new String[] {"heic", "heix", "mif1", "msf1"}) {
            assertEquals(ImageFormat.detect(ftyp(brand)), Optional.of(ImageFormat.HEIC), brand);
        }
    }

    @Test
    public void rejectsVideoAndAvifBrands() {
        for (final String brand : new String[] {"avif", "hevc", "mp42", "isom"}) {
            assertTrue(ImageFormat.detect(ftyp(brand)).isEmpty(), brand);
        }
    }

    @Test
    public void rejectsNullShortAndGarbage() {
        assertTrue(ImageFormat.detect(null).isEmpty());
        assertTrue(ImageFormat.detect(new byte[4]).isEmpty());
        assertTrue(ImageFormat.detect("This is definitely not an image".getBytes(StandardCharsets.US_ASCII))
                .isEmpty());
    }

    @Test
    public void extensionsAndContentTypesAreConsistent() {
        assertEquals(ImageFormat.JPEG.getContentType(), "image/jpeg");
        assertEquals(ImageFormat.JPEG.getExtension(), "jpg");
        assertEquals(ImageFormat.PNG.getContentType(), "image/png");
        assertEquals(ImageFormat.PNG.getExtension(), "png");
        assertEquals(ImageFormat.GIF.getContentType(), "image/gif");
        assertEquals(ImageFormat.GIF.getExtension(), "gif");
        assertEquals(ImageFormat.HEIC.getContentType(), "image/heic");
        assertEquals(ImageFormat.HEIC.getExtension(), "heic");
    }

    private static byte[] ftyp(final String brand) {
        final byte[] bytes = new byte[16];
        bytes[3] = 16;
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, bytes, 4, 4);
        System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        return bytes;
    }
}
