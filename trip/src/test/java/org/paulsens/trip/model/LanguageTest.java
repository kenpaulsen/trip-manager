package org.paulsens.trip.model;

import java.util.Locale;
import java.util.Optional;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link Language#named}, the by-name lookup for code that holds a trip's language as stored text, and
 * {@link Language#forTag}, the by-tag lookup everything about the interface uses.
 */
public class LanguageTest {

    @Test
    public void everyConstantIsFoundByItsStoredName() {
        for (final Language language : Language.values()) {
            Assert.assertEquals(Language.named(language.name()), Optional.of(language));
        }
        Assert.assertEquals(Language.named("Spanish").map(Language::getLocale),
                Optional.of(Locale.forLanguageTag("es")));
        Assert.assertEquals(Language.named("Spanish").map(Language::getDisplayName), Optional.of("Español"));
    }

    @Test
    public void anUnknownNameIsEmptyNotAGuess() {
        Assert.assertEquals(Language.named(null), Optional.empty());
        Assert.assertEquals(Language.named(""), Optional.empty());
        Assert.assertEquals(Language.named("spanish"), Optional.empty(), "names are stored exactly");
        Assert.assertEquals(Language.named("Español"), Optional.empty(),
                "the display name is not the stored name");
        Assert.assertEquals(Language.named("Klingon"), Optional.empty(), "a row from a newer build");
    }

    @Test
    public void everyConstantIsFoundByItsTagAndTheTagIsItsLocale() {
        for (final Language language : Language.values()) {
            Assert.assertEquals(Language.forTag(language.getTag()), Optional.of(language));
            Assert.assertEquals(language.getLocale().toLanguageTag(), language.getTag());
        }
        Assert.assertEquals(Language.English.getTag(), "en");
        Assert.assertEquals(Language.Spanish.getTag(), "es");
        Assert.assertEquals(Language.Croatian.getTag(), "hr");
        Assert.assertEquals(Language.Croatian.getDisplayName(), "Hrvatski");
    }

    @Test
    public void aTagMatchesOnItsLanguageAlone() {
        Assert.assertEquals(Language.forTag("es-MX"), Optional.of(Language.Spanish), "a browser's regional tag");
        Assert.assertEquals(Language.forTag("hr_HR"), Optional.of(Language.Croatian), "the Java spelling");
        Assert.assertEquals(Language.forTag(" EN "), Optional.of(Language.English), "case and padding");
    }

    @Test
    public void anUnknownTagIsEmptyNotAGuess() {
        Assert.assertEquals(Language.forTag(null), Optional.empty());
        Assert.assertEquals(Language.forTag("  "), Optional.empty());
        Assert.assertEquals(Language.forTag("de"), Optional.empty(), "a language this build does not speak");
        Assert.assertEquals(Language.forTag("Spanish"), Optional.empty(), "the stored name is not the tag");
    }
}
