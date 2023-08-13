package org.paulsens.trip.model;

import java.util.HashMap;
import java.util.Map;
import lombok.Getter;

@Getter
public enum BindingType {
    // NOTE: Do not change these numbers, they must match the db
    PERSON(1, false),
    TODO_ITEM(2, true),
    REGISTRATION(3, true),
    TRANSACTION(4, true),
    TRIP(5, false),
    TRIP_EVENT(6, false);

    private final int typeId;
    private final boolean composite;

    private static final Map<Integer, BindingType> values = new HashMap<>();

    static {
        for (BindingType value : values()) {
            values.put(value.getTypeId(), value);
        }
    }

    BindingType(final int typeId, final boolean isComposite) {
        this.typeId = typeId;
        this.composite = isComposite;
    }

    public static BindingType from(final int typeId) {
        return values.get(typeId);
    }
}
