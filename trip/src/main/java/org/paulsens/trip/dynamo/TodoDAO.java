package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.TodoItem;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

@Slf4j
public class TodoDAO {
    private static final String TODO_ITEM_TABLE = "todo_items";
    private static final String CONTENT = "content";
    private static final String DATA_ID = "dataId";
    private static final String TRIP_ID = "tripId";

    private final Map<String, Map<DataId, TodoItem>> todoCache = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Persistence persistence;

    protected TodoDAO(final ObjectMapper mapper, final Persistence persistence) {
        this.mapper = mapper;
        this.persistence = persistence;
    }

    protected CompletableFuture<Boolean> saveTodo(final TodoItem todo) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(TRIP_ID, persistence.toStrAttr(todo.getTripId()));
        map.put(DATA_ID, persistence.toStrAttr(todo.getDataId().getValue()));
        map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(todo)));
        final CompletableFuture<Map<DataId, TodoItem>> futTripTodos = getTodoItemCache(todo.getTripId());
        return persistence.putItem(b -> b.tableName(TODO_ITEM_TABLE).item(map))
                .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
                .thenCombine(futTripTodos, (success, tripRegs) -> success ? tripRegs : null)
                .thenApply(tripTodos -> persistence.cacheOne(tripTodos, todo, todo.getDataId(), tripTodos != null))
                .exceptionally(ex -> {
                    log.error("Failed to save todo (" + todo.getDescription() + ")!", ex);
                    return false;
                });
    }

    protected CompletableFuture<List<TodoItem>> getTodoItems(final String tripId) {
        return getTodoItemCache(tripId)
                .thenApply(map -> new ArrayList<>(map.values()));
    }

    protected CompletableFuture<Optional<TodoItem>> getTodoItem(final String tripId, final DataId pdvId){
        return getTodoItemCache(tripId)             // Ensure todos for this trip are loaded into memory
                .thenApply(map -> map.get(pdvId))   // Read from cache
                .thenApply(Optional::ofNullable);
    }

    public void clearCache() {
        todoCache.clear();
    }

    private CompletableFuture<Map<DataId, TodoItem>> getTodoItemCache(final String tripId) {
        final Map<DataId, TodoItem> result = todoCache.get(tripId);
        return (result == null) ? cacheTodoItems(tripId) : CompletableFuture.completedFuture(result);
    }

    private CompletableFuture<Map<DataId, TodoItem>> cacheTodoItems(final String tripId) {
        return loadTodoItems(tripId)
                .thenApply(cache -> {
                    todoCache.put(tripId, cache);
                    return cache;
                })
                .exceptionally(ex -> {
                    log.error("Unable to load and cache todo items for '" + tripId + "'!", ex);
                    throw new IllegalStateException(ex);
                });
    }

    private CompletableFuture<Map<DataId, TodoItem>> loadTodoItems(final String tripId) {
        log.info("Cache miss for todo items for tripId: {}", tripId);
        // Use a map that preserves order for sorting
        final Map<DataId, TodoItem> result = new ConcurrentSkipListMap<>();
        return persistence.query(qb -> queryTodoItemsByTrip(qb, tripId))
                .thenApply(resp -> resp.items().stream()
                        .map(m -> toTodoItem(m.get(CONTENT)))
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing(TodoItem::getCreated))
                        .toList())
                .thenAccept(list -> persistence.cacheAll(result, list, TodoItem::getDataId))
                .thenApply(v -> result);
    }

    private void queryTodoItemsByTrip(final QueryRequest.Builder qb, final String tripId) {
        qb.tableName(TODO_ITEM_TABLE)
                .keyConditionExpression(TRIP_ID + " = :tripIdVal")
                .expressionAttributeValues(
                        Map.of(":tripIdVal", AttributeValue.builder().s(tripId).build()));
    }

    private TodoItem toTodoItem(final AttributeValue content) {
        try {
            return mapper.readValue(content.s(), TodoItem.class);
        } catch (final IOException ex) {
            log.error("Unable to parse Todo Item record: " + content, ex);
            return null;
        }
    }
}
