package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Trip albums as a source of pictures: which albums are listed for the trip being edited, which photos they offer,
 * and that a pick arriving as a bare id is refused unless every rule holds (same organization, an album the caller
 * may open, an image not kept out of public view). The rules run against this test's maps through the seams; one
 * test at the end runs the real reads against the local fixtures.
 */
public class AlbumPhotoSourceTest {

    private static final String ORG = "org-a";
    private static final String OTHER_ORG = "org-b";

    private final Map<String, Trip> trips = new HashMap<>();
    private final Map<String, List<MediaItem>> albums = new HashMap<>();
    private final Map<String, byte[]> bytes = new HashMap<>();
    /** The organizations the site scope reaches, and the ones {@link #member} administers, in this test. */
    private final Set<String> reachable = new HashSet<>();
    private final Set<String> orgAdmins = new HashSet<>();
    private Person.Id memberId;
    private Caller member;
    private Caller admin;
    private PrivilegeCommands priv;
    private AlbumPhotoSource source;

    /**
     * The last test reads the seeded album through the real DAO. The suite shares one store, and a class that ran
     * before this one may have cleared it, so the seeds this class needs are put back first.
     */
    @BeforeClass
    public void seed() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    @BeforeMethod
    public void setUp() {
        trips.clear();
        albums.clear();
        bytes.clear();
        reachable.clear();
        orgAdmins.clear();
        memberId = Person.Id.newInstance();
        priv = Mockito.mock(PrivilegeCommands.class);
        member = new Caller(memberId, false, AuditActor.from(null), priv);
        admin = new Caller(Person.Id.newInstance(), true, AuditActor.from(null), priv);
        source = new TestSource();

        // The trip a host is open on: next year's, no album yet, with the member on it (its own album follows the
        // album rule like any other, so whoever picks from it may open it).
        trip("target", "Medjugorje 2027", ORG, 2027, List.of(memberId));
        // Two earlier trips of the same organization the member traveled on, and one they did not.
        trip("older", "2025 Oct: Peru Mission", ORG, 2025, List.of(memberId));
        trip("newer", "2026 Sep: Holy Angels", ORG, 2026, List.of(memberId));
        trip("stranger", "2026 Jun: Anniversary", ORG, 2026, List.of());
        // A trip of ANOTHER organization the member also traveled on.
        trip("elsewhere", "Beta Retreat", OTHER_ORG, 2026, List.of(memberId));

        album("newer", photo("n1", "newer", "image/jpeg", false), photo("n2", "newer", "image/heic", false),
                photo("n-hidden", "newer", "image/jpeg", true), photo("n-video", "newer", "video/mp4", false));
        album("older", photo("o1", "older", "image/png", false));
        album("stranger", photo("s1", "stranger", "image/jpeg", false));
        album("elsewhere", photo("e1", "elsewhere", "image/jpeg", false));
    }

    private void trip(final String id, final String title, final String orgId, final int year,
            final List<Person.Id> people) {
        final Trip trip = Trip.builder().id(id).title(title).people(new ArrayList<>(people))
                .startDate(LocalDateTime.of(year, 9, 24, 8, 0)).endDate(LocalDateTime.of(year, 10, 3, 8, 0)).build();
        trip.setOrgId(orgId);
        trips.put(id, trip);
    }

    private void album(final String tripId, final MediaItem... rows) {
        albums.put(tripId, List.of(rows));
    }

    private MediaItem photo(final String id, final String tripId, final String contentType, final boolean hidden) {
        final String key = "chat/" + tripId + "/" + id + ".jpg";
        bytes.put(key, new byte[] {1, 2, 3});
        return new MediaItem(id, key, "IMG_" + id, "Shared by Trinity", contentType, 500_000L,
                ChatPhotos.slotFor(tripId), 0, LocalDateTime.of(2026, 9, 27, 10, 0), "someone", key + "-small",
                hidden ? Boolean.TRUE : null);
    }

    /** The source with every read answered by this test's maps. */
    private final class TestSource extends AlbumPhotoSource {

        @Override
        protected Caller caller() {
            return member;
        }

        @Override
        protected Optional<Trip> tripOf(final String tripId) {
            return Optional.ofNullable(trips.get(tripId));
        }

