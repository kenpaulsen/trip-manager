package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

@JsonDeserialize(builder = TodoItem.TodoItemBuilder.class)
@Data
@Builder
public final class TodoItem implements Serializable {
    @NonNull
    private String tripId;
    @NonNull
    private PersonDataValue.Id dataId;
    @NonNull
    private String description;
    private String moreDetails;
    @Builder.Default
    private LocalDateTime created = LocalDateTime.now();

    @JsonPOJOBuilder(withPrefix = "")
    public static class TodoItemBuilder {
    }
}
