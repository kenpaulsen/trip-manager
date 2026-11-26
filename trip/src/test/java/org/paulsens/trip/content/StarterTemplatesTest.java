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
                StarterTemplates.FILE_ID, StarterTemplates.TRIP_SCHEDULE_ID, StarterTemplates.TRIP_REGISTRATION_ID)) {
            final ContentTemplate starter = byId(id);
            Assert.assertEquals(starter.getKind(), TemplateKind.PROGRAMMATIC, id);
            Assert.assertEquals(starter.getProgrammaticTypeId(), id, "starter id doubles as its type id");
            Assert.assertFalse(starter.getPlaceholders().isEmpty(),
                    "programmatic starters carry their type's properties for the dialog form: " + id);
        }
    }

    /** The plain names the Add dialog lists (2026-11): ids never change, the "Band:" prefix is gone. */
    @Test
    public void bandsCarryPlainNamesAndStayFindableByTheirOldWords() {
        final Map<String, String> names = Map.of(
                StarterTemplates.BAND_HERO_ID, "Opening banner",
                StarterTemplates.BAND_SPLIT_ID, "Text beside a picture",
                StarterTemplates.BAND_CTA_ID, "Call to action",
                StarterTemplates.BAND_TESTIMONIAL_ID, "Quote",
                StarterTemplates.BAND_TEXT_ID, "Heading and text",
                StarterTemplates.BAND_FEATURES_ID, "Feature cards",
                StarterTemplates.BAND_STATS_ID, "Key numbers",
                StarterTemplates.BAND_FAQ_ID, "Questions and answers",
                StarterTemplates.BAND_LOGOS_ID, "Logo strip");
        names.forEach((id, name) -> Assert.assertEquals(byId(id).getName(), name, id));
        for (final String id : names.keySet()) {
            final ContentTemplate band = byId(id);
            Assert.assertTrue(band.isFullWidth(), id + " is made for full-width pages");
            Assert.assertFalse(band.isChildOnly(), id + " is a section of its own");
            Assert.assertTrue(band.getTags().contains("full-width"), id + " " + band.getTags());
        }
        Assert.assertEquals(byId(StarterTemplates.BAND_HERO_ID).getTags(),
                List.of("hero", "header", "banner", "full-width"));
        Assert.assertTrue(byId(StarterTemplates.BAND_TESTIMONIAL_ID).getTags().contains("testimonial"));
        Assert.assertTrue(byId(StarterTemplates.BAND_CTA_ID).getTags().contains("cta"));
        Assert.assertTrue(byId(StarterTemplates.BAND_FAQ_ID).getTags().containsAll(List.of("faq", "group")));
        for (final ContentTemplate starter : StarterTemplates.all()) {
            Assert.assertFalse(starter.getName().startsWith("Band:"), starter.getId());
        }
    }

    /** What the Add dialog reads besides the name: tags on everything a page can hold, and where it fits. */
    @Test
    public void everyPageStarterIsTaggedAndOnlyTheBandLeavesAreChildOnly() {
        for (final ContentTemplate starter : StarterTemplates.all()) {
            final String id = starter.getId();
            if (starter.getKind() == TemplateKind.MAIL) {
                Assert.assertTrue(starter.getTags().isEmpty(), id + ": no Add dialog offers an email body");
                Assert.assertFalse(starter.isChildOnly() || starter.isFullWidth(), id);
                continue;
            }
            Assert.assertFalse(starter.getTags().isEmpty(), id + " has no search tag");
            for (final String tag : starter.getTags()) {
                Assert.assertEquals(tag, tag.trim().toLowerCase(), id + " tags are stored as they are searched");
            }
            Assert.assertEquals(starter.isChildOnly(),
                    List.of(StarterTemplates.FEATURE_CARD_ID, StarterTemplates.STAT_ITEM_ID).contains(id), id);
            Assert.assertEquals(starter.isFullWidth(), id.startsWith("band-"), id);
            Assert.assertFalse(starter.isChildOnly() && starter.getKind() == TemplateKind.CONTAINER, id);
        }
        Assert.assertEquals(byId(StarterTemplates.TEXT_ONLY_ID).getTags(), List.of("text"));
        Assert.assertEquals(byId(StarterTemplates.PHOTO_ALBUMS_ID).getTags(), List.of("photos", "trips"));
        // Every child-only leaf is admitted by some group, or nothing could ever add it.
        for (final String leaf : List.of(StarterTemplates.FEATURE_CARD_ID, StarterTemplates.STAT_ITEM_ID)) {
            Assert.assertTrue(StarterTemplates.all().stream()
                    .anyMatch(t -> t.getAllowedChildTemplateIds() != null
                            && t.getAllowedChildTemplateIds().contains(leaf)), leaf);
        }
    }

    @Test
    public void aGroupsSampleItemsHaveTitlesOfTheirOwn() {
        Assert.assertEquals(StarterTemplates.sampleChildTitles(StarterTemplates.BAND_FEATURES_ID).size(), 3);
        Assert.assertTrue(StarterTemplates.sampleChildTitles(StarterTemplates.BAND_FAQ_ID).get(0).endsWith("?"),
                "a question group's items are questions");
        Assert.assertEquals(StarterTemplates.sampleChildTitles("anything-else"),
                StarterTemplates.sampleChildTitles(null), "one generic list for every other group");
        Assert.assertEquals(StarterTemplates.sampleChildTitles(null).size(), 3);
        Assert.assertEquals(ContentRenderer.requireLinkUrl(StarterTemplates.SAMPLE_PICTURE),
                StarterTemplates.SAMPLE_PICTURE, "the sample picture is a site-relative path");
        Assert.assertEquals(ContentRenderer.requireHttpUrl(StarterTemplates.SAMPLE_PICTURE), "",
                "which no stored picture value may be");
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
            Assert.assertNull(HtmlCheck.structureProblem(band.getBody()), id + " body must validate");
            Assert.assertTrue(band.getPlaceholders().stream().anyMatch(ph -> ph.getName().equals("tone")),
                    id + " takes a tone");
            Assert.assertTrue(band.getBody().contains("band-{{tone}}"), id + " renders the tone as a class");
        }
        Assert.assertNull(HtmlCheck.structureProblem(byId(StarterTemplates.BAND_HERO_ID).getBody()));
        Assert.assertTrue(byId(StarterTemplates.BAND_HERO_ID).getPlaceholders().stream()
                .noneMatch(ph -> ph.getName().equals("tone")), "the hero always wears its own look");
        for (final String id : List.of(StarterTemplates.FEATURE_CARD_ID, StarterTemplates.STAT_ITEM_ID)) {
            Assert.assertEquals(byId(id).getKind(), TemplateKind.STANDARD, id);
            Assert.assertNull(HtmlCheck.structureProblem(byId(id).getBody()), id);
        }

        final List<String> containers = List.of(StarterTemplates.BAND_FEATURES_ID, StarterTemplates.BAND_STATS_ID,
                StarterTemplates.BAND_FAQ_ID, StarterTemplates.BAND_LOGOS_ID);
        for (final String id : containers) {
            final ContentTemplate band = byId(id);
            Assert.assertEquals(band.getKind(), TemplateKind.CONTAINER, id);
            Assert.assertNull(HtmlCheck.structureProblem(band.getBody()), id + " body must validate");
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

    /** Owner, 2026-11-25: a band's tone and a split's side are menus, and transparent is a tone. */
    @Test
    public void tonesAndSidesAreMenus() {
        Assert.assertEquals(StarterTemplates.TONES, List.of("plain", "tint", "dark", "transparent"));
        for (final String id : List.of(StarterTemplates.BAND_SPLIT_ID, StarterTemplates.BAND_CTA_ID,
                StarterTemplates.BAND_TESTIMONIAL_ID, StarterTemplates.BAND_TEXT_ID)) {
            final Placeholder tone = prompt(byId(id), "tone");
            Assert.assertTrue(tone.isFixedChoice(), id);
            Assert.assertEquals(tone.getOptions(), StarterTemplates.TONES, id);
        }
        final Placeholder side = prompt(byId(StarterTemplates.BAND_SPLIT_ID), "side");
        Assert.assertEquals(side.getType(), Placeholder.Type.CHOICE);
        Assert.assertEquals(side.getOptions(), List.of("left", "right"));
        final ContentTemplate text = byId(StarterTemplates.BAND_TEXT_ID);
        final String out = ContentRenderer.render(text, new ContentInstance("t", "s", "T", text.getId(), 1,
                new HashMap<>(Map.of("tone", "transparent", "heading", "H")), null, 0, 1, null, null));
        Assert.assertTrue(out.contains("class=\"band band-transparent\""), out);
        for (final ContentTemplate starter : StarterTemplates.all()) {
            for (final Placeholder ph : starter.getPlaceholders()) {
                Assert.assertFalse(ph.getType() == Placeholder.Type.RICH_TEXT && ph.isRequired(),
                        "no starter's rich-text prompt is required: " + starter.getId() + "." + ph.getName());
            }
        }
    }

    /** The banner's picture size (2026-11-25): a menu class, or a checked width written as the art's flex-basis. */
    @Test
    public void theHeroPictureTakesASizeOrAWidth() {
        final ContentTemplate hero = byId(StarterTemplates.BAND_HERO_ID);
        Assert.assertEquals(prompt(hero, "imageSize").getOptions(), StarterTemplates.HERO_PICTURE_SIZES);
        Assert.assertEquals(prompt(hero, "imageWidth").getFormat(), Placeholder.FORMAT_CSS_LENGTH);
        final String blank = ContentRenderer.render(hero, new ContentInstance("h", "s", "H", hero.getId(), 1,
                new HashMap<>(Map.of("headline", "H")), null, 0, 1, null, null));
        Assert.assertTrue(blank.contains("<div class=\"hero-art hero-art-\" style=\"flex-basis:\">"),
                "blank reads as the stylesheet's default: " + blank);
        final String sized = ContentRenderer.render(hero, new ContentInstance("h", "s", "H", hero.getId(), 1,
                new HashMap<>(Map.of("headline", "H", "imageSize", "half", "imageWidth", "40%")), null, 0, 1, null,
                null));
        Assert.assertTrue(sized.contains("<div class=\"hero-art hero-art-half\" style=\"flex-basis:40%\">"), sized);
        final String hostile = ContentRenderer.render(hero, new ContentInstance("h", "s", "H", hero.getId(), 1,
                new HashMap<>(Map.of("headline", "H", "imageWidth", "1px;position:fixed")), null, 0, 1, null, null));
        Assert.assertTrue(hostile.contains("style=\"flex-basis:\""), "only a length reaches the style: " + hostile);
    }

    /** The Spacer (2026-11-25): every value lands in a style attribute, so each is a checked length or a menu. */
    @Test
    public void theSpacerWritesOnlyLengthsAndAKnownDisplay() {
        final ContentTemplate spacer = byId(StarterTemplates.SPACER_ID);
        Assert.assertEquals(spacer.getKind(), TemplateKind.STANDARD);
        Assert.assertFalse(spacer.isFullWidth(), "fits any page");
        Assert.assertTrue(spacer.getTags().contains("spacer"));
        Assert.assertEquals(prompt(spacer, "display").getOptions(), StarterTemplates.SPACER_DISPLAYS);
        final String out = ContentRenderer.render(spacer, new ContentInstance("p", "s", "", spacer.getId(), 1,
                new HashMap<>(Map.of("height", "40px", "width", "50%", "display", "inline-block")), null, 0, 1,
                null, null));
        Assert.assertTrue(out.contains("style=\"height:40px;width:50%;display:inline-block\""), out);
        final String blank = ContentRenderer.render(spacer, new ContentInstance("p", "s", "", spacer.getId(), 1,
                new HashMap<>(Map.of("height", "tall", "width", "0")), null, 0, 1, null, null));
        Assert.assertTrue(blank.contains("style=\"height:;width:0;display:\""), blank);
        Assert.assertNull(HtmlCheck.structureProblem(spacer.getBody()));
    }

    private static Placeholder prompt(final ContentTemplate template, final String name) {
        return template.getPlaceholders().stream().filter(ph -> ph.getName().equals(name)).findFirst()
                .orElseThrow();
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

    /**
     * The template picker's thumbnails (2026-11): every STANDARD starter's text prompt carries a sample, so a card
     * renders as a filled-in section, and no URL prompt does (the renderer refuses a placeholder URL and a real one
     * would fetch). The rich-text prompt reads "Content".
     */
    @Test
    public void standardStartersCarrySamplesForTheirTextPrompts() {
        for (final ContentTemplate template : StarterTemplates.all()) {
            if (template.getKind() != null && template.getKind() != TemplateKind.STANDARD) {
                continue;
            }
            for (final Placeholder ph : template.getPlaceholders()) {
                final boolean textual = ph.getType() == Placeholder.Type.TEXT
                        || ph.getType() == Placeholder.Type.RICH_TEXT;
                if (textual && !"imageAlt".equals(ph.getName()) && !"side".equals(ph.getName())
                        && !"icon".equals(ph.getName()) && !"width".equals(ph.getName())
                        && !"imageWidth".equals(ph.getName())
                        && !"altText".equals(ph.getName()) && !"caption".equals(ph.getName())
                        && !"linkText".equals(ph.getName()) && !"note".equals(ph.getName())) {
                    org.testng.Assert.assertNotNull(ph.getSample(),
                            template.getId() + "." + ph.getName() + " has no sample");
                }
                if (ph.isFixedChoice() && ph.getSample() != null) {
                    org.testng.Assert.assertTrue(ph.getOptions().contains(ph.getSample()),
                            "a menu's sample is one of its choices: " + template.getId() + "." + ph.getName());
                } else if (!textual) {
                    org.testng.Assert.assertNull(ph.getSample(), template.getId() + "." + ph.getName());
                }
                if (ph.getType() == Placeholder.Type.RICH_TEXT && !"quote".equals(ph.getName())
                        && !"caption".equals(ph.getName())) {
                    org.testng.Assert.assertEquals(ph.getLabel(), "Content", template.getId() + "." + ph.getName());
                }
            }
        }
    }
}
