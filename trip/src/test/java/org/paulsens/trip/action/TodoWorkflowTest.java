package org.paulsens.trip.action;

import java.util.List;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Status;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.TodoStatus;
import org.paulsens.trip.model.Trip;
import org.primefaces.model.dashboard.DashboardModel;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link TodoCommands}' workflow paths against the fake store: the bulk assign/unassign flow the admin page
 * drives, the widget-id round trip the dashboard depends on, and the owner-resolution rules.
 *
 * <p>The unassign half is the subtle one: someone dropped from a todo is not deleted but flipped to DELETED
 * visibility, and ONLY that -- their status values survive in case they are re-assigned.
 */
public class TodoWorkflowTest {

    private final TodoCommands todos = new TodoCommands();
    private final TripCommands trips = new TripCommands();

    // Real, saved people: saveTrip runs sortTripPeople, which silently DROPS any member id that resolves to no
    // Person record -- an invented id never survives the save. Created per-method rather than statically,
    // because other tests in the suite reset the fake store and would erase them.
    private Person.Id alice;
    private Person.Id bob;

    @org.testng.annotations.BeforeMethod
    public void createPeople() {
        alice = realPerson("Alice");
        bob = realPerson("Bob");
    }

    private static Person.Id realPerson(final String first) {
        final PersonCommands people = new PersonCommands();
        final org.paulsens.trip.model.Person person = people.createPerson();
        person.setFirst(first);
        person.setLast("Todoer");
        Assert.assertTrue(people.savePerson(person));
        return person.getId();
    }

    private Trip savedTrip(final Person.Id... members) {
        final Trip trip = trips.createTrip();
        trip.setPeople(List.of(members));
        Assert.assertTrue(trips.saveTrip(trip));
        awaitStoreSettled();
        return trip;
    }

