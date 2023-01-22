package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.mockito.Mockito;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

public class TodoStatusTest {
    final ObjectMapper mapper = DAO.getInstance().getMapper();

    @Test(expectedExceptions = { IllegalArgumentException.class },
            expectedExceptionsMessageRegExp = ".*TODO_PERSON_DATA_VALUE_TYPE.*")
    public void ensureTypeIsChecked() {
        final Person.Id userId = Person.Id.from(RandomData.genAlpha(15));
        final DataId dataId = DataId.newInstance();
        final String type = RandomData.genAlpha(13);    // Random type will fail! Must be "todo"
        getTestTodoStatus(userId, dataId, type, Status.builder().build());
    }

    @Test(expectedExceptions = { IllegalStateException.class },
            expectedExceptionsMessageRegExp = ".*do not have matching Id.*")
    public void ensureDataIdsMatch() {
        final TodoItem todo = TodoItem.builder()
                .tripId(RandomData.genAlpha(3))
                .dataId(DataId.newInstance())
                .description(RandomData.genAlpha(31))
                .moreDetails(RandomData.genAlpha(19))
                .build();
        final PersonDataValue pdv = PersonDataValue.builder()
                .userId(Person.Id.from(RandomData.genAlpha(15)))
                .dataId(DataId.newInstance()) // Doesn't match above... should blow up
                .type(TodoStatus.TODO_PERSON_DATA_VALUE_TYPE)
                .content(RandomData.genAlpha(22))
                .build();
        new TodoStatus(todo, pdv); // Should now blow up
    }

    @Test
    public void StatusCanBeStringOrObjectOrMap() throws IOException {
        final Person.Id userId = Person.Id.from(RandomData.genAlpha(15));
        final DataId dataId = DataId.newInstance();
        final String type = TodoStatus.TODO_PERSON_DATA_VALUE_TYPE;
        final Status status = Status.builder()
                .value(Status.StatusValue.DONE)
                .notes(RandomData.genAlpha(131))
                .owner(Person.Id.newInstance())
                .visibility(Status.Visibility.ADMIN)
                .priority(Status.Priority.HIGH)
                .build();
        final String statusJson = mapper.writeValueAsString(status);
        final TodoStatus todoStatus = getTestTodoStatus(userId, dataId, type, status);
        final TodoStatus todoStatusWithJsonStatus = new TodoStatus(todoStatus.getTodoItem(),
                PersonDataValue.builder()
                        .userId(userId)
                        .dataId(dataId)
                        .type(type)
                        .content(statusJson)
                        .build());
        assertEquals(todoStatusWithJsonStatus, todoStatus);
        final TodoStatus todoStatusWithStringMap = new TodoStatus(todoStatus.getTodoItem(),
                PersonDataValue.builder()
                        .userId(userId)
                        .dataId(dataId)
                        .type(type)
                        .content(Map.of(
                                "value", status.getValue().toString(),
                                "notes", status.getNotes(),
                                "lastUpdate", status.getLastUpdate().toString(),
                                "owner", status.getOwner().getValue(),
                                "priority", String.valueOf(status.getPriority()),
                                "visibility", String.valueOf(status.getVisibility())))
                        .build());
        assertEquals(todoStatusWithStringMap, todoStatus);
        final TodoStatus todoStatusWithObjectMap = new TodoStatus(todoStatus.getTodoItem(),
                PersonDataValue.builder()
                        .userId(userId)
                        .dataId(dataId)
                        .type(type)
                        .content(Map.of(
                                "value", status.getValue(),
                                "notes", status.getNotes(),
                                "lastUpdate", status.getLastUpdate(),
                                "owner", status.getOwner(),
                                "priority", status.getPriority(),
                                "visibility", status.getVisibility()))
                        .build());
        assertEquals(todoStatusWithObjectMap, todoStatus);
    }

    private TodoStatus getTestTodoStatus(
            final Person.Id userId, final DataId dataId, final String type, final Object content) {
        final TodoItem todo = TodoItem.builder()
                .tripId(RandomData.genAlpha(7))
                .description(RandomData.genAlpha(8))
                .dataId(dataId)
                .build();
        final PersonDataValue pdv = PersonDataValue.builder()
                .userId(userId)
                .dataId(dataId)
                .type(type)
                .content(content)
                .build();
        return new TodoStatus(todo, pdv);
    }

    @Test
    public void testEquals() {
        EqualsVerifier.forClass(TodoStatus.class).verify();
    }

    @Test
    public void canGetTripId() {
        final String tripId = RandomData.genAlpha(13);
        final DataId dataId = DataId.newInstance();
        final TodoItem todo = Mockito.mock(TodoItem.class);
        final PersonDataValue status = Mockito.mock(PersonDataValue.class);
        Mockito.when(todo.getTripId()).thenReturn(tripId);
        Mockito.when(todo.getDataId()).thenReturn(dataId);
        Mockito.when(status.getDataId()).thenReturn(dataId);
        Mockito.when(status.getType()).thenReturn(TodoStatus.TODO_PERSON_DATA_VALUE_TYPE);
        Mockito.when(status.getContent()).thenReturn(Status.builder().build());
        final TodoStatus todoStatus = new TodoStatus(todo, status);
        assertEquals(todoStatus.getTripId(), tripId);
    }

