package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class RegistrationDAOTest {
    private RegistrationDAO dao;

    @BeforeMethod
    public void setup() {
        dao = new RegistrationDAO(new ObjectMapper().findAndRegisterModules(), DynamoLocal.persistence());
    }

    @Test
    public void saveAndRetrieveRegistration() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        final Person.Id userId = Person.Id.newInstance();
        final Registration reg = new Registration(tripId, userId);
        assertTrue(dao.saveRegistration(reg));
        final Optional<Registration> found = dao.getRegistration(tripId, userId);
        assertTrue(found.isPresent());
        assertEquals(found.get().getTripId(), tripId);
        assertEquals(found.get().getUserId(), userId);
    }

    @Test
    public void getRegistrationsReturnsEmptyListForUnknownTrip() {
        final List<Registration> regs = dao.getRegistrations(RandomData.genAlpha(10));
        assertTrue(regs.isEmpty());
    }

    @Test
    public void getRegistrationReturnsEmptyForUnknownUser() {
        final String tripId = RandomData.genAlpha(10);
        final Optional<Registration> result = dao.getRegistration(tripId, Person.Id.newInstance());
        assertTrue(result.isEmpty());
    }

    @Test
    public void multipleRegistrationsForSameTrip() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        final Person.Id user1 = Person.Id.newInstance();
        final Person.Id user2 = Person.Id.newInstance();
        final Person.Id user3 = Person.Id.newInstance();
        dao.saveRegistration(new Registration(tripId, user1));
        dao.saveRegistration(new Registration(tripId, user2));
        dao.saveRegistration(new Registration(tripId, user3));
        final List<Registration> regs = dao.getRegistrations(tripId);
        assertEquals(regs.size(), 3);
    }

    @Test
    public void registrationsForDifferentTripsAreIsolated() throws IOException {
        final String trip1 = RandomData.genAlpha(10);
        final String trip2 = RandomData.genAlpha(10);
        final Person.Id user = Person.Id.newInstance();
        dao.saveRegistration(new Registration(trip1, user));
        dao.saveRegistration(new Registration(trip2, user));
        assertEquals(dao.getRegistrations(trip1).size(), 1);
        assertEquals(dao.getRegistrations(trip2).size(), 1);
    }

    @Test
    public void saveRegistrationIsIdempotent() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        final Person.Id userId = Person.Id.newInstance();
        final Registration reg = new Registration(tripId, userId);
        dao.saveRegistration(reg);
        dao.saveRegistration(reg);
        assertEquals(dao.getRegistrations(tripId).size(), 1);
    }

    @Test
    public void registrationStatusIsPreserved() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        final Person.Id userId = Person.Id.newInstance();
        final Registration confirmed = new Registration(tripId, userId).withStatus(Registration.Status.CONFIRMED);
        dao.saveRegistration(confirmed);
        final Registration retrieved = dao.getRegistration(tripId, userId).orElse(null);
        assertNotNull(retrieved);
        assertEquals(retrieved.getStatus(), Registration.Status.CONFIRMED);
    }

    /**
     * Clearing the cache must not lose data.
     *
     * <p>This asserted the OPPOSITE until the DAO tests moved onto a real engine: that the row was GONE after a
     * clear. That was true only because the fake persistence stored nothing, so the cache WAS the store. The
     * real invariant is that a clear drops the cached copy and the next read is served by the store.
     */
    @Test
    public void clearingTheCacheDoesNotLoseTheRow() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        dao.saveRegistration(new Registration(tripId, Person.Id.newInstance()));
        assertEquals(dao.getRegistrations(tripId).size(), 1);

        dao.clearCache();

        assertEquals(dao.getRegistrations(tripId).size(), 1);
    }

    @Test
    public void updatingRegistrationReplacesInCache() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        final Person.Id userId = Person.Id.newInstance();
        dao.saveRegistration(new Registration(tripId, userId));
        assertEquals(dao.getRegistration(tripId, userId).get().getStatus(), Registration.Status.NOT_REGISTERED);
        final Registration updated = new Registration(tripId, userId).withStatus(Registration.Status.CONFIRMED);
        dao.saveRegistration(updated);
        assertEquals(dao.getRegistration(tripId, userId).get().getStatus(), Registration.Status.CONFIRMED);
        assertEquals(dao.getRegistrations(tripId).size(), 1);
    }
    }

