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
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPicture;
import org.primefaces.event.FileUploadEvent;
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
        protected void publishOutcome(final boolean ok) {
            outcomes.add(ok);
        }

        @Override
        protected void publishParam(final String name, final Object value) {
            params.put(name, value);
        }
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

        bean.describeFromUi(first, "Described");
        Assert.assertEquals(trip().getPicture(first).getAlt(), "Described");
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
}
