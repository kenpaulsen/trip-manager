package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.Value;

/**
 * One audit record: who did what, to whom, and whether it worked.
 *
 * <p><b>Keys.</b> {@link #getPartition()} is the UTC day ({@code yyyy-MM-dd}) the event happened on, and
 * {@link #getSortKey()} is the zero-padded epoch millisecond. That pair is the whole query design, and it is
 * worth being explicit about why, because there are deliberately <b>no secondary indexes</b>:
 *
 * <ul>
 *   <li>A DynamoDB {@code Scan} returns items in no defined order, so it cannot produce "newest first" at all.
 *       Sort-key order only exists WITHIN a partition, so newest-first paging means querying a day backwards and
 *       then walking to the previous day. Day-level partitions are chosen over coarser ones so the design keeps
 *       working as volume grows, rather than concentrating an ever-larger history behind one key.</li>
 *   <li>The millis are zero-padded to 13 digits because DynamoDB compares sort keys bytewise: unpadded,
 *       {@code "999"} would sort AFTER {@code "1000000000000"} and the ordering would silently be wrong.</li>
 * </ul>
 *
 * <p><b>Ties.</b> The millisecond alone is the key, so two events in the same millisecond want the same row --
 * and an unconditional {@code PutItem} on an existing key does not fail, it OVERWRITES, which for an audit
 * table means destroying a record. Measured over five years of real history this is rare but real: 199
 * milliseconds carry more than one record, 302 colliding pairs in 36,000. The resolution is
 * {@link #withNextMilli()}: the writer puts conditionally, and a rejected write is retried one millisecond
 * later. The stored time is then off by a millisecond or two, which is a deliberate trade for a shorter key --
 * and because the events are written in the order they happened, nudging the loser forward preserves that
 * order rather than scrambling it the way a random tie-breaker would.
 *
 * <p><b>Actor and target.</b> Both carry an email and an id. Emails are what a human searches by; ids are what
 * stay correct. The app lets a person change their login address, so an email-only trail quietly rewrites its
 * own history -- past actions appear to belong to an address that no longer exists, or worse, to whoever holds
 * it now. Storing both means the record survives the rename.
 *
 * <p><b>schemaVersion.</b> Records imported from the old {@code trip-audit.log} files are version 1 and
 * genuinely lack an outcome and a target -- that information was never written down. The version lets the UI
 * present them as limited rather than rendering empty columns that look like a defect.
 */
@Value
public class AuditEvent implements Serializable {

    /** Current shape. 1 == imported legacy text; 2 == written by the typed API. */
    public static final int CURRENT_SCHEMA = 2;
    public static final int LEGACY_SCHEMA = 1;

    public static final ZoneId ZONE_ID = ZoneId.of("UTC");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZONE_ID);

    /** When it happened. The partition and sort keys are both derived from this -- it IS the row's identity. */
    Instant timestamp;

    AuditAction action;
    AuditOutcome outcome;

    /** Who performed the action. Email for searching, id for correctness; either may be null for old records. */
    String actorEmail;
    String actorId;

    /** What was acted upon: "person", "trip", "media", "config"... null when the action has no distinct target. */
    String targetType;
    /** Set when the target is a person, so the trail reads naturally without a lookup. */
    String targetEmail;
    String targetId;

    /** Human-readable detail. For legacy records this is the entire original message. */
    String message;

    int schemaVersion;

    @JsonCreator
    public AuditEvent(
            @JsonProperty("timestamp") final Instant timestamp,
            @JsonProperty("action") final AuditAction action,
            @JsonProperty("outcome") final AuditOutcome outcome,
            @JsonProperty("actorEmail") final String actorEmail,
            @JsonProperty("actorId") final String actorId,
            @JsonProperty("targetType") final String targetType,
            @JsonProperty("targetEmail") final String targetEmail,
            @JsonProperty("targetId") final String targetId,
            @JsonProperty("message") final String message,
            @JsonProperty("schemaVersion") final Integer schemaVersion) {
        // Truncated to millisecond precision on the way in, so the stored time and the sort key can never
        // disagree: a nanosecond-precision Instant would render one thing and key another.
        this.timestamp = (timestamp == null) ? Instant.now() : timestamp.truncatedTo(ChronoUnit.MILLIS);
        // Null-tolerant on read: a row hand-edited in the console, or written by an older build, must still
        // load. An audit record that cannot be deserialized is an audit record that has been lost.
        this.action = (action == null) ? AuditAction.UNKNOWN : action;
        this.outcome = (outcome == null) ? AuditOutcome.UNKNOWN : outcome;
        this.actorEmail = actorEmail;
        this.actorId = actorId;
        this.targetType = targetType;
        this.targetEmail = targetEmail;
        this.targetId = targetId;
        this.message = message;
        this.schemaVersion = (schemaVersion == null) ? CURRENT_SCHEMA : schemaVersion;
    }

    /** Partition key: the UTC day ({@code yyyy-MM-dd}) this event belongs to. */
    @JsonIgnore
    public String getPartition() {
        return DAY.format(timestamp);
    }

    /**
     * Sort key: the zero-padded epoch millisecond.
     *
     * <p>Padded to 13 digits so the key sorts lexicographically the same way it sorts numerically -- DynamoDB
     * compares strings byte by byte, so an unpadded {@code 999} would sort after {@code 1000000000000}.
     */
    @JsonIgnore
    public String getSortKey() {
        return sortKeyFor(timestamp);
    }

    /** The day bucket for an arbitrary instant, so callers can build a query without an event in hand. */
    public static String partitionFor(final Instant when) {
        return DAY.format(when);
    }

    /** The sort key for an arbitrary instant, for the bounds of a range query. */
    public static String sortKeyFor(final Instant when) {
        return String.format("%013d", when.toEpochMilli());
    }

    /**
     * This event nudged one millisecond later, for the writer's collision retry.
     *
     * <p>Yes, the stored time is then slightly wrong. That is the accepted cost of a key with no tie-breaker in
     * it, and it is the benign direction to be wrong in: events are written in the order they occur, so pushing
     * the second one forward preserves their true order. A random tie-breaker would have kept the timestamp
     * exact while making the ordering within that millisecond arbitrary.
     */
    public AuditEvent withNextMilli() {
        return new AuditEvent(timestamp.plusMillis(1), action, outcome, actorEmail, actorId,
                targetType, targetEmail, targetId, message, schemaVersion);
    }

    /** True when this came from the imported log files and therefore has no reliable outcome or target. */
    @JsonIgnore
    public boolean isLegacy() {
        return schemaVersion <= LEGACY_SCHEMA;
    }
}
