package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How a conference's public page looks ({@code docs/conference.md}, "Look and feel"): the trip's own options
 * over its organization's Appearance settings. Every field is nullable and null means "the organization's" (or
 * the page's default); on a write an absent field is left alone and an empty string clears one back to that.
 * {@code layout} is {@code classic} or {@code full-width}. Read back by the trip's managers only, like the payment
 * settings.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripPageOptionsDto(
        String layout,
        String backgroundUrl,
        String backgroundColor,
        Boolean showTitle,
        Boolean showNav,
        String stylesheetUrl) {
}
