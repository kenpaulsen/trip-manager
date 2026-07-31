package org.paulsens.trip.action;

import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Saving a person's room for a trip.
 *
 * <p>The bug: the rooms page bound its input to {@code getRoomPDV(...).content} and then called a no-value
 * {@code saveRoom}, which looked the record up <b>again</b> and saved that. Two lookups return two objects --
 * since the persistence redesign a read deserializes a fresh copy instead of returning a shared instance -- so
 * the typed value was set on an object nobody stored. Save reported success and the room reverted on reload.
 *
 * <p>{@link #savingDoesNotDependOnAPreviouslyReturnedObject} is the one that matters: it saves through a value
 * rather than through an object, which is the property that makes the page correct no matter how many copies of
 * the record exist.
 */
public class RoomAssignmentTest {

    private final RegistrationCommands reg = new RegistrationCommands();

    @BeforeClass
    void beforeClass() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    private static String tripId() {
        return FakeData.getFakeTrips().get(0).getId();
    }

    private static Person.Id someone(final int index) {
        return FakeData.getFakePeople().get(index).getId();
    }

    @Test
    public void aRoomSurvivesAFreshLookup() {
        final Person.Id who = someone(0);
        Assert.assertTrue(reg.saveRoom(tripId(), who, "214"));
        // Deliberately re-read rather than reusing the object above: that is what the next page load does.
        Assert.assertEquals(reg.getRoomPDV(tripId(), who).getContent(), "214");
    }

    @Test
    public void savingDoesNotDependOnAPreviouslyReturnedObject() {
        final Person.Id who = someone(1);
        final PersonDataValue stale = reg.getRoomPDV(tripId(), who);
        // Mutating a previously-returned record is exactly what the page used to do, and it must not be what
        // makes the save work -- nor may it interfere with a save that passes the value properly.
        stale.setContent("this was never stored");

        Assert.assertTrue(reg.saveRoom(tripId(), who, "301"));
        Assert.assertEquals(reg.getRoomPDV(tripId(), who).getContent(), "301");
    }

    @Test
    public void oneRoomEditDoesNotDisturbAnother() {
        // The page saves every pilgrim in a loop; keying the value by the wrong person is the failure that
        // the page's own comment warns about ("rooms can get saved to the wrong people").
        final Person.Id first = someone(2);
        final Person.Id second = someone(3);
        reg.saveRoom(tripId(), first, "101");
        reg.saveRoom(tripId(), second, "102");

        Assert.assertEquals(reg.getRoomPDV(tripId(), first).getContent(), "101");
        Assert.assertEquals(reg.getRoomPDV(tripId(), second).getContent(), "102");
    }

    @Test
    public void clearingARoomStoresEmptyRatherThanFailing() {
        final Person.Id who = someone(4);
        reg.saveRoom(tripId(), who, "410");
        Assert.assertTrue(reg.saveRoom(tripId(), who, null));
        Assert.assertEquals(reg.getRoomPDV(tripId(), who).getContent(), "");
    }

    @Test
    public void missingArgumentsAreRefused() {
        Assert.assertFalse(reg.saveRoom(null, someone(0), "1"));
        Assert.assertFalse(reg.saveRoom(tripId(), null, "1"));
    }
}
