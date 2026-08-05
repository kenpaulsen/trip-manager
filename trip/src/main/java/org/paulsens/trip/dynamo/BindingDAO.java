package org.paulsens.trip.dynamo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.AdjacencyCache;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.CompositeKey;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * The following details the data storage format.
 * <ul>
 *     <li>id1: {enum-type-num-value}_{id-value}</li>
 *     <li>id2: {enum-type-num-value}_{id-value}</li>
 *     <li>id1_type: {enum-type-string-value}</li>
 *     <li>id2_type: {enum-type-string-value}</li>
 * </ul>
 * Note: IDs are prefixed with enum numeric value to provide a namespace for each type.
 *
 * <p>Bidirectional bindings are two independent rows (non-atomic -- consciously unchanged); the shared cache
 * mirrors each direction's row into its own set, so it introduces no new inconsistency between the directions.</p>
 */
@Slf4j
public class BindingDAO {
    private static final String BINDINGS_TABLE = "bindings";
    private static final String ID1 = "id1";
    private static final String ID1_TYPE = "id1_type";
    private static final String ID2 = "id2";
    private static final String ID2_TYPE = "id2_type";

    private final Persistence persistence;
    private final CacheClient cacheClient;
    private final AdjacencyCache cache;

    protected BindingDAO(final Persistence persistence) {
        this(persistence, new InMemoryCacheClient());
    }

