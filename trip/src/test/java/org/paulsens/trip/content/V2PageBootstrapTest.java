package org.paulsens.trip.content;

import java.util.HashSet;
import java.util.Set;
import org.paulsens.trip.model.ContentInstance;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The production page skeleton must stay coherent -- the bootstrap script's payloads come from here. */
public class V2PageBootstrapTest {

    @Test
    public void skeletonIsCoherent() {
        final var rows = V2PageBootstrap.rows();
        Assert.assertFalse(rows.isEmpty());
        final Set<String> ids = new HashSet<>();
        final Set<String> starterIds = new HashSet<>(StarterTemplates.IDS);
        int lastPosition = -1;
        for (final ContentInstance row : rows) {
            Assert.assertTrue(ids.add(row.getId()), "duplicate id: " + row.getId());
            Assert.assertEquals(row.getSection(), V2PageBootstrap.PAGE_KEY);
            Assert.assertTrue(starterIds.contains(row.getTemplateId()),
                    row.getId() + " must use a starter template: " + row.getTemplateId());
            Assert.assertTrue(row.getPosition() > lastPosition, "positions must ascend");
            lastPosition = row.getPosition();
        }
        // The anchor containers with their editor grants; docs is a File-only container by design (its
        // single effective choice makes the Add flow skip the template picker).
        Assert.assertTrue(ids.contains("events"));
        Assert.assertTrue(ids.contains("docs"));
        Assert.assertEquals(byId(rows, "events").getEditorPrivileges(), java.util.List.of("eventAdmin"));
        Assert.assertEquals(byId(rows, "docs").getEditorPrivileges(), java.util.List.of("mediaAdmin"));
        Assert.assertNull(byId(rows, "reflection").getEditorPrivileges());
        Assert.assertEquals(byId(rows, "docs").getAllowedChildTemplateIds(),
                java.util.List.of(StarterTemplates.FILE_ID));
        Assert.assertNull(byId(rows, "events").getAllowedChildTemplateIds());
        // Authored bodies must pass the same validator the save path enforces.
        for (final ContentInstance row : rows) {
            for (final String value : row.getValues().values()) {
                Assert.assertNull(HtmlFragmentValidator.validate(value), row.getId());
            }
        }
    }

    private static ContentInstance byId(final java.util.List<ContentInstance> rows, final String id) {
        return rows.stream().filter(r -> r.getId().equals(id)).findFirst().orElseThrow();
    }
}
