package org.paulsens.trip.action;

import jakarta.faces.component.UIComponent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.media.PhotoFixtures;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPicture;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.ResponsiveOption;
import org.primefaces.model.file.UploadedFile;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Trip pictures (issue 154): who may change them, the 3:2 cut into three widths, the cap, ordering, descriptions,
 * removal, the dialog's token flow, URLs and the trip-delete cascade. The bean is answered through its seams, so the
 * stored trip is a map here and nothing touches the DAO except the org-admin check, which reads the seeded org.
 */
public class TripPictureCommandsTest {

    private static final String TRIP_ID = "trip-1";

    /** An organization only {@link TestBean}'s org-admin seam knows, administered by {@link #SEAM_ORG_ADMIN}. */
    private static final String SEAM_ORG_ID = "seam-org";
    private static final Person.Id SEAM_ORG_ADMIN = Person.Id.newInstance();

    private final Map<String, Trip> stored = new ConcurrentHashMap<>();
    private final Map<String, String> session = new HashMap<>();
    private final Map<String, Object> viewMap = new HashMap<>();
    private final Map<String, Object> params = new HashMap<>();
    private final Map<Object, Object> memo = new HashMap<>();
    private final List<Boolean> outcomes = new ArrayList<>();
    private final List<Trip> saves = new ArrayList<>();
    /** The album photos the fake source offers, by media id; a null value is a photo that cannot be read. */
    private final Map<String, byte[]> albumPhotos = new HashMap<>();
    private boolean saveResult;
    /** Runs once, right after the next stored read: a trip write landing while a write is in progress. */
    private Runnable afterNextRead;
    private Caller caller;
    private PrivilegeCommands priv;
    private MediaCommands media;
    private TripPictureCommands bean;

