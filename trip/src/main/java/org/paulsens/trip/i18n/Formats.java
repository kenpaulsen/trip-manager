package org.paulsens.trip.i18n;

import com.ibm.icu.text.DateIntervalFormat;
import com.ibm.icu.text.DateTimePatternGenerator;
import com.ibm.icu.text.NumberFormat;
import com.ibm.icu.util.DateInterval;
import com.ibm.icu.util.TimeZone;
import com.ibm.icu.util.ULocale;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dates, times and plain numbers the way the reader's language writes them ({@code docs/i18n.md}, "Dates and
 * numbers"). A fixed pattern such as {@code "MMM d, yyyy"} is English word order with translated month names,
 * which is right in no other language; these ask the locale for its own shape instead.
 *
 * <p>A <em>skeleton</em> names the fields and their widths and nothing else ({@code yMMMd}: year, short month,
 * day); {@link #pattern(String, Locale)} turns it into the locale's own pattern, separators and order included
 * ({@code MMM d, yyyy} / {@code d MMM yyyy} / {@code d. MMM yyyy.}). The result is an ordinary pattern, so a page
 * hands it to {@code f:convertDateTime} or a {@code p:datePicker} and Java to {@link DateTimeFormatter}.
 *
 * <p>English output never changes because of this class. Where the product's English pattern is not what the
 * skeleton gives (a time: CLDR now puts a narrow no-break space before AM/PM; "Sep 3 h:mm a" without a comma;
 * "MM-dd-yyyy"), the caller passes that pattern as {@code english} and English keeps it byte for byte.
 *
 * <p>Money is deliberately absent: US dollars keep the US format in every language ({@code pay/MoneyMath}).
 * Stored values, logs, file names, CSV and wire formats never come through here either: a machine reads those.
 */
public final class Formats {

    /** CLDR's separator before AM/PM; a typed time and every existing English string use a plain space. */
    private static final char NARROW_NO_BREAK_SPACE = ' ';
    private static final Map<String, String> PATTERNS = new ConcurrentHashMap<>();
    private static final Map<String, DateTimeFormatter> FORMATTERS = new ConcurrentHashMap<>();

    private Formats() {
    }

    /** A calendar date with an abbreviated month: Sep 3, 2026 / 3 sept 2026 / 3. ruj 2026. */
    public static String date(final TemporalAccessor when, final Locale locale) {
        return format(when, "yMMMd", locale);
    }

    /** A calendar date written out: September 3, 2026 / 3 de septiembre de 2026. */
    public static String longDate(final TemporalAccessor when, final Locale locale) {
        return format(when, "yMMMMd", locale);
    }

    /** A time of day: 3:05 PM / 15:05. */
    public static String time(final TemporalAccessor when, final Locale locale) {
        return format(when, "jm", locale);
    }

    /**
     * {@link #date} and {@link #time} together, in the order the language uses. Through the skeletons like every
     * other helper here, so English keeps the patterns it has always printed and never gains CLDR's comma or its
     * narrow no-break space.
     */
    public static String dateTime(final TemporalAccessor when, final Locale locale) {
        return format(when, "yMMMdjm", "MMM d, yyyy h:mm a", locale);
    }

    /** A whole number with the language's grouping: 1,234 / 1.234. */
    public static String number(final long value, final Locale locale) {
        return NumberFormat.getIntegerInstance(locale).format(value);
    }

    /** Whether {@code locale} reads English, the source language whose patterns never change. Null is English. */
    public static boolean isEnglish(final Locale locale) {
        return locale == null || locale.getLanguage().isEmpty() || "en".equals(locale.getLanguage());
    }

    /**
     * The locale's own pattern for a skeleton ({@code yMMMd}, {@code yMMdd}, {@code jm}). A one-letter year is
     * widened to {@code yyyy} (a date picker's script reads {@code y} as a two-digit year, and English has always
     * printed {@code yyyy}), and CLDR's narrow no-break space becomes a plain one.
     */
    public static String pattern(final String skeleton, final Locale locale) {
        final Locale reader = locale == null ? Locale.ENGLISH : locale;
        return PATTERNS.computeIfAbsent(skeleton + '|' + reader.toLanguageTag(), key -> generate(skeleton, reader));
    }

    /** {@link #pattern(String, Locale)}, except that English keeps {@code english}, its pattern as it has been. */
    public static String pattern(final String skeleton, final String english, final Locale locale) {
        return isEnglish(locale) ? english : pattern(skeleton, locale);
    }

    /** A formatter for {@link #pattern(String, String, Locale)} in {@code locale}; add a zone with withZone. */
    public static DateTimeFormatter formatter(final String skeleton, final String english, final Locale locale) {
        final Locale reader = locale == null ? Locale.ENGLISH : locale;
        final String pattern = pattern(skeleton, english, reader);
        return FORMATTERS.computeIfAbsent(pattern + '|' + reader.toLanguageTag(),
                key -> DateTimeFormatter.ofPattern(pattern, reader));
    }

    /** A formatter for {@link #pattern(String, Locale)}, for a skeleton whose English pattern is the old one. */
    public static DateTimeFormatter formatter(final String skeleton, final Locale locale) {
        return formatter(skeleton, pattern(skeleton, Locale.ENGLISH), locale);
    }

    /** {@code when} in the skeleton's shape for {@code locale}; blank for null. */
    public static String format(final TemporalAccessor when, final String skeleton, final Locale locale) {
        return when == null ? "" : formatter(skeleton, locale).format(when);
    }

    /** {@code when} in the skeleton's shape, English keeping {@code english}; blank for null. */
    public static String format(final TemporalAccessor when, final String skeleton, final String english,
            final Locale locale) {
        return when == null ? "" : formatter(skeleton, english, locale).format(when);
    }

    /**
     * Two calendar days as one range in the language's own shape, the shared parts written once: "3–12 sept
     * 2026", "28. ruj – 2. lis 2026.". For a language other than English: each English caller keeps the exact
     * range it has always printed, and those differ from one another and from CLDR's.
     */
    public static String dateRange(final LocalDate start, final LocalDate end, final String skeleton,
            final Locale locale) {
        final DateIntervalFormat format = DateIntervalFormat.getInstance(skeleton, ULocale.forLocale(locale));
        // The days are calendar dates, so any one zone works as long as the formatter reads them in the same one.
        format.setTimeZone(TimeZone.GMT_ZONE);
        return format.format(new DateInterval(epochMillis(start), epochMillis(end)))
                .replace(NARROW_NO_BREAK_SPACE, ' ');
    }

    private static long epochMillis(final LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private static String generate(final String skeleton, final Locale locale) {
        final String best = DateTimePatternGenerator.getInstance(ULocale.forLocale(locale))
                .getBestPattern(skeleton, DateTimePatternGenerator.MATCH_ALL_FIELDS_LENGTH);
        return fullYears(best.replace(NARROW_NO_BREAK_SPACE, ' '));
    }

    /** Widens each lone {@code y} outside quoted text to {@code yyyy}; quoted literals pass through untouched. */
    private static String fullYears(final String pattern) {
        final StringBuilder out = new StringBuilder(pattern.length() + 3);
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            final char c = pattern.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            }
            final boolean lone = c == 'y' && !quoted && (i == 0 || pattern.charAt(i - 1) != 'y')
                    && (i + 1 == pattern.length() || pattern.charAt(i + 1) != 'y');
            out.append(lone ? "yyyy" : String.valueOf(c));
        }
        return out.toString();
    }
}