        @Override
        protected List<Trip> candidateTrips(final Caller who) {
            return List.copyOf(trips.values());
        }

        @Override
        protected Map<String, List<MediaItem>> albumRows() {
            return albums;
        }

        @Override
        protected Optional<MediaItem> itemOf(final String mediaId) {
            return albums.values().stream().flatMap(List::stream).filter(row -> row.getId().equals(mediaId))
                    .findFirst();
        }

        @Override
        protected boolean reachableHere(final String orgId) {
            return reachable.contains(orgId);
        }

        @Override
        protected boolean isOrgAdmin(final Caller who, final String orgId) {
            return orgAdmins.contains(orgId) && memberId.equals(who.personId());
        }

        @Override
        protected String photoBase() {
            return "/chat-photos/";
        }

        @Override
        protected Optional<byte[]> storedBytes(final String s3Key) {
            return Optional.ofNullable(bytes.get(s3Key));
        }
    }

    /** The scope of a host editing this trip: what the trip editor's Pictures dialog asks in. */
    private static String scope(final String tripId) {
        return AlbumPhotoSource.tripScope(tripId);
    }

    private static List<String> ids(final List<AlbumPhotoSource.Album> listed) {
        return listed.stream().map(AlbumPhotoSource.Album::tripId).toList();
    }

    // --- which albums ---

    @Test
    public void theTripsOwnAlbumIsFirstThenTheOnesTheCallerMayOpenNewestFirst() {
        final List<AlbumPhotoSource.Album> listed = source.albumsFor(member, scope("target"));
        Assert.assertEquals(ids(listed), List.of("target", "newer", "older"),
                "Not the album of a trip they were not on, and never another organization's");

        final AlbumPhotoSource.Album own = listed.getFirst();
        Assert.assertTrue(own.current());
        Assert.assertTrue(own.photos().isEmpty(), "Listed even while it has no photos");
        Assert.assertEquals(own.getCountLabel(), "No photos yet");
        Assert.assertNull(own.getCoverUrl());
        Assert.assertEquals(own.getWithheldNote(), "");

        Assert.assertEquals(source.albumsOf(scope("target")), listed, "The page form asks for the signed-in caller");
    }

    @Test
    public void anAlbumOffersItsPublicImagesAndCountsTheOnesHeldBack() {
        final AlbumPhotoSource.Album newer = source.albumsFor(member, scope("target")).get(1);
        Assert.assertEquals(newer.title(), "2026 Sep: Holy Angels");
        Assert.assertFalse(newer.current());
        Assert.assertEquals(newer.photos().stream().map(AlbumPhotoSource.Photo::id).toList(), List.of("n1", "n2"),
                "The hidden photo and the video are not offered");
        Assert.assertEquals(newer.withheld(), 1, "Only the hidden IMAGE is counted; a video was never a candidate");
        Assert.assertEquals(newer.getCountLabel(), "2 photos");
        Assert.assertEquals(newer.getWithheldNote(),
                "1 photo in this album is not offered: it was kept out of public view.");
        Assert.assertEquals(newer.photos().getFirst().url(), "/chat-photos/chat/newer/n1.jpg-small",
                "The reduced copy, on the page's photo base");
        Assert.assertEquals(newer.getCoverUrl(), newer.photos().getFirst().url());
        Assert.assertEquals(newer.photos().getFirst().label(), "Shared by Trinity");
        Assert.assertFalse(newer.dates().isBlank());

        Assert.assertEquals(source.albumsFor(member, scope("target")).get(2).getCountLabel(), "1 photo");
    }

    @Test
    public void aPhotoTurnedInPlaceCarriesItsRevisionAndALabelFallsBackToTheTitle() {
        final MediaItem turned = photo("t1", "older", "image/jpeg", false).withRotated(1L, null);
        final MediaItem untitled = new MediaItem("t2", "chat/older/t2.jpg", " ", null, "image/jpeg", 1L,
                ChatPhotos.slotFor("older"), 0, null, "someone", null, null);
        final MediaItem titled = new MediaItem("t3", "chat/older/t3.jpg", "Sunrise", " ", "image/jpeg", 1L,
                ChatPhotos.slotFor("older"), 0, null, "someone", null, null);
        album("older", turned, untitled, titled);
        final List<AlbumPhotoSource.Photo> photos = source.albumsFor(member, scope("target")).get(2).photos();
        Assert.assertEquals(photos.get(0).url(), "/chat-photos/chat/older/t1.jpg?r=1",
                "No reduced copy: the object itself, with the turn's cache buster");
        Assert.assertEquals(photos.get(1).label(), "Photo");
        Assert.assertEquals(photos.get(2).label(), "Sunrise");
    }

