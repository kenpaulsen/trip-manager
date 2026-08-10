package org.paulsens.trip.content;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.TemplateKind;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The starters must actually render: every declared placeholder appears in its body, and vice versa. */
public class StarterTemplatesTest {

    @Test
    public void allReturnsThreeFreshTemplates() {
        final List<ContentTemplate> first = StarterTemplates.all();
        Assert.assertEquals(first.stream().map(ContentTemplate::getId).toList(), StarterTemplates.IDS);
        // Fresh instances each call: templates are mutable, so shared statics would leak edits.
        Assert.assertNotSame(first.get(0), StarterTemplates.all().get(0));
    }

    @Test
    public void declaredPlaceholdersMatchBodyTokens() {
        // The body-token invariant only means something for STANDARD templates: containers have no body,
        // and programmatic templates' placeholders are their type's PROPERTIES, not body tokens.
        for (final ContentTemplate template : StarterTemplates.all()) {
            if (template.getKind() != TemplateKind.STANDARD) {
                continue;
            }
            final var tokens = ContentRenderer.tokenNames(template.getBody());
            final var declared = template.getPlaceholders().stream().map(Placeholder::getName).toList();
            Assert.assertEqualsNoOrder(declared.toArray(), tokens.toArray(),
                    "Body tokens and declared placeholders diverge in " + template.getId());
        }
    }

    @Test
    public void starterKindsAndShapes() {
        Assert.assertEquals(StarterTemplates.all().size(), StarterTemplates.IDS.size());
        final ContentTemplate container = byId(StarterTemplates.CONTAINER_ID);
        Assert.assertEquals(container.getKind(), TemplateKind.CONTAINER);
        Assert.assertNull(container.getMaxChildren(), "the generic container is unlimited");
        Assert.assertNull(container.getAllowedChildTemplateIds(), "and allows any non-container child");

        for (final String id : List.of(StarterTemplates.PILGRIMAGES_ID, StarterTemplates.PHOTO_ALBUMS_ID,
                StarterTemplates.FILE_ID)) {
            final ContentTemplate starter = byId(id);
            Assert.assertEquals(starter.getKind(), TemplateKind.PROGRAMMATIC, id);
            Assert.assertEquals(starter.getProgrammaticTypeId(), id, "starter id doubles as its type id");
            Assert.assertFalse(starter.getPlaceholders().isEmpty(),
                    "programmatic starters carry their type's properties for the dialog form: " + id);
        }
    }

    private static ContentTemplate byId(final String id) {
        return StarterTemplates.all().stream()
                .filter(t -> t.getId().equals(id))
                .findFirst().orElseThrow();
    }

    @Test
    public void imageStarterWidthIsOptional() {
        final ContentTemplate tpl = StarterTemplates.all().get(1);
        Assert.assertEquals(tpl.getId(), StarterTemplates.IMAGE_ID);
        final Map<String, String> values = new HashMap<>(Map.of(
                "imageUrl", "https://files.example.com/x.jpg", "altText", "X"));
        final ContentInstance unsized = new ContentInstance("c", "s", "C", tpl.getId(), 1, values,
                null, 0, 1, null, null);
        // Blank width renders width="" -- an invalid value the browser ignores, i.e. natural size.
        Assert.assertTrue(ContentRenderer.render(tpl, unsized).contains("width=\"\""),
                "blank width must not size the image");

        values.put("width", "212");
        final ContentInstance sized = new ContentInstance("c", "s", "C", tpl.getId(), 1, values,
                null, 0, 1, null, null);
        final String out = ContentRenderer.render(tpl, sized);
        Assert.assertTrue(out.contains("width=\"212\""), out);
        Assert.assertFalse(out.contains("{{"), "No tokens may leak: " + out);
    }

    @Test
    public void youTubeStarterRendersAnEmbed() {
        final ContentTemplate tpl = StarterTemplates.all().get(0);
        final ContentInstance filled = new ContentInstance("c", "s", "C", tpl.getId(), 1,
                new HashMap<>(Map.of("videoUrl", "https://youtu.be/abc123XYZ_-", "caption", "A <caption>")),
                null, 0, 1, null, null);
        final String out = ContentRenderer.render(tpl, filled);
        Assert.assertTrue(out.contains("https://www.youtube.com/embed/abc123XYZ_-"), out);
        Assert.assertTrue(out.contains("A &lt;caption&gt;"), out);
        Assert.assertFalse(out.contains("{{"), "No tokens may leak: " + out);
    }
}
