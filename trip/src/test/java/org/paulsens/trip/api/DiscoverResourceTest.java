package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.BrandCommands;
import org.paulsens.trip.action.BrandingPhotos;
import org.paulsens.trip.action.TripPictureCommands;
import org.paulsens.trip.api.dto.DiscoverFacetDto;
import org.paulsens.trip.api.dto.DiscoverListDto;
import org.paulsens.trip.api.dto.DiscoverTripDto;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPicture;
import org.paulsens.trip.site.DiscoverIndex;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link DiscoverResource} end to end against the real store and the application's own {@link DiscoverIndex}:
 * the acceptance of medjugorje#155's tenancy rule. An organization with Shared sites off and a trip with Show on
 * homepage off must be absent from the list, from search and by id; a miss must look like no trip at all; and an
 * anonymous call must touch no session and set no cookie.
 *
 * <p>The trips and organizations are this class's own (stamped names, removed afterwards), so nothing here
 * depends on the seeds or on what other classes left in the shared store. Assertions name those ids: the
 * unfiltered list also holds whatever else is discoverable in the JVM.
 */
public class DiscoverResourceTest extends ResourceTestSupport {

    private static final String STAMP = Long.toString(System.nanoTime(), 36);
    private static final Person.Id ME = Person.Id.from("discover-me");
    private static final String CLIENT = "203.0.113.50";

    private static String alphaOrg;
    private static String bravoOrg;
    private static String quietOrg;
    private static Trip alphaTrip;
    private static Trip bravoTrip;
    private static Trip quietTrip;
    private static Trip offHomepage;
    private static Trip orgless;
    private static Trip ended;
    private static final List<Trip> CREATED = new ArrayList<>();

    private DiscoverResource resource;

    @BeforeClass
    public static void createOrgsAndTrips() throws IOException {
        alphaOrg = saveOrg("Discover Alpha " + STAMP, null);
        bravoOrg = saveOrg("Discover Bravo " + STAMP, null);
        quietOrg = saveOrg("Discover Quiet " + STAMP, Boolean.FALSE);
        alphaTrip = saveTrip("Alpine Hike zqalpha" + STAMP, alphaOrg, true, 20, 27, "Zermatt, Switzerland", "CH");
        bravoTrip = saveTrip("Lisbon Tour zqbravo" + STAMP, bravoOrg, true, 50, 56, "Lisbon, Portugal", "PT");
        quietTrip = saveTrip("Quiet Retreat zqquiet" + STAMP, quietOrg, true, 30, 35, "Assisi, Italy", "IT");
        offHomepage = saveTrip("Staff Planning zqhidden" + STAMP, alphaOrg, false, 30, 35, "Rome, Italy", "IT");
        orgless = saveTrip("Legacy Row zqorgless" + STAMP, null, true, 30, 35, "Knock, Ireland", "IE");
        ended = saveTrip("Last Month zqended" + STAMP, alphaOrg, true, -30, -20, "Lourdes, France", "FR");
    }

    @AfterClass(alwaysRun = true)
    public static void removeTrips() {
        // Public trips left in the shared store would show up in other classes' listings.
        CREATED.forEach(DAO.getInstance()::deleteTrip);
    }

    private static String saveOrg(final String name, final Boolean allowShared) throws IOException {
        final Organization org = Organization.builder().name(name).created(LocalDateTime.now()).build();
        org.setAllowSharedSites(allowShared);
        Assert.assertTrue(DAO.getInstance().saveOrganization(org));
        return org.getId().getValue();
    }

    private static Trip saveTrip(final String title, final String orgId, final boolean open, final int startInDays,
            final int endInDays, final String destination, final String country) throws IOException {
        final Trip trip = Trip.builder()
                .id(UUID.randomUUID().toString())
                .title(title)
                .openToPublic(open)
                .startDate(LocalDateTime.now().plusDays(startInDays))
                .endDate(LocalDateTime.now().plusDays(endInDays))
                .people(List.of(Person.Id.from("discover-traveler-" + STAMP)))
                .estimatedPrice("$1,200")
                .pictures(List.of(new TripPicture("tripPictures/" + STAMP + "/cover", "A view", 1920, 1280, "#102030")))
                .tags(List.of("tour"))
                .build();
        trip.setOrgId(orgId);
        trip.setDestination(destination);
        trip.setCountry(country);
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        CREATED.add(trip);
        return trip;
    }

