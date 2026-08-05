package org.paulsens.trip.chat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.dynamo.DAO;

/**
 * Fires the daily digest, coordinated through Valkey so N tasks send it once.
 *
 * <p>Every task schedules this independently — there is no leader and no external scheduler in this stack — so the
 * coordination is the whole design:
 *
 * <ol>
 *   <li><b>A lock per run.</b> One task wins and sends; the others reschedule. Keyed by run rather than globally, so
 *       a lock left behind by a crash cannot block tomorrow's digest as well as today's.</li>
 *   <li><b>A progress hash, written before each send.</b> A person's id goes in <em>before</em> their mail is
 *       attempted and is removed only if the attempt fails. So a crash between the two leaves them marked and they
 *       are skipped: <b>missing one summary beats sending it twice.</b> A resuming task therefore continues where the
 *       dead one stopped instead of starting over.</li>
 *   <li><b>A completion flag.</b> Written when the run finishes. Losing tasks stop retrying when they see it — the
 *       alternative is a straggler concluding nothing ran and mailing everyone again.</li>
 *   <li><b>Retry with jitter, and a hard stop.</b> A loser retries at +5 minutes plus jitter; the jitter keeps N
 *       tasks from waking together and contending on the same lock forever. After two hours a task gives up even
 *       with no flag, so a permanently failing run cannot retry until the end of time.</li>
 * </ol>
 *
 * <p>Everything here degrades to "no digest" rather than to duplicates. With the cache unavailable
 * {@code tryAcquireLock} returns false by contract, so no task believes it holds the lock and nothing is sent —
 * which is the correct direction to fail for a daily summary.
 */
@Slf4j
public final class ChatDigestScheduler {


    /** Retry spacing for a task that did not win the lock. */
    private static final Duration RETRY_BASE = Duration.ofMinutes(5);
    /** Spread so N tasks do not wake in lockstep and collide on the lock indefinitely. */
    private static final Duration RETRY_JITTER = Duration.ofMinutes(2);
    /** A task stops attempting a run after this, flag or no flag. */
    private static final Duration GIVE_UP_AFTER = Duration.ofHours(2);
    /** Long enough for a full run, short enough that a crashed holder frees it within a retry cycle. */
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static volatile ChatDigestScheduler instance;

    private final ScheduledExecutorService scheduler;
    private final CacheClient cacheClient;
    private final ConfigCommands config;
    private final ChatDigestSender sender;

    private ChatDigestScheduler(
            final CacheClient cacheClient, final ConfigCommands config, final ChatDigestSender sender) {
        this.cacheClient = cacheClient;
        this.config = config;
        this.sender = sender;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(this::newThread);
    }

    private Thread newThread(final Runnable body) {
        final Thread thread = new Thread(body, "chat-digest");
        // Daemon: a container shutting down must not wait on a sleeping scheduler.
        thread.setDaemon(true);
        return thread;
    }

    /** Starts the scheduler once per JVM. Safe to call again; later calls do nothing. */
    public static synchronized void start() {
        if (instance != null) {
            return;
        }
        instance = new ChatDigestScheduler(
                DAO.getInstance().getCacheClient(), new ConfigCommands(), new ChatDigestSender());
        instance.scheduleNextRun();
        log.info("Chat digest scheduler started");
    }

    public static synchronized void stop() {
        if (instance == null) {
            return;
        }
        instance.scheduler.shutdownNow();
        instance = null;
        log.info("Chat digest scheduler stopped");
    }

    /** Test seam: build one without touching the singleton or the real DAO. */
    static ChatDigestScheduler forTest(
            final CacheClient cacheClient, final ConfigCommands config, final ChatDigestSender sender) {
        return new ChatDigestScheduler(cacheClient, config, sender);
    }

    private void scheduleNextRun() {
        final Duration delay = untilNextRun(ZonedDateTime.now(zone()));
        if (submit(() -> runToday(), delay.toMillis())) {
            log.debug("Next chat digest attempt in {} minutes", delay.toMinutes());
        }
    }

