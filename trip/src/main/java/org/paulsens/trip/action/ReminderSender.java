package org.paulsens.trip.action;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ReminderSend;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripReminder;
import org.paulsens.trip.model.TripReminderPlan;
import org.paulsens.trip.push.PushNotifications;

/**
 * Sends ONE reminder of ONE trip for ONE send date: the single path a trip message takes to a mailbox, whether
 * the daily scheduler or a facilitator's "Send now" started it. See {@code docs/reminders.md}.
 *
 * <p>Two rules live here and nowhere else:
 * <ol>
 *   <li><b>Approved or nothing.</b> {@link #sendAll} re-checks {@link TripReminder#isApprovedFor} against the
 *       trip it is handed and sends nothing otherwise; no caller can forget to.</li>
 *   <li><b>At most once.</b> Each traveler's send is claimed in the ledger ({@code DAO.claimReminderSend}, a
 *       write-once put) BEFORE the mail goes out, and the outcome is recorded after. A claim that already
 *       exists is skipped, so two servers, a restart mid-run, or a "Send now" racing the scheduler deliver one
 *       message. A send that fails stays recorded as failed: it is shown to the facilitator, never retried
 *       silently, because missing a reminder beats sending it twice.</li>
 * </ol>
 */
@Slf4j
public class ReminderSender {
    private static MailCommands liveMail;

    /** One mail out; false when it could not be handed to the mail service. */
    @FunctionalInterface
    public interface Mailer {
        boolean send(String from, String to, String replyTo, String subject, String body, AuditActor who);
    }

    /** One push out, for a reminder that has push text. Never throws. */
    @FunctionalInterface
    public interface Pusher {
        void push(Trip trip, TripReminder reminder, Person.Id recipient, String title, String text);
    }

    /** What one {@link #sendAll} did. {@code refused} is non-null when nothing was attempted, and says why. */
    public record Summary(int sent, int failed, int noAddress, int alreadySent, String refused) {
        static Summary refusedBecause(final String why) {
            return new Summary(0, 0, 0, 0, why);
        }

        public boolean isRefused() {
            return refused != null;
        }

        /** "Sent to 12. 1 failed. 2 have no email address." */
        public String text() {
            if (refused != null) {
                return refused;
            }
            final StringBuilder out = new StringBuilder("Sent to " + sent + ".");
            if (failed > 0) {
                out.append(' ').append(failed).append(" failed.");
            }
            if (noAddress > 0) {
                out.append(' ').append(noAddress).append((noAddress == 1) ? " has" : " have")
                        .append(" no email address.");
            }
            if (alreadySent > 0) {
                out.append(' ').append(alreadySent).append(" already sent earlier.");
            }
            return out.toString();
        }
    }

    private final ReminderRecipients recipients;
    private final Mailer mailer;
    private final Pusher pusher;
    private final BiFunction<Trip, TripReminderPlan, String> fromSource;
    private final BiFunction<Trip, TripReminderPlan, String> replyToSource;
    private final Supplier<Instant> clock;

    /** The live wiring. */
    public ReminderSender() {
        this(new ReminderRecipients(), ReminderSender::sesSend, ReminderSender::appPush,
                ReminderSender::effectiveFrom, ReminderSender::effectiveReplyTo, Instant::now);
    }

    /** Test seam. */
    ReminderSender(final ReminderRecipients recipients, final Mailer mailer, final Pusher pusher,
            final BiFunction<Trip, TripReminderPlan, String> fromSource,
            final BiFunction<Trip, TripReminderPlan, String> replyToSource, final Supplier<Instant> clock) {
        this.recipients = recipients;
        this.mailer = mailer;
        this.pusher = pusher;
        this.fromSource = fromSource;
        this.replyToSource = replyToSource;
        this.clock = clock;
    }

    /**
     * A sender for tests outside this package (the scheduler's): the real audience and ledger, mail handed to
     * {@code mailer}, no push, a fixed From.
     */
    public static ReminderSender forTest(final Mailer mailer, final Supplier<Instant> clock) {
        return new ReminderSender(new ReminderRecipients(), mailer, (trip, reminder, to, title, text) -> { },
                (trip, plan) -> "Trip Team <trips@example.com>", (trip, plan) -> null, clock);
    }

    private static boolean sesSend(final String from, final String to, final String replyTo, final String subject,
            final String body, final AuditActor who) {
        // send() maps every failure to null and sends nothing in local mode (it answers an empty response).
        return mail().send(from, to, null, replyTo, subject, body, who) != null;
    }

