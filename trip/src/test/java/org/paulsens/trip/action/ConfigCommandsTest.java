package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.model.Config;
import org.paulsens.trip.model.SettingDef;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The defaulting contract is the point of these tests: a settings table that is empty, unreachable or holding a
 * malformed value must degrade to the caller's compiled-in default, never to an exception or a wrong-but-
 * plausible value (notably: a mistyped boolean must not read as false).
 */
public class ConfigCommandsTest {

    private final ConfigCommands config = new ConfigCommands();

    private static Config of(final String name, final String value, final Config.Type type) {
        return new Config(name, value, type, "test", LocalDateTime.now(), "tester");
    }

    private String saveAndName(final String value, final Config.Type type) {
        final String name = "test." + RandomData.genAlpha(10);
        Assert.assertTrue(config.save(of(name, value, type), "tester"), "save should succeed for " + value);
        return name;
    }

    @Test
    public void clearAllCachesSucceedsAndTheStoreSurvives() {
        Assert.assertTrue(config.clearAllCaches("tester"));
        // The clear drops only the cache namespace: a save right after must land and read back.
        final String name = saveAndName("after-clear", Config.Type.STRING);
        Assert.assertEquals(config.getString(name, null), "after-clear");
    }

    @Test
    public void unsetSettingsReturnTheDefault() {
        final String missing = "absent." + RandomData.genAlpha(10);
        Assert.assertEquals(config.getString(missing, "fallback"), "fallback");
        Assert.assertTrue(config.getBoolean(missing, true));
        Assert.assertFalse(config.getBoolean(missing, false));
        Assert.assertEquals(config.getInt(missing, 42), 42);
        Assert.assertEquals(config.getLong(missing, 7L), 7L);
    }

    @Test
    public void nullNameIsSafe() {
        Assert.assertEquals(config.getString(null, "fallback"), "fallback");
    }

    @Test
    public void storedValuesRoundTrip() {
        Assert.assertEquals(config.getString(saveAndName("hello", Config.Type.STRING), "nope"), "hello");
        Assert.assertEquals(config.getInt(saveAndName("123", Config.Type.INT), -1), 123);
        Assert.assertEquals(config.getLong(saveAndName("9999999999", Config.Type.LONG), -1L), 9999999999L);
    }

    @Test
    public void booleanAcceptsTheCommonSpellings() {
        for (final String yes : List.of("true", "TRUE", " yes ", "on", "1")) {
            Assert.assertTrue(config.getBoolean(saveAndName(yes, Config.Type.BOOLEAN), false), "for: " + yes);
        }
        for (final String no : List.of("false", "No", "off", "0")) {
            Assert.assertFalse(config.getBoolean(saveAndName(no, Config.Type.BOOLEAN), true), "for: " + no);
        }
    }

    @Test
    public void malformedValuesFallBackInsteadOfLying() {
        // Saved as STRING so validation allows it, then read as the wrong type: the default must win rather
        // than a mistyped boolean quietly reading as false.
        final String notABool = saveAndName("perhaps", Config.Type.STRING);
        Assert.assertTrue(config.getBoolean(notABool, true), "garbage must not read as false");
        Assert.assertFalse(config.getBoolean(notABool, false));

        final String notANumber = saveAndName("twelve", Config.Type.STRING);
        Assert.assertEquals(config.getInt(notANumber, 5), 5);
        Assert.assertEquals(config.getLong(notANumber, 5L), 5L);
    }

    @Test
    public void badValuesAreRefusedAtSaveTime() {
        Assert.assertFalse(config.save(of("bad." + RandomData.genAlpha(6), "maybe", Config.Type.BOOLEAN), "t"));
        Assert.assertFalse(config.save(of("bad." + RandomData.genAlpha(6), "12x", Config.Type.INT), "t"));
        Assert.assertFalse(config.save(of("bad." + RandomData.genAlpha(6), "1.5", Config.Type.LONG), "t"));
    }

