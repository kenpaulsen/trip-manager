package org.paulsens.trip.dynamo;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class PrivilegesDAOTest {

    @Test
    public void canGetAndSavePrivilege() throws Exception {
        final PrivilegesDAO dao = new PrivilegesDAO(DAO.getInstance().getMapper(), FakeData.createFakePersistence());
        final Privilege priv = getTestPriv();
        assertTrue(dao.getPrivilege(priv.getName()).get(1_000, TimeUnit.MILLISECONDS).isEmpty());
        assertTrue(dao.savePrivilege(priv).get(1_000, TimeUnit.MILLISECONDS));
        assertEquals(dao.getPrivilege(priv.getName()).get(1_000, TimeUnit.MILLISECONDS).get(), priv);
    }

    private Privilege getTestPriv() {
        final String name = RandomData.genAlpha(12);
        final String description = RandomData.genAlpha(42);
        final Person person1 = FakeData.getFakePeople().get(0);
        final Person person2 = FakeData.getFakePeople().get(1);
        return new Privilege(name, description, List.of(person1.getId(), person2.getId()));
    }
}