package org.paulsens.trip.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatSettings;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@code ChatNotifications.mentionsFor}: who ends up on a notification.
 *
 * <p>Complements {@code ChatNotificationsTest}, which covers the content policy ({@code includeContent} and
 * {@code snippet}). What is pinned here is recipient SELECTION, where the rule is that being named is
 * necessary but never sufficient: every recipient, whether named individually or swept in by {@code @all},
 * still has to pass their own membership state, their own notification preference, and having a usable address.
 *
 * <p>Dispatch is asynchronous (it runs on the persistence pool so a send is never slowed by notifying), so
 * these await the notifier rather than asserting straight after the call.
 */
public class ChatMentionDispatchTest {

    private ChatNotifier previous;
    private final List<ChatNotification> captured = new ArrayList<>();
    private CountDownLatch dispatched;

    private Person.Id author;
    private Person.Id mentioned;

    @BeforeMethod
    public void setUp() {
        previous = ChatNotifications.notifier();
        captured.clear();
        dispatched = new CountDownLatch(1);
        ChatNotifications.setNotifier(new CapturingNotifier());
        author = person("Author");
        mentioned = person("Mentioned");
    }

    @AfterMethod(alwaysRun = true)
    public void restoreNotifier() {
        ChatNotifications.setNotifier(previous);
    }

    private final class CapturingNotifier implements ChatNotifier {
        @Override
        public Channel channel() {
            return Channel.EMAIL;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void notify(final ChatNotification notification) {
            captured.add(notification);
            dispatched.countDown();
        }

        @Override
        public void close() {
        }
    }

    private static Person.Id person(final String first) {
        final PersonCommands people = new PersonCommands();
        final Person who = people.createPerson();
        who.setFirst(first);
        who.setLast("Notified");
        who.setEmail(first.toLowerCase() + "-" + System.nanoTime() + "@example.org");
        Assert.assertTrue(people.savePerson(who));
        return who.getId();
    }

    private static ChatChannel channel(final String tripId) {
        return new ChatChannel(ChatChannel.Id.forTrip(tripId), tripId, ChatChannel.Kind.TRIP, "Test",
                null, null, ChatSettings.defaults(), Instant.now(), "admin", null, null);
    }

    private ChatMessage message(final String body, final String tripId) {
        return new ChatMessage(ChatMessage.Id.from("1"), ChatChannel.Id.forTrip(tripId), author,
                Instant.now(), null, body, null, null, null, null, null, null, null, null, null);
    }

    private Trip trip(final String tripId, final Person.Id... people) throws java.io.IOException {
        final Trip built = Trip.builder().id(tripId).title("Rome 2027").people(List.of(people)).build();
        Assert.assertTrue(DAO.getInstance().saveTrip(built));
        return built;
    }

    private static String mention(final Person.Id who) {
        return "@{" + who.getValue() + "}";
    }

    private ChatNotification awaitDispatch() throws InterruptedException {
        Assert.assertTrue(dispatched.await(10, TimeUnit.SECONDS), "the notification never dispatched");
        return captured.get(0);
    }

    private void assertNothingDispatched() throws InterruptedException {
        Assert.assertFalse(dispatched.await(1, TimeUnit.SECONDS), "nothing should have been dispatched");
        Assert.assertTrue(captured.isEmpty());
    }

    @Test
    public void aMessageWithNoMentionsNotifiesNobody() throws Exception {
        final String tripId = "mention-" + System.nanoTime();
        ChatNotifications.mentionsFor(message("just chatting", tripId), channel(tripId),
                trip(tripId, author, mentioned), "Author Notified");

        assertNothingDispatched();
    }

    @Test
    public void nullsAreGuarded() throws Exception {
        ChatNotifications.mentionsFor(null, channel("t"), null, "Author");
        ChatNotifications.mentionsFor(message("hi", "t"), null, null, "Author");

        assertNothingDispatched();
    }

    @Test
    public void aMentionedPersonIsNotified() throws Exception {
        final String tripId = "mention-" + System.nanoTime();
        ChatNotifications.mentionsFor(
                message("hey " + mention(mentioned) + " look", tripId),
                channel(tripId), trip(tripId, author, mentioned), "Author Notified");

        final ChatNotification notification = awaitDispatch();

        Assert.assertEquals(notification.getRecipients(), List.of(mentioned));
        Assert.assertEquals(notification.getReason(), ChatNotification.Reason.MENTION);
        Assert.assertEquals(notification.getTripTitle(), "Rome 2027");
        Assert.assertEquals(notification.getAuthorName(), "Author Notified");
    }

