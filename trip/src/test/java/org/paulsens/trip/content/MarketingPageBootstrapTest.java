package org.paulsens.trip.content;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.TemplateKind;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The product's home page must stay coherent: the bootstrap script's payloads and the local seed both come
 * from here, and the page renders on a host anyone can reach.
 */
public class MarketingPageBootstrapTest {

    private static final Pattern DIGIT = Pattern.compile("[0-9]");

    private static Map<String, ContentTemplate> starters() {
        final Map<String, ContentTemplate> byId = new HashMap<>();
        StarterTemplates.all().forEach(t -> byId.put(t.getId(), t));
        return byId;
    }

    @Test
    public void theRowsFormOnePageOfBandsWithTheirChildrenUnderThem() {
        final List<ContentInstance> rows = MarketingPageBootstrap.rows(id -> 3);
        final Map<String, ContentTemplate> starters = starters();
        final Set<String> ids = new HashSet<>();
        final Map<String, Integer> lastPosition = new HashMap<>();
        for (final ContentInstance row : rows) {
            Assert.assertTrue(ids.add(row.getId()), "duplicate id: " + row.getId());
            Assert.assertTrue(starters.containsKey(row.getTemplateId()),
                    row.getTitle() + " must use a starter template: " + row.getTemplateId());
            Assert.assertEquals(row.getTemplateVersion(), 3, "pinned to the CURRENT template version");
            Assert.assertEquals(row.getVersion(), 0, "unsaved: the save path assigns v1");
            Assert.assertEquals(row.getModifiedBy(), MarketingPageBootstrap.SEED_AUTHOR);
            final int previous = lastPosition.getOrDefault(row.getSection(), -1);
            Assert.assertTrue(row.getPosition() > previous, "positions ascend within " + row.getSection());
            lastPosition.put(row.getSection(), row.getPosition());
            if (!OrgPageBootstrap.MARKETING_PAGE_KEY.equals(row.getSection())) {
                Assert.assertTrue(ids.contains(row.getSection()),
                        row.getTitle() + " must follow its parent: " + row.getSection());
            }
        }
        final List<ContentInstance> sections = rows.stream()
                .filter(row -> OrgPageBootstrap.MARKETING_PAGE_KEY.equals(row.getSection())).toList();
        Assert.assertEquals(sections.size(), 8, "hero, features, three splits, stats, faq, cta");
        for (int i = 0; i < sections.size(); i++) {
            Assert.assertEquals(sections.get(i).getPosition(), i, "page positions are 0..7 in order");
        }
        Assert.assertEquals(sections.get(0).getTemplateId(), StarterTemplates.BAND_HERO_ID);
        Assert.assertEquals(sections.get(sections.size() - 1).getTemplateId(), StarterTemplates.BAND_CTA_ID);
        Assert.assertEquals(sections.get(sections.size() - 1).getValues().get("tone"), "dark");
        Assert.assertTrue(rows.stream().noneMatch(
                row -> row.getTemplateId().equals(StarterTemplates.BAND_TESTIMONIAL_ID)
                        || row.getTemplateId().equals(StarterTemplates.BAND_LOGOS_ID)),
                "no customers and no logos are invented");
    }

