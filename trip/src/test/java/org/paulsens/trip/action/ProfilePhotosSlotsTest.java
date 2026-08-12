package org.paulsens.trip.action;

import java.lang.reflect.Field;
import java.util.List;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The slot-aware profile-photo index: key parsing (both forms), versioned replace, selection resolution,
 * and the local-mode store — everything behind the profile page's grid.
 */
public class ProfilePhotosSlotsTest {

    private ProfilePhotos photosWith(final MediaCommands media, final PersonCommands people)
            throws Exception {
        final ProfilePhotos photos = new ProfilePhotos();
        set(photos, "media", media);
        if (people != null) {
            set(photos, "people", people);
        }
        photos.subscribe();
        return photos;
    }

    private static void set(final Object target, final String field, final Object value) throws Exception {
        final Field declared = ProfilePhotos.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }

    private static MediaCommands localMedia() {
        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.listKeys(ProfilePhotos.PREFIX)).thenReturn(List.of());
        Mockito.when(media.isUploadEnabled()).thenReturn(false);
        return media;
    }

    // --- key parsing ---

    @Test
    public void parseAcceptsBothFormsAndRejectsEverythingElse() {
        Assert.assertEquals(ProfilePhotos.parse("profilePics/p1.jpg"),
                new ProfilePhotos.SlotKey("p1", 1, -1L));
        Assert.assertEquals(ProfilePhotos.parse("profilePics/p1/2-1723400000000.jpg"),
                new ProfilePhotos.SlotKey("p1", 2, 1723400000000L));
        Assert.assertEquals(ProfilePhotos.keyFor("p1", 2, 123), "profilePics/p1/2-123.jpg");
        Assert.assertEquals(ProfilePhotos.legacyKeyFor("p1"), "profilePics/p1.jpg");

        Assert.assertNull(ProfilePhotos.parse(null));
        Assert.assertNull(ProfilePhotos.parse("downloads/x.jpg"));
        Assert.assertNull(ProfilePhotos.parse("profilePics/x.png"));
        Assert.assertNull(ProfilePhotos.parse("profilePics/.jpg"));
        Assert.assertNull(ProfilePhotos.parse("profilePics/p1/0-123.jpg"), "Slot below range");
        Assert.assertNull(ProfilePhotos.parse("profilePics/p1/5-123.jpg"), "Slot above range");
        Assert.assertNull(ProfilePhotos.parse("profilePics/p1/1-abc.jpg"), "Non-numeric version");
        Assert.assertNull(ProfilePhotos.parse("profilePics/p1/1-123/x.jpg"), "Nested path");
        Assert.assertNull(ProfilePhotos.parse("profilePics//1-123.jpg"), "Empty person id");
        Assert.assertNull(ProfilePhotos.parse("profilePics/p1/-123.jpg"), "No slot");
    }

    // --- seeding ---

    @Test
    public void seedMergesLegacyAndVersionedWithVersionedWinning() throws Exception {
        final MediaCommands media = localMedia();
        Mockito.when(media.listKeys(ProfilePhotos.PREFIX)).thenReturn(List.of(
                "profilePics/p1.jpg",
                "profilePics/p1/1-100.jpg",
                "profilePics/p1/3-200.jpg",
                "profilePics/p2.jpg",
                "profilePics/not-a-photo.txt"));
        final ProfilePhotos photos = photosWith(media, null);

        Assert.assertTrue(photos.hasPhoto("p1"));
        final List<ProfilePhotos.Slot> slots = photos.getSlots("p1");
        Assert.assertEquals(slots.size(), 2);
        Assert.assertEquals(slots.get(0).number(), 1);
        Assert.assertEquals(slots.get(0).key(), "profilePics/p1/1-100.jpg",
                "The versioned key must beat the legacy form for the same slot");
        Assert.assertEquals(slots.get(1).number(), 3);
        Assert.assertTrue(photos.hasPhoto("p2"), "Legacy-only people still have their photo");
        Assert.assertFalse(photos.hasPhoto("p3"));
        Assert.assertFalse(photos.hasPhoto(null));
        Mockito.verify(media, Mockito.times(1)).listKeys(ProfilePhotos.PREFIX);
    }

    // --- store / replace / delete (local mode) ---

    @Test
    public void storeReplaceMintsANewKeyAndEvictsTheOldObject() throws Exception {
        final ProfilePhotos photos = photosWith(localMedia(), null);
        Assert.assertTrue(photos.store("p1", 1, new byte[] {1}));
        final String firstKey = photos.getSlots("p1").get(0).key();
        Assert.assertEquals(photos.localGet(firstKey).orElseThrow(), new byte[] {1});

        Assert.assertTrue(photos.store("p1", 1, new byte[] {2}));
        final String secondKey = photos.getSlots("p1").get(0).key();
        Assert.assertNotEquals(secondKey, firstKey, "Replace must mint a fresh key (cache busting)");
        Assert.assertTrue(photos.localGet(firstKey).isEmpty(), "The replaced object must be deleted");
        Assert.assertEquals(photos.localGet(secondKey).orElseThrow(), new byte[] {2});
        Assert.assertEquals(photos.getSlots("p1").size(), 1);
    }

    @Test
    public void aStaleRemovedEventCannotEvictTheReplacement() throws Exception {
        final ProfilePhotos photos = photosWith(localMedia(), null);
        photos.store("p1", 1, new byte[] {1});
        final String firstKey = photos.getSlots("p1").get(0).key();
        photos.store("p1", 1, new byte[] {2});

        MediaEvents.fire(MediaEvents.Change.REMOVED, firstKey);
        Assert.assertTrue(photos.hasPhoto("p1"), "A REMOVED for the superseded key must be ignored");
    }

    @Test
    public void deleteSlotRemovesTheObjectAndTheAnswer() throws Exception {
        final ProfilePhotos photos = photosWith(localMedia(), null);
        photos.store("p1", 1, new byte[] {1});
        final String key = photos.getSlots("p1").get(0).key();

        Assert.assertTrue(photos.deleteSlot("p1", 1));
        Assert.assertFalse(photos.hasPhoto("p1"));
        Assert.assertTrue(photos.localGet(key).isEmpty());
        Assert.assertFalse(photos.deleteSlot("p1", 1), "Deleting an empty slot reports false");
    }

    @Test
    public void storeRejectsSlotsOutsideTheRange() throws Exception {
        final ProfilePhotos photos = photosWith(localMedia(), null);
        Assert.assertThrows(IllegalArgumentException.class, () -> photos.store("p1", 0, new byte[] {1}));
        Assert.assertThrows(IllegalArgumentException.class, () -> photos.store("p1", 5, new byte[] {1}));
    }

    // --- selection ---

    @Test
    public void selectionHonorsTheChoiceAndFallsBackDeterministically() throws Exception {
        final PersonCommands people = Mockito.mock(PersonCommands.class);
        final Person chooser = new Person();
        chooser.setProfilePhotoSlot(2);
        Mockito.when(people.getPerson(ArgumentMatchers.any(Person.Id.class))).thenReturn(chooser);
        final ProfilePhotos photos = photosWith(localMedia(), people);

        photos.store("p1", 1, new byte[] {1});
        photos.store("p1", 2, new byte[] {2});
        Assert.assertEquals(photos.getSelectedSlot("p1"), 2, "The explicit choice wins");
        Assert.assertTrue(photos.getUrl("p1").contains("/2-"));

        photos.deleteSlot("p1", 2);
        Assert.assertEquals(photos.getSelectedSlot("p1"), 1, "A deleted choice falls back to lowest");

        chooser.setProfilePhotoSlot(null);
        photos.store("p1", 3, new byte[] {3});
        Assert.assertEquals(photos.getSelectedSlot("p1"), 1, "No choice means the lowest slot");
        Assert.assertEquals(photos.getSelectedSlot("nobody"), 0);
    }

    @Test
    public void selectionSurvivesAPersonLookupFailure() throws Exception {
        final PersonCommands people = Mockito.mock(PersonCommands.class);
        Mockito.when(people.getPerson(ArgumentMatchers.any(Person.Id.class)))
                .thenThrow(new IllegalStateException("dynamo down"));
        final ProfilePhotos photos = photosWith(localMedia(), people);
        photos.store("p1", 2, new byte[] {2});
        Assert.assertEquals(photos.getSelectedSlot("p1"), 2, "Lowest occupied, not an exception");
    }

    // --- free slots ---

    @Test
    public void nextFreeSlotWalksTheRange() throws Exception {
        final ProfilePhotos photos = photosWith(localMedia(), null);
        Assert.assertEquals(photos.nextFreeSlot("p1"), 1);
        photos.store("p1", 1, new byte[] {1});
        photos.store("p1", 2, new byte[] {2});
        Assert.assertEquals(photos.nextFreeSlot("p1"), 3);
        photos.store("p1", 3, new byte[] {3});
        photos.store("p1", 4, new byte[] {4});
        Assert.assertEquals(photos.nextFreeSlot("p1"), 0, "All slots taken");
    }

    // --- URLs and bytes ---

    @Test
    public void localModeUrlsPointAtTheServlet() throws Exception {
        final ProfilePhotos photos = photosWith(localMedia(), null);
        photos.store("p1", 1, new byte[] {1});
        // No FacesContext in a unit test, so the context path contributes nothing.
        Assert.assertTrue(photos.getUrl("p1").startsWith("/profile-photos/profilePics/p1/1-"),
                photos.getUrl("p1"));
        Assert.assertNull(photos.getUrl("nobody"), "No photos means no URL (hasPhoto is the guard)");
        Assert.assertNull(photos.getUrl(null));
    }

    @Test
    public void remoteModeDelegatesToTheMediaLayer() throws Exception {
        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.listKeys(ProfilePhotos.PREFIX)).thenReturn(List.of());
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.putObject(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyBoolean()))
                .thenReturn(true);
        Mockito.when(media.publicUrl(ArgumentMatchers.anyString()))
                .thenAnswer(call -> "https://cdn.example/" + call.getArgument(0));
        Mockito.when(media.getObject(ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.of(new byte[] {9}));
        final ProfilePhotos photos = photosWith(media, null);

        Assert.assertTrue(photos.store("p1", 1, new byte[] {1}));
        final String key = photos.getSlots("p1").get(0).key();
        Mockito.verify(media).putObject(ArgumentMatchers.eq(key), ArgumentMatchers.any(),
                ArgumentMatchers.eq("image/jpeg"), ArgumentMatchers.eq(ProfilePhotos.CACHE_SECONDS),
                ArgumentMatchers.eq(false));
        Assert.assertEquals(photos.getUrl("p1"), "https://cdn.example/" + key);
        Assert.assertEquals(photos.currentBytes("p1", 1).orElseThrow(), new byte[] {9});

        photos.deleteSlot("p1", 1);
        Mockito.verify(media).deleteObject(key);
        Mockito.verify(media).invalidateCdn(List.of("/" + key));

        Mockito.when(media.putObject(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyBoolean()))
                .thenReturn(false);
        Assert.assertFalse(photos.store("p1", 1, new byte[] {1}), "A failed S3 put must report false");
    }

    @Test
    public void currentBytesAnswersLocallyToo() throws Exception {
        final ProfilePhotos photos = photosWith(localMedia(), null);
        Assert.assertTrue(photos.currentBytes("p1", 1).isEmpty());
        photos.store("p1", 1, new byte[] {7});
        Assert.assertEquals(photos.currentBytes("p1", 1).orElseThrow(), new byte[] {7});
    }
}
