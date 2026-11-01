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
 *
 * <p>{@code ticketCode} and {@code ticketUrl} are a bearer secret (whoever holds them opens the ticket page), so a
 * roster or search listing never carries them ({@link #withoutSecrets}); one registration read by its owner or by
 * check-in staff does. {@code name} and {@code registrationType} ride the check-in responses
 * ({@code docs/checkin.md}), where the app shows them without a second request.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegistrationDto(
        String tripId,
        String userId,
        LocalDateTime created,
        String status,
        Map<String, String> options,
        Boolean waiversSatisfied,
        String ticketCode,
        String ticketUrl,
        LocalDateTime checkedInAt,
        String checkedInBy,
        String registrationType,
        String name) {

    /** This registration with the free-text answers removed. */
    public RegistrationDto withoutOptions() {
        return new RegistrationDto(tripId, userId, created, status, null, waiversSatisfied, ticketCode, ticketUrl,
                checkedInAt, checkedInBy, registrationType, name);
    }

    /** A listing entry: no answers and no ticket. */
    public RegistrationDto withoutSecrets() {
        return new RegistrationDto(tripId, userId, created, status, null, waiversSatisfied, null, null,
                checkedInAt, checkedInBy, registrationType, name);
    }

    /**
     * This registration saying whether the traveler has agreed to every document the trip requires -- the
     * apps' status strip flags a registered traveler whose trip moved a document since (absent = not asked).
     */
    public RegistrationDto withWaiversSatisfied(final boolean satisfied) {
        return new RegistrationDto(tripId, userId, created, status, options, satisfied, ticketCode, ticketUrl,
                checkedInAt, checkedInBy, registrationType, name);
    }

    /** This registration with the check-in view's derived fields: the absolute ticket URL, the type and the name. */
    public RegistrationDto withCheckin(final String url, final String type, final String displayName) {
        return new RegistrationDto(tripId, userId, created, status, options, waiversSatisfied, ticketCode,
                ticketCode == null ? null : url, checkedInAt, checkedInBy, type, displayName);
    }
}
