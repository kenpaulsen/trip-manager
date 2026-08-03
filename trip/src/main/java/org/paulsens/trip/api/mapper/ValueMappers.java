package org.paulsens.trip.api.mapper;

import java.util.List;
import org.paulsens.trip.model.Person;

/**
 * Conversions between the model's value types and their wire forms, shared by every mapper via
 * {@code @Mapper(uses = ValueMappers.class)}.
 *
 * <p>The id wrappers ({@code Person.Id} and friends) exist so that a person id and a trip id cannot be swapped by
 * accident. That protection is worth keeping inside the application and is meaningless on the wire, where
 * everything is JSON text anyway -- so they cross the boundary as plain strings, in exactly one place.
 */
public class ValueMappers {

    public String toString(final Person.Id id) {
        return id == null ? null : id.getValue();
    }

    public Person.Id toPersonId(final String value) {
        return (value == null || value.isBlank()) ? null : Person.Id.from(value);
    }

    public List<String> toIdStrings(final List<Person.Id> ids) {
        return ids == null ? null : ids.stream().map(Person.Id::getValue).toList();
    }

    public List<Person.Id> toPersonIds(final List<String> values) {
        return values == null ? null : values.stream().map(Person.Id::from).toList();
    }
}
