package org.paulsens.trip.model;

import java.io.IOException;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import org.paulsens.trip.dynamo.DynamoUtils;

@EqualsAndHashCode
public final class TodoStatus implements Serializable {
    public static final String TODO_PERSON_DATA_VALUE_TYPE = "todo";

    // The todoItem is stored for convenience, it is not altered
    private final TodoItem todoItem;
    // The PersonDataValue is managed by this class and this class provides manipulation methods
    private final PersonDataValue pdv;

    public TodoStatus(final TodoItem todoItem, final PersonDataValue pdv) {
        if (!pdv.getType().equals(TODO_PERSON_DATA_VALUE_TYPE)) {
            throw new IllegalArgumentException("PersonDataValue must be of type TODO_PERSON_DATA_VALUE_TYPE!");
        }
        if (!todoItem.getDataId().equals(pdv.getDataId())) {
            throw new IllegalStateException("TodoItem and PersonDataValue given do not have matching Id!");
        }
        this.todoItem = todoItem;
        this.pdv = pdv;
        final Object content = pdv.getContent();
        if (!(content instanceof Status)) {
            // Need to convert content for easier use
            if (content instanceof String) {
                pdv.setContent(extractSerializedStatus(pdv.castContent()));
            } else if (content instanceof Map) {
                pdv.setContent(extractMapStatus(pdv.castContent()));
            } else {
                throw new IllegalStateException("Unknown type of content for todo: " + content.getClass().getName());
            }
        }
    }

    public TodoStatus(@NonNull final TodoItem todoItem, @NonNull final Person.Id pid) {
        this(todoItem, createNewTodoPDV(todoItem, pid));
    }

    public Status getStatus() {
        return pdv.castContent();
    }

    public PersonDataValue getPersonDataValue() {
        return pdv;
    }

    public TodoItem getTodoItem() {
        return todoItem;
    }

    private static PersonDataValue createNewTodoPDV(final TodoItem todo, final Person.Id userId) {
        return PersonDataValue.builder()
                .type(TODO_PERSON_DATA_VALUE_TYPE)
                .dataId(todo.getDataId())
                .userId(userId)
                .content(Status.builder().build())
                .build();
    }

    private Status extractSerializedStatus(final String statusJson) {
        try {
            return DynamoUtils.getInstance().getMapper().readValue(statusJson, Status.class);
        } catch (final IOException ex) {
            throw new IllegalStateException("Invalid JSON: " + statusJson);
        }
    }

    private Status extractMapStatus(final Map<String, Object> statusMap) {
        final Object value = statusMap.get("value");
        if (value == null) {
            throw new IllegalStateException("Status value cannot be null!");
        }
        final String notes = (String) statusMap.get("notes");
        final Object priority = statusMap.get("priority");
        final Object visibility = statusMap.get("visibility");
        final Object owner = statusMap.get("owner");
        final Object lastUpdate = statusMap.get("lastUpdate");
        return Status.builder()
                .value((value instanceof Status.StatusValue) ?
                        (Status.StatusValue) value : Status.StatusValue.valueOf(String.valueOf(value)))
                .priority((priority instanceof Status.Priority) ?
                        (Status.Priority) priority : Status.Priority.valueOf(String.valueOf(priority)))
                .visibility((visibility instanceof Status.Visibility) ?
                        (Status.Visibility) visibility : Status.Visibility.valueOf(String.valueOf(visibility)))
                .owner((owner instanceof Person.Id) ? (Person.Id) owner : Person.Id.from(String.valueOf(owner)))
                .lastUpdate((lastUpdate instanceof LocalDateTime) ?
                        (LocalDateTime) lastUpdate : LocalDateTime.parse(String.valueOf(lastUpdate)))
                .notes(notes)
                .build();
    }
}
