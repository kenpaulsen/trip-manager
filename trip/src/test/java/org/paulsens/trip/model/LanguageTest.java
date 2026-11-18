package org.paulsens.trip.model;

import java.util.Locale;
import java.util.Optional;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@link Language#named}: the by-name lookup for code that holds a trip's language as stored text. */
public class LanguageTest {

    @Test
    public void everyConstantIsFoundByItsStoredName() {
        for (final Language language : Language.values()) {
            Assert.assertEquals(Language.named(language.name()), Optional.of(language));
        }
        Assert.assertEquals(Language.named("Spanish").map(Language::getLocale),
                Optional.of(Locale.forLanguageTag("es")));
        Assert.assertEquals(Language.named("Spanish").map(Language::getDisplayName), Optional.of("Espa\u00f1ol"));
    }

    @Test
    public void anUnknownNameIsEmptyNotAGuess() {
        Assert.assertEquals(Language.named(null), Optional.empty());
        Assert.assertEquals(Language.named(""), Optional.empty());
        Assert.assertEquals(Language.named("spanish"), Optional.empty(), "names are stored exactly");
        Assert.assertEquals(Language.named("Espa\u00f1ol"), Optional.empty(),
                "the display name is not the stored name");
        Assert.assertEquals(Language.named("Klingon"), Optional.empty(), "a row from a newer build");
    }
}
