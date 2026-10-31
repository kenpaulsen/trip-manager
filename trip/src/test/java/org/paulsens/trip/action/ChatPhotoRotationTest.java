package org.paulsens.trip.action;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.media.PhotoFixtures;
import org.paulsens.trip.media.QuarterTurn;
import org.paulsens.trip.media.RotationGuard;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatAttachment;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Rotating a posted chat photo end to end against the local store and a real channel: the shared
 * {@link ChatPhotos} the send path uses, so the bytes the rotation turns are the bytes the chat serves.
 */
public class ChatPhotoRotationTest {

    private String tripId;
    private ChatCommands chat;
    private ChatPhotos photos;
    private AuditActor actor;
    private Person.Id member;
    private Caller author;
    private final List<String> removed = new CopyOnWriteArrayList<>();

    @BeforeMethod
    public void setUp() throws IOException {
        DAO.getInstance();
        tripId = UUID.randomUUID().toString();
        member = person("Turner");
        final Person.Id adminId = person("Moderator");
        final Trip trip = Trip.builder().id(tripId).title("Rotate trip").openToPublic(false)
                .startDate(LocalDateTime.now()).endDate(LocalDateTime.now().plusDays(14))
                .people(new ArrayList<>(List.of(member, adminId))).build();
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        chat = new ChatCommands(new ChatRateLimiter(new InMemoryCacheClient()));
        photos = ChatPhotos.getChatPhotos();
        actor = new AuditActor("chatmgr@test", adminId.getValue());
        author = Caller.forActor(new AuditActor("turner@test", member.getValue()));
        final PrivilegeCommands privs = new PrivilegeCommands();
        Assert.assertTrue(privs.savePrivilege(
                privs.createPrivilege("chatMgr", "Chat manager", tripId, List.of(adminId))));
        removed.clear();
        MediaEvents.onPrefix(ChatPhotos.KEY_PREFIX + tripId + "/", this::recordRemoval);
    }

    private void recordRemoval(final MediaEvents.Change change, final String key) {
        if (change == MediaEvents.Change.REMOVED) {
            removed.add(key);
        }
    }

    private static Person.Id person(final String first) {
        final PersonCommands people = new PersonCommands();
        final Person who = people.createPerson();
        who.setFirst(first);
        who.setLast("Photog");
        Assert.assertTrue(people.savePerson(who));
        return who.getId();
    }

    /** Stages and sends one photo as {@link #member}; answers the sent message. */
    private ChatMessage post(final byte[] bytes) {
        final ChatPhotos.StagedPhoto staged = photos.stage(tripId, member, bytes);
        final ChatCommands.SendResult sent = chat.send(tripId, member, "", null, null, actor,
                List.of(new ChatPhotos.AttachmentRef(staged.key(), "turn me")));
        Assert.assertTrue(sent.isOk(), String.valueOf(sent.getMessage()));
        return sent.getMessageObj();
    }

    private ChatMessage reread(final ChatMessage.Id msgId) {
        return DAO.getInstance().getChatMessage(ChatChannel.Id.forTrip(tripId), msgId, Cached.NO).orElseThrow();
    }

    private MediaItem albumRow(final String key) {
        return DAO.getInstance().getMediaInSlot(ChatPhotos.slotFor(tripId), Cached.NO).stream()
                .filter(row -> key.equals(row.getS3Key())).findFirst().orElseThrow();
    }

    private long mutationsVersion() {
        final ChatChannel channel = chat.getChannel(tripId);
        return DAO.getInstance().getChatMessagesBefore(channel.getId(), null, 50, null, channel, null,
                Instant.now(), Cached.NO).getMutationsVersion();
    }

