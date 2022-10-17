package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Status;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.TodoStatus;
import org.paulsens.trip.util.RandomData;
import org.primefaces.model.DashboardColumn;
import org.primefaces.model.DashboardModel;
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
        final TodoItem todo = todoCommands.createTodo(tripId, DataId.newInstance());
        assertEquals(todo.getTripId(), tripId);
        assertNotNull(todo.getDataId());
        assertTrue(todo.getCreated().isEqual(LocalDateTime.now()) || todo.getCreated().isBefore(LocalDateTime.now()));
        assertNotNull(todo.getDescription());
        assertNull(todo.getMoreDetails());
    }

    @Test
    public void testSaveTodo() {
        final TodoItem todo = todoCommands.createTodo(RandomData.genAlpha(12), DataId.newInstance());
        assertTrue(todoCommands.saveTodo(todo));
        final TodoItem retrieved = todoCommands.getTodo(todo.getTripId(), todo.getDataId());
        assertEquals(retrieved, todo);
    }

    @Test
    public void testGetTodos() {
        final String tripId = RandomData.genAlpha(12);
        final TodoItem todo = todoCommands.createTodo(tripId, DataId.newInstance());
        assertTrue(todoCommands.saveTodo(todo));
        assertTrue(todoCommands.saveTodo(todo)); // Make sure saving twice does not create 2 entries
        final DataId dataId = DataId.from(RandomData.genAlpha(11));
        final TodoItem todo2 = todoCommands.createTodo(tripId, dataId);
        final String description = RandomData.genAlpha(8);
        todo2.setDescription(description);
        final String details = RandomData.genAlpha(18);
        todo2.setMoreDetails(details);
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
        final TodoItem todo = todoCommands.createTodo(tripId, DataId.newInstance());
        final Person.Id pid = Person.Id.newInstance();
        final TodoStatus todoStatus = todoCommands.getTodoStatus(todo, pid);
        assertEquals(todoStatus.getStatus().getValue(), Status.StatusValue.TODO);
        assertNull(todoStatus.getStatus().getNotes());
        assertEquals(todoStatus.getPersonDataValue().getType(), TodoStatus.TODO_PERSON_DATA_VALUE_TYPE);
        assertEquals(todoStatus.getPersonDataValue().getDataId(), todo.getDataId());
        assertEquals(todoStatus.getPersonDataValue().getUserId(), pid);
        assertEquals(todoStatus.getPersonDataValue().getContent(), todoStatus.getStatus());
        assertEquals(todoStatus.getTodoItem(), todo);
        // Modify...
        final String notes = RandomData.genAlpha(32);
        todoStatus.getStatus().setNotes(notes);
        todoStatus.getStatus().setValue(Status.StatusValue.IN_PROGRESS);
        assertTrue(todoCommands.saveTodoStatus(todoStatus));
        // Get Again...
        final TodoStatus todoStatusFromGet = todoCommands.getTodoStatus(todo, pid);
        assertEquals(todoStatusFromGet.getStatus().getNotes(), notes);
        assertEquals(todoStatusFromGet.getStatus().getValue(), Status.StatusValue.IN_PROGRESS);
        assertEquals(todoStatusFromGet, todoStatus);
    }

    @Test
    public void emptyDashWorks() {
        final String tripId = RandomData.genAlpha(22);
        final Person.Id pid = Person.Id.newInstance();
        final DashboardModel model = todoCommands.getTodoDashboard(tripId, pid, false);
        assertEquals(model.getColumnCount(), 2);
        assertEquals(model.getColumn(0).getWidgetCount(), 0);
        assertEquals(model.getColumn(1).getWidgetCount(), 0);
    }

    @Test
    public void canGetDashboardModel() {
        final String tripId = RandomData.genAlpha(22);
        final Person.Id pid = Person.Id.newInstance();
        final TodoItem todo = todoCommands.createTodo(tripId, DataId.newInstance());
        final String dataId = todo.getDataId().getValue();
        assertTrue(todoCommands.saveTodo(todo));                                // Create the Todo
        setTodoStatusForUser(tripId, todo, pid, Status.StatusValue.IN_PROGRESS);// Assign to user w/ in-progress
        setTodoStatusForUser(tripId, null, pid, Status.StatusValue.TODO);       // Add some more...
        setTodoStatusForUser(tripId, null, pid, Status.StatusValue.TODO);       // Add some more...
        setTodoStatusForUser(tripId, null, pid, Status.StatusValue.TODO);       // Add some more...
        setTodoStatusForUser(tripId, null, pid, Status.StatusValue.DONE);       // Add some more...
        setTodoStatusForUser(tripId, null, pid, Status.StatusValue.DONE);       // Add some more...

        final DashboardModel model = todoCommands.getTodoDashboard(tripId, pid, true);
        assertEquals(model.getColumnCount(), 3);
        assertEquals(model.getColumn(0).getWidgetCount(), 3);
        assertEquals(model.getColumn(1).getWidgetCount(), 1);
        assertEquals(model.getColumn(2).getWidgetCount(), 2);
        assertEquals(model.getColumn(1).getWidget(0), "status-" + dataId);

        // Modify status, rebuild DashboardModel... should see changes (even if we don't persist, due to caching)
        todoCommands.getTodoStatus(todo, pid).getStatus().setValue("DONE");
        final DashboardModel updatedModel = todoCommands.getTodoDashboard(tripId, pid, true);
        assertEquals(updatedModel.getColumn(0).getWidgetCount(), 3);
        assertEquals(updatedModel.getColumn(1).getWidgetCount(), 0);
        final DashboardColumn col2 = updatedModel.getColumn(2);
        assertEquals(col2.getWidgetCount(), 3);
        assertEquals(col2.getWidgets().get(col2.getWidgets().indexOf("status-" + dataId)),
                "status-" + todo.getDataId().getValue());
    }

    private void setTodoStatusForUser(
            final String tripId, final TodoItem todo, final Person.Id pid, final Status.StatusValue status) {
        final TodoItem notNullTodo;
        if (todo == null) {
            notNullTodo = todoCommands.createTodo(tripId, DataId.newInstance());
            assertTrue(todoCommands.saveTodo(notNullTodo));                     // Create the Todo
        } else {
            notNullTodo = todo;
        }
        final TodoStatus todoStatus = todoCommands.getTodoStatus(notNullTodo, pid);
        todoStatus.getStatus().setValue(status);
        assertTrue(todoCommands.saveTodoStatus(todoStatus));                    // Save it
    }
}