    @Test
    public void canGetDataId() {
        final DataId dataId = DataId.newInstance();
        final TodoStatus todoStatus = getTestTodoStatus(
                Person.Id.from(RandomData.genAlpha(15)),
                dataId,
                TodoStatus.TODO_PERSON_DATA_VALUE_TYPE,
                Status.builder().build());
        assertEquals(todoStatus.getDataId(), dataId);
    }

    @Test
    public void canGetUserId() {
        final Person.Id userId = Person.Id.from(RandomData.genAlpha(13));
        final TodoStatus todoStatus = getTestTodoStatus(
                userId,
                DataId.newInstance(),
                TodoStatus.TODO_PERSON_DATA_VALUE_TYPE,
                Status.builder().build());
        assertEquals(todoStatus.getUserId(), userId);
    }

    @Test
    public void canGetSetStatus() throws Exception {
        final Status status = Status.builder().value(Status.StatusValue.IN_PROGRESS).build();
        final TodoStatus todoStatus = getTestTodoStatus(
                Person.Id.from(RandomData.genAlpha(15)),
                DataId.newInstance(),
                TodoStatus.TODO_PERSON_DATA_VALUE_TYPE,
                status);
        assertEquals(todoStatus.getStatus(), status);
        final LocalDateTime lastUpdate = todoStatus.getLastUpdate();
        assertNotNull(lastUpdate);
        Thread.sleep(1);
        todoStatus.setStatusValue(Status.StatusValue.DONE);
        final LocalDateTime newUpdate = todoStatus.getLastUpdate();
        assertNotEquals(newUpdate, lastUpdate);
        assertEquals(todoStatus.getStatus().getValue(), Status.StatusValue.DONE);
        assertEquals(todoStatus.getStatusValue(), Status.StatusValue.DONE);
    }

    @Test
    public void canGetSetDescription() {
        final TodoStatus todoStatus = getTestTodoStatus(
                Person.Id.from(RandomData.genAlpha(15)),
                DataId.newInstance(),
                TodoStatus.TODO_PERSON_DATA_VALUE_TYPE,
                Status.builder().build());
        final String desc = RandomData.genAlpha(88);
        todoStatus.setDescription(desc);
        assertEquals(todoStatus.getDescription(), desc);
    }

    @Test
    public void canGetSetMoreDetails() {
        final TodoStatus todoStatus = getTestTodoStatus(
                Person.Id.from(RandomData.genAlpha(15)),
                DataId.newInstance(),
                TodoStatus.TODO_PERSON_DATA_VALUE_TYPE,
                Status.builder().build());
        assertNull(todoStatus.getMoreDetails());
        final String details = RandomData.genAlpha(188);
        todoStatus.setMoreDetails(details);
        assertEquals(todoStatus.getMoreDetails(), details);
    }

    @Test
    public void canGetCreated() {
        final TodoStatus todoStatus = getTestTodoStatus(
                Person.Id.from(RandomData.genAlpha(15)),
                DataId.newInstance(),
                TodoStatus.TODO_PERSON_DATA_VALUE_TYPE,
                Status.builder().build());
        assertNotNull(todoStatus.getCreated());
    }

    @Test
    public void canGetSetNotes() {
        final TodoStatus todoStatus = getTestTodoStatus(
                Person.Id.from(RandomData.genAlpha(15)),
                DataId.newInstance(),
                TodoStatus.TODO_PERSON_DATA_VALUE_TYPE,
                Status.builder().build());
        assertNull(todoStatus.getNotes());
        final String notes = RandomData.genAlpha(87);
        todoStatus.setNotes(notes);
        assertEquals(todoStatus.getNotes(), notes);
    }

    @Test
    public void canGetSetOwner() {
        final TodoStatus todoStatus = getTestTodoStatus(
                Person.Id.from(RandomData.genAlpha(15)),
                DataId.newInstance(),
                TodoStatus.TODO_PERSON_DATA_VALUE_TYPE,
                Status.builder().build());
        assertNull(todoStatus.getOwner());
        final Person.Id pid = Person.Id.newInstance();
        todoStatus.setOwner(pid);
        assertEquals(todoStatus.getOwner(), pid);
    }

    @Test
    public void canGetSetPriority() {
        final TodoStatus todoStatus = getTestTodoStatus(
                Person.Id.from(RandomData.genAlpha(15)),
                DataId.newInstance(),
                TodoStatus.TODO_PERSON_DATA_VALUE_TYPE,
                Status.builder().build());
        assertEquals(todoStatus.getPriority(), Status.Priority.NORMAL);
        todoStatus.setPriority(Status.Priority.HIGH);
        assertEquals(todoStatus.getPriority(), Status.Priority.HIGH);
    }

    @Test
    public void canGetSetVisibility() {
        final TodoStatus todoStatus = getTestTodoStatus(
                Person.Id.from(RandomData.genAlpha(15)),
                DataId.newInstance(),
                TodoStatus.TODO_PERSON_DATA_VALUE_TYPE,
                Status.builder().build());
        assertEquals(todoStatus.getVisibility(), Status.Visibility.USER);
        todoStatus.setVisibility(Status.Visibility.ADMIN);
        assertEquals(todoStatus.getVisibility(), Status.Visibility.ADMIN);
    }
}