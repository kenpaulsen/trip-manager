package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One value a Discover filter can take and how many listed trips carry it: {@code value} is what the query
 * parameter takes, {@code label} what a person reads, and {@code count} the trips that would remain if this value
 * were chosen with every OTHER filter in the request still applied. A value with no trips is never sent, so a
 * client cannot offer a choice that returns nothing. {@code logoUrl} is set on organizations only, and only for a
 * logo the organization uploaded here.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiscoverFacetDto(String value, String label, int count, String logoUrl) {
}