    @BeforeMethod
    public void setUp() throws Exception {
        stored.clear();
        session.clear();
        viewMap.clear();
        params.clear();
        memo.clear();
        outcomes.clear();
        saves.clear();
        albumPhotos.clear();
        saveResult = true;
        afterNextRead = null;
        stored.put(TRIP_ID, Trip.builder().id(TRIP_ID).title("Holy Angels").build());
        viewMap.put("theTripId", TRIP_ID);
        priv = Mockito.mock(PrivilegeCommands.class);
        caller = new Caller(Person.Id.newInstance(), true, AuditActor.from(null), priv);
        media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.isUploadEnabled()).thenReturn(false);
        bean = new TestBean();
        final Field field = TripPictureCommands.class.getDeclaredField("media");
        field.setAccessible(true);
        field.set(bean, media);
        bean.setAlbumsForTest(new FakeAlbums());
        bean.setPendingUploadsForTest(new PendingUploads());
    }

    /** The bean with every seam answered by this test's maps. */
    private final class TestBean extends TripPictureCommands {

        @Override
        protected Caller caller() {
            return caller;
        }

        @Override
        protected Optional<Trip> readStored(final String tripId) {
            // A copy, like a real DAO read: a write must go through saveStored to stick.
            final Trip trip = stored.get(tripId);
            final Optional<Trip> read = trip == null ? Optional.empty() : Optional.of(copyOf(trip));
            final Runnable hook = afterNextRead;
            afterNextRead = null;
            if (hook != null) {
                hook.run();
            }
            return read;
        }

        @Override
        protected boolean saveStored(final Trip trip) {
            saves.add(trip);
            if (saveResult) {
                stored.put(trip.getId(), copyOf(trip));
            }
            return saveResult;
        }

        @Override
        protected boolean isOrgAdmin(final Caller who, final String orgId) {
            return SEAM_ORG_ID.equals(orgId) ? SEAM_ORG_ADMIN.equals(who.personId()) : super.isOrgAdmin(who, orgId);
        }

        @Override
        protected Map<Object, Object> requestMemo() {
            return memo;
        }

        @Override
        protected String sessionGet(final String key) {
            return session.get(key);
        }

        @Override
        protected void sessionPut(final String key, final String value) {
            if (value == null) {
                session.remove(key);
            } else {
                session.put(key, value);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        protected <T> T viewMap(final String key) {
            return (T) viewMap.get(key);
        }

        @Override
        protected void viewRemove(final String key) {
            viewMap.remove(key);
        }

        @Override
        protected void publishOutcome(final boolean ok) {
            outcomes.add(ok);
        }

        @Override
        protected void publishParam(final String name, final Object value) {
            params.put(name, value);
        }
    }

    /** An album source that offers exactly {@link #albumPhotos}; its own rules are {@code AlbumPhotoSourceTest}'s. */
    private final class FakeAlbums extends AlbumPhotoSource {

        @Override
        public Optional<MediaItem> offered(final Caller who, final String scope, final String mediaId) {
            return albumPhotos.containsKey(mediaId)
                    ? Optional.of(new MediaItem(mediaId, "chat/x/" + mediaId, "t", "d", "image/jpeg", 1L, "tripChat-x",
                            0, null, "someone"))
                    : Optional.empty();
        }

        @Override
        public Optional<byte[]> bytesOf(final MediaItem item) {
            return Optional.ofNullable(albumPhotos.get(item.getId()));
        }
    }

    private static AlbumPicks.Pick pick(final String mediaId) {
        return new AlbumPicks.Pick(mediaId, 0.5d, 0.5d);
    }

    private static Trip copyOf(final Trip trip) {
        final Trip copy = Trip.builder().id(trip.getId()).title(trip.getTitle()).build();
        copy.setOrgId(trip.getOrgId());
        final List<TripPicture> pictures = new ArrayList<>();
        for (final TripPicture pic : trip.getPictures()) {
            pictures.add(new TripPicture(pic.getKey(), pic.getAlt(), pic.getWidth(), pic.getHeight(),
                    pic.getPlaceholder()));
        }
        copy.setPictures(pictures);
        return copy;
    }

    private Trip trip() {
        return stored.get(TRIP_ID);
    }

    private TripPictureCommands.PictureResult addOne(final String alt) {
        return bean.add(caller, TRIP_ID, PhotoFixtures.jpeg(2400, 1800), null, alt);
    }

    private static FileUploadEvent uploadOf(final byte[] bytes) {
        final UploadedFile file = Mockito.mock(UploadedFile.class);
        Mockito.when(file.getContent()).thenReturn(bytes);
        Mockito.when(file.getSize()).thenReturn((long) bytes.length);
        Mockito.when(file.getFileName()).thenReturn("cover.jpg");
        return new FileUploadEvent(Mockito.mock(UIComponent.class), file, 1);
    }

    private static BufferedImage decode(final byte[] jpeg) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    // --- who ---

    @Test
    public void siteAdminsTripManagersAndOrgAdminsMayEditNobodyElse() {
        final Trip trip = trip();
        Assert.assertTrue(bean.mayEdit(caller, trip), "A site admin may");

        final Person.Id manager = Person.Id.newInstance();
        Mockito.when(priv.check("tripMgr", TRIP_ID, manager)).thenReturn(true);
        Assert.assertTrue(bean.mayEdit(new Caller(manager, false, AuditActor.from(null), priv), trip),
                "A holder of tripMgr for THIS trip may");

        final Person.Id member = Person.Id.newInstance();
        final Caller plain = new Caller(member, false, AuditActor.from(null), priv);
        Assert.assertFalse(bean.mayEdit(plain, trip), "A plain member may not");
        Assert.assertFalse(bean.mayEdit(new Caller(null, false, AuditActor.from(null), priv), trip),
                "Anonymous may not");
        Assert.assertFalse(bean.mayEdit(caller, null), "No trip, no permission");
        Assert.assertFalse(bean.mayEdit(null, trip), "No caller, no permission");

        final Organization acme = DAO.getInstance().getOrganization(Organization.Id.from(FakeData.ACME_ORG_ID),
                Cached.NO).orElseThrow();
        final Person.Id orgAdmin = acme.getAdminIds().getFirst();
        final Caller acmeAdmin = new Caller(orgAdmin, false, AuditActor.from(null), priv);
        Assert.assertFalse(bean.mayEdit(acmeAdmin, trip), "An org admin of NO org on this trip may not");
        trip.setOrgId(FakeData.ACME_ORG_ID);
        Assert.assertTrue(bean.mayEdit(acmeAdmin, trip), "An admin of the trip's organization may");
        trip.setOrgId(FakeData.BETA_ORG_ID);
        Assert.assertFalse(bean.mayEdit(acmeAdmin, trip), "An admin of ANOTHER organization may not");

        caller = plain;
        Assert.assertFalse(bean.mayEdit(trip), "The page form asks for the signed-in caller");
        Assert.assertEquals(bean.add(plain, TRIP_ID, PhotoFixtures.jpeg(300, 200), null, null).status(),
                TripPictureCommands.PictureResult.Status.FORBIDDEN);
        Assert.assertTrue(saves.isEmpty(), "A refused write saves nothing");
    }

    // --- add ---

    @Test
    public void anAddCutsThreeWidthsAtThreeTwoAndTheFirstIsTheCover() throws Exception {
        final TripPictureCommands.PictureResult result = addOne("  The group at Cross Mountain  ");
        Assert.assertTrue(result.isOk(), result.message());
        Assert.assertEquals(result.message(), "Cover picture added.");
        Assert.assertNull(result.warning(), "2400 wide is big enough");

        final TripPicture cover = trip().getCover();
        Assert.assertEquals(cover, result.picture());
        Assert.assertTrue(cover.getKey().startsWith("tripPictures/trip-1/"), cover.getKey());
        Assert.assertEquals(cover.getAlt(), "The group at Cross Mountain", "Trimmed");
        Assert.assertEquals(cover.getWidth(), 1920);
        Assert.assertEquals(cover.getHeight(), 1280, "3:2 from a 4:3 source");
        Assert.assertTrue(cover.getPlaceholder().matches("#[0-9a-f]{6}"), cover.getPlaceholder());
        for (final int width : TripPicture.WIDTHS) {
            final BufferedImage img = decode(bean.localGet(cover.keyAt(width)).orElseThrow());
            Assert.assertEquals(img.getWidth(), width);
            Assert.assertEquals(img.getHeight(), width * 2 / 3);
        }

        Assert.assertEquals(addOne(null).message(), "Picture added.", "The second is a gallery picture");
        Assert.assertEquals(trip().getPictures().size(), 2);
        Assert.assertNull(trip().getPictures().get(1).getAlt(), "Blank description stays blank");
    }

    @Test
    public void aSmallPictureIsStoredWithAWarningAndNeverEnlarged() throws Exception {
        final TripPictureCommands.PictureResult result =
                bean.add(caller, TRIP_ID, PhotoFixtures.jpeg(900, 600), null, null);
        Assert.assertTrue(result.isOk());
        Assert.assertNotNull(result.warning());
        Assert.assertTrue(result.warning().contains("900×600"), result.warning());
        Assert.assertEquals(result.picture().getWidth(), 900, "The widest rendition is the source's own width");
        Assert.assertEquals(decode(bean.localGet(result.picture().keyAt(1920)).orElseThrow()).getWidth(), 900);
        Assert.assertEquals(decode(bean.localGet(result.picture().keyAt(480)).orElseThrow()).getWidth(), 480);
    }

    @Test
    public void aCropIsShrunkToThreeTwoInsideTheSelection() throws Exception {
        final TripPictureCommands.PictureResult result = bean.add(caller, TRIP_ID, PhotoFixtures.jpeg(3000, 2000),
                new org.paulsens.trip.media.PhotoProcessor.CropRect(100, 100, 2000, 2000), null);
        Assert.assertEquals(result.picture().getWidth(), 1920);
        Assert.assertEquals(result.picture().getHeight(), 1280);
    }

    @Test
    public void theCapGarbageUnknownTripsAndFailuresAreRefusedCleanly() {
        for (int i = 0; i < Trip.MAX_PICTURES; i++) {
            trip().getPictures().add(new TripPicture("tripPictures/trip-1/" + i, null, 1920, 1280, "#000000"));
        }
        final TripPictureCommands.PictureResult full = addOne(null);
        Assert.assertEquals(full.status(), TripPictureCommands.PictureResult.Status.LIMIT);
        Assert.assertTrue(full.message().contains("a cover and 8 more"), full.message());
        trip().getPictures().clear();

        Assert.assertEquals(bean.add(caller, TRIP_ID, "not an image".getBytes(), null, null).status(),
                TripPictureCommands.PictureResult.Status.INVALID);
        Assert.assertEquals(bean.add(caller, "no-such-trip", PhotoFixtures.jpeg(300, 200), null, null).status(),
                TripPictureCommands.PictureResult.Status.NOT_FOUND);

        saveResult = false;
        final TripPictureCommands.PictureResult unsaved = addOne(null);
        Assert.assertEquals(unsaved.status(), TripPictureCommands.PictureResult.Status.FAILED);
        Assert.assertTrue(trip().getPictures().isEmpty(), "The row is unchanged");
        Assert.assertTrue(bean.localGet(saves.getLast().getPictures().getFirst().keyAt(480)).isEmpty(),
                "and the stored objects of the unsaved picture were removed");
    }

    @Test
    public void aTripWriteThatLandsWhileAnAddIsProcessingSurvivesTheAdd() {
        final TripPicture other = new TripPicture("tripPictures/trip-1/1", "Theirs", 1920, 1280, "#000000");
        // After add's first read, before its save: another upload and an unrelated trip write commit.
        afterNextRead = () -> landConcurrentWrite(other);
        final TripPictureCommands.PictureResult result = addOne("Mine");
        Assert.assertTrue(result.isOk(), result.message());
        Assert.assertEquals(trip().getTitle(), "Holy Angels (renamed)", "The other write was not overwritten");
        Assert.assertEquals(keys(), List.of(other.getKey(), result.picture().getKey()),
                "The other upload is kept, and this one is appended after it");
        Assert.assertEquals(result.message(), "Picture added.", "Counted on the fresh row: not the cover");
    }

    private void landConcurrentWrite(final TripPicture picture) {
        trip().setTitle("Holy Angels (renamed)");
        trip().getPictures().add(picture);
    }

    @Test
    public void anotherWriteToTheSameTripWaitsForAnAddsReadModifyWrite() throws Exception {
        final TripPicture first = new TripPicture("tripPictures/trip-1/1", null, 1920, 1280, "#000000");
        trip().getPictures().add(first);
        final List<Thread> describer = new ArrayList<>();
        // The second read is add's re-read, inside the trip's lock: a describe started there must wait for it.
        afterNextRead = () -> afterNextRead = () -> describer.add(describeMeanwhile(first.getKey()));
        final TripPictureCommands.PictureResult result = addOne(null);
        Assert.assertTrue(result.isOk(), result.message());
        describer.getFirst().join(5_000);
        Assert.assertFalse(describer.getFirst().isAlive());
        Assert.assertEquals(keys(), List.of(first.getKey(), result.picture().getKey()),
                "The describe, run after the add, kept the added picture");
        Assert.assertEquals(trip().getPicture(first.getKey()).getAlt(), "Described meanwhile");
    }

    /**
     * Starts a describe on another thread and gives it time to finish: without the lock it would save the row
     * between add's re-read and add's save, and add would then erase the description.
     */
    private Thread describeMeanwhile(final String key) {
        final Thread thread = Thread.ofVirtual().start(() -> bean.describe(caller, TRIP_ID, key,
                "Described meanwhile"));
        try {
            Assert.assertFalse(thread.join(java.time.Duration.ofMillis(300)), "The describe waited for the add");
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
        return thread;
    }

    @Test
    public void theLimitIsCheckedAgainOnTheRowReadAfterProcessing() {
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.putObject(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.eq(
                "image/jpeg"), ArgumentMatchers.eq(TripPictureCommands.CACHE_SECONDS), ArgumentMatchers.eq(false)))
                .thenReturn(true);
        for (int i = 0; i < Trip.MAX_PICTURES - 1; i++) {
            trip().getPictures().add(new TripPicture("tripPictures/trip-1/" + i, null, 1920, 1280, "#000000"));
        }
        // The first read sees room for one more; a concurrent upload takes it before this one saves.
        afterNextRead = () -> trip().getPictures().add(
                new TripPicture("tripPictures/trip-1/last", null, 1920, 1280, "#000000"));
        final TripPictureCommands.PictureResult result = addOne(null);
        Assert.assertEquals(result.status(), TripPictureCommands.PictureResult.Status.LIMIT, result.message());
        Assert.assertTrue(saves.isEmpty(), "Nothing is saved over the limit");
        Assert.assertEquals(trip().getPictures().size(), Trip.MAX_PICTURES);
        Mockito.verify(media, Mockito.times(3)).putObject(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyBoolean());
        Mockito.verify(media, Mockito.times(3)).deleteObject(ArgumentMatchers.startsWith("tripPictures/trip-1/"));
    }

    @Test
    public void aTripDeletedOrARightLostDuringProcessingIsRefusedAndCleanedUp() {
        afterNextRead = () -> stored.remove(TRIP_ID);
        final TripPictureCommands.PictureResult gone = addOne(null);
        Assert.assertEquals(gone.status(), TripPictureCommands.PictureResult.Status.NOT_FOUND);

        // An org admin whose trip moves to another organization while the picture is processed.
        stored.put(TRIP_ID, Trip.builder().id(TRIP_ID).title("Holy Angels").build());
        trip().setOrgId(SEAM_ORG_ID);
        final Caller orgAdmin = new Caller(SEAM_ORG_ADMIN, false, AuditActor.from(null), priv);
        afterNextRead = () -> trip().setOrgId("some-other-org");
        final TripPictureCommands.PictureResult refused =
                bean.add(orgAdmin, TRIP_ID, PhotoFixtures.jpeg(300, 200), null, null);
        Assert.assertEquals(refused.status(), TripPictureCommands.PictureResult.Status.FORBIDDEN);
        Assert.assertTrue(saves.isEmpty(), "Neither saved anything");
        Assert.assertTrue(trip().getPictures().isEmpty());
    }

    @Test
    public void aFailedStoreInProductionModeRemovesWhatItWrote() {
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.putObject(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.eq(
                "image/jpeg"), ArgumentMatchers.eq(TripPictureCommands.CACHE_SECONDS), ArgumentMatchers.eq(false)))
                .thenReturn(true, false);
        final TripPictureCommands.PictureResult result = addOne(null);
        Assert.assertEquals(result.status(), TripPictureCommands.PictureResult.Status.FAILED);
        Assert.assertTrue(saves.isEmpty(), "Nothing names objects that failed to store");
        Mockito.verify(media, Mockito.times(3)).deleteObject(ArgumentMatchers.startsWith("tripPictures/trip-1/"));
        Mockito.verify(media).invalidateCdn(ArgumentMatchers.anyList());
    }

    // --- order, descriptions, removal ---

    @Test
    public void movingDescribingAndRemovingActOnTheStoredRowByKey() {
        final String first = addOne("one").picture().getKey();
        final String second = addOne("two").picture().getKey();
        final String third = addOne("three").picture().getKey();

        final TripPictureCommands.PictureResult cover = bean.move(caller, TRIP_ID, third, 0);
        Assert.assertEquals(cover.message(), "Cover picture changed.");
        Assert.assertEquals(keys(), List.of(third, first, second));
        Assert.assertEquals(bean.move(caller, TRIP_ID, third, 99).message(), "Picture moved.", "Clamped");
        Assert.assertEquals(keys(), List.of(first, second, third));

        Assert.assertTrue(bean.describe(caller, TRIP_ID, second, " Two ").isOk());
        Assert.assertEquals(trip().getPicture(second).getAlt(), "Two");
        Assert.assertTrue(bean.describe(caller, TRIP_ID, second, "x".repeat(300)).isOk());
        Assert.assertEquals(trip().getPicture(second).getAlt().length(), TripPictureCommands.MAX_ALT, "Capped");

        Assert.assertTrue(bean.remove(caller, TRIP_ID, first).isOk());
        Assert.assertEquals(keys(), List.of(second, third), "The next one becomes the cover");
        Assert.assertTrue(bean.localGet(first + "-480.jpg").isEmpty(), "Its objects are gone");

        Assert.assertEquals(bean.remove(caller, TRIP_ID, first).status(),
                TripPictureCommands.PictureResult.Status.NOT_FOUND, "Already gone");
        Assert.assertEquals(bean.move(caller, "no-such-trip", second, 0).status(),
                TripPictureCommands.PictureResult.Status.NOT_FOUND);
        final Caller plain = new Caller(Person.Id.newInstance(), false, AuditActor.from(null), priv);
        Assert.assertEquals(bean.describe(plain, TRIP_ID, second, "no").status(),
                TripPictureCommands.PictureResult.Status.FORBIDDEN);

        saveResult = false;
        Assert.assertEquals(bean.move(caller, TRIP_ID, third, 0).status(),
                TripPictureCommands.PictureResult.Status.FAILED);
        Assert.assertEquals(bean.describe(caller, TRIP_ID, third, "x").status(),
                TripPictureCommands.PictureResult.Status.FAILED);
        Assert.assertEquals(bean.remove(caller, TRIP_ID, third).status(),
                TripPictureCommands.PictureResult.Status.FAILED);
        Assert.assertTrue(bean.localGet(third + "-480.jpg").isPresent(), "An unsaved remove keeps the objects");
    }

    private List<String> keys() {
        return trip().getPictures().stream().map(TripPicture::getKey).toList();
    }

    // --- the dialog ---

    @Test
    public void theDialogParksCropsAndAddsWithTheTypedDescription() {
        viewMap.put(TripPictureCommands.ALT_VIEW_KEY, "Sunrise on the hill");
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(1200, 800)));
        Assert.assertTrue(bean.isUploadPending());

        bean.confirmCrop();
        Assert.assertEquals(outcomes, List.of(true));
        Assert.assertEquals(trip().getCover().getAlt(), "Sunrise on the hill");
        Assert.assertNull(viewMap.get(TripPictureCommands.ALT_VIEW_KEY), "The picture took it: cleared for the next");
        Assert.assertNotNull(params.get("picWarn"), "1200 wide is stored with a warning");
        Assert.assertFalse(bean.isUploadPending(), "The token is spent");

        bean.confirmFullPhoto();
        Assert.assertEquals(outcomes, List.of(true, false), "No parked upload: refused, the dialog stays open");
    }

    @Test
    public void theDialogRefusesAnUploadItMayNotFinish() {
        caller = new Caller(Person.Id.newInstance(), false, AuditActor.from(null), priv);
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(1200, 800)));
        Assert.assertFalse(bean.isUploadPending(), "A plain member is refused before parking");

        caller = new Caller(Person.Id.newInstance(), true, AuditActor.from(null), priv);
        for (int i = 0; i < Trip.MAX_PICTURES; i++) {
            trip().getPictures().add(new TripPicture("tripPictures/trip-1/" + i, null, 1920, 1280, "#000000"));
        }
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(1200, 800)));
        Assert.assertFalse(bean.isUploadPending(), "A full trip is refused before parking");
        Assert.assertTrue(bean.isFull(TRIP_ID));

        trip().getPictures().removeLast();
        memo.clear();
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(1200, 800)));
        Assert.assertTrue(bean.isUploadPending());
        saveResult = false;
        bean.confirmCrop();
        Assert.assertEquals(outcomes, List.of(false), "A failed save keeps the dialog open");
        Assert.assertTrue(bean.isUploadPending(), "and the parked upload, to try again");
    }

    @Test
    public void theListButtonsReportThroughPicChanged() {
        final String first = addOne("one").picture().getKey();
        final String second = addOne("two").picture().getKey();

        bean.moveFromUi(second, "first");
        Assert.assertEquals(params.get("picChanged"), true);
        Assert.assertEquals(keys(), List.of(second, first));
        bean.moveFromUi(second, "later");
        Assert.assertEquals(keys(), List.of(first, second));
        bean.moveFromUi(second, "earlier");
        Assert.assertEquals(keys(), List.of(second, first));
        bean.moveFromUi(second, "sideways");
        Assert.assertEquals(params.get("picChanged"), false, "An unknown direction is refused");
        bean.moveFromUi("tripPictures/trip-1/gone", "first");
        Assert.assertEquals(params.get("picChanged"), false, "An unknown picture is refused");
        bean.moveFromUi(second, null);
        Assert.assertEquals(params.get("picChanged"), false);

        viewMap.put(TripPictureCommands.EDIT_KEY_VIEW_KEY, "tripPictures/trip-1/gone");
        viewMap.put(TripPictureCommands.EDIT_ALT_VIEW_KEY, "Typed with care");
        bean.describeFromUi("tripPictures/trip-1/gone", "Typed with care");
        Assert.assertEquals(params.get("picChanged"), false, "An unknown picture is refused");
        Assert.assertEquals(viewMap.get(TripPictureCommands.EDIT_ALT_VIEW_KEY), "Typed with care",
                "A refused save keeps the editor open with what was typed");
        viewMap.put(TripPictureCommands.EDIT_KEY_VIEW_KEY, first);
        bean.describeFromUi(first, "Described");
        Assert.assertEquals(trip().getPicture(first).getAlt(), "Described");
        Assert.assertNull(viewMap.get(TripPictureCommands.EDIT_KEY_VIEW_KEY), "A saved description closes the editor");
        Assert.assertNull(viewMap.get(TripPictureCommands.EDIT_ALT_VIEW_KEY));
        bean.removeFromUi(first);
        Assert.assertEquals(params.get("picChanged"), true);
        Assert.assertEquals(keys(), List.of(second));
        bean.removeFromUi(first);
        Assert.assertEquals(params.get("picChanged"), false);
    }

    // --- reads ---

    @Test
    public void readsAreMemoizedPerRequestAndWritesRefreshTheMemo() {
        Assert.assertEquals(bean.summary(TRIP_ID), "No pictures");
        Assert.assertEquals(bean.picturesOf(null), List.of());
        addOne(null);
        Assert.assertEquals(bean.summary(TRIP_ID), "Cover only", "The write refreshed the memo");
        addOne(null);
        Assert.assertEquals(bean.summary(TRIP_ID), "Cover and 1 more");

        stored.get(TRIP_ID).getPictures().clear();
        Assert.assertEquals(bean.picturesOf(TRIP_ID).size(), 2, "Within one request the memo answers");
        memo.clear();
        Assert.assertEquals(bean.picturesOf(TRIP_ID).size(), 0, "A new request reads again");
        Assert.assertFalse(bean.isFull(TRIP_ID));
    }

    @Test
    public void urlsPointAtTheCdnOrTheLocalServlet() {
        final TripPicture pic = new TripPicture("tripPictures/trip-1/7", null, 1920, 1280, "#000000");
        Assert.assertEquals(bean.url(pic), "/trip-pictures/tripPictures/trip-1/7-1920.jpg");
        Assert.assertEquals(bean.url(pic, 480), "/trip-pictures/tripPictures/trip-1/7-480.jpg");
        Assert.assertNull(bean.url(null, 480));

        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.publicUrl("tripPictures/trip-1/7-960.jpg"))
                .thenReturn("https://media.example/tripPictures/trip-1/7-960.jpg");
        Assert.assertEquals(bean.url(pic, 960), "https://media.example/tripPictures/trip-1/7-960.jpg");
    }

    @Test
    public void tripDeleteRemovesEveryRenditionAndSweepsThePrefix() {
        addOne(null);
        addOne(null);
        bean.localStoreMaxBytesForTest(TripPictureCommands.LOCAL_STORE_MAX_BYTES);
        Assert.assertEquals(bean.deleteAllForTrip(trip()), 6, "Two pictures, three renditions each");
        Assert.assertTrue(bean.localGet(trip().getCover().keyAt(480)).isEmpty());

        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.listKeys("tripPictures/trip-1/")).thenReturn(List.of("tripPictures/trip-1/9-480.jpg"));
        Assert.assertEquals(bean.deleteAllForTrip(trip()), 7, "Six named objects and one orphan");
        Mockito.verify(media).deleteObject("tripPictures/trip-1/9-480.jpg");
        Mockito.verify(media).invalidateCdn(List.of("/tripPictures/trip-1/*"));

        Mockito.when(media.listKeys(ArgumentMatchers.anyString())).thenReturn(List.of());
        Assert.assertEquals(bean.deleteAllForTrip(Trip.builder().id("empty").build()), 0);
    }

    @Test
    public void cleanAltTrimsCapsAndBlanks() {
        Assert.assertNull(TripPictureCommands.cleanAlt(null));
        Assert.assertNull(TripPictureCommands.cleanAlt("   "));
        Assert.assertEquals(TripPictureCommands.cleanAlt(" a "), "a");
    }

    @Test
    public void theRealSeamsFailClosedOffAPage() throws Exception {
        final TripPictureCommands real = new TripPictureCommands();
        Assert.assertTrue(real.readStored(null).isEmpty());
        Assert.assertTrue(real.readStored(" ").isEmpty());
        Assert.assertTrue(real.readStored("no-such-trip").isEmpty());
        Assert.assertTrue(real.requestMemo().isEmpty(), "Off a page the memo is a throwaway map");
        Assert.assertNull(real.targetId(), "No view, no target");
        real.viewRemove(TripPictureCommands.ALT_VIEW_KEY);
        Assert.assertNull(real.getAlbumScope(), "No open trip: no albums to list");
        final Trip trip = Trip.builder().id("picture-seam-test").title("Seam").build();
        Assert.assertTrue(real.saveStored(trip), "Writes the row straight through the DAO");
        Assert.assertTrue(DAO.getInstance().getTrip("picture-seam-test", Cached.NO).isPresent());
        DAO.getInstance().deleteTrip(trip);
    }

    /** Two uploads in one millisecond must not share a key stem: the stamp only ever moves forward. */
    @Test
    public void keyStampsNeverRepeat() {
        long last = TripPictureCommands.nextStamp();
        Assert.assertTrue(last >= System.currentTimeMillis() - 1000, "Still the clock's milliseconds");
        for (int i = 0; i < 2000; i++) {
            final long next = TripPictureCommands.nextStamp();
            Assert.assertTrue(next > last, "Stamp " + next + " follows " + last);
            last = next;
        }
    }

    /**
     * The trip-picture servlet is anonymous: only a key that is EXACTLY a bundled picture's rendition is worth
     * the pipeline, and a miss of any other shape costs a store read and stores nothing.
     */
    @Test
    public void aDemoPictureIsMadeOnlyForAnExactRenditionKey() throws Exception {
        final String stem = "tripPictures/demo-gate/demo-valley";
        Assert.assertTrue(bean.localGet(stem + "-1.jpg").isEmpty(), "No such width");
        Assert.assertTrue(bean.localGet(stem + "-960.jpg.bak").isEmpty());
        Assert.assertEquals(bean.deleteAllForTrip(Trip.builder().id("demo-gate").build()), 0,
                "Neither miss stored a rendition");

        Assert.assertEquals(decode(bean.localGet(stem + "-480.jpg").orElseThrow()).getWidth(), 480);
        Assert.assertTrue(bean.localGet(stem + "-960.jpg").isPresent(), "One pass made all three");

        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Assert.assertTrue(bean.localGet("tripPictures/demo-gate-2/demo-valley-480.jpg").isEmpty(),
                "With a media bucket there are no demo pictures");
    }

    /**
     * A picture is stored at its own width when that is under a rendition's, never enlarged, so its srcset names
     * each rendition by its REAL width and lists a width once: a browser told 960w for an 809-pixel file would
     * pick it for a slot it cannot fill.
     */
    @Test
    public void srcsetNamesEachRenditionByItsRealWidthOnce() {
        final String base = "/trip-pictures/tripPictures/trip-1/7";
        Assert.assertEquals(bean.srcset(new TripPicture("tripPictures/trip-1/7", null, 1920, 1280, "#000000")),
                base + "-480.jpg 480w, " + base + "-960.jpg 960w, " + base + "-1920.jpg 1920w");
        Assert.assertEquals(bean.srcset(new TripPicture("tripPictures/trip-1/7", null, 809, 539, "#000000")),
                base + "-480.jpg 480w, " + base + "-960.jpg 809w", "The 1920 rendition is the 809 one again");
        Assert.assertEquals(bean.srcset(new TripPicture("tripPictures/trip-1/7", null, 300, 200, "#000000")),
                base + "-480.jpg 300w", "One entry for a picture smaller than every rendition");
        Assert.assertEquals(bean.srcset(null), "");
    }

    /** A listing card's cap: nothing wider than it is offered, and a smaller picture is listed as srcset lists it. */
    @Test
    public void srcsetUpToLeavesOutEveryWiderRendition() {
        final String base = "/trip-pictures/tripPictures/trip-1/7";
        Assert.assertEquals(bean.srcsetUpTo(new TripPicture("tripPictures/trip-1/7", null, 1920, 1280, "#000000"),
                960), base + "-480.jpg 480w, " + base + "-960.jpg 960w", "No 1920, however dense the screen");
        Assert.assertEquals(bean.srcsetUpTo(new TripPicture("tripPictures/trip-1/7", null, 809, 539, "#000000"),
                960), base + "-480.jpg 480w, " + base + "-960.jpg 809w");
        Assert.assertEquals(bean.srcsetUpTo(new TripPicture("tripPictures/trip-1/7", null, 1920, 1280, "#000000"),
                480), base + "-480.jpg 480w");
        Assert.assertEquals(bean.srcsetUpTo(null, 960), "");
    }

    /** ONE rule for what a screen reader says for a picture, shared by the listing card and the trip page. */
    @Test
    public void aPictureIsDescribedByItsOwnTextElseByTheTripsShortTitle() {
        final Trip titled = Trip.builder().id("alt-trip").title("2026 Oct: Fatima and Lourdes").build();
        final TripPicture described = new TripPicture("tripPictures/alt-trip/1", "The hill at sunset", 1920, 1280,
                "#000000");
        final TripPicture blank = new TripPicture("tripPictures/alt-trip/2", "  ", 1920, 1280, "#000000");
        final TripPicture undescribed = new TripPicture("tripPictures/alt-trip/3", null, 1920, 1280, "#000000");

        Assert.assertEquals(bean.altOf(described, titled), "The hill at sunset");
        Assert.assertEquals(bean.altOf(blank, titled), "Fatima and Lourdes", "The title without its sort prefix");
        Assert.assertEquals(bean.altOf(undescribed, titled), "Fatima and Lourdes");
        Assert.assertEquals(bean.altOf(null, titled), "Fatima and Lourdes", "No picture: the trip still has a name");
        Assert.assertEquals(bean.altOf(described, null), "The hill at sunset", "A description needs no trip");
        Assert.assertEquals(bean.altOf(undescribed, null), "", "Nothing to say, and never null in an attribute");
        Assert.assertEquals(bean.altOf(null, null), "");

        final TripCardCommands.Card card = new TripCardCommands.Card(titled, "/trip", TripCardCommands.State.REGISTER,
                blank, null, null, null, null, false);
        Assert.assertEquals(card.getAlt(), bean.altOf(blank, titled), "A card answers the same rule");
    }

    /**
     * The widths a picture is drawn at, per layout, named once here rather than in every page: the full content
     * width, and a card of the listing's grid. Pinned as text because the page hands them to the browser as they
     * are; whether they match the layout is the browser tests' question.
     */
    @Test
    public void wideAndGridSizesNameTheDrawnWidthsOfEachLayout() {
        Assert.assertEquals(bean.wideSizes(true),
                "(min-width: 1272px) 1200px, (min-width: 992px) calc(100vw - 72px), calc(100vw - 28px)");
        Assert.assertEquals(bean.wideSizes(false), "(min-width: 1472px) 1068px, (min-width: 992px) calc(100vw - 404px),"
                + " (min-width: 715px) calc(100vw - 360px), calc(100vw - 110px)");
        Assert.assertEquals(bean.gridSizes(true), "(min-width: 1272px) 390px,"
                + " (min-width: 1052px) calc((100vw - 104px) / 3), (min-width: 992px) calc((100vw - 88px) / 2),"
                + " (min-width: 688px) calc((100vw - 44px) / 2), calc(100vw - 28px)");
        Assert.assertEquals(bean.gridSizes(false), "(min-width: 1472px) 346px,"
                + " (min-width: 1384px) calc((100vw - 436px) / 3), (min-width: 1064px) calc((100vw - 420px) / 2),"
                + " (min-width: 992px) calc(100vw - 404px), (min-width: 715px) calc(100vw - 360px),"
                + " calc(100vw - 110px)");
    }

    /**
     * A phone shows three thumbnails under the gallery, not five (about 35px each); the strip itself stays, because
     * the gallery's swipe is bound on it.
     */
    @Test
    public void theGalleryShowsThreeThumbnailsOnAPhone() {
        final List<ResponsiveOption> options = bean.getGalleryResponsive();
        Assert.assertEquals(options.size(), 1);
        Assert.assertEquals(options.getFirst().getBreakpoint(), "480px");
        Assert.assertEquals(options.getFirst().getNumVisible(), 3);
    }

    // --- from a trip album ---

    @Test
    public void aBatchFromTheAlbumsIsCutAndAppendedInOrderWithOneSave() throws Exception {
        albumPhotos.put("wide", PhotoFixtures.jpeg(2400, 1600));
        albumPhotos.put("tall", PhotoFixtures.jpeg(1800, 2700));
        albumPhotos.put("pano", PhotoFixtures.jpeg(4000, 1700));
        final TripPictureCommands.AlbumResult result = bean.addFromAlbum(caller, TRIP_ID,
                List.of(pick("tall"), new AlbumPicks.Pick("wide", 0d, 1d), pick("pano")), null);
        Assert.assertEquals(result.added(), 3);
        Assert.assertNull(result.refusal());
        Assert.assertEquals(result.lowResolution(), 0);
        Assert.assertEquals(saves.size(), 1, "The whole batch is one read-modify-write");

        final List<TripPicture> pictures = trip().getPictures();
        Assert.assertEquals(pictures.size(), 3);
        Assert.assertEquals(pictures.get(0).getWidth(), 1800, "The tall photo's 3:2 cut is its full width");
        Assert.assertEquals(pictures.get(0).getHeight(), 1200);
        Assert.assertEquals(pictures.get(1).getWidth(), 1920, "In the order picked");
        Assert.assertEquals(pictures.get(2).getHeight(), 1280);
        for (final TripPicture picture : pictures) {
            Assert.assertNull(picture.getAlt());
            final BufferedImage small = decode(bean.localGet(picture.keyAt(480)).orElseThrow());
            Assert.assertEquals(small.getWidth(), 480);
            Assert.assertEquals(small.getHeight(), 320);
        }
    }

    @Test
    public void aPickThatIsNotOfferedCannotBeReadOrIsNotAPictureAddsNothingAndSaysWhy() {
        albumPhotos.put("good", PhotoFixtures.jpeg(2400, 1600));
        albumPhotos.put("unreadable", null);
        albumPhotos.put("garbage", new byte[] {1, 2, 3});

        TripPictureCommands.AlbumResult result =
                bean.addFromAlbum(caller, TRIP_ID, List.of(pick("someone-elses"), pick("good")), "The hill");
        Assert.assertEquals(result.added(), 1, "The others still go in");
        Assert.assertEquals(result.refusal(), "Not available: that album photo cannot be used for this trip.");
        Assert.assertEquals(trip().getCover().getAlt(), "The hill");

        result = bean.addFromAlbum(caller, TRIP_ID, List.of(pick("unreadable")), null);
        Assert.assertEquals(result.added(), 0);
        Assert.assertEquals(result.refusal(), "Not read: an album photo could not be read. Try again.");

        result = bean.addFromAlbum(caller, TRIP_ID, List.of(pick("garbage"), pick("unreadable")), null);
        Assert.assertEquals(result.added(), 0);
        Assert.assertTrue(result.refusal().startsWith("Picture rejected: "), "The FIRST reason: " + result.refusal());
        Assert.assertEquals(saves.size(), 1, "Only the batch that added something saved");
    }

    @Test
    public void aBatchFromTheAlbumsIsRefusedLikeAnUpload() {
        albumPhotos.put("good", PhotoFixtures.jpeg(2400, 1600));
        Assert.assertEquals(bean.addFromAlbum(caller, "no-such-trip", List.of(pick("good")), null).refusal(),
                "Not found: that trip does not exist.");
        final Caller plain = new Caller(Person.Id.newInstance(), false, AuditActor.from(null), priv);
        final TripPictureCommands.AlbumResult refused = bean.addFromAlbum(plain, TRIP_ID, List.of(pick("good")), null);
        Assert.assertEquals(refused.added(), 0);
        Assert.assertEquals(refused.refusal(), "Not allowed: only this trip's managers can change its pictures.");
        Assert.assertTrue(saves.isEmpty());
    }

    @Test
    public void aBatchStopsAtTheLimitAndSmallPicturesAreCounted() {
        for (int i = 0; i < Trip.MAX_PICTURES - 2; i++) {
            Assert.assertTrue(addOne(null).isOk());
        }
        saves.clear();
        albumPhotos.put("small", PhotoFixtures.jpeg(900, 600));
        albumPhotos.put("also-small", PhotoFixtures.jpeg(600, 900));
        albumPhotos.put("never-cut", new byte[] {1, 2, 3});
        final TripPictureCommands.AlbumResult result = bean.addFromAlbum(caller, TRIP_ID,
                List.of(pick("small"), pick("also-small"), pick("never-cut")), null);
        Assert.assertEquals(result.added(), 2, "Room for two");
        Assert.assertEquals(result.lowResolution(), 2);
        Assert.assertTrue(result.refusal().startsWith("Picture limit reached"),
                "The pick past the room is the limit's, not a rejected picture: it was never cut. " + result.refusal());
        Assert.assertEquals(trip().getPictures().size(), Trip.MAX_PICTURES);
        Assert.assertEquals(bean.roomFor(TRIP_ID), 0);

        final TripPictureCommands.AlbumResult full = bean.addFromAlbum(caller, TRIP_ID, List.of(pick("small")), null);
        Assert.assertEquals(full.added(), 0);
        Assert.assertTrue(full.refusal().startsWith("Picture limit reached"), full.refusal());
        Assert.assertEquals(saves.size(), 1);
    }

    @Test
    public void aBatchThatLosesRoomWhileCuttingKeepsWhatFitsAndDeletesTheRest() {
        for (int i = 0; i < Trip.MAX_PICTURES - 2; i++) {
            Assert.assertTrue(addOne(null).isOk());
        }
        albumPhotos.put("one", PhotoFixtures.jpeg(2400, 1600));
        albumPhotos.put("two", PhotoFixtures.jpeg(2400, 1600));
        // Another upload lands after this batch's first read: there is room for one of the two when it appends.
        final TripPicture other = new TripPicture("tripPictures/trip-1/other", null, 1, 1, "#000000");
        afterNextRead = () -> trip().getPictures().add(other);
        final TripPictureCommands.AlbumResult result =
                bean.addFromAlbum(caller, TRIP_ID, List.of(pick("one"), pick("two")), null);
        Assert.assertEquals(result.added(), 1);
        Assert.assertTrue(result.refusal().startsWith("Picture limit reached"), result.refusal());
        Assert.assertEquals(trip().getPictures().size(), Trip.MAX_PICTURES);
        // The delete counts three objects per row it names and then sweeps the trip's prefix for anything unnamed:
        // the picture that did not fit must have left nothing there.
        Assert.assertEquals(bean.deleteAllForTrip(trip()), (Trip.MAX_PICTURES) * 3,
                "Nine rows counted at three each, and no orphan on top");
    }

    @Test
    public void aBatchWhoseSaveFailsAddsNothingAndStoresNothing() {
        albumPhotos.put("one", PhotoFixtures.jpeg(2400, 1600));
        saveResult = false;
        final TripPictureCommands.AlbumResult result = bean.addFromAlbum(caller, TRIP_ID, List.of(pick("one")), null);
        Assert.assertEquals(result.added(), 0);
        Assert.assertEquals(result.refusal(),
                "Not saved: the trip could not be updated with the new picture. Try again.");
        Assert.assertTrue(trip().getPictures().isEmpty());
        Assert.assertEquals(bean.deleteAllForTrip(trip()), 0, "Its renditions were deleted again");
    }

    @Test
    public void thePagesPicksAreParsedLeniently() {
        Assert.assertTrue(AlbumPicks.parse(null, Trip.MAX_PICTURES).isEmpty());
        Assert.assertTrue(AlbumPicks.parse("  ", Trip.MAX_PICTURES).isEmpty());
        Assert.assertEquals(AlbumPicks.parse("a@0.25,1; b ;@0.5,0.5;c@nonsense;d@0.1", Trip.MAX_PICTURES),
                List.of(new AlbumPicks.Pick("a", 0.25d, 1d), pick("b"), pick("c"),
                        new AlbumPicks.Pick("d", 0.1d, 0.5d)),
                "No position, or one that is not a number, centers; an entry with no id is dropped");
        final StringBuilder many = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            many.append("id").append(i).append("@0.5,0.5;");
        }
        Assert.assertEquals(AlbumPicks.parse(many.toString(), Trip.MAX_PICTURES).size(), Trip.MAX_PICTURES,
                "Nothing past the cap the host gives is read");
    }

    @Test
    public void theAlbumDialogsAddReportsOnceAndDescribesOnlyASinglePick() {
        albumPhotos.put("one", PhotoFixtures.jpeg(2400, 1600));
        albumPhotos.put("two", PhotoFixtures.jpeg(900, 600));
        viewMap.put(TripPictureCommands.ALT_VIEW_KEY, "Evening Mass");

        bean.addFromAlbumUi("");
        Assert.assertEquals(params.get("albumAdded"), false, "Nothing chosen");

        bean.addFromAlbumUi("one@0.5,0.5");
        Assert.assertEquals(params.get("albumAdded"), true);
        Assert.assertEquals(trip().getCover().getAlt(), "Evening Mass", "One pick takes the typed description");
        Assert.assertNull(viewMap.get(TripPictureCommands.ALT_VIEW_KEY), "And it is cleared for the next picture");
        viewMap.put(TripPictureCommands.ALT_VIEW_KEY, "Evening Mass");

        bean.addFromAlbumUi("one@0.5,0.5;two@0.5,0.5");
        Assert.assertEquals(params.get("albumAdded"), true);
        Assert.assertEquals(trip().getPictures().size(), 3);
        Assert.assertNull(trip().getPictures().get(1).getAlt(), "Several picks: no telling which one it describes");
        Assert.assertNull(trip().getPictures().get(2).getAlt());
        Assert.assertEquals(viewMap.get(TripPictureCommands.ALT_VIEW_KEY), "Evening Mass",
                "Several picks take none of it: it stays for the next picture");

        bean.addFromAlbumUi("not-offered@0.5,0.5;one@0.5,0.5");
        Assert.assertEquals(params.get("albumAdded"), true, "One of two went in");
        bean.addFromAlbumUi("not-offered@0.5,0.5");
        Assert.assertEquals(params.get("albumAdded"), false);
        Assert.assertEquals(viewMap.get(TripPictureCommands.ALT_VIEW_KEY), "Evening Mass",
                "Nothing went in, so nothing took it");
        Assert.assertEquals(trip().getPictures().size(), 4);
        Assert.assertEquals(bean.roomFor(TRIP_ID), Trip.MAX_PICTURES - 4);
        Assert.assertEquals(bean.roomFor(null), Trip.MAX_PICTURES);
    }

    @Test
    public void theAlbumScopeIsTheTripBeingEditedForWhoeverMayEditIt() {
        Assert.assertEquals(bean.getAlbumScope(), AlbumPhotoSource.tripScope(TRIP_ID));
        final Caller manager = caller;
        caller = new Caller(Person.Id.newInstance(), false, AuditActor.from(null), priv);
        Assert.assertNull(bean.getAlbumScope(),
                "Not its manager: a trip's public page remembers its trip too, and lists nothing from it");
        caller = manager;
        for (int i = 0; i < Trip.MAX_PICTURES; i++) {
            trip().getPictures().add(new TripPicture("tripPictures/trip-1/" + i, null, 1920, 1280, "#000000"));
        }
        Assert.assertEquals(bean.getAlbumScope(), AlbumPhotoSource.tripScope(TRIP_ID),
                "A full gallery still lists: the dialog says there is no room");
        viewMap.remove("theTripId");
        Assert.assertNull(bean.getAlbumScope(), "No trip open, no albums");
    }
}
