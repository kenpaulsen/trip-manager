package org.paulsens.trip.media;

/**
 * Test-only door to {@link BackgroundRemover}'s package-private seams for tests in other packages: a test that
 * loads the real model must pin the free-memory reading, or its result depends on the machine's load at that
 * moment (and so on test order).
 */
public final class BackgroundRemoverHooks {
    private BackgroundRemoverHooks() {
    }

    /** Pins the free-memory reading high, so the model may load. */
    public static void roomy() {
        BackgroundRemover.freeMemoryForTest(() -> Long.MAX_VALUE);
    }

    /** Back to pristine: the real reading, no session, no remembered failure. */
    public static void reset() {
        BackgroundRemover.resetForTest();
    }
}
