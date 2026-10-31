package org.paulsens.trip.media;

import java.util.Locale;
import java.util.Optional;

/**
 * One 90-degree turn of a stored image, the only rotation anyone can ask for: a sideways photo is always a
 * quarter turn away from upright, and two taps make the half turn. Expressed as the EXIF orientation that
 * means the same turn so {@link PhotoProcessor#applyOrientation} -- the geometry every upload already trusts
 * -- does the pixel work, rather than a second hand-written transform that could disagree with it.
 */
public enum QuarterTurn {

    /** Counter-clockwise: EXIF 8 ("rotate 270 CW to display"). */
    LEFT(8),
    /** Clockwise: EXIF 6 ("rotate 90 CW to display"). */
    RIGHT(6);

    private final int exifOrientation;

    QuarterTurn(final int exifOrientation) {
        this.exifOrientation = exifOrientation;
    }

    /** The EXIF orientation whose correction is this turn. */
    public int exifOrientation() {
        return exifOrientation;
    }

    /** {@code "left"} or {@code "right"} in any case, surrounding space ignored; anything else is empty. */
    public static Optional<QuarterTurn> parse(final String direction) {
        if (direction == null) {
            return Optional.empty();
        }
        return switch (direction.trim().toLowerCase(Locale.ROOT)) {
            case "left" -> Optional.of(LEFT);
            case "right" -> Optional.of(RIGHT);
            default -> Optional.empty();
        };
    }
}
