package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.List;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.media.PhotoFixtures;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatAttachment;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The photo service against the LOCAL store (no bucket configured — the whole unit suite runs that way) plus
 * Mockito for the remote-store contract. The local store is not a test double: it is the real fallback the
 * webtests and local dev run on, so its behavior is product behavior.
 */
public class ChatPhotosTest {

    private static final Person.Id UPLOADER = Person.Id.from("photo-person");

    private ChatPhotos freshLocal() {
        return new ChatPhotos(new MediaCommands());
    }

    @Test
    public void stagingStoresBothRenditionsLocallyUnderTheTripPrefix() {
        final ChatPhotos photos = freshLocal();
        final ChatPhotos.StagedPhoto staged = photos.stage("trip-a", UPLOADER, PhotoFixtures.jpeg(1600, 1200));

        Assert.assertTrue(staged.key().startsWith("chat/trip-a/"), staged.key());
        Assert.assertTrue(staged.key().endsWith(".jpg"), staged.key());
        Assert.assertTrue(staged.smallKey().endsWith("-small.jpg"), staged.smallKey());
        Assert.assertEquals(staged.width(), 1600);
        Assert.assertEquals(staged.height(), 1200);
        Assert.assertTrue(photos.localGet(staged.key()).isPresent(), "full rendition served locally");
        Assert.assertTrue(photos.localGet(staged.smallKey()).isPresent(), "small rendition served locally");
        Assert.assertEquals(photos.localGet(staged.key()).orElseThrow().contentType(), "image/jpeg");
    }

    @Test
    public void aSmallPhotoStoresOneObjectServingBothKeys() {
        final ChatPhotos photos = freshLocal();
        final ChatPhotos.StagedPhoto staged = photos.stage("trip-a", UPLOADER, PhotoFixtures.jpeg(400, 300));

        Assert.assertEquals(staged.smallKey(), staged.key(), "no second rendition for an already-small photo");
        Assert.assertTrue(photos.localGet(staged.key()).isPresent());
    }

    @Test
    public void localModeHasNoPublicBaseButAPageBase() {
        final ChatPhotos photos = freshLocal();
        Assert.assertFalse(photos.isRemoteStore());
        Assert.assertNull(photos.getPublicBase());
        Assert.assertEquals(photos.getPhotoPageBase(), "/chat-photos/",
                "no FacesContext in tests, so the context path contributes nothing");
    }

    @Test
    public void resolveStagedAnswersOnlyTheStagerAndCarriesTheTitle() {
        final ChatPhotos photos = freshLocal();
        final ChatPhotos.StagedPhoto staged = photos.stage("trip-b", UPLOADER, PhotoFixtures.jpeg(900, 600));

        final List<ChatAttachment> resolved = photos.resolveStaged("trip-b", UPLOADER,
                List.of(new ChatPhotos.AttachmentRef(staged.key(), "  Sunset over the hill  ")));
        Assert.assertEquals(resolved.size(), 1);
        Assert.assertEquals(resolved.get(0).getKind(), "image");
        Assert.assertEquals(resolved.get(0).getS3Key(), staged.key());
        Assert.assertEquals(resolved.get(0).getThumbKey(), staged.smallKey());
        Assert.assertEquals(resolved.get(0).getCaption(), "Sunset over the hill", "titles are trimmed");

        Assert.assertThrows(PhotoRejectedException.class, () -> photos.resolveStaged(
                "trip-b", Person.Id.from("someone-else"),
                List.of(new ChatPhotos.AttachmentRef(staged.key(), null))));
        Assert.assertThrows(PhotoRejectedException.class, () -> photos.resolveStaged(
                "another-trip", UPLOADER, List.of(new ChatPhotos.AttachmentRef(staged.key(), null))));
    }

    @Test
    public void consumeMakesAStagedKeySingleUse() {
        final ChatPhotos photos = freshLocal();
        final ChatPhotos.StagedPhoto staged = photos.stage("trip-c", UPLOADER, PhotoFixtures.jpeg(900, 600));
        final List<ChatAttachment> resolved = photos.resolveStaged("trip-c", UPLOADER,
                List.of(new ChatPhotos.AttachmentRef(staged.key(), null)));

        photos.consume(resolved);

        Assert.assertThrows(PhotoRejectedException.class, () -> photos.resolveStaged(
                "trip-c", UPLOADER, List.of(new ChatPhotos.AttachmentRef(staged.key(), null))));
    }

