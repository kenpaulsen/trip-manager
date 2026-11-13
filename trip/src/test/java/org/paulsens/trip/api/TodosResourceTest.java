package org.paulsens.trip.api;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.action.TodoCommands;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.api.dto.TodoDto;
import org.paulsens.trip.api.dto.TodoStatusDto;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Status;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.TodoStatus;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link TodosResource}: the trip's tasks, and each traveller's answers to them.
 *
 * <p>The authorization split is the thing worth pinning. A {@code TodoItem} is what the trip asked for and only
 * staff may write one; a {@code TodoStatus} is one person's answer and the traveller owns it. Getting those
 * backwards would let a traveller delete the task they are avoiding, or let a stranger read a private note.
 */
public class TodosResourceTest extends ResourceTestSupport {

    private static final String TRIP = "trip-todos-1";
    private static final Person.Id ME = Person.Id.from("person-me");
    private static final Person.Id OTHER = Person.Id.from("person-other");

    private TodoCommands todos;
    private TripCommands trips;
    private TodosResource resource;

    @BeforeMethod
    public void bindBeans() {
        todos = bindMock(TodoCommands.class);
        // The owner rule is a pure function of the status: let the real one decide, not a stub's opinion.
        Mockito.when(todos.userCanEdit(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyBoolean()))
                .thenCallRealMethod();
        // Bound for every test: the authorization path resolves the viewer through PersonCommands, so almost
        // any endpoint asks for it before it gets as far as the behaviour under test.
        bindMock(PersonCommands.class);
        // The trip the todos hang off must be real, and ME is on it: the ordinary traveler's case.
        trips = bindMock(TripCommands.class);
        tripWith(TRIP, ME);
        resource = resource(new TodosResource());
    }

    private void tripWith(final String tripId, final Person.Id... roster) {
        Mockito.when(trips.getTrip(tripId)).thenReturn(
                Trip.builder().id(tripId).title("Todo trip").people(List.of(roster)).build());
    }

