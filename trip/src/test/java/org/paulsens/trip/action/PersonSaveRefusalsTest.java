package org.paulsens.trip.action;

import java.io.IOException;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * The ways {@link PersonCommands#savePerson} answers false rather than throwing: a page reads the boolean, and
 * an exception out of a save redirects it home and throws away whatever the person had typed.
 */
public class PersonSaveRefusalsTest {

    private final PersonCommands people = new PersonCommands(TenancyFixtures::asSiteAdmin);

    @Test
    public void anAddressThatBelongsToSomeoneElseIsRefused() {
        final Person holder = TenancyFixtures.savedPerson("Holder");
        final Person newcomer = Person.builder().first("New").last("Comer").email(holder.getEmail()).build();

        assertFalse(people.savePerson(newcomer), "one address, one person");
        assertTrue(DAO.getInstance().getPerson(newcomer.getId(), Cached.NO).isEmpty(), "nothing was written");
    }

    @Test
    public void aStoreThatFailsEitherWayIsAFailedSaveNotAnException() throws IOException {
        final Person person = TenancyFixtures.savedPerson("Unlucky");
        final DAO failing = Mockito.spy(DAO.getInstance());
        try (MockedStatic<DAO> dao = Mockito.mockStatic(DAO.class, Mockito.CALLS_REAL_METHODS)) {
            dao.when(DAO::getInstance).thenReturn(failing);

            Mockito.doThrow(new IllegalStateException("throttled")).when(failing).savePerson(Mockito.any());
            assertFalse(people.savePerson(person));

            Mockito.doThrow(new IOException("unserializable")).when(failing).savePerson(Mockito.any());
            assertFalse(people.savePerson(person));
        }
    }
}
