package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One organization as the native app's "choose your organization" list shows it: name, abbreviation and
 * site only -- the public face every org already has, nothing about its people or its trips.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrgDirectoryEntryDto(String id, String name, String abbreviation, String slug, String siteUrl) {
}
