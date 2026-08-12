package org.paulsens.trip.action;

import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * {@code PersonCommands.selectProfilePhoto}: the fresh-copy save (so a half-edited view is never persisted),
 * the mirror onto the view copy (so its later Save agrees), and the fail-closed guards.
 */
public class ProfilePhotoSelectionTest {

    private final PersonCommands people = new PersonCommands();

    @BeforeClass
    void initTests() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    @Test
    public void selectionSavesAFreshCopyAndMirrorsTheView() {
        final Person stored = FakeData.getFakePeople().get(0);
        // The view's copy is mid-edit: a field change the person never saved must NOT be persisted here.
        final Person viewCopy = people.getPerson(stored.getId());
        viewCopy.setNickname("half-typed-nickname-never-saved");

        Assert.assertTrue(people.selectProfilePhoto(viewCopy, 2));
        Assert.assertEquals(viewCopy.getProfilePhotoSlot(), Integer.valueOf(2),
                "The view copy must mirror the saved choice so its later Save agrees");

        final Person reloaded = people.getPerson(stored.getId());
        Assert.assertEquals(reloaded.getProfilePhotoSlot(), Integer.valueOf(2));
        Assert.assertNotEquals(reloaded.getNickname(), "half-typed-nickname-never-saved",
                "Selecting a photo must not persist unrelated in-flight edits");
    }

    @Test
    public void guardsFailClosed() {
        Assert.assertFalse(people.selectProfilePhoto(null, 1));
        final Person real = people.getPerson(FakeData.getFakePeople().get(0).getId());
        Assert.assertFalse(people.selectProfilePhoto(real, 0), "Slot below range");
        Assert.assertFalse(people.selectProfilePhoto(real, ProfilePhotos.MAX_SLOTS + 1),
                "Slot above range");

        // A subject that does not exist resolves to the miss placeholder (fresh id) and must not be saved.
        final Person ghost = new Person();
        Assert.assertFalse(people.selectProfilePhoto(ghost, 1));
        Assert.assertNotEquals(people.getPerson(ghost.getId()).getId(), ghost.getId(),
                "No junk row may be written for the ghost");
    }
}
