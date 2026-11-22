package org.paulsens.trip.i18n;

import java.util.Locale;
import java.util.Map;
import org.paulsens.trip.model.Language;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@link MessageText}: plural categories come from the reader's language, not from an {@code n == 1} branch. */
public class MessageTextTest {

    private static final String DAYS_EN = "{count, plural, one {# day} other {# days}}";
    private static final String DAYS_ES = "{count, plural, one {# día} other {# días}}";
    private static final String DAYS_HR = "{count, plural, one {# dan} few {# dana} other {# dana}}";
    private static final String PHOTOS_HR =
            "{count, plural, one {# fotografija} few {# fotografije} other {# fotografija}}";

    @Test
    public void englishAndSpanishHaveTwoForms() {
        Assert.assertEquals(days(DAYS_EN, Language.English, 1), "1 day");
        Assert.assertEquals(days(DAYS_EN, Language.English, 0), "0 days");
        Assert.assertEquals(days(DAYS_EN, Language.English, 5), "5 days");
        Assert.assertEquals(days(DAYS_ES, Language.Spanish, 1), "1 día");
        Assert.assertEquals(days(DAYS_ES, Language.Spanish, 3), "3 días");
    }

    @Test
    public void croatianHasThreeFormsChosenByTheLastDigits() {
        Assert.assertEquals(days(PHOTOS_HR, Language.Croatian, 1), "1 fotografija");
        Assert.assertEquals(days(PHOTOS_HR, Language.Croatian, 3), "3 fotografije", "2 to 4 is 'few'");
        Assert.assertEquals(days(PHOTOS_HR, Language.Croatian, 5), "5 fotografija");
        Assert.assertEquals(days(PHOTOS_HR, Language.Croatian, 12), "12 fotografija", "12 to 14 is NOT 'few'");
        Assert.assertEquals(days(PHOTOS_HR, Language.Croatian, 21), "21 fotografija", "21 is 'one' again");
        Assert.assertEquals(days(PHOTOS_HR, Language.Croatian, 23), "23 fotografije");
        Assert.assertEquals(days(DAYS_HR, Language.Croatian, 2), "2 dana");
    }

    @Test
    public void numberedArgumentsMayBeReorderedByTheTranslation() {
        Assert.assertEquals(MessageText.format("{0} registered {1}", Locale.ENGLISH, "Ken", "Lucy"),
                "Ken registered Lucy");
        Assert.assertEquals(MessageText.format("{1}: registered by {0}", Locale.ENGLISH, "Ken", "Lucy"),
                "Lucy: registered by Ken");
    }

    @Test
    public void aPluralNumberIsWrittenTheWayTheLanguageWritesNumbers() {
        Assert.assertEquals(days(DAYS_EN, Language.English, 1234), "1,234 days");
        Assert.assertEquals(days(DAYS_HR, Language.Croatian, 1234), "1.234 dana");
    }

    @Test
    public void aJavaTimeValueGoesInAsItsOwnTextAndNeverThrows() {
        Assert.assertEquals(MessageText.format("back to {0}.", Locale.ENGLISH, java.time.LocalDate.of(2026, 1, 2)),
                "back to 2026-01-02.", "what the concatenation printed, not ICU's 1/2/26, 12:00 AM");
        Assert.assertEquals(MessageText.format("at {0}", Locale.ENGLISH,
                java.time.Instant.parse("2026-01-02T03:04:05Z")), "at 2026-01-02T03:04:05Z", "ICU throws on these");
        Assert.assertEquals(MessageText.format("{when}", Locale.ENGLISH,
                Map.of("when", java.time.LocalDate.of(2026, 1, 2))), "2026-01-02");
    }

    private static String days(final String pattern, final Language language, final int count) {
        return MessageText.format(pattern, language.getLocale(), Map.of("count", count));
    }
}
