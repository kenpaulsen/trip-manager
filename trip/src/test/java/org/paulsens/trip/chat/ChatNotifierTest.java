package org.paulsens.trip.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.MailCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The chat notifier routes: {@link ChatNotification}, {@link CompositeChatNotifier} and
 * {@link EmailChatNotifier}.
 *
 * <p>Two properties carry the risk. Notification is best-effort by construction -- the message it is about has
 * already been acknowledged to its sender, so a failing route must never propagate and must never stop the
 * other routes. And email is claimed through the cache BEFORE it is sent, so a retry or a restart mid-fan-out
 * cannot mail the same person twice.
 */
public class ChatNotifierTest {

    private static final Person.Id RECIPIENT = Person.Id.from("notify-recipient");

    private MailCommands mail;
    private CacheClient cache;
    private ConfigCommands config;
    private EmailChatNotifier notifier;

    @BeforeMethod
    public void setUp() {
        mail = Mockito.mock(MailCommands.class);
        cache = Mockito.mock(CacheClient.class);
        config = Mockito.mock(ConfigCommands.class);
        notifier = new EmailChatNotifier(mail, cache, config);
        // Claimed by default; the dedupe test overrides it.
        Mockito.when(cache.tryAcquireLock(ArgumentMatchers.anyString(), ArgumentMatchers.any(Duration.class)))
                .thenReturn(true);
    }

    private static ChatNotification notification(final ChatNotification.Reason reason, final String snippet) {
        return new ChatNotification(
                ChatChannel.Id.forTrip("trip-1"), ChatMessage.Id.from("1"), "trip-1", "Rome 2027",
                Person.Id.from("author"), "Author Name", List.of(RECIPIENT), snippet, reason,
                ChatNotification.dedupeKeyFor(ChatMessage.Id.from("1"), RECIPIENT, "EMAIL"), Instant.now());
    }

    // --- ChatNotification ---

    @Test
    public void theDedupeKeyIsStableAndToleratesNulls() {
        Assert.assertEquals(
                ChatNotification.dedupeKeyFor(ChatMessage.Id.from("m1"), RECIPIENT, "EMAIL"),
                "m1|" + RECIPIENT.getValue() + "|EMAIL");
        Assert.assertEquals(ChatNotification.dedupeKeyFor(null, null, "EMAIL"), "-|-|EMAIL");
    }

    /** A content-free mention from a short-retention channel carries no snippet; the mail must cope. */
    @Test
    public void hasSnippetDistinguishesEmptyFromAbsent() {
        Assert.assertTrue(notification(ChatNotification.Reason.MENTION, "hello").hasSnippet());
        Assert.assertFalse(notification(ChatNotification.Reason.MENTION, null).hasSnippet());
        Assert.assertFalse(notification(ChatNotification.Reason.MENTION, "   ").hasSnippet());
    }

    @Test
    public void recipientsAreCopiedAndNeverNull() {
        final ChatNotification withNulls = new ChatNotification(null, null, null, null, null, null,
                null, null, ChatNotification.Reason.MENTION, "k", null);

        Assert.assertEquals(withNulls.getRecipients(), List.of());
        Assert.assertNotNull(withNulls.getQueuedAt(), "queuedAt defaults to now rather than staying null");
    }

    // --- CompositeChatNotifier ---

    @Test
    public void aCompositeIsEnabledWhenAnyRouteIsAndFansOutToAllOfThem() {
        final ChatNotifier on = route(true);
        final ChatNotifier off = route(false);
        final CompositeChatNotifier composite = new CompositeChatNotifier(List.of(off, on));

        Assert.assertTrue(composite.isEnabled());
        composite.notify(notification(ChatNotification.Reason.MENTION, "hi"));

        Mockito.verify(on).notify(ArgumentMatchers.any());
        Mockito.verify(off, Mockito.never()).notify(ArgumentMatchers.any());
    }

    /** A failing route must not stop the others: the message is already acknowledged to its sender. */
    @Test
    public void aFailingRouteDoesNotStopTheRest() {
        final ChatNotifier broken = route(true);
        final ChatNotifier working = route(true);
        Mockito.doThrow(new IllegalStateException("route is down"))
                .when(broken).notify(ArgumentMatchers.any());

        new CompositeChatNotifier(List.of(broken, working))
                .notify(notification(ChatNotification.Reason.MENTION, "hi"));

        Mockito.verify(working).notify(ArgumentMatchers.any());
    }

    @Test
    public void aFailingCloseDoesNotStopTheRestEither() {
        final ChatNotifier broken = route(true);
        final ChatNotifier working = route(true);
        Mockito.doThrow(new IllegalStateException("cannot close")).when(broken).close();

        new CompositeChatNotifier(List.of(broken, working)).close();

        Mockito.verify(working).close();
    }

