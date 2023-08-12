package org.paulsens.trip.dynamo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.BindingType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * The following details the data storage format.
 * <ul>
 *     <li>id1: {enum-type-num-value}_{id-value}</li>
 *     <li>id2: {enum-type-num-value}_{id-value}</li>
 *     <li>id1_type: {enum-type-string-value}</li>
 *     <li>id2_type: {enum-type-string-value}</li>
 * </ul>
 * Note: IDs are prefixed with enum numeric value to provide a namespace for each type.
 */
@Slf4j
public class BindingDAO {
    private static final String BINDINGS_TABLE = "bindings";
    private static final String ID1 = "id1";
    private static final String ID1_TYPE = "id1_type";
    private static final String ID2 = "id2";
    private static final String ID2_TYPE = "id2_type";

    // Map of {id-type}_{id} -> Map of {dest-id-type} -> List of {dest-id}
    private final Map<TypeAndId, Map<BindingType, List<String>>> bindingsCache = new ConcurrentHashMap<>();
    private final Persistence persistence;

    protected BindingDAO(final Persistence persistence) {
        this.persistence = persistence;
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
    protected CompletableFuture<List<String>> getBindings(
            final String id, final BindingType type, final BindingType destType) {
        return getBindings(TypeAndId.builder().id(id).type(type).build(), destType);
    }

    protected CompletableFuture<Boolean> saveBinding(final String id, final BindingType type,
            final String destId, final BindingType destType, final boolean both) {
        final TypeAndId key = TypeAndId.builder().id(id).type(type).build();
        final TypeAndId destKey = TypeAndId.builder().id(destId).type(destType).build();
        return saveBinding(key, destKey, both);
    }
    protected CompletableFuture<Boolean> removeBinding(final String id, final BindingType type,
            final String destId, final BindingType destType, final boolean both) {
        final TypeAndId key = TypeAndId.builder().id(id).type(type).build();
        final TypeAndId destKey = TypeAndId.builder().id(destId).type(destType).build();
        return removeBinding(key, destKey, both);
    }

    public void clearCache() {
        bindingsCache.clear();
    }

    private CompletableFuture<List<String>> getBindings(final TypeAndId key, final BindingType dest) {
        final Map<BindingType, List<String>> typeToBindingsMap = bindingsCache.get(key);
        return (typeToBindingsMap == null || typeToBindingsMap.get(dest) == null) ?
                addBindingsToCacheAndGetByType(key, dest) :
                CompletableFuture.completedFuture(typeToBindingsMap.computeIfAbsent(dest, k -> new ArrayList<>()));
    }

    private CompletableFuture<Boolean> saveBinding(final TypeAndId key, final TypeAndId destKey, final boolean both) {
        // If both flag is true, we will write 2 entries... one from id1 -> id2; the other id2 -> id1
        final CompletableFuture<Boolean> reverse = both ?
                saveBinding(destKey, key, false) : CompletableFuture.completedFuture(true);

        return getBindings(key, destKey.getType())
                // If not changed, do nothing
                .thenCompose(l -> l.contains(destKey.getId()) ?
                        CompletableFuture.completedFuture(true) : persistBinding(key, destKey))
                .thenCombine(reverse, (forward, backward) -> forward && backward);
    }

    private CompletableFuture<Boolean> removeBinding(final TypeAndId key, final TypeAndId destKey, final boolean both) {
        // If both flag is true, we will delete 2 entries... one from id1 -> id2; the other id2 -> id1
        final CompletableFuture<Boolean> reverse = both ?
                removeBinding(destKey, key, false) : CompletableFuture.completedFuture(true);
        return removeBinding(key, destKey)
                .thenCombine(reverse, (forward, backward) -> forward && backward);
    }

    private CompletableFuture<Boolean> persistBinding(final TypeAndId key, TypeAndId destKey) {
        // NOTE: only saveBinding(TypeAndId, TypeAndId, boolean) should call this method! It doesn't check for both
        // directions or skip updates to the db if there are no changes.
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID1, AttributeValue.builder().s(key.getValue()).build());
        map.put(ID1_TYPE, AttributeValue.builder().s(key.getType().name()).build());
        map.put(ID2, AttributeValue.builder().s(destKey.getValue()).build());
        map.put(ID2_TYPE, AttributeValue.builder().s(destKey.getType().name()).build());
        return persistence.putItem(b -> b.tableName(BINDINGS_TABLE).item(map))
                .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
                .thenCompose(r -> cacheOneBindingAsync(r, key, destKey));
    }

