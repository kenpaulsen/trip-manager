package org.paulsens.trip.action;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link ThemeColors}: the catalog the editor's strip, its "More" list and the stylesheet guide all draw from.
 * That every compiled palette declares each name is checked against the real themes by the webtest
 * {@code ThemeContrastPwIT}; this pins the catalog's own shape.
 */
public class ThemeColorsTest {

    private static final Pattern PROPERTY = Pattern.compile("--[a-z]+(-[a-z0-9]+)*");

    private final ThemeColors colors = new ThemeColors();

    @Test
    public void everyEntryIsAUniqueWellFormedPropertyWithWords() {
        final Set<String> names = new HashSet<>();
        for (final ThemeColors.ThemeColor color : colors.getAll()) {
            Assert.assertTrue(PROPERTY.matcher(color.name()).matches(), color.name());
            Assert.assertTrue(names.add(color.name()), "listed twice: " + color.name());
            Assert.assertFalse(color.label().isBlank(), color.name());
            Assert.assertFalse(color.hint().isBlank(), color.name());
        }
        Assert.assertFalse(names.contains("--text-secondary-color"), "Freya has no such property");
        Assert.assertFalse(names.contains("--text-color-secondary"),
                "off limits for new work (owner, 2026-11-15): about 2.1:1 on the platform default's light cards");
    }

    @Test
    public void theStripHoldsTheEverydayColors() {
        Assert.assertEquals(colors.getStrip().stream().map(ThemeColors.ThemeColor::name).toList(), List.of(
                "--text-color", "--primary-color", "--primary-color-text",
                "--surface-card", "--surface-ground", "--surface-border", "--status-danger"));
    }

    @Test
    public void theGroupsCoverTheCatalogInOrder() {
        final List<ThemeColors.Group> groups = colors.getGroups();
        Assert.assertEquals(groups.stream().map(ThemeColors.Group::name).toList(),
                List.of("Text", "Accent", "Surfaces", "Status"));
        Assert.assertEquals(groups.stream().mapToInt(group -> group.colors().size()).sum(), colors.getAll().size(),
                "every color sits in exactly one listed group");
        Assert.assertEquals(groups.get(3).colors().size(), 16, "four statuses, four properties each");
    }
}
