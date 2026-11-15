package org.paulsens.trip.reminder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.ReminderSender;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.ReminderSend;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripReminder;
import org.paulsens.trip.model.TripReminderPlan;
import org.paulsens.trip.audit.RequestContext;

/**
 * Sends each trip's approved messages on their send date, once a day ({@code docs/reminders.md}). The shape is
 * {@code ChatDigestScheduler}'s: one virtual timer thread per JVM, a run at the configured hour, a short retry
 * after a failure, and the system identity bound for the whole attempt.
 *
 * <p>What differs is where "once" comes from. The digest's guarantee is a cache lock, which is why it must
 * probe the cache first. Here the guarantee is the send ledger in DynamoDB ({@code ReminderSender}: a
 * write-once claim per traveler, reminder and date), so the cache lock below only saves two servers from doing
 * the same reads; with no cache at all, or a cache that grants every lock, each message still goes out once.
 *
 * <p>It ships dark: {@code reminders.enabled} is off by default. And on top of that switch nothing is sent that
 * a facilitator did not approve; that check is the sender's, not this class's.
 */
@Slf4j
public final class ReminderScheduler {
    private static final Duration RETRY_BASE = Duration.ofMinutes(5);
    private static final Duration RETRY_JITTER = Duration.ofMinutes(2);
    /** A run that keeps failing is abandoned; tomorrow's run catches the day up ({@link #CATCH_UP_DAYS}). */
    private static final Duration GIVE_UP_AFTER = Duration.ofHours(2);
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);
    /**
     * How many earlier days a run still sends for, when that day's run never happened (the server was down, the
     * switch was off). Older than this a message is "Missed" and waits for the facilitator's Send now: a balance
     * reminder that arrives a week late, unannounced, is the confusion this feature exists to prevent.
     */
    static final int CATCH_UP_DAYS = 1;
    /** Trips that ended longer ago than this are not read: no reminder is scheduled that far after a trip. */
    private static final int ENDED_WITHIN_DAYS = 60;

    /** Guards start and stop. A private lock, not the class's own, which any code holding the class could take. */
    private static final Object LIFECYCLE = new Object();
    private static volatile ReminderScheduler instance;

    private final ScheduledExecutorService scheduler;
    private final CacheClient cacheClient;
    private final ConfigCommands config;
    private final ReminderSender sender;
    private final Supplier<Instant> clock;

    private ReminderScheduler(final CacheClient cacheClient, final ConfigCommands config,
            final ReminderSender sender, final Supplier<Instant> clock) {
        this.cacheClient = cacheClient;
        this.config = config;
        this.sender = sender;
        this.clock = clock;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(this::newThread);
    }

    Thread newThread(final Runnable body) {
        // Virtual, so a thread that sleeps until tomorrow costs no carrier, and always daemon.
        return Thread.ofVirtual().name("trip-reminders").unstarted(body);
    }

    /** Starts the scheduler once per JVM. Safe to call again; later calls do nothing. */
    public static void start() {
        synchronized (LIFECYCLE) {
            if (instance != null) {
                return;
            }
            instance = new ReminderScheduler(DAO.getInstance().getCacheClient(), new ConfigCommands(),
                    new ReminderSender(), Instant::now);
            instance.scheduleNextRun();
        }
        log.info("Trip reminder scheduler started");
    }

    public static void stop() {
        synchronized (LIFECYCLE) {
            if (instance == null) {
                return;
            }
            instance.scheduler.shutdownNow();
            instance = null;
        }
        log.info("Trip reminder scheduler stopped");
    }

    /** Test seam: build one without touching the singleton. */
    static ReminderScheduler forTest(final CacheClient cacheClient, final ConfigCommands config,
            final ReminderSender sender, final Supplier<Instant> clock) {
        return new ReminderScheduler(cacheClient, config, sender, clock);
    }

    private void scheduleNextRun() {
        final Duration delay = untilNextRun(ZonedDateTime.ofInstant(clock.get(), zone()));
        if (submit(this::runToday, delay.toMillis())) {
            log.debug("Next trip reminder run in {} minutes", delay.toMinutes());
        }
    }

    private boolean submit(final Runnable body, final long delayMs) {
        try {
            scheduler.schedule(body, delayMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (final RejectedExecutionException ex) {
            log.debug("Trip reminder scheduler is shutting down; not queueing further work");
            return false;
        }
    }

    private void runToday() {
        attempt(today(), clock.get());
    }

    /** The day it is where the reminders are scheduled: {@code reminders.zone} decides what "March 3" means. */
    LocalDate today() {
        return LocalDate.ofInstant(clock.get(), zone());
    }

    /**
     * One attempt at a day's run, rescheduling itself as needed. Never throws: this is the top of the only
     * thread that can ever send a reminder again.
     */
    void attempt(final LocalDate day, final Instant firstAttempt) {
        ScopedValue.where(RequestContext.SCOPE, RequestContext.system()).run(() -> boundAttempt(day, firstAttempt));
    }

    private void boundAttempt(final LocalDate day, final Instant firstAttempt) {
        try {
            if (attemptOnce(day, firstAttempt)) {
                // Finished with this day, so arm the next one; without it the scheduler retires for good.
                scheduleNextRun();
            } else {
                reschedule(day, firstAttempt);
            }
        } catch (final RuntimeException ex) {
            log.error("Trip reminder run for {} failed", day, ex);
            reschedule(day, firstAttempt);
        }
    }

    private void reschedule(final LocalDate day, final Instant firstAttempt) {
        // The jitter only keeps several instances from retrying in step, so the clock's low bits are plenty.
        final long jitterMs = Math.floorMod(System.nanoTime(), RETRY_JITTER.toMillis() + 1);
        final long delayMs = RETRY_BASE.toMillis() + jitterMs;
        submit(() -> attempt(day, firstAttempt), delayMs);
    }

    /**
     * @return true when this instance is finished with the day (sent, switched off, another instance is on it,
     *         or given up), false to try again shortly.
     */
    boolean attemptOnce(final LocalDate day, final Instant firstAttempt) {
        if (!config.getBoolean(KnownSettings.REMINDERS_ENABLED)) {
            return true;
        }
        if (Duration.between(firstAttempt, clock.get()).compareTo(GIVE_UP_AFTER) > 0) {
            log.error("Trip reminder run for {} abandoned after repeated failures", day);
            return true;
        }
        // Best effort only (see the class comment): the ledger, not this lock, is what prevents a second send.
        if (!cacheClient.tryAcquireLock(CacheKeys.reminderRunLockKey(day.toString()), LOCK_TTL)) {
            return true;
        }
        runFor(day);
        return true;
    }

    /** Sends everything due on {@code day} (and the catch-up days before it). Returns the messages sent. */
    int runFor(final LocalDate day) {
        int sent = 0;
        final LocalDateTime endedAfter = day.minusDays(ENDED_WITHIN_DAYS).atStartOfDay();
        for (final Trip trip : DAO.getInstance().getActiveTrips(endedAfter, Cached.NO)) {
            try {
                sent += runTrip(trip, day);
            } catch (final RuntimeException ex) {
                // One trip's bad row must not cost every other trip its reminders.
                log.error("Trip reminders for trip {} failed on {}", trip.getId(), day, ex);
            }
        }
        log.info("Trip reminder run for {}: {} message(s) sent", day, sent);
        return sent;
    }

    private int runTrip(final Trip trip, final LocalDate day) {
        final TripReminderPlan plan = DAO.getInstance().getReminderPlan(trip.getId()).orElse(null);
        if (plan == null || plan.getReminders().isEmpty()) {
            return 0;
        }
        List<ReminderSend> history = null;
        int sent = 0;
        for (final TripReminder reminder : plan.getReminders()) {
            final LocalDate sendDate = reminder.sendDate(trip);
            if (sendDate == null || !reminder.isApprovedFor(trip) || !isDue(sendDate, day)) {
                continue;
            }
            if (!sendDate.equals(day)) {
                // A catch-up day: only when that day's run never happened. Had it run, sending again today
                // would mail people who joined the audience since, a day late and unannounced.
                history = (history == null) ? DAO.getInstance().getReminderSends(trip.getId()) : history;
                if (alreadyRan(history, reminder, sendDate)) {
                    continue;
                }
            }
            final ReminderSender.Summary summary =
                    sender.sendAll(trip, plan, reminder, sendDate, day, false, AuditActor.system());
            new AuditCommands().reminder(trip.getId(), trip.getOrgId(), "Messages of '" + trip.getTitle()
                    + "': sent '" + reminder.getName() + "' for " + sendDate + ". " + summary.text(),
                    AuditActor.system());
            sent += summary.sent();
        }
        return sent;
    }

    static boolean isDue(final LocalDate sendDate, final LocalDate day) {
        return !sendDate.isAfter(day) && !sendDate.isBefore(day.minusDays(CATCH_UP_DAYS));
    }

    static boolean alreadyRan(final List<ReminderSend> history, final TripReminder reminder,
            final LocalDate sendDate) {
        for (final ReminderSend send : history) {
            if (reminder.getId().equals(send.getReminderId()) && sendDate.equals(send.getSendDate())) {
                return true;
            }
        }
        return false;
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(config.getString(KnownSettings.REMINDERS_ZONE));
        } catch (final RuntimeException ex) {
            log.warn("Bad reminders.zone; falling back to UTC", ex);
            return ZoneId.of("UTC");
        }
    }

    /** How long until the next configured send time; always strictly positive. */
    Duration untilNextRun(final ZonedDateTime now) {
        final int hour = config.getInt(KnownSettings.REMINDERS_HOUR, 0, 23);
        ZonedDateTime next = now.with(LocalTime.of(hour, 0));
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next);
    }
}
