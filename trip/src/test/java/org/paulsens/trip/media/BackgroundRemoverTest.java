package org.paulsens.trip.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Background removal. The inference tests run only when the model file is on the classpath
 * ({@code src/main/resources/models/u2netp.onnx}) — a build that does not carry it must still pass, with the
 * feature reporting itself unavailable (which is itself asserted here).
 */
public class BackgroundRemoverTest {

    @Test
    public void unavailableMeansEmptyNeverAnError() {
        BackgroundRemover.resetForTest();
        if (BackgroundRemover.isAvailable()) {
            throw new SkipException("Model present; the unavailable path is exercised where it is absent");
        }
        Assert.assertTrue(BackgroundRemover.cutout(PhotoFixtures.jpeg(512, 512)).isEmpty(),
                "No model must mean a graceful empty, not an exception");
    }

    @Test
    public void cutoutIsAnAlphaVaryingPngOfProfileSize() throws IOException {
        BackgroundRemover.resetForTest();
        if (!BackgroundRemover.isAvailable()) {
            throw new SkipException("u2netp.onnx not on the classpath; inference untestable here");
        }
        final Optional<byte[]> cutout = BackgroundRemover.cutout(PhotoFixtures.jpeg(512, 512));
        Assert.assertTrue(cutout.isPresent());
        Assert.assertEquals(ImageFormat.detect(cutout.get()).orElseThrow(), ImageFormat.PNG);

        final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(cutout.get()));
        Assert.assertEquals(decoded.getWidth(), PhotoProcessor.PROFILE_SIZE);
        Assert.assertEquals(decoded.getHeight(), PhotoProcessor.PROFILE_SIZE);
        Assert.assertTrue(decoded.getColorModel().hasAlpha(), "The mask IS the alpha channel");

