package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * One item on a trip's itinerary.
 *
 * <p>{@code privNote} is this caller's own note, singular. The model holds a {@code Map<Person.Id, String>} of
 * everybody's private notes, and putting that map on the wire would hand every traveller the private notes of
 * every other traveller -- which is the exact opposite of what "private" means on that field.
 *
 * <p>{@code details} is the structured flight/ground description ({@code from}, {@code to},
 * {@code flightNumber}, {@code duration}, {@code carrier}) when the event has one; absent for a free-text
 * event, so a client can tell "no structured details" from "empty details".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripEventDto(
        String id,
        String type,
        String title,
        String notes,
        LocalDateTime start,
        LocalDateTime end,
        List<String> participants,
        Boolean participating,
        String privNote,
        Map<String, String> details) {
}
