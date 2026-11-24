package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;

/**
 * The one JSON codec every DAO uses for its {@code content} attribute. Each DAO once carried its own
 * {@code parseX}/{@code toJson} pair; they differed only in the type and the log wording.
 *
 * <p>Both directions answer {@code null} on failure, which the read pipelines filter out: a mangled row reads as
 * absent rather than failing the whole page. {@link #parse} deliberately has no null guard -- a missing attribute
 * still surfaces as Jackson's {@code IllegalArgumentException}, exactly as the per-DAO copies behaved.
 */
@Slf4j
final class JsonRows {
    private final ObjectMapper mapper;

    JsonRows(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** The stored row as a {@code type}, or {@code null} (logged) when it does not parse. */
    <T> T parse(final Class<T> type, final String json) {
        try {
            return mapper.readValue(json, type);
        } catch (final IOException ex) {
            log.error("Unable to parse {} record: {}", type.getSimpleName(), json, ex);
            return null;
        }
    }

    /**
     * The value as JSON, or {@code null} (logged) when it does not serialize. Only the type is logged: a row's
     * {@code toString} carries names, addresses and phone numbers.
     */
    String toJson(final Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (final IOException ex) {
            log.error("Unable to serialize {}", value == null ? null : value.getClass().getSimpleName(), ex);
            return null;
        }
    }
}
