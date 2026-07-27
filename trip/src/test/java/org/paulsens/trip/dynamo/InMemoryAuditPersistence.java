package org.paulsens.trip.dynamo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * A minimal in-memory stand-in for the {@code audit} table.
 *
 * <p>{@link FakeData#createFakePersistence()} answers every query with an empty list, which is fine for the
 * cached DAOs (their cache holds the data) but useless for {@link AuditDAO}, which is deliberately uncached --
 * a save-then-read round trip there would always read nothing, and every test would pass without proving
 * anything.
 *
 * <p>This models only the three behaviours the audit DAO depends on, and models them honestly, because the
 * whole point is to catch the cases where the real table would behave differently from the naive expectation:
 *
 * <ul>
 *   <li>A conditional put on an existing key FAILS rather than overwriting. This is the behaviour the sort-key
 *       design rests on; a double that silently accepted it would hide exactly the bug worth catching.</li>
 *   <li>Keys sort as STRINGS, not numbers -- which is what makes zero-padding load-bearing.</li>
 *   <li>{@code scanIndexForward(false)} returns a partition newest-first.</li>
 * </ul>
 */
public class InMemoryAuditPersistence implements Persistence {

    /** day -> (sort key -> item). */
    private final Map<String, Map<String, Map<String, AttributeValue>>> rows = new ConcurrentHashMap<>();

    /** Counts conditional rejections, so a test can assert a collision actually happened. */
    private final AtomicInteger rejections = new AtomicInteger();

    @Override
    public CompletableFuture<PutItemResponse> putItem(final Consumer<PutItemRequest.Builder> request) {
        final PutItemRequest.Builder builder = PutItemRequest.builder();
        request.accept(builder);
        final PutItemRequest put = builder.build();

        final Map<String, AttributeValue> item = put.item();
        final String day = item.get(AuditDAO.PARTITION).s();
        final String sort = item.get(AuditDAO.SORT).s();
        final Map<String, Map<String, AttributeValue>> partition =
                rows.computeIfAbsent(day, key -> new ConcurrentHashMap<>());

        final boolean conditional = put.conditionExpression() != null
                && put.conditionExpression().contains("attribute_not_exists");
        if (conditional && partition.containsKey(sort)) {
            // What the real table does: reject, rather than quietly replacing an audit record.
            rejections.incrementAndGet();
            return CompletableFuture.<PutItemResponse>failedFuture(
                    ConditionalCheckFailedException.builder().message("key exists: " + sort).build());
        }
        partition.put(sort, item);
        // Assigned rather than chained: sdkHttpResponse() returns the widened AwsResponse.Builder.
        final PutItemResponse.Builder response = PutItemResponse.builder();
        response.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build());
        return CompletableFuture.completedFuture(response.build());
    }

    @Override
    public CompletableFuture<List<Map<String, AttributeValue>>> queryAll(
            final Consumer<QueryRequest.Builder> request) {
        final QueryRequest.Builder builder = QueryRequest.builder();
        request.accept(builder);
        final QueryRequest query = builder.build();

        final Map<String, AttributeValue> values = query.expressionAttributeValues();
        final String day = values.get(":day").s();
        final AttributeValue before = values.get(":before");

        final List<Map<String, AttributeValue>> result =
                new ArrayList<>(rows.getOrDefault(day, Map.of()).values());
        if (before != null) {
            result.removeIf(item -> item.get(AuditDAO.SORT).s().compareTo(before.s()) >= 0);
        }
        // String comparison on purpose -- the same comparison DynamoDB makes.
        final Comparator<Map<String, AttributeValue>> byKey =
                Comparator.comparing(item -> item.get(AuditDAO.SORT).s());
        result.sort(Boolean.FALSE.equals(query.scanIndexForward()) ? byKey.reversed() : byKey);
        return CompletableFuture.completedFuture(result);
    }

    /** How many writes were rejected for hitting a taken key. */
    public int getRejectionCount() {
        return rejections.get();
    }

    /** Total rows stored, across every day. */
    public int size() {
        return rows.values().stream().mapToInt(Map::size).sum();
    }
}
