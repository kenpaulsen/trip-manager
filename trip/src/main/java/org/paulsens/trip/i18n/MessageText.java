package org.paulsens.trip.i18n;

import com.ibm.icu.text.MessageFormat;
import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Formats one translated message ({@code docs/i18n.md}, "Writing a message"): an ICU pattern, the reader's
 * locale and the values to put in it.
 *
 * <p>ICU rather than {@code java.text.MessageFormat} because of plurals. The JDK's format can only say "one or
 * many", which is wrong for Croatian (1 dan, 2 dana, 5 dana); ICU selects the category from the locale's own
 * rules, so the same call is right in every language and a new language needs no code.
 */
public final class MessageText {

    private MessageText() {
    }

    /** A pattern with numbered arguments: {@code "{0} is registered"}. */
    public static String format(final String pattern, final Locale locale, final Object... args) {
        return new MessageFormat(pattern, locale).format(Arrays.stream(args).map(MessageText::plain).toArray());
    }

    /**
     * A pattern with named arguments: {@code "{count, plural, one {# day} other {# days}}"}. Named is the form to
     * prefer once a message has two values, since a translator reorders them by name without counting positions.
     */
    public static String format(final String pattern, final Locale locale, final Map<String, Object> args) {
        final Map<String, Object> plain = new HashMap<>();
        args.forEach((name, value) -> plain.put(name, plain(value)));
        return new MessageFormat(pattern, locale).format(plain);
    }

    /**
     * A {@code java.time} value goes in as its own text. ICU formats a {@code LocalDate} as a short date-and-time
     * ("1/2/26, 12:00 AM") and throws on an {@code Instant}, where the concatenation these messages replaced
     * printed {@code 2026-01-02}; a date is formatted for its reader on purpose, with {@code Formats}, before it
     * is passed in. A {@code java.util.Date} likewise. Numbers, and everything else, are left to ICU.
     */
    private static Object plain(final Object arg) {
        return arg instanceof TemporalAccessor || arg instanceof Date ? arg.toString() : arg;
    }
}
