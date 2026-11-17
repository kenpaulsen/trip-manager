package org.paulsens.trip.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.media.PhotoFixtures;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatAttachment;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.model.chat.ChatChannel;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * A chat attachment that REFERENCES an album photo instead of owning one ("Choose from Albums" in the chat, owner
 * 2026-11-17: a reference, not a copy). The photo, its album row and its comment thread belong to the message that
 * first shared it, often in another trip, so everything destructive must pass a reference by: this class holds
 * that line. One photo is shared in trip A's chat for real (staged, sent, recorded in A's album); a message in
 * trip B then references it.
 */
public class ChatPhotoReferenceTest {

    private static final Person.Id SHARER = Person.Id.from("ref-sharer");
    private static final Person.Id BORROWER = Person.Id.from("ref-borrower");

    private String tripA;
    private String tripB;
    private ChatPhotos photos;
    private AlbumPhotoSource albums;
    private Caller borrower;
    private ChatAttachment original;
    private MediaItem albumRow;

    @BeforeMethod
    public void shareOnePhotoInTripA() {
        final long stamp = System.nanoTime();
        tripA = "ref-trip-a-" + stamp;
        tripB = "ref-trip-b-" + stamp;
        albums = Mockito.mock(AlbumPhotoSource.class);
        borrower = new Caller(BORROWER, false, AuditActor.from(null), Mockito.mock(PrivilegeCommands.class));
        // The original's dimensions come from the message that first carried it: answered here, not looked up.
        photos = new ChatPhotos(new MediaCommands()) {
            @Override
            protected Optional<ChatAttachment> originalOf(final MediaItem photo) {
                return Optional.ofNullable(original);
            }
        };
        photos.albumsForTest(albums, () -> borrower);

        final ChatPhotos.StagedPhoto staged = photos.stage(tripA, SHARER, PhotoFixtures.jpeg(1600, 900));
        original = photos.resolveStaged(tripA, SHARER,
                List.of(new ChatPhotos.AttachmentRef(staged.key(), "Sunrise"))).getFirst();
        photos.consume(List.of(original));
        photos.recordAlbumRows(tripA, "Trip A", SHARER, "Sharer", List.of(original),
                new AuditActor("sharer@test", SHARER.getValue()));
        albumRow = DAO.getInstance().getMediaInSlot(ChatPhotos.slotFor(tripA), Cached.NO).getFirst();
        Mockito.when(albums.offered(borrower, AlbumPhotoSource.tripScope(tripB), albumRow.getId()))
                .thenReturn(Optional.of(albumRow));
    }

    private ChatPhotos.AttachmentRef refTo(final String mediaId, final String title) {
        return new ChatPhotos.AttachmentRef(ChatPhotos.ALBUM_REF_PREFIX + mediaId, title);
    }

    private ChatAttachment reference() {
        return photos.resolveStaged(tripB, BORROWER, List.of(refTo(albumRow.getId(), " From last year "))).getFirst();
    }

    @Test
    public void aReferenceCarriesTheOriginalsKeysAndFactsAndIsMarked() {
        Assert.assertFalse(original.isRef(), "An upload owns its photo");
        final ChatAttachment ref = reference();
        Assert.assertTrue(ref.isRef());
        Assert.assertEquals(ref.getKind(), "image");
        Assert.assertEquals(ref.getS3Key(), original.getS3Key(), "The ORIGINAL's key: nothing was copied");
        Assert.assertEquals(ref.getThumbKey(), original.getThumbKey());
        Assert.assertEquals(ref.getWidth(), original.getWidth(), "A client lays it out before it arrives");
        Assert.assertEquals(ref.getHeight(), original.getHeight());
        Assert.assertEquals(ref.getCaption(), "From last year", "The caption is this message's own");
        Assert.assertFalse(ref.isHidden(), "The public-site choice belongs to the original");
        Assert.assertTrue(ref.getS3Key().startsWith("chat/" + tripA + "/"), "It stays in trip A's key space");

        original = null;
        final ChatAttachment bare = reference();
        Assert.assertNull(bare.getWidth(), "The first message is gone: a reference without dimensions");
        Assert.assertTrue(bare.isRef());
    }

