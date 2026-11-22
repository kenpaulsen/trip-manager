package org.paulsens.trip.i18n;

import java.util.Locale;
import java.util.Map;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.model.Language;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@link Text} and its page face {@link Msg}, over the test-only {@code i18n/testwords} bundle. */
public class TextTest {

    @Test
    public void aKeyIsAnsweredInTheAskedLanguage() {
        Assert.assertEquals(Text.forLocale(Locale.ENGLISH, "testwords.greeting", "Lucy"), "Hello, Lucy");
        Assert.assertEquals(Text.forLocale(Language.Spanish.getLocale(), "testwords.greeting", "Lucy"), "Hola, Lucy");
        Assert.assertEquals(Text.forLocale(Language.Croatian.getLocale(), "testwords.greeting", "Lucy"), "Bok, Lucy");
        Assert.assertEquals(Text.forLocale(Locale.forLanguageTag("es-MX"), "testwords.greeting", "Lucy"),
                "Hola, Lucy", "a regional tag reads its language's bundle");
    }

    @Test
    public void bundlesAreReadAsUtf8() {
        Assert.assertEquals(Text.forLocale(Language.Croatian.getLocale(), "testwords.plain"),
                "Ovo je obična rečenica");
    }

    @Test
    public void aMissingTranslationFallsBackToEnglish() {
        Assert.assertEquals(Text.forLocale(Language.Spanish.getLocale(), "testwords.onlyEnglish"), "English only");
        Assert.assertEquals(Text.forLocale(Locale.GERMAN, "testwords.plain"), "It's a plain sentence",
                "a language the product does not speak reads English");
        Assert.assertEquals(Text.forLocale(null, "testwords.plain"), "It's a plain sentence");
    }

    @Test
    public void aMissingKeyIsAVisibleMarkerNeverAnException() {
        Assert.assertEquals(Text.forLocale(Locale.ENGLISH, "testwords.nope"), "???testwords.nope???");
        Assert.assertEquals(Text.forLocale(Locale.ENGLISH, "nosuchbundle.key"), "???nosuchbundle.key???");
        Assert.assertEquals(Text.forLocale(Locale.ENGLISH, "nodot"), "???nodot???");
        Assert.assertEquals(Text.forLocale(Locale.ENGLISH, null), "???null???");
        Assert.assertTrue(Text.has("testwords.plain"));
        Assert.assertFalse(Text.has("testwords.nope"));
    }

    @Test
    public void anArgumentFreeMessageKeepsItsApostrophe() {
        Assert.assertEquals(Text.forLocale(Locale.ENGLISH, "testwords.plain"), "It's a plain sentence");
    }

    @Test
    public void pluralsFollowTheLanguage() {
        Assert.assertEquals(Text.forLocale(Locale.ENGLISH, "testwords.days", 1), "1 day");
        Assert.assertEquals(Text.forLocale(Language.Spanish.getLocale(), "testwords.days", 2), "2 días");
        Assert.assertEquals(Text.forLocale(Language.Croatian.getLocale(), "testwords.days", 3), "3 dana");
        Assert.assertEquals(Text.forLocale(Language.Croatian.getLocale(), "testwords.days", 21), "21 dan");
    }

    @Test
    public void offARequestTheLanguageIsEnglishAndOnOneItIsTheRequests() {
        Assert.assertEquals(Text.get("testwords.greeting", "Ken"), "Hello, Ken");
        final RequestContext spanish = RequestContext.system().withLocale(Language.Spanish.getLocale());
        Assert.assertEquals(ScopedValue.where(RequestContext.SCOPE, spanish)
                .call(() -> Text.get("testwords.greeting", "Ken")), "Hola, Ken");
    }

    @Test
    public void thePagesBundleFollowsTheRequestNotTheInstance() {
        final Msg msg = new Msg();
        Assert.assertEquals(msg.getString("testwords.plain"), "It's a plain sentence");
        final RequestContext croatian = RequestContext.system().withLocale(Language.Croatian.getLocale());
        Assert.assertEquals(ScopedValue.where(RequestContext.SCOPE, croatian)
                .call(() -> msg.getString("testwords.greeting")), "Bok, {0}", "the same instance, another request");
        Assert.assertEquals(msg.getString("testwords.nope"), "???testwords.nope???");
        Assert.assertTrue(msg.containsKey("anything.at.all"));
        Assert.assertFalse(msg.containsKey(null));
        Assert.assertFalse(msg.getKeys().hasMoreElements());
    }

    @Test
    public void aBundlesPatternsAreRawAndFallBackToEnglishKeyByKey() {
        final Map<String, String> spanish = Text.patterns("testwords", Language.Spanish.getLocale());
        Assert.assertEquals(spanish.get("testwords.greeting"), "Hola, {0}", "the pattern, not a formatted message");
        Assert.assertEquals(spanish.get("testwords.onlyEnglish"), "English only", "an untranslated key reads English");
        Assert.assertEquals(Text.patterns("testwords", Locale.ENGLISH).get("testwords.plain"), "It's a plain sentence");
        Assert.assertTrue(Text.patterns("noSuchBundle", Locale.ENGLISH).isEmpty());
    }
}
