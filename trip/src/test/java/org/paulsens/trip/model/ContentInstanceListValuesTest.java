package org.paulsens.trip.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The list view a MULTI_CHOICE prompt binds to: one comma-separated string underneath, nothing to sync. */
public class ContentInstanceListValuesTest {

    private static ContentInstance instance(final Map<String, String> values) {
        return new ContentInstance("i", "s", "t", "tpl", 1, new HashMap<>(values), null, 0, 0, null, "me");
    }

    @Test
    public void readsSplitAndWritesJoinIntoTheOneStoredValue() {
        final ContentInstance instance = instance(Map.of("includeOrgs", " a, b ,,c b ", "other", "x"));
        final Map<String, List<String>> view = instance.getListValues();
        Assert.assertEquals(view.get("includeOrgs"), List.of("a", "b", "c"));
        Assert.assertEquals(view.get("missing"), List.of());
        Assert.assertEquals(view.get(null), List.of());
        Assert.assertTrue(view.containsKey("other"));
        Assert.assertFalse(view.containsKey("missing"));
        Assert.assertEquals(view.size(), 2, "the view mirrors every stored value");

        Assert.assertEquals(view.put("includeOrgs", List.of("z", " y ", "z", "")), List.of("a", "b", "c"),
                "put answers the previous list");
        Assert.assertEquals(instance.getValues().get("includeOrgs"), "z,y", "stored joined, trimmed, deduped");
        view.put("includeOrgs", List.of());
        Assert.assertFalse(instance.getValues().containsKey("includeOrgs"), "an empty pick removes the value");
        view.put("includeOrgs", null);
        Assert.assertFalse(instance.getValues().containsKey("includeOrgs"));
        Assert.assertEquals(ContentInstance.joinList(null), "");
        Assert.assertEquals(ContentInstance.splitList(null), List.of());
    }

    @Test
    public void theViewIsNotPartOfTheRow() throws Exception {
        final ContentInstance instance = instance(Map.of("includeOrgs", "a"));
        final String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(instance);
        Assert.assertFalse(json.contains("listValues"), "computed view, never serialized");
        Assert.assertEquals(instance.copy().getListValues().get("includeOrgs"), List.of("a"));
        Assert.assertTrue(Placeholder.isProviderBacked(Placeholder.Type.MULTI_CHOICE));
        Assert.assertTrue(Placeholder.isProviderBacked(Placeholder.Type.CHOICE));
        Assert.assertFalse(Placeholder.isProviderBacked(Placeholder.Type.TEXT));
    }
}
