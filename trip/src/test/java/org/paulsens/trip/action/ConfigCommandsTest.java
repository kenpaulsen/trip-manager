package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.model.Config;
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
}
