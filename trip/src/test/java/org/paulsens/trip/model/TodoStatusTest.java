package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class TodoStatusTest {
    final ObjectMapper mapper = DynamoUtils.getInstance().getMapper();

    @Test(expectedExceptions = { IllegalArgumentException.class },
            expectedExceptionsMessageRegExp = ".*TODO_PERSON_DATA_VALUE_TYPE.*")
    public void ensureTypeIsChecked() {
        final Person.Id userId = Person.Id.from(RandomData.genAlpha(15));
        final PersonDataValue.Id dataId = PersonDataValue.Id.newInstance();
        final String type = RandomData.genAlpha(13);    // Random type will fail! Must be "todo"
        getTestTodoStatus(userId, dataId, type, TodoStatus.Status.builder().build());
    }

    @Test(expectedExceptions = { IllegalStateException.class },
            expectedExceptionsMessageRegExp = ".*do not have matching Id.*")
    public void ensureDataIdsMatch() {
        final TodoItem todo = TodoItem.builder()
                .tripId(RandomData.genAlpha(3))
                .dataId(PersonDataValue.Id.newInstance())
                .description(RandomData.genAlpha(31))
                .moreDetails(RandomData.genAlpha(19))
                .build();
        final PersonDataValue pdv = PersonDataValue.builder()
                .userId(Person.Id.from(RandomData.genAlpha(15)))
                .dataId(PersonDataValue.Id.newInstance()) // Doesn't match above... should blow up
                .type(TodoStatus.TODO_PERSON_DATA_VALUE_TYPE)
                .content(RandomData.genAlpha(22))
                .build();
        new TodoStatus(todo, pdv); // Should now blow up
    }

    @Test
    public void StatusCanBeStringOrObjectOrMap() throws IOException {
        final Person.Id userId = Person.Id.from(RandomData.genAlpha(15));
        final PersonDataValue.Id dataId = PersonDataValue.Id.newInstance();
        final String type = TodoStatus.TODO_PERSON_DATA_VALUE_TYPE;
        final TodoStatus.Status status = TodoStatus.Status.builder()
                .value(TodoStatus.StatusValue.DONE)
                .notes(RandomData.genAlpha(131))
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
        final TodoStatus todoStatusWithMap = new TodoStatus(todoStatus.getTodoItem(),
                PersonDataValue.builder()
                        .userId(userId)
                        .dataId(dataId)
                        .type(type)
                        .content(Map.of("value", "" + status.getValue(), "notes", status.getNotes()))
                        .build());
        assertEquals(todoStatusWithMap, todoStatus);
    }

    private TodoStatus getTestTodoStatus(
            final Person.Id userId, final PersonDataValue.Id dataId, final String type, final Object content) {
        final TodoItem todo = TodoItem.builder()
                .tripId(RandomData.genAlpha(7))
                .description(RandomData.genAlpha(8))
                .moreDetails(RandomData.genAlpha(9))
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
    public void testTestEquals() {
        //EqualsVerifier.forClass(TodoStatus.class).suppress(Warning.NONFINAL_FIELDS).verify();
        EqualsVerifier.forClass(TodoStatus.class).verify();
    }
}