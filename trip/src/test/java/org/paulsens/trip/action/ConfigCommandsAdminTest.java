package org.paulsens.trip.action;

import org.paulsens.trip.model.Config;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The admin-page half of {@link ConfigCommands}: saving settings by their parts, and the declared-settings
 * guard that keeps a typo'd key from being silently written and never read.
 */
public class ConfigCommandsAdminTest {

    private final ConfigCommands config = new ConfigCommands();

    /** The "Other settings" table resolves per request; row buttons decode by row position. */
    @Test
    public void getUnknownAnswersNameSortedRows() {
        Assert.assertTrue(config.saveNew("test.zz.unknown", "v", null, "d", "admin"));
        Assert.assertTrue(config.saveNew("test.aa.unknown", "v", null, "d", "admin"));
        final java.util.List<String> names = config.getUnknown().stream()
                .map(org.paulsens.trip.model.Config::getName).toList();
        Assert.assertTrue(names.indexOf("test.aa.unknown") < names.indexOf("test.zz.unknown"),
                "Unknown settings must come back in deterministic name order, saw: " + names);
    }

    @Test
    public void saveNewParsesItsTypeAndFallsBackToString() {
        Assert.assertTrue(config.saveNew("test.admin.str", "v", null, "d", "admin"), "blank type is STRING");
        Assert.assertTrue(config.saveNew("test.admin.int", "42", " int ", "d", "admin"),
                "type names are trimmed and case-insensitive");
        Assert.assertTrue(config.saveNew("test.admin.junk", "v", "not-a-type", "d", "admin"),
                "an unrecognised type falls back to STRING rather than failing the save");
    }

    @Test
    public void saveRefusesAValueThatDoesNotMatchItsDeclaredType() {
        Assert.assertFalse(config.save(new Config("test.admin.badint", "not-a-number", Config.Type.INT,
                null, null, null), "admin"),
                "a bad edit is refused at save time, not silently ignored on every later read");
        Assert.assertFalse(config.save(null, "admin"));
        Assert.assertFalse(config.save(new Config("  ", "v", Config.Type.STRING, null, null, null), "admin"));
    }

    /** Reading an UNDECLARED name warns and answers the zero value -- nothing will ever read a typo'd key. */
    @Test
    public void anUndeclaredNameReadsAsItsZeroValue() {
        Assert.assertEquals(config.getString("test.not.declared"), "");
        Assert.assertFalse(config.getBoolean("test.not.declared"));
        Assert.assertEquals(config.getInt("test.not.declared"), 0);
        Assert.assertEquals(config.getLong("test.not.declared"), 0L);
    }

    @Test
    public void theSectionsFeedThePropertySheet() {
        Assert.assertFalse(config.getSections().isEmpty());
    }
}
