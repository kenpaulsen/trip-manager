package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditEvent;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.AuditPage;
import org.paulsens.trip.model.AuditQuery;

/**
 * Reads the audit trail for the admin page, as {@code #{auditView}}.
 *
 * <p><b>Cursor paging, not offsets.</b> Records are still being written while an admin reads, so an
 * offset-based "page 2" would skip or repeat records as new events shift everything down. Each page instead
 * asks for events strictly older than the last one shown, which is stable no matter what arrives meanwhile.
 *
 * <p><b>Never a full load.</b> The table grows without bound; the whole point of the day-partition design is
 * that a page reads a bounded slice. The trail is currently ~36,000 records and will only get longer.
 */
@Slf4j
@Named("auditView")
@ApplicationScoped
public class AuditViewCommands {

    /** Filters and cursors are per-user, so the bean is stateless and the page holds the state in viewScope. */
    public AuditQuery.AuditQueryBuilder query() {
        return AuditQuery.builder();
    }

    /**
     * One page of history, newest first.
     *
     * @param before  cursor: return events strictly older than this. Null starts at now.
     * @param actor   substring of an actor or target email (or an exact id); blank matches everything.
     * @param action  action name as a string (the page's dropdown is a string); blank matches everything.
     * @param outcome outcome name as a string; blank matches everything.
     * @param text    substring of the message; blank matches everything.
     * @param limit   page size.
     */
    public AuditPage getPage(final Instant before, final String actor, final String action, final String outcome,
            final String text, final int limit) {
        final AuditQuery query = AuditQuery.builder()
                .before(before)
                .actor(blankToNull(actor))
                // Parsed leniently: these come from EL, where an unset dropdown is "" rather than null, and a
                // stale bookmarked value must not throw on a page render.
                .action(parseAction(action))
                .outcome(parseOutcome(outcome))
                .text(blankToNull(text))
                .limit(limit <= 0 ? AuditQuery.DEFAULT_LIMIT : limit)
                .build();
        try {
            return DAO.getInstance().getAuditEvents(query).join();
        } catch (final RuntimeException ex) {
            // The audit page failing is annoying; the audit page throwing a 500 during an incident is worse.
            log.error("Unable to read the audit trail", ex);
            return new AuditPage(List.of(), LocalDate.now(ZoneOffset.UTC), false);
        }
    }

    /** Convenience for the initial render: the newest page with no filters. */
    public AuditPage getRecent(final int limit) {
        return getPage(null, null, null, null, null, limit);
    }

    /** Every action, for the filter dropdown. */
    public List<AuditAction> getActions() {
        final List<AuditAction> actions = new ArrayList<>(List.of(AuditAction.values()));
        actions.sort(java.util.Comparator.comparing(Enum::name));
        return actions;
    }

    /** Every outcome, for the filter dropdown. */
    public List<AuditOutcome> getOutcomes() {
        return List.of(AuditOutcome.values());
    }

    /** Renders a record's timestamp in the viewer's terms; the stored value is UTC. */
    public LocalDateTime localTime(final AuditEvent event) {
        return (event == null) ? null : LocalDateTime.ofInstant(event.getTimestamp(), ZoneOffset.UTC);
    }

    /**
     * A CSV of the current filter's window.
     *
     * <p>Bounded by the same day budget as a page, so an export cannot turn into an unbounded table walk. The
     * header says so, because a truncated export that looks complete is worse than no export.
     */
    public String toCsv(final Instant before, final String actor, final String action, final String outcome,
            final String text) {
        final AuditQuery query = AuditQuery.builder()
                .before(before)
                .actor(blankToNull(actor))
                .action(parseAction(action))
                .outcome(parseOutcome(outcome))
                .text(blankToNull(text))
                .build();
        final List<AuditEvent> events;
        try {
            events = DAO.getInstance().exportAuditEvents(query).join();
        } catch (final RuntimeException ex) {
            log.error("Unable to export the audit trail", ex);
            return "error\nCould not read the audit trail; see the application log.\n";
        }
        final StringBuilder csv = new StringBuilder(events.size() * 120)
                .append("timestamp,action,outcome,actorEmail,actorId,targetType,targetEmail,targetId,message\n");
        for (final AuditEvent event : events) {
            csv.append(event.getTimestamp()).append(',')
                    .append(event.getAction()).append(',')
                    .append(event.getOutcome()).append(',')
                    .append(escape(event.getActorEmail())).append(',')
                    .append(escape(event.getActorId())).append(',')
                    .append(escape(event.getTargetType())).append(',')
                    .append(escape(event.getTargetEmail())).append(',')
                    .append(escape(event.getTargetId())).append(',')
                    .append(escape(event.getMessage())).append('\n');
        }
        return csv.toString();
    }

    /**
     * CSV escaping.
     *
     * <p>Quotes anything containing a comma, quote or newline -- and newlines are not hypothetical here: the
     * imported PayPal records contain them, which is why the original log files had continuation lines at all.
     */
    static String escape(final String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0
                && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    static AuditAction parseAction(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        final AuditAction action = AuditAction.from(value);
        // from() maps anything unrecognised to UNKNOWN, which as a FILTER would mean "show only unmapped
        // records" -- the opposite of the "no filter" the user intended by picking a stale value.
        return (action == AuditAction.UNKNOWN && !"UNKNOWN".equalsIgnoreCase(value.trim())) ? null : action;
    }

    static AuditOutcome parseOutcome(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        final AuditOutcome outcome = AuditOutcome.from(value);
        return (outcome == AuditOutcome.UNKNOWN && !"UNKNOWN".equalsIgnoreCase(value.trim())) ? null : outcome;
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
