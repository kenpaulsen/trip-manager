package org.paulsens.trip.model.chat;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Validation and merging of the chat background.
 *
 * <p>Both values are typed by a person and end up inside a {@code style} attribute, so this is the boundary that
 * keeps CSS injection and stored XSS out. The merge is tested per field because the useful case is overriding one
 * of the two — someone who wants the trip's photo behind their own choice of colour.
 */
public class ChatAppearanceTest {

    @DataProvider(name = "rejectedColors")
    public Object[][] rejectedColors() {
        return new Object[][] {
                {"red;background-image:url(http://evil/x)", "a second declaration smuggled in"},
                {"#fff\"onload=alert(1)", "an attribute break-out"},
                {"url(http://evil/x)", "a url() where a colour belongs"},
                {"expression(alert(1))", "legacy IE expression syntax"},
                {"#12345", "a hex that is neither 3 nor 6 digits"},
                {"rgba(0,0,0,0.5)", "a function call -- not accepted, parentheses are the risk"},
        };
    }

    @Test(dataProvider = "rejectedColors")
    public void unsafeColoursAreDropped(final String value, final String why) {
        Assert.assertNull(new ChatAppearance(value, null).getBackgroundColor(), "Should be dropped: " + why);
    }

    @Test
    public void ordinaryColoursSurvive() {
        Assert.assertEquals(new ChatAppearance("#eef2f7", null).getBackgroundColor(), "#eef2f7");
        Assert.assertEquals(new ChatAppearance("#ABC", null).getBackgroundColor(), "#abc");
        Assert.assertEquals(new ChatAppearance("lavender", null).getBackgroundColor(), "lavender");
    }

    @DataProvider(name = "rejectedUrls")
    public Object[][] rejectedUrls() {
        return new Object[][] {
                {"javascript:alert(1)", "the scheme this check exists for"},
                {"data:image/svg+xml;base64,AAAA", "data URLs can carry script in SVG"},
                {"/local/path.png", "no scheme at all"},
                {"https://x/a'),url(http://evil/x", "quote and parens break out of url('...')"},
                {"https://x/a b.png", "whitespace ends the token"},
        };
    }

    @Test(dataProvider = "rejectedUrls")
    public void unsafeUrlsAreDropped(final String value, final String why) {
        Assert.assertNull(new ChatAppearance(null, value).getBackgroundImageUrl(), "Should be dropped: " + why);
    }

    @Test
    public void ordinaryImageUrlsSurvive() {
        final String url = "https://files.visitqueenofpeace.com/chat/bg.jpg";
        Assert.assertEquals(new ChatAppearance(null, url).getBackgroundImageUrl(), url);
    }

    @Test
    public void theMemberOverridesPerFieldNotWholesale() {
        // The point of merging field by field: keep the trip's image, use my own colour.
        final ChatAppearance channel = new ChatAppearance("#ffffff", "https://example.com/trip.jpg");
        final ChatAppearance mine = new ChatAppearance("#101020", null);
        final ChatAppearance merged = ChatAppearance.effective(mine, channel);
        Assert.assertEquals(merged.getBackgroundColor(), "#101020", "My colour wins");
        Assert.assertEquals(merged.getBackgroundImageUrl(), "https://example.com/trip.jpg", "Trip image remains");
    }

    @Test
    public void noOverrideLeavesTheChannelDefault() {
        final ChatAppearance channel = new ChatAppearance("#ffffff", "https://example.com/trip.jpg");
        Assert.assertEquals(ChatAppearance.effective(null, channel), channel);
        Assert.assertEquals(ChatAppearance.effective(ChatAppearance.NONE, channel), channel);
    }

    @Test
    public void anUnsafeOverrideFallsBackRatherThanBreakingTheStyle() {
        // A rejected value must read as "not set", so the channel default still applies -- dropping to a broken
        // or empty style would let a bad value blank out the trip's look for that person.
        final ChatAppearance channel = new ChatAppearance("#ffffff", null);
        final ChatAppearance merged = ChatAppearance.effective(new ChatAppearance("red;evil", null), channel);
        Assert.assertEquals(merged.getBackgroundColor(), "#ffffff");
    }

    @Test
    public void emptyIsEmpty() {
        Assert.assertTrue(ChatAppearance.NONE.isEmpty());
        Assert.assertTrue(new ChatAppearance("  ", "  ").isEmpty());
        Assert.assertFalse(new ChatAppearance("#fff", null).isEmpty());
    }
}
