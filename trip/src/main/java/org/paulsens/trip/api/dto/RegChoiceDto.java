package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One choice of a CHOICE or MULTI_CHOICE registration question ({@code OptionChoice}). A registrant's answer stores
 * {@code id}; {@code priceCents} is what the choice costs on a trip with priced checkout (null or absent is free);
 * {@code capacity} null is unlimited; a non-blank {@code attestationText} must be ticked to pick the choice;
 * {@code days} are the ISO dates it covers; {@code enabled} null means offered. A null {@code id} on a write mints
 * one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegChoiceDto(String id, String label, Long priceCents, Integer capacity, String attestationText,
        List<String> days, Boolean enabled) {
}
