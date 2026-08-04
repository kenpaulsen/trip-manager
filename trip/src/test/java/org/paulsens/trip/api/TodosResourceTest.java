package org.paulsens.trip.api;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.TodoCommands;
import org.paulsens.trip.api.dto.TodoDto;
import org.paulsens.trip.api.dto.TodoStatusDto;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Status;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.TodoStatus;
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
    private TodosResource resource;

    @BeforeMethod
    public void bindBeans() {
        todos = bindMock(TodoCommands.class);
        // Bound for every test: the authorization path resolves the viewer through PersonCommands, so almost
        // any endpoint asks for it before it gets as far as the behaviour under test.
        bindMock(PersonCommands.class);
        resource = resource(new TodosResource());
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
        Mockito.when(todos.getOrCreateTodoStatus(todo, ME)).thenReturn(status);
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
        Mockito.when(todos.getOrCreateTodoStatus(todo, OTHER)).thenReturn(status);
        Mockito.when(todos.userCanEdit(status, ME, false)).thenReturn(false);

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
        Mockito.when(todos.getOrCreateTodoStatus(todo, ME)).thenReturn(status);
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
        Mockito.when(todos.getOrCreateTodoStatus(todo, ME)).thenReturn(status);
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
        Mockito.when(todos.getTodo(ArgumentMatchers.eq(TRIP), ArgumentMatchers.any())).thenReturn(todo);
        Mockito.when(todos.getOrCreateTodoStatus(todo, ME)).thenReturn(status);
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
