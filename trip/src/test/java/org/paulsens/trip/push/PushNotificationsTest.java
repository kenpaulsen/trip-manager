package org.paulsens.trip.push;

import java.util.ArrayList;
import java.util.List;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The non-chat events: who is told, with what words and links, under which dedupe key. */
public class PushNotificationsTest {

    private PushSender sender;
    private ConfigCommands config;
    private PushNotifications push;
    private Person traveler;
    private Trip trip;

    @BeforeMethod
    public void setUp() {
        sender = Mockito.mock(PushSender.class);
        config = Mockito.mock(ConfigCommands.class);
        Mockito.when(config.getString(KnownSettings.CHAT_MAIL_BASE_URL)).thenReturn("https://site.example");
        Mockito.when(config.getString(KnownSettings.REG_MAIL_BASE_URL)).thenReturn("https://reg.example");
        push = new PushNotifications(sender, config, Runnable::run);
        traveler = Person.builder().first("Trin").last("Traveler").email("t@example.org").build();
        traveler.setId(Person.Id.from("traveler-1"));
        trip = Trip.builder().id("trip-" + System.nanoTime()).title("Rome 2027")
                .people(new ArrayList<>(List.of(traveler.getId()))).build();
    }

    @AfterMethod(alwaysRun = true)
    public void restoreInstance() {
        PushNotifications.setInstance(null);
    }

    @Test
    public void approvalTellsThePersonAndTheirManagersDifferently() {
        final Person.Id manager = Person.Id.from("manager-1");
        push.registrationApproved(traveler, trip, java.util.Arrays.asList(traveler.getId(), manager, null));

        final ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
        Mockito.verify(sender).sendAlert(ArgumentMatchers.eq(traveler.getId()), payload.capture(),
                ArgumentMatchers.eq("reg:" + trip.getId() + ":" + traveler.getId().getValue() + "|"
                        + traveler.getId().getValue() + "|PUSH"));
        Assert.assertEquals(payload.getValue().getKind(), PushPayload.KIND_REGISTRATION_APPROVED);
        Assert.assertEquals(payload.getValue().getTitle(), "Rome 2027");
        Assert.assertEquals(payload.getValue().getSubtitle(), "Registration approved");
        Assert.assertEquals(payload.getValue().getBody(), "You're confirmed for Rome 2027");
        Assert.assertEquals(payload.getValue().getLink(), "unitetrip://trip/" + trip.getId() + "/payments");
        Assert.assertEquals(payload.getValue().getUrl(), "https://reg.example/trip/tripDetails.jsf?trip="
                + trip.getId());
        Assert.assertEquals(payload.getValue().getThreadId(), "trip:" + trip.getId());
        Assert.assertEquals(payload.getValue().getIconUrl(),
                "https://site.example/resources/images/UniteTripLogo.png");

        final ArgumentCaptor<PushPayload> managers = ArgumentCaptor.forClass(PushPayload.class);
        Mockito.verify(sender).sendAlert(ArgumentMatchers.eq(manager), managers.capture(), ArgumentMatchers.any());
        Assert.assertEquals(managers.getValue().getBody(), "Trin is confirmed for Rome 2027");
        Mockito.verify(sender, Mockito.times(2)).sendAlert(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());

        push.registrationApproved(null, trip, List.of(manager));
        push.registrationApproved(traveler, null, List.of(manager));
        push.registrationApproved(traveler, trip, List.of());
        push.registrationApproved(traveler, trip, null);
        Mockito.verifyNoMoreInteractions(sender);
    }

    @Test
    public void aPaymentTellsThePayerWithTheAmountAndTheOrg() {
        final Payment payment = Payment.builder().paymentId("pay-1").tripId(trip.getId())
                .payerId(traveler.getId()).totalChargedCents(47_550L).build();
        push.paymentRecorded(payment, trip, "Acme Pilgrimages");

        final ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
        Mockito.verify(sender).sendAlert(ArgumentMatchers.eq(traveler.getId()), payload.capture(),
                ArgumentMatchers.eq("pay:pay-1|" + traveler.getId().getValue() + "|PUSH"));
        Assert.assertEquals(payload.getValue().getKind(), PushPayload.KIND_PAYMENT_RECORDED);
        Assert.assertEquals(payload.getValue().getTitle(), "Rome 2027");
        Assert.assertEquals(payload.getValue().getSubtitle(), "Payment received");
        Assert.assertEquals(payload.getValue().getBody(), "Thank you! $475.50 received by Acme Pilgrimages");
        Assert.assertEquals(payload.getValue().getLink(), "unitetrip://trip/" + trip.getId() + "/payments");
        Assert.assertEquals(payload.getValue().getUrl(), "https://site.example/trip/pay.jsf?trip=" + trip.getId());

        // A legacy row with no trip in hand: the org names the title and the trip id comes from the row.
        Mockito.reset(sender);
        push.paymentRecorded(payment, null, null);
        final ArgumentCaptor<PushPayload> orgless = ArgumentCaptor.forClass(PushPayload.class);
        Mockito.verify(sender).sendAlert(ArgumentMatchers.any(), orgless.capture(), ArgumentMatchers.any());
        Assert.assertEquals(orgless.getValue().getTitle(), "the organization");
        Assert.assertEquals(orgless.getValue().getLink(), "unitetrip://trip/" + trip.getId() + "/payments");

        Mockito.reset(sender);
        push.paymentRecorded(Payment.builder().paymentId("p2").build(), trip, "x");
        push.paymentRecorded(null, trip, "x");
        Mockito.verifyNoInteractions(sender);
    }

