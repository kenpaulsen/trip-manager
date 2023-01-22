package org.paulsens.trip.action;

import com.sun.jsft.util.ELUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DAO;
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

// FIXME: Create an admin feature that lists todo's by:
//          - All todo's for a trip (filter by person?) (maybe page loops through todoItem's lists description w/ 3 columns for people by status)
// FIXME: Create a person page that lists all their todo's regardless of trip
// FIXME: Add "public" tasks where I can show my tasks so others know what I'm doing
// FIXME: Add deadlines
// FIXME: When a user tries to move one that only and admin can edit, provide a facesmessage explaining why it failed

    public boolean saveTodo(final TodoItem todo) {
        boolean result;
        try {
            result = DAO.getInstance()
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
        return DAO.getInstance()
                .getTodoItems(tripId)
                .exceptionally(ex -> {
                    log.error("Failed to get todos for trip '" + tripId + "'!", ex);
                    return Collections.emptyList();
                }).join();
    }

    public List<TodoStatus> getTodosForUser(final String tripId, final Person.Id userId, final boolean isAdmin) {
        return getTodos(tripId).stream()
                .map(todo -> getTodoStatus(todo, userId))
                .filter(Objects::nonNull)
                .filter(status -> isAdmin || (status.getVisibility() == Status.Visibility.USER))
                .toList();
    }

    public DataId dataIdFrom(final String id) {
        return DataId.from(id);
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
        return DAO.getInstance()
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

    public String getWidgetId(final DataId dataId, final Person.Id pid) {
        if (dataId == null) {
            log.warn("DataId is null, cannot create widget id!");
            return null;
        }
        if (pid == null) {
            log.warn("Person id is null, cannot create widget id!");
            return null;
        }
        return WIDGET_PREFIX + dataId.getValue() + '-' + pid.getValue();
    }

    public TodoStatus getTodoStatusUsingWidgetId(final String trip, final String widgetId) {
        final TodoItem todo = getTodo(trip, dataIdFromWidgetId(widgetId));
        return getTodoStatus(todo, personIdFromWidgetId(widgetId));
    }

    private DataId dataIdFromWidgetId(final String widgetId) {
        final String dataIdAndUserId = widgetId.substring(WIDGET_PREFIX.length());
        return DataId.from(dataIdAndUserId.substring(0, dataIdAndUserId.indexOf('-')));
    }

    private Person.Id personIdFromWidgetId(final String widgetId) {
        return Person.Id.from(widgetId.substring(widgetId.indexOf('-', WIDGET_PREFIX.length() + 1) + 1));
    }

    public boolean saveTodoStatus(final TodoStatus todoStatus) {
        return PersonDataValueCommands.savePersonDataValue(todoStatus.getPersonDataValue());
    }

    /**
     * This method will create or update todo metadata and apply common attributes to a list of users. If any of the
     * users have custom values already set, those values may be overwritten because we don't know any better.
     */
    public void saveTodoAndStatuses(
            final Person.Id[] people,
            final DataId dataId,
            final Trip theTrip,
            final String todoOwner,
            final String desc,
            final String moreDetails,
            final Status.Visibility todoVis,
            final Status.Priority todoPriority) {
        if (!checkRequiredInputs(people, desc)) {
            // Abort
            return;
        }
        // Todo info
        final TodoItem theTodo;
        if (dataId == null) {
            theTodo = createTodo(theTrip.getId());
        } else {
            theTodo = getTodo(theTrip.getId(), dataId);
        }
        theTodo.setDescription(desc);
        theTodo.setMoreDetails(moreDetails);
        saveTodo(theTodo);

        // Set Status info for all the people now assigned this task...
        final HashSet<Person.Id> assignedPeople = new HashSet<>();
        for (Person.Id pid : people) {
            final TodoStatus userTodoStatus = getOrCreateTodoStatus(theTodo, pid);
            userTodoStatus.setOwner(getTodoOwner(todoOwner, userTodoStatus, pid));
            userTodoStatus.setVisibility(todoVis);
            userTodoStatus.setPriority(todoPriority);
            saveTodoStatus(userTodoStatus);
            assignedPeople.add(pid);
        }
        // Now remove anyone that was prev assigned, but is no longer assigned...
        for (final Person.Id pid : theTrip.getPeople()) {
            if (assignedPeople.contains(pid)) {
                continue;
            }
            // Make sure they don't have this assigned anymore..
            final TodoStatus userTodoStatus = getTodoStatus(theTodo, pid);
            if ((userTodoStatus != null) && (userTodoStatus.getVisibility() != Status.Visibility.DELETED)) {
                // We will *ONLY* update to visibility == deleted
                userTodoStatus.setVisibility(Status.Visibility.DELETED);
                saveTodoStatus(userTodoStatus);
            }
        }
        setRunScript("newTodoError = false;");
        final String msg = "'" + desc + "' Todo Created!";
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_INFO, msg, msg);
    }

    private boolean checkRequiredInputs(final Person.Id[] people, final String desc) {
        boolean goodRequest = true;
        if (people == null || people.length == 0) {
            final String selPeopleId = (String) getRequestAttribute("selPeopleId").orElse(null);
            final String msg = "You must select at least 1 person!";
            TripUtilCommands.addMessage(selPeopleId, new FacesMessage(FacesMessage.SEVERITY_ERROR, msg, msg));
            setRunScript("newTodoError = true;");
            goodRequest = false;
        }
        if (desc == null || desc.isBlank()) {
            final String descClientId = (String) getRequestAttribute("newTodoDescClientId").orElse(null);
            final String msg = "You must provide a description!";
            TripUtilCommands.addMessage(descClientId, new FacesMessage(FacesMessage.SEVERITY_ERROR, msg, msg));
            setRunScript("newTodoError = true;");
            goodRequest = false;
        }
        return goodRequest;
    }

    public boolean userCanEdit(final TodoStatus todoStatus, final Person.Id pid, final boolean isAdmin) {
        final Person.Id owner = todoStatus.getOwner();
        return isAdmin || ((owner != null) && owner.equals(pid));
    }

    public DashboardModel getTodoDashboard(final List<TodoStatus> todos, final boolean showDone) {
        final DashboardModel model = new DefaultDashboardModel();
        model.addColumn(new DefaultDashboardColumn()); // To do
        model.addColumn(new DefaultDashboardColumn()); // In progress
        if (showDone) {
            model.addColumn(new DefaultDashboardColumn()); // Done
        }
        todos.forEach(todoStatus -> addTodoToDash(model, todoStatus, showDone));
        return model;
    }

    public boolean isAssignedTodo(final TodoItem todo, final Person.Id userId) {
        return PersonDataValueCommands.getPersonDataValue(userId, todo.getDataId()) != null;
    }

    public String statusToTagSeverity(final TodoStatus status) {
        return switch (status.getStatusValue()) {
            case TODO -> "warning";
            case IN_PROGRESS -> "info";
            case DONE -> "success";
        };
    }

    public String priorityToTagSeverity(final TodoStatus status) {
        return switch (status.getPriority()) {
            case OPTIONAL -> "success";
            case LOW -> "info";
            case NORMAL -> "primary";
            case HIGH -> "warning";
            case CRITICAL -> "danger";
        };
    }

    /**
     * This effectively gets all the people assigned to a {@link TodoItem} plus the status.
     * @param todo      The {@link TodoItem} to search.
     * @return  All the {@link TodoStatus}es for the given {@link TodoItem}.
     */
    public List<TodoStatus> getTodoStatusesForTodo(final TodoItem todo) {
        if (todo == null) {
            log.warn("getTodoStatusesForTodo invoked with null todo!");
            return List.of();
        }
        final Trip trip = getTripCommands().getTrip(todo.getTripId());
        if (trip == null) {
            log.warn("Trip not found in getTodoStatusesForTodo!");
            return List.of();
        }
        return trip.getPeople().stream()
                .map(pid -> getTodoStatus(todo, pid))
                .filter(Objects::nonNull)
                .filter(status -> status.getVisibility() != Status.Visibility.DELETED)
                .toList();
    }

    public List<Person.Id> getPeopleForTodoItem(final TodoItem todo) {
        return getTodoStatusesForTodo(todo).stream().map(status -> status.getPersonDataValue().getUserId()).toList();
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
            dashModel.getColumn(column).addWidget(getWidgetId(status.getDataId(), status.getUserId()));
        }
    }

    private TripCommands findTripCommands() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null) {
            return (TripCommands) ELUtil.getInstance().eval("#{trip}");
        }
        return new TripCommands();
    }

    /**
     * This method sets a request attribute that contains a JS script to be executed on page rendering.
     * @param script    The script to execute.
     */
    public void setRunScript(final String script) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null) {
            ctx.getExternalContext().getRequestMap().put("runScript", script);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Optional<T> getRequestAttribute(final String attName) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        final T result = (ctx == null) ? null : (T) ctx.getExternalContext().getRequestMap().get(attName);
        return Optional.ofNullable(result);
    }

    private Person.Id getTodoOwner(final String todoOwner, final TodoStatus status, final Person.Id currUser) {
        final Person.Id result;
        if (todoOwner == null || "ADMIN".equals(todoOwner.trim())) {
            result = null;
        } else if ("USER".equals(todoOwner.trim())) {
            // If the owner is already set, leave it set
            result = (status.getOwner() != null) ? status.getOwner() : currUser;
        } else {
            result = Person.Id.from(todoOwner.trim());
        }
        return result;
    }
}
