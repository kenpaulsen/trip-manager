package org.paulsens.trip.model;

import java.io.IOException;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import org.paulsens.trip.dynamo.DAO;

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

    public String getTripId() {
        return getTodoItem().getTripId();
    }

    public DataId getDataId() {
        return getTodoItem().getDataId();
    }

    /**
     * The user is the person this {@link TodoItem} applies to. This is different from the owner, which is responsible
     * for managing the {@code TodoItem}. For example, an admin might be the owner, but a normal user might be the
     * user.
     *
     * @return The person this {@link TodoItem} applies to.
     */
    public Person.Id getUserId() {
        return getPersonDataValue().getUserId();
    }

    public LocalDateTime getCreated() {
        return getTodoItem().getCreated();
    }

    public String getDescription() {
        return getTodoItem().getDescription();
    }

    public void setDescription(final String desc) {
        getTodoItem().setDescription(desc);
    }

    public String getMoreDetails() {
        return getTodoItem().getMoreDetails();
    }

    public void setMoreDetails(final String details) {
        getTodoItem().setMoreDetails(details);
    }

    public Status.StatusValue getStatusValue() {
        return getStatus().getValue();
    }

    public void setStatusValue(final Object statusValue) {
        getStatus().setValue(statusValue);
    }

    public LocalDateTime getLastUpdate() {
        return getStatus().getLastUpdate();
    }

    public String getNotes() {
        return getStatus().getNotes();
    }

    public void setNotes(final String notes) {
        getStatus().setNotes(notes);
    }

    /**
     * This returns the person that can update this {@link TodoItem}. This is not necessarily the same as the user
     * this that this item is about.
     *
     * @return The id of the person responsible for updating this {@link TodoItem}.
     */
    public Person.Id getOwner() {
        return getStatus().getOwner();
    }

    public void setOwner(final Person.Id pid) {
        getStatus().setOwner(pid);
    }

    public Status.Priority getPriority() {
        return getStatus().getPriority();
    }

    public void setPriority(final Status.Priority priority) {
        getStatus().setPriority(priority);
    }

    public Status.Visibility getVisibility() {
        return getStatus().getVisibility();
    }

    public void setVisibility(final Status.Visibility visibility) {
        getStatus().setVisibility(visibility);
    }

    public TodoItem getTodoItem() {
        return todoItem;
    }

    public Status getStatus() {
        return getPersonDataValue().castContent();
    }

    public PersonDataValue getPersonDataValue() {
        return pdv;
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
            return DAO.getInstance().getMapper().readValue(statusJson, Status.class);
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
