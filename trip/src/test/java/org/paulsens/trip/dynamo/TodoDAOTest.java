package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class TodoDAOTest {
    private TodoDAO dao;

    @BeforeMethod
    public void setup() {
        dao = new TodoDAO(new ObjectMapper().findAndRegisterModules(), DynamoLocal.persistence());
    }

    @Test
    public void saveAndRetrieveTodoItem() throws IOException {
        final TodoItem todo = TodoItem.builder()
                .tripId(RandomData.genAlpha(10))
                .dataId(DataId.newInstance())
                .description("Buy sunscreen")
                .build();
        assertTrue(dao.saveTodo(todo));
        final Optional<TodoItem> found = dao.getTodoItem(todo.getTripId(), todo.getDataId());
        assertTrue(found.isPresent());
        assertEquals(found.get(), todo);
    }

    @Test
    public void getTodoItemsReturnsEmptyForUnknownTrip() {
        assertTrue(dao.getTodoItems(RandomData.genAlpha(10)).isEmpty());
    }

    @Test
    public void getTodoItemReturnsEmptyForUnknownDataId() {
        final String tripId = RandomData.genAlpha(10);
        assertTrue(dao.getTodoItem(tripId, DataId.newInstance()).isEmpty());
    }

    @Test
    public void multipleTodosForSameTrip() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        for (int i = 0; i < 5; i++) {
            dao.saveTodo(TodoItem.builder()
                    .tripId(tripId)
                    .dataId(DataId.newInstance())
                    .description("Todo " + i)
                    .build());
        }
        assertEquals(dao.getTodoItems(tripId).size(), 5);
    }

    @Test
    public void todosForDifferentTripsAreIsolated() throws IOException {
        final String trip1 = RandomData.genAlpha(10);
        final String trip2 = RandomData.genAlpha(10);
        dao.saveTodo(TodoItem.builder().tripId(trip1).dataId(DataId.newInstance()).description("t1").build());
        dao.saveTodo(TodoItem.builder().tripId(trip1).dataId(DataId.newInstance()).description("t1b").build());
        dao.saveTodo(TodoItem.builder().tripId(trip2).dataId(DataId.newInstance()).description("t2").build());
        assertEquals(dao.getTodoItems(trip1).size(), 2);
        assertEquals(dao.getTodoItems(trip2).size(), 1);
    }

    @Test
    public void saveTodoIsIdempotent() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        final TodoItem todo = TodoItem.builder()
                .tripId(tripId)
                .dataId(DataId.newInstance())
                .description("Idempotent")
                .build();
        dao.saveTodo(todo);
        dao.saveTodo(todo);
        assertEquals(dao.getTodoItems(tripId).size(), 1);
    }

    @Test
    public void updateTodoReplacesInCache() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        final DataId dataId = DataId.newInstance();
        final TodoItem original = TodoItem.builder()
                .tripId(tripId).dataId(dataId).description("Original").build();
        dao.saveTodo(original);
        assertEquals(dao.getTodoItem(tripId, dataId).get().getDescription(), "Original");
        final TodoItem updated = TodoItem.builder()
                .tripId(tripId).dataId(dataId).description("Updated").build();
        dao.saveTodo(updated);
        assertEquals(dao.getTodoItem(tripId, dataId).get().getDescription(), "Updated");
    }

    /**
     * Clearing the cache must not lose data.
     *
     * <p>This asserted the OPPOSITE until the DAO tests moved onto a real engine: that the row was GONE after a
     * clear. That was true only because the fake persistence stored nothing, so the cache WAS the store. The
     * real invariant is that a clear drops the cached copy and the next read is served by the store.
     */
    @Test
    public void clearingTheCacheDoesNotLoseTheRow() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        dao.saveTodo(TodoItem.builder()
                .tripId(tripId).dataId(DataId.newInstance()).description("clear me").build());
        assertEquals(dao.getTodoItems(tripId).size(), 1);

        dao.clearCache();

        assertEquals(dao.getTodoItems(tripId).size(), 1);
    }

    @Test
    public void moreDetailsIsPreserved() throws IOException {
        final TodoItem todo = TodoItem.builder()
                .tripId(RandomData.genAlpha(10))
                .dataId(DataId.newInstance())
                .description("With details")
                .moreDetails("These are the extra details")
                .build();
        dao.saveTodo(todo);
        final TodoItem found = dao.getTodoItem(todo.getTripId(), todo.getDataId()).orElse(null);
        assertNotNull(found);
        assertEquals(found.getMoreDetails(), "These are the extra details");
    }
    }

