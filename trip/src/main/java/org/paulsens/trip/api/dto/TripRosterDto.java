package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Who is on a trip, as names. The roster ids on {@code TripDto} stay ids; this is the one place they are
 * resolved, authorized by trip membership, and it carries only what a co-traveller may see of a person
 * (the PEER level of {@code PersonDto.redactedFor}): no email, no cell, no documents.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripRosterDto(
        String tripId,
        List<RosterEntryDto> people,
        List<String> directorIds,
        List<String> facilitatorIds,
        String localGuide) {

    public static final String ROLE_MEMBER = "MEMBER";
    public static final String ROLE_DIRECTOR = "DIRECTOR";
    public static final String ROLE_FACILITATOR = "FACILITATOR";

    /** One person on the roster: names, a photo when they have one, and the roles they hold on this trip. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RosterEntryDto(
            String id,
            String first,
            String last,
            String nickname,
            String preferredName,
            String photoUrl,
            List<String> roles) {
    }
}
