package org.paulsens.trip.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.faces.application.ViewHandler;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.mockito.Mockito;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.jsf.TripViewHandler;
import org.paulsens.trip.model.Language;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@link TextCommands} ({@code #{text}}) and {@link TripViewHandler}: both read the REQUEST's language. */
public class TextCommandsTest {

    private final TextCommands text = new TextCommands();

    @Test
    public void offARequestEverythingIsEnglish() {
        Assert.assertEquals(text.getLanguageTag(), "en");
        Assert.assertEquals(text.getLanguage(), Language.English);
        Assert.assertEquals(text.format("testwords.greeting", "Lucy"), "Hello, Lucy");
        Assert.assertEquals(text.date(LocalDateTime.of(2026, 9, 3, 15, 5)), "Sep 3, 2026");
        Assert.assertEquals(text.number(1234), "1,234");
        Assert.assertEquals(text.getOffered(), List.of(Language.English), "the shipped default");
        Assert.assertFalse(text.isChoice(), "one language: no switcher");
    }

    @Test
    public void aSavedLanguageThisSiteLacksStaysInTheProfileMenu() {
        Assert.assertEquals(text.offeredWith("hr"), List.of(Language.English, Language.Croatian),
                "a choice made on another organization's site is shown, so a save here keeps it");
        Assert.assertEquals(text.offeredWith("en"), List.of(Language.English), "an offered one is not repeated");
        Assert.assertEquals(text.offeredWith(null), List.of(Language.English));
        Assert.assertEquals(text.offeredWith("xx"), List.of(Language.English));
    }

    @Test
    public void onARequestTheBeanAndTheViewFollowItsLanguage() {
        final RequestContext croatian = RequestContext.system().withLocale(Language.Croatian.getLocale());
        ScopedValue.where(RequestContext.SCOPE, croatian).run(this::assertCroatian);
    }

    private void assertCroatian() {
        final LocalDateTime when = LocalDateTime.of(2026, 9, 3, 15, 5);
        Assert.assertEquals(text.getLanguageTag(), "hr");
        Assert.assertEquals(text.getLanguage(), Language.Croatian);
        Assert.assertEquals(text.format("testwords.days", 3), "3 dana");
        Assert.assertEquals(text.time(when), "15:05");
        Assert.assertTrue(text.dateTime(when).contains("15:05"));
        Assert.assertTrue(text.longDate(when).contains("2026"));
        Assert.assertEquals(text.number(1234), "1.234");
        final TripViewHandler handler = new TripViewHandler(Mockito.mock(ViewHandler.class));
        Assert.assertEquals(handler.calculateLocale(null), Locale.forLanguageTag("hr"));
    }

    @Test
    public void aPagePatternIsEnglishAsBeforeAndTheReadersOwnElsewhere() {
        Assert.assertEquals(text.datePattern("yMMMd"), "MMM d, yyyy");
        Assert.assertEquals(text.datePattern("yMMdd"), "MM/dd/yyyy");
        Assert.assertEquals(text.datePatternOr("MMMdjm", "MMM d h:mm a"), "MMM d h:mm a");
        Assert.assertEquals(text.getHourFormat(), "12");
        Assert.assertEquals(text.getTimePickerPattern(), "hh:mm a");
        final RequestContext spanish = RequestContext.system().withLocale(Language.Spanish.getLocale());
        ScopedValue.where(RequestContext.SCOPE, spanish).run(this::assertSpanishPatterns);
    }

    private void assertSpanishPatterns() {
        Assert.assertEquals(text.datePattern("yMMMd"), "d MMM yyyy");
        Assert.assertEquals(text.datePattern("yMMdd"), "dd/MM/yyyy");
        Assert.assertEquals(text.datePatternOr("MMMdjm", "MMM d h:mm a"), "d MMM, H:mm");
        Assert.assertEquals(text.getHourFormat(), "24");
        Assert.assertEquals(text.getTimePickerPattern(), "HH:mm");
    }

    @Test
    public void theSwitchLinkIsThisPageWithOnlyItsLanguageChanged() {
        Assert.assertEquals(text.switchUrl("/index.jsf", null, "es"), "/index.jsf?lang=es");
        Assert.assertEquals(text.switchUrl("/trip/tripDetails.jsf", "trip=abc&lang=hr&edit=true", "es"),
                "/trip/tripDetails.jsf?trip=abc&edit=true&lang=es", "the old choice is replaced, the rest kept");
        Assert.assertEquals(text.switchUrl("/page.jsf", "lang=en", "hr"), "/page.jsf?lang=hr");
        Assert.assertEquals(text.switchUrl("/page.jsf", "mylang=1", "hr"), "/page.jsf?mylang=1&lang=hr");
        Assert.assertEquals(text.switchUrl("//evil.example/index.jsf", null, "es"), "/?lang=es",
                "a protocol-relative request URI never becomes a link to another host");
        Assert.assertEquals(text.switchUrl("/\\evil.example/x", "a=1", "es"), "/?a=1&lang=es");
    }

    @Test
    public void aMarkupMessageEscapesItsArgumentsAndAMissingArgumentPrintsNothing() {
        Assert.assertEquals(text.html("testwords.greeting", "<b>Lucy</b> & \"Co\" 's"),
                "Hello, &lt;b&gt;Lucy&lt;/b&gt; &amp; &quot;Co&quot; &#39;s");
        Assert.assertEquals(text.html("testwords.days", 3), "3 days", "a number still drives the plural");
        Assert.assertEquals(text.html("testwords.greeting", (Object) null), "Hello, ");
        Assert.assertEquals(text.format("testwords.greeting", (Object) null), "Hello, ",
                "never the word null: the concatenation this replaced printed nothing");
    }

    @Test
    public void aScriptReadsTheSharedWordsItNamesInItsReadersLanguage() throws Exception {
        final JsonNode english = new ObjectMapper().readTree(text.scriptMessages("common.cancel common.ok"));
        Assert.assertEquals(english.get("common.cancel").asText(), "Cancel");
        Assert.assertEquals(english.size(), 2, "only the words named: the js bundle is the pages repo's");
        final String words = "common.cancel common.ok";
        Assert.assertSame(text.scriptMessages(words), text.scriptMessages(words), "built once per language and list");
        final JsonNode spanish = new ObjectMapper().readTree(
                TextCommands.scriptJson(Language.Spanish.getLocale(), " common.cancel  nonsense common.noSuchKey "));
        Assert.assertEquals(spanish.get("common.cancel").asText(), "Cancelar");
        Assert.assertEquals(spanish.size(), 1, "a name that is no key is left out");
        Assert.assertEquals(TextCommands.scriptJson(Locale.ENGLISH, null), "{}");
    }

    @Test
    public void noMessageCanBreakOutOfTheScriptElement() throws Exception {
        final String hostile = "</script><!-- \"quoted\" & 'single'\u2028\u2029\n\\ \u0001";
        final String written = TextCommands.scriptString(hostile);
        final String inside = written.substring(1, written.length() - 1);
        Assert.assertFalse(inside.matches("(?s).*[<>&\"'\u2028\u2029\n\u0001].*"),
                "nothing the HTML parser or the string literal reads is left raw: " + written);
        Assert.assertEquals(new ObjectMapper().readTree(written).asText(), hostile, "and it is still the same text");
    }

    @Test
    public void aContextNeverCarriesANullLanguage() {
        Assert.assertEquals(RequestContext.system().locale(), Locale.ENGLISH);
        Assert.assertEquals(RequestContext.system().withLocale(null).locale(), Locale.ENGLISH);
        Assert.assertEquals(RequestContext.currentLocale(), Locale.ENGLISH);
    }
}
