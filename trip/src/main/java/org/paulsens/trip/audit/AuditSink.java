package org.paulsens.trip.audit;

/**
 * Where audit records are written. Two implementations: {@link ConsoleAuditSink} (local runs and tests) and
 * {@link CloudWatchAuditSink} (deployed). Chosen once at startup by {@link Audit}.
 *
 * <p>Implementations MUST NOT throw and MUST NOT block the calling thread for any meaningful time -- every
 * caller is a request thread in the middle of a login, an email send, or a credential change. Losing the
 * record is bad; failing the user's request because the audit backend hiccupped is worse.
 */
public interface AuditSink {

    /**
     * Records one already-formatted audit line. Called from request threads, possibly concurrently.
     *
     * @param epochMillis when the event happened (the sink may need this separately from the text).
     * @param line        the formatted record.
     */
    void write(long epochMillis, String line);

    /** Flushes anything buffered. Called on shutdown; may block briefly. */
    default void close() { }
}
