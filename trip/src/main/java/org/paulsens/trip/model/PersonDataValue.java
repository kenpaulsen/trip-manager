package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.Setter;

@JsonDeserialize(builder = PersonDataValue.PersonDataValueBuilder.class)
@Data
@Builder
public class PersonDataValue implements Serializable {
    @NonNull
    @Setter(AccessLevel.NONE)
    private Person.Id userId;
    @NonNull
    @Setter(AccessLevel.NONE)
    private DataId dataId;
    @NonNull
    @Setter(AccessLevel.NONE)
    private String type;
    private Object content;

    @SuppressWarnings("unchecked")
    public <T> T castContent() {
        return (T) content;
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class PersonDataValueBuilder {
    }
}
