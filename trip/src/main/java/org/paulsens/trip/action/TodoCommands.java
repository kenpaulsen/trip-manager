package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.Status;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.TodoStatus;
import org.primefaces.model.DashboardModel;
import org.primefaces.model.DefaultDashboardColumn;
import org.primefaces.model.DefaultDashboardModel;

@Slf4j
@Named("todo")
@ApplicationScoped
public class TodoCommands {
    // FIXME: This was copied from RegistrationCommands... need to think through what commands we need and write them
    // FIXME: Need to write test cases that do the use-cases for todo's
    public TodoItem createTodo(final String tripId, final DataId dataId) {
        return TodoItem.builder()
                .tripId(tripId)
                .dataId(dataId)
                .description("")
                .build();
    }

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
                .filter(todo -> isAssignedTodo(todo, userId))
                .map(todo -> getTodoStatus(todo, userId)).toList();
    }

    public TodoItem getTodo(final String tripId, final DataId pdv) {
        if (tripId == null) {
            log.error("getTodo() called with null tripId.");
            return null;
        }
        if (pdv == null) {
            log.error("getTodo() called with null PersonDataValue ID.");
            return null;
        }
        return DynamoUtils.getInstance()
                .getTodoItem(tripId, pdv)
                .exceptionally(ex -> {
                    log.error("Failed to get trip '" + tripId + "' todo for '" + pdv.getValue() + "'!", ex);
                    return Optional.empty();
                }).join().orElse(null);
    }

    public TodoStatus getTodoStatus(final TodoItem todo, final Person.Id userId) {
        final PersonDataValue pdv = PersonDataValueCommands.getPersonDataValue(userId, todo.getDataId());
        return (pdv == null) ? new TodoStatus(todo, userId) : new TodoStatus(todo, pdv);
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
        getTodos(tripId).stream().filter(todo -> isAssignedTodo(todo, userId))
                .forEach(todo -> addTodoToDash(model, getTodoStatus(todo, userId), showDone));
        return model;
    }

    public boolean isAssignedTodo(final TodoItem todo, final Person.Id userId) {
        return PersonDataValueCommands.getPersonDataValue(userId, todo.getDataId()) != null;
    }

    // Adds the dashboard model assuming 2 or 3 columns: "to do", "in progress", and optional "done". It adds these
    // under the name defined by the "dataId"
    private void addTodoToDash(final DashboardModel dashModel, final TodoStatus status, final boolean showDone) {
        final int column;
        final Status.StatusValue statusValue = status.getStatus().getValue();
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
            dashModel.getColumn(column).addWidget("status-" + status.getTodoItem().getDataId().getValue());
        }
    }
}
