package org.paulsens.trip.media;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bounds the memory that decoded images hold AT ONCE across the whole process, the way
 * {@link BackgroundRemover}'s gate bounds inference.
 *
 * <p>The upload byte cap does not bound decoded size: deflate shrinks a single-color 10000x10000 16-bit RGBA
 * PNG to under a megabyte, and it decodes to 800 MB. One such image is refused by {@link #MAX_DECODED_BYTES};
 * but several ordinary large photos at once (three phone panoramas, each a few hundred megabytes of raster plus
 * the oriented and scaled working copies) add up the same way, on the deliberately single task whose whole heap
 * is about 3 GB. So every image operation reserves its estimated working memory from one shared budget before
 * it decodes and returns it when its result is encoded. Small photos run side by side; a huge one runs alone;
 * work that cannot get its share within {@link #WAIT_SECONDS} is refused with "busy" instead of pushing the
 * heap over, which would fail unrelated requests on every other thread.
 */
final class DecodeBudget {
    /** What all concurrent image work may hold, in megabytes: about a third of the production heap. */
    static final int TOTAL_MB = 1024;

    /**
     * The most one decoded image may occupy. 100 megapixels of 8-bit RGB (300 MB) still fits; 100 megapixels
     * of 16-bit RGBA (800 MB) does not. With {@link #WORKING_COPIES} a single image at this cap takes nearly
     * the whole budget, which is the point: it runs by itself.
     */
    static final long MAX_DECODED_BYTES = 320L * 1024 * 1024;

    /** The decoded raster, its oriented or cropped copy, and the scaled copy being encoded. */
    static final int WORKING_COPIES = 3;

    static final long WAIT_SECONDS = 20;

    private static final long MB = 1024L * 1024L;
    private static final DecodeBudget SHARED = new DecodeBudget(TOTAL_MB, WAIT_SECONDS);
    /** Bound while this thread holds a reservation: a nested entry point shares it instead of asking twice. */
    private static final ScopedValue<Boolean> HOLDING = ScopedValue.newInstance();

    private final int totalMb;
    private final long waitSeconds;
    private final Semaphore permits;

    DecodeBudget(final int totalMb, final long waitSeconds) {
        this.totalMb = totalMb;
        this.waitSeconds = waitSeconds;
        // Fair: a large image waiting for most of the budget is not starved by a stream of small ones.
        this.permits = new Semaphore(totalMb, true);
    }

    static DecodeBudget shared() {
        return SHARED;
    }

    /**
     * Runs {@code work} holding a reservation for an image that decodes to {@code decodedBytes}.
     *
     * @throws PhotoRejectedException when the budget stays taken for {@link #WAIT_SECONDS}.
     */
    <T> T run(final long decodedBytes, final Supplier<T> work) {
        if (HOLDING.isBound()) {
            return work.get();
        }
        final int cost = costMb(decodedBytes);
        acquire(cost);
        try {
            return ScopedValue.where(HOLDING, Boolean.TRUE).call(work::get);
        } finally {
            permits.release(cost);
        }
    }

    /** The megabytes to reserve: every working copy, at least one, never more than the whole budget. */
    int costMb(final long decodedBytes) {
        final long mb = (Math.max(0L, decodedBytes) * WORKING_COPIES + MB - 1) / MB;
        return (int) Math.max(1L, Math.min(totalMb, mb));
    }

    int availableMb() {
        return permits.availablePermits();
    }

    private void acquire(final int cost) {
        try {
            if (permits.tryAcquire(cost, waitSeconds, TimeUnit.SECONDS)) {
                return;
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        throw new PhotoRejectedException("The server is busy with other photos right now. Please try again in a "
                + "moment.");
    }
}
