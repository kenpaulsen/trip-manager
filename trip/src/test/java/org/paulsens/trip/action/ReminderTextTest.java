package org.paulsens.trip.action;

import java.util.List;
import java.util.Map;
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
