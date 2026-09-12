package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An organization as a client needs to see it: its identity, its site (when it has a subdomain), the
 * caller's standing in it, and its look. {@code siteUrl} is shaped for the host the request came in on,
 * so a client on the local container gets {@code http://acme.localhost:8080} and one in production gets
 * {@code https://acme.unitetrip.com} -- the same rule the admin pages' links follow.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrgSummaryDto(
        String id,
        String name,
        String abbreviation,
        String slug,
        String siteUrl,
        boolean isMember,
        boolean isAdmin,
        boolean allowsSharedSites,
        String contactEmail,
        OrgBrandingDto branding) {
}
