package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.testng.Assert;
import org.testng.annotations.Test;

/*
 * NOTE: InMemoryPersistence on purpose: each test wants its OWN empty store to reason about allocation, and the
 * shared DynamoLocal engine is deliberately not reset between tests.
 */
public class ChatIdAllocatorTest {

    @Test
    public void neverReturnsDuplicateUnderConcurrentThreads() throws Exception {
        final ChatDAO dao = new ChatDAO(new ObjectMapper().findAndRegisterModules(),
                new InMemoryPersistence(), new InMemoryCacheClient());
        final int threads = 8;
        final int perThread = 200;
        final Set<Long> seen = ConcurrentHashMap.newKeySet();
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        seen.add(dao.nextMillis());
                    }
                } catch (final Exception ex) {
                    throw new RuntimeException(ex);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        Assert.assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        Assert.assertEquals(seen.size(), threads * perThread);
    }

    @Test
    public void advancesPastStalledClock() {
        final ChatDAO dao = new ChatDAO(new ObjectMapper().findAndRegisterModules(),
                new InMemoryPersistence(), new InMemoryCacheClient());
        final long a = dao.nextMillis();
        final long b = dao.nextMillis();
        Assert.assertTrue(b > a);
        // Force allocator into the future relative to a frozen previous value
        dao.forceAllocator(a);
        final long c = dao.nextMillis();
        Assert.assertTrue(c >= a + 1);
    }

    @Test
    public void rejectsWhenMoreThanFiveSecondsAhead() {
        final ChatDAO dao = new ChatDAO(new ObjectMapper().findAndRegisterModules(),
                new InMemoryPersistence(), new InMemoryCacheClient());
        final long now = System.currentTimeMillis();
        dao.forceAllocator(now + 10_000L);
        try {
            dao.nextMillis();
            Assert.fail("expected drift rejection");
        } catch (final IllegalStateException ex) {
            Assert.assertTrue(ex.getMessage().contains("drifted"));
        }
    }
}
