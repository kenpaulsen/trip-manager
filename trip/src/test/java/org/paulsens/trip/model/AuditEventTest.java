package org.paulsens.trip.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Pins the audit table's key design.
 *
 * <p>These are the assertions worth having, because every failure mode here is SILENT: a sort key that orders
 * wrongly does not throw, it just quietly shows the wrong records first, and nobody notices until the trail is
 * needed. Ordering is the one property the whole no-index design rests on.
 */
public class AuditEventTest {

    private static AuditEvent at(final Instant when) {
        return new AuditEvent(when, AuditAction.LOGIN, AuditOutcome.SUCCESS,
                "a@example.com", null, null, null, null, "msg", null);
    }

    @Test
    public void theOrgSurvivesTheCollisionNudgeAndBlankMeansNone() {
        final AuditEvent acme = new AuditEvent(Instant.parse("2026-09-02T12:00:00Z"), AuditAction.LOGIN,
                AuditOutcome.SUCCESS, "a@example.com", null, null, null, null, "msg", null, "acme");
        Assert.assertEquals(acme.getOrgId(), "acme");
        Assert.assertEquals(acme.withNextMilli().getOrgId(), "acme", "the retry keeps the tenancy");
        Assert.assertNull(at(Instant.now()).getOrgId(), "the pre-org constructor stamps none");
        Assert.assertNull(new AuditEvent(Instant.now(), AuditAction.LOGIN, AuditOutcome.SUCCESS,
                "a@example.com", null, null, null, null, "msg", null, "  ").getOrgId(), "blank reads as none");
    }

    @Test
    public void partitionIsTheUtcDay() {
        final AuditEvent event = at(Instant.parse("2026-07-26T23:59:59Z"));
        Assert.assertEquals(event.getPartition(), "2026-07-26");
        Assert.assertEquals(AuditEvent.partitionFor(Instant.parse("2026-01-02T00:00:00Z")), "2026-01-02");
    }

    @Test
    public void partitionUsesUtcNotTheLocalZone() {
        // A machine in the US would bucket this as the 25th if the day were computed locally, which would
        // scatter a single UTC day across two partitions depending on where the code happened to run.
        Assert.assertEquals(at(Instant.parse("2026-07-26T02:00:00Z")).getPartition(), "2026-07-26");
    }

    @Test
    public void sortKeysOrderChronologicallyAsStrings() {
        // DynamoDB compares sort keys BYTEWISE. Without zero padding, "999" sorts after "1000000000000" and
        // newest-first paging silently returns the wrong page. This is the assertion that catches that.
        final List<Instant> chronological = List.of(
                Instant.ofEpochMilli(1L),
                Instant.ofEpochMilli(999L),
                Instant.ofEpochMilli(1_000L),
                Instant.ofEpochMilli(1_600_000_000_000L),
                Instant.ofEpochMilli(1_900_000_000_000L));

        final List<String> keys = chronological.stream().map(when -> at(when).getSortKey()).toList();
        final List<String> sorted = new ArrayList<>(keys);
        sorted.sort(Comparator.naturalOrder());

        Assert.assertEquals(sorted, keys, "Lexicographic order must match chronological order");
        keys.forEach(key -> Assert.assertEquals(key.length(), 13, "Millis must be padded to 13 digits: " + key));
    }

    @Test
    public void sortKeyIsExactlyTheMillisecond() {
        // No tie-breaker in the key: collisions are resolved by nudging the time (withNextMilli), not by
        // adding entropy. The key is therefore reproducible from the timestamp alone, which is what lets a
        // range query be built from two instants.
        final Instant when = Instant.parse("2026-07-26T12:00:00Z");
        Assert.assertEquals(at(when).getSortKey(), AuditEvent.sortKeyFor(when));
        Assert.assertEquals(at(when).getSortKey(), at(when).getSortKey(), "Key must be deterministic");
    }

    @Test
    public void withNextMilliMovesForwardAndKeepsEverythingElse() {
        final AuditEvent original = new AuditEvent(Instant.parse("2026-07-26T12:00:00Z"),
                AuditAction.PERSON, AuditOutcome.FAILURE, "actor@example.com", "actor-id",
                "person", "target@example.com", "target-id", "message", AuditEvent.CURRENT_SCHEMA);

        final AuditEvent retried = original.withNextMilli();

        Assert.assertEquals(retried.getTimestamp(), original.getTimestamp().plusMillis(1));
        Assert.assertTrue(retried.getSortKey().compareTo(original.getSortKey()) > 0,
                "The retry must sort AFTER the record it collided with, preserving arrival order");
        Assert.assertEquals(retried.getPartition(), original.getPartition());
        Assert.assertEquals(retried.getAction(), original.getAction());
        Assert.assertEquals(retried.getOutcome(), original.getOutcome());
        Assert.assertEquals(retried.getActorEmail(), original.getActorEmail());
        Assert.assertEquals(retried.getTargetEmail(), original.getTargetEmail());
        Assert.assertEquals(retried.getMessage(), original.getMessage());
        Assert.assertEquals(retried.getSchemaVersion(), original.getSchemaVersion());
    }

    @Test
    public void repeatedRetriesKeepMovingForwardAndStayDistinct() {
        // Eight records once shared a millisecond in the real history, so the retry has to survive being
        // applied several times in a row without ever landing back on a taken key.
        final Set<String> keys = new HashSet<>();
        AuditEvent event = at(Instant.parse("2026-07-26T12:00:00Z"));
        for (int i = 0; i < 10; i++) {
            Assert.assertTrue(keys.add(event.getSortKey()), "Retry produced a key already used");
            event = event.withNextMilli();
        }
    }

    @Test
    public void timestampIsTruncatedSoTheKeyNeverDisagreesWithTheStoredTime() {
        // A nanosecond-precision Instant would render one time and key another; the row would appear to be at
        // a moment its own key denies.
        final AuditEvent event = at(Instant.parse("2026-07-26T12:00:00.123456789Z"));
        Assert.assertEquals(event.getTimestamp(), Instant.parse("2026-07-26T12:00:00.123Z"));
        Assert.assertEquals(event.getSortKey(), AuditEvent.sortKeyFor(event.getTimestamp()));
    }

    @Test
    public void nullsDeserializeRatherThanFailing() {
        // A row written by an older build, or edited by hand in the console, must still load: an audit record
        // that cannot be deserialized is an audit record that has been lost.
        final AuditEvent event = new AuditEvent(null, null, null, null, null, null, null, null, null, null);

        Assert.assertNotNull(event.getTimestamp());
        Assert.assertEquals(event.getAction(), AuditAction.UNKNOWN);
        Assert.assertEquals(event.getOutcome(), AuditOutcome.UNKNOWN);
        Assert.assertEquals(event.getSchemaVersion(), AuditEvent.CURRENT_SCHEMA);
        Assert.assertNotNull(event.getSortKey());
    }

    @Test
    public void legacyRecordsAreDistinguishable() {
        // The page shows imported records as limited rather than rendering blank columns that look like a bug.
        Assert.assertTrue(new AuditEvent(Instant.now(), AuditAction.LOGIN, AuditOutcome.UNKNOWN,
                "a@example.com", null, null, null, null, "m", AuditEvent.LEGACY_SCHEMA).isLegacy());
        Assert.assertFalse(at(Instant.now()).isLegacy());
    }
}
