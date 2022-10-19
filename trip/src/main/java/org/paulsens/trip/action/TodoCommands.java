package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.Status;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.TodoStatus;
import org.paulsens.trip.model.Trip;
import org.primefaces.model.DashboardModel;
import org.primefaces.model.DefaultDashboardColumn;
import org.primefaces.model.DefaultDashboardModel;

@Slf4j
@Named("todo")
@ApplicationScoped
public class TodoCommands {
    static final String WIDGET_PREFIX = "status-";

    @Getter(lazy = true, value = AccessLevel.PRIVATE)
    private final TripCommands tripCommands = findTripCommands();

    public TodoItem createTodo(final String tripId) {
        return TodoItem.builder()
                .tripId(tripId)
                .dataId(DataId.newInstance())
                .description("")
                .build();
    }

// TodoStatus Fields:
//      tripId               = String (read only)
//      userId               = Person.Id (read only)
//      dataId               = DataId (read only)
//      created              = LocalDateTime (read only)
//      lastUpdate           = LocalDateTime (read only)
//
//      owner                = Person.Id
//      visibility           = Status.Visibility.ADMIN, USER
//    + priority             = Status.Priority.OPTIONAL, LOW, NORMAL, HIGH, CRITICAL
//    + statusValue          = Status.StatusValue.DONE, TODO, IN_PROGRESS
//    + description          = String
//    + moreDetails          = String
//    + notes                = String

    public boolean saveTodo(final TodoItem todo) {
        boolean result;
        try {
            result = DynamoUtils.getInstance()
                    .saveTodo(todo)
                    .orTimeout(5_000L, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                                "Error saving todo '" + todo.getDescription() + "': " + todo.getTripId(),
                                ex.getMessage());
                        log.error("Error while saving todo: ", ex);
                        return false;
                    }).join();
        } catch (final IOException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Unable to save todo '" + todo.getDescription() + "': " + todo.getTripId(), ex.getMessage());
            log.warn("Error while saving todo: ", ex);
            result = false;
        }
        return result;
    }

    public List<TodoItem> getTodos(final String tripId) {
        return DynamoUtils.getInstance()
                .getTodoItems(tripId)
                .exceptionally(ex -> {
                    log.error("Failed to get todos for trip '" + tripId + "'!", ex);
                    return Collections.emptyList();
                }).join();
    }

    public List<TodoStatus> getTodosForUser(final String tripId, final Person.Id userId) {
        return getTodos(tripId).stream()
                .map(todo -> getTodoStatus(todo, userId))
                .filter(Objects::nonNull)
                .toList();
    }

    public TodoItem getTodo(final String tripId, final DataId dataId) {
        if (tripId == null) {
            log.error("getTodo() called with null tripId.");
            return null;
        }
        if (dataId == null) {
            log.error("getTodo() called with null PersonDataValue ID.");
            return null;
        }
        return DynamoUtils.getInstance()
                .getTodoItem(tripId, dataId)
                .exceptionally(ex -> {
                    log.error("Failed to get trip '" + tripId + "' todo for '" + dataId.getValue() + "'!", ex);
                    return Optional.empty();
                }).join().orElse(null);
    }

    public TodoStatus getOrCreateTodoStatus(final TodoItem todo, final Person.Id userId) {
        final TodoStatus result = getTodoStatus(todo, userId);
        return (result == null) ? new TodoStatus(todo, userId) : result;
    }

    public TodoStatus getTodoStatus(final TodoItem todo, final Person.Id userId) {
        final PersonDataValue pdv = PersonDataValueCommands.getPersonDataValue(userId, todo.getDataId());
        return (pdv == null) ? null : new TodoStatus(todo, pdv);
    }

    public TodoStatus getTodoStatusUsingWidgetId(final String trip, final Person.Id userId, final String widgetId) {
        final TodoItem todo = getTodo(trip, DataId.from(widgetId.substring(WIDGET_PREFIX.length())));
        return getTodoStatus(todo, userId);
    }

    public boolean saveTodoStatus(final TodoStatus todoStatus) {
        return PersonDataValueCommands.savePersonDataValue(todoStatus.getPersonDataValue());
    }

    public DashboardModel getTodoDashboard(final String tripId, final Person.Id userId, final boolean showDone) {
        final DashboardModel model = new DefaultDashboardModel();
        model.addColumn(new DefaultDashboardColumn()); // To do
        model.addColumn(new DefaultDashboardColumn()); // In progress
        if (showDone) {
            model.addColumn(new DefaultDashboardColumn()); // Done
        }
        getTodos(tripId).stream()
                .map(todo -> getTodoStatus(todo, userId))
                .filter(Objects::nonNull)
                .forEach(todoStatus -> addTodoToDash(model, todoStatus, showDone));
        return model;
    }

    public boolean isAssignedTodo(final TodoItem todo, final Person.Id userId) {
        return PersonDataValueCommands.getPersonDataValue(userId, todo.getDataId()) != null;
    }

    /**
     * This effectively gets all the people assigned to a {@link TodoItem} plus the status.
     * @param todo      The {@link TodoItem} to search.
     * @return  All the {@link TodoStatus}es for the given {@link TodoItem}.
     */
    public List<TodoStatus> getTodoStatusesForTodo(final TodoItem todo) {
        if (todo == null) {
            log.warn("getPeopleForTodo invoked with null todo!");
            return List.of();
        }
        final Trip trip = getTripCommands().getTrip(todo.getTripId());
        if (trip == null) {
            log.warn("Trip not found in getPeopleForTodo!");
            return List.of();
        }
        return trip.getPeople().stream()
                .map(pid -> getTodoStatus(todo, pid))
                .filter(Objects::nonNull)
                .toList();
    }

    // Adds the dashboard model assuming 2 or 3 columns: "to do", "in progress", and optional "done". It adds these
    // under the name defined by the "dataId"
    private void addTodoToDash(final DashboardModel dashModel, final TodoStatus status, final boolean showDone) {
        final int column;
        final Status.StatusValue statusValue = status.getStatusValue();
        if (statusValue == Status.StatusValue.TODO) {
            column = 0;
        } else if (statusValue == Status.StatusValue.IN_PROGRESS) {
            column = 1;
        } else if (statusValue == Status.StatusValue.DONE) {
            column = showDone ? 2 : -1;
        } else {
            log.warn("Unknown Todo Status: " + statusValue);
            column = -1;
        }
        if (column != -1) {
            dashModel.getColumn(column).addWidget(WIDGET_PREFIX + status.getDataId().getValue());
        }
    }

    private TripCommands findTripCommands() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null) {
            return (TripCommands) FacesContext.getCurrentInstance().getExternalContext()
                    .getApplicationMap().get("trip");
        }
        return new TripCommands();
    }
}
