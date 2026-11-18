package org.paulsens.trip.api.mapper;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.BrandCommands;
import org.paulsens.trip.action.BrandingPhotos;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.TripPageCommands;
import org.paulsens.trip.action.TripPictureCommands;
import org.paulsens.trip.action.TripTags;
import org.paulsens.trip.api.dto.DiscoverFacetDto;
import org.paulsens.trip.api.dto.DiscoverListDto;
import org.paulsens.trip.api.dto.DiscoverTripDto;
import org.paulsens.trip.api.dto.TripPictureDto;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BadgeImage;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripKind;
import org.paulsens.trip.model.TripPicture;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The tenancy guard on Discover's wire shape (medjugorje#155). Discover is the one read that crosses
 * organizations and answers a caller with no account, so what it may say is pinned twice:
 * <ol>
 *   <li>by NAME: the record components of everything a Discover response is made of must equal the literal lists
 *       below. Adding a field fails this test until someone adds it here, which is the moment to ask whether the
 *       field is already public on the organization's own site ({@code docs/discover-tenancy-review.md});</li>
 *   <li>by VALUE: a trip carrying a sentinel in every field that is NOT public is mapped and serialized, and
 *       none of the sentinels may appear in the JSON, whatever the fields are called.</li>
 * </ol>
 */
public class DiscoverTripDtoAllowListTest {

    /** Every record a Discover response is made of, and exactly the components each may have. */
    private static final Map<Class<?>, List<String>> ALLOWED = allowed();

    /** The only non-record types a component may have: values, never an object that could carry more. */
    private static final Set<Class<?>> VALUE_TYPES =
            Set.of(String.class, LocalDate.class, int.class, boolean.class, List.class);

    private static final String SECRET = "SENTINEL-must-never-leave";
    private static final String ORG_ID = "9f3d5c1e-6b2a-4d7e-8c40-1a2b3c4d5e6f";

    private static Map<Class<?>, List<String>> allowed() {
        final Map<Class<?>, List<String>> allowed = new LinkedHashMap<>();
        allowed.put(DiscoverListDto.class, List.of("items", "total", "nextCursor", "facets"));
        allowed.put(DiscoverListDto.Facets.class,
                List.of("countries", "tags", "languages", "kinds", "organizations", "months"));
        allowed.put(DiscoverFacetDto.class, List.of("value", "label", "count", "logoUrl"));
        allowed.put(DiscoverTripDto.class, List.of("id", "title", "startDate", "endDate", "destination", "countries",
                "tags", "tagLabels", "kind", "language", "priceText", "pictures", "orgId", "orgName", "orgLogoUrl",
                "detailsUrl", "registration", "registersOnWeb"));
        allowed.put(TripPictureDto.class,
                List.of("key", "url", "url480", "url960", "alt", "width", "height", "placeholder"));
        return allowed;
    }

    // ------------------------------------------------------------------ by name

    @Test
    public void everyRecordInADiscoverResponseHasExactlyItsAllowedComponents() {
        final Set<Class<?>> seen = new java.util.LinkedHashSet<>();
        walk(DiscoverListDto.class, seen);

        Assert.assertEquals(seen, ALLOWED.keySet(),
                "The records reachable from a Discover response changed; each new one needs an allow-list here.");
        for (final Class<?> record : seen) {
            Assert.assertEquals(componentNames(record), ALLOWED.get(record), record.getSimpleName()
                    + " changed. A new Discover field crosses organizations to anonymous callers: add it here "
                    + "ONLY with a line in docs/discover-tenancy-review.md saying where it is already public.");
        }
    }

    /** Follows record components and list elements; anything that is neither a record nor a plain value fails. */
    private static void walk(final Type type, final Set<Class<?>> seen) {
        if (type instanceof ParameterizedType generic) {
            Assert.assertEquals(generic.getRawType(), List.class, "only lists may be generic in a Discover record");
            Arrays.stream(generic.getActualTypeArguments()).forEach(argument -> walk(argument, seen));
            return;
        }
        final Class<?> raw = (Class<?>) type;
        if (!raw.isRecord()) {
            Assert.assertTrue(VALUE_TYPES.contains(raw), raw.getName() + " in a Discover record: a component must "
                    + "be a plain value or another allow-listed record, never a type that could carry more.");
            return;
        }
        if (seen.add(raw)) {
            Arrays.stream(raw.getRecordComponents()).forEach(part -> walk(part.getGenericType(), seen));
        }
    }

