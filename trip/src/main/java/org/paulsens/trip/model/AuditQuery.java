package org.paulsens.trip.model;

import java.time.Instant;
import java.util.Locale;
import lombok.Builder;
import lombok.Value;

/**
 * What the audit page is asking for: a filter plus a cursor.
 *
 * <p>With no secondary indexes, none of these filters can be an index lookup -- they are all evaluated while
 * walking day partitions backwards. That is a deliberate trade (see {@link AuditEvent}), and it means a filter
 * narrows what comes BACK, not how much is read. At this volume that is the right trade; it is also why the
 * walk is bounded rather than open-ended.
 *
 * <p>{@link #before} is the cursor: paging asks for events strictly older than the oldest one already shown.
 * A timestamp cursor rather than a page number, because rows are still being written while an admin reads --
 * offset paging would show the same record twice or skip one entirely as new events shift the offsets.
 */
@Value
@Builder(toBuilder = true)
public class AuditQuery {

    /** Default page size; also what the admin table requests. */
    public static final int DEFAULT_LIMIT = 50;

    /** Exclusive upper bound: return events strictly older than this. Null starts from now. */
    Instant before;
    /** Inclusive lower bound: stop looking once the walk reaches this. Null means "until the budget runs out". */
    Instant since;

    /** Substring match on either actor email or target email; null or blank matches everything. */
    String actor;
    /** Exact action match; null matches everything. */
    AuditAction action;
    /** Exact outcome match; null matches everything. */
    AuditOutcome outcome;
    /** Substring match on the message; null or blank matches everything. */
    String text;

    @Builder.Default
    int limit = DEFAULT_LIMIT;

    /** True when this event should appear in the results. */
    public boolean matches(final AuditEvent event) {
        if (event == null) {
            return false;
        }
        if (since != null && event.getTimestamp().isBefore(since)) {
            return false;
        }
        if (before != null && !event.getTimestamp().isBefore(before)) {
            return false;
        }
        if (action != null && event.getAction() != action) {
            return false;
        }
        if (outcome != null && event.getOutcome() != outcome) {
            return false;
        }
        // An actor filter deliberately matches the TARGET too: "show me everything about this person" is the
        // question an admin actually has, and it is answered wrongly by matching only who acted.
        if (isSet(actor)
                && !containsIgnoreCase(event.getActorEmail(), actor)
                && !containsIgnoreCase(event.getTargetEmail(), actor)
                && !equalsIgnoreCase(event.getActorId(), actor)
                && !equalsIgnoreCase(event.getTargetId(), actor)) {
            return false;
        }
        return !isSet(text) || containsIgnoreCase(event.getMessage(), text);
    }

    /** This query with a different page size; used by the exporter, which wants the whole window. */
    public AuditQuery withLimit(final int newLimit) {
        return toBuilder().limit(newLimit).build();
    }

    /** True when any filter is set, so the UI can say whether it is showing everything or a subset. */
    public boolean isFiltered() {
        return isSet(actor) || isSet(text) || action != null || outcome != null || since != null;
    }

    private static boolean isSet(final String value) {
        return value != null && !value.isBlank();
    }

    private static boolean containsIgnoreCase(final String haystack, final String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean equalsIgnoreCase(final String value, final String other) {
        return value != null && value.equalsIgnoreCase(other.trim());
    }
}