    @Test
    public void anEmptyCompositeIsDisabledAndHarmless() {
        final CompositeChatNotifier empty = new CompositeChatNotifier(null);

        Assert.assertFalse(empty.isEnabled());
        empty.notify(notification(ChatNotification.Reason.MENTION, "hi"));
        empty.close();
        Assert.assertEquals(empty.channel(), ChatNotifier.Channel.IN_APP);
    }

    private static ChatNotifier route(final boolean enabled) {
        final ChatNotifier route = Mockito.mock(ChatNotifier.class);
        Mockito.when(route.isEnabled()).thenReturn(enabled);
        Mockito.when(route.channel()).thenReturn(ChatNotifier.Channel.EMAIL);
        return route;
    }

    // --- EmailChatNotifier ---

    @Test
    public void emailShipsDarkUntilTheSettingTurnsItOn() {
        Mockito.when(config.getBoolean(KnownSettings.CHAT_MAIL_ENABLED)).thenReturn(false);
        Assert.assertFalse(notifier.isEnabled(),
                "It ships off so the code can deploy before any pilgrim receives mail from it");

        Mockito.when(config.getBoolean(KnownSettings.CHAT_MAIL_ENABLED)).thenReturn(true);
        Assert.assertTrue(notifier.isEnabled());
        Assert.assertEquals(notifier.channel(), ChatNotifier.Channel.EMAIL);
    }

    /** ALL_MESSAGES is a digest reason: accepted by the model, never mailed immediately. */
    @Test
    public void allMessagesIsLeftToTheDigestRatherThanMailedNow() {
        notifier.notify(notification(ChatNotification.Reason.ALL_MESSAGES, "hi"));
        notifier.notify(null);

        Mockito.verifyNoInteractions(mail);
        Mockito.verifyNoInteractions(cache);
    }

    /** The claim happens BEFORE the send, so a retry or a restart cannot mail the same person twice. */
    @Test
    public void anUnclaimedDedupeKeyMeansSomebodyElseAlreadySent() {
        Mockito.when(cache.tryAcquireLock(ArgumentMatchers.anyString(), ArgumentMatchers.any(Duration.class)))
                .thenReturn(false);

        notifier.notify(notification(ChatNotification.Reason.MENTION, "hi"));

        Mockito.verify(mail, Mockito.never()).send(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(AuditActor.class));
    }

    @Test
    public void aRecipientWithNoUsableAddressIsSkippedNotFailed() {
        Mockito.when(mail.formatEmail(ArgumentMatchers.any())).thenReturn(null);

        notifier.notify(notification(ChatNotification.Reason.MENTION, "hi"));

        Mockito.verify(mail, Mockito.never()).send(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(AuditActor.class));
    }

