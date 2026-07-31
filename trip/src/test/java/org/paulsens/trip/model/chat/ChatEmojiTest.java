package org.paulsens.trip.model.chat;

import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Parsing an administrator-configured reaction palette.
 *
 * <p>The failure direction is what these pin down. An unusable setting must fall back to the built-in palette,
 * never to an empty one: an empty palette shows no picker and rejects every incoming reaction, which reads as
 * "reactions are broken" rather than as a typo in a setting.
 */
public class ChatEmojiTest {

    @Test
    public void aConfiguredPaletteReplacesTheBuiltInOne() {
        Assert.assertEquals(ChatEmoji.parsePalette("👍,🙏,✅"), List.of("👍", "🙏", "✅"));
    }

    @Test
    public void whitespaceAndEmptyEntriesAreIgnored() {
        Assert.assertEquals(ChatEmoji.parsePalette("  👍 , , 🙏 ,"), List.of("👍", "🙏"));
    }

    @Test
    public void anUnusableSettingFallsBackToTheBuiltInPalette() {
        Assert.assertEquals(ChatEmoji.parsePalette(null), ChatEmoji.palette());
        Assert.assertEquals(ChatEmoji.parsePalette(""), ChatEmoji.palette());
        Assert.assertEquals(ChatEmoji.parsePalette("   "), ChatEmoji.palette());
        Assert.assertEquals(ChatEmoji.parsePalette(" , , "), ChatEmoji.palette());
    }

    @Test
    public void anEmojiContainingAHashIsDropped() {
        // Reaction rows key on {msgId}#{personId}#{emoji}. An emoji carrying a '#' shifts the field boundaries,
        // so two different (person, emoji) pairs can land on one key and silently overwrite each other.
        Assert.assertEquals(ChatEmoji.parsePalette("👍,a#b,🙏"), List.of("👍", "🙏"));
        // ...and a palette that is ONLY bad entries must still leave a working picker.
        Assert.assertEquals(ChatEmoji.parsePalette("a#b"), ChatEmoji.palette());
    }

    @Test
    public void duplicatesCollapse() {
        // Two identical entries would render the picker twice and count as one reaction, which looks like a bug.
        Assert.assertEquals(ChatEmoji.parsePalette("👍,👍,🙏"), List.of("👍", "🙏"));
    }

    @Test
    public void matchingIsExactAgainstTheGivenPalette() {
        final List<String> palette = ChatEmoji.parsePalette("👍,❤️");
        Assert.assertTrue(ChatEmoji.isAllowed("👍", palette));
        // Bare U+2764 without the U+FE0F variation selector renders the same but is a different string, and
        // would otherwise be stored as a second distinct emoji that counts separately.
        Assert.assertFalse(ChatEmoji.isAllowed("❤", palette));
        Assert.assertFalse(ChatEmoji.isAllowed("🎉", palette));
        Assert.assertFalse(ChatEmoji.isAllowed(null, palette));
        Assert.assertFalse(ChatEmoji.isAllowed("👍", null));
    }
}
