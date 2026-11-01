package org.paulsens.trip.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A priced-checkout party (docs/checkout.md) for {@code POST .../registrations/party/quote|checkout|offline}: the
 * travelers, each with their answers (keyed by {@code regOptions[].id}: a choice id, comma-joined ids, text, Yes/No),
 * their discount code, the choice ids whose attestation they ticked, their daily-digest choice and their document
 * answers. {@code payerId} is whose payment it is (the caller when null; another person only for a registration
 * admin). {@code returnUrl} must be allowlisted like the payments API's; {@code offline} is for the offline endpoint.
 */
public record CheckoutRequest(String payerId, List<Traveler> travelers, Boolean sandbox, String returnUrl,
        Offline offline, String client) {

    /** One traveler of the party; every traveler named is treated as selected. */
    public record Traveler(String personId, Map<String, String> options, String code, List<String> attested,
            Boolean dailyDigest, List<RegisterPartyRequest.WaiverAnswer> waivers) {
    }

    /** How an offline payment was made: CHECK, CASH, COMP or OTHER; amount in cents (ignored for COMP). */
    public record Offline(String method, String reference, Long amountCents, LocalDate receivedOn, Boolean partial) {
    }
}