    @Test
    public void anAlbumOfOnlyHeldBackPhotosIsListedToExplainItselfAndAnEmptyOneIsNot() {
        album("newer", photo("h1", "newer", "image/jpeg", true), photo("h2", "newer", "image/jpeg", true));
        album("older");
        final List<AlbumPhotoSource.Album> listed = source.albumsFor(member, scope("target"));
        Assert.assertEquals(ids(listed), List.of("target", "newer"));
        Assert.assertEquals(listed.get(1).getWithheldNote(),
                "2 photos in this album are not offered: they were kept out of public view.");
    }

    @Test
    public void nobodyAndNoTripGetNothing() {
        final Caller anonymous = new Caller(null, false, AuditActor.from(null), priv);
        Assert.assertTrue(source.albumsFor(anonymous, scope("target")).isEmpty());
        Assert.assertTrue(source.albumsFor(null, scope("target")).isEmpty());
        Assert.assertTrue(source.albumsFor(member, scope("no-such-trip")).isEmpty());
        Assert.assertTrue(source.albumsOf(null).isEmpty());
        Assert.assertTrue(source.albumsOf(" ").isEmpty());
        Assert.assertNull(source.albumOf(scope("no-such-trip"), "newer"));
    }

    @Test
    public void aTripWithNoDatesOrNoOrganizationStillLists() {
        final Trip undated = Trip.builder().id("undated").title("Undated").people(List.of(memberId)).build();
        undated.setStartDate(null);     // the builder dates a new trip; a stored one can still lack a date
        trips.put("undated", undated);
        album("undated", photo("u1", "undated", "image/jpeg", false));
        final Trip orgless = Trip.builder().id("orgless").title("Legacy").people(List.of(memberId)).build();
        orgless.setOrgId(" ");
        trips.put("orgless", orgless);

        final List<AlbumPhotoSource.Album> listed = source.albumsFor(member, scope("orgless"));
        Assert.assertEquals(ids(listed), List.of("orgless", "undated"),
                "A trip with no organization sees only others with none");
        Assert.assertEquals(listed.get(1).dates(), "");
    }

    // --- one album's rows, for the apps ---

    @Test
    public void anAlbumsRowsAreTheOnesASendWouldAccept() {
        final List<MediaItem> rows = source.offeredRowsFor(member, scope("target"), "newer");
        Assert.assertEquals(rows.stream().map(MediaItem::getId).toList(), List.of("n1", "n2"),
                "Neither the hidden photo nor the video");
        for (final MediaItem row : rows) {
            Assert.assertTrue(source.offered(member, scope("target"), row.getId()).isPresent(),
                    "A listed photo is one the send accepts: " + row.getId());
        }
        Assert.assertEquals(source.albumsFor(member, scope("target")).getFirst().startDate(),
                trips.get("target").getStartDate(), "An album carries its trip's start for a client to spell");
        Assert.assertTrue(source.offeredRowsFor(member, scope("target"), "target").isEmpty(), "The own one is empty");
    }

    @Test
    public void anAlbumsRowsFollowTheListingsRules() {
        Assert.assertTrue(source.offeredRowsFor(member, scope("target"), "stranger").isEmpty(),
                "Not on that trip, and no role over it");
        Assert.assertTrue(source.offeredRowsFor(member, scope("target"), "elsewhere").isEmpty(),
                "Another organization's album, though the member traveled on it");
        Assert.assertTrue(source.offeredRowsFor(member, scope("target"), "no-such-trip").isEmpty());
        Assert.assertTrue(source.offeredRowsFor(member, scope("no-such-trip"), "newer").isEmpty());
        Assert.assertTrue(source.offeredRowsFor(null, scope("target"), "newer").isEmpty());
        Assert.assertTrue(source.offeredRowsFor(new Caller(null, false, AuditActor.from(null), priv),
                scope("target"), "newer").isEmpty());

        // An admin of the organization opens every one of its albums, roster or not: the case the apps lacked.
        orgAdmins.add(ORG);
        Assert.assertEquals(source.offeredRowsFor(member, scope("target"), "stranger").stream()
                .map(MediaItem::getId).toList(), List.of("s1"));
        Assert.assertTrue(source.offeredRowsFor(member, scope("target"), "elsewhere").isEmpty(),
                "Still never another organization's");
    }

