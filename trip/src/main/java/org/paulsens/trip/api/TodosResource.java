package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.TodoCommands;
import org.paulsens.trip.api.dto.TodoDto;
import org.paulsens.trip.api.dto.TodoStatusDto;
import org.paulsens.trip.api.mapper.TodoMapper;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Status;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.TodoStatus;

/**
 * Trip todos and each traveller's progress against them.
 *
 * <p>Two different things share this path and it is worth keeping them apart. A {@code TodoItem} is the task the
 * trip defined ("send us your passport scan"); a {@code TodoStatus} is one person's answer to it. Staff edit the
 * former, travellers edit the latter, and the authorization differs accordingly.
 *
 * <p>Three circles, matching the to-do page ({@code trip/todo.xhtml}). READERS of the trip's task list are
 * whoever may read the trip ({@code canReadTrip}: the roster, its staff, a manager of someone on it). Read
 * STAFF, who see everyone's rows and the ADMIN-visibility ones, are the trip's managers and Viewers. WRITE
 * staff are its managers alone: Viewer ({@code tripView}) is a read role, and it used to create and rewrite
 * the trip's tasks from here. A traveler changes only a status that already exists, is theirs to see, and
 * ({@code TodoCommands.userCanEdit}) is theirs to move.
 *
 * <p>Nothing PrimeFaces-shaped is exposed: the dashboard model, the {@code status-<dataId>-<personId>} widget ids
 * and the tag-severity helpers are all presentation for one specific page and mean nothing to a mobile client.
 */
