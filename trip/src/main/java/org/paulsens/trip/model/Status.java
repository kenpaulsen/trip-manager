package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.Setter;

@JsonDeserialize(builder = Status.StatusBuilder.class)
@Data
@Builder
public class Status implements Serializable {
    @Builder.Default
    @NonNull
    @Setter(AccessLevel.NONE)
    private Status.StatusValue value = StatusValue.TODO;
    private String notes;
// FIXME: Make this happpen...
    // Only the Status owner, a manager of the Status owner, or an admin can change a status
    private Person.Id owner;
    // Priority is to help draw attention to the important items
    @Builder.Default
    private Priority priority = Priority.NORMAL;
    // Visibility is whether the user can see this item, or only an Admin
    @Builder.Default
    private Visibility visibility = Visibility.USER;
    // lastUpdate changes only when status or notes are changed (not owner, priority, or visibility)
    @Setter(AccessLevel.NONE)
    @Builder.Default
    private LocalDateTime lastUpdate = LocalDateTime.now();

    public void setValue(final Object statusValue) {
        final StatusValue newVal = (statusValue instanceof StatusValue) ?
                (StatusValue) statusValue : StatusValue.valueOf(String.valueOf(statusValue));
        if (!value.equals(newVal)) {
            value = newVal;
            lastUpdate = LocalDateTime.now();
        }
    }

    public void setNotes(@NonNull final String newNotes) {
        if (notes == null || !notes.equals(newNotes)) {
            notes = newNotes;
            lastUpdate = LocalDateTime.now();
        }
    }

    public enum StatusValue {
        DONE,
        TODO,
        IN_PROGRESS,
        NEED_HELP,
        WAITING
    }

    public enum Priority {
        OPTIONAL,
        LOW,
        NORMAL,
        HIGH,
        CRITICAL
    }

    public enum Visibility {
        USER,
        ADMIN
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class StatusBuilder {
    }
}
