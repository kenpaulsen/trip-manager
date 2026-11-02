package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * A trip's OWN payment settings (the trip rung of the ladder trip &rarr; organization defaults &rarr; site
 * settings; {@code docs/payments.md}). Every field is nullable and null means "inherit"; on a write, an
 * absent field is left alone and an empty string clears the override. {@code feesPaidBy} is
 * {@code ORGANIZATION} or {@code PAYER}. Read back only by the trip's managers: the mail settings are the
 * organization's business, not the roster's.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripPaymentConfigDto(
        String processorConfigId,
        String feesPaidBy,
        Boolean donationEnabled,
        String donationLabel,
        String confirmationTemplateId,
        String mailFrom,
        String replyTo,
        String bcc,
        Map<String, String> extraTokens) {
}
