package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

@JsonDeserialize(builder = Status.StatusBuilder.class)
@Data
@Builder
public class Status implements Serializable {
    @NonNull
    @Setter(AccessLevel.NONE)
    @Builder.Default
    private Status.StatusValue value = StatusValue.TODO;
    // lastUpdate changes only when status or notes are changed (not owner, priority, or visibility)
    @Setter(AccessLevel.NONE)
    @Builder.Default
    private LocalDateTime lastUpdate = LocalDateTime.now();
    private String notes;
    // Only the Status owner, a manager of the Status owner, or an admin can change a status
    private Person.Id owner;
    // Priority is to help draw attention to the important items
    @Builder.Default
    private Priority priority = Priority.NORMAL;
    // Visibility is whether the user can see this item, or only an Admin
    @Builder.Default
    private Visibility visibility = Visibility.USER;

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
        TODO("To do"),
        IN_PROGRESS("In Progress"),
        //BLOCKED("Blocked"), FIXME: May want to add this as a "tag" along w/ other tags, probably doesn't belong here
        DONE("Done");

        @Getter
        final String displayValue;

        StatusValue(final String text) {
            this.displayValue = text;
        }
    }

    public enum Priority {
        OPTIONAL("Optional"),
        LOW("Low"),
        NORMAL("Normal"),
        HIGH("High"),
        CRITICAL("Critical");

        @Getter
        final String displayValue;

        Priority(final String text) {
            this.displayValue = text;
        }
    }

    public enum Visibility {
        DELETED,
        USER,
        ADMIN
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class StatusBuilder {
    }
}
