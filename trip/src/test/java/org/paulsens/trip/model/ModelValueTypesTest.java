package org.paulsens.trip.model;

import java.time.Instant;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.paulsens.trip.model.chat.ChatAttachment;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatForwardRef;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatQuote;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The small value types: their generated {@code equals}/{@code hashCode} contracts, and the hand-written
 * behaviour that sits alongside them.
 *
 * <p>{@link EqualsVerifier} is used rather than hand-written equality tests because it checks the parts nobody
 * writes by hand -- symmetry, transitivity, the null contract, and consistency with {@code hashCode} -- and it
 * covers a lot of Lombok-generated boilerplate as a side effect. Where a type is deliberately not immutable the
 * relevant warning is suppressed rather than the check dropped.
 */
public class ModelValueTypesTest {

    @Test
    public void tripLinkHonoursTheEqualsContract() {
        EqualsVerifier.forClass(TripLink.class).verify();
    }

    @Test
    public void chatValueTypesHonourTheEqualsContract() {
        EqualsVerifier.forClass(ChatQuote.class).verify();
        EqualsVerifier.forClass(ChatAttachment.class).verify();
        EqualsVerifier.forClass(ChatForwardRef.class).verify();
    }

    @Test
    public void idWrappersHonourTheEqualsContract() {
        EqualsVerifier.forClass(Person.Id.class).verify();
        EqualsVerifier.forClass(DataId.class).verify();
        EqualsVerifier.forClass(ChatChannel.Id.class).verify();
        EqualsVerifier.forClass(ChatMessage.Id.class).suppress(Warning.NONFINAL_FIELDS).verify();
    }

    @Test
    public void compositeKeyHonoursTheEqualsContract() {
        EqualsVerifier.forClass(CompositeKey.class).verify();
    }

    // --- TripLink: the URL scheme guard ---

    /**
     * Only http and https. A {@code javascript:} URL in a trip link would be rendered into a page as an href,
     * which is a stored XSS with extra steps.
     */
    @Test
    public void aTripLinkUrlMustBeHttpOrHttps() {
        Assert.assertEquals(TripLink.validateUrlScheme("  https://example.org/x  "), "https://example.org/x");
        Assert.assertEquals(TripLink.validateUrlScheme("http://example.org"), "http://example.org");

        Assert.assertThrows(IllegalArgumentException.class,
                () -> TripLink.validateUrlScheme("javascript:alert(1)"));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> TripLink.validateUrlScheme("file:///etc/passwd"));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> TripLink.validateUrlScheme("example.org/no-scheme"));
        Assert.assertThrows(IllegalArgumentException.class, () -> TripLink.validateUrlScheme(null));
        Assert.assertThrows(IllegalArgumentException.class, () -> TripLink.validateUrlScheme("   "));
    }

    @Test
    public void aTripLinkDefaultsItsNameAndTarget() {
        final TripLink defaults = new TripLink(null, "https://example.org", null, null);

        Assert.assertEquals(defaults.getName(), "");
        Assert.assertEquals(defaults.getTarget(), "_blank", "links open in a new tab unless told otherwise");
        Assert.assertEquals(new TripLink("n", "u", "  ", null).getTarget(), "_blank", "blank is not a target");
        Assert.assertEquals(new TripLink("n", "u", "_self", null).getTarget(), "_self");
    }

    // --- ChatQuote: the snippet cut ---

    private static ChatMessage message(final String body) {
        return new ChatMessage(ChatMessage.Id.from("1"), ChatChannel.Id.forTrip("t1"),
                Person.Id.from("author"), Instant.now(), null, body, null, null, null, null, null, null,
                null, null, null);
    }

    @Test
    public void aShortQuoteIsNotTruncated() {
        final ChatQuote quote = ChatQuote.from(message("the bus leaves at 7"), "Author");

        Assert.assertEquals(quote.getSnippet(), "the bus leaves at 7");
        Assert.assertFalse(quote.isSnippetTruncated());
        Assert.assertEquals(quote.getAuthorName(), "Author");
    }

    /** Cut on a CODE POINT boundary: slicing by char index would split a surrogate pair. */
    @Test
    public void aLongQuoteIsCutOnACodePointBoundary() {
        final ChatQuote quote = ChatQuote.from(message("🙏".repeat(300)), "Author");

        Assert.assertTrue(quote.isSnippetTruncated());
        Assert.assertEquals(quote.getSnippet().codePointCount(0, quote.getSnippet().length()),
                ChatQuote.MAX_SNIPPET_CODE_POINTS);
        for (int i = 0; i < quote.getSnippet().length(); i++) {
            if (Character.isHighSurrogate(quote.getSnippet().charAt(i))) {
                Assert.assertTrue(i + 1 < quote.getSnippet().length()
                                && Character.isLowSurrogate(quote.getSnippet().charAt(i + 1)),
                        "a high surrogate at " + i + " lost its pair");
            }
        }
    }

    @Test
    public void quotingNothingIsRefusedAndAnEmptyBodyIsFine() {
        Assert.assertThrows(IllegalArgumentException.class, () -> ChatQuote.from(null, "Author"));
        Assert.assertEquals(ChatQuote.from(message(null), "Author").getSnippet(), "");
    }
}
