package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.paulsens.trip.action.BrandCommands;

/**
 * An organization's stored look, host-independent: what {@code BrandCommands} renders on the org's own
 * site, handed to a client that builds its own chrome. Every URL was screened the way the page screens it;
 * a value the org never chose is absent, so the client draws its neutral default rather than a blank.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrgBrandingDto(
        String palette,
        boolean dark,
        String layout,
        String themeName,
        String logoUrl,
        String faviconUrl,
        String ogImageUrl,
        String backgroundUrl,
        String backgroundColor,
        String footerTitle,
        String footerText,
        String contactName,
        String contactPhone,
        String donateUrl) {

    public static OrgBrandingDto of(final BrandCommands.OrgLook look, final String themeName) {
        return new OrgBrandingDto(look.palette(), look.dark(), look.layout(), themeName, look.logoUrl(),
                look.faviconUrl(), look.ogImageUrl(), look.backgroundUrl(), look.backgroundColor(),
                look.footerTitle(), look.footerText(), look.contactName(), look.contactPhone(), look.donateUrl());
    }
}
