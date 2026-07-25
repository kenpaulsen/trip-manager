package org.paulsens.trip.dynamo;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
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
    public void scanAllDefaultsToSinglePageScan() {
        final List<Map<String, AttributeValue>> items = get(persistence.scanAll(b -> b.tableName("test")));
        assertNotNull(items);
        assertTrue(items.isEmpty());
    }

    @Test
    public void queryAllDefaultsToSinglePageQuery() {
        final List<Map<String, AttributeValue>> items = get(persistence.queryAll(b -> b.tableName("test")));
        assertNotNull(items);
        assertTrue(items.isEmpty());
    }

    @Test
    public void scanAllUsesOverriddenScan() {
        final Map<String, AttributeValue> row = Map.of("id", AttributeValue.builder().s("x").build());
        final Persistence custom = new Persistence() {
            @Override
            public CompletableFuture<ScanResponse> scan(final Consumer<ScanRequest.Builder> scanRequest) {
                return CompletableFuture.completedFuture(ScanResponse.builder().items(List.of(row)).build());
            }
        };
        final List<Map<String, AttributeValue>> items = get(custom.scanAll(b -> b.tableName("test")));
        assertEquals(items, List.of(row));
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