    /** One mail bean for every send (each {@code MailCommands} builds its own SES client), made on first use. */
    private static synchronized MailCommands mail() {
        if (liveMail == null) {
            liveMail = new MailCommands();
        }
        return liveMail;
    }

    private static void appPush(final Trip trip, final TripReminder reminder, final Person.Id recipient,
            final String title, final String text) {
        PushNotifications.getInstance().tripReminder(trip, reminder, recipient, title, text);
    }

    /**
     * The From a trip's reminders go out from: the plan's own while the trip's organization may still send from
     * it, else what the trip's payment mail inherits (the org's default, then the site's). Null means no mail.
     */
    static String effectiveFrom(final Trip trip, final TripReminderPlan plan) {
        final OrgCommands orgs = new OrgCommands(() -> Caller.forActor(AuditActor.system()));
        final String own = (plan == null) ? null : plan.getMailFrom();
        if (own != null && !own.isBlank() && orgs.isOrgFromSendable(trip.getOrgId(), own)) {
            return own;
        }
        return orgs.inheritedPaymentFrom(trip);
    }

    /** The Reply-To: the plan's own, else the trip's facilitators, else none (replies go to the From). */
    static String effectiveReplyTo(final Trip trip, final TripReminderPlan plan) {
        final String own = (plan == null) ? null : plan.getReplyTo();
        return (own != null && !own.isBlank()) ? own : new MailAddressCommands().facilitatorEmails(trip);
    }

    /**
     * Mails ONE test rendering to one address, through the same mail seam as a real send. It claims nothing and
     * records nothing: a test is not part of the sent history.
     */
    public boolean sendTest(final String from, final String to, final String replyTo, final String subject,
            final String body, final AuditActor who) {
        return mailer.send(from, to, replyTo, subject, body, who);
    }

    /**
     * Sends {@code reminder} to everyone it is about today, once each.
     *
     * @param sendDate the reminder's send date on this trip: the ledger key, so a late or repeated run of the
     *                 same date never mails anyone twice.
     * @param manual   true for a facilitator's "Send now" (recorded on each history row).
     */
    public Summary sendAll(final Trip trip, final TripReminderPlan plan, final TripReminder reminder,
            final LocalDate sendDate, final LocalDate today, final boolean manual, final AuditActor who) {
        if (trip == null || plan == null || reminder == null || sendDate == null) {
            return Summary.refusedBecause("Nothing to send.");
        }
        if (!reminder.isApprovedFor(trip) || !sendDate.equals(reminder.sendDate(trip))) {
            return Summary.refusedBecause("This message is not approved as it stands, so it was not sent.");
        }
        final String from = fromSource.apply(trip, plan);
        if (from == null || from.isBlank()) {
            return Summary.refusedBecause("No From address is set for this trip's messages, so it was not sent.");
        }
        final String replyTo = replyToSource.apply(trip, plan);
        // Cleaned ONCE here, on the way out, and merged per traveler below: the same body for every one of them.
        final TripReminder clean = reminder.withBody(ReminderHtml.clean(reminder.getBody()));
        final List<ReminderRecipients.Target> targets = recipients.targets(trip, reminder, today);
        final List<ReminderSend.Outcome> outcomes =
                fanOut(trip, clean, sendDate, manual, who, from, replyTo, targets);
        if (targets.isEmpty()) {
            recordNobody(trip, reminder, sendDate, manual);
        }
        return summarize(outcomes);
    }

    private List<ReminderSend.Outcome> fanOut(final Trip trip, final TripReminder reminder, final LocalDate sendDate,
            final boolean manual, final AuditActor who, final String from, final String replyTo,
            final List<ReminderRecipients.Target> targets) {
        // The mail merge's shape and its bound: one fork per traveler, a semaphore capping the SES calls in
        // flight, and the scope guaranteeing every send has finished (and is recorded) before the summary is built.
        final Semaphore permits = new Semaphore(MailCommands.MERGE_PARALLELISM);
        try (var scope = StructuredTaskScope.open()) {
            final List<StructuredTaskScope.Subtask<ReminderSend.Outcome>> sends = new ArrayList<>();
            for (final ReminderRecipients.Target target : targets) {
                sends.add(scope.fork(
                        () -> sendUnderPermit(permits, trip, reminder, sendDate, manual, who, from, replyTo, target)));
            }
            scope.join();
            final List<ReminderSend.Outcome> outcomes = new ArrayList<>();
            for (final StructuredTaskScope.Subtask<ReminderSend.Outcome> sent : sends) {
                outcomes.add(sent.get());
            }
            return outcomes;
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending trip reminders", ex);
        }
    }

