package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditEvent;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.AuditPage;
import org.paulsens.trip.model.AuditQuery;
import org.paulsens.trip.model.AuditScope;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.site.SiteContext;

/**
 * Reads the audit trail for the admin page, as {@code #{auditView}}.
 *
 * <p><b>Cursor paging, not offsets.</b> Records are still being written while an admin reads, so an
 * offset-based "page 2" would skip or repeat records as new events shift everything down. Each page instead
 * asks for events strictly older than the last one shown, which is stable no matter what arrives meanwhile.
 *
 * <p><b>Never a full load.</b> The table grows without bound; the whole point of the day-partition design is
 * that a page reads a bounded slice. The trail is currently ~36,000 records and will only get longer.
 *
 * <p><b>Whose trail.</b> Every read carries an {@link AuditScope} decided by {@link #scopeFor}: an
 * organization's own records on its site or its dashboard card, and on a shared host everything except the
 * hosted organizations' (their activity is not the shared site's business). Reading is gated by
 * {@link #canView}: site admins and global {@code auditAdmin} holders everywhere, and an organization's own
 * {@code auditAdmin@org} holders for that organization's trail only.
 */
@Slf4j
@Named("auditView")
@ApplicationScoped
public class AuditViewCommands {

    /** Test seam: the caller behind the current request (only {@link #canView} consults it). */
    @lombok.Setter
    private Supplier<Caller> callerSource = Caller::current;

    /** Filters and cursors are per-user, so the bean is stateless and the page holds the state in viewScope. */
    public AuditQuery.AuditQueryBuilder query() {
        return AuditQuery.builder();
    }

    /**
     * Whether the signed-in user may read the trail {@code orgId} names (null = this site's own view).
     * Site admins and global {@code auditAdmin} holders may read any; an organization's own
     * {@code auditAdmin@org} holders may read that organization's -- which on its own site IS the site's
     * view. On an organization's site no OTHER organization's trail is readable by anyone: that site
     * serves one tenant.
     */
    public boolean canView(final String orgId) {
        return canView(callerSource.get(), orgId);
    }

    /** {@link #canView(String)} for an explicit caller (the REST resource has its own). */
    public static boolean canView(final Caller caller, final String orgId) {
        if (caller == null || !caller.isAuthenticated()) {
            return false;
        }
        final SiteContext site = SiteContext.current();
        if (site.isOrg() && orgId != null && !orgId.isBlank() && !site.isSiteOf(orgId)) {
            return false;
        }
        if (caller.has(PrivilegeCommands.AUDIT_ADMIN)) {
            return true;
        }
        final String scopedTo = (orgId == null || orgId.isBlank()) && site.isOrg() ? site.orgId().getValue() : orgId;
        return scopedTo != null && !scopedTo.isBlank() && caller.has(PrivilegeCommands.AUDIT_ADMIN, scopedTo);
    }

    /**
     * The scope a read from this request gets: {@code orgId}'s own trail when one is named (the dashboard
     * card, a REST {@code org=}), else the current site's -- an org site's own organization, a shared host's
     * everything-but-the-hosted-orgs, and no boundary at all off a request (the system context).
     */
    public static AuditScope scopeFor(final String orgId) {
        if (orgId != null && !orgId.isBlank()) {
            return AuditScope.org(orgId);
        }
        final SiteContext site = SiteContext.current();
        if (site.isOrg()) {
            return AuditScope.org(site.orgId().getValue());
        }
        if (!site.isBound()) {
            return AuditScope.all();
        }
        return AuditScope.shared(hostedOrgIds());
    }

    /** The ids of every organization with a site of its own, whose records a shared host keeps off its trail. */
    static Set<String> hostedOrgIds() {
        return hostedOrgIds(() -> DAO.getInstance().getOrganizations(Cached.YES));
    }

    /** {@link #hostedOrgIds()} against an explicit org read (the testable half): a failed read is null. */
    static Set<String> hostedOrgIds(final Supplier<List<Organization>> orgs) {
        final Set<String> hosted = new HashSet<>();
        try {
            for (final Organization org : orgs.get()) {
                if (org.getSlug() != null && !org.getSlug().isBlank()) {
                    hosted.add(org.getId().getValue());
                }
            }
        } catch (final RuntimeException ex) {
            // Unreadable orgs must not widen the view: a shared host that cannot tell which orgs are hosted
            // shows site-level records only (null org) rather than everyone's.
            log.error("Unable to read the organizations for the audit scope; hiding every org's records", ex);
            return null;
        }
        return hosted;
    }

