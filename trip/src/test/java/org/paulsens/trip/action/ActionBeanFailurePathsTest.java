package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The action beans when the store FAILS.
 *
 * <p>Every bean carries the same contract in its {@code exceptionally} handlers: a failed DAO future becomes a
 * FacesMessage plus a harmless fallback (false, null, or empty), never an exception up into JSF -- an exception
 * from EL renders as a blank page with no message at all, which is the worst possible way to report "the
 * database hiccuped". These handlers were almost entirely unexecuted: the fake store never fails, so only a
 * mocked one can prove the contract.
 */
public class ActionBeanFailurePathsTest {

    private MockedStatic<DAO> daoStatic;
    private DAO dao;

    @BeforeMethod
    public void failEveryRead() {
        // Since the virtual-threads port DAO methods are direct calls, so "the store is down" is a THROW --
        // exactly what the beans' try/catch fallbacks (formerly exceptionally handlers) must absorb.
        dao = Mockito.mock(DAO.class, invocation -> {
            throw new IllegalStateException("store is down");
        });
        daoStatic = Mockito.mockStatic(DAO.class);
        daoStatic.when(DAO::getInstance).thenReturn(dao);
    }

    @AfterMethod(alwaysRun = true)
    public void restore() {
        daoStatic.close();
    }

    private static Trip trip() {
        return Trip.builder().id("fail-trip").title("T")
                .startDate(LocalDateTime.now()).endDate(LocalDateTime.now().plusDays(3)).build();
    }

    @Test
    public void tripReadsFailToEmptyAndWritesToFalse() {
        final TripCommands trips = new TripCommands();

        Assert.assertFalse(trips.saveTrip(trip()));
        // The known blank-object contract: a failed read answers a BLANK trip with a minted id, never null.
        final Trip blank = trips.getTrip("t1");
        Assert.assertNotNull(blank);
        Assert.assertNotEquals(blank.getId(), "t1");
        Assert.assertTrue(trips.getActiveTrips(30).isEmpty());
        Assert.assertTrue(trips.getInactiveTrips(Person.Id.from("u"), true, 30, 10).isEmpty());
        Assert.assertTrue(trips.getRecentTrips(5).isEmpty());
        Assert.assertTrue(trips.getTripsForUser(Person.Id.from("u")).isEmpty());
        Assert.assertNull(trips.getTripEvent("evt-1"));
    }

    /** Credential lookups fail soft to null; a privilege save failure maps to false (both logged). */
    @Test
    public void credentialAndPrivilegeFailureTails() {
        final PassCommands pass = new PassCommands();
        Assert.assertNull(pass.getCreds("who@example.org", "pw"),
                "a store failure during login must answer null, not blank-page JSF");
        Assert.assertNull(pass.getCredsByAdmin("who@example.org", Person.Id.from("u")));

        final PrivilegeCommands privs = new PrivilegeCommands();
        Assert.assertFalse(privs.savePrivilege(
                        new org.paulsens.trip.model.Privilege("failPriv", "d", java.util.List.of()),
                        org.paulsens.trip.audit.AuditActor.system()),
                "a refused privilege write maps to false");
    }

    @Test
    public void personReadsFailSoftExceptByEmailWhichRethrows() {
        final PersonCommands people = new PersonCommands();

        Assert.assertFalse(people.savePerson(new Person()));
        // Deliberately different from the rest: a store failure during login lookup must not read as
        // "no such account", so this one rethrows rather than answering null.
        Assert.assertThrows(IllegalStateException.class,
                () -> people.getPersonByEmail("x@example.org"));
        Assert.assertTrue(people.searchPeople("ali").isEmpty());
    }

    @Test
    public void transactionOperationsFailSoft() {
        final TransactionsCommands txs = new TransactionsCommands();
        final Person.Id user = Person.Id.from("u");

        Assert.assertFalse(txs.saveTransaction(new Transaction(user, null, null)));
        Assert.assertTrue(txs.getTransactions(user).isEmpty());
        Assert.assertNull(txs.getTransaction(user, "tx-1"));
        Assert.assertTrue(txs.getTripTransactions("trip-1").isEmpty(),
                "the trip ledger fails soft like its sibling reads (aligned 2026-08-04)");
    }