    /** One traveler's send under a permit. Never throws: null means "already claimed, nothing done". */
    private ReminderSend.Outcome sendUnderPermit(final Semaphore permits, final Trip trip,
            final TripReminder reminder, final LocalDate sendDate, final boolean manual, final AuditActor who,
            final String from, final String replyTo, final ReminderRecipients.Target target) {
        try {
            permits.acquire();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            return sendOne(trip, reminder, sendDate, manual, who, from, replyTo, target);
        } catch (final RuntimeException ex) {
            log.error("Reminder '{}' of trip {} failed for {}", reminder.getName(), trip.getId(),
                    target.getPersonId(), ex);
            return ReminderSend.Outcome.FAILED;
        } finally {
            permits.release();
        }
    }

    private ReminderSend.Outcome sendOne(final Trip trip, final TripReminder reminder, final LocalDate sendDate,
            final boolean manual, final AuditActor who, final String from, final String replyTo,
            final ReminderRecipients.Target target) {
        final List<String> addresses = target.mailTo().stream().map(Person::getEmail).toList();
        final ReminderSend claim = ReminderSend.builder().tripId(trip.getId()).reminderId(reminder.getId())
                .sendDate(sendDate).personId(target.traveler().getId()).reminderName(reminder.getName())
                .personName(target.getName()).toEmail(String.join(", ", addresses))
                .subject(reminder.getSubject()).outcome(ReminderSend.Outcome.CLAIMED).manual(manual)
                .at(clock.get()).build();
        if (!claim(claim)) {
            return null;
        }
        if (target.mailTo().isEmpty()) {
            return record(claim.withOutcome(ReminderSend.Outcome.NO_ADDRESS));
        }
        boolean allSent = true;
        for (final Person addressee : target.mailTo()) {
            final Map<String, String> values = ReminderRecipients.valuesFor(target, addressee);
            allSent &= mailer.send(from, addressee.getEmail(), replyTo,
                    ReminderText.renderPlain(reminder.getSubject(), values),
                    ReminderText.renderCleaned(reminder.getBody(), values), who);
            if (reminder.isPush()) {
                pusher.push(trip, reminder, addressee.getId(),
                        ReminderText.renderPlain(reminder.getSubject(), values),
                        ReminderText.renderPlain(reminder.getPushText(), values));
            }
        }
        return record(claim.withPushed(reminder.isPush())
                .withOutcome(allSent ? ReminderSend.Outcome.SENT : ReminderSend.Outcome.FAILED));
    }

    private static boolean claim(final ReminderSend claim) {
        try {
            return DAO.getInstance().claimReminderSend(claim);
        } catch (final IOException ex) {
            // No claim, no mail: an unrecorded send could be repeated by the next run.
            log.error("Unable to claim reminder send {} of trip {}", claim.getSortKey(), claim.getTripId(), ex);
            return false;
        }
    }

    private static ReminderSend.Outcome record(final ReminderSend send) {
        try {
            DAO.getInstance().saveReminderSend(send);
        } catch (final IOException ex) {
            // The claim row stays "Sending", which the history shows; the mail itself already went (or failed).
            log.error("Unable to record reminder send {} of trip {}", send.getSortKey(), send.getTripId(), ex);
        }
        return send.getOutcome();
    }

    /**
     * A run that matched nobody still leaves one row (no traveler), so the page can say "sent, nobody needed
     * it" instead of calling an on-time run with an empty audience "missed".
     */
    private void recordNobody(final Trip trip, final TripReminder reminder, final LocalDate sendDate,
            final boolean manual) {
        claim(ReminderSend.builder().tripId(trip.getId()).reminderId(reminder.getId()).sendDate(sendDate)
                .reminderName(reminder.getName()).personName("(nobody needed this message)")
                .subject(reminder.getSubject()).outcome(ReminderSend.Outcome.NOBODY).manual(manual)
                .at(clock.get()).build());
    }

    private static Summary summarize(final List<ReminderSend.Outcome> outcomes) {
        int sent = 0;
        int failed = 0;
        int noAddress = 0;
        int already = 0;
        for (final ReminderSend.Outcome outcome : outcomes) {
            if (outcome == null) {
                already++;
            } else if (outcome == ReminderSend.Outcome.SENT) {
                sent++;
            } else if (outcome == ReminderSend.Outcome.NO_ADDRESS) {
                noAddress++;
            } else {
                failed++;
            }
        }
        return new Summary(sent, failed, noAddress, already, null);
    }
}
