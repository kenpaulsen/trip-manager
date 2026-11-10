package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.MediaItem;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The conference page dialog's library pick lists and the URL-to-item inverse (docs/conference.md, "Look and
 * feel"): one org's curated items of one content-type family, and the item a public URL names when it is that
 * org's.
 */
public class MediaLibraryPickTest {
    private static final String ORG = FakeData.ACME_ORG_ID;
    private static final String PREFIX = "org/" + ORG + "/pick/";

    private final MediaCommands media = TestCallers.mediaAsSiteAdmin();

    @BeforeClass
    public void seed() {
        DAO.getInstance();
        FakeData.addFakeData();
        save("pick-css-b", PREFIX + "b.css", "Zeta styles", "text/css", null, null, ORG);
        save("pick-css-a", PREFIX + "a.css", "alpha styles", "text/css; charset=utf-8", null, null, ORG);
        save("pick-css-hidden", PREFIX + "h.css", "Hidden styles", "text/css", null, Boolean.TRUE, ORG);
        save("pick-css-other", "org/" + FakeData.BETA_ORG_ID + "/pick/o.css", "Beta styles", "text/css", null, null,
                FakeData.BETA_ORG_ID);
        save("pick-css-site", "downloads/site.css", "Site styles", "text/css", null, null, null);
        save("pick-img", PREFIX + "bg.webp", "Background", "image/webp", null, null, ORG);
        save("pick-chat", "chat/pick-trip/p.jpg", "Chat photo", "image/jpeg", "tripChat-pick-trip", null, ORG);
    }

    @Test
    public void theListIsOneOrgsCuratedItemsOfOneKindByTitle() {
        final List<String> css = media.libraryItems(ORG, "text/css").stream().map(MediaItem::getId).toList();
        assertEquals(css, List.of("pick-css-a", "pick-css-b"),
                "by title, no hidden item, no other org's, no site-level");
        final List<String> images = media.libraryItems(ORG, "image/").stream().map(MediaItem::getId).toList();
        assertEquals(images, List.of("pick-img"), "a chat photo is never a library pick");
        assertTrue(media.libraryItems(ORG, "text/css").stream().allMatch(MediaCommands::isStylesheet));
        assertFalse(MediaCommands.isStylesheet(media.get("pick-img")));
        assertFalse(MediaCommands.isStylesheet(null));
        assertTrue(media.libraryItems("no-such-org", "text/css").isEmpty());
        assertEquals(media.libraryItems(null, "text/css").stream().map(MediaItem::getId).toList(),
                List.of("pick-css-site"), "no org names the site-level items");
    }

    @Test
    public void aPublicUrlResolvesToItsOrgsItemAndNothingElse() {
        final MediaItem item = media.get("pick-css-a");
        final String url = media.getUrl(item);
        assertTrue(url.endsWith("/" + PREFIX + "a.css"), url);
        assertEquals(media.libraryItemFor(ORG, url).getId(), "pick-css-a");
        assertEquals(media.libraryItemFor(ORG, " " + url + " ").getId(), "pick-css-a", "trimmed");
        assertNull(media.libraryItemFor(FakeData.BETA_ORG_ID, url), "another org never reaches it");
        assertNull(media.libraryItemFor(null, url), "nor the shared site");
        assertNull(media.libraryItemFor(ORG, "https://elsewhere.example/" + PREFIX + "a.css"), "another host");
        assertNull(media.libraryItemFor(ORG, media.getMediaBaseUrl() + "/" + PREFIX + "missing.css"));
        assertNull(media.libraryItemFor(ORG, null));
        assertEquals(media.libraryItemFor(null, media.getUrl(media.get("pick-css-site"))).getId(), "pick-css-site");
    }

    @Test
    public void aPickLabelCarriesTheFileNameTheTitleDoesNotSay() {
        assertEquals(media.pickLabel(media.get("pick-css-a")), "alpha styles (a.css)");
        assertEquals(media.pickLabel(media.get("pick-img")), "Background (bg.webp)");
        assertEquals(media.pickLabel(item("org/" + ORG + "/pick/Hero.JPG", "hero.jpg")), "Hero.JPG",
                "a title that is the file name is not repeated");
        assertEquals(media.pickLabel(item("org/" + ORG + "/pick/untitled.png", " ")), "untitled.png");
        assertEquals(media.pickLabel(item("org/" + ORG + "/pick/none.png", null)), "none.png");
        assertEquals(media.pickLabel(item(null, "Keyless")), "Keyless");
        assertEquals(media.pickLabel(null), "");
    }

    @Test
    public void aChosenUrlReadsAsItsPathInTheLibraryOrAsAForeignAddress() {
        final String css = media.getUrl(media.get("pick-css-a"));
        assertEquals(media.describeChoice(ORG, css), "pick/a.css", "no protocol, no host, no org folder");
        assertEquals(media.describeChoice(ORG, " " + css + "?r=3 "), "pick/a.css", "a cache buster is not the name");
        assertEquals(media.describeChoice(ORG, media.getUrl(media.get("pick-img"))), "pick/bg.webp");
        assertEquals(media.describeChoice(null, media.getUrl(media.get("pick-css-site"))), "downloads/site.css",
                "a site-level item keeps its whole key");
        assertEquals(media.describeChoice(ORG, "https://cdn.example/art/bg.jpg?v=2#top"),
                "cdn.example/art/bg.jpg" + MediaCommands.NOT_IN_LIBRARY);
        assertEquals(media.describeChoice(FakeData.BETA_ORG_ID, css),
                css.replaceFirst("^https?://", "") + MediaCommands.NOT_IN_LIBRARY, "another org's item is foreign");
        assertEquals(media.describeChoice(ORG, null), "");
        assertEquals(media.describeChoice(ORG, "  "), "");
    }

    private static MediaItem item(final String key, final String title) {
        return new MediaItem("pick-unsaved", key, title, null, "image/png", 10L, null, 0, LocalDateTime.now(),
                "seed", null, null, ORG);
    }

    private static void save(final String id, final String key, final String title, final String type,
            final String slot, final Boolean hidden, final String orgId) {
        assertTrue(DAO.getInstance().saveMedia(new MediaItem(id, key, title, null, type, 10L, slot, 0,
                LocalDateTime.now(), "seed", null, hidden, orgId)), id);
    }
}
