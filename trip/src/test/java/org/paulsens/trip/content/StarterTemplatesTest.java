package org.paulsens.trip.content;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Placeholder;
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
        for (final ContentTemplate template : StarterTemplates.all()) {
            final var tokens = ContentRenderer.tokenNames(template.getBody());
            final var declared = template.getPlaceholders().stream().map(Placeholder::getName).toList();
            Assert.assertEqualsNoOrder(declared.toArray(), tokens.toArray(),
                    "Body tokens and declared placeholders diverge in " + template.getId());
        }
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
