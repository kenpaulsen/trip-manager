package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

/**
 * One person's progress against one todo.
 *
 * <p>Flattened deliberately: the model's {@code TodoStatus} is a wrapper over a {@code TodoItem} plus that
 * person's {@code PersonDataValue}, and putting that seam on the wire would make every client understand a
 * storage arrangement in order to read a checkbox and a note.
 *
 * <p>{@code visibility} decides who besides the subject may read {@code notes} -- see the resource. It is on the
 * wire because a client rendering the note needs to know whether it is private.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TodoStatusDto(
        String tripId,
        String dataId,
        String userId,
        String description,
        String moreDetails,
        String statusValue,
        String priority,
        String visibility,
        String notes,
        String owner,
        LocalDateTime lastUpdate,
        LocalDateTime created) {

    /** This status with the free-text note removed, for a caller who may see progress but not commentary. */
    public TodoStatusDto withoutNotes() {
        return new TodoStatusDto(tripId, dataId, userId, description, moreDetails, statusValue, priority,
                visibility, null, owner, lastUpdate, created);
    }
}