    @Test
    public void albumRowsRecordAttributionSlotAndSmallKey() {
        final ChatPhotos photos = freshLocal();
        final ChatPhotos.StagedPhoto staged = photos.stage("trip-d", UPLOADER, PhotoFixtures.jpeg(1600, 900));
        final List<ChatAttachment> resolved = photos.resolveStaged("trip-d", UPLOADER,
                List.of(new ChatPhotos.AttachmentRef(staged.key(), "Group at the cross")));

        photos.recordAlbumRows("trip-d", "Pilgrimage 2026", UPLOADER, "Pat Pilgrim", resolved,
                new AuditActor("pat@test", UPLOADER.getValue()));

        final List<MediaItem> inSlot = DAO.getInstance().getMediaInSlot(ChatPhotos.slotFor("trip-d"));
        Assert.assertEquals(inSlot.size(), 1);
        final MediaItem row = inSlot.get(0);
        Assert.assertEquals(row.getS3Key(), staged.key());
        Assert.assertEquals(row.getSmallKey(), staged.smallKey());
        Assert.assertEquals(row.getDisplayKey(), staged.smallKey());
        Assert.assertEquals(row.getTitle(), "Group at the cross");
        Assert.assertTrue(row.getDescription().contains("Pat Pilgrim"), row.getDescription());
        Assert.assertTrue(row.getDescription().contains("Pilgrimage 2026"), row.getDescription());
        Assert.assertEquals(row.getUploadedBy(), UPLOADER.getValue());
    }

    @Test
    public void anUntitledPhotoFallsBackToItsFileName() {
        final ChatPhotos photos = freshLocal();
        final ChatPhotos.StagedPhoto staged = photos.stage("trip-e", UPLOADER, PhotoFixtures.jpeg(900, 600));
        final List<ChatAttachment> resolved = photos.resolveStaged("trip-e", UPLOADER,
                List.of(new ChatPhotos.AttachmentRef(staged.key(), "   ")));

        photos.recordAlbumRows("trip-e", "Trip E", UPLOADER, "Someone", resolved,
                new AuditActor("s@test", UPLOADER.getValue()));

        final MediaItem row = DAO.getInstance().getMediaInSlot(ChatPhotos.slotFor("trip-e")).get(0);
        Assert.assertEquals(row.getTitle(), staged.key().substring(staged.key().lastIndexOf('/') + 1));
    }

    @Test
    public void deleteEverywhereRemovesObjectsAndAlbumRows() {
        final ChatPhotos photos = freshLocal();
        final ChatPhotos.StagedPhoto staged = photos.stage("trip-f", UPLOADER, PhotoFixtures.jpeg(1600, 900));
        final List<ChatAttachment> resolved = photos.resolveStaged("trip-f", UPLOADER,
                List.of(new ChatPhotos.AttachmentRef(staged.key(), "gone soon")));
        photos.recordAlbumRows("trip-f", "Trip F", UPLOADER, "Someone", resolved,
                new AuditActor("s@test", UPLOADER.getValue()));
        Assert.assertEquals(DAO.getInstance().getMediaInSlot(ChatPhotos.slotFor("trip-f")).size(), 1);

        photos.deleteEverywhere(resolved);

        Assert.assertTrue(photos.localGet(staged.key()).isEmpty(), "full rendition removed");
        Assert.assertTrue(photos.localGet(staged.smallKey()).isEmpty(), "small rendition removed");
        Assert.assertEquals(DAO.getInstance().getMediaInSlot(ChatPhotos.slotFor("trip-f")).size(), 0,
                "album row removed");
        photos.deleteEverywhere(List.of());
        photos.deleteEverywhere(null);
    }

