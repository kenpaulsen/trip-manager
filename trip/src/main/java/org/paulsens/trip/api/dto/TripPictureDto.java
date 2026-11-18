package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.function.IntFunction;
import org.paulsens.trip.model.TripPicture;

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

    /**
     * The picture's wire shape, its three URLs taken from {@code urlAt} (a rendition width to its address): the
     * full rendition, then the two smaller ones. The one place that says which width feeds which field, shared by
     * the signed-in trips API and Discover; each caller decides only how an address is spelled (absolute on its
     * request, or as the picture bean gives it).
     */
    public static TripPictureDto of(final TripPicture picture, final IntFunction<String> urlAt) {
        return new TripPictureDto(picture.getKey(), urlAt.apply(TripPicture.WIDTHS.getLast()),
                urlAt.apply(TripPicture.WIDTHS.get(0)), urlAt.apply(TripPicture.WIDTHS.get(1)), picture.getAlt(),
                picture.getWidth(), picture.getHeight(), picture.getPlaceholder());
    }
}
