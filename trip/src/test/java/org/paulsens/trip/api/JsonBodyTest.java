package org.paulsens.trip.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The one reader of a {@code Map} request body. What it pins is what the per-resource copies disagreed on: a
 * missing body is a body with no fields (never a NullPointerException), a field of the wrong JSON type is
 * absent, and a date that cannot be read is the caller's mistake (an IllegalArgumentException, so a 400).
 */
public class JsonBodyTest {

    @Test
    public void aMissingBodyHasNoFields() {
        Assert.assertFalse(JsonBody.has(null, "a"));
        Assert.assertNull(JsonBody.str(null, "a"));
        Assert.assertEquals(JsonBody.str(null, "a", "fallback"), "fallback");
        Assert.assertNull(JsonBody.dbl(null, "a"));
        Assert.assertEquals(JsonBody.intOf(null, "a", 7), 7);
        Assert.assertTrue(JsonBody.bool(null, "a", true));
        Assert.assertTrue(JsonBody.strings(null, "a").isEmpty());
        Assert.assertNull(JsonBody.date(null, "a"));
        Assert.assertNull(JsonBody.dateTime(null, "a"));
    }

    @Test
    public void scalarsReadByTypeAndTheWrongTypeIsAbsent() {
        final Map<String, Object> body = new HashMap<>();
        body.put("text", "hello");
        body.put("number", 12.5);
        body.put("flag", Boolean.FALSE);
        body.put("nothing", null);

        Assert.assertEquals(JsonBody.str(body, "text"), "hello");
        Assert.assertEquals(JsonBody.str(body, "number"), "12.5", "any scalar reads as text");
        Assert.assertNull(JsonBody.string(null));
        Assert.assertEquals(JsonBody.dbl(body, "number"), 12.5);
        Assert.assertNull(JsonBody.dbl(body, "text"));
        Assert.assertEquals(JsonBody.intOf(body, "number", 0), 12);
        Assert.assertEquals(JsonBody.intOf(body, "text", 3), 3);
        Assert.assertFalse(JsonBody.bool(body, "flag", true));
        Assert.assertTrue(JsonBody.bool(body, "text", true), "a non-boolean is the fallback, not false");
    }

    @Test
    public void aFieldSentAsNullIsNamedButHasNoValue() {
        final Map<String, Object> body = new HashMap<>();
        body.put("nothing", null);
        Assert.assertTrue(JsonBody.has(body, "nothing"));
        Assert.assertFalse(JsonBody.has(body, "absent"));
        Assert.assertNull(JsonBody.str(body, "nothing", "fallback"), "named, so the fallback does not apply");
        Assert.assertEquals(JsonBody.str(body, "absent", "fallback"), "fallback");
    }

    @Test
    public void arraysReadAsStringsWithoutTheirNulls() {
        final Map<String, Object> body = new HashMap<>();
        body.put("ids", Arrays.asList("a", null, 3));
        body.put("notAList", "a");
        Assert.assertEquals(JsonBody.strings(body, "ids"), List.of("a", "3"));
        Assert.assertTrue(JsonBody.strings(body, "notAList").isEmpty());
        Assert.assertTrue(JsonBody.strings(body, "absent").isEmpty());
        // Callers add to the answer (a form's id list), so it is never an immutable empty list.
        JsonBody.strings(body, "absent").add("x");
        Assert.assertEquals(JsonBody.stringList(List.of(1, 2)), List.of("1", "2"));
    }

    @Test
    public void datesParseTrimmedAndBlankIsAbsent() {
        final Map<String, Object> body = Map.of("day", " 2026-09-20 ", "when", "2026-09-20T15:00", "blank", " ");
        Assert.assertEquals(JsonBody.date(body, "day"), LocalDate.of(2026, 9, 20));
        Assert.assertEquals(JsonBody.dateTime(body, "when"), LocalDateTime.of(2026, 9, 20, 15, 0));
        Assert.assertNull(JsonBody.date(body, "blank"));
        Assert.assertNull(JsonBody.dateTime(body, "blank"));
    }

    @Test
    public void anUnreadableDateIsTheCallersMistakeAndNamesTheField() {
        final Map<String, Object> body = Map.of("validFrom", "next tuesday");
        final IllegalArgumentException day = Assert.expectThrows(IllegalArgumentException.class,
                () -> JsonBody.date(body, "validFrom"));
        Assert.assertTrue(day.getMessage().contains("validFrom"), day.getMessage());
        final IllegalArgumentException when = Assert.expectThrows(IllegalArgumentException.class,
                () -> JsonBody.dateTime(body, "validFrom"));
        Assert.assertTrue(when.getMessage().contains("validFrom"), when.getMessage());
        // The whole point: the mapper answers these 400, where the DateTimeParseException it replaces was a 500.
        Assert.assertEquals(new JsonExceptionMapper().toResponse(day).getStatus(), 400);
    }

    @Test
    public void aFormsOwnSentenceWinsOverTheFallback() {
        Assert.assertEquals(JsonBody.problemOr("Too many rooms.", "Not saved."), "Too many rooms.");
        Assert.assertEquals(JsonBody.problemOr(" ", "Not saved."), "Not saved.");
        Assert.assertEquals(JsonBody.problemOr(null, "Not saved."), "Not saved.");
    }
}
