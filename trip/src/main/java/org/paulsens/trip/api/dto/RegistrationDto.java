package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Somebody's registration on a trip.
 *
 * <p>{@code options} carries the answers to the trip's registration questions, which is where anything sensitive
 * a traveller typed ends up -- dietary needs, medical notes, room preferences, and whatever else a given trip
 * chose to ask. It is therefore withheld from anyone but the traveller, their manager, and trip staff.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegistrationDto(
        String tripId,
        String userId,
        LocalDateTime created,
        String status,
        Map<String, String> options,
        Boolean waiversSatisfied) {

    /** This registration with the free-text answers removed. */
    public RegistrationDto withoutOptions() {
        return new RegistrationDto(tripId, userId, created, status, null, waiversSatisfied);
    }

    /**
     * This registration saying whether the traveler has agreed to every document the trip requires -- the
     * apps' status strip flags a registered traveler whose trip moved a document since (absent = not asked).
     */
    public RegistrationDto withWaiversSatisfied(final boolean satisfied) {
        return new RegistrationDto(tripId, userId, created, status, options, satisfied);
    }
}
