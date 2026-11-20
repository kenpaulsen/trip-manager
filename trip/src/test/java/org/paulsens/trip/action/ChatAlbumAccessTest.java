package org.paulsens.trip.action;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link ChatAlbumAccess}, the "Match the chat" rule (owner, 2026-11-19) as the website's trip photos page asks
 * it: a chat guest off the roster finds the trip, and sees the album rows the chat shows them, against the real
 * store.
 */
public class ChatAlbumAccessTest {

    private static final Person.Id GUEST = Person.Id.from("album-guest-" + UUID.randomUUID());
    private static final Person.Id TRAVELER = Person.Id.from("album-traveler-" + UUID.randomUUID());

    private ChatCommands chat;
    private ChatAlbumAccess access;
    private Trip trip;

    @BeforeMethod
    public void freshTrip() throws IOException {
        chat = new ChatCommands(new ChatRateLimiter(new InMemoryCacheClient()));
        access = new ChatAlbumAccess(() -> chat);
        trip = Trip.builder().id(UUID.randomUUID().toString()).title("Album access")
                .people(new ArrayList<>(List.of(TRAVELER))).build();
        trip.setOpenToPublic(false);
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
    }

    private MediaItem row(final String name, final long minutesAgo, final boolean hidden) {
        final MediaItem item = new MediaItem(UUID.randomUUID().toString(), "chat/" + trip.getId() + "/" + name + ".jpg",
                name, "desc", "image/jpeg", 9L, ChatPhotos.slotFor(trip.getId()), 0,
                LocalDateTime.now().minusMinutes(minutesAgo), "who", null, hidden);
        Assert.assertTrue(DAO.getInstance().saveMedia(item));
        return item;
    }

    private List<String> titles(final List<MediaItem> rows) {
        return rows.stream().map(MediaItem::getTitle).sorted().toList();
    }

    @Test
    public void aGuestFindsTheTripAndSeesWhatTheChatShowsThem() {
        row("before", 60, true);
        row("after", -1, false);
        Assert.assertFalse(access.readsChat(trip.getId(), GUEST), "no channel: nobody's chat");
        Assert.assertEquals(access.albumFor(trip.getId(), GUEST), List.of());
        final Trip blank = Trip.builder().id(UUID.randomUUID().toString()).build();
        Assert.assertSame(access.tripForAlbumPage(blank, trip.getId(), GUEST), blank, "not a reader: unchanged");

        final ChatChannel channel = chat.ensureChannel(trip.getId(), AuditActor.system());
        Assert.assertTrue(DAO.getInstance().saveChatMembership(
                ChatMembership.guestJoining(channel.getId(), GUEST, Instant.now(), "sel")));

        Assert.assertTrue(access.readsChat(trip.getId(), GUEST));
        Assert.assertEquals(access.tripForAlbumPage(blank, trip.getId(), GUEST).getId(), trip.getId());
        Assert.assertSame(access.tripForAlbumPage(trip, trip.getId(), GUEST), trip, "already found: kept");
        Assert.assertEquals(titles(access.albumFor(trip.getId(), GUEST)), List.of("after", "before"),
                "the default chat: everything, hidden too");

        Assert.assertTrue(DAO.getInstance().saveChatChannel(channel.withSettings(
                channel.getSettings().toBuilder().fullHistoryForNewMembers(false).build())));
        Assert.assertEquals(titles(access.albumFor(trip.getId(), GUEST)), List.of("after"),
                "history hidden from new members: nothing from before they joined");

        Assert.assertTrue(DAO.getInstance().saveChatMembership(ChatMembership.guestJoining(channel.getId(), GUEST,
                Instant.now(), "sel").withRemoved(Instant.now(), "spam", "admin")));
        Assert.assertEquals(access.albumFor(trip.getId(), GUEST), List.of(), "removed: an empty album at once");
        Assert.assertNull(access.visibleThroughChat(trip.getId(), GUEST, List::of));
        Assert.assertEquals(access.albumFor(null, GUEST), List.of());
    }

    @Test
    public void theApplicationsBeanAsksTheSharedChat() {
        Assert.assertFalse(new ChatAlbumAccess().readsChat(null, GUEST));
    }
}
