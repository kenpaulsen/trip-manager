package org.paulsens.trip.model;

import java.io.Serial;
import java.io.Serializable;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

@Value
@Builder
public class CompositeKey implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private static final char SEPARATOR = ',';

    @NonNull
    String partitionKey;
    @NonNull
    String sortKey;

    public String getValue() {
        return partitionKey + SEPARATOR + sortKey;
    }

    @Override
    public String toString() {
        return getValue();
    }

    public static CompositeKey from(final String value) {
        final int idx = value.indexOf(SEPARATOR);
        if (idx < 0) {
            throw new IllegalArgumentException("Invalid composite key, missing '" + SEPARATOR + "'!");
        }
        return CompositeKey.builder()
                .partitionKey(value.substring(0, idx).trim())
                .sortKey(value.substring(idx + 1).trim())
                .build();
    }
}
