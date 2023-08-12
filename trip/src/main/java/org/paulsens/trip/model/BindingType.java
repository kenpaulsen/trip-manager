package org.paulsens.trip.model;

import java.util.HashMap;
import java.util.Map;
import lombok.Getter;

@Getter
public enum BindingType {
    PERSON(1),
    TODO_ITEM(2),
    REGISTRATION(3),
    TRANSACTION(4),
    TRIP(5),
    TRIP_EVENT(6);

    private final int typeId;
    private static final Map<Integer, BindingType> values = new HashMap<>();

    static {
        for (BindingType value : values()) {
            values.put(value.getTypeId(), value);
        }
    }

    BindingType(final int typeId) {
        this.typeId = typeId;
    }

    public static BindingType from(final int typeId) {
        return values.get(typeId);
    }
}
