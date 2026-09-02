package org.paulsens.trip.model;

import java.io.Serializable;
import java.util.Set;

/**
 * Which organizations' records an audit read may answer with -- the tenancy half of an {@link AuditQuery}.
 *
 * <ul>
 *   <li>{@link #org}: one organization's own trail (its site, or its dashboard card anywhere).</li>
 *   <li>{@link #shared}: a shared site's trail: site-level records (no org) and the sharing tenants' --
 *       everything EXCEPT the hosted organizations named, whose activity is theirs and not the shared
 *       site's business.</li>
 *   <li>{@link #all}: no boundary -- the system context (the recovery filter's dedupe read) and unit tests,
 *       where there is no host to draw one from.</li>
 * </ul>
 * Serializable because the query it belongs to may be parked in viewScope.
 */
public record AuditScope(String orgId, Set<String> hiddenOrgIds, boolean siteLevelOnly) implements Serializable {

    private static final AuditScope ALL = new AuditScope(null, Set.of(), false);
    private static final AuditScope SITE_LEVEL_ONLY = new AuditScope(null, Set.of(), true);

    public AuditScope {
        hiddenOrgIds = hiddenOrgIds == null ? Set.of() : Set.copyOf(hiddenOrgIds);
    }

    /** Everything, whichever organization it belongs to. */
    public static AuditScope all() {
        return ALL;
    }

    /** One organization's records only. */
    public static AuditScope org(final String orgId) {
        if (orgId == null || orgId.isBlank()) {
            throw new IllegalArgumentException("An org scope needs an organization id");
        }
        return new AuditScope(orgId, Set.of(), false);
    }

    /**
     * A shared site's records: all but those of the hosted organizations named. A null set means the
     * hosted organizations could NOT be determined, and the answer narrows to site-level records only --
     * an unreadable org list must never widen a shared host's view to every tenant's activity.
     */
    public static AuditScope shared(final Set<String> hostedOrgIds) {
        return hostedOrgIds == null ? SITE_LEVEL_ONLY : new AuditScope(null, hostedOrgIds, false);
    }

    /** True when this scope admits an event owned by {@code eventOrgId} (null = a site-level record). */
    public boolean admits(final String eventOrgId) {
        if (orgId != null) {
            return orgId.equals(eventOrgId);
        }
        if (eventOrgId == null) {
            return true;
        }
        return !siteLevelOnly && !hiddenOrgIds.contains(eventOrgId);
    }

    /** True when this is one organization's own view rather than a site's. */
    public boolean isOrg() {
        return orgId != null;
    }
}
