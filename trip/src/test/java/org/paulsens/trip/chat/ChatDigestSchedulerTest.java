package org.paulsens.trip.chat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.NoopCacheClient;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatSettings;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The digest's multi-instance coordination.
 *
 * <p>Every ECS task schedules the digest independently, so the interesting behaviour is not "does it send" but "do N
 * tasks send it exactly once". These tests drive {@code attemptOnce} directly rather than through the scheduler's
 * clock, so the protocol is asserted without waiting on real time.
 */
public class ChatDigestSchedulerTest {

    private static final String RUN = "2026-07-30";

    private InMemoryCacheClient cache;
    private RecordingSender sender;
    private ConfigCommands enabled;

    @BeforeMethod
    public void setUp() {
        cache = new InMemoryCacheClient();
        sender = new RecordingSender();
        enabled = new AlwaysEnabledConfig();
    }

    @Test
    public void oneInstanceSendsAndTheOthersStandDown() {
        final ChatDigestScheduler first = ChatDigestScheduler.forTest(cache, enabled, sender);
        final ChatDigestScheduler second = ChatDigestScheduler.forTest(cache, enabled, sender);
        sender.candidates.add(candidate("p1"));
        sender.candidates.add(candidate("p2"));

        Assert.assertTrue(first.attemptOnce(RUN, Instant.now()), "the lock winner finishes the run");
        // The second instance sees the completion flag and is done -- it must NOT send again.
        Assert.assertTrue(second.attemptOnce(RUN, Instant.now()));
        Assert.assertEquals(sender.sent.size(), 2, "each person gets exactly one digest: " + sender.sent);
    }

    @Test
    public void aSecondInstanceWithoutTheLockRetriesRatherThanSending() {
        // Take the lock behind the scheduler's back, as a peer mid-run would hold it.
        cache.tryAcquireLock(CacheKeys.chatDigestLockKey(RUN), Duration.ofMinutes(10)).join();
        sender.candidates.add(candidate("p1"));

        final ChatDigestScheduler loser = ChatDigestScheduler.forTest(cache, enabled, sender);
        Assert.assertFalse(loser.attemptOnce(RUN, Instant.now()), "no lock means retry, not send");
        Assert.assertTrue(sender.sent.isEmpty());
    }

    @Test
    public void progressIsRecordedBeforeSendingSoAResumeDoesNotDuplicate() {
        sender.candidates.add(candidate("p1"));
        sender.candidates.add(candidate("p2"));
        final ChatDigestScheduler scheduler = ChatDigestScheduler.forTest(cache, enabled, sender);
        scheduler.attemptOnce(RUN, Instant.now());

        final Map<String, String> progress = cache.getHash(CacheKeys.chatDigestProgressKey(RUN)).join();
        Assert.assertEquals(progress.size(), 2, "every person dealt with is recorded");

        // Now simulate a fresh run id being resumed: clear the completion flag but keep the progress. Nobody should
        // be mailed twice.
        cache.removeKey(CacheKeys.chatDigestCompleteKey(RUN)).join();
        cache.releaseLock(CacheKeys.chatDigestLockKey(RUN)).join();
        sender.sent.clear();
        ChatDigestScheduler.forTest(cache, enabled, sender).attemptOnce(RUN, Instant.now());
        Assert.assertTrue(sender.sent.isEmpty(), "a resumed run must skip everyone already recorded");
    }

    @Test
    public void aFailedSendIsRolledBackSoItIsRetriedNotSkipped() {
        sender.candidates.add(candidate("good"));
        sender.candidates.add(candidate("bad"));
        sender.failFor.add("bad");

        final ChatDigestScheduler scheduler = ChatDigestScheduler.forTest(cache, enabled, sender);
        Assert.assertFalse(scheduler.attemptOnce(RUN, Instant.now()),
                "a run with a failure is not complete, so it must ask to be retried");

        final Map<String, String> progress = cache.getHash(CacheKeys.chatDigestProgressKey(RUN)).join();
        Assert.assertEquals(progress.size(), 1, "only the successful person stays recorded: " + progress);
        Assert.assertFalse(cache.getValue(CacheKeys.chatDigestCompleteKey(RUN)).join().isPresent(),
                "the completion flag must NOT be written while anyone still needs mail");

        // The retry must pick up exactly the failure -- not re-send to the person who already got theirs.
        sender.failFor.clear();
        sender.sent.clear();
        cache.releaseLock(CacheKeys.chatDigestLockKey(RUN)).join();
        Assert.assertTrue(scheduler.attemptOnce(RUN, Instant.now()));
        Assert.assertEquals(sender.sent, List.of("bad"), "retry sends only what failed: " + sender.sent);
    }

