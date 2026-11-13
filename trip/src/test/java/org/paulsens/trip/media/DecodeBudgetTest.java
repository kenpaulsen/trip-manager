package org.paulsens.trip.media;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;
import org.testng.annotations.Test;

/**
 * The memory bound on image work: one image is capped by the bytes it DECODES to (pixels alone let a 16-bit
 * RGBA PNG through at 800 MB), and concurrent work shares one budget so uploads queue instead of stacking
 * rasters on the single task's heap.
 */
public class DecodeBudgetTest {
    private static final long MB = 1024L * 1024L;

    @Test
    public void theCostIsEveryWorkingCopyBoundedByTheWholeBudget() {
        final DecodeBudget budget = new DecodeBudget(1024, 1);
        assertEquals(budget.costMb(0L), 1, "never free");
        assertEquals(budget.costMb(-5L), 1);
        assertEquals(budget.costMb(36 * MB), 108, "a 12 MP photo: three copies of 36 MB");
        assertEquals(budget.costMb(DecodeBudget.MAX_DECODED_BYTES), 960, "the largest image runs nearly alone");
        assertEquals(budget.costMb(4096 * MB), 1024, "and nothing can ask for more than there is");
    }

    @Test
    public void workHoldsItsShareWhileItRunsAndGivesItBack() {
        final DecodeBudget budget = new DecodeBudget(1024, 1);
        assertEquals(budget.run(36 * MB, budget::availableMb), Integer.valueOf(1024 - 108));
        assertEquals(budget.availableMb(), 1024);
        assertThrows(IllegalStateException.class, () -> budget.run(36 * MB, DecodeBudgetTest::fail));
        assertEquals(budget.availableMb(), 1024, "a failure gives the share back too");
    }

    /** An entry point that calls another inside its reservation does not reserve twice (or wait on itself). */
    @Test
    public void aNestedOperationSharesTheOutersReservation() {
        final DecodeBudget budget = new DecodeBudget(100, 1);
        final int inside = budget.run(30 * MB, () -> budget.run(30 * MB, budget::availableMb));
        assertEquals(inside, 10, "one reservation of 90 MB, not two");
        assertEquals(budget.availableMb(), 100);
    }

    @Test
    public void workThatCannotGetItsShareWaitsAndIsThenRefusedAsBusy() throws Exception {
        final DecodeBudget budget = new DecodeBudget(100, 1);
        final CountDownLatch holding = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread big = Thread.ofVirtual().start(() -> budget.run(30 * MB, () -> park(holding, release)));
        assertTrue(holding.await(3, TimeUnit.SECONDS));

        assertEquals(budget.run(MB, () -> "small"), "small", "a small image still fits beside it");
        final PhotoRejectedException busy = org.testng.Assert.expectThrows(PhotoRejectedException.class,
                () -> budget.run(30 * MB, () -> "second large image"));
        assertTrue(busy.getMessage().contains("busy"), busy.getMessage());

        release.countDown();
        big.join(3000);
        assertEquals(budget.run(30 * MB, () -> "after"), "after", "and runs once the first is done");
    }

    @Test
    public void anInterruptedWaitIsARefusalAndKeepsTheInterrupt() throws Exception {
        final DecodeBudget budget = new DecodeBudget(10, 30);
        final CountDownLatch holding = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = Thread.ofVirtual().start(() -> budget.run(4 * MB, () -> park(holding, release)));
        assertTrue(holding.await(3, TimeUnit.SECONDS));
        final AtomicBoolean refused = new AtomicBoolean();
        final AtomicBoolean stillInterrupted = new AtomicBoolean();
        final Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                budget.run(4 * MB, () -> "never");
            } catch (final PhotoRejectedException ex) {
                refused.set(true);
                stillInterrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        Thread.sleep(100);
        waiter.interrupt();
        waiter.join(3000);
        release.countDown();
        holder.join(3000);
        assertTrue(refused.get());
        assertTrue(stillInterrupted.get());
    }

    // ------------------------------------------------------------------ the per-image cap, from the header

    @Test
    public void theHeaderSaysWhatADecodeWillAllocate() throws IOException {
        final PhotoProcessor.Header rgba16 = PhotoProcessor.headerOf(pngHeader(4000, 3000, 16, 6));
        assertEquals(rgba16.pixels(), 12_000_000L);
        assertEquals(rgba16.bytesPerPixel(), 8, "16 bits x 4 channels");
        assertEquals(rgba16.decodedBytes(), 96_000_000L);
        assertEquals(PhotoProcessor.headerOf(pngHeader(4000, 3000, 8, 2)).bytesPerPixel(), 3, "8-bit RGB");

        final ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpg", jpeg);
        final PhotoProcessor.Header photo = PhotoProcessor.headerOf(jpeg.toByteArray());
        assertEquals(photo.width(), 40);
        assertEquals(photo.height(), 30);
        assertEquals(photo.bytesPerPixel(), 3);
        assertEquals(PhotoProcessor.headerOf("not an image".getBytes(StandardCharsets.UTF_8)), null);
    }

    /**
     * The audit's file: 10000x10000 16-bit RGBA is exactly the 100 MP pixel cap, under a megabyte on disk, and
     * 800 MB decoded. It is refused from its header, before any raster is allocated.
     */
    @Test
    public void anImageThatDecodesPastTheByteCapIsRefusedFromItsHeader() throws IOException {
        final byte[] bomb = pngHeader(10_000, 10_000, 16, 6);
        assertTrue(bomb.length < 4096, "tiny on the wire");
        assertFalse(PhotoProcessor.headerOf(bomb).pixels() > PhotoProcessor.MAX_PIXELS, "the pixel cap lets it by");
        final PhotoRejectedException refused = org.testng.Assert.expectThrows(PhotoRejectedException.class,
                () -> new PhotoProcessor().preview(bomb));
        assertEquals(refused.getMessage(), "That image's dimensions are too large to process.");
        assertThrows(PhotoRejectedException.class, () -> new PhotoProcessor().process(bomb));
        assertEquals(DecodeBudget.shared().availableMb(), DecodeBudget.TOTAL_MB, "and its reservation is returned");
    }

    private static String fail() {
        throw new IllegalStateException("work failed");
    }

    private static String park(final CountDownLatch holding, final CountDownLatch release) {
        holding.countDown();
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return "done";
    }

    /** A valid PNG signature and IHDR claiming these dimensions, with one empty image-data chunk. */
    private static byte[] pngHeader(final int width, final int height, final int bitDepth, final int colorType)
            throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        final ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(width).putInt(height).put((byte) bitDepth).put((byte) colorType).put((byte) 0).put((byte) 0)
                .put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(data)) {
            deflate.write(new byte[16]);
        }
        chunk(out, "IDAT", data.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(final ByteArrayOutputStream out, final String type, final byte[] body)
            throws IOException {
        final byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        final CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(body);
        out.write(ByteBuffer.allocate(4).putInt(body.length).array());
        out.write(name);
        out.write(body);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
