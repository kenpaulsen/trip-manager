package org.paulsens.trip.i18n;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import org.paulsens.trip.model.Language;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@link Formats}: each language's own date and number shape, not English order with translated words. */
public class FormatsTest {

    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 9, 3, 15, 5);
    private static final Locale ES = Language.Spanish.getLocale();
    private static final Locale HR = Language.Croatian.getLocale();

    @Test
    public void aDateTakesTheLanguagesOwnOrder() {
        Assert.assertEquals(Formats.date(WHEN, Locale.ENGLISH), "Sep 3, 2026");
        Assert.assertTrue(Formats.date(WHEN, ES).startsWith("3 sept"), Formats.date(WHEN, ES));
        Assert.assertTrue(Formats.date(WHEN, HR).startsWith("3. ruj"), Formats.date(WHEN, HR));
        Assert.assertEquals(Formats.longDate(LocalDate.of(2026, 9, 3), Locale.ENGLISH), "September 3, 2026");
        Assert.assertEquals(Formats.longDate(WHEN, ES), "3 de septiembre de 2026");
    }

    @Test
    public void aTimeIsTwelveOrTwentyFourHourByLanguage() {
        Assert.assertTrue(Formats.time(WHEN, Locale.ENGLISH).startsWith("3:05"), Formats.time(WHEN, Locale.ENGLISH));
        Assert.assertEquals(Formats.time(WHEN, ES), "15:05");
        Assert.assertEquals(Formats.time(WHEN, HR), "15:05");
        Assert.assertTrue(Formats.dateTime(WHEN, ES).contains("15:05"));
        Assert.assertTrue(Formats.dateTime(WHEN, Locale.ENGLISH).startsWith("Sep 3, 2026"));
    }

    @Test
    public void aMissingValueIsBlank() {
        Assert.assertEquals(Formats.date(null, Locale.ENGLISH), "");
        Assert.assertEquals(Formats.longDate(null, ES), "");
        Assert.assertEquals(Formats.time(null, HR), "");
        Assert.assertEquals(Formats.dateTime(null, HR), "");
    }

    @Test
    public void aNumberGroupsTheWayTheLanguageDoes() {
        Assert.assertEquals(Formats.number(1234567, Locale.ENGLISH), "1,234,567");
        Assert.assertEquals(Formats.number(1234567, HR), "1.234.567");
    }

    /**
     * Every skeleton the code and the pages use WITHOUT an English pattern of their own, and the literal pattern
     * each replaced: English output may not change, so each must still be exactly the old one.
     */
    private static final Map<String, String> ENGLISH_AS_BEFORE = Map.ofEntries(
            Map.entry("yMMMd", "MMM d, yyyy"),
            Map.entry("yMMMdd", "MMM dd, yyyy"),
            Map.entry("MMMd", "MMM d"),
            Map.entry("MMMdd", "MMM dd"),
            Map.entry("yMMdd", "MM/dd/yyyy"),
            Map.entry("yMMM", "MMM yyyy"),
            Map.entry("yMMMM", "MMMM yyyy"),
            Map.entry("yMMMMd", "MMMM d, yyyy"),
            Map.entry("yMMMEd", "EEE, MMM d, yyyy"),
            Map.entry("MMMMEEEEd", "EEEE, MMMM d"),
            Map.entry("jm", "h:mm a"));

    @Test
    public void eachSkeletonKeepsTheEnglishPatternItReplaced() {
        ENGLISH_AS_BEFORE.forEach(FormatsTest::assertEnglishUnchanged);
    }

    private static void assertEnglishUnchanged(final String skeleton, final String old) {
        Assert.assertEquals(Formats.pattern(skeleton, Locale.ENGLISH), old, skeleton);
        Assert.assertEquals(Formats.pattern(skeleton, Locale.US), old, skeleton);
        Assert.assertEquals(Formats.format(WHEN, skeleton, Locale.ENGLISH),
                DateTimeFormatter.ofPattern(old, Locale.US).format(WHEN), skeleton);
    }

    @Test
    public void theNamedHelpersPrintEnglishAsItHasAlwaysBeen() {
        Assert.assertEquals(Formats.time(WHEN, Locale.ENGLISH), "3:05 PM", "a plain space, never CLDR's narrow one");
        Assert.assertEquals(Formats.dateTime(WHEN, Locale.ENGLISH), "Sep 3, 2026 3:05 PM");
    }

    @Test
    public void otherLanguagesGetTheirOwnOrderAndAFullYear() {
        Assert.assertEquals(Formats.pattern("yMMMd", ES), "d MMM yyyy");
        Assert.assertEquals(Formats.pattern("yMMMd", HR), "d. MMM yyyy.");
        Assert.assertEquals(Formats.pattern("yMMdd", ES), "dd/MM/yyyy");
        Assert.assertEquals(Formats.pattern("yMMdd", HR), "dd. MM. yyyy.");
        Assert.assertEquals(Formats.pattern("yMMMMd", ES), "d 'de' MMMM 'de' yyyy", "quoted text is left alone");
        Assert.assertEquals(Formats.pattern("jm", ES), "H:mm");
        Assert.assertEquals(Formats.format(WHEN, "yMMMMd", ES), "3 de septiembre de 2026");
        Assert.assertEquals(Formats.format(WHEN, "MMMMEEEEd", ES), "jueves, 3 de septiembre");
        Assert.assertEquals(Formats.format(YearMonth.of(2026, 9), "yMMMM", ES), "septiembre de 2026");
        Assert.assertFalse(Formats.pattern("yMMMdjm", Locale.ENGLISH).contains("\u202f"),
                "a plain space before AM/PM, as people type it");
    }

    @Test
    public void anEnglishPatternIsKeptByteForByteAndOnlyForEnglish() {
        Assert.assertEquals(Formats.pattern("MMMdjm", "MMM d h:mm a", Locale.ENGLISH), "MMM d h:mm a");
        Assert.assertEquals(Formats.pattern("MMMdjm", "MMM d h:mm a", null), "MMM d h:mm a", "null reads English");
        Assert.assertEquals(Formats.pattern("MMMdjm", "MMM d h:mm a", ES), "d MMM, H:mm");
        Assert.assertEquals(Formats.format(WHEN, "MMMdjm", "MMM d h:mm a", Locale.ENGLISH), "Sep 3 3:05 PM");
        Assert.assertEquals(Formats.format(WHEN, "MMMdjm", "MMM d h:mm a", HR), "3. ruj 15:05");
        Assert.assertEquals(Formats.format(null, "MMMdjm", "MMM d h:mm a", HR), "");
        Assert.assertEquals(Formats.format(null, "yMMMd", HR), "");
        Assert.assertSame(Formats.formatter("yMMMd", ES), Formats.formatter("yMMMd", ES), "built once");
    }

    @Test
    public void englishIsTheSourceLanguageWhateverItsRegion() {
        Assert.assertTrue(Formats.isEnglish(null));
        Assert.assertTrue(Formats.isEnglish(Locale.ROOT));
        Assert.assertTrue(Formats.isEnglish(Locale.US));
        Assert.assertFalse(Formats.isEnglish(ES));
    }

    @Test
    public void aRangeWritesTheSharedPartsOnce() {
        final LocalDate start = LocalDate.of(2026, 9, 3);
        Assert.assertEquals(Formats.dateRange(start, LocalDate.of(2026, 9, 12), "yMMMd", ES), "3–12 sept 2026");
        Assert.assertEquals(Formats.dateRange(start, LocalDate.of(2026, 10, 2), "yMMMd", ES),
                "3 sept\u2009–\u20092 oct 2026", "thin spaces around the dash, as CLDR writes it");
        Assert.assertTrue(Formats.dateRange(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3), "yMMMd", HR)
                .contains("2026"), "a range across a new year names both years");
    }
}
