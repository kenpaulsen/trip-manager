package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.TodoStatus;

@Slf4j
@Named("todo")
@ApplicationScoped
public class TodoCommands {
    // FIXME: This was copied from RegistrationCommands... need to think through what commands we need and write them
    // FIXME: Need to write test cases that do the use-cases for todo's
    public TodoItem createTodo(final String tripId) {
        return TodoItem.builder()
                .tripId(tripId)
                .dataId(PersonDataValue.Id.newInstance())
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

    public TodoItem getTodo(final String tripId, final PersonDataValue.Id pdv) {
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
}
