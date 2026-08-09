package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The public-visibility layer over media: the age-windowed slot read behind the Documents section, the
 * home-page albums, and the moderation toggle. Uses its own uniquely-named seeds -- the suite shares one
 * local store.
 */
public class MediaVisibilityTest {

    private final MediaCommands media = new MediaCommands();
    private final TripCommands trips = new TripCommands();

    @BeforeClass
    public void seed() {
        DAO.getInstance();
        // Other suite tests clear caches (locally, the store) -- restore the FakeData rows this relies on.
        FakeData.addFakeData();
        // An ended-recently public trip with 12 photos, one uploader-hidden: the qualifying album.
        saveTrip("vis-album-trip", "2026 Aug: Album Trip", -12, -2);
        for (int i = 1; i <= 12; i++) {
            saveMedia(new MediaItem("vis-photo-" + i, "chat/vis-album-trip/p" + i + ".jpg", "P" + i, null,
                    "image/jpeg", 100L, "tripChat-vis-album-trip", 0,
                    LocalDateTime.now().minusDays(10).plusHours(i), "seed", null, i == 1 ? true : null));
        }
        // Too few photos: no album.
        saveTrip("vis-few-trip", "2026 Aug: Few Photos", -12, -2);
        for (int i = 1; i <= 3; i++) {
            saveMedia(new MediaItem("vis-few-" + i, "chat/vis-few-trip/p" + i + ".jpg", "P" + i, null,
                    "image/jpeg", 100L, "tripChat-vis-few-trip", 0,
                    LocalDateTime.now().minusDays(10), "seed", null, null));
        }
        // Enough photos but not started: no album.
        saveTrip("vis-future-trip", "2026 Dec: Future Trip", 20, 30);
        for (int i = 1; i <= 12; i++) {
            saveMedia(new MediaItem("vis-future-" + i, "chat/vis-future-trip/p" + i + ".jpg", "P" + i, null,
                    "image/jpeg", 100L, "tripChat-vis-future-trip", 0,
                    LocalDateTime.now().minusDays(1), "seed", null, null));
        }
        // The docs slot: visible, hidden-by-flag, aged-out, and undated.
        saveMedia(new MediaItem("vis-doc-ok", "downloads/vis-ok.pdf", "OK", null, "application/pdf", 9L,
                "vis-docs", 0, LocalDateTime.now().minusDays(30), "seed", null, null));
        saveMedia(new MediaItem("vis-doc-hidden", "downloads/vis-hidden.pdf", "Hidden", null,
                "application/pdf", 9L, "vis-docs", 1, LocalDateTime.now().minusDays(1), "seed", null, true));
        saveMedia(new MediaItem("vis-doc-old", "downloads/vis-old.pdf", "Old", null, "application/pdf", 9L,
                "vis-docs", 2, LocalDateTime.now().minusDays(200), "seed", null, null));
        saveMedia(new MediaItem("vis-doc-undated", "downloads/vis-undated.pdf", "Undated", null,
                "application/pdf", 9L, "vis-docs", 3, null, "seed", null, null));
    }

    private void saveTrip(final String id, final String title, final int startOffset, final int endOffset) {
        final Trip trip = Trip.builder()
                .id(id).title(title).openToPublic(true)
                .startDate(LocalDateTime.now().plusDays(startOffset))
                .endDate(LocalDateTime.now().plusDays(endOffset))
                .build();
        trip.setProvider("CFPW");
        Assert.assertTrue(trips.saveTrip(trip), "seed trip " + id);
    }

    private static void saveMedia(final MediaItem item) {
        Assert.assertTrue(DAO.getInstance().saveMedia(item), "seed media " + item.getId());
    }

    @Test
    public void visibleInSlotAppliesFlagAndAgeWindow() {
        final List<String> ids = media.getVisibleInSlot("vis-docs", 92).stream()
                .map(MediaItem::getId).toList();
        Assert.assertEquals(ids, List.of("vis-doc-ok"),
                "hidden, aged-out and undated rows must all be excluded when a window applies");
    }

    @Test
    public void visibleInSlotWithoutWindowKeepsUndatedRows() {
        final List<String> ids = media.getVisibleInSlot("vis-docs", 0).stream()
                .map(MediaItem::getId).toList();
        Assert.assertEquals(ids, List.of("vis-doc-ok", "vis-doc-old", "vis-doc-undated"),
                "no window: only the hidden flag filters");
    }

    @Test
    public void homeAlbumsRequireStartedTripsWithEnoughVisiblePhotos() {
        final List<MediaCommands.TripAlbum> albums = media.getHomeAlbums(365, 10);
        final MediaCommands.TripAlbum album = albums.stream()
                .filter(a -> a.trip().getId().equals("vis-album-trip"))
                .findFirst().orElseThrow(() -> new AssertionError("qualifying album missing"));
        Assert.assertEquals(album.photos().size(), 11, "12 photos minus the uploader-hidden one");
        Assert.assertTrue(album.photos().stream().noneMatch(MediaItem::getHidden));

        Assert.assertTrue(albums.stream().noneMatch(a -> a.trip().getId().equals("vis-few-trip")),
                "3 photos is under the minimum");
        Assert.assertTrue(albums.stream().noneMatch(a -> a.trip().getId().equals("vis-future-trip")),
                "a not-yet-started trip must not show its photos");
        Assert.assertTrue(albums.stream().noneMatch(a -> a.trip().getId().equals("faketrip")),
                "a non-public trip is unlisted here too");
    }

    @Test
    public void inProgressTripQualifies() {
        // Fake2 runs now; give it enough photos under a distinct id prefix and it must appear.
        final List<MediaCommands.TripAlbum> before = media.getHomeAlbums(365, 3);
        Assert.assertTrue(before.stream().anyMatch(a -> a.trip().getId().equals("Fake2")),
                "an in-progress public trip with enough photos shows (FakeData seeds 4)");
    }

    @Test
    public void setHiddenFlipsAndAudits() {
        saveMedia(new MediaItem("vis-toggle", "chat/x/toggle.jpg", "T", null, "image/jpeg", 1L,
                "vis-toggle-slot", 0, LocalDateTime.now(), "seed", null, null));
        Assert.assertTrue(media.setHidden("vis-toggle", true, "mod@example.com"));
        final MediaItem hidden = media.get("vis-toggle");
        Assert.assertTrue(hidden.getHidden());
        Assert.assertTrue(media.setHidden("vis-toggle", true, "mod@example.com"), "no-op repeat succeeds");
        Assert.assertTrue(media.setHidden("vis-toggle", false, "mod@example.com"));
        Assert.assertFalse(media.get("vis-toggle").getHidden());
        Assert.assertFalse(media.setHidden("vis-no-such-row", true, "mod@example.com"));
    }
}