    /**
     * Cache invalidation after a write is asynchronous (a ~50ms delayed clear), so an immediate re-read can see
     * the pre-save value -- the same effect the production gotchas describe. Under a loaded suite the delay is
     * not bounded by the nominal 50ms, so reads that depend on the write POLL until the condition holds rather
     * than sleeping a fixed amount and hoping.
     */
    private static void awaitStoreSettled() {
        try {
            Thread.sleep(120L);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static <T> T awaitValue(final java.util.function.Supplier<T> read,
            final java.util.function.Predicate<T> ready, final String what) {
        final long deadline = System.currentTimeMillis() + 5_000L;
        T value = read.get();
        while (!ready.test(value) && System.currentTimeMillis() < deadline) {
            awaitStoreSettled();
            value = read.get();
        }
        Assert.assertTrue(ready.test(value), "Timed out waiting for " + what + "; last saw: " + value);
        return value;
    }

    @Test
    public void aTodoRoundTripsThroughTheStore() {
        final Trip trip = savedTrip(alice);
        final TodoItem todo = todos.createTodo(trip.getId());
        todo.setDescription("Send passport");

        Assert.assertTrue(todos.saveTodo(todo));

        Assert.assertTrue(todos.getTodos(trip.getId()).stream()
                .anyMatch(item -> "Send passport".equals(item.getDescription())));
        Assert.assertNotNull(todos.getTodo(trip.getId(), todo.getDataId()));
    }

    @Test
    public void getTodoGuardsItsInputs() {
        Assert.assertNull(todos.getTodo(null, DataId.from("d")));
        Assert.assertNull(todos.getTodo("t", null));
        Assert.assertNull(todos.getTodo("no-such-trip", DataId.from("no-such-todo")));
    }

    @Test
    public void widgetIdsRoundTripBackToTheStatus() {
        final Trip trip = savedTrip(alice);
        final TodoItem todo = todos.createTodo(trip.getId());
        todo.setDescription("Widget round trip");
        Assert.assertTrue(todos.saveTodo(todo));
        final TodoStatus status = todos.getOrCreateTodoStatus(todo, alice);
        Assert.assertTrue(todos.saveTodoStatus(status));

        final String widgetId = todos.getWidgetId(todo.getDataId(), alice);
        Assert.assertNotNull(widgetId);

        final TodoStatus found = todos.getTodoStatusUsingWidgetId(trip.getId(), widgetId);
        Assert.assertNotNull(found, "The dashboard resolves statuses by widget id; the round trip must hold");
        Assert.assertEquals(found.getUserId(), alice);
    }

    @Test
    public void widgetIdGuardsItsInputs() {
        Assert.assertNull(todos.getWidgetId(null, alice));
        Assert.assertNull(todos.getWidgetId(DataId.from("d"), null));
    }

    @Test
    public void theBulkSaveAssignsEveryoneAndUnassignsByFlippingVisibility() {
        final Trip trip = savedTrip(alice, bob);

        // Assign both.
        todos.saveTodoAndStatuses(new Person.Id[] {alice, bob}, null, trip, "USER",
                "Bring boots", "Sturdy ones", Status.Visibility.USER, Status.Priority.NORMAL);
        awaitStoreSettled();
        final TodoItem todo = todos.getTodos(trip.getId()).stream()
                .filter(item -> "Bring boots".equals(item.getDescription()))
                .findFirst().orElseThrow();
        awaitValue(() -> todos.getTodoStatusesForTodo(todo), list -> list.size() == 2, "both assignees");

        // Re-save with only Alice: Bob is flipped to DELETED, not erased.
        todos.saveTodoAndStatuses(new Person.Id[] {alice}, todo.getDataId(), trip, "USER",
                "Bring boots", "Sturdy ones", Status.Visibility.USER, Status.Priority.NORMAL);
        final List<TodoStatus> visible =
                awaitValue(() -> todos.getTodoStatusesForTodo(todo), list -> list.size() == 1, "one assignee");
        Assert.assertEquals(visible.get(0).getUserId(), alice);
        final TodoStatus bobs = todos.getTodoStatus(todo, bob);
        Assert.assertNotNull(bobs, "Bob's status row must survive the unassign");
        Assert.assertEquals(bobs.getVisibility(), Status.Visibility.DELETED);
    }

    @Test
    public void theBulkSaveRefusesMissingPeopleOrDescription() {
        final Trip trip = savedTrip(alice);

        todos.saveTodoAndStatuses(new Person.Id[0], null, trip, "USER", "desc", null,
                Status.Visibility.USER, Status.Priority.NORMAL);
        todos.saveTodoAndStatuses(new Person.Id[] {alice}, null, trip, "USER", "  ", null,
                Status.Visibility.USER, Status.Priority.NORMAL);

        Assert.assertTrue(todos.getTodos(trip.getId()).isEmpty(), "A refused save must create nothing");
    }

    @Test
    public void ownerResolutionFollowsTheThreeRules() {
        final Trip trip = savedTrip(alice);

        // ADMIN (or null): no owner.
        todos.saveTodoAndStatuses(new Person.Id[] {alice}, null, trip, "ADMIN", "Admin-owned", null,
                Status.Visibility.USER, Status.Priority.NORMAL);
        final TodoItem adminOwned = byDescription(trip, "Admin-owned");
        Assert.assertNull(todos.getTodoStatus(adminOwned, alice).getOwner());

        // USER: the assignee owns it.
        todos.saveTodoAndStatuses(new Person.Id[] {alice}, null, trip, "USER", "User-owned", null,
                Status.Visibility.USER, Status.Priority.NORMAL);
        Assert.assertEquals(todos.getTodoStatus(byDescription(trip, "User-owned"), alice).getOwner(), alice);

        // An explicit id: that person owns it regardless of assignee.
        todos.saveTodoAndStatuses(new Person.Id[] {alice}, null, trip, bob.getValue(), "Bob-owned", null,
                Status.Visibility.USER, Status.Priority.NORMAL);
        Assert.assertEquals(todos.getTodoStatus(byDescription(trip, "Bob-owned"), alice).getOwner(), bob);
    }

    private TodoItem byDescription(final Trip trip, final String description) {
        return todos.getTodos(trip.getId()).stream()
                .filter(item -> description.equals(item.getDescription()))
                .findFirst().orElseThrow();
    }

    @Test
    public void editingIsForTheOwnerOrAnAdmin() {
        final TodoItem todo = TodoItem.builder().tripId("t").dataId(DataId.from("d")).description("d").build();
        final TodoStatus owned = new TodoStatus(todo, alice);
        owned.setOwner(alice);

        Assert.assertTrue(todos.userCanEdit(owned, alice, false));
        Assert.assertFalse(todos.userCanEdit(owned, bob, false));
        Assert.assertTrue(todos.userCanEdit(owned, bob, true), "An admin edits anything");

        final TodoStatus unowned = new TodoStatus(todo, alice);
        Assert.assertFalse(todos.userCanEdit(unowned, alice, false), "No owner means admin-only");
    }

    @Test
    public void theDashboardPlacesStatusesByColumnAndHidesDoneWhenAsked() {
        final TodoItem todo = TodoItem.builder().tripId("t").dataId(DataId.from("dash-d")).description("d").build();
        final TodoStatus toDo = new TodoStatus(todo, alice);
        final TodoStatus inProgress = new TodoStatus(todo, bob);
        inProgress.setStatusValue(Status.StatusValue.IN_PROGRESS);
        final TodoStatus done = new TodoStatus(todo, Person.Id.from("todo-carol"));
        done.setStatusValue(Status.StatusValue.DONE);
        final List<TodoStatus> statuses = List.of(toDo, inProgress, done);

        final DashboardModel with = todos.getTodoDashboard(statuses, true);
        Assert.assertEquals(with.getWidgets().size(), 3);
        Assert.assertEquals(with.getWidget(2).getWidgets().size(), 1, "Done lands in the third column");

        final DashboardModel without = todos.getTodoDashboard(statuses, false);
        Assert.assertEquals(without.getWidgets().size(), 2);
        Assert.assertEquals(without.getWidget(0).getWidgets().size(), 1);
        Assert.assertEquals(without.getWidget(1).getWidgets().size(), 1);
    }

    @Test
    public void tagSeveritiesCoverEveryEnumValue() {
        final TodoItem todo = TodoItem.builder().tripId("t").dataId(DataId.from("sev-d")).description("d").build();
        final TodoStatus status = new TodoStatus(todo, alice);

        Assert.assertEquals(todos.statusToTagSeverity(status), "warning");
        status.setStatusValue(Status.StatusValue.IN_PROGRESS);
        Assert.assertEquals(todos.statusToTagSeverity(status), "info");
        status.setStatusValue(Status.StatusValue.DONE);
        Assert.assertEquals(todos.statusToTagSeverity(status), "success");

        status.setPriority(Status.Priority.OPTIONAL);
        Assert.assertEquals(todos.priorityToTagSeverity(status), "success");
        status.setPriority(Status.Priority.LOW);
        Assert.assertEquals(todos.priorityToTagSeverity(status), "info");
        status.setPriority(Status.Priority.NORMAL);
        Assert.assertEquals(todos.priorityToTagSeverity(status), "primary");
        status.setPriority(Status.Priority.HIGH);
        Assert.assertEquals(todos.priorityToTagSeverity(status), "warning");
        status.setPriority(Status.Priority.CRITICAL);
        Assert.assertEquals(todos.priorityToTagSeverity(status), "danger");
    }

    @Test
    public void statusesForANullOrOrphanedTodoAreEmpty() {
        Assert.assertEquals(todos.getTodoStatusesForTodo(null), List.of());

        final TodoItem orphan = TodoItem.builder().tripId("no-such-trip").dataId(DataId.from("d")).description("d").build();
        Assert.assertEquals(todos.getTodoStatusesForTodo(orphan), List.of(),
                "getTrip mints a blank trip on a miss; the id comparison must catch it");
    }

    @Test
    public void peopleForATodoAreItsVisibleAssignees() {
        final Trip trip = savedTrip(alice, bob);
        todos.saveTodoAndStatuses(new Person.Id[] {alice}, null, trip, "USER", "People check", null,
                Status.Visibility.USER, Status.Priority.NORMAL);
        awaitValue(() -> todos.getPeopleForTodoItem(byDescription(trip, "People check")),
                list -> list.equals(List.of(alice)), "the assignee list");
    }

    @Test
    public void assignmentIsDetectedThroughTheDataValue() {
        final Trip trip = savedTrip(alice);
        todos.saveTodoAndStatuses(new Person.Id[] {alice}, null, trip, "USER", "Assigned check", null,
                Status.Visibility.USER, Status.Priority.NORMAL);
        final TodoItem todo = byDescription(trip, "Assigned check");

        Assert.assertTrue(todos.isAssignedTodo(todo, alice));
        Assert.assertFalse(todos.isAssignedTodo(todo, bob));
    }
}