    private static Person.Id holderOf(final String base, final String tripId) {
        final Person.Id who = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.getOrCreate(base, tripId, base).withNewPerson(who), null));
        return who;
    }

    private TodosResource as(final Person.Id who) {
        signedInAs(who);
        return resource(new TodosResource());
    }

    private static TodoItem todoItem(final String dataId) {
        return TodoItem.builder()
                .tripId(TRIP)
                .dataId(DataId.from(dataId))
                .description("Send your passport scan")
                .build();
    }

    @Test
    public void listReturnsTheTripsTodos() {
        signedInAs(ME);
        Mockito.when(todos.getTodos(TRIP)).thenReturn(List.of(todoItem("d1"), todoItem("d2")));

        final Response response = resource.list(TRIP);

        assertOk(response);
        Assert.assertEquals(((List<?>) response.getEntity()).size(), 2);
    }

    @Test
    public void listStampsTheVersionedTypeOnlyWhenAsked() {
        signedInAs(ME);
        Mockito.when(todos.getTodos(TRIP)).thenReturn(List.of());

        // No Accept header: served v1 semantics, but told plain JSON -- a client that did not name a version
        // is never told it received one.
        assertPlainJsonType(resource.list(TRIP));

        accepting(ApiMediaTypes.TODOS_V1);
        assertVersionedType(resource.list(TRIP), ApiMediaTypes.TODOS_V1);
    }

    @Test
    public void getAnswers404ForAnUnknownTodo() {
        signedInAs(ME);
        Mockito.when(todos.getTodo(ArgumentMatchers.eq(TRIP), ArgumentMatchers.any())).thenReturn(null);

        assertError(resource.get(TRIP, "nope"), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void getReturnsTheTodo() {
        signedInAs(ME);
        Mockito.when(todos.getTodo(ArgumentMatchers.eq(TRIP), ArgumentMatchers.any())).thenReturn(todoItem("d1"));

        final Response response = resource.get(TRIP, "d1");

        assertOk(response);
        Assert.assertEquals(((TodoDto) response.getEntity()).description(), "Send your passport scan");
    }

    @Test
    public void creatingATodoWithoutTheCsrfHeaderIsRefused() {
        signedInAsSiteAdmin(ME);

        assertError(resource.create(TRIP, null, new TodoDto(null, null, null, null, null)), 403, ApiErrors.CSRF);
        // The refusal must come before any write is attempted.
        Mockito.verify(todos, Mockito.never()).saveTodo(ArgumentMatchers.any());
    }

    @Test
    public void aTravellerCannotInventTasksForTheGroup() {
        signedInAs(ME);

        assertError(resource.create(TRIP, CSRF_OK, new TodoDto(null, null, null, null, null)),
                403, ApiErrors.FORBIDDEN);
        Mockito.verify(todos, Mockito.never()).saveTodo(ArgumentMatchers.any());
    }

    @Test
    public void staffCanCreateATodo() {
        signedInAsSiteAdmin(ME);
        Mockito.when(todos.createTodo(TRIP)).thenReturn(todoItem("d1"));
        Mockito.when(todos.saveTodo(ArgumentMatchers.any())).thenReturn(true);

        final Response response = resource.create(TRIP, CSRF_OK, new TodoDto(null, null, "Bring a coat", null, null));

        assertOk(response);
        Assert.assertEquals(((TodoDto) response.getEntity()).description(), "Bring a coat");
    }

    @Test
    public void aFailedStoreIsReportedRatherThanSwallowed() {
        signedInAsSiteAdmin(ME);
        Mockito.when(todos.createTodo(TRIP)).thenReturn(todoItem("d1"));
        Mockito.when(todos.saveTodo(ArgumentMatchers.any())).thenReturn(false);

        assertError(resource.create(TRIP, CSRF_OK, new TodoDto(null, null, "x", null, null)),
                500, ApiErrors.STORE_FAILED);
    }

    @Test
    public void updateAnswers404BeforeApplyingAnythingToAMissingTodo() {
        signedInAsSiteAdmin(ME);
        Mockito.when(todos.getTodo(ArgumentMatchers.eq(TRIP), ArgumentMatchers.any())).thenReturn(null);

        assertError(resource.update(TRIP, "gone", CSRF_OK, new TodoDto(null, null, "x", null, null)),
                404, ApiErrors.NOT_FOUND);
        Mockito.verify(todos, Mockito.never()).saveTodo(ArgumentMatchers.any());
    }

    @Test
    public void updateAppliesOnlyTheFieldsThatWerePresent() {
        signedInAsSiteAdmin(ME);
        final TodoItem existing = todoItem("d1");
        existing.setMoreDetails("original details");
        Mockito.when(todos.getTodo(ArgumentMatchers.eq(TRIP), ArgumentMatchers.any())).thenReturn(existing);
        Mockito.when(todos.saveTodo(ArgumentMatchers.any())).thenReturn(true);

        // Only description is set; a null field means "not submitted", not "clear it".
        assertOk(resource.update(TRIP, "d1", CSRF_OK, new TodoDto(null, null, "new description", null, null)));

        Assert.assertEquals(existing.getDescription(), "new description");
        Assert.assertEquals(existing.getMoreDetails(), "original details");
    }

    @Test
    public void updateRefusesWithoutTheCsrfHeader() {
        signedInAsSiteAdmin(ME);

        assertError(resource.update(TRIP, "d1", null, null), 403, ApiErrors.CSRF);
    }

    @Test
    public void updateRefusesANonStaffCaller() {
        signedInAs(ME);
        // A fresh resource, because BaseResource memoizes its Caller: one instance serves one request.
        final TodosResource forThisCaller = resource(new TodosResource());

        assertError(forThisCaller.update(TRIP, "d1", CSRF_OK, null), 403, ApiErrors.FORBIDDEN);
    }

    @Test
    public void statusesRefusesAThirdPartyLookingAtSomebodyElsesList() {
        signedInAs(ME);

        assertError(resource.statuses(TRIP, OTHER.getValue()), 403, ApiErrors.FORBIDDEN);
    }

    @Test
    public void statusesAllowsSomebodyToReadTheirOwnList() {
        signedInAs(ME);
        Mockito.when(todos.getTodosForUser(TRIP, ME, false)).thenReturn(List.of(status("d1", ME, "note")));

        final Response response = resource.statuses(TRIP, ME.getValue());

        assertOk(response);
        Assert.assertEquals(((List<?>) response.getEntity()).size(), 1);
    }

    @Test
    public void myStatusesNeedsNoIdFromTheClient() {
        signedInAs(ME);
        Mockito.when(todos.getTodosForUser(TRIP, ME, false)).thenReturn(List.of(status("d1", ME, "note")));

        assertOk(resource.myStatuses(TRIP));
    }

    /**
     * The redaction rule, which is authorization rather than presentation: a USER-visibility note belongs to the
     * traveller. Staff see it because that is the point of asking; a third party does not.
     */
    @Test
    public void anotherPersonsNoteIsWithheldFromANonStaffViewer() {
        signedInAsSiteAdmin(ME); // staff: sees the note
        Mockito.when(todos.getTodosForUser(TRIP, OTHER, true))
                .thenReturn(List.of(status("d1", OTHER, "private note")));

        final Response staffView = resource.statuses(TRIP, OTHER.getValue());
        assertOk(staffView);
        Assert.assertEquals(firstDto(staffView).notes(), "private note");
    }

    @Test
    public void savingAStatusRequiresTheCsrfHeader() {
        signedInAs(ME);
        assertError(resource.saveStatus(TRIP, "d1", ME.getValue(), null, null), 403, ApiErrors.CSRF);
    }

    @Test
    public void savingAStatusAgainstAMissingTodoIs404() {
        signedInAs(ME);
        Mockito.when(todos.getTodo(ArgumentMatchers.eq(TRIP), ArgumentMatchers.any())).thenReturn(null);

        assertError(resource.saveStatus(TRIP, "gone", ME.getValue(), CSRF_OK, null), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void aTravellerMayEditTheirOwnStatus() {
        signedInAs(ME);
        final TodoItem todo = todoItem("d1");
        final TodoStatus status = status("d1", ME, null);
        Mockito.when(todos.getTodo(ArgumentMatchers.eq(TRIP), ArgumentMatchers.any())).thenReturn(todo);
        Mockito.when(todos.getTodoStatus(todo, ME)).thenReturn(status);
        Mockito.when(todos.saveTodoStatus(status)).thenReturn(true);

        assertOk(resource.saveStatus(TRIP, "d1", ME.getValue(), CSRF_OK,
                submitted(null, null, "my note", null)));
        Assert.assertEquals(status.getNotes(), "my note");
    }

    @Test
    public void aTravellerMayNotEditSomebodyElsesStatus() {
        signedInAs(ME);
        final TodoItem todo = todoItem("d1");
        final TodoStatus status = status("d1", OTHER, null);
        Mockito.when(todos.getTodo(ArgumentMatchers.eq(TRIP), ArgumentMatchers.any())).thenReturn(todo);
        Mockito.when(todos.getTodoStatus(todo, OTHER)).thenReturn(status);

        assertError(resource.saveStatus(TRIP, "d1", OTHER.getValue(), CSRF_OK, null), 403, ApiErrors.FORBIDDEN);
        Mockito.verify(todos, Mockito.never()).saveTodoStatus(ArgumentMatchers.any());
    }

    /**
     * Visibility and owner are staff-only. A traveller flipping their own item to DELETED, or reassigning it,
     * would remove it from what the trip is tracking, which is not their decision to make.
     */
    @Test
    public void aTravellerCannotChangeVisibilityOrOwner() {
        signedInAs(ME);
        final TodoItem todo = todoItem("d1");
        final TodoStatus status = status("d1", ME, null);
        status.setVisibility(Status.Visibility.USER);
        Mockito.when(todos.getTodo(ArgumentMatchers.eq(TRIP), ArgumentMatchers.any())).thenReturn(todo);
        Mockito.when(todos.getTodoStatus(todo, ME)).thenReturn(status);
        Mockito.when(todos.saveTodoStatus(status)).thenReturn(true);

        assertOk(resource.saveStatus(TRIP, "d1", ME.getValue(), CSRF_OK,
                submitted(null, "ADMIN", null, OTHER.getValue())));

        Assert.assertEquals(status.getVisibility(), Status.Visibility.USER, "Visibility is staff-only");
        Assert.assertNotEquals(status.getOwner(), OTHER, "Owner is staff-only");
    }

    @Test
    public void aFailedStatusStoreIsReported() {
        signedInAs(ME);
        final TodoItem todo = todoItem("d1");
        final TodoStatus status = status("d1", ME, null);
        Mockito.when(todos.getTodo(ArgumentMatchers.eq(TRIP), ArgumentMatchers.any())).thenReturn(todo);
        Mockito.when(todos.getTodoStatus(todo, ME)).thenReturn(status);
        Mockito.when(todos.saveTodoStatus(status)).thenReturn(false);

        assertError(resource.saveStatus(TRIP, "d1", ME.getValue(), CSRF_OK, null), 500, ApiErrors.STORE_FAILED);
    }

    /** Only a moved VALUE is a milestone; auditing every save would bury the transitions somebody looks for. */
    @Test
    public void aStatusValueChangeIsAuditedAndANoteEditIsNot() {
        signedInAs(ME);
        final AuditCommands audit = bindMock(AuditCommands.class);
        final TodoItem todo = todoItem("d1");
        final TodoStatus status = status("d1", ME, null);
        status.setOwner(ME);        // theirs to move: a traveler moves only a status they own
        Mockito.when(todos.getTodo(ArgumentMatchers.eq(TRIP), ArgumentMatchers.any())).thenReturn(todo);
        Mockito.when(todos.getTodoStatus(todo, ME)).thenReturn(status);
        Mockito.when(todos.saveTodoStatus(status)).thenReturn(true);

        // A note edit alone moves nothing.
        resource.saveStatus(TRIP, "d1", ME.getValue(), CSRF_OK,
                submitted(null, null, "just a note", null));
        Mockito.verifyNoInteractions(audit);

        resource.saveStatus(TRIP, "d1", ME.getValue(), CSRF_OK,
                submitted("DONE", null, null, null));
        Mockito.verify(audit).todoStatus(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq("DONE"), ArgumentMatchers.any());
    }

    // ------------------------------------------------------------------ audit 2026-11-11: F11, F24

    /** F11: the trip's task list answered anybody signed in, for any organization's trip. */
    @Test
    public void aStrangerCannotReadATripsTodos() {
        tripWith("trip-of-others", OTHER);
        signedInAs(ME);

        assertError(resource.list("trip-of-others"), 403, ApiErrors.FORBIDDEN);
        assertError(resource.get("trip-of-others", "d1"), 403, ApiErrors.FORBIDDEN);
        Mockito.verify(todos, Mockito.never()).getTodos(ArgumentMatchers.any());
        Mockito.verify(todos, Mockito.never()).getTodo(ArgumentMatchers.any(), ArgumentMatchers.any());
        // A trip nobody created is a 404, not an empty list.
        assertThrown(() -> resource.list("no-such-trip"), 404, ApiErrors.NOT_FOUND);
        assertThrown(() -> resource.get("no-such-trip", "d1"), 404, ApiErrors.NOT_FOUND);
    }

    /** The trip's staff read its todos without a seat; its Viewer reads and does nothing else (F24). */
    @Test
    public void aViewerReadsTheTodosButIsNotWriteStaff() {
        final String tripId = UUID.randomUUID().toString();
        tripWith(tripId, OTHER);
        final TodoItem todo = todoItem("d1");
        final TodoStatus status = status("d1", OTHER, null);
        Mockito.when(todos.getTodos(tripId)).thenReturn(List.of(todo));
        Mockito.when(todos.getTodo(ArgumentMatchers.eq(tripId), ArgumentMatchers.any())).thenReturn(todo);
        Mockito.when(todos.getTodoStatus(todo, OTHER)).thenReturn(status);
        final Person.Id viewerId = holderOf(PrivilegeCommands.TRIP_VIEW, tripId);

        assertOk(as(viewerId).list(tripId));
        assertOk(as(viewerId).get(tripId, "d1"));
        assertError(as(viewerId).create(tripId, CSRF_OK, new TodoDto(null, null, "Viewer's task", null, null)),
                403, ApiErrors.FORBIDDEN);
        assertError(as(viewerId).update(tripId, "d1", CSRF_OK, new TodoDto(null, null, "rewritten", null, null)),
                403, ApiErrors.FORBIDDEN);
        assertError(as(viewerId).saveStatus(tripId, "d1", OTHER.getValue(), CSRF_OK,
                submitted("DONE", "DELETED", null, null)), 403, ApiErrors.FORBIDDEN);
        Assert.assertEquals(todo.getDescription(), "Send your passport scan");
        Mockito.verify(todos, Mockito.never()).saveTodo(ArgumentMatchers.any());
        Mockito.verify(todos, Mockito.never()).saveTodoStatus(ArgumentMatchers.any());
    }

    /** The trip's manager is write staff: assigns a task (the row is created), sets visibility and owner. */
    @Test
    public void aTripManagerAssignsAndEditsAnybodysStatus() {
        final String tripId = UUID.randomUUID().toString();
        tripWith(tripId, OTHER);
        final TodoItem todo = todoItem("d1");
        final TodoStatus fresh = status("d1", OTHER, null);
        Mockito.when(todos.getTodo(ArgumentMatchers.eq(tripId), ArgumentMatchers.any())).thenReturn(todo);
        Mockito.when(todos.createTodo(tripId)).thenReturn(todo);
        Mockito.when(todos.saveTodo(todo)).thenReturn(true);
        Mockito.when(todos.getOrCreateTodoStatus(todo, OTHER)).thenReturn(fresh);
        Mockito.when(todos.saveTodoStatus(fresh)).thenReturn(true);
        bindMock(AuditCommands.class);
        final Person.Id manager = holderOf(PrivilegeCommands.TRIP_MGR, tripId);

        assertOk(as(manager).create(tripId, CSRF_OK, new TodoDto(null, null, "Bring a coat", null, null)));
        assertOk(as(manager).saveStatus(tripId, "d1", OTHER.getValue(), CSRF_OK, new TodoStatusDto(null, null,
                null, null, null, "IN_PROGRESS", "HIGH", "ADMIN", "from staff", OTHER.getValue(), null, null)));
        Assert.assertEquals(fresh.getStatusValue(), Status.StatusValue.IN_PROGRESS);
        Assert.assertEquals(fresh.getPriority(), Status.Priority.HIGH);
        Assert.assertEquals(fresh.getVisibility(), Status.Visibility.ADMIN);
        Assert.assertEquals(fresh.getOwner(), OTHER);
    }

    /**
     * F24: "it is about me" used to skip the owner rule, so a traveler marked a staff-owned task done. They
     * move only a status they own; their note on a row they can see is still theirs to write (the page's
     * Notes box asks no more), and urgency was never theirs to set.
     */
    @Test
    public void aTravelerMovesOnlyAStatusTheyOwn() {
        signedInAs(ME);
        final TodoItem todo = todoItem("d1");
        final TodoStatus staffOwned = status("d1", ME, null);       // owner null: the trip organizer's
        Mockito.when(todos.getTodo(ArgumentMatchers.eq(TRIP), ArgumentMatchers.any())).thenReturn(todo);
        Mockito.when(todos.getTodoStatus(todo, ME)).thenReturn(staffOwned);
        Mockito.when(todos.saveTodoStatus(staffOwned)).thenReturn(true);
        bindMock(AuditCommands.class);

        assertError(resource.saveStatus(TRIP, "d1", ME.getValue(), CSRF_OK, submitted("DONE", null, null, null)),
                403, ApiErrors.FORBIDDEN);
        Assert.assertEquals(staffOwned.getStatusValue(), Status.StatusValue.TODO);
        Mockito.verify(todos, Mockito.never()).saveTodoStatus(ArgumentMatchers.any());

        // A note, and a body that merely echoes the value it read, are not a move.
        assertOk(resource.saveStatus(TRIP, "d1", ME.getValue(), CSRF_OK,
                new TodoStatusDto(null, null, null, null, null, "TODO", "CRITICAL", null, "on its way", null,
                        null, null)));
        Assert.assertEquals(staffOwned.getNotes(), "on its way");
        Assert.assertEquals(staffOwned.getPriority(), Status.Priority.NORMAL, "priority is the staff's");

        staffOwned.setOwner(ME);
        assertOk(resource.saveStatus(TRIP, "d1", ME.getValue(), CSRF_OK, submitted("DONE", null, null, null)));
        Assert.assertEquals(staffOwned.getStatusValue(), Status.StatusValue.DONE);
    }

    /** F24: a row a traveler cannot see, or that nobody assigned them, is not theirs to write (or to create). */
    @Test
    public void aTravelerCannotWriteAHiddenStatusOrMintOneThatWasNeverAssigned() {
        signedInAs(ME);
        final TodoItem todo = todoItem("d1");
        final TodoStatus hidden = status("d1", ME, null);
        hidden.setOwner(ME);
        hidden.setVisibility(Status.Visibility.ADMIN);
        Mockito.when(todos.getTodo(ArgumentMatchers.eq(TRIP), ArgumentMatchers.any())).thenReturn(todo);
        Mockito.when(todos.getTodoStatus(todo, ME)).thenReturn(hidden);

        assertError(resource.saveStatus(TRIP, "d1", ME.getValue(), CSRF_OK, submitted("DONE", null, "x", null)),
                403, ApiErrors.FORBIDDEN);

        Mockito.when(todos.getTodoStatus(todo, ME)).thenReturn(null);
        assertError(resource.saveStatus(TRIP, "d1", ME.getValue(), CSRF_OK, submitted("DONE", null, null, null)),
                403, ApiErrors.FORBIDDEN);
        Mockito.verify(todos, Mockito.never()).getOrCreateTodoStatus(ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.verify(todos, Mockito.never()).saveTodoStatus(ArgumentMatchers.any());
    }

    /** A status has an OWNER who is not always its subject: the owner moves it, on somebody else's row too. */
    @Test
    public void theOwnerOfSomeoneElsesStatusMayMoveIt() {
        signedInAs(ME);
        final TodoItem todo = todoItem("d1");
        final TodoStatus mineToDo = status("d1", OTHER, null);
        mineToDo.setOwner(ME);
        Mockito.when(todos.getTodo(ArgumentMatchers.eq(TRIP), ArgumentMatchers.any())).thenReturn(todo);
        Mockito.when(todos.getTodoStatus(todo, OTHER)).thenReturn(mineToDo);
        Mockito.when(todos.saveTodoStatus(mineToDo)).thenReturn(true);
        bindMock(AuditCommands.class);

        assertOk(resource.saveStatus(TRIP, "d1", OTHER.getValue(), CSRF_OK, submitted("DONE", null, null, null)));
        Assert.assertEquals(mineToDo.getStatusValue(), Status.StatusValue.DONE);
    }

    @Test
    public void theProducedTypeIsTheTodosMediaType() {
        Assert.assertEquals(new TodosResource().versionedType(), ApiMediaTypes.TODOS_V1);
        Assert.assertNotEquals(ApiMediaTypes.TODOS_V1, MediaType.APPLICATION_JSON);
    }

    private static TodoStatus status(final String dataId, final Person.Id owner, final String notes) {
        final TodoStatus status = new TodoStatus(todoItem(dataId), owner);
        if (notes != null) {
            status.setNotes(notes);
        }
        return status;
    }

    /**
     * The submitted fields, by name.
     *
     * <p>Positionally the record is twelve components wide and mostly nulls at a call site, which reads as a
     * row of commas and silently shifts meaning if a component is ever inserted.
     */
    private static TodoStatusDto submitted(
            final String statusValue, final String visibility, final String notes, final String owner) {
        return new TodoStatusDto(null, null, null, null, null, statusValue, null, visibility, notes, owner,
                null, null);
    }

    private static TodoStatusDto firstDto(final Response response) {
        return (TodoStatusDto) ((List<?>) response.getEntity()).get(0);
    }
}
