package org.paulsens.trip.web;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.paulsens.trip.security.PasswordHasher;
import org.paulsens.trip.security.Pepper;
import org.paulsens.trip.web.DeepHealth.Check;
import org.paulsens.trip.web.DeepHealth.Status;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link DeepHealth}: the deploy's dependency check. What matters is that it can neither pass a broken server
 * (a throw or a hang is FAIL, and one failure does not hide the others) nor be driven by its callers (a result is
 * reused for {@link DeepHealth#MEMO}).
 */
public class DeepHealthTest {

    private static final Duration SHORT = Duration.ofMillis(300);

    private static DeepHealth of(final Map<String, Check> checks, final AtomicLong clock) {
        return new DeepHealth(checks, SHORT, clock::get);
    }

    private static Map<String, Check> checks(final Object... namesAndChecks) {
        final Map<String, Check> map = new LinkedHashMap<>();
        for (int i = 0; i < namesAndChecks.length; i += 2) {
            map.put((String) namesAndChecks[i], (Check) namesAndChecks[i + 1]);
        }
        return map;
    }

    @Test
    public void everyCheckAnsweringIsHealthyInDeclaredOrder() {
        final DeepHealth.Report report = of(checks(
                "b", (Check) () -> Status.OK,
                "a", (Check) () -> Status.NOT_CONFIGURED), new AtomicLong()).report();
        Assert.assertTrue(report.healthy(), "NOT_CONFIGURED is the caller's to judge, not a failure");
        Assert.assertEquals(List.copyOf(report.checks().keySet()), List.of("b", "a"));
        Assert.assertEquals(report.checks().get("a").wire(), "not_configured");
    }

    @Test
    public void aThrowingCheckFailsWithoutHidingTheOthers() {
        final DeepHealth.Report report = of(checks(
                "broken", (Check) DeepHealthTest::explode,
                "fine", (Check) () -> Status.OK), new AtomicLong()).report();
        Assert.assertFalse(report.healthy());
        Assert.assertEquals(report.checks(), Map.of("broken", Status.FAIL, "fine", Status.OK));
    }

    @Test
    public void aCheckStillRunningAtTheDeadlineFails() {
        final long started = System.nanoTime();
        final DeepHealth.Report report = of(checks(
                "hangs", (Check) DeepHealthTest::hang,
                "fast", (Check) () -> Status.OK), new AtomicLong()).report();
        Assert.assertEquals(report.checks(), Map.of("hangs", Status.FAIL, "fast", Status.OK));
        Assert.assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5,
                "the deadline, not the hang, decides how long the answer takes");
    }

    @Test
    public void anInterruptedCallerReportsUnfinishedChecksAsFailedAndKeepsTheFlag() {
        final DeepHealth health = of(checks("slow", (Check) DeepHealthTest::hang), new AtomicLong());
        Thread.currentThread().interrupt();
        try {
            Assert.assertEquals(health.runAll().checks(), Map.of("slow", Status.FAIL));
            Assert.assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is restored, not eaten");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void aResultIsReusedUntilTheMemoRunsOut() {
        final AtomicInteger runs = new AtomicInteger();
        final AtomicLong clock = new AtomicLong(1_000);
        final DeepHealth health = of(checks("counted", (Check) () -> counted(runs)), clock);
        health.report();
        clock.addAndGet(DeepHealth.MEMO.toMillis() - 1);
        health.report();
        Assert.assertEquals(runs.get(), 1, "a caller inside the memo must not reach the dependencies");
        clock.addAndGet(1);
        health.report();
        Assert.assertEquals(runs.get(), 2);
    }

    @Test
    public void productionChecksInLocalModeReachOnlyTheFakes() {
        final DeepHealth.Report report = DeepHealth.production().report();
        Assert.assertEquals(report.checks(), Map.of(
                "cache", Status.OK,
                "datastore", Status.OK,
                "pepper", Status.NOT_CONFIGURED,
                "mail", Status.NOT_CONFIGURED,
                "media", Status.NOT_CONFIGURED),
                "local mode has an in-memory cache and store, and no pepper, SES or bucket to ask");
        Assert.assertTrue(report.healthy());
    }

    @Test
    public void pepperVersionNamesTheVersionNotTheKey() {
        Assert.assertEquals(new PasswordHasher(Pepper.none()).pepperVersion(), 0);
        Assert.assertEquals(new PasswordHasher(Pepper.of(3, new byte[32])).pepperVersion(), 3);
    }

    /** A dependency that answers with an error naming an AWS resource, which must never reach the wire. */
    private static Status explode() {
        throw new IllegalStateException("arn:aws:secret-thing");
    }

    /** A dependency that never answers in time. */
    private static Status hang() throws InterruptedException {
        Thread.sleep(10_000);
        return Status.OK;
    }

    private static Status counted(final AtomicInteger runs) {
        runs.incrementAndGet();
        return Status.OK;
    }
}
