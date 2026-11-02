package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Who is on a trip, as names. The roster ids on {@code TripDto} stay ids; this is the one place they are
 * resolved, authorized by trip membership, and it carries only what THIS viewer may see of each person: the
 * contact fields each person shares (the web contacts page's {@code PrivacyView} rule, so an admin also gets the
 * withheld ones, named in {@code masked}), never a document, and how much of their itinerary the viewer may
 * open ({@code ItineraryAccess}: {@code FULL}, {@code SHARED}, or absent).
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
            List<String> roles,
            String itinerary,
            RosterContactDto contact) {
    }

    /**
     * What the viewer may see of how to reach one person. Absent altogether when there is nothing to show, so a
     * client offers no "contact" affordance for them. A field in {@code masked} is one the person keeps private
     * that an admin may still reveal: the client shows it behind a tap, as the website's mask does.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RosterContactDto(
            String email,
            String cell,
            String street,
            String city,
            String state,
            String zip,
            List<String> masked) {
    }
}
