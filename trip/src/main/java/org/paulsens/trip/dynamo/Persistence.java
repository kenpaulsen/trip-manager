package org.paulsens.trip.dynamo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

public interface Persistence {
    default CompletableFuture<PutItemResponse> putItem(Consumer<PutItemRequest.Builder> putItemRequest) {
        final PutItemResponse.Builder builder = PutItemResponse.builder();
        builder.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build());
        return CompletableFuture.completedFuture(builder.build());
    }

    default CompletableFuture<ScanResponse> scan(Consumer<ScanRequest.Builder> scanRequest) {
        return CompletableFuture.completedFuture(ScanResponse.builder().items(new ArrayList<>()).build());
    }

    default CompletableFuture<QueryResponse> query(Consumer<QueryRequest.Builder> queryRequest) {
        return CompletableFuture.completedFuture(QueryResponse.builder().items(new ArrayList<>()).build());
    }

    default CompletableFuture<GetItemResponse> getItem(Consumer<GetItemRequest.Builder> getItemRequest) {
        final GetItemRequest.Builder builder = GetItemRequest.builder();
        getItemRequest.accept(builder); // Populate it from the consumer
        final GetItemRequest giReq = builder.build();
        final Map<String, AttributeValue> attrs = (CredentialsDAO.PASS_TABLE.equals(giReq.tableName())) ?
                FakeData.getTestUserCreds(giReq) : null /*new HashMap<>()*/;
        return CompletableFuture.completedFuture(GetItemResponse.builder().item(attrs).build());
    }

    default CompletableFuture<DeleteItemResponse> deleteItem(Consumer<DeleteItemRequest.Builder> deleteItemRequest) {
        final DeleteItemResponse.Builder builder = DeleteItemResponse.builder();
        builder.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build());
        return CompletableFuture.completedFuture(builder.build());
    }

    /**
     * This method caches a single value, typically used when a single value is updated. In fact, it does not support
     * the use case of saving a single value to the cache if the cache isn't fully populated. In other words, it will
     * do nothing if the cache is completely empty. This allows checking if the cache is empty to know if it is
     * completely populated.
     *
     * @param cacheMap      The Map used to cache values.
     * @param item          The value to cache.
     * @param key           The key to cache it under.
     * @param returnValue   The return value (only to help functional style, pass through).
     * @param <T>           The type of the thing being cached.
     * @param <R>           The return value type.
     * @return  It always returns the {@code returnValue} passed in.
     */
    default <K, T, R> R cacheOne(final Map<K, T> cacheMap, final T item, K key, final R returnValue) {
        if (cacheMap != null) {
            cacheMap.put(key, item);
        }
        return returnValue;
    }

    default <K, T> List<T> cacheAll(final Map<K, T> cacheMap, final List<T> items, final Function<T, K> getKey) {
        cacheMap.clear();
        items.forEach(item -> cacheMap.put(getKey.apply(item), item));
        return items;
    }

    default <K, T, R> R clearCache(Map<K, T> cacheMap, R returnValue) {
        // To avoid the chance of a slow scan returning and caching the result AFTER we clear, but before we may
        // have added/deleted content, introduce a cache clear delay.
        CompletableFuture.runAsync(() -> {
            try {
                // FIXME: do not do this! ScheduledExecutor if we really do need a delay
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
            cacheMap.clear();
        });
        return returnValue;
    }

    default AttributeValue toStrAttr(final String val) {
        return AttributeValue.builder().s(val).build();
    }

    default <T> List<T> sortList(final Collection<T> list, final Comparator<T> cmp) {
        final ArrayList<T> result = new ArrayList<>(list);
        result.sort(cmp);
        return result;
    }
}
