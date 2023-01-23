package org.paulsens.trip.action;

import java.util.List;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class PrivilegeCommandsTest {
    private final PrivilegeCommands privCmds = new PrivilegeCommands();
    private final Person.Id noAccess = FakeData.getFakePeople().get(0).getId();
    private final Person.Id hasAccess1 = FakeData.getFakePeople().get(1).getId();
    private final Person.Id hasAccess2 = FakeData.getFakePeople().get(2).getId();

    @Test
    public void badGetPrivNameReturnsNull() {
        final String name = RandomData.genAlpha(15);
        privCmds.getPrivilege(name);
    }

    @Test
    public void canGetExistingPriv() {
        final Privilege before = getTestPriv();
        assertTrue(privCmds.savePrivilege(before));
        final Privilege after = privCmds.getPrivilege(before.getName());
        assertEquals(after, before);
    }

    @Test
    public void testCheck() {
        final Privilege priv = getTestPriv();
        assertTrue(privCmds.savePrivilege(priv));
        assertTrue(privCmds.check(priv.getName(), hasAccess1));
        assertTrue(privCmds.check(priv.getName(), hasAccess1));
        assertFalse(privCmds.check(priv.getName(), noAccess));
    }

    @Test
    public void testAdd() {
        final Privilege priv = getTestPriv();
        assertTrue(privCmds.savePrivilege(priv));
        assertFalse(privCmds.check(priv.getName(), noAccess));
        privCmds.add(priv.getName(), noAccess);
        assertTrue(privCmds.check(priv.getName(), noAccess));
    }

    @Test
    public void testRemove() {
        final Privilege priv = getTestPriv();
        assertEquals(priv.getPeople().size(), 2);
        assertTrue(privCmds.savePrivilege(priv));
        assertTrue(privCmds.check(priv.getName(), hasAccess2));
        privCmds.remove(priv.getName(), hasAccess2);
        assertFalse(privCmds.check(priv.getName(), hasAccess2));
        final Privilege after = privCmds.getPrivilege(priv.getName());
        assertEquals(after.getPeople().size(), 1);
        assertFalse(privCmds.check(priv.getName(), hasAccess2));
        assertFalse(privCmds.check(priv.getName(), noAccess));
        assertTrue(privCmds.check(priv.getName(), hasAccess1));
    }

    private Privilege getTestPriv() {
        return new Privilege(RandomData.genAlpha(8), RandomData.genAlpha(18), List.of(hasAccess1, hasAccess2));
    }
}