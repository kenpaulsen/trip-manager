package org.paulsens.trip.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.testng.annotations.Test;

import static org.testng.Assert.assertTrue;

public class TripThreadsTest {

    /** The successor of the old platform pool must spawn NAMED VIRTUAL threads -- dumps rely on the prefix. */
    @Test
    public void spawnsNamedVirtualThreadsThatRun() throws Exception {
        final Thread probe = TripThreads.factory().newThread(TripThreadsTest::noop);
        assertTrue(probe.isVirtual(), "trip-persist workers must be virtual threads");
        assertTrue(probe.getName().startsWith("trip-persist-"),
                "thread dumps tell persistence work apart by this prefix, got: " + probe.getName());

        final CountDownLatch ran = new CountDownLatch(1);
        final Thread started = TripThreads.start(ran::countDown);
        assertTrue(ran.await(2, TimeUnit.SECONDS), "start() must actually run the task");
        assertTrue(started.isVirtual());
    }

    /** Every spawn -- start() and factory() alike -- runs in the background cache lane, no side door. */
    @Test
    public void spawnedWorkRidesTheBackgroundLane() throws Exception {
        final CountDownLatch checked = new CountDownLatch(2);
        final AtomicBoolean viaStart = new AtomicBoolean();
        final AtomicBoolean viaFactory = new AtomicBoolean();
        TripThreads.start(() -> recordLane(viaStart, checked));
        TripThreads.factory().newThread(() -> recordLane(viaFactory, checked)).start();
        assertTrue(checked.await(2, TimeUnit.SECONDS));
        assertTrue(viaStart.get(), "start() must bind the background lane");
        assertTrue(viaFactory.get(), "factory() threads must bind the background lane too");
    }

    private static void recordLane(final AtomicBoolean lane, final CountDownLatch done) {
        lane.set(CacheLane.isBackground());
        done.countDown();
    }

    private static void noop() {
    }
}