    /**
     * Regression for the emptied-album bug: deleting ONE photo invalidates the media cache, which reloads
     * by scanning the table — and with no real fake behind that scan, the reload came back empty and the
     * whole album vanished. The other rows must survive the reload.
     */
    @Test
    public void deletingOnePhotoLeavesTheRestOfTheAlbumIntact() {
        final ChatPhotos photos = freshLocal();
        final String trip = "trip-survivors-" + System.nanoTime();
        final List<List<ChatAttachment>> sent = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final ChatPhotos.StagedPhoto staged = photos.stage(trip, UPLOADER, PhotoFixtures.jpeg(900, 600));
            final List<ChatAttachment> resolved = photos.resolveStaged(trip, UPLOADER,
                    List.of(new ChatPhotos.AttachmentRef(staged.key(), "photo " + i)));
            photos.recordAlbumRows(trip, "Survivors", UPLOADER, "Someone", resolved,
                    new AuditActor("s@test", UPLOADER.getValue()));
            sent.add(resolved);
        }
        Assert.assertEquals(DAO.getInstance().getMediaInSlot(ChatPhotos.slotFor(trip)).size(), 3);

        photos.deleteEverywhere(sent.get(0));

        final List<MediaItem> remaining = DAO.getInstance().getMediaInSlot(ChatPhotos.slotFor(trip));
        Assert.assertEquals(remaining.size(), 2,
                "deleting one photo must not empty the album via the cache reload");
    }

    @Test
    public void slotNamingRoundTrips() {
        Assert.assertEquals(ChatPhotos.slotFor("abc"), "tripChat-abc");
        Assert.assertTrue(ChatPhotos.isChatSlot("tripChat-abc"));
        Assert.assertFalse(ChatPhotos.isChatSlot("homepage"));
        Assert.assertFalse(ChatPhotos.isChatSlot(null));
    }

    @Test
    public void parseRefsToleratesEveryShapeOfGarbage() {
        Assert.assertTrue(ChatPhotos.parseRefs(null).isEmpty());
        Assert.assertTrue(ChatPhotos.parseRefs("  ").isEmpty());
        Assert.assertTrue(ChatPhotos.parseRefs("not json").isEmpty());
        Assert.assertTrue(ChatPhotos.parseRefs("{\"key\":\"x\"}").isEmpty(), "an object is not an array");
        Assert.assertTrue(ChatPhotos.parseRefs("[{\"title\":\"no key\"}, 7, \"x\"]").isEmpty());

        final List<ChatPhotos.AttachmentRef> refs =
                ChatPhotos.parseRefs("[{\"key\":\"chat/t/x.jpg\",\"title\":\"hi\"},{\"key\":\"chat/t/y.jpg\"}]");
        Assert.assertEquals(refs.size(), 2);
        Assert.assertEquals(refs.get(0).key(), "chat/t/x.jpg");
        Assert.assertEquals(refs.get(0).title(), "hi");
        Assert.assertNull(refs.get(1).title());
    }

    @Test
    public void rejectedBytesNeverReachTheStore() {
        final ChatPhotos photos = freshLocal();
        Assert.assertThrows(PhotoRejectedException.class,
                () -> photos.stage("trip-g", UPLOADER, new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}));
    }

    @Test
    public void remoteStoreUploadsBothRenditionsWithTheRightDispositions() {
        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.putObject(ArgumentMatchers.anyString(), ArgumentMatchers.any(byte[].class),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyBoolean()))
                .thenReturn(true);
        final ChatPhotos photos = new ChatPhotos(media);

        final ChatPhotos.StagedPhoto staged = photos.stage("trip-r", UPLOADER, PhotoFixtures.jpeg(1600, 900));

        // Full rendition: download disposition. Small rendition: inline. Both on the one-hour cache.
        Mockito.verify(media).putObject(ArgumentMatchers.eq(staged.key()), ArgumentMatchers.any(byte[].class),
                ArgumentMatchers.eq("image/jpeg"), ArgumentMatchers.eq(ChatPhotos.CACHE_SECONDS),
                ArgumentMatchers.eq(true));
        Mockito.verify(media).putObject(ArgumentMatchers.eq(staged.smallKey()),
                ArgumentMatchers.any(byte[].class), ArgumentMatchers.eq("image/jpeg"),
                ArgumentMatchers.eq(ChatPhotos.CACHE_SECONDS), ArgumentMatchers.eq(false));
        Assert.assertTrue(photos.localGet(staged.key()).isEmpty(), "nothing lands in the local store");
    }

    @Test
    public void aFailedRemotePutIsAnOperationalErrorNotARejection() {
        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.putObject(ArgumentMatchers.anyString(), ArgumentMatchers.any(byte[].class),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyBoolean()))
                .thenReturn(false);
        final ChatPhotos photos = new ChatPhotos(media);

        Assert.assertThrows(IllegalStateException.class,
                () -> photos.stage("trip-r", UPLOADER, PhotoFixtures.jpeg(900, 600)));
    }

    @Test
    public void remoteDeleteRemovesBothRenditions() {
        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        final ChatPhotos photos = new ChatPhotos(media);
        final ChatAttachment attachment = new ChatAttachment("image", "chat/t/a.jpg", "image/jpeg", 9L,
                1600, 900, "chat/t/a-small.jpg", null);

        photos.deleteRenditions(List.of(attachment));

        Mockito.verify(media).deleteObject("chat/t/a.jpg");
        Mockito.verify(media).deleteObject("chat/t/a-small.jpg");
    }

    @Test
    public void publicBaseComesFromTheMediaLayerWhenRemote() {
        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.publicUrl("")).thenReturn("https://cdn.example.com/");
        final ChatPhotos photos = new ChatPhotos(media);

        Assert.assertEquals(photos.getPublicBase(), "https://cdn.example.com/");
        Assert.assertEquals(photos.getPhotoPageBase(), "https://cdn.example.com/");
    }

    @Test
    public void theSharedInstanceIsStableOutsideJsf() {
        Assert.assertSame(ChatPhotos.getChatPhotos(), ChatPhotos.getChatPhotos(),
                "upload servlet and send path must share one staging registry");
    }

    @Test
    public void moderationInvalidatesTheCdnOnlyWhenThereIsOne() {
        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        final ChatPhotos remote = new ChatPhotos(media);
        final ChatAttachment attachment = new ChatAttachment("image", "chat/t/a.jpg", "image/jpeg", 9L,
                1600, 900, "chat/t/a-small.jpg", null);

        remote.deleteRenditions(List.of(attachment));
        Mockito.verify(media).invalidateCdn(List.of("/chat/t/a.jpg", "/chat/t/a-small.jpg"));

        final MediaCommands local = Mockito.mock(MediaCommands.class);
        Mockito.when(local.isUploadEnabled()).thenReturn(false);
        new ChatPhotos(local).deleteRenditions(List.of(attachment));
        Mockito.verify(local, Mockito.never()).invalidateCdn(ArgumentMatchers.anyList());
    }

    @Test
    public void abandonedUploadsAreSweptWithTheirObjects() {
        final org.paulsens.trip.media.ChatPhotoStaging staging =
                new org.paulsens.trip.media.ChatPhotoStaging();
        final ChatPhotos photos = new ChatPhotos(new MediaCommands(), staging);
        final ChatPhotos.StagedPhoto abandoned = photos.stage("trip-s", UPLOADER,
                PhotoFixtures.jpeg(1600, 900));
        // Backdate the entry: the sweep keys off stagedAt, and the next upload triggers it.
        staging.consume(abandoned.key());
        staging.put(new org.paulsens.trip.media.ChatPhotoStaging.Staged("trip-s", UPLOADER.getValue(),
                abandoned.key(), abandoned.smallKey(), "image/jpeg", 9, 1600, 900,
                java.time.Instant.now().minus(java.time.Duration.ofHours(25))));

        photos.stage("trip-s", UPLOADER, PhotoFixtures.jpeg(400, 300));

        Assert.assertTrue(photos.localGet(abandoned.key()).isEmpty(), "abandoned full rendition swept");
        Assert.assertTrue(photos.localGet(abandoned.smallKey()).isEmpty(), "abandoned small rendition swept");
    }

    @Test
    public void theLocalStoreEvictsOldestFirstUnderItsBudget() {
        final ChatPhotos photos = freshLocal();
        final byte[] photo = PhotoFixtures.jpeg(1600, 1200);
        // Budget: about one and a half photos, so the third upload must push the first one out.
        photos.localStoreMaxBytesForTest(photo.length + photo.length / 2);
        final ChatPhotos.StagedPhoto first = photos.stage("trip-l", UPLOADER, photo);
        photos.stage("trip-l", UPLOADER, PhotoFixtures.jpeg(1600, 1200));
        final ChatPhotos.StagedPhoto third = photos.stage("trip-l", UPLOADER, PhotoFixtures.jpeg(1600, 1200));

        Assert.assertTrue(photos.localGet(first.key()).isEmpty(), "oldest evicted");
        Assert.assertTrue(photos.localGet(third.key()).isPresent(), "newest kept");
    }
}
