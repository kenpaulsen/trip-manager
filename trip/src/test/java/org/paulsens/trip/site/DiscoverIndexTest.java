package org.paulsens.trip.site;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.paulsens.trip.action.TripPageCommands;
import org.paulsens.trip.api.dto.DiscoverTripDto;
import org.paulsens.trip.api.dto.MediaItemDto;
import org.paulsens.trip.api.mapper.DiscoverTripMapper;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link DiscoverIndex}: what the snapshot holds (only what {@code ListingScope.discoverable} admits), when it is
 * rebuilt (stale by the clock, or marked by a save), and that a failure never empties a list that was being
 * served. Loaders, clock and mapper are the test's own, so every rebuild is counted.
 */
public class DiscoverIndexTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
    private static final String ALLOWING = "11111111-1111-4111-8111-111111111111";
    private static final String OPTED_OUT = "33333333-3333-4333-8333-333333333333";
    private static final String UNKNOWN = "44444444-4444-4444-8444-444444444444";

    private final List<Trip> trips = new ArrayList<>();
    private final List<Organization> orgs = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AtomicInteger loads = new AtomicInteger();
    private boolean loaderFails;
    private Runnable duringLoad;
    private DiscoverIndex index;

    @BeforeMethod
    public void freshIndex() {
        trips.clear();
        orgs.clear();
        loads.set(0);
        loaderFails = false;
        duringLoad = null;
        orgs.add(org(ALLOWING, "Allowing Org", null));
        orgs.add(org(OPTED_OUT, "Quiet Org", Boolean.FALSE));
        index = DiscoverIndex.over(this::loadTrips, () -> List.copyOf(orgs), () -> DiscoverIndexTest::plainDto,
                () -> TODAY, now::get);
    }

    private List<Trip> loadTrips() {
        loads.incrementAndGet();
        if (loaderFails) {
            throw new IllegalStateException("the cache is down");
        }
        if (duringLoad != null) {
            duringLoad.run();
        }
        return List.copyOf(trips);
    }

    /** The real allow-list mapping, with the looked-up values a unit test has no beans for left empty. */
    private static DiscoverTripDto plainDto(final Trip trip, final Organization org) {
        if (trip.getTitle() != null && trip.getTitle().startsWith("Unmappable")) {
            throw new IllegalStateException("this trip cannot be mapped");
        }
        return DiscoverTripMapper.toDto(trip, org, List.of(), List.of(), null,
                "/trip/tripPage.jsf?trip=" + trip.getId(), TripPageCommands.Phase.OPEN, null);
    }

    private static Organization org(final String id, final String name, final Boolean allowShared) {
        final Organization org = new Organization();
        org.setId(Organization.Id.from(id));
        org.setName(name);
        org.setAllowSharedSites(allowShared);
        return org;
    }

    private Trip trip(final String id, final String title, final String orgId, final boolean open,
            final int startInDays) {
        final Trip trip = Trip.builder()
                .id(id)
                .title(title)
                .openToPublic(open)
                .startDate(TODAY.plusDays(startInDays).atTime(8, 0))
                .endDate(TODAY.plusDays(startInDays + 9L).atTime(17, 0))
                .build();
        trip.setOrgId(orgId);
        trips.add(trip);
        return trip;
    }

    private List<String> listedIds() {
        return index.current().entries().stream().map(entry -> entry.trip().id()).sorted().toList();
    }

    // ------------------------------------------------------------------ what is listed

    /** The whole rule at the index: one trip listed, five refused for five different reasons. */
    @Test
    public void onlyDiscoverableTripsAreInTheSnapshot() {
        trip("listed", "Listed Trip", ALLOWING, true, 30);
        trip("org-opted-out", "Quiet Trip", OPTED_OUT, true, 30);
        trip("off-homepage", "Private Trip", ALLOWING, false, 30);
        trip("no-org", "Legacy Trip", null, true, 30);
        trip("unknown-org", "Orphan Trip", UNKNOWN, true, 30);
        trip("ended", "Old Trip", ALLOWING, true, -30);

        final DiscoverIndex.Snapshot snapshot = index.current();

        Assert.assertEquals(listedIds(), List.of("listed"));
        Assert.assertTrue(snapshot.find("listed").isPresent());
        Assert.assertEquals(snapshot.ended().stream().map(entry -> entry.trip().id()).toList(), List.of("ended"),
                "an ended trip is in the past list, not the upcoming one");
        Assert.assertTrue(snapshot.find("ended").isPresent(), "and can be read by id");
        for (final String refused : List.of("org-opted-out", "off-homepage", "no-org", "unknown-org",
                "never-existed")) {
            Assert.assertTrue(snapshot.find(refused).isEmpty(), refused + " must be the same miss as no trip");
        }
        Assert.assertTrue(snapshot.find(null).isEmpty());
    }

    /** An entry carries the DTO and search text made FROM the DTO: the organization's name, never a hidden field. */
    @Test
    public void anEntryIsItsPublicShapeAndTextDerivedFromIt() {
        final Trip trip = trip("listed", "2026 Dec: Winter Trip", ALLOWING, true, 30);
        trip.setDestination("Fátima, Portugal");
        trip.setCountry("pt");
        trip.setDescription("secret-description-word");
        trip.setFlyerUrl("https://example.com/secret-flyer.pdf");

        final DiscoverIndex.Entry entry = index.current().find("listed").orElseThrow();

        Assert.assertEquals(entry.trip().title(), "Winter Trip", "the short title");
        Assert.assertEquals(entry.trip().orgName(), "Allowing Org");
        Assert.assertEquals(entry.trip().countries(), List.of("PT"), "one country is a list of one");
        Assert.assertEquals(entry.countryLabels(), List.of("Portugal"));
        Assert.assertEquals(entry.countryKey(), "portugal");
        Assert.assertEquals(entry.month().toString(), TODAY.plusDays(30).toString().substring(0, 7));
        Assert.assertTrue(entry.haystack().contains("winter trip"));
        Assert.assertTrue(entry.haystack().contains("fatima, portugal"), "accents folded: " + entry.haystack());
        Assert.assertTrue(entry.haystack().contains("allowing org"));
        Assert.assertFalse(entry.haystack().contains("secret"), "what a response cannot say, search cannot match");
        Assert.assertEquals(entry.titleKey(), "winter trip");
        Assert.assertEquals(entry.orgKey(), "allowing org");
        Assert.assertEquals(entry.etag().length(), 16);
    }

    /** A trip that visits several countries: every one is named, searchable, and it sorts by the first by name. */
    @Test
    public void anEntryNamesEveryCountryTheTripVisits() {
        final Trip trip = trip("listed", "Fatima and Lourdes", ALLOWING, true, 30);
        trip.setCountries(List.of("pt", "FR", "not-a-country", "PT"));
        trip("nowhere", "No Place Yet", ALLOWING, true, 31);

        final DiscoverIndex.Entry entry = index.current().find("listed").orElseThrow();

        Assert.assertEquals(entry.trip().countries(), List.of("PT", "FR"),
                "real codes, each once, in the trip's order");
        Assert.assertEquals(entry.countryLabels(), List.of("Portugal", "France"), "side by side with the codes");
        Assert.assertEquals(entry.countryKey(), "france",
                "sorted by the alphabetically first name, not the first listed");
        Assert.assertTrue(entry.haystack().contains("portugal") && entry.haystack().contains("france"),
                entry.haystack());
        final DiscoverIndex.Entry nowhere = index.current().find("nowhere").orElseThrow();
        Assert.assertEquals(nowhere.trip().countries(), List.of(), "no country is an empty list, never null");
        Assert.assertEquals(nowhere.countryLabels(), List.of());
        Assert.assertNull(nowhere.countryKey());
    }

    // ------------------------------------------------------------------ the past list and the albums

    /** The past list relaxes "not ended" and nothing else: an ended trip still needs every kind of consent. */
    @Test
    public void thePastListHoldsEndedTripsThatAreListedInEveryOtherRespect() {
        trip("upcoming", "Upcoming", ALLOWING, true, 30);
        trip("ended", "Ended", ALLOWING, true, -30);
        trip("ended-yesterday", "Ended Yesterday", ALLOWING, true, -10);
        trip("ended-quiet", "Ended Quiet", OPTED_OUT, true, -30);
        trip("ended-private", "Ended Private", ALLOWING, false, -30);
        trip("ended-orgless", "Ended Legacy", null, true, -30);
        // Read twice (the active and the ended reads may overlap): still one entry.
        trips.add(trips.get(1));

        final DiscoverIndex.Snapshot snapshot = index.current();

        Assert.assertEquals(snapshot.list(true).stream().map(entry -> entry.trip().id()).sorted().toList(),
                List.of("ended", "ended-yesterday"));
        Assert.assertEquals(snapshot.list(false).stream().map(entry -> entry.trip().id()).toList(),
                List.of("upcoming"));
        for (final String refused : List.of("ended-quiet", "ended-private", "ended-orgless")) {
            Assert.assertTrue(snapshot.find(refused).isEmpty(), refused);
        }
    }

    private static final MediaItemDto PHOTO = new MediaItemDto("p1", "chat/listed/a.jpg", null, null, "image/jpeg",
            9L, "tripChat-listed", 0, null, null, false, "https://cdn.example/chat/listed/a.jpg", 0, null);

    /** Two photos for "listed", a failure for "broken", none for anything else. */
    private static List<MediaItemDto> albumOf(final String tripId) {
        if ("broken".equals(tripId)) {
            throw new IllegalStateException("the media table is down");
        }
        return "listed".equals(tripId) ? List.of(PHOTO, PHOTO) : List.of();
    }

    @Test
    public void anEntryHoldsItsPublicAlbumAndCountsIt() {
        final MediaItemDto photo = PHOTO;
        index = DiscoverIndex.over(this::loadTrips, () -> List.copyOf(orgs), () -> DiscoverIndexTest::plainDto,
                () -> DiscoverIndexTest::albumOf, () -> TODAY, now::get);
        trip("listed", "Listed", ALLOWING, true, -30);
        trip("bare", "Bare", ALLOWING, true, 30);
        trip("broken", "Broken Album", ALLOWING, true, 31);

        final DiscoverIndex.Entry listed = index.current().find("listed").orElseThrow();
        Assert.assertEquals(listed.photos(), List.of(photo, photo));
        Assert.assertEquals(listed.trip().photoCount(), 2);
        Assert.assertEquals(listed.photosEtag().length(), 16);
        Assert.assertEquals(index.current().find("bare").orElseThrow().trip().photoCount(), 0);
        final DiscoverIndex.Entry broken = index.current().find("broken").orElseThrow();
        Assert.assertEquals(broken.photos(), List.of(), "an album that cannot be read is empty, the trip stays");
        Assert.assertNotEquals(listed.photosEtag(), broken.photosEtag());
    }

    // ------------------------------------------------------------------ when it rebuilds

    @Test
    public void theSnapshotIsReusedUntilItIsAMinuteOld() {
        trip("first", "First", ALLOWING, true, 30);
        Assert.assertEquals(listedIds(), List.of("first"));
        trip("second", "Second", ALLOWING, true, 40);

        now.addAndGet(DiscoverIndex.REFRESH_INTERVAL.toMillis() - 1);
        Assert.assertEquals(listedIds(), List.of("first"), "still fresh: no read, the new trip is not seen yet");
        Assert.assertEquals(loads.get(), 1);

        now.addAndGet(1);
        Assert.assertEquals(listedIds(), List.of("first", "second"), "a minute old: rebuilt");
        Assert.assertEquals(loads.get(), 2);
    }

    /** A save marks the snapshot stale and does nothing else: the next REQUEST pays for the rebuild. */
    @Test
    public void invalidateMarksStaleAndTheNextRequestRebuilds() {
        final Trip trip = trip("listed", "Listed", ALLOWING, true, 30);
        Assert.assertEquals(listedIds(), List.of("listed"));

        trip.setOpenToPublic(false);
        index.invalidate();
        Assert.assertEquals(loads.get(), 1, "invalidate reads nothing and builds nothing");

        Assert.assertEquals(listedIds(), List.of(), "taken off its homepage: gone on the very next request");
        Assert.assertEquals(loads.get(), 2);
        Assert.assertEquals(listedIds(), List.of());
        Assert.assertEquals(loads.get(), 2, "and that rebuild is fresh again");
    }

    @Test
    public void anOrganizationTurningSharedSitesOffTakesItsTripsOut() {
        trip("listed", "Listed", ALLOWING, true, 30);
        Assert.assertEquals(listedIds(), List.of("listed"));

        orgs.set(0, org(ALLOWING, "Allowing Org", Boolean.FALSE));
        index.invalidate();

        Assert.assertEquals(listedIds(), List.of());
        Assert.assertTrue(index.current().find("listed").isEmpty());
    }

    @Test
    public void invalidateBeforeAnythingWasBuiltIsHarmless() {
        index.invalidate();
        Assert.assertEquals(loads.get(), 0);
        trip("listed", "Listed", ALLOWING, true, 30);
        Assert.assertEquals(listedIds(), List.of("listed"));
    }

    /**
     * A save that lands WHILE a rebuild is reading may not be in what it read. The rebuild still publishes (the
     * request gets an answer), but stale, so the next request reads again instead of serving it for a minute.
     */
    @Test
    public void aSaveDuringARebuildLeavesTheSnapshotStale() {
        trip("listed", "Listed", ALLOWING, true, 30);
        duringLoad = index::invalidate;
        Assert.assertEquals(listedIds(), List.of("listed"));
        Assert.assertEquals(loads.get(), 1);

        duringLoad = null;
        Assert.assertEquals(listedIds(), List.of("listed"));
        Assert.assertEquals(loads.get(), 2, "the raced snapshot was not trusted for a whole interval");
        Assert.assertEquals(listedIds(), List.of("listed"));
        Assert.assertEquals(loads.get(), 2);
    }

    // ------------------------------------------------------------------ failures

    @Test
    public void aFailedRebuildKeepsServingThePreviousSnapshotAndRetriesSoon() {
        trip("listed", "Listed", ALLOWING, true, 30);
        final String before = index.current().fingerprint();
        loaderFails = true;
        index.invalidate();

        Assert.assertEquals(listedIds(), List.of("listed"), "a list a minute old beats an error");
        Assert.assertEquals(index.current().fingerprint(), before);
        Assert.assertEquals(loads.get(), 2, "one failed attempt, then quiet until the retry time");

        loaderFails = false;
        now.addAndGet(DiscoverIndex.FAILURE_RETRY.toMillis());
        trip("second", "Second", ALLOWING, true, 40);
        Assert.assertEquals(listedIds(), List.of("listed", "second"), "healed at the retry");
    }

    @Test
    public void aFailedFirstBuildServesAnEmptyListRatherThanThrowing() {
        loaderFails = true;

        Assert.assertEquals(listedIds(), List.of());
        Assert.assertEquals(index.current().fingerprint(), "");
        Assert.assertTrue(index.current().find("anything").isEmpty());
    }

    @Test
    public void oneTripThatCannotBeMappedIsLeftOutAndTheRestAreListed() {
        trip("good", "Good Trip", ALLOWING, true, 30);
        trip("bad", "Unmappable Trip", ALLOWING, true, 31);

        Assert.assertEquals(listedIds(), List.of("good"));
    }

    // ------------------------------------------------------------------ the fingerprint

    /** The fingerprint follows what the listed trips SAY, so an unchanged rebuild keeps every ETag handed out. */
    @Test
    public void theFingerprintChangesOnlyWhenWhatIsListedChanges() {
        final Trip trip = trip("listed", "Listed", ALLOWING, true, 30);
        trip("other", "Other", ALLOWING, true, 35);
        final String first = index.current().fingerprint();
        final String entryTag = index.current().find("listed").orElseThrow().etag();

        index.invalidate();
        Assert.assertEquals(index.current().fingerprint(), first, "rebuilt from the same data: the same");

        trips.add(trips.remove(0));
        index.invalidate();
        Assert.assertEquals(index.current().fingerprint(), first, "the order trips are read in does not matter");

        trip.setTitle("Renamed");
        index.invalidate();
        Assert.assertNotEquals(index.current().fingerprint(), first, "a title changed");
        Assert.assertNotEquals(index.current().find("listed").orElseThrow().etag(), entryTag);
        Assert.assertEquals(DiscoverIndex.fingerprintOf("abc"), DiscoverIndex.fingerprintOf("abc"));
    }

    /** The application's own index is built from suppliers only: taking it reads nothing. */
    @Test
    public void theApplicationIndexExists() {
        Assert.assertNotNull(DiscoverIndex.getInstance());
        Assert.assertSame(DiscoverIndex.getInstance(), DiscoverIndex.getInstance());
    }
}