    @Test
    public void aDeclaredSettingsOwnRulesApplyOnTheSitePageToo() {
        final String palette = KnownSettings.SITE_THEME_PALETTE.getName();
        final String logo = KnownSettings.SITE_LOGO_URL.getName();
        try {
            Assert.assertNull(config.rejection(of(palette, "", Config.Type.STRING)), "blank is unset");
            Assert.assertNull(config.rejection(of(palette, null, Config.Type.STRING)));
            Assert.assertNull(config.rejection(of(palette, " green ", Config.Type.STRING)));
            final String outside = config.rejection(of(palette, "pink", Config.Type.STRING));
            Assert.assertNotNull(outside, "a value outside the choices is refused with the choices named");
            Assert.assertTrue(outside.contains("avocado, blue, green"), outside);
            Assert.assertFalse(config.save(of(palette, "pink", Config.Type.STRING), "tester"));
            Assert.assertFalse(config.isValid(of(palette, "pink", Config.Type.STRING)));
            Assert.assertTrue(config.save(of(palette, "green", Config.Type.STRING), "tester"));

            Assert.assertNull(config.rejection(of(logo, "https://cdn.example/logo.png", Config.Type.STRING)));
            final String notUrl = config.rejection(of(logo, "javascript:alert(1)", Config.Type.STRING));
            Assert.assertNotNull(notUrl);
            Assert.assertTrue(notUrl.contains("http(s) URL"), notUrl);
            Assert.assertFalse(config.save(of(logo, "cdn.example/logo.png", Config.Type.STRING), "tester"));
            Assert.assertFalse(config.save(of(logo, "https://x.example/a b", Config.Type.STRING), "tester"));
            Assert.assertTrue(config.save(of(logo, "https://cdn.example/logo.png", Config.Type.STRING), "tester"));

            Assert.assertEquals(config.rejection(of(palette, "x", Config.Type.INT)), "'x' is not a valid INT.",
                    "the type check comes first and reads as before");
            Assert.assertNull(config.rejection(of("free." + RandomData.genAlpha(6), "anything", Config.Type.STRING)),
                    "an undeclared key has no declaration rules");
        } finally {
            config.save(of(palette, null, Config.Type.STRING), "tester");
            config.save(of(logo, null, Config.Type.STRING), "tester");
        }
    }

    @Test
    public void blankValueMeansUnsetAndIsAllowed() {
        final String name = "blank." + RandomData.genAlpha(8);
        Assert.assertTrue(config.save(of(name, "", Config.Type.INT), "tester"));
        Assert.assertEquals(config.getInt(name, 3), 3, "an unset value falls back to the default");
    }

    @Test
    public void namelessConfigIsRejected() {
        Assert.assertFalse(config.save(of(null, "x", Config.Type.STRING), "tester"));
        Assert.assertFalse(config.save(of("  ", "x", Config.Type.STRING), "tester"));
        Assert.assertFalse(config.save(null, "tester"));
    }

    @Test
    public void missingTypeDefaultsToString() {
        final Config noType = new Config("x", "y", null, null, null, null);
        Assert.assertEquals(noType.getType(), Config.Type.STRING);
    }

    @Test
    public void getAllIsSortedAndIncludesSavedRows() {
        final String name = saveAndName("v", Config.Type.STRING);
        final List<Config> all = config.getAll();
        Assert.assertTrue(all.stream().anyMatch(c -> name.equals(c.getName())), "saved row should be listed");
        final List<String> names = all.stream().map(Config::getName).toList();
        final List<String> sorted = names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        Assert.assertEquals(names, sorted, "getAll should be name-sorted");
    }

    // --- the registry-driven reads and the settings page's save path ---

    @Test
    public void declaredSettingsReadTheirDeclaredDefault() {
        // The point of the SettingDef overloads: the key and the default come from one declaration, so a call
        // site cannot quietly disagree with what the settings page shows as the default.
        Assert.assertEquals(config.getString(KnownSettings.CHAT_MAIL_FROM),
                KnownSettings.CHAT_MAIL_FROM.getDefaultValue());
        Assert.assertEquals(config.getInt(KnownSettings.CHAT_DIGEST_HOUR),
                KnownSettings.CHAT_DIGEST_HOUR.intDefault());
        Assert.assertEquals(config.getBoolean(KnownSettings.CHAT_MAIL_ENABLED),
                KnownSettings.CHAT_MAIL_ENABLED.booleanDefault());
    }