    /**
     * Queues work, tolerating a shut-down executor.
     *
     * @return false when the scheduler is stopping, which is ordinary at shutdown and not worth an error.
     */
    private boolean submit(final Runnable body, final long delayMs) {
        try {
            scheduler.schedule(body, delayMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (final RejectedExecutionException ex) {
            log.debug("Chat digest scheduler is shutting down; not queueing further work");
            return false;
        }
    }

    private void runToday() {
        final String runId = LocalDate.now(zone()).format(RUN_ID);
        attempt(runId, Instant.now());
    }

    /**
     * One attempt at a run, rescheduling itself as needed. Never throws: this is the top of a scheduler thread, and
     * an escaping exception would silently kill the only thread that can ever send a digest again.
     */
    void attempt(final String runId, final Instant firstAttempt) {
        try {
            if (attemptOnce(runId, firstAttempt)) {
                // Finished with THIS run -- so arm the next one. Without this the executor is left with nothing
                // queued and the scheduler is done for the life of the JVM: digests fired once and never again.
                // It bit hardest in the shipped-dark state, where the disabled check returns "finished"
                // immediately, so the very first run silently retired the scheduler and enabling the config
                // later would have done nothing until a restart.
                scheduleNextRun();
            } else {
                reschedule(runId, firstAttempt);
            }
        } catch (final RuntimeException ex) {
            log.error("Chat digest attempt failed for run {}", runId, ex);
            reschedule(runId, firstAttempt);
        }
    }

    /** @return true when this task is finished with the run — sent it, saw it done, or gave up. */
    boolean attemptOnce(final String runId, final Instant firstAttempt) {
        if (!config.getBoolean(KnownSettings.CHAT_DIGEST_ENABLED)) {
            // Ships dark. Enabling is a config-table change, not a deploy.
            return true;
        }
        if (isComplete(runId)) {
            return true;
        }
        if (Duration.between(firstAttempt, Instant.now()).compareTo(GIVE_UP_AFTER) >= 0) {
            // No flag, but stop anyway: a run that has failed for two hours will not start working, and a scheduler
            // retrying forever is a slow leak that outlives whatever caused it.
            log.warn("Giving up on chat digest run {} after {}", runId, GIVE_UP_AFTER);
            return true;
        }
        if (!coordinationAvailable(runId)) {
            // Refuse to send at all. Every safeguard here -- the lock, the progress hash, the completion flag --
            // lives in the cache, so without a working one there is nothing stopping each task from mailing
            // everybody, on every retry. NoopCacheClient in particular GRANTS every lock and remembers nothing, so
            // "no cache" would mean N duplicate digests rather than none.
            log.warn("Chat digest run {} skipped: no usable cache, so a send could not be coordinated", runId);
            return true;
        }
        if (!cacheClient.tryAcquireLock(CacheKeys.chatDigestLockKey(runId), LOCK_TTL)) {
            return false;
        }
        return dispatch(runId);
    }

    /**
     * Holds the lock and works through the candidates.
     *
     * @return true when the run completed and the flag was written
     */
    private boolean dispatch(final String runId) {
        final List<ChatDigestSender.Candidate> candidates = sender.candidates(Instant.now());
        final Map<String, String> alreadyDone = cacheClient
                .getHash(CacheKeys.chatDigestProgressKey(runId));
        int sent = 0;
        int failed = 0;
        for (final ChatDigestSender.Candidate candidate : candidates) {
            final int result = sendOne(runId, candidate, alreadyDone);
            sent += result > 0 ? 1 : 0;
            failed += result < 0 ? 1 : 0;
        }
        if (failed > 0) {
            // Leave the flag unwritten so a later attempt picks up exactly the ones that failed -- the progress hash
            // already excludes everyone who succeeded, so a retry is not a re-send.
            log.warn("Chat digest run {}: {} sent, {} failed; will retry", runId, sent, failed);
            releaseLock(runId);
            return false;
        }
        markComplete(runId);
        releaseLock(runId);
        log.info("Chat digest run {} complete: {} sent", runId, sent);
        return true;
    }

    /** @return 1 sent, 0 skipped, -1 failed */
    private int sendOne(
            final String runId,
            final ChatDigestSender.Candidate candidate,
            final Map<String, String> alreadyDone) {
        final String field = candidate.progressField();
        // Cheap pre-filter only. The snapshot was read once at the top of the dispatch and cannot see a claim made
        // after that, so it must never be the thing that decides.
        if (alreadyDone.containsKey(field)) {
            return 0;
        }
        // The real claim, and the only race-free one: SET-if-absent, so exactly one caller wins even when two
        // tasks dispatch at once -- which the run lock allows the moment its TTL lapses part-way through a long
        // run. Without this, both would consult their own stale snapshots and both would mail the same person.
        final String claimKey = CacheKeys.chatDigestClaimKey(runId, field);
        if (!cacheClient.tryAcquireLock(claimKey, CacheKeys.CHAT_DIGEST_RUN_TTL)) {
            return 0;
        }
        // Recorded BEFORE sending. The window between claim and send is the one place a crash loses a digest, and
        // that is the intended trade: an unrecorded send would be a duplicate on the next attempt.
        cacheClient.putHashField(CacheKeys.chatDigestProgressKey(runId), field,
                Long.toString(System.currentTimeMillis()));
        cacheClient.expire(CacheKeys.chatDigestProgressKey(runId), CacheKeys.CHAT_DIGEST_RUN_TTL);
        if (sender.send(candidate)) {
            return 1;
        }
        // Roll both back so a retry picks this person up again.
        cacheClient.removeHashField(CacheKeys.chatDigestProgressKey(runId), field);
        cacheClient.releaseLock(claimKey);
        return -1;
    }

    private void reschedule(final String runId, final Instant firstAttempt) {
        final long jitterMs = ThreadLocalRandom.current().nextLong(RETRY_JITTER.toMillis() + 1);
        final long delayMs = RETRY_BASE.toMillis() + jitterMs;
        if (submit(() -> attempt(runId, firstAttempt), delayMs)) {
            log.debug("Chat digest run {} retrying in {}s", runId, delayMs / 1000);
        }
    }

    /**
     * Whether the cache can actually hold the state this protocol depends on.
     *
     * <p>Checked by writing a value and reading it back, because "a cache is configured" is not the same question.
     * A lock that is granted but not remembered is worse than no lock: it looks like exclusivity and provides none.
     */
    private boolean coordinationAvailable(final String runId) {
        final String probeKey = CacheKeys.chatDigestLockKey(runId + ":probe");
        final String token = Long.toString(System.nanoTime());
        if (!cacheClient.putValue(probeKey, token, Duration.ofMinutes(1))) {
            return false;
        }
        return cacheClient.getValue(probeKey).filter(token::equals).isPresent();
    }

    private boolean isComplete(final String runId) {
        return cacheClient.getValue(CacheKeys.chatDigestCompleteKey(runId)).isPresent();
    }

    private void markComplete(final String runId) {
        cacheClient.putValue(CacheKeys.chatDigestCompleteKey(runId), "1", CacheKeys.CHAT_DIGEST_RUN_TTL);
    }

    private void releaseLock(final String runId) {
        cacheClient.releaseLock(CacheKeys.chatDigestLockKey(runId));
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(config.getString(KnownSettings.CHAT_DIGEST_ZONE));
        } catch (final RuntimeException ex) {
            log.warn("Bad chat.digest.zone; falling back to UTC", ex);
            return ZoneId.of("UTC");
        }
    }

    /**
     * How long until the next configured send time. Always strictly positive, so a scheduler starting exactly on the
     * hour waits until tomorrow rather than firing immediately and again in a millisecond.
     */
    Duration untilNextRun(final ZonedDateTime now) {
        final int hour = config.getInt(KnownSettings.CHAT_DIGEST_HOUR, 0, 23);
        ZonedDateTime next = now.with(LocalTime.of(hour, 0));
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next);
    }
}