    @BeforeMethod
    public void bindBeansAndBuildTheResource() {
        final TripPictureCommands pictures = bindMock(TripPictureCommands.class);
        Mockito.when(pictures.url(ArgumentMatchers.any(TripPicture.class), ArgumentMatchers.anyInt()))
                .thenAnswer(call -> "/trip-pictures/" + call.<TripPicture>getArgument(0).keyAt(call.getArgument(1)));
        Mockito.when(pictures.url(ArgumentMatchers.any(TripPicture.class)))
                .thenAnswer(call -> "/trip-pictures/" + call.<TripPicture>getArgument(0).keyAt(1920));
        final BrandCommands brand = bindMock(BrandCommands.class);
        Mockito.when(brand.lookOf(ArgumentMatchers.any())).thenReturn(new BrandCommands.OrgLook(null, false, null,
                null, null, null, null, null, null, null, null, null, null, null));
        bindMock(BrandingPhotos.class);
        Mockito.when(request.getRemoteAddr()).thenReturn(CLIENT);
        Mockito.when(request.getRequestURI()).thenReturn("/api/discover/trips");
        Mockito.when(request.getContextPath()).thenReturn("");
        anonymous();
        // Whatever an earlier class left the application's index holding, this method starts from a rebuild.
        DiscoverIndex.getInstance().invalidate();
        resource = discover(10_000);
    }

    /** A resource on the application's index with a limiter of this test's own (its own counters, its own limit). */
    private DiscoverResource discover(final int limit) {
        return resource(new DiscoverResource(DiscoverIndex.getInstance(),
                new PublicRateLimiter(new InMemoryCacheClient(), limit, 60)));
    }

