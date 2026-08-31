package org.paulsens.trip.action;

import java.io.IOException;
import java.time.Instant;
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
import org.paulsens.trip.model.chat.ChatDraft;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Composer drafts end-to-end: autosave (lenient clamping and pruning), restore (with expired-reference
 * pruning written back), the send clearing the draft, and the two indicator reads. Uses the SHARED
 * {@link ChatPhotos} instance like {@code ChatPhotoSendTest} — the staging registry it holds is the one
 * authority a draft's attachment references are validated against.
 */
public class ChatDraftTest {

    private String tripId;
    private ChatCommands chat;
    private ChatPhotos photos;
    private AuditActor actor;
    private Person.Id member;
    private Person.Id stranger;

    @BeforeMethod
    public void setUp() throws IOException {
        DAO.getInstance();
        tripId = java.util.UUID.randomUUID().toString();
        member = person("Drafter");
        stranger = person("Outsider");
        seedTrip();
        chat = new ChatCommands(new ChatRateLimiter(new InMemoryCacheClient()));
        photos = ChatPhotos.getChatPhotos();
        actor = new AuditActor("drafter@test", member.getValue());
    }

    private static Person.Id person(final String first) {
        final PersonCommands people = new PersonCommands();
        final Person who = people.createPerson();
        who.setFirst(first);
        who.setLast("Composer");
        Assert.assertTrue(people.savePerson(who));
        return who.getId();
    }

