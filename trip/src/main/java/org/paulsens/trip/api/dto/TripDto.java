package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A trip.
 *
 * <p>The roster ({@code people}) is ids only. Resolving them to names here would make every trip fetch a bulk
 * disclosure of the roster, redacted by nothing; a client that needs names asks {@code /api/people/{id}}, which
 * redacts per viewer.
 *
 * <p>{@code tripEvents} is absent unless the caller asked for the itinerary, so the common "list my trips" call
 * does not drag every event of every trip across the wire.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripDto(
        String id,
        String title,
        String description,
        Boolean openToPublic,
        boolean chatEnabled,
        LocalDateTime startDate,
        LocalDateTime endDate,
        Integer regLimit,
        String provider,
        String language,
        String estimatedPrice,
        String director,
        String localGuide,
        String facilitators,
        String nonHostedTripUrl,
        Integer nonHostedRegNumber,
        List<String> people,
        List<TripEventDto> tripEvents) {

    /** The same trip without its itinerary, for list responses. */
    public TripDto withoutEvents() {
        return new TripDto(id, title, description, openToPublic, chatEnabled, startDate, endDate, regLimit,
                provider, language, estimatedPrice, director, localGuide, facilitators, nonHostedTripUrl,
                nonHostedRegNumber, people, null);
    }
}