    @Test
    public void aRunGivesUpAfterTwoHoursEvenWithNoCompletionFlag() {
        // Otherwise a permanently failing run retries until the process dies -- a slow leak that outlives its cause.
        cache.tryAcquireLock(CacheKeys.chatDigestLockKey(RUN), Duration.ofHours(9)).join();
        sender.candidates.add(candidate("p1"));

        final ChatDigestScheduler scheduler = ChatDigestScheduler.forTest(cache, enabled, sender);
        final Instant longAgo = Instant.now().minus(Duration.ofHours(3));
        Assert.assertTrue(scheduler.attemptOnce(RUN, longAgo), "past the window it stops attempting");
        Assert.assertTrue(sender.sent.isEmpty());
    }

    @Test
    public void nothingIsSentWhileDisabled() {
        // Ships dark: enabling is a config-table change, not a deploy.
        sender.candidates.add(candidate("p1"));
        final ChatDigestScheduler scheduler = ChatDigestScheduler.forTest(cache, new ConfigCommands(), sender);
        Assert.assertTrue(scheduler.attemptOnce(RUN, Instant.now()));
        Assert.assertTrue(sender.sent.isEmpty());
    }

    @Test
    public void withNoUsableCacheNothingIsSentAtAll() {
        // The trap this guards. NoopCacheClient GRANTS every lock and remembers nothing, so without an explicit
        // capability check every task would believe it held the lock, the progress hash would never persist, and the
        // completion flag could never be read -- N tasks would mail everybody, on every retry. Sending nothing is
        // the correct direction to fail for a daily summary.
        sender.candidates.add(candidate("p1"));
        final ChatDigestScheduler scheduler =
                ChatDigestScheduler.forTest(new NoopCacheClient(), enabled, sender);
        Assert.assertTrue(scheduler.attemptOnce(RUN, Instant.now()),
                "it stops rather than retrying: a missing cache will not fix itself on a 5-minute timer");
        Assert.assertTrue(sender.sent.isEmpty(), "nothing may be sent without coordination");
    }

    @Test
    public void theNextRunIsAlwaysInTheFuture() {
        final ChatDigestScheduler scheduler = ChatDigestScheduler.forTest(cache, enabled, sender);
        final ZoneId zone = ZoneId.of("America/Los_Angeles");
        // Exactly on the hour: must wait until tomorrow rather than fire now and again a millisecond later.
        final ZonedDateTime onTheHour = ZonedDateTime.of(2026, 7, 30, 8, 0, 0, 0, zone);
        final Duration wait = scheduler.untilNextRun(onTheHour);
        Assert.assertTrue(wait.toMinutes() > 0, "must be strictly positive, was " + wait);
        Assert.assertEquals(wait.toHours(), 24);

        final Duration fromMidnight = scheduler.untilNextRun(
                ZonedDateTime.of(2026, 7, 30, 0, 0, 0, 0, zone));
        Assert.assertEquals(fromMidnight.toHours(), 8);
    }

    // --- helpers ---

    private ChatDigestSender.Candidate candidate(final String personId) {
        final ChatChannel channel = new ChatChannel(
                ChatChannel.Id.forTrip("t1"), "t1", ChatChannel.Kind.TRIP, "Test", null, null,
                ChatSettings.defaults(), Instant.parse("2026-01-01T00:00:00Z"), "admin", null, null);
        return new ChatDigestSender.Candidate(channel, null, Person.Id.from(personId), null, null);
    }

    /** Records what it was asked to send, and can be told to fail for particular people. */
    private static final class RecordingSender extends ChatDigestSender {
        private final List<ChatDigestSender.Candidate> candidates = new ArrayList<>();
        private final List<String> sent = new CopyOnWriteArrayList<>();
        private final List<String> failFor = new CopyOnWriteArrayList<>();

        @Override
        public List<Candidate> candidates(final Instant now) {
            return List.copyOf(candidates);
        }

        @Override
        public boolean send(final Candidate candidate) {
            final String person = candidate.personId().getValue();
            if (failFor.contains(person)) {
                return false;
            }
            sent.add(person);
            return true;
        }
    }

    /** Digests enabled, everything else defaulted. */
    private static final class AlwaysEnabledConfig extends ConfigCommands {
        @Override
        public boolean getBoolean(final String name, final boolean defaultValue) {
            return "chat.digest.enabled".equals(name) || defaultValue;
        }
    }
}
