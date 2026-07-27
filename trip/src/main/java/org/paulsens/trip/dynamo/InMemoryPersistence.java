package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * The in-memory datastore behind local mode and the tests.
 *
 * <p>It exists because "every read returns empty" is not a harmless fake once anything reads through to the
 * datastore. Two places do:
 *
 * <ul>
 *   <li>{@link AuditDAO} is deliberately uncached, so a save-then-read round trip would always read nothing --
 *       leaving the audit page untestable end to end and empty on a laptop.</li>
 *   <li>{@code SearchIndex} rebuilds from a table scan and RECONCILES against that snapshot, deleting entries
 *       the snapshot does not contain. An empty scan therefore erases the index that the writers just
 *       populated, and person search silently returns nothing. In production the scan is authoritative and the
 *       reconcile is correct; it is only a fake that reports an empty database while holding data that makes
 *       the two disagree.</li>
 * </ul>
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
public class InMemoryPersistence implements Persistence {

    /** Serializes fake people the same way PersonDAO does, so the scan looks like the real table. */
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    /** day -> (sort key -> item). */
    private final Map<String, Map<String, Map<String, AttributeValue>>> rows = new ConcurrentHashMap<>();

    /** Counts conditional rejections, so a test can assert a collision actually happened. */
    private final AtomicInteger rejections = new AtomicInteger();

    @Override
    public CompletableFuture<PutItemResponse> putItem(final Consumer<PutItemRequest.Builder> request) {
        final PutItemRequest.Builder builder = PutItemRequest.builder();
        request.accept(builder);
        final PutItemRequest put = builder.build();
        if (!AuditDAO.AUDIT_TABLE.equals(put.tableName())) {
            // Only the audit table is modelled here; everything else keeps the plain fake's behaviour.
            return Persistence.super.putItem(request);
        }

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

    /**
     * DynamoDB reserved words this table's schema collides with.
     *
     * <p>{@code day} is genuinely reserved, and naming it directly in an expression makes the real service
     * reject the query with {@code ValidationException}. The first version of this double ignored expression
     * syntax altogether, so twelve tests passed against a query that could not work anywhere but here -- the
     * admin page shipped showing "no records" over a table holding 36,000. A double that accepts what the real
     * service rejects is worse than no double at all, so this one rejects it too.
     *
     * @see <a href="https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/ReservedWords.html">
     *      DynamoDB reserved words</a>
     */
    private static final List<String> RESERVED = List.of("day", "timestamp", "year", "month", "hour", "second",
            "status", "name", "value", "size", "action", "message", "target", "source", "user");

    /**
     * Table scans. The {@code people} table reports the seeded fake people; everything else stays empty.
     *
     * <p>Person search depends on this. {@code SearchIndex} rebuilds from a scan and deletes index entries the
     * scan does not confirm, so an empty scan wipes the entries {@code savePerson} just wrote and search
     * silently returns nothing -- which is what broke EditPrivsSelectionIT under a real cache.
     */
    @Override
    public CompletableFuture<List<Map<String, AttributeValue>>> scanAll(
            final Consumer<software.amazon.awssdk.services.dynamodb.model.ScanRequest.Builder> request) {
        final software.amazon.awssdk.services.dynamodb.model.ScanRequest.Builder builder =
                software.amazon.awssdk.services.dynamodb.model.ScanRequest.builder();
        request.accept(builder);
        if (!PersonDAO.PERSON_TABLE.equals(builder.build().tableName()) || FakeData.getFakePeople() == null) {
            return Persistence.super.scanAll(request);
        }
        final List<Map<String, AttributeValue>> items = new ArrayList<>();
        for (final org.paulsens.trip.model.Person person : FakeData.getFakePeople()) {
            try {
                items.add(Map.of(
                        "id", AttributeValue.builder().s(person.getId().getValue()).build(),
                        "content", AttributeValue.builder().s(MAPPER.writeValueAsString(person)).build()));
            } catch (final com.fasterxml.jackson.core.JsonProcessingException ex) {
                // A fake that cannot serialize its own seed data is a bug in the seed, not something to hide.
                throw new IllegalStateException("Unable to serialize fake person " + person.getId(), ex);
            }
        }
        return CompletableFuture.completedFuture(items);
    }

    @Override
    public CompletableFuture<List<Map<String, AttributeValue>>> queryAll(
            final Consumer<QueryRequest.Builder> request) {
        final QueryRequest.Builder builder = QueryRequest.builder();
        request.accept(builder);
        final QueryRequest query = builder.build();
        if (!AuditDAO.AUDIT_TABLE.equals(query.tableName())) {
            return Persistence.super.queryAll(request);
        }

        rejectUnaliasedReservedWords(query.keyConditionExpression());

        final Map<String, AttributeValue> values = query.expressionAttributeValues();
        // Every alias used in the expression must actually be declared, or the real service rejects the query.
        for (final String alias : aliasesIn(query.keyConditionExpression())) {
            if (!query.expressionAttributeNames().containsKey(alias)) {
                throw new IllegalArgumentException("ValidationException: ExpressionAttributeNames contains no "
                        + "entry for " + alias);
            }
        }
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

    /**
     * Throws the way DynamoDB would when an expression names a reserved word without an
     * {@code ExpressionAttributeNames} alias.
     */
    private static void rejectUnaliasedReservedWords(final String expression) {
        if (expression == null) {
            return;
        }
        for (final String word : RESERVED) {
            // Word-boundary match, and not preceded by '#': "#day" is the aliased (correct) form, and ":day"
            // is a value placeholder, which is always fine.
            if (expression.matches("(?is).*(^|[^#:\\w])" + word + "\\b.*")) {
                throw new IllegalArgumentException("ValidationException: Invalid KeyConditionExpression: "
                        + "Attribute name is a reserved keyword; reserved keyword: " + word
                        + " -- use ExpressionAttributeNames (#" + word + ")");
            }
        }
    }

    /** The {@code #name} aliases referenced by an expression. */
    private static List<String> aliasesIn(final String expression) {
        if (expression == null) {
            return List.of();
        }
        final List<String> found = new ArrayList<>();
        final java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("#\\w+").matcher(expression);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
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