    // --- which album the dialog shows ---

    @Test
    public void theDialogShowsTheAlbumAskedForElseTheFirstWithPhotos() {
        Assert.assertEquals(source.albumOf(scope("target"), "older").tripId(), "older");
        Assert.assertEquals(source.albumOf(scope("target"), null).tripId(), "newer", "The trip's own is empty");
        Assert.assertEquals(source.albumOf(scope("target"), "elsewhere").tripId(), "newer",
                "An album that is not offered is not shown for the asking");
        album("newer");
        album("older");
        Assert.assertEquals(source.albumOf(scope("target"), null).tripId(),
                "target", "Nothing has photos: the trip's own");
    }

    // --- who may open an album ---

    @Test
    public void theRosterAndTheTripsViewersAndManagersMayOpenAnAlbum() {
        final Trip stranger = trips.get("stranger");
        Assert.assertTrue(source.mayOpen(member, trips.get("newer")), "On the roster");
        Assert.assertFalse(source.mayOpen(member, stranger));
        Assert.assertTrue(source.mayOpen(admin, stranger), "A site admin holds every privilege");

        Mockito.when(priv.check(PrivilegeCommands.TRIP_VIEW, "stranger", memberId)).thenReturn(true);
        Assert.assertTrue(source.mayOpen(new Caller(memberId, false, AuditActor.from(null), priv), stranger));
        Mockito.when(priv.check(PrivilegeCommands.TRIP_VIEW, "stranger", memberId)).thenReturn(false);
        Mockito.when(priv.check(PrivilegeCommands.TRIP_MGR, "stranger", memberId)).thenReturn(true);
        Assert.assertTrue(source.mayOpen(new Caller(memberId, false, AuditActor.from(null), priv), stranger));

        Assert.assertFalse(source.mayOpen(null, stranger));
        Assert.assertFalse(source.mayOpen(new Caller(null, false, AuditActor.from(null), priv), stranger));
        Assert.assertFalse(source.mayOpen(member, null));
        Assert.assertFalse(source.mayOpen(member, Trip.builder().id(null).build()));
    }

    // --- a pick is a bare id ---

    @Test
    public void aPickIsOfferedOnlyWhenEveryRuleHolds() {
        final String target = scope("target");
        Assert.assertEquals(source.offered(member, target, "n1").orElseThrow().getId(), "n1");
        Assert.assertTrue(source.offered(member, target, "n-hidden").isEmpty(), "Kept out of public view");
        Assert.assertTrue(source.offered(member, target, "n-video").isEmpty(), "Not an image");
        Assert.assertTrue(source.offered(member, target, "s1").isEmpty(), "An album they may not open");
        Assert.assertTrue(source.offered(member, target, "e1").isEmpty(), "Another organization's album");
        Assert.assertTrue(source.offered(admin, target, "e1").isEmpty(),
                "Another organization's album, even for a site admin");
        Assert.assertTrue(source.offered(admin, target, "s1").isPresent(), "A site admin may open any album of it");
        Assert.assertTrue(source.offered(member, target, "no-such-photo").isEmpty());
        Assert.assertTrue(source.offered(member, target, " ").isEmpty());
        Assert.assertTrue(source.offered(member, target, null).isEmpty());
        Assert.assertTrue(source.offered(member, null, "n1").isEmpty());
        Assert.assertTrue(source.offered(null, target, "n1").isEmpty());
        Assert.assertTrue(source.offered(new Caller(null, false, AuditActor.from(null), priv), target, "n1")
                .isEmpty());
    }

