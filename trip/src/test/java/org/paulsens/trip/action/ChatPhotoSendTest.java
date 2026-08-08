package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.media.PhotoFixtures;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatSettings;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Sending photos end-to-end against the local store: stage (as the upload servlet would), reference from a
 * send, and observe the message, the album rows, and the moderation cascade. Uses the SHARED
 * {@link ChatPhotos} instance deliberately — that is the instance the send path consults, and these tests
 * prove the wiring, not just the parts.
 */
public class ChatPhotoSendTest {

    private String tripId;
    private ChatCommands chat;
    private ChatPhotos photos;
    private AuditActor actor;
    private Person.Id member;
    private Person.Id adminId;

    @BeforeMethod
    public void setUp() throws IOException {
        DAO.getInstance();
        tripId = java.util.UUID.randomUUID().toString();
        member = person("Shutterbug");
        adminId = person("Moderator");
        seedTrip();
        chat = new ChatCommands(new ChatRateLimiter(new InMemoryCacheClient()));
        photos = ChatPhotos.getChatPhotos();
        actor = new AuditActor("chatmgr@test", adminId.getValue());
        final PrivilegeCommands privs = new PrivilegeCommands();
        Assert.assertTrue(privs.savePrivilege(
                privs.createPrivilege("chatMgr", "Chat manager", tripId, List.of(adminId))));
    }

    private static Person.Id person(final String first) {
        final PersonCommands people = new PersonCommands();
        final Person who = people.createPerson();
        who.setFirst(first);
        who.setLast("Photog");
        Assert.assertTrue(people.savePerson(who));
        return who.getId();
    }

