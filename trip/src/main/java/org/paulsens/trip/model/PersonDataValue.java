package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.Setter;
import lombok.Value;
import org.paulsens.trip.util.RandomData;

@JsonDeserialize(builder = PersonDataValue.PersonDataValueBuilder.class)
@Data
@Builder
public final class PersonDataValue implements Serializable {
    @NonNull
    @Setter(AccessLevel.NONE)
    private Person.Id userId;
    @NonNull
    @Setter(AccessLevel.NONE)
    private Id dataId;
    @NonNull
    @Setter(AccessLevel.NONE)
    private String type;
    @NonNull
    private Object content;

    @SuppressWarnings("unchecked")
    public <T> T castContent() {
        return (T) content;
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class PersonDataValueBuilder {
    }

    @Value
    public static class Id implements Serializable, Comparable<Id> {
        @JsonValue
        String value;

        public static Id from(final String id) {
            return new Id(id);
        }

        public static Id newInstance() {
            return new Id(RandomData.genString(8, RandomData.ALPHA_NUM));
        }

        @Override
        public int compareTo(Id o) {
            return value.compareTo(o.getValue());
        }
    }
}
