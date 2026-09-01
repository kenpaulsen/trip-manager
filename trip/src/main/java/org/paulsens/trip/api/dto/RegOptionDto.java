package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One registration-page question on a trip ({@code RegistrationOption}). {@code id} is the option's stable
 * key ({@code Registration.options} is keyed by its string form); a null id on write means "next index".
 * {@code show} null means visible, matching the model's default.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegOptionDto(Integer id, String shortDesc, String longDesc, Boolean show) {
}
