package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One picture of a trip: the cover (position 0 of {@link Trip#getPictures()}) or a gallery picture. The list on the
 * trip IS the inventory, the {@link BadgeImage} precedent: there are no media-table rows, and nothing is derived from
 * an S3 listing, so deleting the entry (plus its objects) is the whole delete.
 *
 * <p>Every picture is stored as {@link #WIDTHS three JPEG widths} under one stem ({@link #key}), so a page can offer
 * a {@code srcset} and an app can pick the size it needs. {@link #width} and {@link #height} are the largest
 * rendition's, which a page writes as the image's {@code width}/{@code height} attributes so nothing shifts when it
 * arrives. {@link #placeholder} is the picture's average color ({@code #rrggbb}), painted behind the image until it
 * loads.
 *
 * <p>A mutable no-invariant POJO rather than a record for the reason {@link BadgeImage} gives: the session codec
 * (Kryo) rebuilds classes without running a constructor.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class TripPicture implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The widths every picture is stored at, smallest first; the last is the full one. */
    public static final List<Integer> WIDTHS = List.of(480, 960, 1920);

    /** The stem every rendition's key starts with, e.g. {@code tripPictures/{tripId}/{version}}. */
    @JsonProperty("key")
    private String key;

    /** What a screen reader says for the picture; blank means the trip's title is used. */
    @JsonProperty("alt")
    private String alt;

    /** The largest rendition's width in pixels. */
    @JsonProperty("width")
    private int width;

    /** The largest rendition's height in pixels. */
    @JsonProperty("height")
    private int height;

    /** The picture's average color as {@code #rrggbb}, painted before the image arrives. */
    @JsonProperty("placeholder")
    private String placeholder;

    /** The object key of one rendition: {@code {stem}-{width}.jpg}. */
    public static String variantKey(final String stem, final int width) {
        return stem + "-" + width + ".jpg";
    }

    /** This picture's rendition at {@code width}, which should be one of {@link #WIDTHS}. */
    @JsonIgnore
    public String keyAt(final int width) {
        return variantKey(key, width);
    }

    /** Every rendition's object key, smallest first. */
    @JsonIgnore
    public List<String> getKeys() {
        return WIDTHS.stream().map(this::keyAt).toList();
    }
}
