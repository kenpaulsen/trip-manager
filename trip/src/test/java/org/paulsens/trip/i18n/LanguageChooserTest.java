package org.paulsens.trip.i18n;

import java.util.Arrays;
import java.util.List;
import org.paulsens.trip.model.Language;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@link LanguageChooser}: the first wish the site offers, else the site's default. */
public class LanguageChooserTest {

    private static final List<Language> ALL = List.of(Language.English, Language.Spanish, Language.Croatian);

    @Test
    public void theFirstOfferedWishWins() {
        Assert.assertEquals(LanguageChooser.choose(ALL, List.of("hr", "es")), Language.Croatian);
        Assert.assertEquals(LanguageChooser.choose(ALL, List.of("es-MX", "en")), Language.Spanish);
    }

    @Test
    public void aWishTheSiteDoesNotOfferIsSkipped() {
        final List<Language> noCroatian = List.of(Language.Spanish, Language.English);
        Assert.assertEquals(LanguageChooser.choose(noCroatian, List.of("hr", "en")), Language.English);
        Assert.assertEquals(LanguageChooser.choose(noCroatian, List.of("hr", "de")), Language.Spanish,
                "nothing offered was wished for: the site's default, its first language");
    }

    @Test
    public void nullBlankAndUnknownWishesAreSkippedNotFatal() {
        Assert.assertEquals(LanguageChooser.choose(ALL, Arrays.asList(null, " ", "klingon", "es")),
                Language.Spanish);
        Assert.assertEquals(LanguageChooser.choose(ALL, List.of()), Language.English);
    }
}