    @Test
    public void aTripsOwnAlbumIsNoExceptionAndALibraryFileIsNeverAnAlbumPhoto() {
        // The host's own trip follows the album rule like any other: someone who may post in its chat (an invite
        // guest) or reach its page, but may not open its album, is neither shown it nor offered its photos.
        final Caller offRoster = new Caller(Person.Id.newInstance(), false, AuditActor.from(null), priv);
        album("target", photo("own1", "target", "image/jpeg", false));
        Assert.assertTrue(source.offered(member, scope("target"), "own1").isPresent(), "Its own traveler may");
        Assert.assertTrue(source.offered(offRoster, scope("target"), "own1").isEmpty(), "A guest may not");
        Assert.assertFalse(ids(source.albumsFor(offRoster, scope("target"))).contains("target"),
                "Nor is the guest shown it");

        // A media-library row (no chat slot) is not an album photo, whatever its type.
        final MediaItem library = new MediaItem("lib1", "org/x/flyer.jpg", "Flyer", null, "image/jpeg", 1L,
                "home-docs", 0, null, "someone", null, null);
        album("newer", library);
        Assert.assertTrue(source.offered(member, scope("target"), "lib1").isEmpty());

        // An album row whose trip is gone names no organization to compare.
        final MediaItem orphan = photo("orphan", "deleted-trip", "image/jpeg", false);
        album("newer", orphan);
        Assert.assertTrue(source.offered(member, scope("target"), "orphan").isEmpty());
    }

    @Test
    public void aPicksBytesAreTheFullSizeObject() {
        final MediaItem item = source.offered(member, scope("target"), "n1").orElseThrow();
        Assert.assertEquals(source.bytesOf(item).orElseThrow(), new byte[] {1, 2, 3});
        bytes.clear();
        Assert.assertTrue(source.bytesOf(item).isEmpty());
        Assert.assertTrue(source.bytesOf(null).isEmpty());
    }

    // --- the real reads, against the local fixtures ---

    @Test
    public void theRealReadsListASeededAlbumAndReadItsBytes() {
        final AlbumPhotoSource real = new AlbumPhotoSource();
        final Caller siteAdmin = TestCallers.siteAdmin();
        final List<AlbumPhotoSource.Album> listed = real.albumsFor(siteAdmin, scope(FakeData.PUB_PAST_3D_TRIP_ID));
        Assert.assertFalse(listed.isEmpty());
        final AlbumPhotoSource.Album own = listed.getFirst();
        Assert.assertEquals(own.tripId(), FakeData.PUB_PAST_3D_TRIP_ID);
        Assert.assertEquals(own.photos().size(), 11, "Twelve seeded, one of them hidden");
        Assert.assertEquals(own.withheld(), 1);
        Assert.assertTrue(own.getCoverUrl().contains("chat-photos/"), own.getCoverUrl());

        final MediaItem first = real.offered(siteAdmin, scope(FakeData.PUB_PAST_3D_TRIP_ID),
                own.photos().getFirst().id()).orElseThrow();
        Assert.assertTrue(real.bytesOf(first).orElseThrow().length > 0);
        Assert.assertTrue(real.tripOf(null).isEmpty());
        Assert.assertTrue(real.tripOf(" ").isEmpty());
        Assert.assertTrue(real.itemOf("no-such-media-row").isEmpty());

        // The other scopes and seams, on the real reads: off a request every organization is reachable.
        Assert.assertTrue(real.reachableHere(FakeData.CFPW_ORG_ID));
        Assert.assertTrue(ids(real.albumsFor(siteAdmin, AlbumPhotoSource.SCOPE_SITE))
                .contains(FakeData.PUB_PAST_3D_TRIP_ID));
        final String seededOrg = real.tripOf(FakeData.PUB_PAST_3D_TRIP_ID).orElseThrow().getOrgId();
        Assert.assertEquals(ids(real.albumsFor(siteAdmin, AlbumPhotoSource.orgScope(FakeData.CFPW_ORG_ID)))
                .contains(FakeData.PUB_PAST_3D_TRIP_ID), FakeData.CFPW_ORG_ID.equals(seededOrg),
                "An organization's scope lists an album exactly when its trip is that organization's");
        Assert.assertFalse(real.isOrgAdmin(siteAdmin, null), "No organization has no admins");
        Assert.assertFalse(real.isOrgAdmin(TestCallers.person(Person.Id.newInstance()), FakeData.CFPW_ORG_ID));
        Assert.assertFalse(real.isOrgAdmin(TestCallers.person(Person.Id.newInstance()),
                "00000000-0000-4000-8000-000000000000"),
                "An organization that does not exist has no admins either");
        Assert.assertTrue(real.albumsOf(scope(FakeData.PUB_PAST_3D_TRIP_ID)).isEmpty(),
                "Off a page there is nobody signed in, so nothing is listed");
    }

