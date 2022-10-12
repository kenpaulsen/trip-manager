package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.TodoStatus;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class TodoCommandsTest {
    private final TodoCommands todoCommands = new TodoCommands();

    @Test
    public void testCreateTodo() {
        final String tripId = RandomData.genAlpha(15);
        final TodoItem todo = todoCommands.createTodo(tripId);
        assertEquals(todo.getTripId(), tripId);
        assertNotNull(todo.getDataId());
        assertTrue(todo.getCreated().isEqual(LocalDateTime.now()) || todo.getCreated().isBefore(LocalDateTime.now()));
        assertNotNull(todo.getDescription());
        assertNull(todo.getMoreDetails());
    }

    @Test
    public void testSaveTodo() {
        final TodoItem todo = todoCommands.createTodo(RandomData.genAlpha(12));
        assertTrue(todoCommands.saveTodo(todo));
        final TodoItem retrieved = todoCommands.getTodo(todo.getTripId(), todo.getDataId());
        assertEquals(retrieved, todo);
    }

    @Test
    public void testGetTodos() {
        final String tripId = RandomData.genAlpha(12);
        final TodoItem todo = todoCommands.createTodo(tripId);
        assertTrue(todoCommands.saveTodo(todo));
        assertTrue(todoCommands.saveTodo(todo)); // Make sure saving twice does not create 2 entries
        final TodoItem todo2 = todoCommands.createTodo(tripId);
        final String description = RandomData.genAlpha(8);
        todo2.setDescription(description);
        final String details = RandomData.genAlpha(18);
        todo2.setMoreDetails(details);
        final PersonDataValue.Id dataId = PersonDataValue.Id.from(RandomData.genAlpha(11));
        todo2.setDataId(dataId);
        assertTrue(todoCommands.saveTodo(todo2));
        final List<TodoItem> todos = todoCommands.getTodos(tripId);
        assertEquals(todos.size(), 2);
        assertTrue(todos.contains(todo));
        assertTrue(todos.contains(todo2));
        final TodoItem todoFromGet = todos.get(todos.indexOf(todo2));
        assertEquals(todoFromGet.getCreated(), todo2.getCreated());
        assertEquals(todoFromGet.getDataId(), dataId);
        assertEquals(todoFromGet.getDescription(), description);
        assertEquals(todoFromGet.getMoreDetails(), details);
        assertEquals(todoFromGet.getTripId(), tripId);
    }

    @Test
    public void creatingSavingGettingTodoStatusWorks() {
        // Create...
        final String tripId = RandomData.genAlpha(22);
        final TodoItem todo = todoCommands.createTodo(tripId);
        final Person.Id pid = Person.Id.newInstance();
        final TodoStatus todoStatus = todoCommands.getTodoStatus(todo, pid);
        assertEquals(todoStatus.getStatus().getValue(), TodoStatus.StatusValue.TODO);
        assertNull(todoStatus.getStatus().getNotes());
        assertEquals(todoStatus.getPersonDataValue().getType(), TodoStatus.TODO_PERSON_DATA_VALUE_TYPE);
        assertEquals(todoStatus.getPersonDataValue().getDataId(), todo.getDataId());
        assertEquals(todoStatus.getPersonDataValue().getUserId(), pid);
        assertEquals(todoStatus.getPersonDataValue().getContent(), todoStatus.getStatus());
        assertEquals(todoStatus.getTodoItem(), todo);
        // Modify...
        final String notes = RandomData.genAlpha(32);
        todoStatus.getStatus().setNotes(notes);
        todoStatus.getStatus().setValue(TodoStatus.StatusValue.IN_PROGRESS);
        assertTrue(todoCommands.saveTodoStatus(todoStatus));
        // Get Again...
        final TodoStatus todoStatusFromGet = todoCommands.getTodoStatus(todo, pid);
        assertEquals(todoStatusFromGet.getStatus().getNotes(), notes);
        assertEquals(todoStatusFromGet.getStatus().getValue(), TodoStatus.StatusValue.IN_PROGRESS);
        assertEquals(todoStatusFromGet, todoStatus);
    }
}