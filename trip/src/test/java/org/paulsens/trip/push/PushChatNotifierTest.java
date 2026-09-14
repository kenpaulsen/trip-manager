package org.paulsens.trip.push;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.chat.ChatNotification;
import org.paulsens.trip.chat.ChatNotifier;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatNotifyPref;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The push route: per-channel preference, payload copy, photo-thread preference home, silent refresh walk. */
public class PushChatNotifierTest {

    private PushSender sender;
    private ConfigCommands config;
    private PushChatNotifier notifier;
    private String tripId;
    private Person.Id author;
    private Person.Id fan;
    private Person.Id quiet;

    @BeforeMethod
    public void setUp() throws Exception {
        sender = Mockito.mock(PushSender.class);
        Mockito.when(sender.sendAlert(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new PushSender.Report(1, List.of(), null));
        config = Mockito.mock(ConfigCommands.class);
        Mockito.when(config.getBoolean(KnownSettings.PUSH_ENABLED)).thenReturn(true);
        Mockito.when(config.getString(KnownSettings.CHAT_MAIL_BASE_URL)).thenReturn("https://site.example");
        notifier = new PushChatNotifier(sender, config);
        tripId = "push-chat-" + System.nanoTime();
        author = person("Author");
        fan = person("Fan");
        quiet = person("Quiet");
        Assert.assertTrue(DAO.getInstance().saveTrip(Trip.builder().id(tripId).title("Rome 2027")
                .people(new ArrayList<>(List.of(author, fan, quiet))).build()));
        final ChatChannel.Id home = ChatChannel.Id.forTrip(tripId);
        final ChatMembership fanRow = ChatMembership.joining(home, fan, Instant.now());
        Assert.assertTrue(DAO.getInstance().saveChatMembership(
                fanRow.withNotify(fanRow.getNotify().withPushMode(ChatNotifyPref.PushMode.ALL))));
        final ChatMembership quietRow = ChatMembership.joining(home, quiet, Instant.now());
        Assert.assertTrue(DAO.getInstance().saveChatMembership(
                quietRow.withNotify(quietRow.getNotify().withPushMode(ChatNotifyPref.PushMode.OFF))));
    }

    private static Person.Id person(final String first) throws Exception {
        final Person who = Person.builder().first(first).last("Pushed").email(first.toLowerCase() + "-"
                + System.nanoTime() + "@example.org").build();
        Assert.assertTrue(DAO.getInstance().savePerson(who));
        return who.getId();
    }

    private ChatNotification notification(final ChatNotification.Reason reason, final List<Person.Id> to,
            final String snippet, final String image) {
        return new ChatNotification(ChatChannel.Id.forTrip(tripId), ChatMessage.Id.from("77"), tripId, "Rome 2027",
                author, "Ada Author", to, snippet, reason, null, Instant.now(), image);
    }

    @Test
    public void itIsThePushChannelBehindTheMasterSwitch() {
        Assert.assertEquals(notifier.channel(), ChatNotifier.Channel.PUSH);
        Assert.assertTrue(notifier.isEnabled());
        Mockito.when(config.getBoolean(KnownSettings.PUSH_ENABLED)).thenReturn(false);
        Assert.assertFalse(notifier.isEnabled());
        Assert.assertNotNull(new PushChatNotifier().channel(), "the default constructor wires itself up");
    }

    @Test
    public void aMentionReachesEveryoneNotOffWithTheContractPayload() {
        final Person.Id implicit = Person.Id.from("implicit-" + System.nanoTime());
        notifier.notify(notification(ChatNotification.Reason.MENTION, List.of(fan, quiet, implicit),
                "bus leaves at 7", null));

        final ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
        Mockito.verify(sender).sendAlert(ArgumentMatchers.eq(fan), payload.capture(),
                ArgumentMatchers.eq("77|" + fan.getValue() + "|PUSH"));
        Mockito.verify(sender).sendAlert(ArgumentMatchers.eq(implicit), ArgumentMatchers.any(),
                ArgumentMatchers.eq("77|" + implicit.getValue() + "|PUSH"));
        Mockito.verify(sender, Mockito.never()).sendAlert(ArgumentMatchers.eq(quiet), ArgumentMatchers.any(),
                ArgumentMatchers.any());
        Mockito.verify(sender, Mockito.never()).sendSilent(ArgumentMatchers.any());
        final PushPayload sent = payload.getValue();
        Assert.assertEquals(sent.getKind(), PushPayload.KIND_CHAT_MENTION);
        Assert.assertEquals(sent.getTitle(), "Rome 2027");
        Assert.assertEquals(sent.getSubtitle(), "Ada Author mentioned you");
        Assert.assertEquals(sent.getBody(), "bus leaves at 7");
        Assert.assertEquals(sent.getThreadId(), "trip:" + tripId);
        Assert.assertEquals(sent.getChannelId(), "trip:" + tripId);
        Assert.assertEquals(sent.getMessageId(), "77");
        Assert.assertEquals(sent.getLink(), "unitetrip://chat/" + tripId);
        Assert.assertEquals(sent.getUrl(), "https://site.example/trip/chat.jsf?trip=" + tripId);
        Assert.assertEquals(sent.getIconUrl(), "https://site.example/resources/images/UniteTripLogo.png");
        Assert.assertNull(sent.getImageUrl());
    }

    @Test
    public void everyMessageAlertsOnlyTheAllRowsThenWakesTheRestOfTheTrip() {
        final Person.Id implicit = Person.Id.from("implicit-" + System.nanoTime());
        notifier.notify(notification(ChatNotification.Reason.ALL_MESSAGES, List.of(fan, implicit), null,
                "https://f/x-small.jpg"));

        final ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
        Mockito.verify(sender).sendAlert(ArgumentMatchers.eq(fan), payload.capture(), ArgumentMatchers.any());
        Mockito.verify(sender, Mockito.never()).sendAlert(ArgumentMatchers.eq(implicit), ArgumentMatchers.any(),
                ArgumentMatchers.any());
        Assert.assertEquals(payload.getValue().getKind(), PushPayload.KIND_CHAT_MESSAGE);
        Assert.assertEquals(payload.getValue().getSubtitle(), "Ada Author");
        Assert.assertEquals(payload.getValue().getBody(), PushChatNotifier.PHOTO_BODY, "a bare photo message");
        Assert.assertEquals(payload.getValue().getImageUrl(), "https://f/x-small.jpg");
        // The silent walk: everyone on the trip except the author and the freshly alerted.
        Mockito.verify(sender).sendSilent(quiet);
        Mockito.verify(sender, Mockito.never()).sendSilent(fan);
        Mockito.verify(sender, Mockito.never()).sendSilent(author);
    }

    /**
     * A child with no phone: their family managers' phones are told instead, with copy naming the child and
     * a dedupe key naming the child, and a manager who was named directly is told once, as themselves.
     */
    @Test
    public void aRecipientWithNoPhoneReachesTheirManagersPhones() throws Exception {
        final Person child = Person.builder().first("Lucy").last("Pushed").build();
        Assert.assertTrue(DAO.getInstance().savePerson(child));
        final Person.Id mom = person("Mom");
        final Person.Id dad = person("Dad");
        final org.paulsens.trip.model.Family family = org.paulsens.trip.model.Family.builder()
                .id(org.paulsens.trip.model.Family.Id.from("fam-" + System.nanoTime()))
                .memberIds(List.of(mom, dad, child.getId())).managerIds(List.of(mom, dad)).createdBy(mom).build();
        Assert.assertTrue(DAO.getInstance().saveFamily(family));
        child.setFamilyId(family.getId());
        Assert.assertTrue(DAO.getInstance().savePerson(child));
        Mockito.when(sender.sendAlert(ArgumentMatchers.eq(child.getId()), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(PushSender.Report.skipped(PushSender.SKIPPED_NO_DEVICES));

        // Mom is named herself; dad is only reached on Lucy's behalf.
        notifier.notify(notification(ChatNotification.Reason.MENTION, List.of(child.getId(), mom), "bus at 7", null));

        final ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
        final ArgumentCaptor<String> dedupe = ArgumentCaptor.forClass(String.class);
        Mockito.verify(sender).sendAlert(ArgumentMatchers.eq(dad), payload.capture(), dedupe.capture());
        Assert.assertTrue(payload.getValue().toApns().contains("Ada Author mentioned Lucy"),
                payload.getValue().toApns());
        Assert.assertTrue(dedupe.getValue().endsWith("PUSH:for:" + child.getId().getValue()), dedupe.getValue());
        Mockito.verify(sender, Mockito.times(1)).sendAlert(ArgumentMatchers.eq(mom), ArgumentMatchers.any(),
                ArgumentMatchers.any());
        Mockito.verify(sender).sendAlert(ArgumentMatchers.eq(mom), ArgumentMatchers.any(),
                ArgumentMatchers.endsWith("|PUSH"));
        Assert.assertEquals(PushChatNotifier.subtitleOf(notification(ChatNotification.Reason.REPLY, List.of(), "x",
                null), child), "Ada Author replied to Lucy");
        Assert.assertEquals(PushChatNotifier.subtitleOf(notification(ChatNotification.Reason.PHOTO_COMMENT,
                List.of(), "x", null), child), "Ada Author commented on Lucy's photo");
    }

    /** Every message is a per-person opt-in; a child's silence there is not answered for by their managers. */
    @Test
    public void everyMessageIsNeverForwardedToManagers() throws Exception {
        final Person child = Person.builder().first("Lucy").last("Pushed").build();
        Assert.assertTrue(DAO.getInstance().savePerson(child));
        Mockito.when(sender.sendAlert(ArgumentMatchers.eq(child.getId()), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(PushSender.Report.skipped(PushSender.SKIPPED_NO_DEVICES));
        notifier.notify(notification(ChatNotification.Reason.ALL_MESSAGES, List.of(child.getId()), "hi", null));
        Mockito.verify(sender, Mockito.never()).sendAlert(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.contains(":for:"));
    }

    @Test
    public void aSkippedAlertStillCountsAsNotAlertedForTheSilentWalk() {
        Mockito.when(sender.sendAlert(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(PushSender.Report.skipped(PushSender.SKIPPED_NO_DEVICES));
        notifier.notify(notification(ChatNotification.Reason.ALL_MESSAGES, List.of(fan), "hi", null));
        Mockito.verify(sender).sendSilent(fan);
        Mockito.verify(sender).sendSilent(quiet);
    }

    @Test
    public void repliesAnnouncementsAndContentFreeChannelsHaveTheirOwnWording() {
        notifier.notify(notification(ChatNotification.Reason.REPLY, List.of(fan), null, null));
        final ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
        Mockito.verify(sender).sendAlert(ArgumentMatchers.eq(fan), payload.capture(), ArgumentMatchers.any());
        Assert.assertEquals(payload.getValue().getKind(), PushPayload.KIND_CHAT_REPLY);
        Assert.assertEquals(payload.getValue().getSubtitle(), "Ada Author replied to you");
        Assert.assertEquals(payload.getValue().getBody(), PushChatNotifier.OPEN_THE_CHAT);

        final ChatNotification announcement = new ChatNotification(ChatChannel.Id.forTrip(tripId),
                ChatMessage.Id.from("78"), tripId, null, author, null, List.of(fan), "Bus at 7",
                ChatNotification.Reason.ADMIN_ANNOUNCEMENT, null, Instant.now(), null);
        Assert.assertEquals(PushChatNotifier.subtitleOf(announcement), "Announcement");
        Assert.assertEquals(PushChatNotifier.kindOf(announcement), PushPayload.KIND_CHAT_ANNOUNCEMENT);
        Assert.assertEquals(notifier.payloadFor(announcement, null).getTitle(), "Trip chat");
        Assert.assertEquals(PushChatNotifier.subtitleOf(notification(ChatNotification.Reason.PHOTO_COMMENT,
                List.of(), null, null)), "Ada Author commented on your photo");
        Assert.assertEquals(PushChatNotifier.kindOf(notification(ChatNotification.Reason.PHOTO_COMMENT,
                List.of(), null, null)), PushPayload.KIND_CHAT_PHOTO_COMMENT);
        final ChatNotification anonymous = new ChatNotification(ChatChannel.Id.forTrip(tripId),
                null, tripId, "T", author, null, List.of(), null,
                ChatNotification.Reason.MENTION, null, Instant.now(), null);
        Assert.assertEquals(PushChatNotifier.subtitleOf(anonymous), "Someone mentioned you");
        Assert.assertNull(notifier.payloadFor(anonymous, null).getMessageId());
    }

    @Test
    public void photoThreadsReadTheTripChannelsPreferenceAndLinkToTheAlbum() {
        final String key = "chat/" + tripId + "/x.jpg";
        final ChatNotification comment = new ChatNotification(ChatChannel.Id.forPhoto(key), ChatMessage.Id.from("80"),
                tripId, "Rome 2027", author, "Ada Author", List.of(fan, quiet), "nice", ChatNotification.Reason.MENTION,
                null, Instant.now(), "https://f/" + key);
        notifier.notify(comment);
        final ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
        Mockito.verify(sender).sendAlert(ArgumentMatchers.eq(fan), payload.capture(), ArgumentMatchers.any());
        Mockito.verify(sender, Mockito.never()).sendAlert(ArgumentMatchers.eq(quiet), ArgumentMatchers.any(),
                ArgumentMatchers.any());
        Assert.assertEquals(payload.getValue().getLink(), "unitetrip://trip/" + tripId + "/photos");
        Assert.assertTrue(payload.getValue().getUrl().startsWith("https://site.example/trip/tripMedia.jsf?trip="
                + tripId + "&photo=chat%2F"), payload.getValue().getUrl());
        Assert.assertEquals(payload.getValue().getThreadId(), "photo:" + key);
        Mockito.verify(sender, Mockito.never()).sendSilent(ArgumentMatchers.any());

        // ALL_MESSAGES never walks a photo thread either.
        notifier.notify(new ChatNotification(ChatChannel.Id.forPhoto(key), ChatMessage.Id.from("81"), tripId,
                "Rome 2027", author, "Ada Author", List.of(), "nice", ChatNotification.Reason.ALL_MESSAGES, null,
                Instant.now(), null));
        Mockito.verify(sender, Mockito.never()).sendSilent(ArgumentMatchers.any());
    }

    @Test
    public void digestsNullsAndBrokenSendersAreAbsorbed() {
        notifier.notify(null);
        notifier.notify(notification(ChatNotification.Reason.DIGEST, List.of(fan), "x", null));
        notifier.notify(new ChatNotification(null, null, tripId, null, author, null, List.of(fan), null,
                ChatNotification.Reason.MENTION, null, null, null));
        Mockito.verifyNoInteractions(sender);

        Mockito.when(sender.sendAlert(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("sender down"));
        notifier.notify(notification(ChatNotification.Reason.MENTION, List.of(fan), "x", null));

        // A notification for a trip that no longer exists: alerts still go out, the silent walk has nobody.
        final ChatNotification orphan = new ChatNotification(ChatChannel.Id.forTrip("gone-" + System.nanoTime()),
                ChatMessage.Id.from("82"), "gone-" + System.nanoTime(), "Gone", author, "A", List.of(), null,
                ChatNotification.Reason.ALL_MESSAGES, null, Instant.now(), null);
        Mockito.reset(sender);
        notifier.notify(orphan);
        Mockito.verifyNoInteractions(sender);
    }
}
