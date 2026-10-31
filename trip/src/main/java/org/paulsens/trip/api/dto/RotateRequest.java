package org.paulsens.trip.api.dto;

import java.util.Optional;
import org.paulsens.trip.media.QuarterTurn;

/** The body of every rotate endpoint: {@code {"direction": "left" | "right"}}. */
public record RotateRequest(String direction) {

    /** The parsed turn; empty for a missing or unknown direction (the caller answers 400). */
    public Optional<QuarterTurn> turn() {
        return QuarterTurn.parse(direction);
    }
}
