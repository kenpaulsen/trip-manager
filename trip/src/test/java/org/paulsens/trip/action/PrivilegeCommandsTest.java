package org.paulsens.trip.action;

import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class PrivilegeCommandsTest {
    private final PrivilegeCommands privCmds = new PrivilegeCommands();
    private final Person.Id noAccess = FakeData.getFakePeople().get(0).getId();
    private final Person.Id hasAccess1 = FakeData.getFakePeople().get(1).getId();
    private final Person.Id hasAccess2 = FakeData.getFakePeople().get(2).getId();

    @Test
    public void deleteRemovesExactlyOneRowAndAnswersFalseOnAMiss() {
        final String scope = java.util.UUID.randomUUID().toString();
        final Privilege doomed = privCmds.createPrivilege("del" + RandomData.genAlpha(8), "doomed", scope,
                List.of(hasAccess1));
        final Privilege survivor = privCmds.createPrivilege("keep" + RandomData.genAlpha(8), "kept", scope,
                List.of(hasAccess2));
        assertTrue(privCmds.savePrivilege(doomed));
        assertTrue(privCmds.savePrivilege(survivor));

        assertTrue(privCmds.deletePrivilege(doomed.getName(), scope));
        assertEquals(privCmds.getPrivilege(doomed.getName(), scope), Privilege.NONE, "The row is gone");
        assertEquals(privCmds.getPrivilege(survivor.getName(), scope).getPeople(), List.of(hasAccess2),
                "Its partition neighbour survives (removeOne, never a partition invalidate)");
        assertFalse(privCmds.deletePrivilege(doomed.getName(), scope), "Deleting a miss reports false");
        assertFalse(privCmds.deletePrivilege("  ", scope));
    }

    @Test
    public void knownBasesAnswerPerScopeKind() {
        assertEquals(privCmds.knownBases("GLOBAL"), PrivilegeCommands.GLOBAL_BASES);
        assertEquals(privCmds.knownBases("TRIP"), PrivilegeCommands.TRIP_SCOPED_BASES);
        assertEquals(privCmds.knownBases("ORG"), PrivilegeCommands.ORG_SCOPED_BASES);
        assertEquals(privCmds.knownBases(null), PrivilegeCommands.GLOBAL_BASES);
    }

    @Test
    public void everyCanonicalBaseHasADescriptionAndCustomNamesHaveNone() {
        for (final String base : privCmds.knownBases("GLOBAL")) {
            assertFalse(privCmds.baseDescription(base).isBlank(), "Missing description: " + base);
        }
        for (final String base : privCmds.knownBases("TRIP")) {
            assertFalse(privCmds.baseDescription(base).isBlank(), "Missing description: " + base);
        }
        for (final String base : privCmds.knownBases("ORG")) {
            assertFalse(privCmds.baseDescription(" " + base + " ").isBlank(), "Untrimmed lookup: " + base);
        }
        assertEquals(privCmds.baseDescription("someCustomEditorPriv"), "");
        assertEquals(privCmds.baseDescription(null), "");
    }

    @Test
    public void badGetPrivNameReturnsNull() {
        final String name = RandomData.genAlpha(15);
        privCmds.getPrivilege(name, null);
    }

    @Test
    public void canGetExistingPriv() {
        final Privilege before = getTestPriv();
        assertTrue(privCmds.savePrivilege(before));
        final Privilege after = privCmds.getPrivilege(before.getName(), null);
        assertEquals(after, before);
    }

    @Test
    public void testCheck() {
        final Privilege priv = getTestPriv();
        assertTrue(privCmds.savePrivilege(priv));
        assertTrue(privCmds.check(priv.getName(), null, hasAccess1));
        assertTrue(privCmds.check(priv.getName(), null, hasAccess1));
        assertFalse(privCmds.check(priv.getName(), null, noAccess));
    }

    @Test
    public void testAdd() {
        final Privilege priv = getTestPriv();
        assertTrue(privCmds.savePrivilege(priv));
        assertFalse(privCmds.check(priv.getName(), null, noAccess));
        privCmds.add(priv.getName(), null, noAccess);
        assertTrue(privCmds.check(priv.getName(), null, noAccess));
    }

    @Test
    public void testRemove() {
        final Privilege priv = getTestPriv();
        assertEquals(priv.getPeople().size(), 2);
        assertTrue(privCmds.savePrivilege(priv));
        assertTrue(privCmds.check(priv.getName(), null, hasAccess2));
        privCmds.remove(priv.getName(), null, hasAccess2);
        assertFalse(privCmds.check(priv.getName(), null, hasAccess2));
        final Privilege after = privCmds.getPrivilege(priv.getName(), null);
        assertEquals(after.getPeople().size(), 1);
        assertFalse(privCmds.check(priv.getName(), null, hasAccess2));
        assertFalse(privCmds.check(priv.getName(), null, noAccess));
        assertTrue(privCmds.check(priv.getName(), null, hasAccess1));
    }

    @Test
    public void canGetGlobalPrivs() {
        DAO.getInstance().clearAllCaches();
        // Test privileges have plain (non-UUID) names, so they land in the global partition.
        final Privilege priv1 = getTestPriv();
        assertTrue(privCmds.savePrivilege(priv1));
        final Privilege priv2 = getTestPriv();
        assertTrue(privCmds.savePrivilege(priv2));
        final List<Privilege> privs = privCmds.getGlobalPrivileges();
        assertEquals(privs.size(), 2);
        assertTrue(privs.contains(priv1));
        assertTrue(privs.contains(priv2));
    }

    @Test
    public void tripScopedPrivsListByTrip() {
        DAO.getInstance().clearAllCaches();
        final String tripId = java.util.UUID.randomUUID().toString();
        final Privilege tripPriv = privCmds.createPrivilege("tripMgr", "Manage", tripId, List.of(hasAccess1));
        assertTrue(privCmds.savePrivilege(tripPriv));
        final Privilege globalPriv = getTestPriv();
        assertTrue(privCmds.savePrivilege(globalPriv));
        // The trip partition holds only the trip privilege; the global partition holds only the global one.
        final List<Privilege> tripPrivs = privCmds.getTripPrivileges(tripId);
        assertEquals(tripPrivs.size(), 1);
        assertEquals(tripPrivs.get(0).getName(), "tripMgr");
        assertEquals(tripPrivs.get(0).getTripId(), tripId);
        assertTrue(privCmds.getGlobalPrivileges().contains(globalPriv));
        assertFalse(privCmds.getGlobalPrivileges().contains(tripPriv));
    }

    @Test
    public void canCreateAGlobalPrivilege() {
        final String name = RandomData.genAlpha(5);
        final String description = RandomData.genAlpha(7);
        final Privilege priv = privCmds.createPrivilege(
                name, description, null, List.of(hasAccess1, hasAccess2, noAccess));
        assertNotNull(priv);
        assertEquals(priv.getPeople().size(), 3);
        assertEquals(priv.getName(), name);
        assertNull(priv.getTripId());
        assertEquals(priv.getDescription(), description);
    }

    @Test
    public void createTripPrivilegeAppendsTripId() {
        final String tripId = java.util.UUID.randomUUID().toString();
        final Privilege priv = privCmds.createPrivilege("tripView", "desc", tripId, List.of(hasAccess1));
        assertEquals(priv.getName(), "tripView");
        assertEquals(priv.getTripId(), tripId);
        assertEquals(priv.getId(), "tripView" + tripId);
    }

    @Test
    public void tripScopedCheckAddRemoveUseNameAndTripId() {
        DAO.getInstance().clearAllCaches();
        final String tripId = java.util.UUID.randomUUID().toString();
        assertTrue(privCmds.savePrivilege(privCmds.createPrivilege("tripMgr", "desc", tripId, List.of(hasAccess1))));
        // Same base name resolves per-trip; the caller never builds the combined key.
        assertTrue(privCmds.check("tripMgr", tripId, hasAccess1));
        assertFalse(privCmds.check("tripMgr", tripId, noAccess));
        // A different trip's privilege of the same base name is independent.
        assertFalse(privCmds.check("tripMgr", java.util.UUID.randomUUID().toString(), hasAccess1));
        assertTrue(privCmds.add("tripMgr", tripId, noAccess));
        assertTrue(privCmds.check("tripMgr", tripId, noAccess));
        assertTrue(privCmds.remove("tripMgr", tripId, noAccess));
        assertFalse(privCmds.check("tripMgr", tripId, noAccess));
        assertEquals(privCmds.getPrivilege("tripMgr", tripId).getTripId(), tripId);
        assertEquals(privCmds.getOrCreate("tripMgr", tripId, "x").getPeople(), List.of(hasAccess1));
    }

    private Privilege getTestPriv() {
        return new Privilege(RandomData.genAlpha(8), RandomData.genAlpha(18), List.of(hasAccess1, hasAccess2));
    }
}
