package org.paulsens.trip.dynamo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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

/**
 * Abstracts the datastore. Since the virtual-threads migration these are plain BLOCKING calls: the caller's
 * (virtual) thread waits for the I/O, errors surface as ordinary thrown exceptions, and no continuation ever
 * runs on an SDK or Netty thread. The defaults implement a benign empty store so fakes only override what
 * they model.
 */
public interface Persistence {
    default PutItemResponse putItem(Consumer<PutItemRequest.Builder> putItemRequest) {
        final PutItemResponse.Builder builder = PutItemResponse.builder();
        builder.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build());
        return builder.build();
    }

    default ScanResponse scan(Consumer<ScanRequest.Builder> scanRequest) {
        return ScanResponse.builder().items(new ArrayList<>()).build();
    }

    default QueryResponse query(Consumer<QueryRequest.Builder> queryRequest) {
        return QueryResponse.builder().items(new ArrayList<>()).build();
    }

    default GetItemResponse getItem(Consumer<GetItemRequest.Builder> getItemRequest) {
        final GetItemRequest.Builder builder = GetItemRequest.builder();
        getItemRequest.accept(builder); // Populate it from the consumer
        final GetItemRequest giReq = builder.build();
        final Map<String, AttributeValue> attrs = (CredentialsDAO.PASS_TABLE.equals(giReq.tableName()))
                ? FakeData.getTestUserCreds(giReq) : null /*new HashMap<>()*/;
        return GetItemResponse.builder().item(attrs).build();
    }

    default DeleteItemResponse deleteItem(Consumer<DeleteItemRequest.Builder> deleteItemRequest) {
        final DeleteItemResponse.Builder builder = DeleteItemResponse.builder();
        builder.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build());
        return builder.build();
    }

    /**
     * Scans the <em>entire</em> table, following pagination. The single-page {@link #scan} silently truncates at
     * the 1 MB response limit; loaders that feed the shared cache must use this instead. This default delegates to
     * {@link #scan} (one page) so fakes and tests keep their existing behavior.
     */
    default List<Map<String, AttributeValue>> scanAll(Consumer<ScanRequest.Builder> scanRequest) {
        return scan(scanRequest).items();
    }

    /**
     * Runs a query, following pagination (see {@link #scanAll}). This default delegates to {@link #query} (one
     * page) so fakes and tests keep their existing behavior.
     *
     * <p><b>This ignores {@code limit}.</b> DynamoDB's {@code limit} caps a single <em>page</em>, not the total,
     * so a paginating read keeps fetching until the partition is exhausted. Use this only when you genuinely want
     * every matching item (a cache warm, an index rebuild). For a bounded read -- anything user-facing, or any
     * partition that grows without bound, such as a chat channel's message log -- call {@link #query} and take
     * the one page.
     */
    default List<Map<String, AttributeValue>> queryAll(Consumer<QueryRequest.Builder> queryRequest) {
        return query(queryRequest).items();
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
