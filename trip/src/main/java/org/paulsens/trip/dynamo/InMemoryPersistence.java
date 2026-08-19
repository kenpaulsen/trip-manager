package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * The in-memory datastore behind local mode and the tests.
 *
 * <p>Originally audit-only; generalized to a small PK/SK registry so {@link ChatDAO} (and any future table)
 * can round-trip under unit tests with the same honesty rules:
 *
 * <ul>
 *   <li>A conditional put on an existing key FAILS rather than overwriting.</li>
 *   <li>Keys sort as STRINGS, not numbers — zero-padding is load-bearing.</li>
 *   <li>{@code scanIndexForward(false)} returns a partition newest-first.</li>
 *   <li>Unaliased reserved words in expressions are rejected the way DynamoDB would.</li>
 * </ul>
 */
public class InMemoryPersistence implements Persistence {

    /** Serializes fake people the same way PersonDAO does, so the scan looks like the real table. */
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    /**
     * DynamoDB reserved words this table's schema collides with.
     *
     * @see <a href="https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/ReservedWords.html">
     *      DynamoDB reserved words</a>
     */
    private static final List<String> RESERVED = List.of("day", "timestamp", "year", "month", "hour", "second",
            "status", "name", "value", "size", "action", "message", "target", "source", "user");

    /**
     * Per-table key attribute names. Tables not listed fall through to the default no-op Persistence (empty
     * reads) so unrelated code paths keep working.
     */
    private static final Map<String, TableKeys> TABLES = Map.ofEntries(
            Map.entry(AuditDAO.AUDIT_TABLE, new TableKeys(AuditDAO.PARTITION, AuditDAO.SORT)),
            Map.entry(ChatDAO.CHANNELS_TABLE, new TableKeys(ChatDAO.ATTR_CHANNEL_ID, null)),
            Map.entry(ChatDAO.MEMBERS_TABLE, new TableKeys(ChatDAO.ATTR_CHANNEL_ID, ChatDAO.ATTR_PERSON_ID)),
            Map.entry(ChatDAO.MESSAGES_TABLE, new TableKeys(ChatDAO.ATTR_CHANNEL_ID, ChatDAO.ATTR_MSG_ID)),
            Map.entry(ChatDAO.REACTIONS_TABLE, new TableKeys(ChatDAO.ATTR_CHANNEL_ID, ChatDAO.ATTR_SK)),
            // Invite links must round-trip in local mode: the webtest mints, redeems and revokes them.
            Map.entry(ChatInviteDAO.INVITES_TABLE,
                    new TableKeys(ChatInviteDAO.CHANNEL_ID, ChatInviteDAO.SELECTOR)),
            // Chat photos made this table WRITTEN in local mode (album rows). Without a real fake behind
            // it, the rows lived only in the media cache -- and the first cache invalidation (any media
            // delete does one) silently emptied the whole trip album.
            Map.entry(MediaDAO.MEDIA_TABLE, new TableKeys("id", null)),
            // Both content-template tables are written in local mode (admin dialogs + FakeData seeds); same
            // lesson as media -- without a real fake, rows live only in the cache.
            Map.entry(TemplateDAO.TEMPLATES_TABLE, new TableKeys("id", null)),
            Map.entry(ContentDAO.CONTENT_TABLE, new TableKeys("id", null)),
            // Remember-me works fully in local mode (the webtest deletes JSESSIONID and expects the cookie
            // to restore the session), so the token rows need a real fake store. Same for passkeys: the
            // webtest registers against a virtual authenticator and signs back in with it.
            Map.entry(RememberMeDAO.REMEMBER_TABLE, new TableKeys(RememberMeDAO.SELECTOR, null)),
            Map.entry(PasskeyDAO.PASSKEY_TABLE, new TableKeys(PasskeyDAO.CREDENTIAL_ID, null)),
            // Families are written in local mode (FakeData seeds one, the family page edits them), and
            // their optimistic-version puts must be honestly rejectable here or the race is untestable.
            Map.entry(FamilyDAO.FAMILY_TABLE, new TableKeys("id", null)),
            // Organizations follow the family rules (seeded + edited locally, version races must be real);
            // membership rows must round-trip so the org pages and the migration tooling are testable.
            Map.entry(OrganizationDAO.ORG_TABLE, new TableKeys("id", null)),
            Map.entry(OrgMemberDAO.ORG_MEMBERS_TABLE,
                    new TableKeys(OrgMemberDAO.ORG_ID, OrgMemberDAO.PERSON_ID)),
            // Processor configs round-trip locally (FakeData seeds FAKE configs; orgSettings edits them),
            // and their optimistic-version puts must be honestly rejectable.
            Map.entry(PaymentProcessorDAO.PROCESSORS_TABLE,
                    new TableKeys(PaymentProcessorDAO.ORG_ID, PaymentProcessorDAO.CONFIG_ID)),
            // Payments round-trip locally (the FAKE-processor flow is fully webtestable), and their
            // conditional state transitions must be honestly rejectable or the double-capture race is
            // untestable.
            Map.entry(PaymentDAO.PAYMENTS_TABLE, new TableKeys(PaymentDAO.PAYMENT_ID, null)));

