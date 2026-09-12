package org.paulsens.trip.api.dto;

import java.util.List;
import java.util.Map;

/**
 * A party submit: the travelers to register on a trip, each with their answers to the trip's registration
 * questions (keyed by {@code regOptions[].id}) and, while the trip's chat is on, their daily-digest choice
 * (absent means the form's default: on). Every traveler named is treated as selected.
 */
public record RegisterPartyRequest(List<TravelerRequest> travelers) {

    public record TravelerRequest(String personId, Map<String, String> options, Boolean dailyDigest) {
    }
}
