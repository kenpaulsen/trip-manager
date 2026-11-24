package org.paulsens.trip.action;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.content.ReminderDefaults;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/** The reminder wording: the token vocabulary, the unknown-token check, and both renderings. */
public class ReminderTextTest {

    @Test
    public void unknownTokensAreFoundOnceEachInOrder() {
        assertTrue(ReminderText.unknownTokens(null).isEmpty());
        assertTrue(ReminderText.unknownTokens("Hi {{firstName}}, {{ tripTitle }}").isEmpty());
        assertEquals(ReminderText.unknownTokens("{{nope}} {{firstName}} {{other}} {{nope}}"),
                List.of("nope", "other"));
    }

    @Test
    public void theBodyIsEscapedAndTheSubjectIsNot() {
        final Map<String, String> values = Map.of(ReminderText.TRIP_TITLE, "Smith & Sons <Rome>",
                ReminderText.PAYMENT_LINK, "https://x.test/pay?a=1&b=2");
        assertEquals(ReminderText.render("<b>{{tripTitle}}</b> <a href=\"{{paymentLink}}\">pay</a>", values),
                "<b>Smith &amp; Sons &lt;Rome&gt;</b> <a href=\"https://x.test/pay?a=1&amp;b=2\">pay</a>");
        assertEquals(ReminderText.renderPlain("About {{tripTitle}}", values), "About Smith & Sons <Rome>");
    }

    @Test
    public void aKnownTokenWithNoValueIsEmptyAndAnUnknownOneIsLeftAsTyped() {
        assertEquals(ReminderText.render("[{{balanceDue}}] {{typo}} $1", Map.of()), "[] {{typo}} $1");
        assertEquals(ReminderText.render("[{{balanceDue}}]", null), "[]");
        assertEquals(ReminderText.render(null, Map.of()), "");
        assertEquals(ReminderText.renderPlain(null, null), "");
    }

    @Test
    public void everyTokenHasHelpMarkupAndASampleValue() {
        final Map<String, String> samples = ReminderText.sampleValues();
        for (final ReminderText.Token token : ReminderText.TOKENS) {
            assertFalse(token.getHelp().isBlank());
            assertEquals(token.getMarkup(), "{{" + token.getName() + "}}");
            assertNotNull(samples.get(token.getName()), token.getName());
        }
        assertEquals(samples.size(), ReminderText.TOKENS.size());
    }

    /** The editor's token help was English sentences in the class: a Spanish or Croatian facilitator read English. */
    @Test
    public void tokenHelpIsReadInTheReadersLanguage() {
        for (final ReminderText.Token token : ReminderText.TOKENS) {
            final String english = token.getHelp();
            assertFalse(english.startsWith("???"), token.getName() + " has no English help");
            for (final String reader : List.of("es", "hr")) {
                final String help = helpFor(reader, token);
                assertFalse(help.isBlank() || help.startsWith("???"), token.getName() + " in " + reader);
                assertFalse(help.equals(english), token.getName() + " is still English for " + reader);
            }
        }
        assertEquals(ReminderText.TOKENS.get(0).getHelp(), "The first name of the person receiving the message.");
        assertEquals(helpFor("es", ReminderText.TOKENS.get(0)), "El nombre de la persona que recibe el mensaje.");
    }

    private static String helpFor(final String tag, final ReminderText.Token token) {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.system().withLocale(Locale.forLanguageTag(tag)))
                .call(token::getHelp);
    }

    @Test
    public void theStarterTextsUseOnlyKnownTokens() {
        for (final ReminderDefaults.Text text : ReminderDefaults.TEXTS) {
            assertTrue(ReminderText.unknownTokens(text.subject() + text.body()).isEmpty(), text.id());
            assertFalse(text.description().isBlank());
        }
        for (final ReminderDefaults.Suggestion suggestion : ReminderDefaults.SUGGESTIONS) {
            assertNotNull(ReminderDefaults.text(suggestion.templateId()), suggestion.name());
        }
        assertNull(ReminderDefaults.text("no-such-id"));
    }
}
