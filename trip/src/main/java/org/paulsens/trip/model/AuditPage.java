package org.paulsens.trip.model;

import java.time.LocalDate;
import java.util.List;
import lombok.Value;

/**
 * One page of audit history, plus enough context to page again honestly.
 *
 * <p>{@link #searchedBackTo} exists because the backwards walk is bounded. Without it, an empty page is
 * ambiguous in the worst way: "this person never did this" and "I gave up looking after two months" render
 * identically, and the first is a conclusion someone might act on. The page carries how far it actually looked
 * so the UI can say so.
 */
@Value
public class AuditPage {

    /** The matching events, newest first. */
    List<AuditEvent> events;

    /** The oldest day this search examined. Anything before it was not looked at. */
    LocalDate searchedBackTo;

    /**
     * True when the walk stopped because it had filled the page, rather than because it ran out of budget.
     * False means there may well be more history just beyond {@link #searchedBackTo}.
     */
    boolean complete;

    /** The cursor for the next page: ask for events strictly older than the oldest one here. */
    public java.time.Instant nextCursor() {
        return events.isEmpty() ? null : events.get(events.size() - 1).getTimestamp();
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }
}
