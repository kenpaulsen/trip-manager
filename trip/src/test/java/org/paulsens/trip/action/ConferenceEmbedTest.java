package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The embed render's inline stylesheet (docs/conference.md "The embed"): the overrides always, the trip's own
 * stylesheet inlined when its bytes can be read and linked live when they cannot, and the version suffix the page's
 * link carries once the stylesheet was rewritten in place.
 */
public class ConferenceEmbedTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2027, 1, 10, 12, 0);

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    @Test
    public void theEmbedCarriesTheOverridesAndLinksAStylesheetItCannotInline() throws IOException {
        final ConferenceCommands views = new ConferenceCommands(() -> null, () -> NOW);
        final String css = views.embedCss(FakeData.CONF_TRIP_ID);
        assertTrue(css.contains(".tripEmbed .cta-row"), "the overrides close the stylesheet: " + css);
        assertTrue(css.contains("--layout-full-bg"));
        assertNull(views.embedStylesheetLink(FakeData.CONF_TRIP_ID), "no stylesheet of its own: nothing to link");
        assertNull(views.embedStylesheetLink(" "));
        assertTrue(views.embedCss("no-such-trip").contains(".tripEmbed"), "an unknown trip still gets the overrides");

        // A trip linking the seeded sample stylesheet: no bucket to read it from, so the embed links it live.
        final Trip trip = Trip.builder().title("Embed Trip").orgId(FakeData.CFPW_ORG_ID).openToPublic(true)
                .startDate(NOW.plusDays(20)).endDate(NOW.plusDays(22)).build();
        final MediaItem sample = DAO.getInstance().getMedia("fake-css-cfpw", Cached.NO).orElseThrow();
        final String url = new MediaCommands().getUrl(sample);
        trip.getPage().setStylesheetUrl(url);
        assertTrue(DAO.getInstance().saveTrip(trip));
        final ConferenceCommands fresh = new ConferenceCommands(() -> null, () -> NOW);
        assertEquals(fresh.embedStylesheetLink(trip.getId()), url, "linked live when the bytes are unreadable");
        assertEquals(fresh.lookFor(trip.getId()).stylesheetUrl(), url, "rev 0: no version suffix");

        // Rewritten in place: the page's link carries the row's version so browsers refetch it.
        assertTrue(DAO.getInstance().saveMedia(sample.withRewritten(10)));
        assertEquals(new ConferenceCommands(() -> null, () -> NOW).lookFor(trip.getId()).stylesheetUrl(),
                url + "?r=1");
        assertEquals(new MediaCommands().libraryItemFor(FakeData.CFPW_ORG_ID, url + "?r=1").getId(), sample.getId(),
                "a pasted suffix still resolves to the item");
        assertTrue(DAO.getInstance().saveMedia(sample), "restored for the other tests");

        // With a store that can read the bytes, the stylesheet is INLINED (once per version) and nothing is linked.
        final MediaCommands readable = org.mockito.Mockito.mock(MediaCommands.class);
        org.mockito.Mockito.when(readable.cachedLibraryItemFor(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(sample);
        org.mockito.Mockito.when(readable.getObject(sample.getS3Key()))
                .thenReturn(java.util.Optional.of(".contentItem .x { color: red }".getBytes()));
        final ConferenceCommands inlining = new ConferenceCommands(() -> null, () -> NOW) {
            @Override
            protected MediaCommands media() {
                return readable;
            }
        };
        assertTrue(inlining.embedCss(trip.getId()).contains(".contentItem .x { color: red }"), "inlined");
        assertNull(inlining.embedStylesheetLink(trip.getId()), "inlined, so not linked");
        assertTrue(inlining.embedCss(trip.getId()).contains(".contentItem .x"), "the second read is the cache's");
        org.mockito.Mockito.verify(readable, org.mockito.Mockito.times(1)).getObject(sample.getS3Key());
    }

    @Test
    public void convertingWaitsForTheStarterItNeeds() throws IOException {
        final Trip legacy = Trip.builder().title("Starterless").orgId(FakeData.CFPW_ORG_ID).openToPublic(true)
                .startDate(NOW.plusDays(20)).endDate(NOW.plusDays(22)).build();
        legacy.setDescription("<p>old</p>");
        assertTrue(DAO.getInstance().saveTrip(legacy));
        final ConferenceCommands starterless = new ConferenceCommands(() -> null, () -> NOW) {
            @Override
            protected boolean starterInstalled(final String templateId) {
                return false;
            }
        };
        final org.paulsens.trip.model.Person.Id manager = org.paulsens.trip.model.Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.TRIP_MGR, "starterless mgr", legacy.getId(),
                java.util.List.of(manager)));
        assertTrue(!starterless.convertLegacyPage(legacy.getId(), manager), "refused while the starter is missing");
        assertNull(DAO.getInstance().getTrip(legacy.getId(), Cached.NO).orElseThrow().getPageSeededAt());
        final Trip blank = Trip.builder().title("Starterless Blank").orgId(FakeData.CFPW_ORG_ID).build();
        assertTrue(!starterless.ensurePage(blank), "seeding waits for the starters too");
        assertNull(blank.getPageSeededAt());
    }

    @Test
    public void onlyACssItemIsInlined() {
        final MediaItem picture = new MediaItem("not-css", "org/x/pic.png", "Pic", null, "image/png", 1L, null, 0,
                NOW, "seed", null, null, FakeData.CFPW_ORG_ID);
        final MediaCommands odd = org.mockito.Mockito.mock(MediaCommands.class);
        org.mockito.Mockito.when(odd.cachedLibraryItemFor(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(picture);
        final Trip trip = Trip.builder().title("Odd Link").orgId(FakeData.CFPW_ORG_ID).openToPublic(true)
                .startDate(NOW.plusDays(20)).endDate(NOW.plusDays(22)).build();
        trip.getPage().setStylesheetUrl("https://cdn.example/not-really.css");
        try {
            assertTrue(DAO.getInstance().saveTrip(trip));
        } catch (final IOException ex) {
            throw new AssertionError(ex);
        }
        final ConferenceCommands views = new ConferenceCommands(() -> null, () -> NOW) {
            @Override
            protected MediaCommands media() {
                return odd;
            }
        };
        assertEquals(views.embedStylesheetLink(trip.getId()), "https://cdn.example/not-really.css",
                "a link that is not a CSS item is left to the browser");
    }
}
