package org.paulsens.trip.action;

import java.util.List;
import java.util.Map;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Config;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PrivacySettings;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatAppearance;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
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
    public void theSwatchesAreTheChoicesCommaJoined() {
        // The color picker's swatches attribute takes one comma-separated string, not a list.
        set(KnownSettings.CHAT_BACKGROUND_COLORS.getName(), "#ffffff,#000000");
        Assert.assertEquals(chat.getBackgroundColorSwatches(), "#ffffff,#000000");
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
    public void theDefaultImageDoesNotCoverAChosenColour() {
        // The reported bug: picking a colour appeared to do nothing, because the site-wide default image was
        // still laid over it. The default fills in for "nothing chosen", not for "no image chosen".
        final Person.Id who = trip().getPeople().get(0);
        try {
            chooseAppearance(who, new ChatAppearance("#123456", null));
            final String style = chat.backgroundStyleForTrip(trip().getId(), who);
            Assert.assertTrue(style.contains("#123456"), "the chosen colour must be applied; got: " + style);
            Assert.assertFalse(style.contains("background-image"),
                    "no image may be drawn over a chosen colour; got: " + style);
        } finally {
            chooseAppearance(who, ChatAppearance.NONE);
        }
    }

    /** Writes a person's own background choice, which is what the settings dialog's Save does. */
    private void chooseAppearance(final Person.Id who, final ChatAppearance look) {
        final ChatChannel channel = chat.ensureChannel(trip().getId(), null);
        final ChatMembership row = chat.membershipRow(channel.getId(), who)
                .orElseGet(() -> ChatMembership.joining(channel.getId(), who, channel.getCreated()));
        DAO.getInstance().saveChatMembership(row.withAppearance(look));
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
    public void aDropdownEntryReadsAsPreferredPlusLastName() {
        final String json = chat.rosterJsonForTrip(trip().getId());
        final Person someone = FakeData.getFakePeople().get(0);
        Assert.assertTrue(json.contains(someone.getPreferredName() + " " + someone.getLast()),
                "the menu shows preferred and last name together; got: " + json);
    }

    @Test
    public void twoPeopleSharingANameGetTellableApartLabels() {
        // Not cosmetic. The composer inserts the label as the visible token and swaps tokens back to @{id} by
        // exact text replacement, so two people with one label do not read as ambiguous -- one of them is
        // mentioned twice and the other never, and the wrong person is mailed.
        final List<Person> twins = List.of(
                person("Mary", "Smith", "mary.smith@example.com"),
                person("Mary", "Smith", "m.smith@example.com"));
        final List<String> labels = List.copyOf(ChatCommands.uniqueLabels(twins).values());
        Assert.assertEquals(labels.size(), 2);
        Assert.assertNotEquals(labels.get(0), labels.get(1), "identical labels mention the wrong person");
        Assert.assertTrue(labels.get(0).contains("mary.smith@example.com"),
                "email is what separates them, since it is unique system-wide; got: " + labels);
    }

    @Test
    public void anEmailIsShownOnlyWhereItIsNeededToDisambiguate() {
        // A label is a token typed into a message. Putting an address on every entry would broadcast the whole
        // trip's emails into the chat log to solve a problem most trips do not have.
        final List<Person> distinct = List.of(
                person("Mary", "Smith", "mary@example.com"),
                person("John", "Doe", "john@example.com"));
        for (final String label : ChatCommands.uniqueLabels(distinct).values()) {
            Assert.assertFalse(label.contains("@"), "unambiguous names must not carry an email: " + label);
        }
    }

    @Test
    public void aPrivateEmailIsNeverUsedToDisambiguate() {
        // Sharing a name with someone must not cost you your email privacy: the private twin falls to the id
        // rung, which is unique by construction and discloses nothing.
        final Person open = person("Mary", "Smith", "mary.smith@example.com");
        final Person closed = person("Mary", "Smith", "m.smith@example.com");
        closed.getPrivacy().setEmail(PrivacySettings.Visibility.PRIVATE);
        final Map<Person.Id, String> labels = ChatCommands.uniqueLabels(List.of(open, closed));
        Assert.assertTrue(labels.get(open.getId()).contains("mary.smith@example.com"));
        Assert.assertFalse(labels.get(closed.getId()).contains("@"),
                "a private email leaked into a roster label: " + labels.get(closed.getId()));
        Assert.assertNotEquals(labels.get(open.getId()), labels.get(closed.getId()),
                "labels must still be unique");
    }

    private static Person person(final String preferred, final String last, final String email) {
        final Person person = new Person();
        person.setNickname(preferred);
        person.setLast(last);
        person.setEmail(email);
        return person;
    }

    @Test
    public void anUnknownTripHasAnEmptyRoster() {
        Assert.assertEquals(chat.rosterJsonForTrip("no-such-trip"), "[]");
        Assert.assertEquals(chat.rosterJsonForTrip(null), "[]");
    }
}