    @Test
    public void registrationOperationsFailSoft() {
        final RegistrationCommands regs = new RegistrationCommands();
        final Person.Id user = Person.Id.from("u");

        Assert.assertFalse(regs.saveRegistration(regs.createRegistration("trip-1", user)));
        Assert.assertTrue(regs.getRegistrations("trip-1").isEmpty());
        // Same blank-object contract as getTrip: a failed read answers a fresh PENDING registration.
        final Registration fallback = regs.getRegistration("trip-1", user);
        Assert.assertNotNull(fallback);
        Assert.assertNull(regs.getRegistration(null, user));
        Assert.assertNull(regs.getRegistration("trip-1", null));
    }

    @Test
    public void todoOperationsFailSoft() {
        final TodoCommands todos = new TodoCommands();

        Assert.assertTrue(todos.getTodos("trip-1").isEmpty());
        Assert.assertNull(todos.getTodo("trip-1", DataId.from("todo-1")));
    }

    @Test
    public void personDataValueOperationsFailSoft() throws Exception {
        Assert.assertFalse(PersonDataValueCommands.savePersonDataValue(
                PersonDataValueCommands.createPersonDataValue(
                        Person.Id.from("u"), DataId.from("d"), "type")));
        Assert.assertTrue(PersonDataValueCommands.getPersonDataValues(Person.Id.from("u")).isEmpty());
        Assert.assertNull(PersonDataValueCommands.getPersonDataValue(Person.Id.from("u"), DataId.from("d")));

        // The checked-IOException catch as well as the failed-future path.
        Mockito.doThrow(new java.io.IOException("cannot serialize")).when(dao)
                .savePersonDataValue(Mockito.any());
        Assert.assertFalse(PersonDataValueCommands.savePersonDataValue(
                PersonDataValueCommands.createPersonDataValue(
                        Person.Id.from("u"), DataId.from("d"), "type")));
    }

    /** Binding writes fail to false (with a warning), and setBindings reports nothing removed. */
    @Test
    public void bindingOperationsFailSoft() {
        final BindingCommands bind = new BindingCommands();

        Assert.assertFalse(bind.saveBinding("id", org.paulsens.trip.model.BindingType.TRIP, "d",
                org.paulsens.trip.model.BindingType.TRIP_EVENT, true));
        Assert.assertFalse(bind.removeBinding("id", org.paulsens.trip.model.BindingType.TRIP, "d",
                org.paulsens.trip.model.BindingType.TRIP_EVENT, true));
        Assert.assertTrue(bind.setBindings("id", org.paulsens.trip.model.BindingType.TRIP,
                org.paulsens.trip.model.BindingType.TRIP_EVENT, List.of("d1"), true).isEmpty(),
                "with every save failing, nothing was removed and nothing throws");
    }

    /** The checked-IOException catches: a store that THROWS (not fails a future) still maps to false. */
    @Test
    public void aThrowingStoreHitsTheIoExceptionCatches() throws Exception {
        Mockito.doThrow(new java.io.IOException("cannot serialize")).when(dao)
                .saveTrip(Mockito.any());
        Mockito.doThrow(new java.io.IOException("cannot serialize")).when(dao)
                .savePerson(Mockito.any());
        Mockito.doThrow(new java.io.IOException("cannot serialize")).when(dao)
                .saveTransaction(Mockito.any());
        Mockito.doThrow(new java.io.IOException("cannot serialize")).when(dao)
                .saveRegistration(Mockito.any());

        Assert.assertFalse(new TripCommands().saveTrip(trip()));
        Assert.assertFalse(new PersonCommands().savePerson(new Person()));
        Assert.assertFalse(new TransactionsCommands()
                .saveTransaction(new Transaction(Person.Id.from("u"), null, null)));
        final RegistrationCommands regs = new RegistrationCommands();
        Assert.assertFalse(regs.saveRegistration(regs.createRegistration("t", Person.Id.from("u"))));
    }

    @Test
    public void configReadsSurviveAFailingStoreMidRender() {
        final ConfigCommands config = new ConfigCommands();

        // A settings lookup happens mid-render and must never break the page.
        Assert.assertEquals(config.getString("chat.digest.zone", "UTC"), "UTC");
        Assert.assertTrue(config.getAll().isEmpty());
        Assert.assertFalse(config.save(new org.paulsens.trip.model.Config(
                "chat.digest.enabled", "true", org.paulsens.trip.model.Config.Type.BOOLEAN, null, null, null),
                "admin"));
    }

    @Test
    public void mediaListingsFailToEmptyAndLookupsToNull() {
        final MediaCommands media = new MediaCommands();

        Assert.assertTrue(media.getAll().isEmpty());
        Assert.assertTrue(media.getInSlot("homepage").isEmpty());
        Assert.assertNull(media.get("id-1"));
    }
}
