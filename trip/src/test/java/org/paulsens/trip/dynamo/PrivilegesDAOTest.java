package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class PrivilegesDAOTest {
    private PrivilegesDAO dao;

    @BeforeClass
    public void init() {
        FakeData.initFakeData();
    }

    @BeforeMethod
    public void setup() {
        dao = new PrivilegesDAO(new ObjectMapper().findAndRegisterModules(), FakeData.createFakePersistence());
    }

    @Test
    public void canGetAndSavePrivilege() {
        final Privilege priv = getTestPriv();
        assertTrue(get(dao.getPrivilege(priv.getName())).isEmpty());
        assertTrue(get(dao.savePrivilege(priv)));
        assertEquals(get(dao.getPrivilege(priv.getName())).get(), priv);
    }

    @Test
    public void getPrivilegeReturnsEmptyForUnknown() {
        assertTrue(get(dao.getPrivilege("nonexistent-" + RandomData.genAlpha(10))).isEmpty());
    }

    @Test
    public void saveAndRetrieveMultiplePrivileges() {
        final Privilege p1 = new Privilege("alpha-" + RandomData.genAlpha(5), "desc1", List.of());
        final Privilege p2 = new Privilege("beta-" + RandomData.genAlpha(5), "desc2", List.of());
        get(dao.savePrivilege(p1));
        get(dao.savePrivilege(p2));
        assertEquals(get(dao.getPrivilege(p1.getName())), Optional.of(p1));
        assertEquals(get(dao.getPrivilege(p2.getName())), Optional.of(p2));
    }

    @Test
    public void getPrivilegesReturnsEmptyInitially() {
        final List<Privilege> privs = get(dao.getPrivileges());
        assertTrue(privs.isEmpty());
    }

    @Test
    public void getPrivilegesReturnsSortedByName() {
        // First call getPrivileges to trigger the initial scan (returns empty from fake persistence)
        // This sets hasScanned=true, so subsequent getPrivileges calls use the cache
        get(dao.getPrivileges());
        // Now saves will add to the non-empty cache (cacheOne only adds when cache is non-empty,
        // but after the scan, the cache map object exists even if empty, so we need to seed it)
        // savePrivilege uses cacheOne which requires a non-null cache, but won't add to completely empty map
        // because the cache pattern checks if the cache map is non-empty first.
        // Instead, we test via getPrivilege (which does a direct getItem lookup)
        get(dao.savePrivilege(new Privilege("Zebra", "z", List.of())));
        get(dao.savePrivilege(new Privilege("Alpha", "a", List.of())));
        get(dao.savePrivilege(new Privilege("Middle", "m", List.of())));
        // getPrivileges now uses cache (hasScanned=true) and returns sorted
        final List<Privilege> privs = get(dao.getPrivileges());
        assertEquals(privs.size(), 3);
        assertEquals(privs.get(0).getName(), "Alpha");
        assertEquals(privs.get(1).getName(), "Middle");
        assertEquals(privs.get(2).getName(), "Zebra");
    }

    @Test
    public void savePrivilegeIsIdempotent() {
        final Privilege priv = new Privilege("idem-" + RandomData.genAlpha(5), "desc", List.of());
        get(dao.savePrivilege(priv));
        get(dao.savePrivilege(priv));
        assertEquals(get(dao.getPrivilege(priv.getName())), Optional.of(priv));
    }

    @Test
    public void clearCacheWorks() {
        final Privilege priv = new Privilege("clear-" + RandomData.genAlpha(5), "desc", List.of());
        get(dao.savePrivilege(priv));
        assertTrue(get(dao.getPrivilege(priv.getName())).isPresent());
        dao.clearCache();
        // After clearing, getPrivilege goes to persistence (returns null for non-pass tables)
        assertTrue(get(dao.getPrivilege(priv.getName())).isEmpty());
    }

    @Test
    public void privilegeWithPeopleIsPreserved() {
        final Person.Id p1 = Person.Id.newInstance();
        final Person.Id p2 = Person.Id.newInstance();
        final Privilege priv = new Privilege("withpeople-" + RandomData.genAlpha(5), "desc", List.of(p1, p2));
        get(dao.savePrivilege(priv));
        final Privilege found = get(dao.getPrivilege(priv.getName())).orElse(null);
        assertNotNull(found);
        assertEquals(found.getPeople().size(), 2);
        assertTrue(found.getPeople().contains(p1));
        assertTrue(found.getPeople().contains(p2));
    }

    private Privilege getTestPriv() {
        final String name = RandomData.genAlpha(12);
        final String description = RandomData.genAlpha(42);
        final Person person1 = FakeData.getFakePeople().get(0);
        final Person person2 = FakeData.getFakePeople().get(1);
        return new Privilege(name, description, List.of(person1.getId(), person2.getId()));
    }

    private <T> T get(final CompletableFuture<T> future) {
        try {
            return future.get(1_000, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException | ExecutionException | TimeoutException ex) {
            throw new RuntimeException(ex);
        }
    }
}