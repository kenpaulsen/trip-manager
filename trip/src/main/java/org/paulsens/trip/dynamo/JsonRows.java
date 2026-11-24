package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

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
    /** Every table keeps the row's JSON in this attribute. */
    private static final String CONTENT = "content";

    private final ObjectMapper mapper;

    JsonRows(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * The stored row as a {@code type}, or {@code null} (logged) when it does not parse.
     *
     * <p>The log names the type, the kind of failure and the position in the row, and nothing else. Not the row: it
     * holds names, addresses and money. Not Jackson's message either, nor the exception (whose stack trace prints
     * that message): it quotes the offending value ({@code from String "..."}), an unknown field's name and an
     * unrecognized token, and its reference chain quotes map keys.
     */
    <T> T parse(final Class<T> type, final String json) {
        try {
            return mapper.readValue(json, type);
        } catch (final IOException ex) {
            log.error("Unable to parse {} record: {} at {}", type.getSimpleName(), ex.getClass().getSimpleName(),
                    position(ex));
            return null;
        }
    }

    /** "line 1, column 42": the numbers only, because a location's own text can carry a snippet of the source. */
    private static String position(final IOException ex) {
        final JsonLocation at = (ex instanceof JsonProcessingException jackson) ? jackson.getLocation() : null;
        return (at == null) ? "an unknown position" : "line " + at.getLineNr() + ", column " + at.getColumnNr();
    }

    /** One stored row as a {@code type}: {@code null} for a row that is absent or carries no {@code content}. */
    <T> T parseItem(final Class<T> type, final Map<String, AttributeValue> item) {
        final AttributeValue content = item.get(CONTENT);
        return (content == null) ? null : parse(type, content.s());
    }

    /**
     * A point read that lets a store failure PROPAGATE; {@code null} only when there is no such row. For the
     * loaders behind a cache and for the uncached reads: an outage must never be answered as "no such row",
     * which a cache would then remember.
     */
    <T> T get(final Persistence persistence, final String table, final Map<String, AttributeValue> key,
            final Class<T> type) {
        return parseItem(type, persistence.getItem(b -> b.tableName(table).key(key)).item());
    }

    /**
     * A point read that answers empty (logged at debug) when the store fails. Only for the fallback read behind
     * a scan-cached table, where the caller has a default or an already-rendered page and must keep serving.
     * Never interchangeable with {@link #get}.
     */
    <T> Optional<T> tryGet(final Persistence persistence, final String table, final Map<String, AttributeValue> key,
            final Class<T> type) {
        try {
            return Optional.ofNullable(get(persistence, table, key, type));
        } catch (final RuntimeException ex) {
            log.debug("Unable to read the {} row {}", table, key, ex);
            return Optional.empty();
        }
    }

    /**
     * Every row of a (small) table, by a STRONGLY CONSISTENT paginating scan. Consistent because these are the
     * loaders behind a {@code PartitionScanCache}, whose rebuild REMOVES whatever the scan does not return: an
     * eventually consistent scan can miss a row written just before it (docs/caching.md). Fixed here so that no
     * loader can leave it out.
     */
    <T> List<T> scanAll(final Persistence persistence, final String table, final Class<T> type) {
        return parseAll(type, persistence.scanAll(b -> b.tableName(table).consistentRead(true).limit(1000)));
    }

    /**
     * The rows a query or scan answered, each as a {@code type}. A row with no {@code content} attribute and a row
     * whose content does not parse are both skipped: one mangled row must not empty the list it is in.
     */
    <T> List<T> parseAll(final Class<T> type, final List<Map<String, AttributeValue>> items) {
        return items.stream()
                .map(item -> item.get(CONTENT))
                .filter(Objects::nonNull)
                .map(content -> parse(type, content.s()))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * The value as JSON, or {@code null} (logged) when it does not serialize. Only the two types are logged: a
     * row's {@code toString} carries names, addresses and phone numbers, and Jackson's message (which the
     * exception's stack trace would print) quotes the map keys on the way to the failure.
     */
    String toJson(final Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (final IOException ex) {
            log.error("Unable to serialize {}: {}", value == null ? null : value.getClass().getSimpleName(),
                    ex.getClass().getSimpleName());
            return null;
        }
    }
}
