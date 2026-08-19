package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * The single home for freshness semantics: every typed cache routes staleness and background-refresh
 * gating through {@link Revalidator}, so the rules are pinned once, here.
 */
public class RevalidatorTest {

    @Test
    public void markerStalenessRules() {
        final AtomicLong clock = new AtomicLong(10_000_000L);
        final Revalidator revalidator = revalidator(new InMemoryCacheClient(), clock, 0.5);
        assertTrue(revalidator.isSoftStale((String) null));
        assertTrue(revalidator.isSoftStale("  "));
        assertTrue(revalidator.isSoftStale("garbage"));
        assertTrue(revalidator.isSoftStale("1"), "the legacy adjacency marker is ancient, therefore stale");
        assertTrue(revalidator.isSoftStale(0L), "epoch 0 means unknown provenance");
        assertTrue(revalidator.isSoftStale(-5L));
        assertFalse(revalidator.isSoftStale(String.valueOf(clock.get())));
        assertFalse(revalidator.isSoftStale(clock.get()));
    }

    /** Jitter bounds: 0.0 makes the effective TTL 0.9x (stale sooner), 1.0 makes it 1.1x (stale later). */
    @Test
    public void jitterWidensAndNarrowsTheStalenessLine() {
        final AtomicLong clock = new AtomicLong(10_000_000L);
        final long loadedAt = clock.get();
        clock.addAndGet(Duration.ofSeconds(57).toMillis()); // 0.95x of the 60s soft TTL
        final InMemoryCacheClient client = new InMemoryCacheClient();
        assertTrue(revalidator(client, clock, 0.0).isSoftStale(loadedAt), "57s >= 54s effective: stale");
        assertFalse(revalidator(client, clock, 1.0).isSoftStale(loadedAt), "57s < 66s effective: fresh");
    }

    @Test
    public void nowStampIsTheClock() {
        final AtomicLong clock = new AtomicLong(10_000_000L);
        assertEquals(revalidator(new InMemoryCacheClient(), clock, 0.5).nowStamp(), "10000000");
    }

    /** Two schedules for the same key while the first reload is parked collapse to one reload. */
    @Test
    public void dedupSingleFlightsConcurrentSchedules() throws Exception {
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final AtomicLong clock = new AtomicLong(10_000_000L);
        final Revalidator revalidator = revalidator(client, clock, 0.5);
        final ConcurrentHashMap<String, Boolean> refreshing = new ConcurrentHashMap<>();
        final AtomicInteger reloads = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Runnable reload = () -> parkReload(reloads, started, release);

        revalidator.schedule(refreshing, "k", "data:k", "test", reload);
        assertTrue(started.await(3, TimeUnit.SECONDS));
        revalidator.schedule(refreshing, "k", "data:k", "test", reload);
        Thread.sleep(100);
        assertEquals(reloads.get(), 1, "the second schedule must be deduped while the first is in flight");

        release.countDown();
        awaitPermitsRestored();
        revalidator.schedule(refreshing, "k", "data:k", "test", reload);
        awaitReloads(reloads, 2); // dedup entry was released, so a later schedule runs again
        release.countDown();
    }

    /** With every permit taken, schedule does nothing and leaves the dedup entry released. */
    @Test
    public void atThePermitCapScheduleSkipsAndReleasesDedup() throws Exception {
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final AtomicLong clock = new AtomicLong(10_000_000L);
        final Revalidator revalidator = revalidator(client, clock, 0.5);
        final ConcurrentHashMap<String, Boolean> refreshing = new ConcurrentHashMap<>();
        final AtomicInteger reloads = new AtomicInteger();
        final int drained = drainAllPermits();
        try {
            revalidator.schedule(refreshing, "k", "data:k", "test", reloads::incrementAndGet);
            Thread.sleep(100);
            assertEquals(reloads.get(), 0, "no reload may run while the cap is exhausted");
            assertTrue(refreshing.isEmpty(), "a skipped schedule must not leave the key marked in-flight");
        } finally {
            releasePermits(drained);
        }
        revalidator.schedule(refreshing, "k", "data:k", "test", reloads::incrementAndGet);
        awaitReloads(reloads, 1);
    }

    /** A held distributed lock means another instance is refreshing: skip, restore permit and dedup. */
    @Test
    public void aLockLoserSkipsTheReloadAndRestoresGates() throws Exception {
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final AtomicLong clock = new AtomicLong(10_000_000L);
        final Revalidator revalidator = revalidator(client, clock, 0.5);
        final ConcurrentHashMap<String, Boolean> refreshing = new ConcurrentHashMap<>();
        final AtomicInteger reloads = new AtomicInteger();
        assertTrue(client.tryAcquireLock(CacheKeys.refreshLockKey("data:k"), Duration.ofMinutes(5)));

        revalidator.schedule(refreshing, "k", "data:k", "test", reloads::incrementAndGet);
        awaitPermitsRestored();
        assertEquals(reloads.get(), 0, "the lock loser must not reload");
        assertTrue(refreshing.isEmpty());
    }

    /** A throwing reload is swallowed; lock, permit, and dedup are all restored. */
    @Test
    public void aThrowingReloadRestoresLockPermitAndDedup() throws Exception {
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final AtomicLong clock = new AtomicLong(10_000_000L);
        final Revalidator revalidator = revalidator(client, clock, 0.5);
        final ConcurrentHashMap<String, Boolean> refreshing = new ConcurrentHashMap<>();
        final AtomicInteger reloads = new AtomicInteger();

        revalidator.schedule(refreshing, "k", "data:k", "test", RevalidatorTest::throwingReload);
        awaitPermitsRestored();
        assertTrue(refreshing.isEmpty(), "a failed reload must not leave the key marked in-flight");
        assertTrue(client.tryAcquireLock(CacheKeys.refreshLockKey("data:k"), Duration.ofMinutes(5)),
                "the distributed lock must have been released");
        client.releaseLock(CacheKeys.refreshLockKey("data:k"));

        revalidator.schedule(refreshing, "k", "data:k", "test", reloads::incrementAndGet);
        awaitReloads(reloads, 1); // gates fully restored: a later schedule runs normally
    }

    private static void throwingReload() {
        throw new IllegalStateException("reload boom (expected by the test)");
    }

    private static void parkReload(
            final AtomicInteger reloads, final CountDownLatch started, final CountDownLatch release) {
        reloads.incrementAndGet();
        started.countDown();
        awaitQuietly(release);
    }

    private static void awaitQuietly(final CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitReloads(final AtomicInteger reloads, final int expected) throws InterruptedException {
        for (int i = 0; i < 40 && reloads.get() < expected; i++) {
            Thread.sleep(50);
        }
        assertEquals(reloads.get(), expected);
    }

    private static void awaitPermitsRestored() throws InterruptedException {
        for (int i = 0; i < 40 && RefreshPermits.available() < RefreshPermits.MAX_CONCURRENT; i++) {
            Thread.sleep(50);
        }
        assertEquals(RefreshPermits.available(), RefreshPermits.MAX_CONCURRENT);
    }

    private static int drainAllPermits() {
        int drained = 0;
        while (RefreshPermits.tryAcquire()) {
            drained++;
        }
        return drained;
    }

    private static void releasePermits(final int count) {
        for (int i = 0; i < count; i++) {
            RefreshPermits.release();
        }
    }

    private static Revalidator revalidator(
            final CacheClient client, final AtomicLong clock, final double jitter) {
        return new Revalidator(client, Duration.ofMinutes(1), clock::get, () -> jitter);
    }
}
