package org.paulsens.trip.action;

import org.paulsens.trip.model.Organization;

/**
 * The REST write of an organization's "Shared sites" choice ({@code PUT /api/orgs/{orgId}/profile}), the same way
 * the Profile tab writes it: an uncached edit seed ({@code OrgCommands.freshOrg}), the model's one write of the
 * flag ({@link Organization#setSharedSitesAllowed}), and the bean's gated, audited save. Since medjugorje#155 that
 * flag is the organization's consent to Discover, so the two writers must seed and store it identically, and the
 * resource wraps this rather than reading the row itself.
 *
 * <p>A class of its own because {@code OrgCommands} is at the file-length cap (as {@link OrgEditAudit} is).
 */
public final class OrgSharedSites {

    /** How a write went: saved, no such organization to edit, or the store refused the save. */
    public enum Outcome { SAVED, NOT_FOUND, FAILED }

    private OrgSharedSites() {
    }

    /**
     * Sets the organization's Shared sites choice through {@code orgs}, whose caller must already be known to
     * manage the organization (the save re-checks it, and answers {@link Outcome#FAILED} when they do not).
     */
    public static Outcome save(final OrgCommands orgs, final String orgId, final boolean allowed) {
        final Organization fresh = orgs.freshOrg(orgId);
        if (fresh == null) {
            return Outcome.NOT_FOUND;
        }
        fresh.setSharedSitesAllowed(allowed);
        return orgs.saveOrganization(fresh) ? Outcome.SAVED : Outcome.FAILED;
    }
}
