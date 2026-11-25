package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.PropertyBindingException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
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
     * <p>The log names the type, the kind of failure, the position in the row and the path of known properties to
     * it, and nothing else. Not the row: it holds names, addresses and money. Not Jackson's message either, nor the
     * exception (whose stack trace prints that message): it quotes the offending value ({@code from String "..."}),
     * an unknown field's name and an unrecognized token, and its reference chain quotes map keys.
     */
    <T> T parse(final Class<T> type, final String json) {
        return parse(type, json, Map.of());
    }

    /**
     * {@link #parse(Class, String)} for a stored row, whose log also names the row: its id-like key attributes
     * ({@link #rowKeys}), so a row that stops parsing after a deploy can be found rather than just vanishing.
     */
    private <T> T parse(final Class<T> type, final String json, final Map<String, AttributeValue> item) {
        try {
            return mapper.readValue(json, type);
        } catch (final IOException ex) {
            log.error("Unable to parse {} record{}: {} at {}{}", type.getSimpleName(), rowKeys(item),
                    ex.getClass().getSimpleName(), position(ex), path(ex));
            return null;
        }
    }

    /**
     * The row's id-like attributes ({@code id}, {@code sk}, {@code ts} and every {@code ...Id}), as " (id=...)", or
     * "" for none. Only those: other attributes can carry names, and a value holding an {@code @} (an email key) is
     * left out too.
     */
    static String rowKeys(final Map<String, AttributeValue> item) {
        final String keys = item.entrySet().stream()
                .filter(entry -> isIdLike(entry.getKey()) && isPlain(entry.getValue()))
                .map(entry -> entry.getKey() + "=" + plain(entry.getValue()))
                .sorted()
                .collect(Collectors.joining(", "));
        return keys.isEmpty() ? "" : " (" + keys + ")";
    }

    private static boolean isIdLike(final String name) {
        return "id".equals(name) || "sk".equals(name) || "ts".equals(name) || name.endsWith("Id");
    }

    private static boolean isPlain(final AttributeValue value) {
        final String plain = plain(value);
        return plain != null && plain.indexOf('@') < 0;
    }

    private static String plain(final AttributeValue value) {
        return value.s() != null ? value.s() : value.n();
    }

    /**
     * " in field.list[2].{key}" for a mapping failure: the known properties on the way to it, an index as itself and a
     * map key as "{key}" (a key can be an address), and an unknown property left off (its name is the row's text).
     */
    static String path(final IOException ex) {
        if (!(ex instanceof JsonMappingException mapping) || mapping.getPath().isEmpty()) {
            return "";
        }
        final List<JsonMappingException.Reference> refs = new ArrayList<>(mapping.getPath());
        if (ex instanceof PropertyBindingException) {
            refs.remove(refs.size() - 1);
        }
        final String path = refs.stream().map(JsonRows::step).collect(Collectors.joining("."));
        return path.isEmpty() ? "" : " in " + path;
    }

    private static String step(final JsonMappingException.Reference ref) {
        if (ref.getIndex() >= 0) {
            return "[" + ref.getIndex() + "]";
        }
        return ref.getFrom() instanceof Map ? "{key}" : ref.getFieldName();
    }

    /** "line 1, column 42": the numbers only, because a location's own text can carry a snippet of the source. */
    private static String position(final IOException ex) {
        final JsonLocation at = (ex instanceof JsonProcessingException jackson) ? jackson.getLocation() : null;
        return (at == null) ? "an unknown position" : "line " + at.getLineNr() + ", column " + at.getColumnNr();
    }

    /** One stored row as a {@code type}: {@code null} for a row that is absent or carries no {@code content}. */
    <T> T parseItem(final Class<T> type, final Map<String, AttributeValue> item) {
        final AttributeValue content = item.get(CONTENT);
        return (content == null) ? null : parse(type, content.s(), item);
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
                .map(item -> parseItem(type, item))
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
