package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One page of Discover: {@code items} in the requested order, {@code total} the number of trips matching the
 * search and filters (all pages), {@code nextCursor} what to send back as {@code cursor} for the next page (absent
 * on the last), and {@code facets} the values each filter can still take. Grouped views are a client grouping a
 * sorted list; the server sends none.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiscoverListDto(List<DiscoverTripDto> items, int total, String nextCursor, Facets facets) {

    /**
     * The filters' remaining values, each list counted with every other active filter applied. {@code months} are
     * departure months ({@code yyyy-MM}), oldest first; the rest are ordered by count, then label.
     */
    public record Facets(
            List<DiscoverFacetDto> countries,
            List<DiscoverFacetDto> tags,
            List<DiscoverFacetDto> languages,
            List<DiscoverFacetDto> kinds,
            List<DiscoverFacetDto> organizations,
            List<DiscoverFacetDto> months) {
    }
}
