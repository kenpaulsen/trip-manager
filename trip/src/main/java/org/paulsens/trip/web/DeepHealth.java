package org.paulsens.trip.web;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.MailCommands;
import org.paulsens.trip.action.MediaCommands;
import org.paulsens.trip.api.Beans;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheProbes;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.security.PasswordHasher;

/**
 * Whether this server can reach every outside service it depends on: the shared cache, the datastore, the
 * password pepper, outbound mail and the media bucket. The deploy's green check asks it before production traffic
 * moves, so a task that boots but cannot reach one of them is rolled back instead of promoted.
 *
 * <p>Not the load balancer's health check, on purpose. That one ({@code health.jsf}) decides whether the ONLY
 * running task stays in service, so it must not fail over a mail or media blip; this one decides whether a NEW
 * task replaces a working one, where failing on any dependency is exactly right.
 *
 * <p>The endpoint is reachable by anyone, so: the answer names each check and its state and nothing else (an AWS
 * error message carries account ids and resource names; it goes to the log), every check is bounded by one shared
 * deadline, and a result is reused for {@link #MEMO} so repeated calls cannot drive calls to SES, S3 or the
 * datastore. A check with nothing configured answers {@link Status#NOT_CONFIGURED} rather than passing; the green
 * check accepts only {@link Status#OK}, so a deployment missing a setting fails there instead of looking healthy.
 */
@Slf4j
public final class DeepHealth {

    /** One check's outcome, written in lower case on the wire. */
    public enum Status {
        OK, FAIL, NOT_CONFIGURED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One dependency check: OK when it answered, NOT_CONFIGURED when there is nothing to ask; throws otherwise. */
    @FunctionalInterface
    public interface Check {
        Status run() throws Exception;
    }

    /** The checks in order, by name; healthy when none FAILED (NOT_CONFIGURED is the caller's to judge). */
    public record Report(Map<String, Status> checks) {
        public boolean healthy() {
            return !checks.containsValue(Status.FAIL);
        }
    }

    /** All checks together must answer within this, well inside the green check's 15 s read timeout. */
    static final Duration DEADLINE = Duration.ofSeconds(5);
    /** Each AWS call's own timeout: below the deadline, so a slow call fails as itself, not as a timeout. */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(4);
    static final Duration MEMO = Duration.ofSeconds(15);

    private final Map<String, Check> checks;
    private final Duration deadline;
    private final LongSupplier clock;
    /** A private lock, not {@code synchronized}: a caller holding the instance could otherwise stall every probe. */
    private final ReentrantLock lock = new ReentrantLock();
    private Report last;
    private long lastAt;

    DeepHealth(final Map<String, Check> checks, final Duration deadline, final LongSupplier clock) {
        this.checks = checks;
        this.deadline = deadline;
        this.clock = clock;
    }

    /** The production checks. */
    public static DeepHealth production() {
        final Map<String, Check> checks = new LinkedHashMap<>();
        checks.put("cache", DeepHealth::cache);
        checks.put("datastore", DeepHealth::datastore);
        checks.put("pepper", DeepHealth::pepper);
        checks.put("mail", DeepHealth::mail);
        // One instance for the server's life: it builds its S3 client on first use and keeps it.
        final MediaCommands media = new MediaCommands();
        checks.put("media", () -> media(media));
        return new DeepHealth(checks, DEADLINE, System::currentTimeMillis);
    }

    /**
     * Runs every check, or answers the previous result when it is younger than {@link #MEMO}. Locked so that
     * callers arriving together share one run rather than each starting their own.
     */
    public Report report() {
        lock.lock();
        try {
            final long now = clock.getAsLong();
            if (last == null || now - lastAt >= MEMO.toMillis()) {
                last = runAll();
                lastAt = now;
            }
            return last;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Every check at once, under one deadline. Each writes its own outcome, so whatever has not answered when the
     * deadline passes reads as FAIL; checks never short-circuit one another, so one log line names every problem.
     */
    Report runAll() {
        final Map<String, Status> done = new ConcurrentHashMap<>();
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAll(),
                config -> config.withTimeout(deadline))) {
            checks.forEach((name, check) -> scope.fork(() -> done.put(name, outcome(name, check))));
            scope.join();
        } catch (final StructuredTaskScope.TimeoutException ex) {
            log.warn("Deep health: checks still running after {}: they count as failed", deadline);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        final Map<String, Status> ordered = new LinkedHashMap<>();
        checks.keySet().forEach(name -> ordered.put(name, done.getOrDefault(name, Status.FAIL)));
        final Report report = new Report(ordered);
        if (!report.healthy()) {
            log.warn("Deep health FAILED: {}", ordered);
        }
        return report;
    }

    private static Status outcome(final String name, final Check check) {
        try {
            return check.run();
        } catch (final Exception ex) {
            // The reason goes to the log only: an AWS message names the account and the resource.
            log.warn("Deep health check '{}' failed", name, ex);
            return Status.FAIL;
        } catch (final LinkageError | ServiceConfigurationError ex) {
            // A class or SDK service the image lacks: exactly the broken build this check exists to catch. Left
            // uncaught it would die inside the fork, read as FAIL with no cause in either log.
            log.warn("Deep health check '{}' failed: the image is missing a class or service", name, ex);
            return Status.FAIL;
        }
    }

    /** A value written and read back: proves the shared cache holds state (a Noop cache remembers nothing). */
    static Status cache() {
        return CacheProbes.remembers(DAO.getInstance().getCacheClient(), CacheKeys.deepHealthProbeKey())
                ? Status.OK : Status.FAIL;
    }

    /** One uncached point read; throws when the table cannot be reached. */
    static Status datastore() {
        DAO.getInstance().probeDataStore();
        return Status.OK;
    }

    /** Production must hash under a pepper: version 0 means the secret was never configured. */
    static Status pepper() {
        if (FakeData.isLocal()) {
            return Status.NOT_CONFIGURED;
        }
        return PasswordHasher.getInstance().pepperVersion() > 0 ? Status.OK : Status.FAIL;
    }

    /** Local mode fakes sending, so there is no SES to ask. */
    static Status mail() {
        if (FakeData.isLocal()) {
            return Status.NOT_CONFIGURED;
        }
        Beans.get(MailCommands.class).probeSes(CALL_TIMEOUT);
        return Status.OK;
    }

    static Status media(final MediaCommands media) {
        return media.probeBucket(CALL_TIMEOUT) ? Status.OK : Status.NOT_CONFIGURED;
    }
}