    private BufferedImage served(final String key) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(photos.localGet(key).orElseThrow().bytes()));
    }

    private ChatPhotoRotation.Outcome rotate(final String key, final QuarterTurn turn, final Caller caller) {
        return ChatPhotoRotation.rotate(chat, tripId, key, turn, null, caller);
    }

    @Test
    public void theAuthorRotatesAndEveryCopyFollowsWhileTheKeyStays() throws IOException {
        final ChatMessage sent = post(PhotoFixtures.jpeg(1400, 1000));
        final ChatAttachment before = sent.getAttachments().get(0);
        final String key = before.getS3Key();
        Assert.assertNotNull(DAO.getInstance().getChatChannel(ChatChannel.Id.forPhoto(key), Cached.NO)
                .orElse(null), "test setup: the photo's comment channel exists");
        final long versionBefore = mutationsVersion();

        final ChatPhotoRotation.Outcome outcome = ChatPhotoRotation.rotate(chat, tripId, key, QuarterTurn.RIGHT,
                sent.getId(), author);

        Assert.assertTrue(outcome.ok(), outcome.message());
        final ChatAttachment after = outcome.attachment();
        Assert.assertEquals(after.getS3Key(), key, "the key is the photo's identity");
        Assert.assertEquals(after.getThumbKey(), before.getThumbKey());
        Assert.assertEquals(after.getWidth(), Integer.valueOf(1000));
        Assert.assertEquals(after.getHeight(), Integer.valueOf(1400));
        Assert.assertEquals(after.getRev(), 1);
        Assert.assertEquals(after.getCaption(), "turn me");
        Assert.assertEquals(reread(sent.getId()).getAttachments().get(0), after, "the message was rewritten");
        Assert.assertTrue(mutationsVersion() > versionBefore, "polling clients must refetch the message");
        final BufferedImage full = served(key);
        Assert.assertEquals(full.getWidth(), 1000);
        Assert.assertEquals(full.getHeight(), 1400);
        Assert.assertEquals(served(after.getThumbKey()).getWidth(), 800);
        final MediaItem row = albumRow(key);
        Assert.assertEquals(row.getRev(), 1);
        Assert.assertEquals(row.getCacheBuster(), "?r=1");
        Assert.assertEquals(row.getSize(), after.getSize());
        Assert.assertNotNull(DAO.getInstance().getChatChannel(ChatChannel.Id.forPhoto(key), Cached.NO)
                .orElse(null), "the comment thread survives");
        Assert.assertTrue(removed.isEmpty(), "a REMOVED event would purge the comment thread: " + removed);
        Assert.assertNull(reread(sent.getId()).getEditedAt(), "a rotation is not an edit");
    }

    @Test
    public void twoTurnsMakeRevTwoAndALeftUndoesARight() throws IOException {
        final ChatMessage sent = post(PhotoFixtures.jpeg(1400, 1000));
        final String key = sent.getAttachments().get(0).getS3Key();
        Assert.assertTrue(rotate(key, QuarterTurn.RIGHT, author).ok());

        final ChatPhotoRotation.Outcome back = rotate(key, QuarterTurn.LEFT, author);

        Assert.assertTrue(back.ok());
        Assert.assertEquals(back.attachment().getRev(), 2);
        Assert.assertEquals(back.attachment().getWidth(), Integer.valueOf(1400));
        Assert.assertEquals(served(key).getWidth(), 1400);
        Assert.assertEquals(albumRow(key).getRev(), 2);
    }

    @Test
    public void aChatAdminRotatesSomeoneElsesPhoto() {
        final ChatMessage sent = post(PhotoFixtures.jpeg(900, 600));
        Assert.assertTrue(rotate(sent.getAttachments().get(0).getS3Key(), QuarterTurn.LEFT,
                Caller.forActor(actor)).ok());
    }

    @Test
    public void aStrangerIsRefusedAndNothingMoves() throws IOException {
        final ChatMessage sent = post(PhotoFixtures.jpeg(900, 600));
        final String key = sent.getAttachments().get(0).getS3Key();
        final Caller stranger = Caller.forActor(new AuditActor("nosy@test", person("Nosy").getValue()));

        final ChatPhotoRotation.Outcome outcome = rotate(key, QuarterTurn.LEFT, stranger);

        Assert.assertEquals(outcome.code(), ChatPhotoRotation.Outcome.FORBIDDEN);
        Assert.assertEquals(outcome.message(), "You can only rotate photos you posted.");
        Assert.assertNull(outcome.attachment());
        Assert.assertEquals(served(key).getWidth(), 900);
        Assert.assertEquals(reread(sent.getId()).getAttachments().get(0).getRev(), 0);
    }

    @Test
    public void anUnknownPhotoIsNotFound() {
        post(PhotoFixtures.jpeg(900, 600));
        Assert.assertEquals(rotate("chat/" + tripId + "/no-such.jpg", QuarterTurn.LEFT, author).code(),
                ChatPhotoRotation.Outcome.NOT_FOUND);
    }

    @Test
    public void aPhotoWhoseBytesAreGoneIsNotFound() {
        final ChatMessage sent = post(PhotoFixtures.jpeg(900, 600));
        photos.deleteRenditions(sent.getAttachments());
        Assert.assertEquals(rotate(sent.getAttachments().get(0).getS3Key(), QuarterTurn.LEFT, author).code(),
                ChatPhotoRotation.Outcome.NOT_FOUND);
    }

    @Test
    public void anAnimatedGifIsRejectedWithTheProcessorsReason() {
        final ChatMessage sent = post(PhotoFixtures.animatedGif(300, 200));
        final ChatPhotoRotation.Outcome outcome = rotate(sent.getAttachments().get(0).getS3Key(),
                QuarterTurn.RIGHT, author);
        Assert.assertEquals(outcome.code(), ChatPhotoRotation.Outcome.REJECTED);
        Assert.assertEquals(outcome.message(), "Animated GIFs can't be rotated.");
    }

    @Test
    public void aSecondRotationWhileOneRunsIsBusyAndTheGuardReleases() {
        final ChatMessage sent = post(PhotoFixtures.jpeg(900, 600));
        final String key = sent.getAttachments().get(0).getS3Key();
        Assert.assertTrue(RotationGuard.begin(key));
        try {
            Assert.assertEquals(rotate(key, QuarterTurn.LEFT, author).code(), ChatPhotoRotation.Outcome.BUSY);
        } finally {
            RotationGuard.end(key);
        }
        Assert.assertTrue(rotate(key, QuarterTurn.LEFT, author).ok(), "released after the refusal");
    }

    /** A narrow portrait stored ONE object; turned landscape it is too wide and gets its own -small key. */
    @Test
    public void aPhotoThatNowNeedsASmallRenditionGetsTheKeyAnUploadWouldHaveMinted() throws IOException {
        final ChatMessage sent = post(PhotoFixtures.jpeg(700, 1000));
        final ChatAttachment before = sent.getAttachments().get(0);
        Assert.assertEquals(before.getThumbKey(), before.getS3Key(), "test setup: one object");

        final ChatPhotoRotation.Outcome outcome = rotate(before.getS3Key(), QuarterTurn.LEFT, author);

        Assert.assertTrue(outcome.ok());
        final String thumb = outcome.attachment().getThumbKey();
        Assert.assertEquals(thumb, before.getS3Key().replace(".jpg", "-small.jpg"));
        Assert.assertEquals(served(thumb).getWidth(), 800);
        Assert.assertEquals(served(before.getS3Key()).getWidth(), 1000);
        Assert.assertEquals(albumRow(before.getS3Key()).getSmallKey(), thumb);
    }

    /** The reverse: a separate small key is kept (holding the full bytes) rather than moved. */
    @Test
    public void aSmallKeyIsKeptWhenTheTurnNoLongerNeedsIt() throws IOException {
        final ChatMessage sent = post(PhotoFixtures.jpeg(1000, 700));
        final ChatAttachment before = sent.getAttachments().get(0);
        Assert.assertNotEquals(before.getThumbKey(), before.getS3Key(), "test setup: two objects");

        final ChatPhotoRotation.Outcome outcome = rotate(before.getS3Key(), QuarterTurn.RIGHT, author);

        Assert.assertTrue(outcome.ok());
        Assert.assertEquals(outcome.attachment().getThumbKey(), before.getThumbKey());
        Assert.assertEquals(served(before.getThumbKey()).getWidth(), 700);
    }

    @Test
    public void aStoreThatRefusesTheBytesIsAStoreFailure() {
        final ChatMessage sent = post(PhotoFixtures.jpeg(900, 600));
        final String key = sent.getAttachments().get(0).getS3Key();
        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.getObject(key)).thenReturn(Optional.of(PhotoFixtures.jpeg(900, 600)));
        final ChatPhotos remote = new ChatPhotos(media);

        final ChatPhotoRotation.Outcome outcome = ChatPhotoRotation.rotate(chat, remote, tripId, key,
                QuarterTurn.LEFT, null, author);

        Assert.assertEquals(outcome.code(), ChatPhotoRotation.Outcome.STORE_FAILED);
        Assert.assertEquals(reread(sent.getId()).getAttachments().get(0).getRev(), 0);
    }

    @Test
    public void aMessageThatCannotBeRewrittenIsAStoreFailure() {
        final ChatMessage sent = post(PhotoFixtures.jpeg(900, 600));
        final String key = sent.getAttachments().get(0).getS3Key();
        final ChatChannel channel = chat.getChannel(tripId);
        final DAO dao = Mockito.mock(DAO.class);
        Mockito.when(dao.getChatChannel(channel.getId(), Cached.NO)).thenReturn(Optional.of(channel));
        Mockito.when(dao.getChatMessage(channel.getId(), sent.getId(), Cached.NO)).thenReturn(Optional.of(sent));
        Mockito.when(dao.rotateChatAttachment(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        final ChatCommands stubbed = Mockito.spy(chat);
        Mockito.doReturn(dao).when(stubbed).dao();

        final ChatPhotoRotation.Outcome outcome = ChatPhotoRotation.rotate(stubbed, photos, tripId, key,
                QuarterTurn.LEFT, sent.getId(), author);

        Assert.assertEquals(outcome.code(), ChatPhotoRotation.Outcome.STORE_FAILED);
    }
}