    private void seedTrip() throws IOException {
        final Trip trip = Trip.builder()
                .id(tripId)
                .title("Draft trip")
                .openToPublic(false)
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().plusDays(14))
                .people(new ArrayList<>(List.of(member)))
                .build();
        Assert.assertTrue(DAO.getInstance().saveTrip(trip), "test setup: save dedicated trip");
    }

    private ChatPhotos.StagedPhoto stageOne(final Person.Id uploader) {
        return photos.stage(tripId, uploader, PhotoFixtures.jpeg(1600, 900));
    }

    @Test
    public void aDraftRoundTripsWithItsPhoto() {
        final ChatPhotos.StagedPhoto staged = stageOne(member);

        final ChatCommands.DraftResult saved = chat.saveDraft(tripId, member, "hello there",
                List.of(new ChatPhotos.AttachmentRef(staged.key(), "Sunset", true)));

        Assert.assertTrue(saved.ok(), String.valueOf(saved.message()));
        Assert.assertFalse(saved.cleared());
        Assert.assertEquals(saved.attachments(), 1);
        Assert.assertTrue(chat.hasDraft(tripId, member));

        final ChatCommands.DraftView view = chat.draftForTrip(tripId, member);
        Assert.assertNotNull(view);
        Assert.assertEquals(view.body(), "hello there");
        Assert.assertEquals(view.pruned(), 0);
        Assert.assertEquals(view.attachments().size(), 1);
        Assert.assertEquals(view.attachments().get(0).key(), staged.key());
        Assert.assertEquals(view.attachments().get(0).smallKey(), staged.smallKey());
        Assert.assertEquals(view.attachments().get(0).title(), "Sunset");
        Assert.assertEquals(view.attachments().get(0).hidden(), Boolean.TRUE);
    }

    @Test
    public void anEmptySaveClearsTheDraft() {
        Assert.assertTrue(chat.saveDraft(tripId, member, "something", List.of()).ok());
        Assert.assertTrue(chat.hasDraft(tripId, member));

        final ChatCommands.DraftResult cleared = chat.saveDraft(tripId, member, "   ", List.of());

        Assert.assertTrue(cleared.ok());
        Assert.assertTrue(cleared.cleared());
        Assert.assertFalse(chat.hasDraft(tripId, member));
        Assert.assertNull(chat.draftForTrip(tripId, member));
    }

    @Test
    public void aNonParticipantMayNotSaveADraft() {
        final ChatCommands.DraftResult refused = chat.saveDraft(tripId, stranger, "let me in", List.of());

        Assert.assertFalse(refused.ok());
        Assert.assertEquals(refused.code(), "forbidden");
        Assert.assertFalse(chat.hasDraft(tripId, stranger));
    }

    @Test
    public void aDisabledChatRefusesDrafts() throws IOException {
        final Trip trip = DAO.getInstance().getTrip(tripId, org.paulsens.trip.cache.Cached.NO).orElseThrow();
        trip.setChatEnabled(false);
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));

        final ChatCommands.DraftResult refused = chat.saveDraft(tripId, member, "anyone?", List.of());

        Assert.assertFalse(refused.ok());
        Assert.assertEquals(refused.code(), "disabled");
    }

    @Test
    public void theBodyIsClampedNeverRefused() {
        final String longBody = "x".repeat(5000);

        final ChatCommands.DraftResult saved = chat.saveDraft(tripId, member, longBody, List.of());

        Assert.assertTrue(saved.ok());
        // The channel default cap is 4000 chars; an autosave clamps rather than bouncing mid-typing.
        Assert.assertEquals(chat.draftForTrip(tripId, member).body().length(), 4000);
    }

    @Test
    public void unclaimableReferencesAreDroppedAtSave() {
        final ChatPhotos.StagedPhoto someoneElses = stageOne(stranger);

        final ChatCommands.DraftResult saved = chat.saveDraft(tripId, member, "mine says",
                List.of(new ChatPhotos.AttachmentRef("chat/" + tripId + "/never-staged.jpg", null, null),
                        new ChatPhotos.AttachmentRef(someoneElses.key(), "not mine", null)));

        Assert.assertTrue(saved.ok());
        Assert.assertEquals(saved.attachments(), 0, "neither an unstaged nor a foreign key is claimable");
        Assert.assertEquals(chat.draftForTrip(tripId, member).body(), "mine says");
    }

    @Test
    public void restorePrunesExpiredReferencesAndWritesThePruneBack() {
        // A stored draft whose reference is no longer claimable — the photo aged out after the save.
        final ChatChannel.Id cid = ChatChannel.Id.forTrip(tripId);
        Assert.assertTrue(DAO.getInstance().saveChatDraft(cid, member, new ChatDraft("still here",
                List.of(new ChatDraft.Ref("chat/" + tripId + "/aged-out.jpg", "Gone", null)), Instant.now())));

        final ChatCommands.DraftView first = chat.draftForTrip(tripId, member);
        Assert.assertEquals(first.body(), "still here");
        Assert.assertEquals(first.pruned(), 1);
        Assert.assertTrue(first.attachments().isEmpty());

        // The prune stuck: the next restore no longer reports a loss.
        Assert.assertEquals(chat.draftForTrip(tripId, member).pruned(), 0);
    }

    @Test
    public void aDraftReducedToNothingByPruningReportsOnceThenIsGone() {
        final ChatChannel.Id cid = ChatChannel.Id.forTrip(tripId);
        Assert.assertTrue(DAO.getInstance().saveChatDraft(cid, member, new ChatDraft("",
                List.of(new ChatDraft.Ref("chat/" + tripId + "/aged-out.jpg", null, null)), Instant.now())));

        final ChatCommands.DraftView first = chat.draftForTrip(tripId, member);
        Assert.assertNotNull(first, "the one-visit view must still say the photos were lost");
        Assert.assertEquals(first.pruned(), 1);

        Assert.assertFalse(chat.hasDraft(tripId, member));
        Assert.assertNull(chat.draftForTrip(tripId, member));
    }

    @Test
    public void aSuccessfulSendDeletesTheDraft() {
        Assert.assertTrue(chat.saveDraft(tripId, member, "about to send", List.of()).ok());

        final ChatCommands.SendResult sent = chat.send(tripId, member, "about to send", null, null, actor);

        Assert.assertTrue(sent.isOk(), String.valueOf(sent.getMessage()));
        Assert.assertFalse(chat.hasDraft(tripId, member));
    }

    @Test
    public void clearDraftRemovesIt() {
        Assert.assertTrue(chat.saveDraft(tripId, member, "temp", List.of()).ok());

        Assert.assertTrue(chat.clearDraft(tripId, member));

        Assert.assertFalse(chat.hasDraft(tripId, member));
        Assert.assertFalse(chat.clearDraft(tripId, null), "null person refused, not thrown");
    }

    @Test
    public void myChatsCarriesTheDraftFlag() {
        // The channel must exist for a My Chats row — the first send creates it.
        Assert.assertTrue(chat.send(tripId, member, "hello", null, null, actor).isOk());
        Assert.assertTrue(chat.saveDraft(tripId, member, "unfinished thought", List.of()).ok());

        final List<ChatCommands.ChatSummary> mine = chat.myChats(member);
        final ChatCommands.ChatSummary summary = mine.stream()
                .filter(s -> tripId.equals(s.channel().getTripId()))
                .findFirst().orElseThrow();

        Assert.assertTrue(summary.draft());
    }

    @Test
    public void withoutASessionTheTabLabelIsPlainAndJsonIsNull() {
        // No FacesContext in unit tests, so "current user" is null: every indicator must fail closed.
        Assert.assertEquals(chat.chatTabLabelForCurrentUser(tripId), "Chat");
        Assert.assertFalse(chat.hasDraftForCurrentUser(tripId));
        Assert.assertEquals(chat.draftJsonForTrip(tripId), "null");
    }

    @Test
    public void draftModelNormalizesNulls() {
        final ChatDraft draft = new ChatDraft(null, null, null);
        Assert.assertEquals(draft.body(), "");
        Assert.assertTrue(draft.attachments().isEmpty());
        Assert.assertTrue(draft.isEmpty());
        Assert.assertFalse(new ChatDraft("x", null, null).isEmpty());
        Assert.assertFalse(new ChatDraft(null,
                List.of(new ChatDraft.Ref("k", null, null)), null).isEmpty());
    }
}
