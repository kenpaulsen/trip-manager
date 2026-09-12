package org.paulsens.trip.api.dto;

import java.util.List;
import java.util.Map;

/**
 * How a party submit went: the registrations just filed, the already-filed ones whose answers changed, and
 * the travelers refused with why ({@code NOT_ALLOWED}, {@code ALREADY_REGISTERED}, {@code CANNOT_JOIN}).
 * A 200 with a non-empty {@code refused} is normal -- the party is filed traveler by traveler, and one
 * refusal never undoes another traveler's registration.
 */
public record RegisterPartyResponse(
        List<RegistrationDto> registered,
        List<RegistrationDto> updated,
        Map<String, String> refused) {
}