    /** table -> (pk -> (sk -> item)). sk is "" for PK-only tables. */
    private final Map<String, Map<String, Map<String, Map<String, AttributeValue>>>> store =
            new ConcurrentHashMap<>();

    /** Counts conditional rejections, so a test can assert a collision actually happened. */
    private final AtomicInteger rejections = new AtomicInteger();

    @Override
    public PutItemResponse putItem(final Consumer<PutItemRequest.Builder> request) {
        final PutItemRequest.Builder builder = PutItemRequest.builder();
        request.accept(builder);
        final PutItemRequest put = builder.build();
        final TableKeys keys = TABLES.get(put.tableName());
        if (keys == null) {
            return Persistence.super.putItem(request);
        }

        final Map<String, AttributeValue> item = put.item();
        final String pk = item.get(keys.pk).s();
        final String sk = keys.sk == null ? "" : item.get(keys.sk).s();
        final Map<String, Map<String, AttributeValue>> partition =
                store.computeIfAbsent(put.tableName(), t -> new ConcurrentHashMap<>())
                        .computeIfAbsent(pk, k -> new ConcurrentHashMap<>());

        final String condition = put.conditionExpression();
        final boolean notExists = condition != null && condition.contains("attribute_not_exists");
        if (notExists && partition.containsKey(sk)) {
            rejections.incrementAndGet();
            throw ConditionalCheckFailedException.builder().message("key exists: " + sk).build();
        }
        if (condition != null && !notExists && !equalityConditionHolds(put, partition.get(sk))) {
            rejections.incrementAndGet();
            throw ConditionalCheckFailedException.builder().message("condition failed: " + condition).build();
        }
        partition.put(sk, new HashMap<>(item));
        final PutItemResponse.Builder response = PutItemResponse.builder();
        response.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build());
        return response.build();
    }

    @Override
    public GetItemResponse getItem(final Consumer<GetItemRequest.Builder> getItemRequest) {
        final GetItemRequest.Builder builder = GetItemRequest.builder();
        getItemRequest.accept(builder);
        final GetItemRequest giReq = builder.build();
        final TableKeys keys = TABLES.get(giReq.tableName());
        if (keys == null) {
            return Persistence.super.getItem(getItemRequest);
        }
        final Map<String, AttributeValue> key = giReq.key();
        final String pk = key.get(keys.pk).s();
        final String sk = keys.sk == null ? "" : key.get(keys.sk).s();
        final Map<String, AttributeValue> item = store
                .getOrDefault(giReq.tableName(), Map.of())
                .getOrDefault(pk, Map.of())
                .get(sk);
        return GetItemResponse.builder().item(item == null ? Map.of() : item).build();
    }

    @Override
    public DeleteItemResponse deleteItem(
            final Consumer<DeleteItemRequest.Builder> deleteItemRequest) {
        final DeleteItemRequest.Builder builder = DeleteItemRequest.builder();
        deleteItemRequest.accept(builder);
        final DeleteItemRequest del = builder.build();
        final TableKeys keys = TABLES.get(del.tableName());
        if (keys == null) {
            return Persistence.super.deleteItem(deleteItemRequest);
        }
        final Map<String, AttributeValue> key = del.key();
        final String pk = key.get(keys.pk).s();
        final String sk = keys.sk == null ? "" : key.get(keys.sk).s();
        final Map<String, Map<String, AttributeValue>> partition =
                store.getOrDefault(del.tableName(), Map.of()).get(pk);
        if (partition != null) {
            partition.remove(sk);
        }
        final DeleteItemResponse.Builder response = DeleteItemResponse.builder();
        response.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build());
        return response.build();
    }

    /**
     * Table scans. The {@code people} table reports the seeded fake people; everything else stays empty
     * (chat loaders do not scan).
     */
    @Override
    public List<Map<String, AttributeValue>> scanAll(
            final Consumer<software.amazon.awssdk.services.dynamodb.model.ScanRequest.Builder> request) {
        final software.amazon.awssdk.services.dynamodb.model.ScanRequest.Builder builder =
                software.amazon.awssdk.services.dynamodb.model.ScanRequest.builder();
        request.accept(builder);
        // Registered tables scan what was actually written. Media needed this first (its cache reloads via
        // scanAll after an invalidation, so an empty answer silently erased the trip photo albums); the
        // template/content tables need it for the same loader shape plus their reference-check scan.
        final String tableName = builder.build().tableName();
        if (TABLES.containsKey(tableName)) {
            final List<Map<String, AttributeValue>> items = new ArrayList<>();
            for (final Map<String, Map<String, AttributeValue>> partition
                    : store.getOrDefault(tableName, Map.of()).values()) {
                items.addAll(partition.values());
            }
            return items;
        }
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
                throw new IllegalStateException("Unable to serialize fake person " + person.getId(), ex);
            }
        }
        return items;
    }

    /**
     * The whole-partition read. Delegates to {@link #query} because this fake holds everything in memory and so
     * has no 1 MB page limit to paginate around -- one "page" is always the complete answer.
     */
    @Override
    public List<Map<String, AttributeValue>> queryAll(
            final Consumer<QueryRequest.Builder> request) {
        return query(request).items();
    }

    /**
     * The bounded single-page read. This is the one that honors {@code limit}, so callers that must not walk a
     * whole partition (the chat message log) get the same truncation here as they do against real DynamoDB.
     */
    @Override
    public QueryResponse query(final Consumer<QueryRequest.Builder> request) {
        final QueryRequest.Builder builder = QueryRequest.builder();
        request.accept(builder);
        final QueryRequest query = builder.build();
        final TableKeys keys = TABLES.get(query.tableName());
        if (keys == null) {
            return Persistence.super.query(request);
        }

        rejectUnaliasedReservedWords(query.keyConditionExpression());

        for (final String alias : aliasesIn(query.keyConditionExpression())) {
            if (query.expressionAttributeNames() == null
                    || !query.expressionAttributeNames().containsKey(alias)) {
                throw new IllegalArgumentException("ValidationException: ExpressionAttributeNames contains no "
                        + "entry for " + alias);
            }
        }

        final Map<String, AttributeValue> values = query.expressionAttributeValues();
        final Map<String, String> names = query.expressionAttributeNames() == null
                ? Map.of() : query.expressionAttributeNames();
        final String expr = query.keyConditionExpression();

        // Resolve the partition key value. Audit uses :day; chat uses :c.
        final String pkValue = resolvePk(values, names, expr, keys);
        if (pkValue == null) {
            return QueryResponse.builder().items(List.of()).build();
        }

        final List<Map<String, AttributeValue>> result = new ArrayList<>(
                store.getOrDefault(query.tableName(), Map.of())
                        .getOrDefault(pkValue, Map.of())
                        .values());

        applySkBounds(result, keys, values, expr);

        final Comparator<Map<String, AttributeValue>> bySk = Comparator.comparing(item -> sortKeyOf(item, keys));
        result.sort(Boolean.FALSE.equals(query.scanIndexForward()) ? bySk.reversed() : bySk);

        Integer limit = query.limit();
        if (limit != null && limit > 0 && result.size() > limit) {
            return QueryResponse.builder()
                    .items(new ArrayList<>(result.subList(0, limit)))
                    .build();
        }
        return QueryResponse.builder().items(result).build();
    }

    /**
     * Evaluates a single {@code #attr = :value} equality condition (the optimistic-version guard shape) the way
     * DynamoDB would: the item must exist and the aliased attribute must equal the supplied value. Anything this
     * fake cannot parse fails loudly rather than silently passing -- a test asserting on a condition it does not
     * actually enforce would be worse than no test.
     */
    private static boolean equalityConditionHolds(
            final PutItemRequest put, final Map<String, AttributeValue> existing) {
        final java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("^\\s*(#\\w+)\\s*=\\s*(:\\w+)\\s*$").matcher(put.conditionExpression());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "InMemoryPersistence cannot evaluate condition: " + put.conditionExpression());
        }
        final String attr = put.expressionAttributeNames() == null
                ? null : put.expressionAttributeNames().get(matcher.group(1));
        final AttributeValue expected = put.expressionAttributeValues() == null
                ? null : put.expressionAttributeValues().get(matcher.group(2));
        if (attr == null || expected == null) {
            throw new IllegalArgumentException("ValidationException: unresolved alias in condition: "
                    + put.conditionExpression());
        }
        final AttributeValue actual = existing == null ? null : existing.get(attr);
        return actual != null && actual.equals(expected);
    }

    /** The sort key as a comparable string; a table without one, or a missing value, sorts as empty. */
    private static String sortKeyOf(final Map<String, AttributeValue> item, final TableKeys keys) {
        if (keys.sk == null) {
            return "";
        }
        final AttributeValue sk = item.get(keys.sk);
        return sk == null || sk.s() == null ? "" : sk.s();
    }

    private static String resolvePk(
            final Map<String, AttributeValue> values,
            final Map<String, String> names,
            final String expr,
            final TableKeys keys) {
        if (values == null) {
            return null;
        }
        // Prefer common placeholders used by DAOs.
        if (values.containsKey(":day")) {
            return values.get(":day").s();
        }
        if (values.containsKey(":c")) {
            return values.get(":c").s();
        }
        // Fallback: first value whose attribute name maps to the PK.
        for (final Map.Entry<String, AttributeValue> e : values.entrySet()) {
            if (e.getKey().startsWith(":") && e.getValue().s() != null) {
                return e.getValue().s();
            }
        }
        return null;
    }

    private static void applySkBounds(
            final List<Map<String, AttributeValue>> result,
            final TableKeys keys,
            final Map<String, AttributeValue> values,
            final String expr) {
        if (keys.sk == null || values == null || expr == null) {
            return;
        }
        final AttributeValue before = values.get(":before");
        if (before != null) {
            result.removeIf(item -> atOrAfter(item.get(keys.sk), before.s()));
        }
        final AttributeValue after = values.get(":after");
        if (after != null) {
            result.removeIf(item -> atOrBefore(item.get(keys.sk), after.s()));
        }
        final AttributeValue lo = values.get(":lo");
        final AttributeValue hi = values.get(":hi");
        if (lo != null && hi != null) {
            result.removeIf(item -> outsideRange(item.get(keys.sk), lo.s(), hi.s()));
        }
    }

    // The bound predicates below take the raw attribute so an *absent* sort key stays distinguishable from an
    // empty one: a row with no sort key at all cannot satisfy a range condition and is always dropped.

    /** True when the row falls at or after an exclusive {@code :before} bound, so it must be dropped. */
    private static boolean atOrAfter(final AttributeValue sk, final String before) {
        return sk == null || sk.s() == null || sk.s().compareTo(before) >= 0;
    }

    /** True when the row falls at or before an exclusive {@code :after} bound, so it must be dropped. */
    private static boolean atOrBefore(final AttributeValue sk, final String after) {
        return sk == null || sk.s() == null || sk.s().compareTo(after) <= 0;
    }

    /** True when the row falls outside an inclusive {@code BETWEEN :lo AND :hi} bound. */
    private static boolean outsideRange(final AttributeValue sk, final String lo, final String hi) {
        return sk == null || sk.s() == null || sk.s().compareTo(lo) < 0 || sk.s().compareTo(hi) > 0;
    }

    private static void rejectUnaliasedReservedWords(final String expression) {
        if (expression == null) {
            return;
        }
        for (final String word : RESERVED) {
            if (expression.matches("(?is).*(^|[^#:\\w])" + word + "\\b.*")) {
                throw new IllegalArgumentException("ValidationException: Invalid KeyConditionExpression: "
                        + "Attribute name is a reserved keyword; reserved keyword: " + word
                        + " -- use ExpressionAttributeNames (#" + word + ")");
            }
        }
    }

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

    /** Total rows stored, across every table and partition. */
    public int size() {
        return store.values().stream()
                .flatMap(t -> t.values().stream())
                .mapToInt(Map::size)
                .sum();
    }

    private record TableKeys(String pk, String sk) {
    }
}