    private static Map<String, List<String>> params(final String... pairs) {
        final Map<String, List<String>> params = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.computeIfAbsent(pairs[i], name -> new ArrayList<>()).add(pairs[i + 1]);
        }
        return params;
    }

    private DiscoverListDto listed(final String... pairs) {
        final Response response = resource.list(params(pairs));
        assertOk(response);
        return (DiscoverListDto) response.getEntity();
    }

    private List<String> ids(final String... pairs) {
        return listed(pairs).items().stream().map(DiscoverTripDto::id).toList();
    }

    /** Everything discoverable in this JVM, this class's trips among it. */
    private List<String> everything() {
        return ids("limit", "100");
    }

    // ------------------------------------------------------------------ what is listed

    @Test
    public void theListHoldsPublicTripsOfMoreThanOneOrganization() {
        final List<String> all = everything();

        Assert.assertTrue(all.contains(alphaTrip.getId()), "Alpha's public trip");
        Assert.assertTrue(all.contains(bravoTrip.getId()), "Bravo's public trip");
        Assert.assertTrue(all.indexOf(alphaTrip.getId()) < all.indexOf(bravoTrip.getId()),
                "soonest departure first: Alpha leaves in 20 days, Bravo in 50");
    }

    @Test
    public void anOrgWithSharedSitesOffAndATripOffItsHomepageAreAbsentFromTheList() {
        final List<String> all = everything();

        Assert.assertFalse(all.contains(quietTrip.getId()), "the organization turned Shared sites off");
        Assert.assertFalse(all.contains(offHomepage.getId()), "Show on homepage is off");
        Assert.assertFalse(all.contains(orgless.getId()), "a trip with no organization is not listed");
        Assert.assertFalse(all.contains(ended.getId()), "an ended trip is not listed");
        Assert.assertEquals(ids("org", quietOrg), List.of(), "asking for the opted-out organization by id finds none");
        final List<String> offeredOrgs = listed("limit", "100").facets().organizations().stream()
                .map(DiscoverFacetDto::value).toList();
        Assert.assertFalse(offeredOrgs.contains(quietOrg), "nor is it offered as a filter");
        Assert.assertTrue(offeredOrgs.contains(alphaOrg));
    }

    @Test
    public void searchFindsListedTripsAndNeverTheOthers() {
        Assert.assertEquals(ids("q", "zqalpha" + STAMP), List.of(alphaTrip.getId()));
        final List<DiscoverTripDto> byOrgName = listed("q", "discover bravo " + STAMP).items();
        Assert.assertTrue(byOrgName.stream().anyMatch(trip -> bravoTrip.getId().equals(trip.id())),
                "found by its organization's name");
        Assert.assertTrue(byOrgName.stream().allMatch(trip -> bravoOrg.equals(trip.orgId())), "and only Bravo's");
        Assert.assertEquals(ids("q", "zqquiet" + STAMP), List.of(), "the opted-out organization's trip, by its title");
        Assert.assertEquals(ids("q", "discover quiet " + STAMP), List.of(), "or by the organization's name");
        Assert.assertEquals(ids("q", "zqhidden" + STAMP), List.of(), "the trip that is off its homepage");
        Assert.assertEquals(ids("q", "zqorgless" + STAMP), List.of());
        Assert.assertEquals(ids("q", "zqended" + STAMP), List.of());
        Assert.assertEquals(ids("country", "IT", "org", alphaOrg, "org", quietOrg), List.of(),
                "a filter cannot reach them either: both Italian trips are unlisted");
    }

    @Test
    public void aListedTripCanBeReadByIdAndCarriesOnlyItsPublicShape() {
        final Response response = resource.get(alphaTrip.getId());

        assertOk(response);
        final DiscoverTripDto trip = (DiscoverTripDto) response.getEntity();
        Assert.assertEquals(trip.id(), alphaTrip.getId());
        Assert.assertEquals(trip.title(), "Alpine Hike zqalpha" + STAMP);
        Assert.assertEquals(trip.orgId(), alphaOrg);
        Assert.assertEquals(trip.orgName(), "Discover Alpha " + STAMP);
        Assert.assertEquals(trip.countries(), List.of("CH"));
        Assert.assertEquals(trip.priceText(), "$1,200");
        Assert.assertEquals(trip.registration(), "open");
        Assert.assertEquals(trip.tags(), List.of("tour"));
        Assert.assertEquals(trip.tagLabels(), List.of("Tour"));
        Assert.assertEquals(trip.pictures().size(), 1);
        Assert.assertFalse(trip.toString().contains("discover-traveler"), "the roster is no part of the shape");
    }

    /** Unlisted, for whatever reason, is the SAME answer as never existed: status, body and headers. */
    @Test
    public void everyKindOfMissIsTheIdentical404() {
        final Response never = resource.get(UUID.randomUUID().toString());
        assertError(never, 404, ApiErrors.NOT_FOUND);

        for (final Trip unlisted : List.of(quietTrip, offHomepage, orgless, ended)) {
            final Response miss = resource.get(unlisted.getId());
            Assert.assertEquals(miss.getStatus(), 404, unlisted.getTitle());
            Assert.assertEquals(miss.getEntity(), never.getEntity(), "the body must not tell them apart");
            Assert.assertEquals(miss.getHeaders().keySet(), never.getHeaders().keySet());
            Assert.assertEquals(miss.getMediaType(), never.getMediaType());
            Assert.assertNull(miss.getHeaderString("ETag"), "a miss has no validator to compare");
        }
    }

    /** A trip that visits two countries is found under either, listed once, and counted under each. */
    @Test
    public void aTripInTwoCountriesIsFoundUnderEitherAndCountedUnderEach() throws IOException {
        final Trip trip = saveTrip("Two Countries zqtwo" + STAMP, bravoOrg, true, 80, 88, "Fatima and Lourdes", "PT");
        trip.setCountries(List.of("PT", "FR"));
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));

        Assert.assertEquals(((DiscoverTripDto) resource.get(trip.getId()).getEntity()).countries(),
                List.of("PT", "FR"));
        Assert.assertEquals(ids("org", bravoOrg, "country", "FR"), List.of(trip.getId()), "by its second country");
        Assert.assertTrue(ids("org", bravoOrg, "country", "PT").contains(trip.getId()), "by its first");
        final DiscoverListDto either = listed("q", "zqtwo" + STAMP, "country", "FR", "country", "PT");
        Assert.assertEquals(either.total(), 1, "asked for both countries, the trip is still one trip");
        final Map<String, Integer> counted = new HashMap<>();
        listed("q", "zqtwo" + STAMP).facets().countries().forEach(facet -> counted.put(facet.value(), facet.count()));
        Assert.assertEquals(counted, Map.of("PT", 1, "FR", 1), "counted once under each of its countries");
        Assert.assertEquals(ids("q", "france zqtwo" + STAMP), List.of(trip.getId()), "and found by either name");
        DAO.getInstance().deleteTrip(trip);
    }

    // ------------------------------------------------------------------ saves take effect on the next request

    /** Turning Shared sites off removes the organization's trips at once on this server; back on, they return. */
    @Test
    public void turningSharedSitesOffTakesTheOrganizationOutOnTheNextRequest() throws IOException {
        final String org = saveOrg("Discover Toggle " + STAMP + System.nanoTime(), null);
        final Trip trip = saveTrip("Toggle Trip", org, true, 40, 45, "Fatima, Portugal", "PT");
        Assert.assertTrue(everything().contains(trip.getId()), "never touched counts as ON");

        final Organization stored = DAO.getInstance().getOrganization(Organization.Id.from(org), Cached.NO)
                .orElseThrow();
        stored.setSharedSitesAllowed(false);
        Assert.assertTrue(DAO.getInstance().saveOrganization(stored));

        Assert.assertFalse(everything().contains(trip.getId()), "gone from the list with no wait");
        Assert.assertEquals(resource.get(trip.getId()).getStatus(), 404, "and by id");
    }

    @Test
    public void takingATripOffItsHomepageRemovesItOnTheNextRequest() throws IOException {
        final Trip trip = saveTrip("Homepage Toggle", alphaOrg, true, 60, 65, "Paris, France", "FR");
        Assert.assertEquals(resource.get(trip.getId()).getStatus(), 200);

        trip.setOpenToPublic(false);
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        Assert.assertEquals(resource.get(trip.getId()).getStatus(), 404);
        Assert.assertFalse(everything().contains(trip.getId()));

        // Listed again, and READ as listed, so the snapshot is fresh and holds it: only then does the 404 after
        // the delete prove that deleting marks the index stale (it would hold the trip for a minute otherwise).
        trip.setOpenToPublic(true);
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        Assert.assertEquals(resource.get(trip.getId()).getStatus(), 200, "back on its homepage, back in Discover");
        DAO.getInstance().deleteTrip(trip);
        Assert.assertEquals(resource.get(trip.getId()).getStatus(), 404, "a deleted trip leaves at once too");
        Assert.assertFalse(everything().contains(trip.getId()));
    }

    /**
     * A row changed behind the application's back is made visible by {@code DAO.invalidate}; Discover holds
     * trips already mapped, so the same call must mark its snapshot stale, or it would keep the old answer
     * for a minute after the caches were cleared.
     */
    @Test
    public void aCacheInvalidationAlsoRefreshesDiscover() throws IOException {
        final Trip trip = saveTrip("Invalidate Trip", bravoOrg, true, 90, 95, "Braga, Portugal", "PT");
        Assert.assertEquals(resource.get(trip.getId()).getStatus(), 200, "listed, and the snapshot is fresh");
        final String before = resource.list(params("org", bravoOrg)).getHeaderString("ETag");

        // Nothing saved: the snapshot would be trusted for a whole interval were it not marked stale here. The
        // waiver scope, because clearing it disturbs no other class's rows (WaiverDAOTest clears it the same way).
        final DiscoverIndex.Snapshot held = DiscoverIndex.getInstance().current();
        DAO.getInstance().invalidate(DAO.CacheScope.WAIVER);

        Assert.assertNotSame(DiscoverIndex.getInstance().current(), held, "rebuilt on the next request");
        Assert.assertEquals(resource.list(params("org", bravoOrg)).getHeaderString("ETag"), before,
                "rebuilt from unchanged data: the same answer");
        DAO.getInstance().deleteTrip(trip);
    }

    /**
     * The filters stand aside only for requests {@code SessionlessPaths} recognizes. A request that reached this
     * resource some other way would have had its session handled as on any API path, so it must not be given a
     * public, cacheable answer: it is the same miss as a trip that does not exist.
     */
    @Test
    public void aRequestTheSessionlessRuleDidNotRecognizeIsRefused() {
        Mockito.when(request.getRequestURI()).thenReturn("/api/trips/elsewhere");

        for (final Response refused : List.of(resource.list(params()), resource.get(alphaTrip.getId()))) {
            assertError(refused, 404, ApiErrors.NOT_FOUND);
            Assert.assertNull(refused.getHeaderString("Cache-Control"));
            Assert.assertNull(refused.getHeaderString("ETag"));
        }
        Mockito.when(request.getRequestURI()).thenReturn("/api/discover;x/trips");
        Assert.assertEquals(resource.list(params()).getStatus(), 200, "a path parameter is still Discover");
    }

    // ------------------------------------------------------------------ nobody is asking

    /** The acceptance line: an anonymous Discover session creates no server session and sets no cookie. */
    @Test
    public void anAnonymousCallTouchesNoSessionAndSetsNoCookie() {
        final Response list = resource.list(params("q", "zqalpha" + STAMP));
        final Response one = resource.get(alphaTrip.getId());
        final Response miss = resource.get(quietTrip.getId());

        Mockito.verify(request, Mockito.never()).getSession();
        Mockito.verify(request, Mockito.never()).getSession(ArgumentMatchers.anyBoolean());
        Mockito.verifyNoInteractions(response);
        for (final Response answer : List.of(list, one, miss)) {
            Assert.assertNull(answer.getHeaderString("Set-Cookie"));
            Assert.assertTrue(answer.getCookies().isEmpty());
        }
    }

    /** A signed-in caller gets exactly the anonymous answer: that is what lets it be cached as public. */
    @Test
    public void aSignedInCallerGetsTheAnonymousAnswer() {
        final DiscoverListDto anonymous = listed("org", alphaOrg, "org", bravoOrg, "org", quietOrg);
        final String anonymousTag = resource.list(params("org", alphaOrg)).getHeaderString("ETag");

        signedInAsSiteAdmin(ME);
        Mockito.when(request.getSession(false)).thenReturn(session);
        resource = discover(10_000);

        Assert.assertEquals(listed("org", alphaOrg, "org", bravoOrg, "org", quietOrg), anonymous,
                "even a site administrator is shown nothing more here");
        Assert.assertEquals(resource.list(params("org", alphaOrg)).getHeaderString("ETag"), anonymousTag);
        Assert.assertEquals(resource.get(quietTrip.getId()).getStatus(), 404);
        Mockito.verify(session, Mockito.never()).getAttribute(ArgumentMatchers.anyString());
    }

    @Test
    public void theResourceIsOpenAndReadOnlyByDeclaration() {
        Assert.assertNull(DiscoverResource.class.getAnnotation(TripApi.class),
                "it must answer callers with no account");
        Assert.assertEquals(new DiscoverResource().versionedType(), ApiMediaTypes.DISCOVER_V1);
    }

    // ------------------------------------------------------------------ an organization's own host

    private <T> T onSite(final SiteContext site, final ScopedValue.CallableOp<T, RuntimeException> body) {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site)).call(body);
    }

    @Test
    public void onAnOrganizationsOwnHostDiscoverIsThatOrganizationsAlone() {
        final SiteContext alphaSite = SiteContext.org(Organization.Id.from(alphaOrg), "alpha", "alpha.unitetrip.com")
                .forApiClient();

        final List<String> onAlpha = onSite(alphaSite, this::everything);
        Assert.assertEquals(onAlpha, List.of(alphaTrip.getId()));
        Assert.assertEquals(onSite(alphaSite, () -> resource.get(bravoTrip.getId())).getStatus(), 404,
                "another organization's listed trip is no trip on this host");
        Assert.assertEquals(onSite(alphaSite, () -> resource.get(alphaTrip.getId())).getStatus(), 200);

        final SiteContext marketing = SiteContext.marketing("unitetrip.com").forApiClient();
        final List<String> onMarketing = onSite(marketing, this::everything);
        Assert.assertTrue(onMarketing.contains(alphaTrip.getId()) && onMarketing.contains(bravoTrip.getId()));
        Assert.assertFalse(onMarketing.contains(quietTrip.getId()),
                "the host that reaches everything still lists nothing the rule refuses");
        Assert.assertNotEquals(onSite(alphaSite, () -> resource.list(params())).getHeaderString("ETag"),
                resource.list(params()).getHeaderString("ETag"), "two hosts' answers never share a validator");
    }

    // ------------------------------------------------------------------ caching

    @Test
    public void anAnswerIsPubliclyCacheableForAMinuteWithAWeakValidator() {
        accepting(ApiMediaTypes.DISCOVER_V1);

        for (final Response answer : List.of(resource.list(params()), resource.get(alphaTrip.getId()))) {
            assertOk(answer);
            assertVersionedType(answer, ApiMediaTypes.DISCOVER_V1);
            Assert.assertEquals(answer.getHeaderString("Cache-Control"), "public, max-age=60");
            Assert.assertTrue(answer.getHeaderString("ETag").matches("W/\"[0-9a-f]{16}-[0-9a-f]+\""),
                    answer.getHeaderString("ETag"));
        }
        accepting(null);
        assertPlainJsonType(resource.list(params()));
    }

    @Test
    public void aMatchingIfNoneMatchIs304AndAChangeIsA200Again() throws IOException {
        final Trip trip = saveTrip("Etag Trip", bravoOrg, true, 70, 75, "Porto, Portugal", "PT");
        final String listTag = resource.list(params("org", bravoOrg)).getHeaderString("ETag");
        final String tripTag = resource.get(trip.getId()).getHeaderString("ETag");
        Assert.assertNotEquals(listTag, resource.list(params("org", alphaOrg)).getHeaderString("ETag"),
                "another question, another validator");

        Mockito.when(request.getHeader("If-None-Match")).thenReturn(listTag);
        final Response unchanged = resource.list(params("org", bravoOrg));
        Assert.assertEquals(unchanged.getStatus(), 304);
        Assert.assertNull(unchanged.getEntity());
        Assert.assertEquals(unchanged.getHeaderString("ETag"), listTag);
        Assert.assertEquals(unchanged.getHeaderString("Cache-Control"), "public, max-age=60");
        assertVaryAccept(unchanged);

        // Weak comparison: the W/ marker is ignored, and any validator of a list may match.
        Mockito.when(request.getHeader("If-None-Match")).thenReturn("\"other\", " + tripTag.substring(2));
        Assert.assertEquals(resource.get(trip.getId()).getStatus(), 304);
        Mockito.when(request.getHeader("If-None-Match")).thenReturn("*");
        Assert.assertEquals(resource.get(trip.getId()).getStatus(), 304);
        Mockito.when(request.getHeader("If-None-Match")).thenReturn(" ");
        Assert.assertEquals(resource.get(trip.getId()).getStatus(), 200, "a blank header is no validator");

        // The same data rebuilt keeps its validators; a real change does not.
        DiscoverIndex.getInstance().invalidate();
        Mockito.when(request.getHeader("If-None-Match")).thenReturn(listTag);
        Assert.assertEquals(resource.list(params("org", bravoOrg)).getStatus(), 304, "rebuilt, nothing changed");
        trip.setTitle("Etag Trip Renamed");
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        final Response changed = resource.list(params("org", bravoOrg));
        Assert.assertEquals(changed.getStatus(), 200);
        Assert.assertNotEquals(changed.getHeaderString("ETag"), listTag);
        Mockito.when(request.getHeader("If-None-Match")).thenReturn(tripTag);
        Assert.assertEquals(resource.get(trip.getId()).getStatus(), 200, "the trip's own validator moved too");
        Assert.assertEquals(resource.get(alphaTrip.getId()).getStatus(), 200, "one trip's validator is not another's");
    }

    // ------------------------------------------------------------------ refusals

    @Test
    public void aMalformedParameterIs400() {
        assertError(resource.list(params("month", "sometime")), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.list(params("sort", "price")), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.list(params("from", "2027-02-01", "to", "2027-01-01")), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.list(params("cursor", "garbage!")), 400, ApiErrors.BAD_REQUEST);
        Assert.assertNull(resource.list(params("limit", "x")).getHeaderString("ETag"));
    }

    @Test
    public void aBurstFromOneAddressIs429WithRetryAfter() {
        resource = discover(2);
        Assert.assertEquals(resource.list(params()).getStatus(), 200);
        Assert.assertEquals(resource.get(alphaTrip.getId()).getStatus(), 200, "the list and a trip share the count");

        for (final Response refused : List.of(resource.list(params()), resource.get(alphaTrip.getId()),
                resource.get(quietTrip.getId()), resource.list(params("sort", "nonsense")))) {
            assertError(refused, 429, ChatErrors.RATE_LIMITED);
            final int retryAfter = Integer.parseInt(refused.getHeaderString("Retry-After"));
            Assert.assertTrue(retryAfter >= 1 && retryAfter <= 60, "seconds until the window turns: " + retryAfter);
            Assert.assertEquals(refused.getHeaderString("Cache-Control"), "no-store", "a refusal is never cached");
            Assert.assertNull(refused.getHeaderString("ETag"));
        }

        Mockito.when(request.getRemoteAddr()).thenReturn("203.0.113.51");
        Assert.assertEquals(resource.list(params()).getStatus(), 200, "another address is not held back");
    }

    // ------------------------------------------------------------------ URLs and paging

    /** A phone cannot resolve a site-relative path: in local mode every URL is finished on the request's origin. */
    @Test
    public void everyUrlThatLeavesIsAbsolute() {
        Mockito.when(request.getScheme()).thenReturn("http");
        Mockito.when(request.getServerName()).thenReturn("localhost");
        Mockito.when(request.getServerPort()).thenReturn(8091);
        Mockito.when(request.getContextPath()).thenReturn("");

        final DiscoverTripDto trip = (DiscoverTripDto) resource.get(alphaTrip.getId()).getEntity();

        Assert.assertEquals(trip.detailsUrl(), "http://localhost:8091/trip/tripPage.jsf?trip=" + alphaTrip.getId());
        Assert.assertEquals(trip.pictures().get(0).url480(),
                "http://localhost:8091/trip-pictures/tripPictures/" + STAMP + "/cover-480.jpg");
        Assert.assertTrue(listed("org", alphaOrg).items().get(0).detailsUrl().startsWith("http://localhost:8091/"));
    }

    @Test
    public void aListPagesByCursorAndCountsItsFacets() {
        final DiscoverListDto first = listed("org", alphaOrg, "org", bravoOrg, "limit", "1");
        Assert.assertEquals(first.total(), 2);
        Assert.assertEquals(first.items().get(0).id(), alphaTrip.getId());
        Assert.assertNotNull(first.nextCursor());

        final DiscoverListDto second = listed("org", alphaOrg, "org", bravoOrg, "limit", "1", "cursor",
                first.nextCursor());
        Assert.assertEquals(second.items().get(0).id(), bravoTrip.getId());
        Assert.assertNull(second.nextCursor(), "the last page names no next");

        final Map<String, Integer> countries = new HashMap<>();
        first.facets().countries().forEach(facet -> countries.put(facet.value(), facet.count()));
        Assert.assertEquals(countries, Map.of("CH", 1, "PT", 1));
    }
}