        int min = 255;
        int max = 0;
        for (int y = 0; y < decoded.getHeight(); y += 16) {
            for (int x = 0; x < decoded.getWidth(); x += 16) {
                final int alpha = (decoded.getRGB(x, y) >>> 24) & 0xFF;
                min = Math.min(min, alpha);
                max = Math.max(max, alpha);
            }
        }
        Assert.assertTrue(max > min, "A saliency mask over a gradient must vary, not be a constant");
    }

    /** A corrupt model fails ONCE, loudly, and the feature then reports unavailable — never per-click. */
    @Test
    public void aBrokenModelFailsFastAndReportsUnavailable() {
        BackgroundRemover.resetForTest();
        try {
            BackgroundRemover.modelResourceForTest("/bad-model.onnx");
            Assert.assertTrue(BackgroundRemover.isAvailable(), "The garbage resource exists on the classpath");
            Assert.assertTrue(BackgroundRemover.cutout(PhotoFixtures.jpeg(64, 64)).isEmpty(),
                    "A model that cannot load answers empty");
            Assert.assertFalse(BackgroundRemover.isAvailable(),
                    "The failure is remembered so later clicks fail fast");
            Assert.assertTrue(BackgroundRemover.cutout(PhotoFixtures.jpeg(64, 64)).isEmpty());
        } finally {
            BackgroundRemover.resetForTest();
        }
    }

    /** The single-permit gate: a held permit means "busy", and interruption backs off immediately. */
    @Test
    public void theGateReportsBusyAndHonorsInterruption() throws InterruptedException {
        BackgroundRemover.resetForTest();
        if (!BackgroundRemover.isAvailable()) {
            throw new SkipException("u2netp.onnx not on the classpath");
        }
        Thread.currentThread().interrupt();
        Assert.assertTrue(BackgroundRemover.cutout(PhotoFixtures.jpeg(64, 64)).isEmpty(),
                "An interrupted caller backs off instead of waiting");
        Assert.assertTrue(Thread.interrupted(), "The interrupt flag is restored, not swallowed");

        BackgroundRemover.GATE.acquire();
        try {
            Assert.assertTrue(BackgroundRemover.cutout(PhotoFixtures.jpeg(64, 64)).isEmpty(),
                    "A held permit means busy (after the bounded wait), never a queue");
        } finally {
            BackgroundRemover.GATE.release();
        }
    }

    /** The idle reaper gives the session's memory back — but never mid-inference, and never early. */
    @Test
    public void idleCloseReclaimsTheSessionButNeverEarlyOrBusy() {
        BackgroundRemover.resetForTest();
        if (!BackgroundRemover.isAvailable()) {
            throw new SkipException("u2netp.onnx not on the classpath");
        }
        try {
            Assert.assertTrue(BackgroundRemover.cutout(PhotoFixtures.jpeg(64, 64)).isPresent());
            Assert.assertTrue(BackgroundRemover.isSessionLoaded());

            BackgroundRemover.closeIfIdle();
            Assert.assertTrue(BackgroundRemover.isSessionLoaded(),
                    "Freshly used means not idle; the session must survive");

            BackgroundRemover.idleCloseMillisForTest(0);
            BackgroundRemover.GATE.acquireUninterruptibly();
            try {
                BackgroundRemover.closeIfIdle();
            } finally {
                BackgroundRemover.GATE.release();
            }
            Assert.assertTrue(BackgroundRemover.isSessionLoaded(),
                    "A held permit means in use; the session must never close mid-run");

            BackgroundRemover.closeIfIdle();
            Assert.assertFalse(BackgroundRemover.isSessionLoaded(),
                    "Past the idle window the session closes and its memory returns");

            BackgroundRemover.idleCloseMillisForTest(BackgroundRemover.IDLE_CLOSE_MILLIS);
            Assert.assertTrue(BackgroundRemover.cutout(PhotoFixtures.jpeg(64, 64)).isPresent(),
                    "The next use reloads the model transparently");
            Assert.assertTrue(BackgroundRemover.isSessionLoaded());
        } finally {
            BackgroundRemover.resetForTest();
        }
    }

    /** The mask math is model-independent and testable without inference. */
    @Test
    public void maskNormalizationAndApplicationAreExact() {
        final float[][] raw = {{-2f, 0f}, {2f, 6f}};
        final float[][] normalized = BackgroundRemover.normalized(raw);
        Assert.assertEquals(normalized[0][0], 0f);
        Assert.assertEquals(normalized[1][1], 1f);
        Assert.assertEquals(normalized[0][1], 0.25f);

        final float[][] flat = BackgroundRemover.normalized(new float[][] {{3f, 3f}, {3f, 3f}});
        Assert.assertTrue(flat[0][0] >= 0f && flat[0][0] <= 1f, "A constant map must not divide by zero");

        final BufferedImage source = PhotoFixtures.gradient(4, 4, false);
        final float[][] mask = {{0f, 0f, 1f, 1f}, {0f, 0f, 1f, 1f}, {0f, 0f, 1f, 1f}, {0f, 0f, 1f, 1f}};
        final BufferedImage masked = BackgroundRemover.applyMask(source, mask);
        Assert.assertEquals((masked.getRGB(0, 0) >>> 24) & 0xFF, 0, "Masked-out pixel is transparent");
        Assert.assertEquals((masked.getRGB(3, 3) >>> 24) & 0xFF, 255, "Kept pixel is opaque");
        Assert.assertEquals(masked.getRGB(3, 3) & 0xFFFFFF, source.getRGB(3, 3) & 0xFFFFFF,
                "The mask changes alpha only, never the color");
    }

    @Test
    public void garbageInputIsRejectedNotMasked() {
        BackgroundRemover.resetForTest();
        if (!BackgroundRemover.isAvailable()) {
            throw new SkipException("u2netp.onnx not on the classpath");
        }
        Assert.assertThrows(PhotoRejectedException.class,
                () -> BackgroundRemover.cutout("not an image".getBytes()));
    }
}
