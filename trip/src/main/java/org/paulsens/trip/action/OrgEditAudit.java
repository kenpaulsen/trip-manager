package org.paulsens.trip.action;

import org.paulsens.trip.model.Organization;

/**
 * The audit message for an organization edit, with the one change that must be readable from the trail on its own
 * named in it: "Shared sites". Since medjugorje#155 that checkbox is the organization's consent to having its
 * public trips listed across organizations (on the shared sites and in the apps' Discover), and it has two
 * writers, the profile page and {@code PUT /api/orgs/{orgId}/profile}. A row that only said "edited" could not
 * answer "who published our trips, and when": a rename, a new contact address and both directions of this switch
 * would all read the same.
 *
 * <p>A class of its own because {@code OrgCommands}, the only caller, is at the file-length cap.
 */
final class OrgEditAudit {

    private OrgEditAudit() {
    }

    /**
     * "Organization 'X' edited", followed by which way Shared sites went when this save changed it. {@code stored}
     * is the row as it was before the save (null when it could not be read, which names no change).
     */
    static String message(final Organization stored, final Organization saved) {
        final String edited = "Organization '" + saved.getName() + "' edited";
        if (stored == null || stored.allowsSharedSites() == saved.allowsSharedSites()) {
            return edited;
        }
        return edited + (saved.allowsSharedSites()
                ? "; Shared sites turned on (its public trips may be listed on shared sites and in Discover)"
                : "; Shared sites turned off (its public trips leave the shared sites and Discover)");
    }
}