    /**
     * A photo small enough to need no reduced copy has none in its album row. Its reference must still name a
     * thumbnail (the photo itself): the chat page draws an attachment from that key and skips one without it,
     * which is how a second, small photo once went missing from a two-photo message.
     */
    @Test
    public void aReferenceToAPhotoWithNoReducedCopyUsesThePhotoItselfAsItsThumbnail() {
        final MediaItem small = new MediaItem("small-row", "chat/" + tripA + "/small.jpg", "small", "d", "image/jpeg",
                9L, ChatPhotos.slotFor(tripA), 0, null, "someone");
        Assert.assertNull(small.getSmallKey(), "The album row holds no reduced copy");
        Mockito.when(albums.offered(borrower, AlbumPhotoSource.tripScope(tripB), "small-row"))
                .thenReturn(Optional.of(small));
        final ChatAttachment ref = photos.resolveStaged(tripB, BORROWER, List.of(refTo("small-row", null))).getFirst();
        Assert.assertEquals(ref.getThumbKey(), "chat/" + tripA + "/small.jpg");
        Assert.assertEquals(ref.getS3Key(), "chat/" + tripA + "/small.jpg");
    }

    @Test
    public void aReferenceIsCheckedAgainAtSendAndNeverStaged() {
        Assert.assertTrue(ChatPhotos.isAlbumRef("album:abc"));
        Assert.assertFalse(ChatPhotos.isAlbumRef("chat/t/x.jpg"));
        Assert.assertFalse(ChatPhotos.isAlbumRef(null));

        Assert.assertThrows(PhotoRejectedException.class, () -> photos.resolveStaged(tripB, BORROWER,
                List.of(refTo("someone-elses-photo", null))));
        // The same photo, asked for in a trip the album source does not offer it to.
        Assert.assertThrows(PhotoRejectedException.class, () -> photos.resolveStaged("some-other-trip", BORROWER,
                List.of(refTo(albumRow.getId(), null))));

        // A draft keeps a reference only while it is still offered, with the thumbnail its chip shows.
        final List<ChatPhotos.StagedRef> kept = photos.peekStaged(tripB, BORROWER,
                List.of(refTo(albumRow.getId(), "kept"), refTo("someone-elses-photo", "dropped")));
        Assert.assertEquals(kept.size(), 1);
        Assert.assertEquals(kept.getFirst().ref().title(), "kept");
        Assert.assertEquals(kept.getFirst().smallKey(), albumRow.getDisplayKey());

        // Sending consumes staged uploads; a reference was never one, and the original must survive it.
        photos.consume(List.of(reference()));
        Assert.assertTrue(photos.localGet(original.getS3Key()).isPresent());
    }

    @Test
    public void aReferenceAddsNoRowToTheBorrowingTripsAlbum() {
        photos.recordAlbumRows(tripB, "Trip B", BORROWER, "Borrower", List.of(reference()),
                new AuditActor("borrower@test", BORROWER.getValue()));
        Assert.assertTrue(DAO.getInstance().getMediaInSlot(ChatPhotos.slotFor(tripB), Cached.NO).isEmpty(),
                "The photo already has its row, in the album it was shared in");
        Assert.assertEquals(DAO.getInstance().getMediaInSlot(ChatPhotos.slotFor(tripA), Cached.NO).size(), 1);
    }

    @Test
    public void removingAReferenceLeavesTheOriginalItsRowAndItsFilesAlone() {
        final ChatAttachment ref = reference();

        photos.deleteEverywhere(List.of(ref));
        photos.deleteRenditions(List.of(ref));

        Assert.assertTrue(photos.localGet(original.getS3Key()).isPresent(), "The full-size photo is still stored");
        Assert.assertTrue(photos.localGet(original.getThumbKey()).isPresent(), "And its small copy");
        final List<MediaItem> rows = DAO.getInstance().getMediaInSlot(ChatPhotos.slotFor(tripA), Cached.NO);
        Assert.assertEquals(rows.size(), 1, "Trip A's album still lists it");
        Assert.assertEquals(rows.getFirst().getId(), albumRow.getId());

        // Removed together with an upload of the borrower's own, only the upload goes.
        final ChatPhotos.StagedPhoto own = photos.stage(tripB, BORROWER, PhotoFixtures.jpeg(900, 600));
        final ChatAttachment owned = photos.resolveStaged(tripB, BORROWER,
                List.of(new ChatPhotos.AttachmentRef(own.key(), null))).getFirst();
        final List<ChatAttachment> both = new ArrayList<>(List.of(ref, owned));
        photos.deleteEverywhere(both);
        Assert.assertTrue(photos.localGet(own.key()).isEmpty(), "The borrower's own upload is removed");
        Assert.assertTrue(photos.localGet(original.getS3Key()).isPresent(), "The referenced original is not");

        // And the owner removing the ORIGINAL still removes it, as ever.
        photos.deleteEverywhere(List.of(original));
        Assert.assertTrue(photos.localGet(original.getS3Key()).isEmpty());
        Assert.assertTrue(DAO.getInstance().getMediaInSlot(ChatPhotos.slotFor(tripA), Cached.NO).isEmpty());
    }

