package org.paulsens.trip.api.dto;

import java.util.List;

/**
 * One person's itinerary as another traveler may see it: the events they are on, each with
 * {@code participating} and {@code privNote} absent, because neither is the trip's data
 * (docs/shared-itineraries.md).
 */
public record SharedItineraryDto(String personId, List<TripEventDto> events) {
}
