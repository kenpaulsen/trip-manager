package org.paulsens.trip.audit;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.paulsens.trip.model.AuditEvent;
import org.paulsens.trip.model.AuditOutcome;

/**
 * Where audit records are written. Implementations: {@link ConsoleAuditSink} (local runs and tests),
 * {@link CloudWatchAuditSink} (the immutable ledger), {@link DynamoAuditSink} (the queryable index), and
 * {@link CompositeAuditSink}, which fans out to several at once. Chosen once at startup by {@link Audit}.
 *
 * <p>Implementations MUST NOT throw and MUST NOT block the calling thread for any meaningful time -- every
 * caller is a request thread in the middle of a login, an email send, or a credential change. Losing the
 * record is bad; failing the user's request because the audit backend hiccupped is worse.
 *
 * <p>Sinks receive the whole {@link AuditEvent} rather than a pre-formatted line, because they no longer agree
 * on a representation: CloudWatch wants one line of text, DynamoDB wants fields it can filter on. Rendering is
 * each sink's own business.
 */
public interface AuditSink {

    /**
     * Records one event. Called from request threads, possibly concurrently.
     *
     * @param event the event to record; never null.
     */
    void write(AuditEvent event);

    /** Flushes anything buffered. Called on shutdown; may block briefly. */
    default void close() { }

    /**
     * The canonical one-line rendering, used by the text sinks.
     *
     * <p>The first four fields keep the historical {@code ts | actor | type | message} layout on purpose: five
     * years of existing log files use it, the importer parses it, and anything already reading the trail keeps
     * working. The typed fields are appended after, and only when they carry something worth saying.
     */
    static String format(final AuditEvent event) {
        final StringBuilder line = new StringBuilder(160)
                .append(OffsetDateTime.ofInstant(event.getTimestamp(), AuditEvent.ZONE_ID)
                        .format(DateTimeFormatter.ISO_INSTANT))
                .append(" | ").append(nullToEmpty(event.getActorEmail()))
                .append(" | ").append(event.getAction())
                .append(" | ").append(nullToEmpty(event.getMessage()));
        if (event.getOutcome() != AuditOutcome.UNKNOWN) {
            line.append(" | outcome=").append(event.getOutcome());
        }
        if (event.getTargetType() != null) {
            line.append(" | target=").append(event.getTargetType());
            if (event.getTargetEmail() != null) {
                line.append(':').append(event.getTargetEmail());
            } else if (event.getTargetId() != null) {
                line.append(':').append(event.getTargetId());
            }
        }
        return line.toString();
    }

    private static String nullToEmpty(final String value) {
        return (value == null) ? "" : value;
    }
}
