package org.paulsens.trip.content;

import java.util.Comparator;
import java.util.List;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.site.ListingScope;

/**
 * The shared-site curation prompt every org-listing programmatic type (pilgrimages, photo albums)
 * declares: which organizations' content a SHARED site's section shows. One declaration and one options
 * provider so the two types can never drift. The org side of the double gate is shown, not hidden: an org
 * that opted out of shared sites stays pickable but labelled, so an editor sees why nothing appears.
 */
final class SharedSiteOrgChoices {

    static final String PROP_INCLUDE_ORGS = ListingScope.INCLUDE_ORGS_PROPERTY;

    private SharedSiteOrgChoices() {
    }

    static Placeholder property() {
        return new Placeholder(PROP_INCLUDE_ORGS, Placeholder.Type.MULTI_CHOICE,
                Text.get("server.types.sharedSites.orgsLabel"), Text.get("server.types.sharedSites.orgsHint"), false);
    }

    /** Every organization, name-sorted, opted-out ones labelled. */
    static List<ProgrammaticContentTemplate.Choice> choices() {
        return DAO.getInstance().getOrganizations(Cached.YES).stream()
                .filter(org -> !org.isPlatform())   // the product's own org has no trips or albums to share
                .sorted(Comparator.comparing(Organization::getName, String.CASE_INSENSITIVE_ORDER))
                .map(SharedSiteOrgChoices::choice)
                .toList();
    }

    private static ProgrammaticContentTemplate.Choice choice(final Organization org) {
        return new ProgrammaticContentTemplate.Choice(org.getId().getValue(), org.allowsSharedSites()
                ? org.getName() : Text.get("server.types.sharedSites.optedOut", org.getName()));
    }
}
