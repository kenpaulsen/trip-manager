package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One registration-page question on a trip ({@code RegistrationOption}). {@code id} is the option's stable
 * key ({@code Registration.options} is keyed by its string form); a null id on write means "next index".
 * {@code show} null means visible, matching the model's default.
 *
 * <p>Typed questions (2026-11): {@code type} is {@code TEXT} (the default, and what every older question reads
 * as), {@code CHOICE}, {@code MULTI_CHOICE} or {@code YES_NO}; {@code choices} lists what a choice question
 * offers; {@code required} null is optional. The trip's age bands adjust the admission question's prices only
 * (the first shown, required, priced pick-one question). See {@code docs/checkout.md}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegOptionDto(Integer id, String shortDesc, String longDesc, Boolean show, String type,
        Boolean required, List<RegChoiceDto> choices) {

    /** A free-text question: the shape before typed questions existed. */
    public RegOptionDto(final Integer id, final String shortDesc, final String longDesc, final Boolean show) {
        this(id, shortDesc, longDesc, show, null, null, null);
    }
}
