package org.paulsens.trip.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.i18n.Text;

/**
 * Reads the fields of a request body that arrived as a plain JSON object ({@code Map<String, Object>}).
 *
 * <p>Every resource that takes such a body grew its own {@code str}/{@code dbl}/{@code intOf} trio, and the
 * copies drifted where it matters: some read a missing BODY as a body with no fields, others threw a
 * {@code NullPointerException} (a 500) on it, and a date that did not parse was a 400 in one resource and a 500
 * in the next. One reader, so the answer is the same everywhere: a null body has no fields, a field of the
 * wrong JSON type reads as absent, and a date that cannot be read is an {@link IllegalArgumentException},
 * which {@code JsonExceptionMapper} answers as 400 {@code BAD_REQUEST}.
 */
final class JsonBody {

    private JsonBody() {
    }

    /** Whether the body names this field at all, even as JSON null; a missing body names nothing. */
    static boolean has(final Map<String, Object> body, final String key) {
        return body != null && body.containsKey(key);
    }

    /** The field as text ({@link #string}); null when absent. */
    static String str(final Map<String, Object> body, final String key) {
        return string(body == null ? null : body.get(key));
    }

    /** The field as text when the body names it, else the fallback (a field sent as JSON null reads as null). */
    static String str(final Map<String, Object> body, final String key, final String fallback) {
        return has(body, key) ? str(body, key) : fallback;
    }

    /** Any JSON scalar as text; null stays null. */
    static String string(final Object value) {
        return value == null ? null : value.toString();
    }

    /** A JSON number; null when absent or not a number. */
    static Double dbl(final Map<String, Object> body, final String key) {
        return (body != null && body.get(key) instanceof Number number) ? number.doubleValue() : null;
    }

    /** A JSON number as an int; the fallback when absent or not a number. */
    static int intOf(final Map<String, Object> body, final String key, final int fallback) {
        return (body != null && body.get(key) instanceof Number number) ? number.intValue() : fallback;
    }

    /** A JSON boolean; the fallback when absent or not a boolean. */
    static boolean bool(final Map<String, Object> body, final String key, final boolean fallback) {
        return (body != null && body.get(key) instanceof Boolean flag) ? flag : fallback;
    }

    /** A JSON array as strings, nulls dropped; empty (and mutable) when absent or not an array. */
    static List<String> strings(final Map<String, Object> body, final String key) {
        return stringList(body == null ? null : body.get(key));
    }

    /** {@link #strings} for a value already in hand. */
    static List<String> stringList(final Object value) {
        final List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (final Object item : list) {
                if (item != null) {
                    out.add(item.toString());
                }
            }
        }
        return out;
    }

    /**
     * An ISO day ("2026-09-20"); null when absent or blank.
     *
     * @throws IllegalArgumentException naming the field, when the text is not a date
     */
    static LocalDate date(final Map<String, Object> body, final String key) {
        final String text = str(body, key);
        try {
            return (text == null || text.isBlank()) ? null : LocalDate.parse(text.trim());
        } catch (final DateTimeParseException ex) {
            throw new IllegalArgumentException(Text.get("api.shared.dateInvalid", key), ex);
        }
    }

    /**
     * An ISO local date and time ("2026-09-20T15:00"); null when absent or blank.
     *
     * @throws IllegalArgumentException naming the field, when the text is not a date and time
     */
    static LocalDateTime dateTime(final Map<String, Object> body, final String key) {
        final String text = str(body, key);
        try {
            return (text == null || text.isBlank()) ? null : LocalDateTime.parse(text.trim());
        } catch (final DateTimeParseException ex) {
            throw new IllegalArgumentException(Text.get("api.shared.dateTimeInvalid", key), ex);
        }
    }

    /** A form's own refusal sentence, or the endpoint's fallback when the bean left none. */
    static String problemOr(final String problem, final String fallback) {
        return (problem == null || problem.isBlank()) ? fallback : problem;
    }
}
