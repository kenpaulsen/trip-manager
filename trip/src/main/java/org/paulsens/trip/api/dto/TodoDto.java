package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

/** A todo item defined on a trip -- the task itself, not anybody's progress against it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TodoDto(
        String tripId,
        String dataId,
        String description,
        String moreDetails,
        LocalDateTime created) {
}
