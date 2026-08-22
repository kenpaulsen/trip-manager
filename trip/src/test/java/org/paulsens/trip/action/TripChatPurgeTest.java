package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The trip-delete chat cascade ({@link ChatCommands#purgeTripChat}): everything the channel owns must go,
 * INCLUDING the two row families {@code purgeChannel} alone leaves behind -- invite rows and guests'
 * {@code person:} reverse-index rows.
 */
public class TripChatPurgeTest {

    private ChatCommands chat;
    private AuditActor actor;
    private String tripId;
    private Person.Id rosterMember;

    @BeforeMethod
    public void setUp() throws Exception {
        DAO.getInstance();
        tripId = UUID.randomUUID().toString();
        rosterMember = Person.Id.from("purge-roster-" + RandomData.genAlpha(8));
        final Trip trip = Trip.builder()
                .id(tripId)
                .title("Chat purge dedicated trip")
                .openToPublic(false)
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().plusDays(7))
                .people(new ArrayList<>(List.of(rosterMember)))
                .build();
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        chat = new ChatCommands(new ChatRateLimiter(new InMemoryCacheClient()));
        actor = new AuditActor("purgetest@test", rosterMember.getValue());
    }

    @Test
    public void purgeRemovesChannelMessagesInvitesAndGuestReverseRows() throws Exception {
        final ChatChannel channel = chat.ensureChannel(tripId, actor);
        Assert.assertTrue(chat.send(tripId, rosterMember, "first message", null, null, actor).isOk());
        Assert.assertTrue(chat.send(tripId, rosterMember, "second message", null, null, actor).isOk());

        final String url = chat.createInvite(tripId, rosterMember, actor);
        final String token = url.substring(url.indexOf("token=") + "token=".length());
        final Person person = Person.builder()
                .first("Guest").last(RandomData.genAlpha(8))
                .email("purge." + RandomData.genAlpha(8) + "@example.com")
                .build();
        Assert.assertTrue(DAO.getInstance().savePerson(person));
        Assert.assertEquals(chat.redeemInvite(tripId, token, person.getId(), actor), "ok");

        // The rows the purge must reach, present beforehand.
        Assert.assertFalse(DAO.getInstance()
                .getRawChatMessagesBefore(channel.getId(), null, 100, Cached.NO).isEmpty());
        Assert.assertFalse(DAO.getInstance().listChatInvites(channel.getId(), Cached.NO).isEmpty());
        Assert.assertTrue(DAO.getInstance().getGuestChatChannelIds(person.getId(), Cached.NO)
                .contains(channel.getId()), "the guest's reverse-index row must exist before the purge");

        chat.purgeTripChat(tripId);

        Assert.assertTrue(DAO.getInstance().getChatChannel(channel.getId(), Cached.NO).isEmpty(),
                "channel row must be gone");
        Assert.assertTrue(DAO.getInstance()
                .getRawChatMessagesBefore(channel.getId(), null, 100, Cached.NO).isEmpty(),
                "messages must be gone");
        Assert.assertTrue(DAO.getInstance().listChatInvites(channel.getId(), Cached.NO).isEmpty(),
                "invite rows must be gone");
        Assert.assertTrue(DAO.getInstance().listChatMembers(channel.getId(), Cached.NO).isEmpty(),
                "membership rows must be gone");
        Assert.assertTrue(DAO.getInstance().getGuestChatChannelIds(person.getId(), Cached.NO).isEmpty(),
                "the guest reverse-index row must be gone too -- purgeChannel alone leaves it");
    }

    @Test
    public void purgeOfANeverUsedChatIsAQuietNoOp() {
        chat.purgeTripChat(tripId);
        chat.purgeTripChat(tripId);     // idempotent
        chat.purgeTripChat(null);       // and null-safe
        chat.purgeTripChat("  ");
        Assert.assertTrue(DAO.getInstance()
                .getChatChannel(ChatChannel.Id.forTrip(tripId), Cached.NO).isEmpty());
    }
}