    /** Mentioning yourself must not email you. */
    @Test
    public void theAuthorIsNeverARecipient() throws Exception {
        final String tripId = "mention-" + System.nanoTime();
        ChatNotifications.mentionsFor(
                message("note to self " + mention(author), tripId),
                channel(tripId), trip(tripId, author, mentioned), "Author Notified");

        assertNothingDispatched();
    }

    /** {@code @all} widens the audience to the roster; it is not an override of anyone's preference. */
    @Test
    public void mentioningEveryoneReachesTheRosterWithoutTheAuthor() throws Exception {
        final String tripId = "mention-" + System.nanoTime();
        final Person.Id other = person("Other");
        ChatNotifications.mentionsFor(message("@all please read", tripId), channel(tripId),
                trip(tripId, author, mentioned, other), "Author Notified");

        final ChatNotification notification = awaitDispatch();

        Assert.assertEquals(Set.copyOf(notification.getRecipients()), Set.of(mentioned, other));
        Assert.assertFalse(notification.getRecipients().contains(author));
    }

    /** Somebody with no usable address is dropped from the list rather than mailed into the void. */
    @Test
    public void aRecipientWithNoUsableAddressIsNotIncluded() throws Exception {
        final String tripId = "mention-" + System.nanoTime();
        final PersonCommands people = new PersonCommands();
        final Person unreachable = people.createPerson();
        unreachable.setFirst("Unreachable");
        unreachable.setLast("Person");
        Assert.assertTrue(people.savePerson(unreachable));

        ChatNotifications.mentionsFor(
                message(mention(unreachable.getId()), tripId),
                channel(tripId), trip(tripId, author, unreachable.getId()), "Author Notified");

        assertNothingDispatched();
    }

    @Test
    public void anUnknownMentionedIdIsSimplyDropped() throws Exception {
        final String tripId = "mention-" + System.nanoTime();
        ChatNotifications.mentionsFor(
                message("@{no-such-person-at-all}", tripId),
                channel(tripId), trip(tripId, author), "Author Notified");

        assertNothingDispatched();
    }

    @Test
    public void everyMentionInOneMessageIsCollected() throws Exception {
        final String tripId = "mention-" + System.nanoTime();
        final Person.Id second = person("Second");
        ChatNotifications.mentionsFor(
                message(mention(mentioned) + " and " + mention(second), tripId),
                channel(tripId), trip(tripId, author, mentioned, second), "Author Notified");

        Assert.assertEquals(Set.copyOf(awaitDispatch().getRecipients()), Set.of(mentioned, second));
    }

    /**
     * Dedupe is per RECIPIENT, so it cannot live on the notification.
     *
     * <p>One notification carries many recipients, and the idempotency key is
     * {@code (message, person, channel)} -- so each route derives its own at delivery time. The notification's
     * own key stays unset, which is why {@code EmailChatNotifier} calls {@code dedupeKeyFor} itself rather than
     * reading one off the notification.
     */
    @Test
    public void dedupeIsDerivedPerRecipientAtDeliveryNotCarriedOnTheNotification() throws Exception {
        final String tripId = "mention-" + System.nanoTime();
        final Person.Id second = person("Second");
        ChatNotifications.mentionsFor(
                message(mention(mentioned) + " " + mention(second), tripId),
                channel(tripId), trip(tripId, author, mentioned, second), "Author Notified");

        final ChatNotification notification = awaitDispatch();

        Assert.assertNull(notification.getDedupeKey(), "one notification, many recipients: no single key");
        Assert.assertNotEquals(
                ChatNotification.dedupeKeyFor(notification.getMessageId(), mentioned, "EMAIL"),
                ChatNotification.dedupeKeyFor(notification.getMessageId(), second, "EMAIL"),
                "each recipient must claim separately, or one claim suppresses everyone else's mail");
    }

    /** A dispatch failure must not escape onto the pool: the message is already acknowledged to its sender. */
    @Test
    public void aFailingNotifierDoesNotEscape() throws Exception {
        final CountDownLatch tried = new CountDownLatch(1);
        ChatNotifications.setNotifier(new ChatNotifier() {
            @Override
            public Channel channel() {
                return Channel.EMAIL;
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public void notify(final ChatNotification notification) {
                tried.countDown();
                throw new IllegalStateException("notifier is down");
            }

            @Override
            public void close() {
            }
        });
        final String tripId = "mention-" + System.nanoTime();

        ChatNotifications.mentionsFor(message(mention(mentioned), tripId), channel(tripId),
                trip(tripId, author, mentioned), "Author Notified");

        Assert.assertTrue(tried.await(10, TimeUnit.SECONDS), "the notifier should have been called");
    }

    @Test
    public void theDefaultNotifierIsBuiltOnDemand() {
        ChatNotifications.setNotifier(null);

        Assert.assertNotNull(ChatNotifications.notifier());
    }
}