    /** The organization an event belongs to, by name, for the shared host's Org column; "" when none. */
    public String orgLabel(final AuditEvent event) {
        if (event == null || event.getOrgId() == null) {
            return "";
        }
        try {
            final Optional<Organization> org =
                    DAO.getInstance().getOrganization(Organization.Id.from(event.getOrgId()), Cached.YES);
            return org.map(AuditViewCommands::shortNameOf).orElse(event.getOrgId());
        } catch (final RuntimeException ex) {
            return event.getOrgId();
        }
    }

    /** An org's abbreviation, or its name when it has none: the column is narrow. */
    static String shortNameOf(final Organization org) {
        final String abbreviation = org.getAbbreviation();
        return abbreviation == null || abbreviation.isBlank() ? org.getName() : abbreviation;
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
        return getPage(null, before, actor, action, outcome, text, limit);
    }

    /** {@link #getPage(Instant, String, String, String, String, int)} scoped to {@code orgId}'s trail. */
    public AuditPage getPage(final String orgId, final Instant before, final String actor, final String action,
            final String outcome, final String text, final int limit) {
        final AuditQuery query = AuditQuery.builder()
                .scope(scopeFor(orgId))
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
            return DAO.getInstance().getAuditEvents(query, Cached.NO);
        } catch (final RuntimeException ex) {
            // The audit page failing is annoying; the audit page throwing a 500 during an incident is worse.
            log.error("Unable to read the audit trail", ex);
            return new AuditPage(List.of(), LocalDate.now(ZoneOffset.UTC), false);
        }
    }

    /** Convenience for the initial render: the newest page with no filters. */
    public AuditPage getRecent(final int limit) {
        return getPage(null, null, null, null, null, null, limit);
    }

    /** {@link #getRecent(int)} scoped to {@code orgId}'s trail. */
    public AuditPage getRecent(final String orgId, final int limit) {
        return getPage(orgId, null, null, null, null, null, limit);
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

    /** A record's timestamp as a UTC wall-clock value, for the server-rendered (no-JS) form of the cell. */
    public LocalDateTime utcTime(final AuditEvent event) {
        return (event == null) ? null : LocalDateTime.ofInstant(event.getTimestamp(), ZoneOffset.UTC);
    }

    /**
     * A record's timestamp as epoch milliseconds, carried on the cell as a data attribute so the browser --
     * the only party that knows the viewer's timezone -- can re-render it in local time.
     */
    public long epochMillis(final AuditEvent event) {
        return (event == null) ? 0L : event.getTimestamp().toEpochMilli();
    }

    /**
     * A CSV of the current filter's window.
     *
     * <p>Bounded by the same day budget as a page, so an export cannot turn into an unbounded table walk. The
     * header says so, because a truncated export that looks complete is worse than no export.
     */
    public String toCsv(final Instant before, final String actor, final String action, final String outcome,
            final String text) {
        return toCsv(null, before, actor, action, outcome, text);
    }

    /** {@link #toCsv(Instant, String, String, String, String)} scoped to {@code orgId}'s trail. */
    public String toCsv(final String orgId, final Instant before, final String actor, final String action,
            final String outcome, final String text) {
        final AuditQuery query = AuditQuery.builder()
                .scope(scopeFor(orgId))
                .before(before)
                .actor(blankToNull(actor))
                .action(parseAction(action))
                .outcome(parseOutcome(outcome))
                .text(blankToNull(text))
                .build();
        final List<AuditEvent> events;
        try {
            events = DAO.getInstance().exportAuditEvents(query, Cached.NO);
        } catch (final RuntimeException ex) {
            log.error("Unable to export the audit trail", ex);
            return "error\nCould not read the audit trail; see the application log.\n";
        }
        final StringBuilder csv = new StringBuilder(events.size() * 120)
                .append("timestamp,action,outcome,actorEmail,actorId,targetType,targetEmail,targetId,message,orgId\n");
        for (final AuditEvent event : events) {
            csv.append(event.getTimestamp()).append(',')
                    .append(event.getAction()).append(',')
                    .append(event.getOutcome()).append(',')
                    .append(escape(event.getActorEmail())).append(',')
                    .append(escape(event.getActorId())).append(',')
                    .append(escape(event.getTargetType())).append(',')
                    .append(escape(event.getTargetEmail())).append(',')
                    .append(escape(event.getTargetId())).append(',')
                    .append(escape(event.getMessage())).append(',')
                    .append(escape(event.getOrgId())).append('\n');
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
