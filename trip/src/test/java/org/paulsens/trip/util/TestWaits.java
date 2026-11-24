package org.paulsens.trip.util;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import org.testng.Assert;

/**
 * Waiting and capturing for tests of code that answers somewhere else: on another thread, or on stdout (where
 * the audit trail and every sink fallback write). Public, next to {@link TestRandom}, because the copies this
 * replaced lived in three packages.
 */
public final class TestWaits {
    private static final long TIMEOUT_MILLIS = 5_000;
    private static final long POLL_MILLIS = 10;

    private TestWaits() {
    }

    /** Polls until the condition holds, failing with {@code message} after five seconds. */
    public static void awaitTrue(final BooleanSupplier condition, final String message)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(POLL_MILLIS);
        }
        Assert.fail(message);
    }

    /**
     * Everything the action printed to {@code System.out}. The swap is JVM-wide, so this only reads true while
     * test classes run one at a time (they do: surefire is not parallel here).
     */
    public static String captureStdout(final Runnable action) {
        final PrintStream original = System.out;
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
