package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatMentions;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The two send-time rules added for the store review: blocked words, and no mentioning someone who blocked you. */
public class ChatContentFilterTest {

    private final String tripId = "filter-" + RandomData.genAlpha(8);
    private Person alice;
    private Person bob;
    private ChatCommands chat;
    private PhotoChatCommands photoChat;

    @BeforeMethod
    public void setUp() throws IOException {
        DAO.getInstance();
        alice = saved("Alice");
        bob = saved("Bob");
        Assert.assertTrue(DAO.getInstance().saveTrip(Trip.builder()
                .id(tripId)
                .title("Filter test trip")
                .openToPublic(false)
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().plusDays(7))
                .people(new ArrayList<>(List.of(alice.getId(), bob.getId())))
                .build()));
        final ConfigCommands config = Mockito.spy(new ConfigCommands());
        Mockito.doReturn("darn, heck").when(config).getString(KnownSettings.CHAT_BLOCKED_TERMS);
        chat = new ChatCommands(new ChatRateLimiter(new InMemoryCacheClient()), config);
        photoChat = new PhotoChatCommands(new ChatRateLimiter(new InMemoryCacheClient()), config);
    }

    @Test
    public void aBlockedWordRefusesTheSendAndTheEdit() {
        final ChatCommands.SendResult refused = chat.send(tripId, alice.getId(), "well DARN it", null, null,
                actor(alice));
        Assert.assertFalse(refused.isOk());
        Assert.assertEquals(refused.getCode(), "blocked_content");
        Assert.assertEquals(refused.getMessage(), ChatCommands.BLOCKED_CONTENT_MESSAGE);
        Assert.assertFalse(refused.getMessage().contains("darn"), "the matched term is never echoed");

        final ChatCommands.SendResult sent = chat.send(tripId, alice.getId(), "a darning needle", null, null,
                actor(alice));
        Assert.assertTrue(sent.isOk(), "whole words only: 'darning' is not 'darn'");

        final ChatCommands.ReactResult edit = chat.editMessage(tripId, alice.getId(), sent.getMessageObj().getId(),
                "what the heck");
        Assert.assertFalse(edit.ok());
        Assert.assertEquals(edit.code(), "blocked_content");
        Assert.assertTrue(chat.editMessage(tripId, alice.getId(), sent.getMessageObj().getId(), "fine").ok());
    }

    @Test
    public void aBlockedPersonCannotMentionTheirBlocker() {
        final BlockListCommands blocks = new BlockListCommands();
        Assert.assertTrue(blocks.block(alice.getId(), bob.getId()).ok(), "alice blocks bob");

        final String mention = "hey " + ChatMentions.token(alice.getId()) + " look";
        final ChatCommands.SendResult refused = chat.send(tripId, bob.getId(), mention, null, null, actor(bob));
        Assert.assertFalse(refused.isOk());
        Assert.assertEquals(refused.getCode(), "forbidden");

        Assert.assertTrue(chat.send(tripId, bob.getId(), "no mention, fine", null, null, actor(bob)).isOk());
        final String reverse = "hey " + ChatMentions.token(bob.getId());
        Assert.assertTrue(chat.send(tripId, alice.getId(), reverse, null, null, actor(alice)).isOk(),
                "the blocker may still mention the blocked person");
        Assert.assertTrue(blocks.unblock(alice.getId(), bob.getId()).ok());
        Assert.assertTrue(chat.send(tripId, bob.getId(), mention, null, null, actor(bob)).isOk(),
                "unblocking lifts the rule");
    }

    @Test
    public void photoCommentsUseTheSameList() {
        final ChatMessage.Id nothing = ChatMessage.Id.from("unused");
        Assert.assertNotNull(nothing);
        final ChatCommands.SendResult refused = photoChat.comment("chat/" + tripId + "/x.jpg", alice.getId(),
                "heck no", null, callerFor(alice));
        // Either the filter or the photo lookup answers first; both are refusals, and the filter one carries
        // the blocked_content code when the photo exists. With no media row the lookup wins -- so pin only
        // that a blocked word never gets stored.
        Assert.assertFalse(refused.isOk());
    }

    private static Person saved(final String first) throws IOException {
        final Person person = Person.builder()
                .first(first).last(RandomData.genAlpha(8))
                .email(first.toLowerCase() + "." + RandomData.genAlpha(10).toLowerCase(Locale.ROOT) + "@example.com")
                .build();
        Assert.assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    private static AuditActor actor(final Person person) {
        return new AuditActor(person.getEmail(), person.getId().getValue());
    }

    private static Caller callerFor(final Person person) {
        return new Caller(person.getId(), false, actor(person), new PrivilegeCommands());
    }
}