    /** Notification must never propagate: the message it is about is already acknowledged to its sender. */
    @Test
    public void aFailureInsideDeliveryIsSwallowed() {
        Mockito.when(mail.formatEmail(ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("mail layer is down"));

        notifier.notify(notification(ChatNotification.Reason.MENTION, "hi"));
    }

    @Test
    public void theDefaultConstructorWiresItselfUp() {
        Assert.assertNotNull(new EmailChatNotifier().channel());
    }

    // --- the rendered mail itself ---

    private void mailIsConfigured() {
        // The recipient must exist in the store: DAO.getPerson (unlike PersonCommands.getPerson) answers a
        // real Optional.empty for an unknown id, and deliver treats that as "no usable address".
        final Person recipient = new Person();
        recipient.setId(RECIPIENT);
        recipient.setEmail("r@example.org");
        try {
            Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().savePerson(recipient));
        } catch (final java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
        Mockito.when(config.getString(KnownSettings.CHAT_MAIL_BASE_URL))
                .thenReturn("https://example.org");
        Mockito.when(config.getString(KnownSettings.CHAT_MAIL_FROM)).thenReturn("chat@example.org");
        Mockito.when(config.getString(KnownSettings.CHAT_MAIL_REPLY_TO)).thenReturn("noreply@example.org");
        Mockito.when(mail.formatEmail(ArgumentMatchers.any())).thenReturn("R <r@example.org>");
    }

    private org.mockito.ArgumentCaptor<String> sendAndCaptureBody(final ChatNotification notification,
            final org.mockito.ArgumentCaptor<String> subject) {
        notifier.notify(notification);
        final org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        Mockito.verify(mail).send(ArgumentMatchers.eq("chat@example.org"),
                ArgumentMatchers.eq("R <r@example.org>"), ArgumentMatchers.isNull(),
                ArgumentMatchers.eq("noreply@example.org"), subject.capture(), body.capture(),
                ArgumentMatchers.argThat((AuditActor actor) -> actor != null));
        return body;
    }

    @Test
    public void aMentionIsRenderedWithTheQuoteEscapedAndADeepLink() {
        mailIsConfigured();
        final org.mockito.ArgumentCaptor<String> subject = org.mockito.ArgumentCaptor.forClass(String.class);

        final String body = sendAndCaptureBody(
                notification(ChatNotification.Reason.MENTION, "bus <b>leaves</b> at 7"), subject).getValue();

        Assert.assertEquals(subject.getValue(), "Author Name mentioned you in the Rome 2027 chat");
        Assert.assertTrue(body.contains("bus &lt;b&gt;leaves&lt;/b&gt; at 7"),
                "the snippet is user content and must arrive escaped, not as markup");
        Assert.assertTrue(body.contains("https://example.org/trip/chat.jsf?trip=trip-1"),
                "the mail must deep-link back to the chat");
    }

    /** A reply notification says "replied", never "mentioned": the recipient typed no @name and knows it. */
    @Test
    public void aReplyIsRenderedWithReplyWording() {
        mailIsConfigured();
        final org.mockito.ArgumentCaptor<String> subject = org.mockito.ArgumentCaptor.forClass(String.class);

        final String body = sendAndCaptureBody(
                notification(ChatNotification.Reason.REPLY, "good point"), subject).getValue();

        Assert.assertEquals(subject.getValue(), "Author Name replied to you in the Rome 2027 chat");
        Assert.assertTrue(body.contains("replied to your message"), "the chat-reply template renders");
        Assert.assertTrue(body.contains("https://example.org/trip/chat.jsf?trip=trip-1"));
    }

    /** A comment on your photo says so — and deep-links to the photo, not the chat. */
    @Test
    public void aPhotoCommentIsRenderedWithCommentWordingAndAPhotoLink() {
        mailIsConfigured();
        final ChatNotification note = new ChatNotification(
                ChatChannel.Id.forPhoto("chat/trip-1/x.jpg"), ChatMessage.Id.from("1"), "trip-1",
                "Rome 2027", Person.Id.from("author"), "Author Name", List.of(RECIPIENT), "lovely",
                ChatNotification.Reason.PHOTO_COMMENT, null, Instant.now());
        final org.mockito.ArgumentCaptor<String> subject = org.mockito.ArgumentCaptor.forClass(String.class);

        final String body = sendAndCaptureBody(note, subject).getValue();

        Assert.assertEquals(subject.getValue(), "Author Name commented on your photo from the Rome 2027 trip");
        Assert.assertTrue(body.contains("commented on"), "the photo-comment template renders");
        // The URL rides through the template's HTML escaping, so the ampersand arrives as an entity.
        Assert.assertTrue(body.contains("/trip/tripMedia.jsf?trip=trip-1&amp;photo="),
                "the mail must deep-link to the photo, not the chat");
    }

    @Test
    public void anAdminAnnouncementGetsItsOwnSubject() {
        mailIsConfigured();
        final org.mockito.ArgumentCaptor<String> subject = org.mockito.ArgumentCaptor.forClass(String.class);

        sendAndCaptureBody(notification(ChatNotification.Reason.ADMIN_ANNOUNCEMENT, "hi"), subject);

        Assert.assertEquals(subject.getValue(), "Announcement in the Rome 2027 chat");
    }

    /**
     * A short-retention channel sends a content-free notification: an inbox keeps mail for years, so the body
     * of a message the channel is configured to forget must not outlive the retention policy in the mail.
     */
    @Test
    public void aContentFreeNotificationCarriesNoQuoteBlock() {
        mailIsConfigured();
        final org.mockito.ArgumentCaptor<String> subject = org.mockito.ArgumentCaptor.forClass(String.class);

        final String body = sendAndCaptureBody(
                notification(ChatNotification.Reason.MENTION, null), subject).getValue();

        Assert.assertFalse(body.contains("<blockquote"), "no snippet may leak into the mail");
    }

    /** Nulls degrade to generic wording, never to the string "null" in somebody's inbox. */
    @Test
    public void missingAuthorAndTripFallBackToGenericWording() {
        mailIsConfigured();
        final ChatNotification bare = new ChatNotification(
                ChatChannel.Id.forTrip("trip-1"), ChatMessage.Id.from("1"), null, null,
                Person.Id.from("author"), null, List.of(RECIPIENT), null,
                ChatNotification.Reason.MENTION, null, Instant.now());
        final org.mockito.ArgumentCaptor<String> subject = org.mockito.ArgumentCaptor.forClass(String.class);

        final String body = sendAndCaptureBody(bare, subject).getValue();

        Assert.assertEquals(subject.getValue(), "Someone mentioned you in the your trip chat");
        Assert.assertFalse(body.contains("null"), "a null field must never render as the word 'null'");
    }
}