    protected BindingDAO(final Persistence persistence, final CacheClient cacheClient) {
        this.persistence = persistence;
        this.cacheClient = cacheClient;
        final AdjacencyCache.AdjacencyCacheBuilder cacheBuilder = AdjacencyCache.builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.BIND_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient));
        for (final BindingType type : BindingType.values()) {
            cacheBuilder.destTypeId(String.valueOf(type.getTypeId()));
        }
        this.cache = cacheBuilder.build();
    }

    /**
     * This method returns the {@code List} of bindings (maybe empty) for the requested {@code id}, {@code type}, and
     * {@code destType}. For example, to find all the {@link org.paulsens.trip.model.Transaction} <em>ids</em> for a
     * {@link org.paulsens.trip.model.Trip}: {@code getBindings("a-trip-id", TRIP, TRANSACTIONS)}.
     *
     * @param id        The id of the object for which to look for bindings.
     * @param type      The type of {@code id}.
     * @param destType  The type of object to look for bindings.
     *
     * @return  The {@code List} of bound ids, empty List if none found.
     */
    protected List<String> getBindings(
            final String id, final BindingType type, final BindingType destType) {
        return getBindings(TypeAndId.builder().id(id).type(type).build(), destType);
    }

    protected Boolean saveBinding(final String id, final BindingType type,
            final String destId, final BindingType destType, final boolean both) {
        final TypeAndId key = TypeAndId.builder().id(id).type(type).build();
        final TypeAndId destKey = TypeAndId.builder().id(destId).type(destType).build();
        return saveBinding(key, destKey, both);
    }
    protected Boolean removeBinding(final String id, final BindingType type,
            final String destId, final BindingType destType, final boolean both) {
        final TypeAndId key = TypeAndId.builder().id(id).type(type).build();
        final TypeAndId destKey = TypeAndId.builder().id(destId).type(destType).build();
        return removeBinding(key, destKey, both);
    }

    public void clearCache() {
        cacheClient.clearNamespace(CacheKeys.BIND_PREFIX);
    }

    private List<String> getBindings(final TypeAndId key, final BindingType dest) {
        try {
            return 
                    cache.get(key.getValue(), destTypeId(dest), () -> loadBindings(key));
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    private Boolean saveBinding(final TypeAndId key, final TypeAndId destKey, final boolean both) {
        if (destKey.getId() == null || destKey.getId().equalsIgnoreCase("none")
                || key.getId() == null || key.getId().equalsIgnoreCase("none")) {
            // Ignore
            return true;
        }
        // If both flag is true, we will write 2 entries... one from id1 -> id2; the other id2 -> id1
        final boolean reverse = both ? saveBinding(destKey, key, false) : true;
        // If not changed, do nothing
        final boolean forward = getBindings(key, destKey.getType()).contains(destKey.getId())
                || persistBinding(key, destKey);
        return forward && reverse;
    }

    private Boolean removeBinding(final TypeAndId key, final TypeAndId destKey, final boolean both) {
        // If both flag is true, we will delete 2 entries... one from id1 -> id2; the other id2 -> id1
        final boolean reverse = both ? removeBinding(destKey, key, false) : true;
        return removeBinding(key, destKey) && reverse;
    }

    private Boolean persistBinding(final TypeAndId key, final TypeAndId destKey) {
        // NOTE: only saveBinding(TypeAndId, TypeAndId, boolean) should call this method! It doesn't check for both
        // directions or skip updates to the db if there are no changes.
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID1, AttributeValue.builder().s(key.getValue()).build());
        map.put(ID1_TYPE, AttributeValue.builder().s(key.getType().name()).build());
        map.put(ID2, AttributeValue.builder().s(destKey.getValue()).build());
        map.put(ID2_TYPE, AttributeValue.builder().s(destKey.getType().name()).build());
        try {
            final boolean saved = persistence.putItem(b -> b.tableName(BINDINGS_TABLE).item(map))
                    .sdkHttpResponse().isSuccessful();
            return 
                    saved && cache.add(key.getValue(), destTypeId(destKey.getType()), destKey.getId());
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    private Boolean removeBinding(final TypeAndId key, final TypeAndId destKey) {
        log.info("Removing {} ({}) to {} ({}) binding.", key.getType().name(), key.getId(),
                destKey.getType().name(), destKey.getId());
        // NOTE: only removeBinding(TypeAndId, TypeAndId, boolean) should call this method!
        final Map<String, AttributeValue> primaryKey = Map.of(
                ID1, AttributeValue.builder().s(key.getValue()).build(),
                ID2, AttributeValue.builder().s(destKey.getValue()).build());
        try {
            final boolean deleted = persistence.deleteItem(b -> b.tableName(BINDINGS_TABLE).key(primaryKey))
                    .sdkHttpResponse().isSuccessful();
            return 
                    deleted && cache.remove(key.getValue(), destTypeId(destKey.getType()), destKey.getId());
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    /** Loads every direction of the source's partition: destination-type id -> bound destination ids. */
    private Map<String, List<String>> loadBindings(final TypeAndId key) {
        log.info("Cache Miss for {} ({}) bindings.", key.getType().name(), key.getId());
        final List<Map<String, AttributeValue>> items = persistence.queryAll(qb -> createQueryByID1(qb, key));
        // Pre-seed every dest type (including empty) so cache reconcile can clear deleted edges.
        final Map<String, List<String>> result = new HashMap<>();
        for (final BindingType type : BindingType.values()) {
            result.put(destTypeId(type), new ArrayList<>());
        }
        for (final Map<String, AttributeValue> row : items) {
            final TypeAndId key2 = TypeAndId.from(row.get(ID2).s());
            result.computeIfAbsent(destTypeId(key2.getType()), na -> new ArrayList<>())
                    .add(key2.getId());
        }
        return result;
    }

    private static String destTypeId(final BindingType type) {
        return String.valueOf(type.getTypeId());
    }

    private void createQueryByID1(final QueryRequest.Builder qb, final TypeAndId key) {
        qb.tableName(BINDINGS_TABLE)
                .keyConditionExpression(ID1 + " = :key")
                .expressionAttributeValues(
                        Map.of(":key", AttributeValue.builder().s(key.getValue()).build()));
    }

    @Value
    @Builder
    public static class TypeAndId {
        private static final char TYPE_SEPARATOR = '_';

        @NonNull
        String id;
        @NonNull
        BindingType type;

        TypeAndId(final String id, final BindingType type) {
            // We convert to a composite key for validation and normalization
            this.id = type.isComposite() ? CompositeKey.from(id).getValue() : id;
            this.type = type;
        }

        private String getValue() {
            return "" + type.getTypeId() + TYPE_SEPARATOR + id;
        }

        @Override
        public String toString() {
            return getValue();
        }

        private static TypeAndId from(final String combined) {
            final int idx = combined.indexOf(TYPE_SEPARATOR);
            final int num = (idx < 0) ? -1 : Integer.parseInt(combined.substring(0, idx).trim());
            return TypeAndId.builder()
                    .id((idx < 0) ? combined : combined.substring(idx + 1))
                    .type(BindingType.from(num))
                    .build();
        }
    }
}
