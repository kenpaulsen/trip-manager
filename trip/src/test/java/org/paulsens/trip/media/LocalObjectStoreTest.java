package org.paulsens.trip.media;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The local-mode object store that five photo beans used to each carry a copy of. The budget arithmetic is
 * the part worth pinning: a running total is exactly what drifts silently between copies.
 */
public class LocalObjectStoreTest {

    private static byte[] bytes(final int n) {
        return new byte[n];
    }

    @Test
    public void storesAndReadsBackWithOrWithoutAContentType() {
        final LocalObjectStore store = new LocalObjectStore(100);
        store.put("a", bytes(10));
        store.put("b", bytes(20), "image/png");

        Assert.assertEquals(store.get("a").orElseThrow().length, 10);
        Assert.assertNull(store.stored("a").orElseThrow().contentType(), "no type given, none invented");
        Assert.assertEquals(store.stored("b").orElseThrow().contentType(), "image/png");
        Assert.assertTrue(store.get("nope").isEmpty());
        Assert.assertEquals(store.totalBytes(), 30);
    }

    /** Oldest-first, and only as far as needed: the newest object is the one the caller is about to use. */
    @Test
    public void evictsOldestFirstOncePastTheBudget() {
        final LocalObjectStore store = new LocalObjectStore(25);
        store.put("first", bytes(10));
        store.put("second", bytes(10));
        store.put("third", bytes(10));

        Assert.assertTrue(store.get("first").isEmpty(), "the oldest goes");
        Assert.assertTrue(store.get("second").isPresent(), "and only as many as it takes");
        Assert.assertTrue(store.get("third").isPresent());
        Assert.assertEquals(store.totalBytes(), 20);
    }

    /** An object larger than the whole budget is stored and immediately evicted, never a negative total. */
    @Test
    public void anObjectOverTheWholeBudgetLeavesTheStoreEmptyAndConsistent() {
        final LocalObjectStore store = new LocalObjectStore(5);
        store.put("huge", bytes(50));
        Assert.assertTrue(store.get("huge").isEmpty());
        Assert.assertEquals(store.totalBytes(), 0);
    }

    /** The drift the copies had: re-putting a key added its bytes again without subtracting the old ones. */
    @Test
    public void rePuttingAKeyCountsItOnce() {
        final LocalObjectStore store = new LocalObjectStore(100);
        store.put("k", bytes(30));
        store.put("k", bytes(40));
        Assert.assertEquals(store.totalBytes(), 40, "the replaced bytes are no longer counted");
        Assert.assertEquals(store.get("k").orElseThrow().length, 40);
    }

    @Test
    public void removeAnswersWhetherAnythingWasThere() {
        final LocalObjectStore store = new LocalObjectStore(100);
        store.put("k", bytes(30));
        Assert.assertTrue(store.remove("k"));
        Assert.assertFalse(store.remove("k"), "already gone");
        Assert.assertFalse(store.remove("never"));
        Assert.assertEquals(store.totalBytes(), 0);
    }

    @Test
    public void removeByPrefixSweepsOnlyThatPrefixAndCounts() {
        final LocalObjectStore store = new LocalObjectStore(100);
        store.put("chat/t1/a", bytes(10));
        store.put("chat/t1/b", bytes(10));
        store.put("chat/t2/c", bytes(10));

        Assert.assertEquals(store.removeByPrefix("chat/t1/"), 2);
        Assert.assertTrue(store.get("chat/t1/a").isEmpty());
        Assert.assertTrue(store.get("chat/t2/c").isPresent(), "another prefix is untouched");
        Assert.assertEquals(store.removeByPrefix("chat/t1/"), 0, "nothing left to sweep");
        Assert.assertEquals(store.totalBytes(), 10);
    }

    /** Lowering the budget takes effect on the next put, which is how the bean tests reach eviction. */
    @Test
    public void theBudgetCanBeLoweredAfterConstruction() {
        final LocalObjectStore store = new LocalObjectStore(1000);
        store.put("a", bytes(10));
        store.maxBytes(15);
        store.put("b", bytes(10));
        Assert.assertTrue(store.get("a").isEmpty());
        Assert.assertTrue(store.get("b").isPresent());
    }
}