    @Test
    public void aSupportRequestReachesTheAdminsButNotTheRequester() {
        final Person admin = Person.builder().first("Ada").last("Admin").build();
        admin.setId(Person.Id.from("admin-1"));
        final Person requester = Person.builder().first("Rob").last("Requester").build();
        requester.setId(Person.Id.from("requester-1"));
        final ChatMessage stored = new ChatMessage(ChatMessage.Id.from("500"), ChatChannel.Id.forSupport(),
                requester.getId(), null, null, "help", null, null, null, null, null, null, null, null, null);
        final List<Person> admins = new ArrayList<>(List.of(admin, requester, Person.builder().build()));
        admins.add(null);
        push.supportRequest(stored, requester, admins, "Support request: family removal");

        final ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
        Mockito.verify(sender).sendAlert(ArgumentMatchers.eq(admin.getId()), payload.capture(),
                ArgumentMatchers.eq("sup:500|" + admin.getId().getValue() + "|PUSH"));
        Assert.assertEquals(payload.getValue().getKind(), PushPayload.KIND_SUPPORT_REQUEST);
        Assert.assertEquals(payload.getValue().getTitle(), "Support request");
        Assert.assertEquals(payload.getValue().getSubtitle(), "Rob");
        Assert.assertEquals(payload.getValue().getBody(), "Support request: family removal");
        Assert.assertNull(payload.getValue().getLink(), "support has no app route");
        Assert.assertEquals(payload.getValue().getUrl(), "https://site.example/admin/support.jsf");
        Assert.assertEquals(payload.getValue().getChannelId(), "support:main");
        Assert.assertEquals(payload.getValue().getMessageId(), "500");
        Mockito.verify(sender, Mockito.times(2)).sendAlert(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());

        Mockito.reset(sender);
        push.supportRequest(stored, null, List.of(admin), null);
        final ArgumentCaptor<PushPayload> anonymous = ArgumentCaptor.forClass(PushPayload.class);
        Mockito.verify(sender).sendAlert(ArgumentMatchers.any(), anonymous.capture(), ArgumentMatchers.any());
        Assert.assertEquals(anonymous.getValue().getSubtitle(), "Someone");
        Assert.assertEquals(anonymous.getValue().getBody(), "New support request");

        Mockito.reset(sender);
        push.supportRequest(null, requester, List.of(admin), "s");
        push.supportRequest(stored, requester, List.of(), "s");
        push.supportRequest(stored, requester, null, "s");
        Mockito.verifyNoInteractions(sender);
    }

    @Test
    public void theTestPushSkipsTheDedupeClaim() {
        Mockito.when(sender.sendAlert(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.isNull()))
                .thenReturn(new PushSender.Report(1, List.of(), null));
        Assert.assertEquals(push.sendTest(traveler.getId()).sent(), 1);
        final ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
        Mockito.verify(sender).sendAlert(ArgumentMatchers.eq(traveler.getId()), payload.capture(),
                ArgumentMatchers.isNull());
        Assert.assertEquals(payload.getValue().getKind(), PushPayload.KIND_TEST);
        Assert.assertEquals(payload.getValue().getTitle(), "UniteTrip");
        Assert.assertNull(payload.getValue().getLink());
    }

    @Test
    public void failuresInsideOrAroundTheFanOutNeverEscape() {
        Mockito.when(sender.sendAlert(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("sender down"));
        push.registrationApproved(traveler, trip, List.of(traveler.getId()));

        final PushNotifications undispatchable = new PushNotifications(sender, config,
                PushNotificationsTest::refuse);
        undispatchable.registrationApproved(traveler, trip, List.of(traveler.getId()));
    }

    private static void refuse(final Runnable task) {
        throw new IllegalStateException("no threads");
    }

    @Test
    public void theSharedInstanceIsReplaceableForTests() {
        Assert.assertSame(PushNotifications.getInstance(), PushNotifications.getInstance());
        PushNotifications.setInstance(push);
        Assert.assertSame(PushNotifications.getInstance(), push);
        PushNotifications.setInstance(null);
        Assert.assertNotSame(PushNotifications.getInstance(), push);
        Assert.assertEquals(PushNotifications.dedupe("k", Person.Id.from("p")), "k|p|PUSH");
    }
}
