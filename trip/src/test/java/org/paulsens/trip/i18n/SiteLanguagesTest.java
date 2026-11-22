package org.paulsens.trip.i18n;

import java.util.List;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.model.Language;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@link SiteLanguages}: the one parser of the {@code site.languages} setting. */
public class SiteLanguagesTest {

    @Test
    public void theListKeepsItsOrderAndTheFirstIsTheDefault() {
        Assert.assertEquals(SiteLanguages.parse("es, en,hr"),
                List.of(Language.Spanish, Language.English, Language.Croatian));
        Assert.assertEquals(SiteLanguages.parse("es, en,hr").get(0), Language.Spanish);
    }

    @Test
    public void anUnknownOrRepeatedCodeIsDropped() {
        Assert.assertEquals(SiteLanguages.parse("en, xx, es-MX, es, ,EN"),
                List.of(Language.English, Language.Spanish));
    }

    @Test
    public void nothingValidMeansEnglishNeverAnEmptySite() {
        Assert.assertEquals(SiteLanguages.parse(null), List.of(Language.English));
        Assert.assertEquals(SiteLanguages.parse(""), List.of(Language.English));
        Assert.assertEquals(SiteLanguages.parse("klingon, ,"), List.of(Language.English));
        Assert.assertEquals(SiteLanguages.parse(" ").get(0), Language.English);
    }

    @Test
    public void theSettingShipsAsEnglishAloneSoANewLanguageDeploysDark() {
        Assert.assertEquals(SiteLanguages.parse(KnownSettings.SITE_LANGUAGES.getDefaultValue()),
                List.of(Language.English));
    }
}