@Slf4j
@Path("trips/{tripId}/todos")
@TripApi
public class TodosResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.TODOS_V1;
    private static final String NOT_A_MEMBER = "Not a member of this trip.";
    private static final String NOT_THEIR_STATUS = "Not permitted to edit this todo status.";

    @Override
    protected String versionedType() {
        return V1;
    }

    /**
     * The todos defined on this trip. Any member may see what is being asked of them -- and only a member
     * (or the trip's staff): the list used to answer anybody signed in, for any organization's trip.
     */
    @GET
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response list(@PathParam("tripId") final String tripId) {
        if (!canReadTrip(requireTrip(tripId))) {
            return error(403, ApiErrors.FORBIDDEN, NOT_A_MEMBER);
        }
        return ok(Beans.get(TodoCommands.class).getTodos(tripId).stream()
                .map(TodoMapper.INSTANCE::toDto)
                .toList());
    }

    @GET
    @Path("{dataId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response get(@PathParam("tripId") final String tripId, @PathParam("dataId") final String dataId) {
        if (!canReadTrip(requireTrip(tripId))) {
            return error(403, ApiErrors.FORBIDDEN, NOT_A_MEMBER);
        }
        final TodoItem todo = Beans.get(TodoCommands.class).getTodo(tripId, DataId.from(dataId));
        if (todo == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such todo.");
        }
        return ok(TodoMapper.INSTANCE.toDto(todo));
    }

    /**
     * Somebody's todo list for this trip.
     *
     * <p>The {@code isAdmin} flag passed down decides whether ADMIN-visibility items are included, so it is
     * derived from the caller's trip role rather than taken from the request.
     */
    @GET
    @Path("statuses/{personId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response statuses(
            @PathParam("tripId") final String tripId, @PathParam("personId") final String personIdParam) {
        final Person.Id subject = Person.Id.from(personIdParam);
        if (!canSee(tripId, subject)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to read this person's todos.");
        }
        final boolean staff = isTripStaff(tripId);
        final List<TodoStatus> found = Beans.get(TodoCommands.class).getTodosForUser(tripId, subject, staff);
        return ok(found.stream().map(status -> dto(status, subject, staff)).toList());
    }

    /** Shorthand for the caller's own list, so a client does not have to know its own id to render a home screen. */
    @GET
    @Path("statuses")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response myStatuses(@PathParam("tripId") final String tripId) {
        final Person.Id me = personId();
        final boolean staff = isTripStaff(tripId);
        return ok(Beans.get(TodoCommands.class).getTodosForUser(tripId, me, staff).stream()
                .map(status -> dto(status, me, staff))
                .toList());
    }

    /** Creates a todo on this trip. Trip managers only -- a traveler does not invent tasks for the group. */
    @POST
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response create(
            @PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final TodoDto body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!isWriteStaff(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip manager required.");
        }
        requireTrip(tripId);
        final TodoCommands todos = Beans.get(TodoCommands.class);
        final TodoItem todo = todos.createTodo(tripId);
        apply(body, todo);
        if (!todos.saveTodo(todo)) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the todo.");
        }
        return ok(TodoMapper.INSTANCE.toDto(todo));
    }

    @PUT
    @Path("{dataId}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response update(
            @PathParam("tripId") final String tripId,
            @PathParam("dataId") final String dataId,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final TodoDto body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!isWriteStaff(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip manager required.");
        }
        final TodoCommands todos = Beans.get(TodoCommands.class);
        // Load and save the same object; a DAO read hands back a copy.
        final TodoItem todo = todos.getTodo(tripId, DataId.from(dataId));
        if (todo == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such todo.");
        }
        apply(body, todo);
        if (!todos.saveTodo(todo)) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the todo.");
        }
        return ok(TodoMapper.INSTANCE.toDto(todo));
    }

    /**
     * Updates one person's progress against one todo.
     *
     * <p>Authorization is {@code TodoCommands.userCanEdit}, not a role check of this resource's own devising:
     * a status has an OWNER, which is not always the person the todo is about (staff can own a task on someone's
     * behalf), and re-deriving that rule here would let the two disagree. It applies to the caller's OWN row
     * too: "it is about me" used to skip it, so a traveler marked a staff-owned task done.
     *
     * <p>What a traveler may do is what the to-do page lets them do ({@link #travelerRefusal}): the row must
     * already exist (only staff assign a task; the old get-or-create minted a row tying anyone to any trip's
     * task), be USER-visible, and be theirs, their family member's, or one they own. On such a row they may
     * always write a note (the page's Notes box asks no more), and move the status when its owner is in the
     * picture. Priority, visibility and owner are the staff's.
     */
    @PUT
    @Path("{dataId}/statuses/{personId}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response saveStatus(
            @PathParam("tripId") final String tripId,
            @PathParam("dataId") final String dataId,
            @PathParam("personId") final String personIdParam,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final TodoStatusDto body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final TodoCommands todos = Beans.get(TodoCommands.class);
        final TodoItem todo = todos.getTodo(tripId, DataId.from(dataId));
        if (todo == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such todo.");
        }
        final Person.Id subject = Person.Id.from(personIdParam);
        final boolean staff = isWriteStaff(tripId);
        // Only staff ASSIGN a task (create the row); for anyone else a status that does not exist is not theirs.
        final TodoStatus status = staff ? todos.getOrCreateTodoStatus(todo, subject)
                : todos.getTodoStatus(todo, subject);
        if (status == null || (!staff && travelerRefusal(todos, status, subject, body))) {
            return error(403, ApiErrors.FORBIDDEN, NOT_THEIR_STATUS);
        }
        final String before = String.valueOf(status.getStatusValue());
        applyStatus(body, status, staff);
        if (!todos.saveTodoStatus(status)) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the todo status.");
        }
        auditIfChanged(status, subject, before);
        return ok(dto(status, subject, staff));
    }

    /**
     * Whether a non-staff caller's write to this status is refused. The page's rules, in its order: the row
     * is one a traveler can see at all ({@code getTodosForUser} hides every non-USER visibility); it is the
     * caller's own or a managed family member's ({@code actsFor}, the page's subject gate), or the caller is
     * its owner; and a body that MOVES the status needs {@code userCanEdit} -- the dashboard's drag test,
     * asked of the caller, and of the subject the way the page asks it (a family manager viewing a member's
     * list moves what that member owns).
     */
    private boolean travelerRefusal(final TodoCommands todos, final TodoStatus status, final Person.Id subject,
            final TodoStatusDto body) {
        final boolean owner = todos.userCanEdit(status, personId(), false);
        final boolean own = actsFor(subject);
        if (status.getVisibility() != Status.Visibility.USER || !(own || owner)) {
            return true;
        }
        // An echoed, unchanged value is not a move: a client that reads a row and PUTs it back with a note.
        final boolean moves = body != null && body.statusValue() != null
                && !body.statusValue().equals(String.valueOf(status.getStatusValue()));
        return moves && !owner && !todos.userCanEdit(status, subject, false);
    }

    /**
     * Records a status change, matching what the todo pages record.
     *
     * <p>Only when the VALUE moved -- editing a note is not a milestone, and auditing every save would bury the
     * transitions somebody is actually looking for.
     */
    private void auditIfChanged(final TodoStatus status, final Person.Id subject, final String before) {
        final String after = String.valueOf(status.getStatusValue());
        if (before.equals(after)) {
            return;
        }
        Beans.get(AuditCommands.class)
                .todoStatus(findPerson(subject), status.getDescription(), after, actor());
    }

    /**
     * The wire form, with the note withheld unless this caller may read it.
     *
     * <p>A USER-visibility note belongs to the traveller. Staff see it, because that is the point of asking; a
     * third party looking at somebody else's list does not.
     */
    private TodoStatusDto dto(final TodoStatus status, final Person.Id subject, final boolean staff) {
        final TodoStatusDto dto = TodoMapper.INSTANCE.toDto(status);
        final boolean mine = subject.equals(personId());
        return (mine || staff) ? dto : dto.withoutNotes();
    }

    private static void apply(final TodoDto body, final TodoItem todo) {
        if (body == null) {
            return;
        }
        if (body.description() != null) {
            todo.setDescription(body.description());
        }
        if (body.moreDetails() != null) {
            todo.setMoreDetails(body.moreDetails());
        }
    }

    /**
     * Applies the submitted status fields.
     *
     * <p>{@code visibility}, {@code owner} and {@code priority} are staff-only. A traveler flipping their own
     * item to {@code DELETED}, or reassigning its owner, would remove it from the list the trip is tracking --
     * which is a decision for whoever set the task, not for the person avoiding it; and how urgent a task is
     * was never the traveler's to say (the page offers it only in the staff's edit dialog).
     */
    private static void applyStatus(final TodoStatusDto body, final TodoStatus status, final boolean staff) {
        if (body == null) {
            return;
        }
        if (body.statusValue() != null) {
            status.setStatusValue(body.statusValue());
        }
        if (body.notes() != null) {
            status.setNotes(body.notes());
        }
        if (staff && body.priority() != null) {
            status.setPriority(Status.Priority.valueOf(body.priority()));
        }
        if (staff && body.visibility() != null) {
            status.setVisibility(Status.Visibility.valueOf(body.visibility()));
        }
        if (staff && body.owner() != null) {
            status.setOwner(Person.Id.from(body.owner()));
        }
    }

    /** READ staff: the trip's managers and its Viewers see every row, the ADMIN-visibility ones included. */
    private boolean isTripStaff(final String tripId) {
        final ApiPrivileges privileges = privileges();
        return privileges.has(ApiPrivileges.TRIP_MGR, tripId) || privileges.has(ApiPrivileges.TRIP_VIEW, tripId);
    }

    /** WRITE staff: the trip's managers (and site admins). Viewer is a read role and stops at reading. */
    private boolean isWriteStaff(final String tripId) {
        return privileges().has(ApiPrivileges.TRIP_MGR, tripId);
    }

    private boolean canSee(final String tripId, final Person.Id subject) {
        return actsFor(subject) || isTripStaff(tripId);
    }
}