    @Test
    public void theMarkSurvivesTheWireAndEveryCopyButATombstone() throws Exception {
        final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        final ChatAttachment ref = reference();

        final String json = mapper.writeValueAsString(ref);
        Assert.assertTrue(json.contains("\"ref\":true"), json);
        Assert.assertTrue(mapper.readValue(json, ChatAttachment.class).isRef());
        Assert.assertFalse(mapper.writeValueAsString(original).contains("\"ref\""),
                "Absent for every ordinary photo, so old rows and old clients see nothing new");
        Assert.assertFalse(mapper.readValue(mapper.writeValueAsString(original), ChatAttachment.class).isRef());

        Assert.assertTrue(ref.withHidden(true).isRef());
        Assert.assertTrue(ref.rotated(900, 1600, 5L).isRef());
        Assert.assertFalse(ref.deleted("Someone").isRef(), "A tombstone names no photo at all");
    }

    @Test
    public void aMessageKnowsWhichOfItsPhotosItOnlyReferences() {
        final ChatAttachment ref = reference();
        final ChatMessage borrowing = Mockito.mock(ChatMessage.class);
        Mockito.when(borrowing.getAttachments()).thenReturn(List.of(ref));
        Assert.assertTrue(ChatPhotoEdits.isReference(borrowing, original.getS3Key()));
        Assert.assertFalse(ChatPhotoEdits.isReference(borrowing, "chat/x/another.jpg"));

        final ChatMessage owning = Mockito.mock(ChatMessage.class);
        Mockito.when(owning.getAttachments()).thenReturn(List.of(original));
        Assert.assertFalse(ChatPhotoEdits.isReference(owning, original.getS3Key()),
                "The message that shared the photo may still rotate and re-flag it");

        final ChatMessage removed = Mockito.mock(ChatMessage.class);
        Mockito.when(removed.getAttachments()).thenReturn(List.of(ref.deleted("Someone")));
        Assert.assertFalse(ChatPhotoEdits.isReference(removed, original.getS3Key()));
    }

    private static ChatMessage carrying(final String id, final ChatAttachment attachment) {
        return new ChatMessage(ChatMessage.Id.from(id), ChatChannel.Id.forTrip("x"), SHARER, Instant.now(),
                ChatMessage.MessageKind.TEXT, "", null, List.of(attachment), null, null, null, null, null, null, 1);
    }

    @Test
    public void aPhotosThreadHangsOffTheMessageThatOwnsItNeverAReference() {
        final ChatAttachment ref = reference();
        final List<ChatMessage> newestFirst = List.of(carrying("2", ref), carrying("1", original));
        Assert.assertEquals(PhotoChatCommands.messageCarrying(newestFirst, original.getS3Key()),
                ChatMessage.Id.from("1"), "The newer message only borrows the photo");
        Assert.assertNull(PhotoChatCommands.messageCarrying(List.of(carrying("2", ref)), original.getS3Key()),
                "A page holding only borrowers holds no parent");
    }

