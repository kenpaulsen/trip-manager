package org.paulsens.trip.content;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.SitePage;
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
                Integer.valueOf(6), "six questions");
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

    // --- the platform org's Site Pages (issue medjugorje#41): Pricing and Why UniteTrip ---

    private static final Organization.Id PLATFORM = Organization.Id.from("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
    /** Other products' names: the Why page compares with registration tools in general (owner decision 2026-11-15). */
    private static final Pattern COMPETITOR = Pattern.compile("(?i)regfox|webconnex|eventbrite|cvent|jotform|wufoo");
    /** British spellings the product never uses (US English, owner rule). */
    private static final Pattern BRITISH = Pattern.compile(
            "(?i)colour|organis|favourite|centre|licence|cancell|travell|realis|catalogue");
    private static final Pattern EM_DASH = Pattern.compile("\u2014|&#8212;|&#[xX]0*2014;|&mdash;");
    private static final Pattern PAGE_LINK = Pattern.compile("/page\\.jsf\\?name=([a-z0-9-]+)");

    private static List<ContentInstance> sitePageRows() {
        final List<ContentInstance> rows = new ArrayList<>();
        for (final OrgPageBootstrap.StarterPage page : MarketingPageBootstrap.sitePages()) {
            rows.addAll(OrgPageBootstrap.starterRows(PLATFORM, page, id -> 2));
        }
        return rows;
    }

    @Test
    public void thePlatformSiteHasPricingAndWhyUniteTripInItsAboutMenu() {
        final List<OrgPageBootstrap.StarterPage> pages = MarketingPageBootstrap.sitePages();
        Assert.assertEquals(pages.stream().map(OrgPageBootstrap.StarterPage::name).toList(),
                List.of(MarketingPageBootstrap.PRICING_PAGE, MarketingPageBootstrap.WHY_PAGE));
        Assert.assertSame(MarketingPageBootstrap.sitePages(), pages, "read once");
        for (final OrgPageBootstrap.StarterPage page : pages) {
            Assert.assertTrue(OrgPageBootstrap.isPageName(page.name()), page.name());
            Assert.assertEquals(page.entry().where(), SitePage.Placement.ABOUT, page.name());
            Assert.assertFalse(page.entry().isLink());
            final List<ContentInstance> rows = OrgPageBootstrap.starterRows(PLATFORM, page, id -> 2);
            final List<ContentInstance> top = rows.stream()
                    .filter(row -> row.getSection().equals(OrgPageBootstrap.pageKey(PLATFORM, page.name()))).toList();
            Assert.assertEquals(top.get(0).getTemplateId(), StarterTemplates.BAND_HERO_ID, page.name());
            Assert.assertEquals(top.get(top.size() - 1).getTemplateId(), StarterTemplates.BAND_CTA_ID, page.name());
            Assert.assertEquals(rows, OrgPageBootstrap.starterRows(PLATFORM, page, id -> 2), "fixed ids");
        }
    }

    /** Containers hold their children (parents first, filed under the parent's id) and admit what they hold. */
    @Test
    public void thePagesContainersHoldTheirCards() {
        final List<ContentInstance> rows = sitePageRows();
        final Map<String, ContentTemplate> starters = starters();
        final Map<String, ContentInstance> byId = new HashMap<>();
        final Map<String, Integer> childCounts = new HashMap<>();
        for (final ContentInstance row : rows) {
            Assert.assertNull(byId.put(row.getId(), row), "duplicate id: " + row.getTitle());
            Assert.assertTrue(starters.containsKey(row.getTemplateId()), row.getTemplateId());
            Assert.assertEquals(row.getTemplateVersion(), 2, "pinned to the CURRENT template version");
            final ContentInstance parent = byId.get(row.getSection());
            if (parent == null) {
                Assert.assertEquals(OrgPageBootstrap.orgOf(row.getSection()), PLATFORM,
                        row.getTitle() + " is a top-level row of a platform page, or follows its parent");
                continue;
            }
            final ContentTemplate container = starters.get(parent.getTemplateId());
            Assert.assertEquals(container.getKind(), TemplateKind.CONTAINER, parent.getTitle());
            Assert.assertTrue(container.getAllowedChildTemplateIds().contains(row.getTemplateId()),
                    parent.getTitle() + " does not admit " + row.getTemplateId());
            childCounts.merge(parent.getId(), 1, Integer::sum);
        }
        rows.stream().filter(row -> starters.get(row.getTemplateId()).getKind() == TemplateKind.CONTAINER)
                .forEach(row -> Assert.assertTrue(childCounts.getOrDefault(row.getId(), 0) > 0,
                        row.getTitle() + " is an empty band"));
    }

    /**
     * Every value renders through the real templates, every link on the pages reaches a page that exists, and the
     * copy keeps the product's rules: no em dash, US spelling, "trip" not "pilgrimage", and no competitor named.
     */
    @Test
    public void thePagesCopyRendersAndKeepsTheHouseRules() {
        final Map<String, ContentTemplate> starters = starters();
        final Set<String> names = new HashSet<>();
        MarketingPageBootstrap.sitePages().forEach(page -> names.add(page.name()));
        for (final ContentInstance row : sitePageRows()) {
            final ContentTemplate template = starters.get(row.getTemplateId());
            final List<String> texts = new ArrayList<>(row.getValues().values());
            texts.add(row.getTitle());
            for (final Map.Entry<String, String> value : row.getValues().entrySet()) {
                Assert.assertNull(HtmlFragmentValidator.validate(value.getValue()),
                        row.getTitle() + "." + value.getKey() + " must validate");
                final Placeholder declared = template.getPlaceholders().stream()
                        .filter(ph -> ph.getName().equals(value.getKey())).findFirst().orElse(null);
                Assert.assertNotNull(declared, row.getTitle() + " sets an undeclared value: " + value.getKey());
                if (declared.getType() == Placeholder.Type.URL) {
                    Assert.assertEquals(ContentRenderer.requireLinkUrl(value.getValue()), value.getValue(),
                            row.getTitle() + "." + value.getKey() + " must be a usable link");
                }
                if (value.getKey().equals("tone")) {
                    Assert.assertTrue(StarterTemplates.TONES.contains(value.getValue()), value.getValue());
                }
            }
            for (final String text : texts) {
                Assert.assertFalse(text.contains("{{"), row.getTitle() + " carries a token");
                Assert.assertFalse(EM_DASH.matcher(text).find(), row.getTitle() + " has an em dash");
                Assert.assertFalse(BRITISH.matcher(text).find(), row.getTitle() + " has a British spelling: " + text);
                Assert.assertFalse(COMPETITOR.matcher(text).find(), row.getTitle() + " names a competitor");
                Assert.assertFalse(text.toLowerCase().contains("pilgrimage"), row.getTitle() + " says pilgrimage");
                final Matcher link = PAGE_LINK.matcher(text);
                while (link.find()) {
                    Assert.assertTrue(names.contains(link.group(1)), row.getTitle() + " links to a page that "
                            + "does not exist: " + link.group(1));
                }
            }
            for (final Placeholder ph : template.getPlaceholders()) {
                Assert.assertFalse(ph.isRequired() && !row.getValues().containsKey(ph.getName()),
                        row.getTitle() + " leaves required " + ph.getName() + " blank");
            }
            if (template.getKind() == TemplateKind.STANDARD) {
                Assert.assertFalse(ContentRenderer.render(template, row).contains("{{"), row.getTitle());
            }
        }
    }

    /**
     * The home page no longer says PayPal is the only processor (Stripe shipped 2026-11), says an org may bring its
     * own domain, and its questions send a reader who asks about cost to the Pricing page.
     */
    @Test
    public void theHomePageNamesStripeAndPointsAtPricing() {
        final List<ContentInstance> rows = MarketingPageBootstrap.rows(id -> 1);
        for (final ContentInstance row : rows) {
            for (final String text : row.getValues().values()) {
                Assert.assertFalse(text.contains("PayPal") && !text.contains("Stripe"),
                        row.getTitle() + " names PayPal alone: " + text);
                Assert.assertFalse(EM_DASH.matcher(text).find() || BRITISH.matcher(text).find()
                        || COMPETITOR.matcher(text).find(), row.getTitle() + ": " + text);
            }
        }
        final ContentInstance cost = rows.stream().filter(row -> row.getTitle().equals("What does it cost?"))
                .findFirst().orElseThrow();
        Assert.assertEquals(cost.getSection(), MarketingPageBootstrap.idOf(MarketingPageBootstrap.FAQ));
        Assert.assertTrue(cost.getValues().get("body").contains("href=\"/page.jsf?name="
                + MarketingPageBootstrap.PRICING_PAGE + "\""), cost.getValues().get("body"));
        Assert.assertTrue(rows.stream().anyMatch(row -> row.getTitle().equals("Do we need our own domain?")
                && row.getValues().get("body").contains("already own a domain")));
    }

    /**
     * Neighboring bands never share a tone, on the home page or either Site Page, so each section reads as its own.
     * The container bands' tones are fixed by their templates (Features plain, Questions tint, Stats dark, the hero
     * its own gradient); a split, text or call-to-action band names its tone.
     */
    @Test
    public void neighboringBandsNeverShareATone() {
        assertAlternates("home", MarketingPageBootstrap.rows(id -> 1).stream()
                .filter(row -> row.getSection().equals(OrgPageBootstrap.MARKETING_PAGE_KEY)).toList());
        for (final OrgPageBootstrap.StarterPage page : MarketingPageBootstrap.sitePages()) {
            final String key = OrgPageBootstrap.pageKey(PLATFORM, page.name());
            assertAlternates(page.name(), OrgPageBootstrap.starterRows(PLATFORM, page, id -> 1).stream()
                    .filter(row -> row.getSection().equals(key)).toList());
        }
    }

    private static void assertAlternates(final String page, final List<ContentInstance> bands) {
        final Map<String, String> fixed = Map.of(StarterTemplates.BAND_HERO_ID, "hero",
                StarterTemplates.BAND_FEATURES_ID, "plain", StarterTemplates.BAND_FAQ_ID, "tint",
                StarterTemplates.BAND_STATS_ID, "dark");
        String previous = null;
        for (final ContentInstance band : bands) {
            final String tone = fixed.getOrDefault(band.getTemplateId(), band.getValues().get("tone"));
            Assert.assertNotNull(tone, page + ": " + band.getTitle() + " has no tone");
            Assert.assertNotEquals(tone, previous, page + ": " + band.getTitle() + " repeats its neighbor's tone");
            previous = tone;
        }
    }
}
