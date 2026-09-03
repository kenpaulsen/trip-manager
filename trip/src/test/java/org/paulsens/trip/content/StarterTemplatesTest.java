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

    /**
     * The installer writes rows in {@link StarterTemplates#IDS} order and a container's allow-list must name
     * ids that exist by then, so every leaf a band admits comes before that band.
     */
    @Test
    public void childrenAreListedBeforeTheContainersThatAdmitThem() {
        for (final ContentTemplate template : StarterTemplates.all()) {
            if (template.getKind() != TemplateKind.CONTAINER || template.getAllowedChildTemplateIds() == null) {
                continue;
            }
            for (final String childId : template.getAllowedChildTemplateIds()) {
                Assert.assertTrue(StarterTemplates.IDS.indexOf(childId) >= 0, childId + " is not a starter");
                Assert.assertTrue(
                        StarterTemplates.IDS.indexOf(childId) < StarterTemplates.IDS.indexOf(template.getId()),
                        childId + " must be installed before " + template.getId());
            }
        }
    }

    /**
     * The band family as a whole: bodies validate, every STANDARD band but the hero takes a tone, container
     * bands place their own heading and delimit a row with a child slot, and the allow-lists and cap are
     * what the seeder relies on.
     */
    @Test
    public void bandStartersAreWellFormed() {
        final List<String> standardBands = List.of(StarterTemplates.BAND_SPLIT_ID, StarterTemplates.BAND_CTA_ID,
                StarterTemplates.BAND_TESTIMONIAL_ID, StarterTemplates.BAND_TEXT_ID);
        for (final String id : standardBands) {
            final ContentTemplate band = byId(id);
            Assert.assertEquals(band.getKind(), TemplateKind.STANDARD, id);
            Assert.assertNull(HtmlFragmentValidator.validate(band.getBody()), id + " body must validate");
            Assert.assertTrue(band.getPlaceholders().stream().anyMatch(ph -> ph.getName().equals("tone")),
                    id + " takes a tone");
            Assert.assertTrue(band.getBody().contains("band-{{tone}}"), id + " renders the tone as a class");
        }
        Assert.assertNull(HtmlFragmentValidator.validate(byId(StarterTemplates.BAND_HERO_ID).getBody()));
        Assert.assertTrue(byId(StarterTemplates.BAND_HERO_ID).getPlaceholders().stream()
                .noneMatch(ph -> ph.getName().equals("tone")), "the hero always wears its own look");
        for (final String id : List.of(StarterTemplates.FEATURE_CARD_ID, StarterTemplates.STAT_ITEM_ID)) {
            Assert.assertEquals(byId(id).getKind(), TemplateKind.STANDARD, id);
            Assert.assertNull(HtmlFragmentValidator.validate(byId(id).getBody()), id);
        }

        final List<String> containers = List.of(StarterTemplates.BAND_FEATURES_ID, StarterTemplates.BAND_STATS_ID,
                StarterTemplates.BAND_FAQ_ID, StarterTemplates.BAND_LOGOS_ID);
        for (final String id : containers) {
            final ContentTemplate band = byId(id);
            Assert.assertEquals(band.getKind(), TemplateKind.CONTAINER, id);
            Assert.assertNull(HtmlFragmentValidator.validate(band.getBody()), id + " body must validate");
            Assert.assertNull(ContentRenderer.containerBodyProblem(band.getBody()), id + " is a usable row");
            Assert.assertTrue(ContentRenderer.hasContainerTitleSlot(band.getBody()), id + " places its title");
            Assert.assertFalse(ContentRenderer.containerBody(band.getBody()).beforeAll().isEmpty(),
                    id + " wraps its children once");
            Assert.assertEquals(band.getAllowedChildTemplateIds().size(), 1,
                    id + " admits exactly one leaf, so Add skips the picker");
        }
        Assert.assertEquals(byId(StarterTemplates.BAND_FEATURES_ID).getAllowedChildTemplateIds(),
                List.of(StarterTemplates.FEATURE_CARD_ID));
        Assert.assertEquals(byId(StarterTemplates.BAND_STATS_ID).getAllowedChildTemplateIds(),
                List.of(StarterTemplates.STAT_ITEM_ID));
        Assert.assertEquals(byId(StarterTemplates.BAND_STATS_ID).getMaxChildren(),
                Integer.valueOf(StarterTemplates.STATS_MAX));
        Assert.assertEquals(byId(StarterTemplates.BAND_FAQ_ID).getAllowedChildTemplateIds(),
                List.of(StarterTemplates.TEXT_ONLY_ID), "a question is a titled Text Only item");
        Assert.assertEquals(byId(StarterTemplates.BAND_LOGOS_ID).getAllowedChildTemplateIds(),
                List.of(StarterTemplates.IMAGE_ID));
        Assert.assertTrue(byId(StarterTemplates.BAND_FEATURES_ID).getBody().contains("{{child:title}}"),
                "a feature card's heading is its title, written by the row");
        Assert.assertTrue(byId(StarterTemplates.BAND_FAQ_ID).getBody().contains("<summary class=\"faqQuestion\">"
                + "{{child:title}}</summary>"), "a question is the child's title");
        for (final ContentTemplate starter : StarterTemplates.all()) {
            Assert.assertFalse(starter.getName().toLowerCase().contains("pilgrimage")
                    || starter.getDescription().toLowerCase().contains("pilgrimage"),
                    "product text says trip, never pilgrimage: " + starter.getId());
        }
    }

    /** Blank optional parts leave empty elements for the stylesheet to hide -- never a stray "{{". */
    @Test
    public void theHeroRendersWithBlankButtonsAndPicture() {
        final ContentTemplate hero = byId(StarterTemplates.BAND_HERO_ID);
        final ContentInstance sparse = new ContentInstance("h", "s", "Hero", hero.getId(), 1,
                new HashMap<>(Map.of("headline", "Trips & more")), null, 0, 1, null, null);
        final String out = ContentRenderer.render(hero, sparse);
        Assert.assertTrue(out.contains("<h1 class=\"hero-headline\">Trips &amp; more</h1>"), out);
        Assert.assertTrue(out.contains("<a class=\"cta cta-primary\" href=\"\"></a>"),
                "a blank button is an EMPTY anchor, which :empty hides: " + out);
        Assert.assertTrue(out.contains("<a class=\"cta cta-secondary\" href=\"\"></a>"), out);
        Assert.assertTrue(out.contains("<img src=\"\" alt=\"\""), "a blank picture is img[src=\"\"]: " + out);
        Assert.assertTrue(out.contains("<div class=\"eyebrow\"></div>"), out);
        Assert.assertFalse(out.contains("{{"), "No tokens may leak: " + out);

        final ContentInstance full = new ContentInstance("h", "s", "Hero", hero.getId(), 1,
                new HashMap<>(Map.of("headline", "H", "primaryText", "Go", "primaryUrl", "/account/x.jsf",
                        "secondaryText", "More", "secondaryUrl", "#benefits")), null, 0, 1, null, null);
        final String linked = ContentRenderer.render(hero, full);
        Assert.assertTrue(linked.contains("href=\"/account/x.jsf\">Go</a>"), "site-relative links render: " + linked);
        Assert.assertTrue(linked.contains("href=\"#benefits\">More</a>"), "anchors render: " + linked);
    }

    /** An icon is a CLASS NAME from a text prompt: it is escaped like any text, so it can never break out. */
    @Test
    public void iconsAndTonesAreEscapedClassNames() {
        final ContentTemplate card = byId(StarterTemplates.FEATURE_CARD_ID);
        final ContentInstance hostile = new ContentInstance("c", "s", "Card", card.getId(), 1,
                new HashMap<>(Map.of("icon", "pi-globe\"><script>x</script>", "text", "<p>t</p>")),
                null, 0, 1, null, null);
        final String out = ContentRenderer.render(card, hostile);
        Assert.assertFalse(out.contains("<script>"), out);
        Assert.assertTrue(out.contains("class=\"feature-icon pi pi-globe&quot;&gt;&lt;script&gt;"), out);

        final ContentTemplate split = byId(StarterTemplates.BAND_SPLIT_ID);
        final ContentInstance toned = new ContentInstance("b", "s", "Split", split.getId(), 1,
                new HashMap<>(Map.of("tone", "dark", "side", "right", "heading", "H", "body", "<p>b</p>")),
                null, 0, 1, null, null);
        final String band = ContentRenderer.render(split, toned);
        Assert.assertTrue(band.contains("class=\"band band-dark\""), band);
        Assert.assertTrue(band.contains("class=\"band-inner split split-right\""), band);
        Assert.assertTrue(band.contains("data-icon=\"\""), "a blank icon is detectable by the stylesheet");
        Assert.assertFalse(band.contains("{{"), band);
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