    @Test
    public void aClampedReadNeverReturnsAnUnusableNumber() {
        // Clamping rather than throwing, because these are read mid-render: an absurd number should produce the
        // nearest workable behaviour, not a 500 on whatever page reads it first.
        config.save(of(KnownSettings.CHAT_DIGEST_HOUR.getName(), "99", Config.Type.INT), "tester");
        Assert.assertEquals(config.getInt(KnownSettings.CHAT_DIGEST_HOUR, 0, 23), 23);
        config.save(of(KnownSettings.CHAT_DIGEST_HOUR.getName(), "-4", Config.Type.INT), "tester");
        Assert.assertEquals(config.getInt(KnownSettings.CHAT_DIGEST_HOUR, 0, 23), 0);
        config.save(of(KnownSettings.CHAT_DIGEST_HOUR.getName(), null, Config.Type.INT), "tester");
    }

    @Test
    public void knownValuesReportsUnsetAsBlankRatherThanAsTheDefault() {
        // Deliberate: pre-filling the page with defaults would make every visit a mass write of settings nobody
        // chose, after which the shipped default could never change under this deployment.
        config.save(of(KnownSettings.HOME_BANNER_TEXT.getName(), null, Config.Type.STRING), "tester");
        final Map<String, String> values = config.getKnownValues();
        Assert.assertEquals(values.get(KnownSettings.HOME_BANNER_TEXT.getName()), "");
        Assert.assertTrue(values.keySet().containsAll(
                KnownSettings.all().stream().map(SettingDef::getName).toList()),
                "every declared setting must have an entry, or the page renders an unbound field");
    }

    @Test
    public void savingTheKnownMapStoresOnlyWhatChanged() {
        final String name = KnownSettings.HOME_BANNER_TEXT.getName();
        config.save(of(name, "before", Config.Type.STRING), "tester");

        final Map<String, String> values = new HashMap<>(config.getKnownValues());
        Assert.assertTrue(config.saveKnown(values, "nobody"), "an unchanged map should save cleanly");
        Assert.assertEquals(config.getAll().stream().filter(c -> name.equals(c.getName())).findFirst()
                .orElseThrow().getModifiedBy(), "tester",
                "pressing Save without editing must not restamp every setting with a new modified-by");

        values.put(name, "after");
        Assert.assertTrue(config.saveKnown(values, "editor"));
        Assert.assertEquals(config.getString(KnownSettings.HOME_BANNER_TEXT), "after");
    }

    @Test
    public void blankingAFieldRestoresTheDeclaredDefault() {
        // This is what makes "blank means use the default" true rather than merely intended: the stored value
        // has to become null, not "".
        final String name = KnownSettings.CHAT_MAIL_FROM.getName();
        config.save(of(name, "someone@example.com", Config.Type.STRING), "tester");
        Assert.assertEquals(config.getString(KnownSettings.CHAT_MAIL_FROM), "someone@example.com");

        final Map<String, String> values = new HashMap<>(config.getKnownValues());
        values.put(name, "   ");
        Assert.assertTrue(config.saveKnown(values, "editor"));
        Assert.assertEquals(config.getString(KnownSettings.CHAT_MAIL_FROM),
                KnownSettings.CHAT_MAIL_FROM.getDefaultValue());
    }

    @Test
    public void aBadValueIsRefusedAndLeavesTheStoredOneAlone() {
        final String name = KnownSettings.CHAT_DIGEST_HOUR.getName();
        config.save(of(name, "9", Config.Type.INT), "tester");

        final Map<String, String> values = new HashMap<>(config.getKnownValues());
        values.put(name, "half past eight");
        Assert.assertFalse(config.saveKnown(values, "editor"), "an invalid value must report failure");
        Assert.assertEquals(config.getInt(KnownSettings.CHAT_DIGEST_HOUR), 9,
                "a rejected edit must not disturb the value already stored");
        config.save(of(name, null, Config.Type.INT), "tester");
    }

    @Test
    public void unknownListsOnlyRowsNothingReads() {
        final String stray = "legacy." + RandomData.genAlpha(8);
        config.save(of(stray, "x", Config.Type.STRING), "tester");
        config.save(of(KnownSettings.HOME_BANNER_TEXT.getName(), "shown", Config.Type.STRING), "tester");

        final List<String> unknown = config.getUnknown().stream().map(Config::getName).toList();
        Assert.assertTrue(unknown.contains(stray),
                "a leftover row must stay visible, or it sits in the table doing nothing and nobody notices");
        Assert.assertFalse(unknown.contains(KnownSettings.HOME_BANNER_TEXT.getName()),
                "a declared setting belongs in its property sheet, not in the leftovers");
    }

    @Test
    public void aNullEditMapIsSafe() {
        Assert.assertTrue(config.saveKnown(null, "editor"));
    }
}
