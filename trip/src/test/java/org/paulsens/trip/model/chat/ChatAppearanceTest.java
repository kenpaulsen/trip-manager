package org.paulsens.trip.model.chat;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Validation and merging of the chat background.
 *
 * <p>Both values are typed by a person and end up inside a {@code style} attribute, so this is the boundary that
 * keeps CSS injection and stored XSS out.
 *
 * <p>The other half is exclusivity: a colour and an image cannot both be set. An image covers the pane, so a
 * colour under one is a setting that silently does nothing, and the person who chose it cannot see what is
 * overriding them. That is what these tests pin, including the case that made it visible — the site-wide default
 * image being laid over a colour someone had just picked.
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
    public void aColourAndAnImageCannotBothBeSet() {
        final ChatAppearance both = new ChatAppearance("#101020", "https://example.com/trip.jpg");
        Assert.assertEquals(both.getBackgroundColor(), "#101020");
        Assert.assertNull(both.getBackgroundImageUrl(),
                "an image over a chosen colour makes the colour invisible, with nothing on screen to explain it");
    }

    @Test
    public void anImageAloneIsStillAnImage() {
        // The exclusivity must not amount to "images are ignored".
        final ChatAppearance image = new ChatAppearance(null, "https://example.com/trip.jpg");
        Assert.assertEquals(image.getBackgroundImageUrl(), "https://example.com/trip.jpg");
        Assert.assertNull(image.getBackgroundColor());
    }

    @Test
    public void anUnsafeColourDoesNotSuppressTheImage() {
        // Exclusivity keys off the colour that SURVIVED validation. Keying off the raw input would let a
        // rejected colour silently cancel a perfectly good image, leaving no background at all.
        final ChatAppearance look = new ChatAppearance("red;evil", "https://example.com/trip.jpg");
        Assert.assertNull(look.getBackgroundColor());
        Assert.assertEquals(look.getBackgroundImageUrl(), "https://example.com/trip.jpg");
    }

    @Test
    public void aMemberChoiceReplacesTheChannelsWholesale() {
        // Whole, not per field. Merging field by field inverts the person's intent in the case that matters:
        // choosing an image over a channel whose default is a colour would merge to colour-plus-image, the
        // colour would win, and their pick would do nothing at all.
        final ChatAppearance channel = new ChatAppearance("#ffffff", null);
        final ChatAppearance mine = new ChatAppearance(null, "https://example.com/mine.jpg");
        final ChatAppearance merged = ChatAppearance.effective(mine, channel);
        Assert.assertEquals(merged.getBackgroundImageUrl(), "https://example.com/mine.jpg");
        Assert.assertNull(merged.getBackgroundColor(), "the trip's colour must not survive under my image");
    }

    @Test
    public void noOverrideLeavesTheChannelDefault() {
        final ChatAppearance channel = new ChatAppearance(null, "https://example.com/trip.jpg");
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
    public void theChannelDefaultFollowsTheSameExclusivity() {
        // Held as two loose strings on ChatSettings rather than as a ChatAppearance, so the rule has to be
        // stated in both places -- otherwise an administrator's stored pair disagrees with what renders.
        final ChatSettings both = ChatSettings.builder()
                .backgroundColor("#eef2f7")
                .backgroundImageUrl("https://example.com/trip.jpg")
                .build();
        Assert.assertEquals(both.getBackgroundColor(), "#eef2f7");
        Assert.assertNull(both.getBackgroundImageUrl(), "the colour is kept and the image dropped");

        final ChatSettings imageOnly = ChatSettings.builder()
                .backgroundImageUrl("https://example.com/trip.jpg")
                .build();
        Assert.assertEquals(imageOnly.getBackgroundImageUrl(), "https://example.com/trip.jpg",
                "an image with no colour must survive");
    }

    @Test
    public void emptyIsEmpty() {
        Assert.assertTrue(ChatAppearance.NONE.isEmpty());
        Assert.assertTrue(new ChatAppearance("  ", "  ").isEmpty());
        Assert.assertFalse(new ChatAppearance("#fff", null).isEmpty());
    }
}
