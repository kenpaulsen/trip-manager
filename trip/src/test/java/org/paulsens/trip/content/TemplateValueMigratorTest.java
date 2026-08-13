package org.paulsens.trip.content;

import java.util.List;
import java.util.Map;
import org.paulsens.trip.model.Placeholder;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Moving an instance to another template version. The common case (names unchanged) must be lossless and
 * silent; a rename should be caught when the evidence is unambiguous; and anything genuinely unmatchable
 * must be REPORTED as dropped rather than quietly guessed onto the wrong field.
 */
public class TemplateValueMigratorTest {

    @Test
    public void unchangedNamesCarryEverythingWithNoReport() {
        final List<Placeholder> same = List.of(text("caption", "Caption"), text("width", "Width"));
        final TemplateValueMigrator.Result result =
                TemplateValueMigrator.migrate(same, same, Map.of("caption", "hi", "width", "60%"));

        Assert.assertEquals(result.values(), Map.of("caption", "hi", "width", "60%"));
        Assert.assertTrue(result.renamed().isEmpty());
        Assert.assertTrue(result.dropped().isEmpty());
    }

    @Test
    public void aNewPlaceholderArrivesDeclaredButEmpty() {
        final TemplateValueMigrator.Result result = TemplateValueMigrator.migrate(
                List.of(text("caption", "Caption")),
                List.of(text("caption", "Caption"), text("altText", "Alt text")),
                Map.of("caption", "hi"));

        Assert.assertEquals(result.values(), Map.of("caption", "hi", "altText", ""),
                "the dialog must render a field for every hole in the new version");
        Assert.assertTrue(result.dropped().isEmpty());
    }

    @Test
    public void aRenameThatKeptItsLabelIsFollowed() {
        final TemplateValueMigrator.Result result = TemplateValueMigrator.migrate(
                List.of(text("cap", "Caption")), List.of(text("caption", "Caption")), Map.of("cap", "hi"));

        Assert.assertEquals(result.values().get("caption"), "hi");
        Assert.assertEquals(result.renamed(), Map.of("cap", "caption"));
        Assert.assertTrue(result.dropped().isEmpty());
    }

    @Test
    public void aLoneSurvivorOfItsTypeIsPaired() {
        final TemplateValueMigrator.Result result = TemplateValueMigrator.migrate(
                List.of(text("caption", "Caption"), url("link", "Link URL")),
                List.of(text("caption", "Caption"), url("href", "Destination")),
                Map.of("caption", "hi", "link", "https://x.test"));

        Assert.assertEquals(result.values().get("href"), "https://x.test",
                "one unmatched URL on each side can only be the same hole renamed");
        Assert.assertEquals(result.renamed(), Map.of("link", "href"));
    }

    @Test
    public void anAmbiguousTypeMatchIsDroppedNotGuessed() {
        final TemplateValueMigrator.Result result = TemplateValueMigrator.migrate(
                List.of(text("one", "First"), text("two", "Second")),
                List.of(text("alpha", "A"), text("beta", "B")),
                Map.of("one", "1", "two", "2"));

        Assert.assertEquals(result.values(), Map.of("alpha", "", "beta", ""));
        Assert.assertEquals(result.dropped(), List.of("one", "two"));
        Assert.assertTrue(result.renamed().isEmpty(), "two candidates each way is not evidence");
    }

    @Test
    public void aDeletedPlaceholderIsReportedOnlyWhenItHeldSomething() {
        final TemplateValueMigrator.Result withValue = TemplateValueMigrator.migrate(
                List.of(text("caption", "Caption"), text("legacy", "Legacy")),
                List.of(text("caption", "Caption")), Map.of("caption", "hi", "legacy", "old text"));
        Assert.assertEquals(withValue.dropped(), List.of("legacy"));

        final TemplateValueMigrator.Result empty = TemplateValueMigrator.migrate(
                List.of(text("caption", "Caption"), text("legacy", "Legacy")),
                List.of(text("caption", "Caption")), Map.of("caption", "hi"));
        Assert.assertTrue(empty.dropped().isEmpty(), "an empty field loses nothing");
    }

    @Test
    public void nullsAndEmptiesAreHandled() {
        final TemplateValueMigrator.Result none = TemplateValueMigrator.migrate(null, null, null);
        Assert.assertTrue(none.values().isEmpty());
        Assert.assertTrue(none.dropped().isEmpty());

        final TemplateValueMigrator.Result fresh =
                TemplateValueMigrator.migrate(List.of(), List.of(text("a", "A")), Map.of());
        Assert.assertEquals(fresh.values(), Map.of("a", ""));
    }

    private static Placeholder text(final String name, final String label) {
        return new Placeholder(name, Placeholder.Type.TEXT, label, null, false);
    }

    private static Placeholder url(final String name, final String label) {
        return new Placeholder(name, Placeholder.Type.URL, label, null, false);
    }
}
