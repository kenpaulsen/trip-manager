package org.paulsens.trip.action;

import jakarta.faces.context.FacesContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.Transaction;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The remaining Faces-bound and binding helpers on the action beans.
 */
public class BeanFacesTailsTest {

    // --- PersonCommands: the application-scope singleton and the EL sorter ---

    @Test
    public void getPersonCommandsUsesTheApplicationMapWhenThereIsOneAndANewInstanceOtherwise() {
        Assert.assertNotNull(PersonCommands.getPersonCommands(), "no FacesContext: a fresh instance");

        final FacesContext ctx = Mockito.mock(FacesContext.class, Mockito.RETURNS_DEEP_STUBS);
        final Map<String, Object> appMap = new HashMap<>();
        Mockito.when(ctx.getExternalContext().getApplicationMap()).thenReturn(appMap);
        try (MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class)) {
            faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);

            final PersonCommands first = PersonCommands.getPersonCommands();

            Assert.assertSame(PersonCommands.getPersonCommands(), first,
                    "with a FacesContext the bean is application-scoped, not per-call");
            Assert.assertSame(appMap.get("people"), first);
        }
    }

    @Test
    public void toSortedListSortsThroughTheExpression() {
        final FacesContext ctx = Mockito.mock(FacesContext.class, Mockito.RETURNS_DEEP_STUBS);
        final Map<String, Object> reqMap = new HashMap<>();
        Mockito.when(ctx.getExternalContext().getRequestMap()).thenReturn(reqMap);
        try (MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class)) {
            faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);

            // Off a real Faces thread the EL answers null for every item, so the sort is a stable no-op --
            // what matters is that it neither NPEs nor loses elements.
            final List<String> sorted = new PersonCommands().toSortedList(
                    List.of("b", "a"), "loopItem");

            Assert.assertEquals(Set.copyOf(sorted), Set.copyOf(List.of("a", "b")));
        }
    }

    @Test
    public void hasRoleReadsTheSessionDirectlyAtTheEdges() {
        final jakarta.servlet.http.HttpSession session =
                Mockito.mock(jakarta.servlet.http.HttpSession.class);
        Mockito.when(session.getAttribute(PersonCommands.ACTIVE_USER_ROLE)).thenReturn("admin");

        Assert.assertTrue(PersonCommands.hasRole(session, "admin"));
        Assert.assertFalse(PersonCommands.hasRole(session, "user"));
        Assert.assertFalse(PersonCommands.hasRole(null, "admin"));
    }

    // --- BindingCommands ---

    @Test
    public void bindingsRoundTripAndTheBoundThingGetterFollowsTheFirst() {
        final BindingCommands bind = new BindingCommands();
        final String tripId = "bind-trip-" + System.nanoTime();
        final String eventId = "bind-evt-" + System.nanoTime();

        Assert.assertTrue(bind.setBindings(tripId, BindingType.TRIP, BindingType.TRIP_EVENT,
                List.of(eventId), true).isEmpty(), "nothing removed on first bind");

        Assert.assertEquals(bind.getBoundThing(tripId, "TRIP", BindingType.TRIP_EVENT, id -> id), eventId);
        Assert.assertNull(bind.getBoundThing("nothing-bound", "TRIP", BindingType.TRIP_EVENT, id -> id));

        // Replacing the binding reports what was removed.
        final String other = eventId + "-b";
        Assert.assertEquals(bind.setBindings(tripId, BindingType.TRIP, BindingType.TRIP_EVENT,
                List.of(other), true), List.of(eventId));
    }

    @Test
    public void compositeKeyGetterSplitsAndDelegates() {
        Assert.assertEquals(new BindingCommands().compositeKeyGetter("user-1,tx-9", (a, b) -> a + "|" + b),
                "user-1|tx-9");
    }

    // --- TransactionsCommands: the trip roll-up and the bound transaction ---

    @Test
    public void tripTransactionsRollUpEveryMembersLedger() throws Exception {
        final PersonCommands people = new PersonCommands();
        final Person member = people.createPerson();
        member.setFirst("Roll");
        member.setLast("Up");
        Assert.assertTrue(people.savePerson(member));
        final String tripId = "rollup-" + System.nanoTime();
        final Trip trip = Trip.builder().id(tripId).title("Rollup")
                .startDate(java.time.LocalDateTime.now()).endDate(java.time.LocalDateTime.now().plusDays(2))
                .people(List.of(member.getId())).build();
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveTrip(trip));
        final TransactionsCommands txs = new TransactionsCommands();
        final Transaction tx = new Transaction(null, member.getId(), null, null, null,
                java.time.LocalDateTime.now(), 25f, "cat", "note");
        Assert.assertTrue(txs.saveTransaction(tx));

        final List<Transaction> rolled = txs.getTripTransactions(tripId);

        Assert.assertTrue(rolled.stream().anyMatch(t -> t.getTxId().equals(tx.getTxId())));
        Assert.assertTrue(txs.getTripTransactions("no-such-trip-" + System.nanoTime()).isEmpty());
        Assert.assertTrue(txs.hasTransaction(member.getId(), tx.getTxId()));
        Assert.assertFalse(txs.hasTransaction(member.getId(), "no-such-tx"));
    }

    // --- AuditCommands: the one-line describers ---

    @Test
    public void theAuditDescribersRecordWithoutAFacesContext() {
        final AuditCommands audit = new AuditCommands();
        final Person target = new Person();
        target.setFirst("Aud");
        target.setLast("It");
        target.setEmail("audit-target@example.org");
        final Trip from = Trip.builder().id("f").title("From").build();
        final Trip to = Trip.builder().id("t").title("To").build();

        Assert.assertNotNull(audit.person(target, "EDITED"));
        Assert.assertNotNull(audit.loginChanged(target, "old@example.org"));
        Assert.assertNotNull(audit.credentialsRemoved(target));
        Assert.assertTrue(audit.registrationMoved(target, from, to).contains("From"));
        Assert.assertNotNull(audit.todoStatus(target, "Pack", "DONE"));
        Assert.assertNotNull(audit.transaction(target,
                new Transaction(Person.Id.from("payer"), null, null)));
    }
}