    private void seedTrip() throws IOException {
        final Trip trip = Trip.builder()
                .id(tripId)
                .title("Photo trip")
                .openToPublic(false)
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().plusDays(14))
                .people(new ArrayList<>(List.of(member, adminId)))
                .build();
        Assert.assertTrue(DAO.getInstance().saveTrip(trip), "test setup: save dedicated trip");
    }

    private ChatPhotos.StagedPhoto stageOne() {
        return photos.stage(tripId, member, PhotoFixtures.jpeg(1600, 900));
    }

    @Test
    public void aPhotoOnlyMessageSendsAndFilesIntoTheAlbum() {
        final ChatPhotos.StagedPhoto staged = stageOne();

        final ChatCommands.SendResult sent = chat.send(tripId, member, "", null, null, actor,
                List.of(new ChatPhotos.AttachmentRef(staged.key(), "The blue cross")));

        Assert.assertTrue(sent.isOk(), String.valueOf(sent.getMessage()));
        final ChatMessage stored = sent.getMessageObj();
        Assert.assertEquals(stored.getKind(), ChatMessage.MessageKind.MEDIA);
        Assert.assertEquals(stored.getAttachments().size(), 1);
        Assert.assertEquals(stored.getAttachments().get(0).getS3Key(), staged.key());
        Assert.assertEquals(stored.getAttachments().get(0).getThumbKey(), staged.smallKey());
        Assert.assertEquals(stored.getAttachments().get(0).getCaption(), "The blue cross");
        Assert.assertEquals(DAO.getInstance().getMediaInSlot(ChatPhotos.slotFor(tripId)).size(), 1,
                "the send is what creates the album row");
    }

    @Test
    public void aStagedPhotoIsSingleUseAcrossSends() {
        final ChatPhotos.StagedPhoto staged = stageOne();
        Assert.assertTrue(chat.send(tripId, member, "with photo", null, null, actor,
                List.of(new ChatPhotos.AttachmentRef(staged.key(), null))).isOk());

        final ChatCommands.SendResult again = chat.send(tripId, member, "same photo again", null, null,
                actor, List.of(new ChatPhotos.AttachmentRef(staged.key(), null)));

        Assert.assertFalse(again.isOk());
        Assert.assertEquals(again.getCode(), "attachment");
    }

    @Test
    public void anUnstagedReferenceFailsTheSendLoudly() {
        final ChatCommands.SendResult result = chat.send(tripId, member, "hi", null, null, actor,
                List.of(new ChatPhotos.AttachmentRef("chat/" + tripId + "/forged.jpg", null)));

        Assert.assertFalse(result.isOk());
        Assert.assertEquals(result.getCode(), "attachment");
    }

    @Test
    public void theCapIsCheckedBeforeAnyStagingLookup() {
        final List<ChatPhotos.AttachmentRef> tooMany = new ArrayList<>();
        for (int i = 0; i < ChatSettings.DEFAULT_MAX_ATTACHMENTS_PER_MESSAGE + 1; i++) {
            tooMany.add(new ChatPhotos.AttachmentRef("chat/" + tripId + "/x" + i + ".jpg", null));
        }

        final ChatCommands.SendResult result = chat.send(tripId, member, "", null, null, actor, tooMany);

        Assert.assertFalse(result.isOk());
        Assert.assertEquals(result.getCode(), "too_many_photos");
    }

    @Test
    public void anEmptyMessageWithNoPhotosIsStillEmpty() {
        final ChatCommands.SendResult result = chat.send(tripId, member, "", null, null, actor, List.of());
        Assert.assertFalse(result.isOk());
        Assert.assertEquals(result.getCode(), "empty");
    }

    @Test
    public void turningMediaOffRefusesPhotosButNotText() {
        chat.ensureChannel(tripId, actor);
        Assert.assertTrue(chat.updateSettings(tripId,
                ChatSettings.builder().allowMedia(false).build(), Caller.forActor(actor)));
        final ChatPhotos.StagedPhoto staged = stageOne();

        final ChatCommands.SendResult photo = chat.send(tripId, member, "", null, null, actor,
                List.of(new ChatPhotos.AttachmentRef(staged.key(), null)));
        Assert.assertFalse(photo.isOk());
        Assert.assertEquals(photo.getCode(), "attachment");

        Assert.assertTrue(chat.send(tripId, member, "words still work", null, null, actor, List.of()).isOk());
        Assert.assertEquals(chat.maxPhotosForTrip(tripId), 0, "0 is what hides the Attach button");
    }

    /** The admin settings page's save path controls the photo switches (added 2026-08-07, user request). */
    @Test
    public void adminSettingsSaveControlsThePhotoSwitches() {
        final ChatChannel channel = chat.ensureChannel(tripId, actor);
        final Caller admin = Caller.forActor(actor);

        final ChatSettings photosOff = ChatCommands.settingsFromForm(channel.getSettings(), null, null,
                null, null, null, null, null, null, null, null, "forever", null, null, null,
                false, 10, 10);
        Assert.assertTrue(chat.updateSettings(tripId, photosOff, admin));
        Assert.assertEquals(chat.maxPhotosForTrip(tripId), 0, "unchecking Allow photos hides the button");
        // A photos-off save must never resemble the v1 reserved fingerprint the constructor upgrades.
        Assert.assertFalse(photosOff.isAllowMedia());
        Assert.assertEquals(photosOff.getMaxAttachmentBytes(), ChatSettings.DEFAULT_MAX_ATTACHMENT_BYTES,
                "modern photos-off keeps the caps, so it cannot be mistaken for a v1 row");

        final ChatSettings capped = ChatCommands.settingsFromForm(chat.getChannel(tripId).getSettings(),
                null, null, null, null, null, null, null, null, null, null, "forever", null, null, null,
                true, 5, 8);
        Assert.assertTrue(chat.updateSettings(tripId, capped, admin));
        Assert.assertEquals(chat.maxPhotosForTrip(tripId), 5);
        Assert.assertEquals(chat.getChannel(tripId).getSettings().getMaxAttachmentBytes(),
                8L * 1024 * 1024, "the page speaks MB; the setting stores bytes");

        final ChatSettings blanks = ChatCommands.settingsFromForm(chat.getChannel(tripId).getSettings(),
                null, null, null, null, null, null, null, null, null, null, "forever", null, null, null,
                null, null, null);
        Assert.assertTrue(blanks.isAllowMedia(), "blank form fields mean the defaults");
        Assert.assertEquals(blanks.getMaxAttachmentsPerMessage(),
                ChatSettings.DEFAULT_MAX_ATTACHMENTS_PER_MESSAGE);
    }

    @Test
    public void checkAttachMirrorsTheSendGates() {
        Assert.assertNull(chat.checkAttach(tripId, member).denial(), "a member may attach");
        Assert.assertNotNull(chat.checkAttach(tripId, person("Stranger")).denial());
        Assert.assertNull(chat.getChannel(tripId),
                "checkAttach must not create the channel: composing is not posting");
        Assert.assertEquals(chat.maxPhotosForTrip(tripId), ChatSettings.DEFAULT_MAX_ATTACHMENTS_PER_MESSAGE);
    }

    @Test
    public void removingAMessageRemovesItsPhotosEverywhere() {
        final ChatPhotos.StagedPhoto staged = stageOne();
        final ChatCommands.SendResult sent = chat.send(tripId, member, "", null, null, actor,
                List.of(new ChatPhotos.AttachmentRef(staged.key(), "to be moderated")));
        Assert.assertTrue(sent.isOk());
        final ChatMessage.Id msgId = sent.getMessageObj().getId();
        Assert.assertTrue(photos.localGet(staged.key()).isPresent(), "premise: object stored");

        Assert.assertTrue(chat.deleteMessage(tripId, msgId, Caller.forActor(actor)));

        Assert.assertTrue(photos.localGet(staged.key()).isEmpty(), "full rendition gone");
        Assert.assertTrue(photos.localGet(staged.smallKey()).isEmpty(), "small rendition gone");
        Assert.assertEquals(DAO.getInstance().getMediaInSlot(ChatPhotos.slotFor(tripId)).size(), 0,
                "album row gone");
        final ChatMessage tomb = DAO.getInstance()
                .getChatMessage(ChatChannel.Id.forTrip(tripId), msgId).orElseThrow();
        Assert.assertTrue(tomb.isDeleted());
        Assert.assertTrue(tomb.getAttachments().isEmpty(),
                "a tombstone must not keep keys pointing at deleted objects");
    }

    @Test
    public void authorsModerateTheirOwnPhotosByDeletingTheMessage() {
        final ChatPhotos.StagedPhoto staged = stageOne();
        final ChatCommands.SendResult sent = chat.send(tripId, member, "oops", null, null, actor,
                List.of(new ChatPhotos.AttachmentRef(staged.key(), null)));
        Assert.assertTrue(sent.isOk());

        final Caller author = Caller.forActor(new AuditActor("shutter@test", member.getValue()));
        Assert.assertTrue(chat.deleteMessage(tripId, sent.getMessageObj().getId(), author));
        Assert.assertTrue(photos.localGet(staged.key()).isEmpty());
    }
}
