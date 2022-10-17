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

@JsonDeserialize(builder = TodoItem.TodoItemBuilder.class)
@Data
@Builder
public class TodoItem implements Serializable {
    @NonNull
    @Setter(AccessLevel.NONE)
    private String tripId;
    @NonNull
    @Setter(AccessLevel.NONE)
    private DataId dataId;
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private LocalDateTime created = LocalDateTime.now();
    @NonNull
    private String description;
    private String moreDetails;

    @JsonPOJOBuilder(withPrefix = "")
    public static class TodoItemBuilder {
    }
}
