package org.paulsens.trip.action;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.api.Beans;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPicture;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The Trip Listings cards (issue 154, Phase 2): which trip is featured, each card's one registration state, its
 * cover or what stands in for one, and when the organization is named.
 */
public class TripCardCommandsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 12, 0);
    private static final Person.Id VIEWER = Person.Id.from("card-viewer");
    /** A URL only its organization chose: typed into an Appearance field, on a host that is not this platform's. */
    private static final String TYPED = "https://tracker.example/p.gif";
    /** Acme's own Appearance upload (a page background), as local mode serves it. */
    private static final String ACME_UPLOAD = "http://acme.localhost:8080/branding-photos/"
            + BrandingPhotos.keyFor(FakeData.ACME_ORG_ID, "background", 5L, "jpg");

    private final Map<String, Object> requestMap = new HashMap<>();
    private TripCommands trips;
    private RegistrationCommands registrations;
    private TripPageCommands pages;
    private TripPictureCommands pictures;
    private SiteContext site;
    private String orgImage;
    private ContentInstance instance;
    private TripCardCommands cards;

    @BeforeClass
    public void seedOrgs() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    @BeforeMethod
    public void setUp() {
        requestMap.clear();
        trips = Mockito.mock(TripCommands.class);
        registrations = Mockito.mock(RegistrationCommands.class);
        pages = Mockito.mock(TripPageCommands.class);
        pictures = Mockito.mock(TripPictureCommands.class);
        site = SiteContext.shared("visitqueenofpeace.com");
        orgImage = null;
        instance = Mockito.mock(ContentInstance.class);
        Mockito.when(instance.getId()).thenReturn("listing-1");
        Mockito.when(trips.publicTripUrlFor(ArgumentMatchers.any(Trip.class), ArgumentMatchers.anyBoolean()))
                .thenAnswer(call -> "/trip/tripDetails.jsf?trip=" + call.<Trip>getArgument(0).getId());
        Mockito.when(pictures.url(ArgumentMatchers.any(TripPicture.class), ArgumentMatchers.anyInt()))
                .thenAnswer(call -> "/pics/" + call.<TripPicture>getArgument(0).keyAt(call.getArgument(1)));
        Mockito.when(pictures.srcset(ArgumentMatchers.any())).thenReturn("a 480w, b 960w");
        cards = new TripCardCommands(() -> trips, () -> registrations, () -> pages, () -> pictures,
                org -> orgImage, () -> site, () -> NOW, () -> requestMap);
    }

    private static Trip trip(final String id, final int startInDays) {
        final Trip trip = Trip.builder().id(id).title("2026 Oct: Trip " + id)
                .startDate(NOW.plusDays(startInDays)).endDate(NOW.plusDays(startInDays + 9)).build();
        trip.setProvider("CFPW");
        return trip;
    }

    private void listed(final Trip... listing) {
        Mockito.when(trips.getPublicTripsFor(instance)).thenReturn(List.of(listing));
    }

    private void phase(final Trip trip, final TripPageCommands.Phase phase) {
        Mockito.when(pages.registrationInfo(trip)).thenReturn(new TripPageCommands.RegistrationInfo(
                phase, 0, null, null, List.of(), null, trip.getStartDateIso(), "/join", "Register"));
    }

    @Test
    public void theFirstTripStillToStartIsFeaturedAndTheRestKeepTheirOrder() {
        final Trip started = trip("started", -2);
        final Trip next = trip("next", 30);
        final Trip later = trip("later", 90);
        listed(started, next, later);
        phase(started, TripPageCommands.Phase.CLOSED);

        final TripCardCommands.Card featured = cards.featured(instance, null);
        Assert.assertSame(featured.getTrip(), next, "The next departure, not the one under way");
        Assert.assertEquals(cards.rest(instance, null).stream().map(card -> card.getTrip().getId()).toList(),
                List.of("started", "later"));
        Assert.assertEquals(cards.cards(instance, null).size(), 3);
        Assert.assertEquals(cards.rest(instance, null).getFirst().getState(), TripCardCommands.State.CLOSED);
        Assert.assertEquals(featured.getUrl(), "/trip/tripDetails.jsf?trip=next");

        Mockito.verify(trips, Mockito.times(1)).getPublicTripsFor(instance);
        Mockito.verifyNoInteractions(registrations);
    }

    @Test
    public void aSectionWithNothingStillToStartFeaturesNothing() {
        listed(trip("started", -2));
        Assert.assertNull(cards.featured(instance, null));
        Assert.assertEquals(cards.rest(instance, null).size(), 1);

        requestMap.clear();
        listed();
        Assert.assertNull(cards.featured(instance, null));
        Assert.assertTrue(cards.cards(instance, null).isEmpty());
        Assert.assertTrue(cards.cards(null, null).isEmpty(), "No section, no cards");
    }

    @Test
    public void eachCardShowsOneRegistrationState() {
        final Trip open = trip("open", 10);
        final Trip full = trip("full", 20);
        final Trip mine = trip("mine", 30);
        final Trip external = trip("external", 40);
        external.setNonHostedTripUrl("https://example.com/elsewhere");
        external.setNonHostedRegNumber(45);
        external.setRegLimit(45);
        listed(open, full, mine, external);
        phase(open, TripPageCommands.Phase.OPEN);
        phase(full, TripPageCommands.Phase.FULL);
        phase(mine, TripPageCommands.Phase.FULL);
        // An external trip's fullness is part of the trip page's ONE phase rule (TripPageTest), not the card's.
        phase(external, TripPageCommands.Phase.FULL);
        Mockito.when(registrations.isChipRegistered("mine", VIEWER)).thenReturn(true);

        final List<TripCardCommands.Card> all = cards.cards(instance, VIEWER);
        Assert.assertEquals(all.stream().map(TripCardCommands.Card::getState).toList(), List.of(
                TripCardCommands.State.REGISTER, TripCardCommands.State.FULL, TripCardCommands.State.REGISTERED,
                TripCardCommands.State.FULL), "Registered outranks Full; every other card is the page's phase");
        Mockito.verify(registrations, Mockito.times(1)).isChipRegistered("mine", VIEWER);
        Mockito.verify(trips).publicTripUrlFor(mine, true);
        Mockito.verify(trips).publicTripUrlFor(open, false);
        Assert.assertEquals(TripCardCommands.State.REGISTERED.getLabel(), "Registered");
        Assert.assertEquals(TripCardCommands.State.FULL.getStyle(), "full");

        Assert.assertEquals(TripCardCommands.stateOf(TripPageCommands.Phase.OPEN), TripCardCommands.State.REGISTER);
        Assert.assertEquals(TripCardCommands.stateOf(TripPageCommands.Phase.CLOSED), TripCardCommands.State.CLOSED);
    }

    /** The staff-only pending chip asks twice per card (draw it? what does it say?): one uncached read answers both. */
    @Test
    public void thePendingCountIsReadOncePerTripAndRequest() {
        final Trip open = trip("open", 10);
        Mockito.when(registrations.getNumPending("open")).thenReturn(2);
        Assert.assertEquals(cards.pending(open), 2);
        Assert.assertEquals(cards.pending(open), 2);
        Mockito.verify(registrations, Mockito.times(1)).getNumPending("open");
    }

    @Test
    public void aTripWithoutARegistrationBlockReadsAsOpen() {
        final Trip open = trip("open", 10);
        listed(open);
        Assert.assertEquals(cards.featured(instance, VIEWER).getState(), TripCardCommands.State.REGISTER);
    }

    @Test
    public void theCoverCarriesItsUrlsAndAMissingOneFallsBackToTheOrganizationsImage() {
        final Trip pictured = trip("pictured", 10);
        pictured.getPictures().add(new TripPicture("tripPictures/pictured/1", "The hill at sunset", 1920, 1280,
                "#5a564f"));
        final Trip undescribed = trip("undescribed", 20);
        undescribed.getPictures().add(new TripPicture("tripPictures/undescribed/1", " ", 960, 640, "#000000"));
        final Trip bare = trip("bare", 30);
        bare.setOrgId(FakeData.ACME_ORG_ID);
        bare.setDestination("Fatima, Portugal");
        final Trip orgless = trip("orgless", 40);
        listed(pictured, undescribed, bare, orgless);
        orgImage = ACME_UPLOAD;

        final List<TripCardCommands.Card> all = cards.cards(instance, null);
        final TripCardCommands.Card first = all.get(0);
        Assert.assertEquals(first.getCoverUrl(), "/pics/tripPictures/pictured/1-960.jpg", "The middle rendition");
        Assert.assertEquals(first.getCoverSrcset(), "a 480w, b 960w");
        Assert.assertEquals(first.getAlt(), "The hill at sunset");
        Assert.assertEquals(first.getCover().getPlaceholder(), "#5a564f");
        Assert.assertNull(first.getFallbackUrl(), "A trip with a cover needs no stand-in");
        Assert.assertEquals(all.get(1).getAlt(), "Trip undescribed", "No description: the title without its prefix");

        final TripCardCommands.Card third = all.get(2);
        Assert.assertNull(third.getCover());
        Assert.assertNull(third.getCoverUrl());
        Assert.assertNull(third.getCoverSrcset());
        Assert.assertEquals(third.getFallbackUrl(), ACME_UPLOAD, "The TRIP's organization's own upload");
        Assert.assertEquals(third.getAlt(), "Trip bare");

        final TripCardCommands.Card fourth = all.get(3);
        Assert.assertNull(fourth.getFallbackUrl(), "No organization, nothing to borrow: the generated cover");

        requestMap.clear();
        orgImage = null;
        Assert.assertNull(cards.cards(instance, null).get(2).getFallbackUrl(), "An org with no image of its own");
        bare.setOrgId("no-such-org");
        requestMap.clear();
        Assert.assertNull(cards.cards(instance, null).get(2).getFallbackUrl());
        Mockito.verify(pictures, Mockito.atLeastOnce()).srcset(ArgumentMatchers.any());
    }

    /** The branding store as local mode runs it: no media host, so an upload is served by this app. */
    private static BrandingPhotos localPhotos() throws ReflectiveOperationException {
        final MediaCommands media = Mockito.mock(MediaCommands.class);
        final BrandingPhotos photos = new BrandingPhotos();
        final Field field = BrandingPhotos.class.getDeclaredField("media");
        field.setAccessible(true);
        field.set(photos, media);
        return photos;
    }

    private static Organization acmeWith(final Map<String, String> settings) {
        final Organization org = new Organization();
        org.setId(Organization.Id.from(FakeData.ACME_ORG_ID));
        org.setName("Acme Inc");
        org.getSettingsOverrides().putAll(settings);
        return org;
    }

    /**
     * The rule where it is applied: both Appearance settings also take a typed URL, and a card on a shared listing
     * must never make another organization's visitors call a host only this organization chose.
     */
    @Test
    public void onlyAnImageTheOrganizationUploadedStandsInForACover() throws Exception {
        final BrandCommands brand = new BrandCommands();
        final BrandingPhotos photos = localPhotos();
        final String preview = "http://acme.localhost:8080/branding-photos/"
                + BrandingPhotos.keyFor(FakeData.ACME_ORG_ID, "ogImage", 7L, "jpg");

        Assert.assertEquals(TripCardCommands.brandImageOf(acmeWith(Map.of(
                KnownSettings.SITE_OG_IMAGE_URL.getName(), TYPED,
                KnownSettings.SITE_BACKGROUND_URL.getName(), ACME_UPLOAD)), brand, photos), ACME_UPLOAD,
                "A typed link-preview URL is passed over for the uploaded background");
        Assert.assertNull(TripCardCommands.brandImageOf(acmeWith(Map.of(
                KnownSettings.SITE_OG_IMAGE_URL.getName(), TYPED)), brand, photos),
                "Only a typed URL: nothing to borrow, so the generated cover");
        Assert.assertNull(TripCardCommands.brandImageOf(acmeWith(Map.of(
                KnownSettings.SITE_OG_IMAGE_URL.getName(), TYPED,
                KnownSettings.SITE_BACKGROUND_URL.getName(), TYPED)), brand, photos));
        Assert.assertEquals(TripCardCommands.brandImageOf(acmeWith(Map.of(
                KnownSettings.SITE_OG_IMAGE_URL.getName(), preview,
                KnownSettings.SITE_BACKGROUND_URL.getName(), ACME_UPLOAD)), brand, photos), preview,
                "With both uploaded, the link preview outranks the background");
        Assert.assertNull(TripCardCommands.brandImageOf(acmeWith(Map.of()), brand, photos), "Nothing chosen");

        final String foreign = "http://acme.localhost:8080/branding-photos/"
                + BrandingPhotos.keyFor(FakeData.BETA_ORG_ID, "background", 5L, "jpg");
        Assert.assertNull(TripCardCommands.brandImageOf(acmeWith(Map.of(
                KnownSettings.SITE_BACKGROUND_URL.getName(), foreign)), brand, photos),
                "Another organization's upload, typed into this one's field, is not this one's image");
    }

    /** The bean as CDI builds it asks the real rule, with the TRIP's organization, for a trip with no picture. */
    @Test
    public void theDefaultWiringBorrowsTheTripsOwnOrganizationsUpload() throws Exception {
        final Trip bare = trip("bare", 30);
        bare.setOrgId(FakeData.ACME_ORG_ID);
        listed(bare);
        final BrandCommands brand = Mockito.mock(BrandCommands.class);
        Mockito.when(brand.lookOf(ArgumentMatchers.any())).thenReturn(new BrandCommands.OrgLook(null, false, null,
                null, null, TYPED, ACME_UPLOAD, null, null, null, null, null, null, null));
        final BrandingPhotos photos = localPhotos();

        try (MockedStatic<Beans> beans = Mockito.mockStatic(Beans.class)) {
            beans.when(() -> Beans.get(TripCommands.class)).thenReturn(trips);
            beans.when(() -> Beans.get(TripPageCommands.class)).thenReturn(pages);
            beans.when(() -> Beans.get(BrandCommands.class)).thenReturn(brand);
            beans.when(() -> Beans.get(BrandingPhotos.class)).thenReturn(photos);

            final TripCardCommands.Card card = new TripCardCommands().cards(instance, null).getFirst();
            Assert.assertEquals(card.getFallbackUrl(), ACME_UPLOAD, "The upload, never the typed link preview");
            Assert.assertEquals(card.getState(), TripCardCommands.State.REGISTER);
        }
        final ArgumentCaptor<Organization> asked = ArgumentCaptor.forClass(Organization.class);
        Mockito.verify(brand).lookOf(asked.capture());
        Assert.assertEquals(asked.getValue().getId().getValue(), FakeData.ACME_ORG_ID);
    }

    @Test
    public void theOrganizationIsNamedOnASharedSiteOnly() {
        final Trip named = trip("named", 10);
        final Trip unnamed = trip("unnamed", 20);
        unnamed.setProvider(" ");
        listed(named, unnamed);
        Assert.assertEquals(cards.cards(instance, null).get(0).getOrgName(), "CFPW");
        Assert.assertNull(cards.cards(instance, null).get(1).getOrgName(), "Nothing to name");

        requestMap.clear();
        site = SiteContext.org(Organization.Id.from(FakeData.ACME_ORG_ID), "acme", "acme.unitetrip.com");
        Assert.assertNull(cards.cards(instance, null).get(0).getOrgName(), "On its own site every trip is the org's");
    }

    @Test
    public void helpersAndTheDefaultWiring() {
        Assert.assertTrue(new TripCardCommands().cards(null, null).isEmpty(), "The CDI constructor builds");
    }

    /**
     * The featured card is chosen by CALENDAR day, like the sidebar countdown: the server's clock is UTC, so at
     * noon there a trip starting at 00:00 today has "started" by the instant while it is still the eve of the
     * departure, or its morning, for a viewer in the Americas.
     */
    @Test
    public void aTripStartingTodayIsStillTheNextDeparture() {
        final Trip today = Trip.builder().id("today").title("2026 Sep: Today")
                .startDate(NOW.toLocalDate().atStartOfDay()).endDate(NOW.plusDays(9)).build();
        final Trip later = trip("later", 30);
        listed(today, later);
        Assert.assertSame(cards.featured(instance, null).getTrip(), today, "Midnight today has not left the day");

        requestMap.clear();
        final Trip yesterday = Trip.builder().id("yesterday").title("2026 Aug: Yesterday")
                .startDate(NOW.toLocalDate().atStartOfDay().minusMinutes(1)).endDate(NOW.plusDays(9)).build();
        listed(yesterday, later);
        Assert.assertSame(cards.featured(instance, null).getTrip(), later, "Yesterday's departure has gone");

        requestMap.clear();
        final Trip undated = Trip.builder().id("undated").title("Undated").build();
        undated.setStartDate(null);   // the builder defaults one
        listed(undated, later);
        Assert.assertSame(cards.featured(instance, null).getTrip(), later, "A trip with no start date is never next");
    }
}
