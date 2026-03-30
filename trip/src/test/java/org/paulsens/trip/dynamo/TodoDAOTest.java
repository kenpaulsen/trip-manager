package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class TodoDAOTest {
    private TodoDAO dao;

    @BeforeMethod
    public void setup() {
        dao = new TodoDAO(new ObjectMapper().findAndRegisterModules(), FakeData.createFakePersistence());
    }

    @Test
    public void saveAndRetrieveTodoItem() throws IOException {
        final TodoItem todo = TodoItem.builder()
                .tripId(RandomData.genAlpha(10))
                .dataId(DataId.newInstance())
                .description("Buy sunscreen")
                .build();
        assertTrue(get(dao.saveTodo(todo)));
        final Optional<TodoItem> found = get(dao.getTodoItem(todo.getTripId(), todo.getDataId()));
        assertTrue(found.isPresent());
        assertEquals(found.get(), todo);
    }

    @Test
    public void getTodoItemsReturnsEmptyForUnknownTrip() {
        assertTrue(get(dao.getTodoItems(RandomData.genAlpha(10))).isEmpty());
    }

    @Test
    public void getTodoItemReturnsEmptyForUnknownDataId() {
        final String tripId = RandomData.genAlpha(10);
        assertTrue(get(dao.getTodoItem(tripId, DataId.newInstance())).isEmpty());
    }

    @Test
    public void multipleTodosForSameTrip() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        for (int i = 0; i < 5; i++) {
            get(dao.saveTodo(TodoItem.builder()
                    .tripId(tripId)
                    .dataId(DataId.newInstance())
                    .description("Todo " + i)
                    .build()));
        }
        assertEquals(get(dao.getTodoItems(tripId)).size(), 5);
    }

    @Test
    public void todosForDifferentTripsAreIsolated() throws IOException {
        final String trip1 = RandomData.genAlpha(10);
        final String trip2 = RandomData.genAlpha(10);
        get(dao.saveTodo(TodoItem.builder().tripId(trip1).dataId(DataId.newInstance()).description("t1").build()));
        get(dao.saveTodo(TodoItem.builder().tripId(trip1).dataId(DataId.newInstance()).description("t1b").build()));
        get(dao.saveTodo(TodoItem.builder().tripId(trip2).dataId(DataId.newInstance()).description("t2").build()));
        assertEquals(get(dao.getTodoItems(trip1)).size(), 2);
        assertEquals(get(dao.getTodoItems(trip2)).size(), 1);
    }

    @Test
    public void saveTodoIsIdempotent() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        final TodoItem todo = TodoItem.builder()
                .tripId(tripId)
                .dataId(DataId.newInstance())
                .description("Idempotent")
                .build();
        get(dao.saveTodo(todo));
        get(dao.saveTodo(todo));
        assertEquals(get(dao.getTodoItems(tripId)).size(), 1);
    }

    @Test
    public void updateTodoReplacesInCache() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        final DataId dataId = DataId.newInstance();
        final TodoItem original = TodoItem.builder()
                .tripId(tripId).dataId(dataId).description("Original").build();
        get(dao.saveTodo(original));
        assertEquals(get(dao.getTodoItem(tripId, dataId)).get().getDescription(), "Original");
        final TodoItem updated = TodoItem.builder()
                .tripId(tripId).dataId(dataId).description("Updated").build();
        get(dao.saveTodo(updated));
        assertEquals(get(dao.getTodoItem(tripId, dataId)).get().getDescription(), "Updated");
    }

    @Test
    public void clearCacheWorks() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        get(dao.saveTodo(TodoItem.builder()
                .tripId(tripId).dataId(DataId.newInstance()).description("clear me").build()));
        assertEquals(get(dao.getTodoItems(tripId)).size(), 1);
        dao.clearCache();
        assertEquals(get(dao.getTodoItems(tripId)).size(), 0);
    }

    @Test
    public void moreDetailsIsPreserved() throws IOException {
        final TodoItem todo = TodoItem.builder()
                .tripId(RandomData.genAlpha(10))
                .dataId(DataId.newInstance())
                .description("With details")
                .moreDetails("These are the extra details")
                .build();
        get(dao.saveTodo(todo));
        final TodoItem found = get(dao.getTodoItem(todo.getTripId(), todo.getDataId())).orElse(null);
        assertNotNull(found);
        assertEquals(found.getMoreDetails(), "These are the extra details");
    }

    private <T> T get(final CompletableFuture<T> future) {
        try {
            return future.get(1_000, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException | ExecutionException | TimeoutException ex) {
            throw new RuntimeException(ex);
        }
    }
}
