package org.paulsens.trip.model;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import lombok.Value;

/**
 * One page of audit history, plus enough context to page again honestly.
 *
 * <p><b>Serializable is load-bearing, not decoration.</b> The admin page holds this in {@code viewScope},
 * viewScope lives in the HTTP session, and the session is serialized into Valkey by Redisson on every request.
 * A non-serializable object here does not fail the page that created it -- it fails the SESSION SAVE, so every
 * subsequent request on that session gets a 500 no matter which page it asks for. Shipping this class without
 * it took the whole site down for anyone who opened the audit page.
 *
 * <p>{@link #searchedBackTo} exists because the backwards walk is bounded. Without it, an empty page is
 * ambiguous in the worst way: "this person never did this" and "I gave up looking after two months" render
 * identically, and the first is a conclusion someone might act on. The page carries how far it actually looked
 * so the UI can say so.
 */
@Value
public class AuditPage implements Serializable {

    /** The matching events, newest first. */
    List<AuditEvent> events;

    /** The oldest day this search examined. Anything before it was not looked at. */
    LocalDate searchedBackTo;

    /**
     * True when the walk stopped because it had filled the page, rather than because it ran out of budget.
     * False means there may well be more history just beyond {@link #searchedBackTo}.
     */
    boolean complete;

    /**
     * How many day partitions could not be read.
     *
     * <p>This exists because of how the audit page first shipped: every query was failing on a reserved-word
     * error, each failure was caught and turned into an empty day, and the page cheerfully reported "no
     * records" over a table holding 36,000. "Nothing matched" and "nothing could be read" have to look
     * different, or a total outage presents as an ordinary empty result.
     */
    int failedPartitions;

    /** Convenience for the common case: nothing failed to read. */
    public AuditPage(final List<AuditEvent> events, final LocalDate searchedBackTo, final boolean complete) {
        this(events, searchedBackTo, complete, 0);
    }

    // Written out rather than left to Lombok: declaring the convenience constructor above suppresses the
    // generated all-args one.
    public AuditPage(final List<AuditEvent> events, final LocalDate searchedBackTo, final boolean complete,
            final int failedPartitions) {
        this.events = events;
        this.searchedBackTo = searchedBackTo;
        this.complete = complete;
        this.failedPartitions = failedPartitions;
    }

    /** True when any part of the window could not be read, so the result is not to be trusted as complete. */
    public boolean isDegraded() {
        return failedPartitions > 0;
    }

    /** The cursor for the next page: ask for events strictly older than the oldest one here. */
    public java.time.Instant nextCursor() {
        return events.isEmpty() ? null : events.get(events.size() - 1).getTimestamp();
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }
}
