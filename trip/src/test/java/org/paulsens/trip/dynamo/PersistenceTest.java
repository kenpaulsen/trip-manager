package org.paulsens.trip.dynamo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import static org.testng.Assert.*;

public class PersistenceTest {
    private final Persistence persistence = new Persistence() {};

    @Test
    public void putItemReturns200() {
        final PutItemResponse resp = get(persistence.putItem(b -> b.tableName("test")));
        assertEquals(resp.sdkHttpResponse().statusCode(), 200);
        assertTrue(resp.sdkHttpResponse().isSuccessful());
    }

    @Test
    public void scanReturnsEmptyItems() {
        final ScanResponse resp = get(persistence.scan(b -> b.tableName("test")));
        assertNotNull(resp.items());
        assertTrue(resp.items().isEmpty());
    }

    @Test
    public void queryReturnsEmptyItems() {
        final QueryResponse resp = get(persistence.query(b -> b.tableName("test")));
        assertNotNull(resp.items());
        assertTrue(resp.items().isEmpty());
    }

    @Test
    public void deleteItemReturns200() {
        final DeleteItemResponse resp = get(persistence.deleteItem(b -> b.tableName("test")));
        assertEquals(resp.sdkHttpResponse().statusCode(), 200);
        assertTrue(resp.sdkHttpResponse().isSuccessful());
    }

    @Test
    public void toStrAttrCreatesCorrectAttributeValue() {
        final AttributeValue av = persistence.toStrAttr("hello");
        assertEquals(av.s(), "hello");
    }

    @Test
    public void cacheOneAddsToNonNullMap() {
        final Map<String, String> cache = new HashMap<>();
        cache.put("existing", "value");
        final String result = persistence.cacheOne(cache, "newVal", "newKey", "returnMe");
        assertEquals(result, "returnMe");
        assertEquals(cache.get("newKey"), "newVal");
        assertEquals(cache.size(), 2);
    }

    @Test
    public void cacheOneHandlesNullMap() {
        final String result = persistence.cacheOne(null, "val", "key", "returnMe");
        assertEquals(result, "returnMe");
    }

    @Test
    public void cacheOneReturnsPassedReturnValue() {
        final Map<String, String> cache = new HashMap<>();
        assertFalse(persistence.cacheOne(cache, "v", "k", false));
        assertTrue(persistence.cacheOne(cache, "v2", "k2", true));
    }

    @Test
    public void cacheAllClearsAndRepopulates() {
        final Map<String, Integer> cache = new HashMap<>();
        cache.put("old", 0);
        final List<Integer> items = List.of(1, 2, 3);
        final List<Integer> result = persistence.cacheAll(cache, items, i -> "key" + i);
        assertEquals(result, items);
        assertEquals(cache.size(), 3);
        assertFalse(cache.containsKey("old"));
        assertEquals(cache.get("key1"), Integer.valueOf(1));
        assertEquals(cache.get("key2"), Integer.valueOf(2));
        assertEquals(cache.get("key3"), Integer.valueOf(3));
    }

    @Test
    public void cacheAllWithEmptyList() {
        final Map<String, String> cache = new HashMap<>();
        cache.put("old", "val");
        persistence.cacheAll(cache, List.of(), s -> s);
        assertTrue(cache.isEmpty());
    }

    @Test
    public void clearCacheEventuallyClears() throws InterruptedException {
        final Map<String, String> cache = new HashMap<>();
        cache.put("k1", "v1");
        cache.put("k2", "v2");
        final boolean result = persistence.clearCache(cache, false);
        assertFalse(result);
        // Give the async clear time to complete (50ms delay + margin)
        Thread.sleep(150);
        assertTrue(cache.isEmpty());
    }

    @Test
    public void clearCacheReturnsPassedValue() {
        final Map<String, String> cache = new HashMap<>();
        assertEquals(persistence.clearCache(cache, "hello"), "hello");
        assertTrue(persistence.clearCache(cache, true));
    }

    @Test
    public void sortListReturnsSortedCopy() {
        final List<Integer> original = Arrays.asList(3, 1, 4, 1, 5, 9);
        final List<Integer> sorted = persistence.sortList(original, Comparator.naturalOrder());
        assertEquals(sorted, List.of(1, 1, 3, 4, 5, 9));
        // Original should be unchanged
        assertEquals(original, Arrays.asList(3, 1, 4, 1, 5, 9));
    }

    @Test
    public void sortListWithEmptyCollection() {
        final List<String> result = persistence.sortList(List.<String>of(), Comparator.naturalOrder());
        assertTrue(result.isEmpty());
    }

    @Test
    public void sortListWithCustomComparator() {
        final List<String> items = List.of("banana", "apple", "cherry");
        final List<String> sorted = persistence.sortList(items, Comparator.<String>reverseOrder());
        assertEquals(sorted, List.of("cherry", "banana", "apple"));
    }

    private <T> T get(final CompletableFuture<T> future) {
        try {
            return future.get(1_000, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException | ExecutionException | TimeoutException ex) {
            throw new RuntimeException(ex);
        }
    }
}