    private CompletableFuture<Boolean> removeBinding(final TypeAndId key, TypeAndId destKey) {
        log.info("Removing {} ({}) to {} ({}) binding.", key.getType().name(), key.getId(),
                destKey.getType().name(), destKey.getId());
        // NOTE: only removeBinding(TypeAndId, TypeAndId, boolean) should call this method!
        final Map<String, AttributeValue> primaryKey = Map.of(
                ID1, AttributeValue.builder().s(key.getValue()).build(),
                ID2, AttributeValue.builder().s(destKey.getValue()).build());
        return persistence.deleteItem(b -> b
                        .tableName(BINDINGS_TABLE)
                        .key(primaryKey))
                .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
                .thenCompose(r -> removeOneBindingFromCache(r, key, destKey));
    }

    private CompletableFuture<Boolean> cacheOneBindingAsync(
            final boolean success, final TypeAndId key1, final TypeAndId key2) {
        return success ?
                getBindings(key1, key2.getType()).thenApply(ids -> cacheOneBinding(ids, key2.getId())) :
                persistence.clearCache(bindingsCache.get(key1), CompletableFuture.completedFuture(false));
    }

    private boolean cacheOneBinding(final List<String> ids, final String id) {
        if (!ids.contains(id)) {
            ids.add(id);
        }
        return true;
    }

    private CompletableFuture<Boolean> removeOneBindingFromCache(
            final boolean success, final TypeAndId key1, final TypeAndId key2) {
        return success ?
                getBindings(key1, key2.getType()).thenApply(ids -> removeOneBindingFromCache(ids, key2.getId())) :
                persistence.clearCache(bindingsCache.get(key1), CompletableFuture.completedFuture(false));
    }

    private boolean removeOneBindingFromCache(final List<String> ids, final String id) {
        ids.remove(id);
        return true;
    }

    private CompletableFuture<Map<BindingType, List<String>>> addBindingsToCache(final TypeAndId key) {
        return loadBindings(key)
                .whenComplete((bindingMap, ex) -> bindingsCache.put(key, bindingMap));
    }

    private CompletableFuture<List<String>> addBindingsToCacheAndGetByType(final TypeAndId key, final BindingType destType) {
        return addBindingsToCache(key)
                .thenApply(bindingsForKey -> bindingsForKey.computeIfAbsent(destType, t -> new ArrayList<>()));
    }

    private CompletableFuture<Map<BindingType, List<String>>> loadBindings(final TypeAndId key) {
        log.info("Cache Miss for {} ({}) bindings.", key.getType().name(), key.getId());
        final Map<BindingType, List<String>> result = new HashMap<>();
        return persistence.query(qb -> createQueryByID1(qb, key))
                .thenApply(QueryResponse::items)
                .thenApply(list -> addToMap(list, result));
    }

    private Map<BindingType, List<String>> addToMap(
            final List<Map<String, AttributeValue>> items, final Map<BindingType, List<String>> dest) {
        for (final Map<String, AttributeValue> row : items) {
            final TypeAndId key2 = TypeAndId.from(row.get(ID2).s());
            dest.computeIfAbsent(key2.getType(), na -> new ArrayList<>())
                    .add(key2.getId());
        }
        // Loop through all types and make sure each has at least an empty List. Allows setting this to null to force
        // a cache-miss and reload from db
        for (BindingType type : BindingType.values()) {
            dest.computeIfAbsent(type, t -> new ArrayList<>());
        }
        return dest;
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

        @Override
        public String toString() {
            return getValue();
        }

        private String getValue() {
            return "" + type.getTypeId() + TYPE_SEPARATOR + id;
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
