package org.paulsens.trip.push;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.pay.MoneyMath;
import org.paulsens.trip.util.TripThreads;

/**
 * The non-chat push events, one method per event, each a one-liner at its call site
 * ({@code RegistrationCommands.approvePending}, {@code PaymentCommands.recordNow},
 * {@code SupportChatCommands.notifyAdmins}). Every call returns immediately: the fan-out runs on a fresh
 * virtual thread under the system actor, and nothing here can fail the request that triggered it.
 */
@Slf4j
public class PushNotifications {

    private static volatile PushNotifications instance;

    private final PushSender sender;
    private final ConfigCommands config;
    private final Consumer<Runnable> dispatcher;

    public static PushNotifications getInstance() {
        PushNotifications local = instance;
        if (local == null) {
            synchronized (PushNotifications.class) {
                local = instance;
                if (local == null) {
                    local = new PushNotifications();
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Test seam (null restores the default). */
    public static void setInstance(final PushNotifications replacement) {
        instance = replacement;
    }

    public PushNotifications() {
        this(PushSender.getInstance(), new ConfigCommands(),
                task -> TripThreads.startAs(AuditActor.system(), task));
    }

    /** Test seam: {@code Runnable::run} as the dispatcher makes the fan-out synchronous. */
    public PushNotifications(final PushSender sender, final ConfigCommands config,
            final Consumer<Runnable> dispatcher) {
        this.sender = sender;
        this.config = config;
        this.dispatcher = dispatcher;
    }

    /**
     * "Registration approved" to the person and their family managers ({@code approvalRecipientIds}): the
     * person reads "You're confirmed", a manager reads who was confirmed.
     */
    public void registrationApproved(final Person person, final Trip trip, final List<Person.Id> recipients) {
        if (person == null || trip == null || recipients == null || recipients.isEmpty()) {
            return;
        }
        dispatch(() -> fanOutApproval(person, trip, recipients));
    }

    private void fanOutApproval(final Person person, final Trip trip, final List<Person.Id> recipients) {
        final String title = trip.getTitle() == null ? "Your trip" : trip.getTitle();
        final String link = PushLinks.paymentsLink(trip.getId());
        final String url = PushLinks.tripUrl(trip, KnownSettings.REG_MAIL_BASE_URL, config);
        final String icon = PushLinks.iconFor(trip, config);
        final String name = person.getPreferredName() == null ? "A traveler" : person.getPreferredName();
        for (final Person.Id recipient : new ArrayList<>(recipients)) {
            if (recipient == null) {
                continue;
            }
            final String body = recipient.equals(person.getId())
                    ? "You're confirmed for " + title
                    : name + " is confirmed for " + title;
            final PushPayload payload = PushPayload.alert(PushPayload.KIND_REGISTRATION_APPROVED, title,
                    "Registration approved", body, "trip:" + trip.getId(), link, url).withIcon(icon);
            sender.sendAlert(recipient, payload, dedupe("reg:" + trip.getId() + ":" + person.getId().getValue(),
                    recipient));
        }
    }

    /** "Payment received" to the payer. Called only for real (non-sandbox) RECORDED payments. */
    public void paymentRecorded(final Payment payment, final Trip trip, final String orgName) {
        if (payment == null || payment.getPayerId() == null) {
            return;
        }
        dispatch(() -> fanOutPayment(payment, trip, orgName));
    }

    private void fanOutPayment(final Payment payment, final Trip trip, final String orgName) {
        final String org = orgName == null || orgName.isBlank() ? "the organization" : orgName;
        final String title = trip != null && trip.getTitle() != null ? trip.getTitle() : org;
        final String tripId = payment.getTripId() != null ? payment.getTripId()
                : trip == null ? null : trip.getId();
        final PushPayload payload = PushPayload.alert(PushPayload.KIND_PAYMENT_RECORDED, title,
                "Payment received", "Thank you! " + MoneyMath.formatCents(payment.getTotalChargedCents())
                        + " received by " + org, "trip:" + tripId, PushLinks.paymentsLink(tripId),
                PushLinks.paymentsUrl(trip, tripId, config)).withIcon(PushLinks.iconFor(trip, config));
        sender.sendAlert(payment.getPayerId(), payload, dedupe("pay:" + payment.getPaymentId(),
                payment.getPayerId()));
    }

    /**
     * A support request to the support channel's admins (minus the requester). Rides {@code push.enabled}
     * only -- the mail switch is the mail route's. No app deep link: support lives on the admin site.
     */
    public void supportRequest(final ChatMessage stored, final Person requester, final List<Person> admins,
            final String subject) {
        if (stored == null || stored.getId() == null || admins == null || admins.isEmpty()) {
            return;
        }
        dispatch(() -> fanOutSupport(stored, requester, admins, subject));
    }

    private void fanOutSupport(final ChatMessage stored, final Person requester, final List<Person> admins,
            final String subject) {
        final String who = requester == null || requester.getPreferredName() == null ? "Someone"
                : requester.getPreferredName();
        final PushPayload payload = PushPayload.alert(PushPayload.KIND_SUPPORT_REQUEST, "Support request", who,
                        subject == null ? "New support request" : subject, ChatChannel.Id.forSupport().getValue(),
                        null, PushLinks.supportUrl(config))
                .withChat(ChatChannel.Id.forSupport().getValue(), stored.getId().getValue())
                .withIcon(PushLinks.base(null, config) + PushLinks.SITE_LOGO);
        for (final Person admin : admins) {
            if (admin == null || admin.getId() == null
                    || (requester != null && admin.getId().equals(requester.getId()))) {
                continue;
            }
            sender.sendAlert(admin.getId(), payload, dedupe("sup:" + stored.getId().getValue(), admin.getId()));
        }
    }

    /** The test push to the caller's own devices: no dedupe, so it can be pressed twice. */
    public PushSender.Report sendTest(final Person.Id me) {
        final PushPayload payload = PushPayload.alert(PushPayload.KIND_TEST, "UniteTrip", "Test notification",
                "Push notifications are working.", "test", null, null)
                .withIcon(PushLinks.base(null, config) + PushLinks.SITE_LOGO);
        return sender.sendAlert(me, payload, null);
    }

    /** The same claim-key shape as chat: {@code "{eventKey}|{personId}|PUSH"} under the 24 h sent marker. */
    static String dedupe(final String eventKey, final Person.Id recipient) {
        return eventKey + "|" + recipient.getValue() + "|PUSH";
    }

    private void dispatch(final Runnable task) {
        try {
            dispatcher.accept(() -> guarded(task));
        } catch (final RuntimeException ex) {
            log.warn("Push fan-out could not be dispatched", ex);
        }
    }

    private static void guarded(final Runnable task) {
        try {
            task.run();
        } catch (final RuntimeException ex) {
            log.warn("Push fan-out failed", ex);
        }
    }
}
