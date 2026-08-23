package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class PrivilegeTest {
    @Test
    public void testGetName() {
        final String name = RandomData.genAlpha(14);
        final Privilege priv = new Privilege(name, RandomData.genAlpha(5), List.of());
        assertEquals(priv.getName(), name);
    }

    @Test
    public void globalPrivilegeHasNoTripId() {
        final Privilege priv = new Privilege("peopleAdmin", "desc", List.of());
        assertNull(priv.getTripId());
        assertEquals(priv.getName(), "peopleAdmin");
        assertTrue(priv.isGlobal());
    }

    @Test
    public void tripScopedPrivilegeSplitsNameAndTripId() {
        final String tripId = java.util.UUID.randomUUID().toString();
        final Privilege priv = new Privilege("tripMgr" + tripId, "desc", List.of());
        assertEquals(priv.getTripId(), tripId);
        assertEquals(priv.getName(), "tripMgr");
        assertFalse(priv.isGlobal());
        assertEquals(priv.getId(), "tripMgr" + tripId);
    }

    @Test
    public void idForBuildsIdentity() {
        final String tripId = java.util.UUID.randomUUID().toString();
        assertEquals(Privilege.idFor("tripView", tripId), "tripView" + tripId);
        assertEquals(Privilege.idFor("peopleAdmin", null), "peopleAdmin");
        assertEquals(Privilege.idFor("peopleAdmin", ""), "peopleAdmin");
    }

    @Test
    public void orgScopedPrivilegeRoundTripsLikeATripScopedOne() {
        // Org ids are canonical UUIDs by construction, so the same suffix parse must serve both scope kinds.
        final Organization.Id orgId = Organization.Id.newInstance();
        final Privilege priv = new Privilege(Privilege.idFor("peopleAdmin", orgId.getValue()), "desc", List.of());
        assertEquals(priv.getScopeId(), orgId.getValue());
        assertEquals(priv.getName(), "peopleAdmin");
        assertFalse(priv.isGlobal());
    }

    @Test
    public void tripIdIsAnAliasOfScopeId() {
        final String scopeId = java.util.UUID.randomUUID().toString();
        final Privilege scoped = new Privilege("tripMgr" + scopeId, "desc", List.of());
        assertEquals(scoped.getTripId(), scoped.getScopeId());
        final Privilege global = new Privilege("privilegeAdmin", "desc", List.of());
        assertNull(global.getScopeId());
        assertEquals(global.getTripId(), global.getScopeId());
    }

    @Test
    public void requireStorableScopeRefusesNonUuidScopes() {
        Privilege.requireStorableScope(null);
        Privilege.requireStorableScope("");
        Privilege.requireStorableScope(java.util.UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class, () -> Privilege.requireStorableScope("not-a-uuid"));
    }

    @Test
    public void testGetDescription() {
        final String desc = RandomData.genAlpha(14);
        final Privilege priv = new Privilege(
                RandomData.genAlpha(5), desc, FakeData.getFakePeople().stream().map(Person::getId).toList());
        assertEquals(priv.getDescription(), desc);
    }

    @Test
    public void testGetPeople() {
        final Person.Id id1 = FakeData.getFakePeople().get(0).getId();
        final Person.Id id2 = FakeData.getFakePeople().get(1).getId();
        final List<Person.Id> people = new ArrayList<>();
        people.add(id1);
        people.add(id2);
        final Privilege priv = new Privilege(RandomData.genAlpha(7), RandomData.genAlpha(5), people);
        assertEquals(priv.getPeople().size(), 2);
        assertEquals(priv.getPeople().get(0), id1);
        assertEquals(priv.getPeople().get(1), id2);
    }

    @Test
    public void canSerializePrivileges() throws Exception {
        final Privilege before = new Privilege(RandomData.genAlpha(5), RandomData.genAlpha(9),
                FakeData.getFakePeople().stream().map(Person::getId).toList());
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final String valueAsString = mapper.writeValueAsString(before);
        final Privilege after = mapper.readValue(valueAsString, Privilege.class);
        assertEquals(after, before);
    }

    @Test
    public void testTestEquals() {
        EqualsVerifier.forClass(Privilege.class).verify();
    }
}