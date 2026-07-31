package org.paulsens.trip.action;

import java.util.List;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Config;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** The offered background colours, the default background image, and the mention roster. */
public class ChatAppearanceChoicesTest {

    private final ChatCommands chat = new ChatCommands(new ChatRateLimiter(new InMemoryCacheClient()));
    private final ConfigCommands config = new ConfigCommands();

    @BeforeClass
    void beforeClass() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    @AfterMethod
    void reset() {
        // These are global settings; leaving one set would silently steer every later test.
        set(KnownSettings.CHAT_BACKGROUND_COLORS.getName(), null);
        set(KnownSettings.CHAT_BACKGROUND_IMAGE.getName(), null);
    }

    private void set(final String name, final String value) {
        config.save(new Config(name, value, Config.Type.STRING, "test", null, null), "tester");
    }

    private static Trip trip() {
        return FakeData.getFakeTrips().get(0);
    }

    @Test
    public void theDefaultListIsOffered() {
        final List<String> choices = chat.getBackgroundColorChoices();
        Assert.assertFalse(choices.isEmpty(), "there must always be something to choose");
        Assert.assertTrue(choices.contains("#eef2f7"));
    }

    @Test
    public void theListIsConfigurable() {
        set(KnownSettings.CHAT_BACKGROUND_COLORS.getName(), " #ffffff , #000000 ,#ffffff");
        Assert.assertEquals(chat.getBackgroundColorChoices(), List.of("#ffffff", "#000000"),
                "trimmed, in order, and de-duplicated");
    }

    @Test
    public void anUnsafeEntryIsDroppedRatherThanOffered() {
        // The value ends up inside a style attribute. Offering something the validator will later reject would
        // give someone a swatch that silently does nothing.
        set(KnownSettings.CHAT_BACKGROUND_COLORS.getName(), "#ffffff,red;background:url(x),#123456");
        Assert.assertEquals(chat.getBackgroundColorChoices(), List.of("#ffffff", "#123456"));
    }

    @Test
    public void anEntirelyBadListStillLeavesSomethingUsable() {
        set(KnownSettings.CHAT_BACKGROUND_COLORS.getName(), ",,,");
        Assert.assertTrue(chat.getBackgroundColorChoices().isEmpty(),
                "an empty list is honest -- the dialog still offers 'use the trip's colour'");
    }

    @Test
    public void theDefaultBackgroundImageIsUsedWhenNobodyChoseOne() {
        final Person.Id who = trip().getPeople().get(0);
        final String style = chat.backgroundStyleForTrip(trip().getId(), who);
        Assert.assertTrue(style.contains("files.visitqueenofpeace.com/images/mary-link.jpg"),
                "expected the configured default image; got: " + style);
    }

    @Test
    public void theDefaultImageDoesNotLeakIntoTheSettingsDialog() {
        // appearanceForTrip fills the edit fields. Showing the default there would present it as the person's
        // own choice, and the next Save would make that true -- freezing them on today's default forever.
        final Person.Id who = trip().getPeople().get(0);
        Assert.assertNull(chat.appearanceForTrip(trip().getId(), who).getBackgroundImageUrl());
    }

    @Test
    public void aBlankDefaultImageMeansNoImage() {
        set(KnownSettings.CHAT_BACKGROUND_IMAGE.getName(), "");
        final Person.Id who = trip().getPeople().get(0);
        Assert.assertFalse(chat.backgroundStyleForTrip(trip().getId(), who).contains("background-image"));
    }

    @Test
    public void theRosterCarriesEveryNameFieldForMatching() {
        final String json = chat.rosterJsonForTrip(trip().getId());
        Assert.assertTrue(json.startsWith("["), "expected a JSON array, got: " + json);
        final Person someone = FakeData.getFakePeople().get(0);
        Assert.assertTrue(json.contains(someone.getLast().toLowerCase()),
                "the search field must include the last name so typing it finds the person");
    }

    @Test
    public void theRosterCannotBreakOutOfAScriptElement() {
        // These names go inside <script>. A literal </script> in one would end the block and turn the remainder
        // into markup -- valid JSON and stored XSS at the same time.
        Assert.assertFalse(chat.rosterJsonForTrip(trip().getId()).contains("<"),
                "'<' must be escaped as \\\\u003C before this reaches a script element");
    }

    @Test
    public void anUnknownTripHasAnEmptyRoster() {
        Assert.assertEquals(chat.rosterJsonForTrip("no-such-trip"), "[]");
        Assert.assertEquals(chat.rosterJsonForTrip(null), "[]");
    }
}
