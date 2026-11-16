package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One of a trip's pictures (issue 154) as the apps see it: position 0 of {@code TripDto.pictures} is the cover.
 * Every URL is absolute; {@code url} is the full 1920-pixel rendition, {@code url480} and {@code url960} the smaller
 * ones, all cut to 3:2. {@code width}/{@code height} are the full rendition's, {@code placeholder} the picture's
 * average color ({@code #rrggbb}) to paint while it loads, and {@code key} what the picture endpoints take to name it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripPictureDto(
        String key,
        String url,
        String url480,
        String url960,
        String alt,
        int width,
        int height,
        String placeholder) {
}