    // --- the other scopes ---

    @Test
    public void anOrganizationScopeOffersThatOrganizationsAlbumsAndNoTripOfItsOwn() {
        final List<AlbumPhotoSource.Album> listed = source.albumsFor(member, AlbumPhotoSource.orgScope(ORG));
        Assert.assertEquals(ids(listed), List.of("newer", "older"), "No current trip heads the list");
        Assert.assertFalse(listed.getFirst().current());
        Assert.assertEquals(ids(source.albumsFor(member, AlbumPhotoSource.orgScope(OTHER_ORG))), List.of("elsewhere"));
        Assert.assertTrue(source.offered(member, AlbumPhotoSource.orgScope(ORG), "n1").isPresent());
        Assert.assertTrue(source.offered(member, AlbumPhotoSource.orgScope(ORG), "e1").isEmpty());
        Assert.assertTrue(source.offered(member, AlbumPhotoSource.orgScope(OTHER_ORG), "n1").isEmpty());
    }

    @Test
    public void theMineScopeOffersEveryAlbumThePersonMayOpenWhateverItsOrganization() {
        Assert.assertEquals(ids(source.albumsFor(member, AlbumPhotoSource.SCOPE_MINE)),
                List.of("newer", "elsewhere", "older"), "Both organizations; still not the trip they were not on");
        Assert.assertTrue(source.offered(member, AlbumPhotoSource.SCOPE_MINE, "e1").isPresent());
        Assert.assertTrue(source.offered(member, AlbumPhotoSource.SCOPE_MINE, "s1").isEmpty());
        Assert.assertTrue(source.offered(member, AlbumPhotoSource.SCOPE_MINE, "n-hidden").isEmpty(),
                "Kept out of public view, in every scope");
    }

    @Test
    public void theSiteScopeOffersTheAlbumsOfTheTripsThisSiteServes() {
        reachable.add(ORG);
        Assert.assertEquals(ids(source.albumsFor(member, AlbumPhotoSource.SCOPE_SITE)), List.of("newer", "older"));
        Assert.assertTrue(source.offered(member, AlbumPhotoSource.SCOPE_SITE, "e1").isEmpty());
        reachable.add(OTHER_ORG);
        Assert.assertTrue(source.offered(member, AlbumPhotoSource.SCOPE_SITE, "e1").isPresent());
    }

    @Test
    public void aScopeThatNamesNothingOffersNothing() {
        for (final String scope : new String[] {null, "", " ", "org:", "org: ", "everything", "trip:no-such-trip"}) {
            Assert.assertTrue(source.albumsFor(member, scope).isEmpty(), "scope '" + scope + "'");
            Assert.assertTrue(source.offered(member, scope, "n1").isEmpty(), "scope '" + scope + "'");
        }
    }

    @Test
    public void anAlbumsManagersAndItsOrganizationsAdminsMayOpenItWithoutBeingOnTheTrip() {
        final Trip stranger = trips.get("stranger");
        Mockito.when(priv.check(PrivilegeCommands.MEDIA_ADMIN, ORG, memberId)).thenReturn(true);
        Assert.assertTrue(source.mayOpen(new Caller(memberId, false, AuditActor.from(null), priv), stranger),
                "The organization's media admin already manages the whole album");
        Mockito.when(priv.check(PrivilegeCommands.MEDIA_ADMIN, ORG, memberId)).thenReturn(false);
        Assert.assertFalse(source.mayOpen(new Caller(memberId, false, AuditActor.from(null), priv), stranger));

        orgAdmins.add(ORG);
        Assert.assertTrue(source.mayOpen(member, stranger), "An admin of the trip's organization");
        Assert.assertFalse(source.mayOpen(member, trips.get("orgless-missing")), "No trip");
        final Trip orgless = Trip.builder().id("no-org").title("Legacy").build();
        Assert.assertFalse(source.mayOpen(member, orgless), "A trip with no organization has no admins");
    }
}
