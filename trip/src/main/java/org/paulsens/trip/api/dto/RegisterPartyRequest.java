package org.paulsens.trip.api.dto;

import java.util.List;
import java.util.Map;

/**
 * A party submit: the travelers to register on a trip, each with their answers to the trip's registration
 * questions (keyed by {@code regOptions[].id}) and, while the trip's chat is on, their daily-digest choice
 * (absent means the form's default: on). Every traveler named is treated as selected.
 */
public record RegisterPartyRequest(List<TravelerRequest> travelers, String client) {

    /** A submit that does not say which app sent it. */
    public RegisterPartyRequest(final List<TravelerRequest> travelers) {
        this(travelers, null);
    }

    /**
     * One traveler. {@code waivers} carries their answers to the trip's required documents (see
     * {@code docs/waivers.md}); a traveler with an unsigned document is refused {@code WAIVERS_REQUIRED}.
     */
    public record TravelerRequest(String personId, Map<String, String> options, Boolean dailyDigest,
            List<WaiverAnswer> waivers) {

        public TravelerRequest(final String personId, final Map<String, String> options, final Boolean dailyDigest) {
            this(personId, options, dailyDigest, null);
        }
    }

    /** "I agree" and "I may sign for them", for one document version. */
    public record WaiverAnswer(String waiverId, int version, boolean agreed, boolean authorityAttested) {
    }
}