    @Test
    public void childrenHonorTheirContainersAllowListsAndCaps() {
        final List<ContentInstance> rows = MarketingPageBootstrap.rows(id -> 1);
        final Map<String, ContentTemplate> starters = starters();
        final Map<String, ContentInstance> byId = new HashMap<>();
        rows.forEach(row -> byId.put(row.getId(), row));
        final Map<String, Integer> childCounts = new HashMap<>();
        for (final ContentInstance row : rows) {
            final ContentInstance parent = byId.get(row.getSection());
            if (parent == null) {
                continue;
            }
            final ContentTemplate container = starters.get(parent.getTemplateId());
            Assert.assertEquals(container.getKind(), TemplateKind.CONTAINER, parent.getTitle());
            Assert.assertTrue(container.getAllowedChildTemplateIds().contains(row.getTemplateId()),
                    parent.getTitle() + " does not admit " + row.getTemplateId());
            childCounts.merge(parent.getId(), 1, Integer::sum);
        }
        final ContentInstance stats = byId.get(MarketingPageBootstrap.idOf(MarketingPageBootstrap.STATS));
        Assert.assertEquals(childCounts.get(stats.getId()), Integer.valueOf(StarterTemplates.STATS_MAX));
        Assert.assertEquals(childCounts.get(MarketingPageBootstrap.idOf(MarketingPageBootstrap.FEATURES)),
                Integer.valueOf(6), "six feature cards");
        Assert.assertEquals(childCounts.get(MarketingPageBootstrap.idOf(MarketingPageBootstrap.FAQ)),
                Integer.valueOf(5), "five questions");
        Assert.assertTrue(rows.stream().filter(row -> row.getSection().equals(stats.getId()))
                .noneMatch(row -> DIGIT.matcher(row.getValues().get("value")).find()),
                "stats are phrases, never invented figures");
    }

    /** The same ids on every machine and every run: conditional puts, the seed and the hero's anchor agree. */
    @Test
    public void idsAreStableAndTheHeroPointsAtTheFeaturesBand() {
        final List<String> once = MarketingPageBootstrap.rows(id -> 1).stream().map(ContentInstance::getId).toList();
        final List<String> again = MarketingPageBootstrap.rows(id -> 9).stream().map(ContentInstance::getId).toList();
        Assert.assertEquals(again, once);
        Assert.assertEquals(MarketingPageBootstrap.idOf("hero"), MarketingPageBootstrap.idOf("hero"));
        Assert.assertNotEquals(MarketingPageBootstrap.idOf("hero"), MarketingPageBootstrap.idOf("cta"));
        final ContentInstance hero = MarketingPageBootstrap.rows(id -> 1).get(0);
        Assert.assertEquals(hero.getValues().get("secondaryUrl"),
                "#" + MarketingPageBootstrap.idOf(MarketingPageBootstrap.FEATURES),
                "the second button scrolls to the Features band, whose id is the page anchor");
    }

    /** Every value renders through the real templates: validates, escapes, and leaks no token. */
    @Test
    public void valuesRenderCleanlyThroughTheirTemplates() {
        final Map<String, ContentTemplate> starters = starters();
        for (final ContentInstance row : MarketingPageBootstrap.rows(id -> 1)) {
            final ContentTemplate template = starters.get(row.getTemplateId());
            for (final Map.Entry<String, String> value : row.getValues().entrySet()) {
                Assert.assertNull(HtmlFragmentValidator.validate(value.getValue()),
                        row.getTitle() + "." + value.getKey() + " must validate");
                Assert.assertFalse(value.getValue().contains("{{"), row.getTitle() + " carries a token");
                final Placeholder declared = template.getPlaceholders().stream()
                        .filter(ph -> ph.getName().equals(value.getKey())).findFirst().orElse(null);
                Assert.assertNotNull(declared, row.getTitle() + " sets an undeclared value: " + value.getKey());
                if (declared.getType() == Placeholder.Type.URL) {
                    Assert.assertEquals(ContentRenderer.requireLinkUrl(value.getValue()), value.getValue(),
                            row.getTitle() + "." + value.getKey() + " must be a usable link");
                }
                if (declared.getType() == Placeholder.Type.TEXT && value.getKey().equals("tone")) {
                    Assert.assertTrue(StarterTemplates.TONES.contains(value.getValue()), value.getValue());
                }
                Assert.assertFalse(value.getValue().toLowerCase().contains("pilgrimage"),
                        "product text says trip, never pilgrimage: " + row.getTitle());
            }
            for (final Placeholder ph : template.getPlaceholders()) {
                Assert.assertFalse(ph.isRequired() && !row.getValues().containsKey(ph.getName()),
                        row.getTitle() + " leaves required " + ph.getName() + " blank");
            }
            if (template.getKind() == TemplateKind.STANDARD) {
                final String out = ContentRenderer.render(template, row);
                Assert.assertFalse(out.contains("{{"), "No tokens may leak from " + row.getTitle() + ": " + out);
            }
        }
    }
}
