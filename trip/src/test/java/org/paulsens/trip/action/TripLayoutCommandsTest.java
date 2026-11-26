package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.api.Beans;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPicture;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The trip page's live sections through their template's layout body (docs/content-templates.md, "Live sections
 * with a layout"): the trip's values, the facts the section shows, the cover or the gallery, and the Tone.
 */
public class TripLayoutCommandsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 12, 0);
    private static final String SIZES = "(max-width: 991px) 100vw, 1200px";

    private final Map<String, Object> requestMap = new HashMap<>();
    private final Map<String, String> bodies = new HashMap<>();
    private final AtomicInteger reads = new AtomicInteger();
    private TripPictureCommands pictures;
    private TripLayoutCommands layouts;

    @BeforeMethod
    public void setUp() {
        requestMap.clear();
        bodies.clear();
        reads.set(0);
        pictures = Mockito.mock(TripPictureCommands.class);
        Mockito.when(pictures.url(ArgumentMatchers.any(TripPicture.class), ArgumentMatchers.anyInt()))
                .thenAnswer(call -> "/pics/" + call.<TripPicture>getArgument(0).keyAt(call.getArgument(1)));
        Mockito.when(pictures.srcset(ArgumentMatchers.any())).thenReturn("a 480w, b 960w");
        Mockito.when(pictures.altOf(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(call -> TripPictureCommands.altText(call.getArgument(0), call.getArgument(1)));
        final TripPageCommands pages = new TripPageCommands(() -> requestMap, () -> NOW);
        layouts = new TripLayoutCommands(() -> pages, () -> pictures, TripCommands::new, this::bodyOf,
                key -> "[" + key + "]", () -> requestMap);
    }

    private String bodyOf(final ContentInstance instance) {
        reads.incrementAndGet();
        return bodies.get(instance.getTemplateId());
    }

    private static ContentInstance section(final String id, final String templateId,
            final Map<String, String> values) {
        return new ContentInstance(id, "s", "", templateId, 1, new HashMap<>(values), null, 0, 1, null, null);
    }

    private static Trip trip() {
        final Trip trip = Trip.builder().id("layout-trip").title("2027 Mar: Layout Trip")
                .startDate(NOW.plusDays(30)).endDate(NOW.plusDays(39)).build();
        trip.setDestination("Medjugorje <&>");
        trip.setDirector("Fr. Ivan");
        trip.setEstimatedPrice("$1,999");
        trip.setFlyerUrl("https://example.org/flyer.pdf");
        return trip;
    }

    private static TripPicture picture(final int n, final String color) {
        return new TripPicture("tripPictures/layout-trip/" + n, n == 1 ? "The hill" : " ", 1920, 1280, color);
    }

    @Test
    public void aBlankBodyLeavesTheBuiltInMarkup() {
        final ContentInstance facts = section("f", StarterTemplates.TRIP_DETAILS_ID, Map.of());
        Assert.assertFalse(layouts.facts(facts, trip()).set(), "no body at all");
        bodies.put(StarterTemplates.TRIP_DETAILS_ID, "  ");
        requestMap.clear();
        Assert.assertFalse(layouts.facts(facts, trip()).set(), "a blank body");
        Assert.assertFalse(layouts.facts(null, trip()).set());
        Assert.assertFalse(layouts.facts(facts, null).set());
        Assert.assertFalse(layouts.pictures(null, trip(), SIZES).set());
        Assert.assertFalse(layouts.pictures(facts, null, SIZES).set());
    }

    @Test
    public void theFactsAreTheTripsInTheReadersLabels() {
        bodies.put(StarterTemplates.TRIP_DETAILS_ID, StarterTemplates.TRIP_DETAILS_LAYOUT);
        final TripLayoutCommands.Layout out = layouts.facts(section("f", StarterTemplates.TRIP_DETAILS_ID,
                Map.of()), trip());
        Assert.assertTrue(out.set());
        Assert.assertFalse(out.gallery());
        Assert.assertEquals(out.after(), "");
        final String html = out.before();
        Assert.assertTrue(html.contains("<i class=\"pi pi-map-marker\" aria-hidden=\"true\"></i> "
                + "Medjugorje &lt;&amp;&gt;"), html);
        Assert.assertTrue(html.contains("<b>[common.spiritualDirector]:</b>&#160;Fr. Ivan</span>"), html);
        Assert.assertTrue(html.contains("&#160;$1,999<span class=\"tripFactNote textMuted\"> "
                + "[trip.page.priceTerms]"), html);
        Assert.assertTrue(html.contains("<a href=\"https://example.org/flyer.pdf\" target=\"_blank\""), html);
        Assert.assertTrue(html.contains("</i> [trip.page.flyer]</a>"), html);
        Assert.assertFalse(html.contains("tripFactDates"), "the default line leaves the dates to the banner");
        Assert.assertFalse(html.contains("[trip.page.guide]"), "a fact the trip lacks leaves no label: " + html);
        Assert.assertFalse(html.contains("{{"), "every word is filled: " + html);
    }

    @Test
    public void theSectionsPickDecidesWhichFactsShow() {
        bodies.put("my-facts", StarterTemplates.TRIP_DETAILS_LAYOUT);
        final String html = layouts.facts(section("f", "my-facts", Map.of("shown", "dates,price")), trip())
                .before();
        Assert.assertTrue(html.contains("tripFactDates"), html);
        Assert.assertTrue(html.contains("$1,999"), html);
        Assert.assertFalse(html.contains("Medjugorje"), "a template of this type under another id draws too");
    }

    @Test
    public void aTypedFlyerLinkIsScreened() {
        bodies.put(StarterTemplates.TRIP_DETAILS_ID, "<a href=\"{{trip:flyerUrl}}\">x</a>|{{trip:flyerUrl}}");
        final Trip trip = trip();
        trip.setFlyerUrl("javascript:alert(1)");
        Assert.assertEquals(layouts.facts(section("f", StarterTemplates.TRIP_DETAILS_ID, Map.of()), trip).before(),
                "<a href=\"\">x</a>|");
    }

    @Test
    public void onePictureIsTheCoverInItsPlaceholderColor() {
        bodies.put(StarterTemplates.TRIP_PICTURES_ID, StarterTemplates.TRIP_PICTURES_LAYOUT);
        final Trip trip = trip();
        trip.getPictures().add(picture(1, "#112233"));
        final TripLayoutCommands.Layout out = layouts.pictures(section("p", StarterTemplates.TRIP_PICTURES_ID,
                Map.of()), trip, SIZES);
        Assert.assertTrue(out.set());
        Assert.assertFalse(out.gallery());
        Assert.assertTrue(out.before().contains("<span class=\"tripHeroMedia\" style=\"background-color:#112233\">"
                + "<img class=\"tripCardImg\" src=\"/pics/tripPictures/layout-trip/1-960.jpg\" srcset=\"a 480w, "
                + "b 960w\" sizes=\"" + SIZES + "\" width=\"1920\" height=\"1280\" alt=\"The hill\" "
                + "loading=\"eager\" /></span>"), out.before());
    }

    @Test
    public void twoOrMorePicturesAreTheGalleryUnlessTheSectionShowsTheCoverOnly() {
        bodies.put(StarterTemplates.TRIP_PICTURES_ID, StarterTemplates.TRIP_PICTURES_LAYOUT);
        final Trip trip = trip();
        trip.getPictures().add(picture(1, "#112233"));
        trip.getPictures().add(picture(2, "#445566"));
        final TripLayoutCommands.Layout gallery = layouts.pictures(section("p", StarterTemplates.TRIP_PICTURES_ID,
                Map.of()), trip, SIZES);
        Assert.assertTrue(gallery.gallery());
        Assert.assertEquals(gallery.before().strip(), "<div class=\"tripHero\">");
        Assert.assertEquals(gallery.after().strip(), "</div>");
        Assert.assertFalse(gallery.before().contains("tripHeroMedia"));

        final TripLayoutCommands.Layout cover = layouts.pictures(section("c", StarterTemplates.TRIP_PICTURES_ID,
                Map.of("show", "cover")), trip, SIZES);
        Assert.assertFalse(cover.gallery());
        Assert.assertTrue(cover.before().contains("tripHeroMedia"), cover.before());
    }

    @Test
    public void aSlotOutsideItsBlockDrawsNoGalleryForOnePicture() {
        bodies.put(StarterTemplates.TRIP_PICTURES_ID, "<div>{{trip:gallery}}</div>");
        final Trip trip = trip();
        trip.getPictures().add(picture(1, "#112233"));
        final TripLayoutCommands.Layout out = layouts.pictures(section("p", StarterTemplates.TRIP_PICTURES_ID,
                Map.of()), trip, SIZES);
        Assert.assertFalse(out.gallery(), "one picture is never a gallery");
        Assert.assertEquals(out.before() + out.after(), "<div></div>");
        requestMap.clear();
        Assert.assertFalse(layouts.pictures(section("p", StarterTemplates.TRIP_PICTURES_ID, Map.of()), trip(),
                SIZES).gallery(), "nor are no pictures");
    }

    @Test
    public void aColorThatIsNotOneStaysOutOfTheStyle() {
        Assert.assertEquals(TripLayoutCommands.colorOf(picture(1, "#a1B2c3")), "#a1B2c3");
        Assert.assertEquals(TripLayoutCommands.colorOf(picture(1, "red;background:url(x)")), "");
        Assert.assertEquals(TripLayoutCommands.colorOf(picture(1, null)), "");
        bodies.put(StarterTemplates.TRIP_PICTURES_ID,
                "<i style=\"color:{{trip:coverColor}}\">{{trip:coverAlt}}</i>");
        final Trip trip = trip();
        trip.getPictures().add(picture(2, "#000;x:y"));
        final String out = layouts.pictures(section("p", StarterTemplates.TRIP_PICTURES_ID, Map.of()), trip,
                SIZES).before();
        Assert.assertTrue(out.startsWith("<i style=\"color:\">"), out);
        Assert.assertTrue(out.contains("Layout Trip"), "an undescribed cover reads the trip's title: " + out);
    }

    @Test
    public void theToneIsAPropertyAndTheWrappersClass() {
        bodies.put(StarterTemplates.TRIP_DETAILS_ID, "<div class=\"{{tone}}\">{{trip:destination}}</div>");
        final ContentInstance dark = section("t", StarterTemplates.TRIP_DETAILS_ID, Map.of("tone", "dark"));
        Assert.assertEquals(layouts.facts(dark, trip()).before(),
                "<div class=\"dark\">Medjugorje &lt;&amp;&gt;</div>");
        Assert.assertEquals(layouts.withTone("tripTop tripTopFacts", dark), "tripTop tripTopFacts tone-dark");
        final ContentInstance odd = section("o", StarterTemplates.TRIP_DETAILS_ID, Map.of("tone", "Dark"));
        Assert.assertEquals(layouts.facts(odd, trip()).before(), "<div class=\"\">Medjugorje &lt;&amp;&gt;</div>",
                "only one of the tones reaches the body");
        Assert.assertEquals(layouts.withTone("tripTop", odd), "tripTop");
        Assert.assertEquals(layouts.withTone("tripTop", section("b", "x", Map.of())), "tripTop", "blank: as before");
        Assert.assertEquals(layouts.withTone("tripTop", null), "tripTop");
    }

    @Test
    public void eachSectionIsBuiltOncePerRequest() {
        bodies.put(StarterTemplates.TRIP_DETAILS_ID, StarterTemplates.TRIP_DETAILS_LAYOUT);
        final ContentInstance facts = section("f", StarterTemplates.TRIP_DETAILS_ID, Map.of());
        final Trip trip = trip();
        final TripLayoutCommands.Layout first = layouts.facts(facts, trip);
        Assert.assertSame(layouts.facts(facts, trip), first);
        Assert.assertEquals(reads.get(), 1, "the fragment asks for it several times; the layout is read once");
    }

    @Test
    public void theDefaultWiringReadsTheStoredLayout() {
        final ContentCommands content = Mockito.mock(ContentCommands.class);
        Mockito.when(content.layoutBody(ArgumentMatchers.any())).thenReturn("<p>{{trip:destination}}</p>");
        final TripPageCommands pages = new TripPageCommands(() -> null, () -> NOW);
        try (MockedStatic<Beans> beans = Mockito.mockStatic(Beans.class)) {
            beans.when(() -> Beans.get(ContentCommands.class)).thenReturn(content);
            beans.when(() -> Beans.get(TripPageCommands.class)).thenReturn(pages);
            beans.when(() -> Beans.get(TripPictureCommands.class)).thenReturn(pictures);
            beans.when(() -> Beans.get(TripCommands.class)).thenReturn(new TripCommands());
            final TripLayoutCommands wired = new TripLayoutCommands();
            Assert.assertEquals(wired.facts(section("f", StarterTemplates.TRIP_DETAILS_ID, Map.of()), trip())
                    .before(), "<p>Medjugorje &lt;&amp;&gt;</p>");
        }
    }
}