    @Test
    public void aReferenceFollowsItsPhotoWhenItIsTurnedWhereItWasShared() {
        final ChatAttachment ref = reference();
        final ChatPage page = new ChatPage(List.of(carrying("2", ref), carrying("1", original)), null, null, 0L, 0L,
                false, true, null, null);
        Assert.assertSame(photos.withCurrentReferences(page).getMessages().getFirst().getAttachments().getFirst(),
                ref, "Not turned since it was sent: as it was");

        // Turned once where it was shared: the album row moves on, and so does what the reference shows.
        Assert.assertTrue(photos.setAlbumRotated(tripA, original.getS3Key(), 4321L, original.getThumbKey()));
        final ChatAttachment once = photos.withCurrentReferences(page).getMessages().getFirst()
                .getAttachments().getFirst();
        Assert.assertEquals(once.getWidth(), ref.getHeight(), "A quarter turn swaps the dimensions");
        Assert.assertEquals(once.getHeight(), ref.getWidth());
        Assert.assertEquals(once.getRev(), ref.getRev() + 1, "So its address changes and browsers refetch it");
        Assert.assertEquals(once.getSize(), 4321L);
        Assert.assertTrue(once.isRef());
        Assert.assertSame(photos.withCurrentReferences(page).getMessages().get(1), page.getMessages().get(1),
                "The owning message keeps its own facts, rotated with it");

        // A row whose rev went BACK (rebuilt by old code without it) is not a turn: the reference stays put.
        Assert.assertSame(once.followingAlbum(0, 4321L, original.getThumbKey()), once);

        // Turned twice: the shape is back where it was.
        Assert.assertTrue(photos.setAlbumRotated(tripA, original.getS3Key(), 4322L, original.getThumbKey()));
        final ChatAttachment twice = photos.withCurrentReferences(page).getMessages().getFirst()
                .getAttachments().getFirst();
        Assert.assertEquals(twice.getWidth(), ref.getWidth());
        Assert.assertEquals(twice.getRev(), ref.getRev() + 2);

        // A page with no reference is handed back untouched, and a removed reference is left removed.
        final ChatPage plain = new ChatPage(List.of(carrying("1", original)), null, null, 0L, 0L, false, true, null,
                null);
        Assert.assertSame(photos.withCurrentReferences(plain), plain);
        final ChatAttachment gone = ref.deleted("Someone");
        final ChatPage tombstone = new ChatPage(List.of(carrying("3", gone)), null, null, 0L, 0L, false, true, null,
                null);
        Assert.assertSame(photos.withCurrentReferences(tombstone), tombstone);
    }

    @Test
    public void aReferenceWhoseOriginalWasRemovedGoesOutAsATombstoneThatStaysAReference() {
        final ChatAttachment ref = reference();
        final ChatPage page = new ChatPage(List.of(carrying("2", ref)), null, null, 0L, 0L, false, true, null, null);

        // A failed read of the media table must never look like every photo removed: references go out as sent.
        // The read itself throws here, so the real catch is what answers "unreadable".
        final ChatPhotos unreadable = new ChatPhotos(new MediaCommands()) {
            @Override
            protected List<MediaItem> allMedia() {
                throw new IllegalStateException("media table refused");
            }
        };
        Assert.assertTrue(unreadable.mediaRowsByKey().isEmpty(), "Unreadable, which is not the same as no rows");
        Assert.assertSame(unreadable.withCurrentReferences(page), page);

        // A photo moved out of its trip album (to a library slot) is still there: its references keep showing it.
        final MediaItem moved = albumRow.withPlacement("home-images", 0, albumRow.getUploaded());
        Assert.assertTrue(DAO.getInstance().saveMedia(moved));
        Assert.assertFalse(photos.withCurrentReferences(page).getMessages().getFirst().getAttachments().getFirst()
                .isDeleted(), "A moved photo is not a removed one");

        // Hidden since it was referenced (kept off the public site): Choose from Albums would no longer offer it, so
        // the reference stops showing it to the other trip's chat too. Shown again, it comes back.
        Assert.assertTrue(DAO.getInstance().saveMedia(moved.withHidden(true)));
        final ChatAttachment hidden =
                photos.withCurrentReferences(page).getMessages().getFirst().getAttachments().getFirst();
        Assert.assertTrue(hidden.isDeleted() && hidden.isRef(), "A hidden original's reference is a tombstone");
        Assert.assertNull(hidden.getS3Key(), "No key left to fetch the hidden photo by");
        Assert.assertTrue(DAO.getInstance().saveMedia(moved.withHidden(false)));
        Assert.assertFalse(photos.withCurrentReferences(page).getMessages().getFirst().getAttachments().getFirst()
                .isDeleted(), "Nothing was rewritten: shown again, the reference shows it again");

        photos.deleteEverywhere(List.of(original));
        final ChatAttachment gone =
                photos.withCurrentReferences(page).getMessages().getFirst().getAttachments().getFirst();
        Assert.assertTrue(gone.isDeleted(), "A tombstone, so a client that cached the photo drops it");
        Assert.assertTrue(gone.isRef(), "Still a reference: the client says the photo is no longer available");
        Assert.assertNull(gone.getS3Key(), "No key left to fetch the removed photo by");
        Assert.assertNull(gone.getThumbKey());
        Assert.assertNull(gone.getWidth());
        Assert.assertNull(gone.getDeletedBy(), "Nobody here knows who removed the original");
        Assert.assertEquals(photos.withCurrentReferences(page).getMessages().getFirst().getAttachments().getFirst(),
                gone, "Every read of the message says the same");
    }
}
