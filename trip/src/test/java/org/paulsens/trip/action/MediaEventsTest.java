package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * The media pub/sub contract. The interesting cases are the ones that decide whether a subscriber's cache can
 * go wrong: only matching prefixes are told, both directions are delivered, and one bad subscriber cannot
 * silence another or break the upload that triggered it.
 */
public class MediaEventsTest {

    @AfterMethod(alwaysRun = true)
    public void reset() {
        MediaEvents.clear();
    }

    @Test
    public void listenersHearOnlyTheirOwnPrefix() {
        final List<String> pics = new ArrayList<>();
        final List<String> docs = new ArrayList<>();
        MediaEvents.onPrefix("profilePics/", (change, key) -> pics.add(key));
        MediaEvents.onPrefix("downloads/", (change, key) -> docs.add(key));

        MediaEvents.fire(MediaEvents.Change.ADDED, "profilePics/abc.jpg");
        MediaEvents.fire(MediaEvents.Change.ADDED, "downloads/guide.pdf");
        MediaEvents.fire(MediaEvents.Change.ADDED, "images/unrelated.jpg");

        Assert.assertEquals(pics, List.of("profilePics/abc.jpg"));
        Assert.assertEquals(docs, List.of("downloads/guide.pdf"));
    }

    @Test
    public void bothDirectionsAreDelivered() {
        final List<String> seen = new ArrayList<>();
        MediaEvents.onPrefix("p/", (change, key) -> seen.add(change + ":" + key));
        MediaEvents.fire(MediaEvents.Change.ADDED, "p/x.jpg");
        MediaEvents.fire(MediaEvents.Change.REMOVED, "p/x.jpg");
        Assert.assertEquals(seen, List.of("ADDED:p/x.jpg", "REMOVED:p/x.jpg"));
    }

    @Test
    public void aThrowingListenerCannotSilenceOthersOrThePublisher() {
        final List<String> survived = new ArrayList<>();
        MediaEvents.onPrefix("p/", (change, key) -> {
            throw new IllegalStateException("subscriber is broken");
        });
        MediaEvents.onPrefix("p/", (change, key) -> survived.add(key));

        MediaEvents.fire(MediaEvents.Change.ADDED, "p/x.jpg");   // must not propagate

        Assert.assertEquals(survived, List.of("p/x.jpg"),
                "a broken subscriber must not stop the others being told");
    }

    @Test
    public void nullKeyIsIgnored() {
        MediaEvents.onPrefix("p/", (change, key) -> Assert.fail("should not be called"));
        MediaEvents.fire(MediaEvents.Change.ADDED, null);
    }

    @Test
    public void profilePhotoKeysRoundTripToPersonIds() {
        final String id = "c411fff9-4bb2-4957-8e85-aa194e66a399";
        Assert.assertEquals(ProfilePhotos.parse(ProfilePhotos.keyFor(id, 2, 123)),
                new ProfilePhotos.SlotKey(id, 2, 123));
        Assert.assertEquals(ProfilePhotos.parse(ProfilePhotos.legacyKeyFor(id)),
                new ProfilePhotos.SlotKey(id, 1, -1L));
        // Keys outside the prefix, or of another type, must not be mistaken for a person.
        Assert.assertNull(ProfilePhotos.parse("downloads/guide.pdf"));
        Assert.assertNull(ProfilePhotos.parse("profilePics/notajpg.png"));
        Assert.assertNull(ProfilePhotos.parse("profilePics/.jpg"));
        Assert.assertNull(ProfilePhotos.parse(null));
    }
}
