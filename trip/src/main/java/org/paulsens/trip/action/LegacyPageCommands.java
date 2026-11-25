package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.web.LegacyPages;

/**
 * The shared site's menu links to its hand-written pages, as {@code #{legacyPages}}: where an entry's page now
 * lives as another organization's Editable Page, the menu links straight there rather than through the redirect
 * {@code LegacyPageFilter} answers at the old address (docs/org-admin.md, "Hand-written shared pages").
 */
@Named("legacyPages")
@ApplicationScoped
public class LegacyPageCommands {

    /** The menu's link for the shared entry {@code path}: the link-out Editable Page, else {@code path} unchanged. */
    public String href(final String path) {
        return LegacyPages.installed().href(path, SiteContext.current(), SiteCommands.facesRequest());
    }
}
