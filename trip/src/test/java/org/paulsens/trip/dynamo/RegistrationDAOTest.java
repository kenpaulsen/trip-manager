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
        dao = new RegistrationDAO(new ObjectMapper().findAndRegisterModules(), FakeData.createFakePersistence());
    }

    @Test
    public void saveAndRetrieveRegistration() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        final Person.Id userId = Person.Id.newInstance();
        final Registration reg = new Registration(tripId, userId);
        assertTrue(get(dao.saveRegistration(reg)));
        final Optional<Registration> found = get(dao.getRegistration(tripId, userId));
        assertTrue(found.isPresent());
        assertEquals(found.get().getTripId(), tripId);
        assertEquals(found.get().getUserId(), userId);
    }

    @Test
    public void getRegistrationsReturnsEmptyListForUnknownTrip() {
        final List<Registration> regs = get(dao.getRegistrations(RandomData.genAlpha(10)));
        assertTrue(regs.isEmpty());
    }

    @Test
    public void getRegistrationReturnsEmptyForUnknownUser() {
        final String tripId = RandomData.genAlpha(10);
        final Optional<Registration> result = get(dao.getRegistration(tripId, Person.Id.newInstance()));
        assertTrue(result.isEmpty());
    }

    @Test
    public void multipleRegistrationsForSameTrip() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        final Person.Id user1 = Person.Id.newInstance();
        final Person.Id user2 = Person.Id.newInstance();
        final Person.Id user3 = Person.Id.newInstance();
        get(dao.saveRegistration(new Registration(tripId, user1)));
        get(dao.saveRegistration(new Registration(tripId, user2)));
        get(dao.saveRegistration(new Registration(tripId, user3)));
        final List<Registration> regs = get(dao.getRegistrations(tripId));
        assertEquals(regs.size(), 3);
    }

    @Test
    public void registrationsForDifferentTripsAreIsolated() throws IOException {
        final String trip1 = RandomData.genAlpha(10);
        final String trip2 = RandomData.genAlpha(10);
        final Person.Id user = Person.Id.newInstance();
        get(dao.saveRegistration(new Registration(trip1, user)));
        get(dao.saveRegistration(new Registration(trip2, user)));
        assertEquals(get(dao.getRegistrations(trip1)).size(), 1);
        assertEquals(get(dao.getRegistrations(trip2)).size(), 1);
    }

    @Test
    public void saveRegistrationIsIdempotent() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        final Person.Id userId = Person.Id.newInstance();
        final Registration reg = new Registration(tripId, userId);
        get(dao.saveRegistration(reg));
        get(dao.saveRegistration(reg));
        assertEquals(get(dao.getRegistrations(tripId)).size(), 1);
    }

    @Test
    public void registrationStatusIsPreserved() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        final Person.Id userId = Person.Id.newInstance();
        final Registration confirmed = new Registration(tripId, userId).withStatus(Registration.Status.CONFIRMED);
        get(dao.saveRegistration(confirmed));
        final Registration retrieved = get(dao.getRegistration(tripId, userId)).orElse(null);
        assertNotNull(retrieved);
        assertEquals(retrieved.getStatus(), Registration.Status.CONFIRMED);
    }

    @Test
    public void clearCacheWorks() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        get(dao.saveRegistration(new Registration(tripId, Person.Id.newInstance())));
        assertEquals(get(dao.getRegistrations(tripId)).size(), 1);
        dao.clearCache();
        // After clearing, query returns empty from fake persistence
        assertEquals(get(dao.getRegistrations(tripId)).size(), 0);
    }

    @Test
    public void updatingRegistrationReplacesInCache() throws IOException {
        final String tripId = RandomData.genAlpha(10);
        final Person.Id userId = Person.Id.newInstance();
        get(dao.saveRegistration(new Registration(tripId, userId)));
        assertEquals(get(dao.getRegistration(tripId, userId)).get().getStatus(), Registration.Status.NOT_REGISTERED);
        final Registration updated = new Registration(tripId, userId).withStatus(Registration.Status.CONFIRMED);
        get(dao.saveRegistration(updated));
        assertEquals(get(dao.getRegistration(tripId, userId)).get().getStatus(), Registration.Status.CONFIRMED);
        assertEquals(get(dao.getRegistrations(tripId)).size(), 1);
    }

    private <T> T get(final CompletableFuture<T> future) {
        try {
            return future.get(1_000, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException | ExecutionException | TimeoutException ex) {
            throw new RuntimeException(ex);
        }
    }
}
