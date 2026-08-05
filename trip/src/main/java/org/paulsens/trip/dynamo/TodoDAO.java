package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.PartitionCache;
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

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final CacheClient cacheClient;
    private final PartitionCache<DataId, TodoItem> cache;

    protected TodoDAO(final ObjectMapper mapper, final Persistence persistence) {
        this(mapper, persistence, new InMemoryCacheClient());
    }

    protected TodoDAO(final ObjectMapper mapper, final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cacheClient = cacheClient;
        this.cache = PartitionCache.<DataId, TodoItem>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.TODO_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .idGetter(TodoItem::getDataId)
                .idFormatter(DataId::getValue)
                .serializer(this::toJson)
                .deserializer(this::parseTodoItem)
                // The old per-JVM cache was a skip-list map keyed by data id, so that was the caller-visible order.
                .order(Comparator.comparing(TodoItem::getDataId))
                .build();
    }

    protected Boolean saveTodo(final TodoItem todo) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(TRIP_ID, persistence.toStrAttr(todo.getTripId()));
        map.put(DATA_ID, persistence.toStrAttr(todo.getDataId().getValue()));
        map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(todo)));
        try {
            final boolean saved = persistence.putItem(b -> b.tableName(TODO_ITEM_TABLE).item(map))
                    .sdkHttpResponse().isSuccessful();
            if (!saved) {
                return false;
            }
            return cache.put(todo.getTripId(), todo);
        } catch (final RuntimeException ex) {
            return logSaveTodoFailure(todo, ex);
        }
    }

    private Boolean logSaveTodoFailure(final TodoItem todo, final Throwable ex) {
        log.error("Failed to save todo (" + todo.getDescription() + ")!", ex);
        return false;
    }

    protected List<TodoItem> getTodoItems(final String tripId) {
        try {
            return cache.getAll(tripId, () -> loadTodoItems(tripId));
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    protected Optional<TodoItem> getTodoItem(final String tripId, final DataId pdvId) {
        try {
            return cache.getOne(tripId, pdvId, () -> loadTodoItems(tripId));
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    public void clearCache() {
        cacheClient.clearNamespace(CacheKeys.TODO_PREFIX);
    }

    private List<TodoItem> loadTodoItems(final String tripId) {
        log.info("Cache miss for todo items for tripId: {}", tripId);
        return persistence.queryAll(qb -> queryTodoItemsByTrip(qb, tripId)).stream()
                .map(m -> toTodoItem(m.get(CONTENT)))
                .filter(Objects::nonNull)
                .toList();
    }

    private void queryTodoItemsByTrip(final QueryRequest.Builder qb, final String tripId) {
        qb.tableName(TODO_ITEM_TABLE)
                .keyConditionExpression(TRIP_ID + " = :tripIdVal")
                .expressionAttributeValues(
                        Map.of(":tripIdVal", AttributeValue.builder().s(tripId).build()));
    }

    private TodoItem toTodoItem(final AttributeValue content) {
        return (content == null) ? null : parseTodoItem(content.s());
    }

    private TodoItem parseTodoItem(final String json) {
        try {
            return mapper.readValue(json, TodoItem.class);
        } catch (final IOException ex) {
            log.error("Unable to parse Todo Item record: " + json, ex);
            return null;
        }
    }

    private String toJson(final TodoItem todo) {
        try {
            return mapper.writeValueAsString(todo);
        } catch (final IOException ex) {
            log.error("Unable to serialize todo item: " + todo.getDataId(), ex);
            return null;
        }
    }
}