    private static List<String> componentNames(final Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    // ------------------------------------------------------------------ by value

    private static Organization org() {
        final Organization org = new Organization();
        org.setId(Organization.Id.from(ORG_ID));
        org.setName("Holy Redeemer Parish");
        org.setAbbreviation("HRP");
        org.setContactEmail(SECRET + "@example.com");
        org.setAdminIds(List.of(Person.Id.from(SECRET + "-admin")));
        org.setDefaultMailDomain(SECRET + ".example");
        org.getSettingsOverrides().put("site.contact.phone", SECRET + "-phone");
        return org;
    }

    /** A trip whose every public field has a plain value and whose every OTHER field holds a sentinel. */
    private static Trip tripWithSentinels() {
        final LocalDateTime start = LocalDateTime.now().plusDays(30).withHour(8);
        final Trip trip = Trip.builder()
                .id("7b1f0c2d-3e4a-4b5c-8d6e-9f0a1b2c3d4e")
                .title("2026 Dec: Advent in Medjugorje")
                .openToPublic(true)
                .kind(TripKind.PILGRIMAGE)
                .language(Language.Spanish)
                .estimatedPrice("  $3,290 from Portland ")
                .startDate(start)
                .endDate(start.plusDays(10))
                .tags(List.of("retreat", "pilgrimage", "no-longer-offered"))
                .pictures(List.of(new TripPicture("tripPictures/t/cover", "The church at dusk", 1920, 1280, "#aabbcc")))
                // Everything below is NOT public.
                .description(SECRET + " description")
                .people(List.of(Person.Id.from(SECRET + "-traveler-1"), Person.Id.from(SECRET + "-traveler-2")))
                .regLimit(424_242)
                .provider(SECRET + " provider")
                .director(SECRET + " director")
                .directorIds(List.of(Person.Id.from(SECRET + "-director")))
                .localGuide(SECRET + " guide")
                .facilitators(SECRET + " facilitators")
                .facilitatorIds(List.of(Person.Id.from(SECRET + "-facilitator")))
                .flyerUrl("https://example.com/" + SECRET + "-flyer.pdf")
                .nonHostedTripUrl("https://example.com/" + SECRET + "-external")
                .nonHostedRegNumber(434_343)
                .regOptions(List.of(new RegistrationOption(1, SECRET + " question", SECRET + " help", true)))
                .badgeImages(List.of(new BadgeImage(SECRET + "-badge-key", SECRET + " badge")))
                .build();
        trip.setOrgId(ORG_ID);
        trip.setDestination("Medjugorje, Bosnia and Herzegovina");
        trip.setCountries(List.of("ba", "hr", "not-a-country"));
        trip.getPaymentConfig().setProcessorConfigId(SECRET + "-processor");
        trip.getPaymentConfig().setMailFrom(SECRET + "-from@example.com");
        trip.getPaymentConfig().setBcc(SECRET + "-bcc@example.com");
        trip.getPage().setStylesheetUrl("/" + SECRET + ".css");
        trip.getPage().setBackgroundUrl("/" + SECRET + "-background.jpg");
        return trip;
    }

    private static DiscoverTripMapper mapperWith(final String storedLogo, final String uploadedLogo,
            final boolean local) {
        final TripPictureCommands pictures = Mockito.mock(TripPictureCommands.class);
        Mockito.when(pictures.url(ArgumentMatchers.any(TripPicture.class), ArgumentMatchers.anyInt()))
                .thenAnswer(call -> "/trip-pictures/" + call.<TripPicture>getArgument(0).keyAt(call.getArgument(1)));
        Mockito.when(pictures.url(ArgumentMatchers.any(TripPicture.class)))
                .thenAnswer(call -> "/trip-pictures/" + call.<TripPicture>getArgument(0).keyAt(1920));
        final BrandCommands brand = Mockito.mock(BrandCommands.class);
        Mockito.when(brand.lookOf(ArgumentMatchers.any())).thenReturn(new BrandCommands.OrgLook(null, false, null,
                storedLogo, SECRET + "-favicon", SECRET + "-og", SECRET + "-background", null, SECRET + " footer",
                SECRET + " footer text", SECRET + " contact", SECRET + " phone", SECRET + "-donate", null));
        final BrandingPhotos photos = Mockito.mock(BrandingPhotos.class);
        Mockito.when(photos.firstUploadOf(ORG_ID, storedLogo)).thenReturn(uploadedLogo);
        final TripPageCommands pages = Mockito.mock(TripPageCommands.class);
        // The registration block knows the counts; only its phase may cross.
        Mockito.when(pages.registrationInfo(ArgumentMatchers.any())).thenReturn(new TripPageCommands.RegistrationInfo(
                TripPageCommands.Phase.FULL, 454_545, 464_646, 474_747,
                List.of(new TripPageCommands.PriceGroup(SECRET + " priced question", List.of(SECRET + " choice"))),
                SECRET + " fallback", "2026-12-17", "/trip/joinTrip.jsf?trip=" + SECRET, SECRET + " label"));
        return new DiscoverTripMapper(TripTags.parse("Pilgrimage, Retreat, Tour"), pictures, brand, photos, pages,
                new ConfigCommands(), local);
    }

    private static String json(final Object value) throws Exception {
        return DAO.getInstance().getMapper().writeValueAsString(value);
    }

    @Test
    public void aTripFullOfSentinelsSerializesWithNoneOfThem() throws Exception {
        final DiscoverTripDto dto = mapperWith("/branding-photos/org/x/branding/logo-1.png",
                "/branding-photos/org/x/branding/logo-1.png", true).map(tripWithSentinels(), org());
        final String json = json(dto);

        Assert.assertFalse(json.contains(SECRET), "a private field reached the public shape: " + json);
        for (final String number : List.of("424242", "434343", "454545", "464646", "474747")) {
            Assert.assertFalse(json.contains(number), "a limit or a count reached the public shape: " + json);
        }
        for (final String name : List.of("people", "regLimit", "regOptions", "paymentConfig", "flyerUrl",
                "nonHostedTripUrl", "nonHostedRegNumber", "director", "facilitators", "localGuide", "description",
                "provider", "page", "checkout", "waivers", "badgeImages", "tripEvents", "openToPublic", "contactEmail",
                "adminIds")) {
            Assert.assertFalse(json.contains("\"" + name + "\""), name + " is not a Discover field: " + json);
        }
    }

    @Test
    public void thePublicFieldsAreMappedAsDocumented() {
        final Trip trip = tripWithSentinels();
        final DiscoverTripDto dto = mapperWith("/branding-photos/org/x/branding/logo-1.png",
                "/branding-photos/org/x/branding/logo-1.png", true).map(trip, org());

        Assert.assertEquals(dto.id(), trip.getId());
        Assert.assertEquals(dto.title(), "Advent in Medjugorje", "the short title, without its sort prefix");
        Assert.assertEquals(dto.startDate(), trip.getStartDate().toLocalDate(), "a date, not a time of day");
        Assert.assertEquals(dto.endDate(), trip.getEndDate().toLocalDate());
        Assert.assertEquals(dto.destination(), "Medjugorje, Bosnia and Herzegovina");
        Assert.assertEquals(dto.countries(), List.of("BA", "HR"), "every country, upper case, in the trip's order");
        Assert.assertEquals(dto.tags(), List.of("pilgrimage", "retreat"), "only tags still offered, in list order");
        Assert.assertEquals(dto.tagLabels(), List.of("Pilgrimage", "Retreat"));
        Assert.assertEquals(dto.kind(), "PILGRIMAGE");
        Assert.assertEquals(dto.language(), "Spanish");
        Assert.assertEquals(dto.priceText(), "$3,290 from Portland");
        Assert.assertEquals(dto.orgId(), ORG_ID);
        Assert.assertEquals(dto.orgName(), "Holy Redeemer Parish");
        Assert.assertEquals(dto.orgLogoUrl(), "/branding-photos/org/x/branding/logo-1.png");
        Assert.assertEquals(dto.detailsUrl(), "/trip/tripPage.jsf?trip=" + trip.getId());
        Assert.assertEquals(dto.registration(), "full", "the phase alone");
        Assert.assertTrue(dto.registersOnWeb(), "an external trip: the one fact taken from its URL is that it has one");
        Assert.assertEquals(dto.pictures(), List.of(new TripPictureDto("tripPictures/t/cover",
                "/trip-pictures/tripPictures/t/cover-1920.jpg", "/trip-pictures/tripPictures/t/cover-480.jpg",
                "/trip-pictures/tripPictures/t/cover-960.jpg", "The church at dusk", 1920, 1280, "#aabbcc")));
    }

    /** The defaults a sparse trip maps to: nothing blank on the wire, English when no language was stored. */
    @Test
    public void aSparseTripMapsToAbsentFieldsAndEnglish() throws Exception {
        final LocalDateTime start = LocalDateTime.now().plusDays(5);
        final Trip trip = Trip.builder().title("Day Trip").startDate(start).endDate(start).build();
        trip.setOrgId(ORG_ID);
        trip.setDestination("  ");
        trip.setCountry("");
        trip.setEstimatedPrice(" ");

        final DiscoverTripDto dto = DiscoverTripMapper.toDto(trip, org(), List.of(), List.of(), " ", "/page",
                TripPageCommands.Phase.OPEN);

        Assert.assertNull(dto.destination());
        Assert.assertEquals(dto.countries(), List.of(), "no country is an empty list, never null");
        Assert.assertNull(dto.priceText());
        Assert.assertNull(dto.orgLogoUrl());
        Assert.assertEquals(dto.language(), "English");
        Assert.assertEquals(dto.kind(), "PILGRIMAGE");
        Assert.assertEquals(dto.registration(), "open");
        Assert.assertFalse(dto.registersOnWeb());
        Assert.assertEquals(dto.pictures(), List.of(), "empty, never null: a client can tell none from not sent");
        final String json = json(dto);
        Assert.assertFalse(json.contains("\"destination\""), "an absent value is absent, not null: " + json);
        Assert.assertTrue(json.contains("\"startDate\":\"" + start.toLocalDate() + "\""), json);
        Assert.assertTrue(json.contains("\"pictures\":[]"), json);
        Assert.assertTrue(json.contains("\"countries\":[]"), json);
        Assert.assertFalse(json.contains("\"country\""), "Discover has the list only, no single country: " + json);

        final Trip undated = Trip.builder().title("Undated").build();
        undated.setStartDate(null);
        undated.setEndDate(null);
        undated.setOrgId(ORG_ID);
        final DiscoverTripDto noDates = DiscoverTripMapper.toDto(undated, org(), List.of(), List.of(), null, "/page",
                TripPageCommands.Phase.CLOSED);
        Assert.assertNull(noDates.startDate());
        Assert.assertNull(noDates.endDate());
    }

    /**
     * The logo setting also takes a typed URL. Discover is drawn for people who never chose this organization, so
     * only a file the organization uploaded here is sent; a typed address would make every phone call that host.
     */
    @Test
    public void onlyAnUploadedLogoIsSent() {
        final String typed = "https://tracker.example/logo.png";
        final DiscoverTripMapper mapper = mapperWith(typed, null, true);

        Assert.assertNull(mapper.map(tripWithSentinels(), org()).orgLogoUrl(), "a typed logo URL is not sent");
        Assert.assertNull(mapper.map(tripWithSentinels(), org()).orgLogoUrl(), "the per-organization memo agrees");
    }

    /** In production the page link is on the organization's own site, never the host the API was called on. */
    @Test
    public void outsideLocalModeTheDetailsUrlIsAbsoluteOnTheOwningSite() {
        final Trip trip = tripWithSentinels();
        final DiscoverTripDto dto = mapperWith(null, null, false).map(trip, org());

        Assert.assertTrue(dto.detailsUrl().startsWith("https://"), dto.detailsUrl());
        Assert.assertTrue(dto.detailsUrl().endsWith("/trip/tripPage.jsf?trip=" + trip.getId()), dto.detailsUrl());
    }

    /** Local mode's site-relative URLs are finished per request; nothing else about the trip changes. */
    @Test
    public void absoluteFinishesEveryUrlAndChangesNothingElse() {
        final DiscoverTripDto relative = mapperWith("/branding-photos/logo.png", "/branding-photos/logo.png", true)
                .map(tripWithSentinels(), org());

        final DiscoverTripDto absolute = DiscoverTripMapper.absolute(relative,
                url -> url == null ? null : "http://localhost:8091" + url);

        Assert.assertEquals(absolute.detailsUrl(), "http://localhost:8091" + relative.detailsUrl());
        Assert.assertEquals(absolute.orgLogoUrl(), "http://localhost:8091/branding-photos/logo.png");
        final TripPictureDto picture = absolute.pictures().get(0);
        Assert.assertEquals(picture.url(), "http://localhost:8091/trip-pictures/tripPictures/t/cover-1920.jpg");
        Assert.assertEquals(picture.url480(), "http://localhost:8091/trip-pictures/tripPictures/t/cover-480.jpg");
        Assert.assertEquals(picture.url960(), "http://localhost:8091/trip-pictures/tripPictures/t/cover-960.jpg");
        Assert.assertEquals(picture.alt(), "The church at dusk");
        Assert.assertEquals(DiscoverTripMapper.absolute(absolute, url -> url), absolute, "already absolute: unchanged");
        Assert.assertEquals(new DiscoverTripDto(absolute.id(), absolute.title(), absolute.startDate(),
                absolute.endDate(), absolute.destination(), absolute.countries(), absolute.tags(), absolute.tagLabels(),
                absolute.kind(), absolute.language(), absolute.priceText(), relative.pictures(), absolute.orgId(),
                absolute.orgName(), relative.orgLogoUrl(), relative.detailsUrl(), absolute.registration(),
                absolute.registersOnWeb()), relative, "only the URLs differ");
    }
}
