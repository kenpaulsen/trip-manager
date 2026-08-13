package org.paulsens.trip.action;

import jakarta.faces.component.UIComponent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.media.PhotoFixtures;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.model.BadgeImage;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.CroppedImage;
import org.primefaces.model.file.UploadedFile;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The badge-image dialog bean: the manager auth rule, upload→crop→trip-save flow, and delete. */
public class BadgePhotoCommandsTest {

    private final Map<String, String> session = new HashMap<>();
    private final Map<String, Object> viewMap = new HashMap<>();
    private final Map<String, Object> params = new HashMap<>();
    private final List<Boolean> outcomes = new ArrayList<>();
    private final List<Trip> savedTrips = new ArrayList<>();
    private boolean saveResult;
    private Trip trip;
    private Caller caller;
    private PrivilegeCommands priv;
    private MediaCommands media;
    private PendingUploads pendingUploads;
    private BadgePhotoCommands bean;

    @BeforeMethod
    public void setUp() throws Exception {
        session.clear();
        viewMap.clear();
        params.clear();
        outcomes.clear();
        savedTrips.clear();
        saveResult = true;
        trip = Trip.builder().id("trip-1").title("Test Pilgrimage").build();
        caller = callerFor(Person.Id.newInstance(), true);
        priv = Mockito.mock(PrivilegeCommands.class);
        media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.isUploadEnabled()).thenReturn(false);
        pendingUploads = new PendingUploads();
        bean = new BadgePhotoCommands() {
            @Override
            protected Caller caller() {
                return caller;
            }

            @Override
            protected Trip subjectTrip() {
                return trip;
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
            protected void publishOutcome(final boolean stored) {
                outcomes.add(stored);
            }

            @Override
            protected void publishParam(final String name, final Object value) {
                params.put(name, value);
            }

            @Override
            protected boolean saveTrip(final Trip toSave) {
                savedTrips.add(toSave);
                return saveResult;
            }
        };
        set("priv", priv);
        set("media", media);
        bean.setPendingUploadsForTest(pendingUploads);
    }

    private void set(final String field, final Object value) throws Exception {
        final Field declared = BadgePhotoCommands.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(bean, value);
    }

    private static Caller callerFor(final Person.Id id, final boolean admin) {
        return new Caller(id, admin, AuditActor.from(null), new PrivilegeCommands());
    }

    private static FileUploadEvent uploadOf(final byte[] bytes, final String fileName) {
        final UploadedFile file = Mockito.mock(UploadedFile.class);
        Mockito.when(file.getContent()).thenReturn(bytes);
        Mockito.when(file.getSize()).thenReturn((long) bytes.length);
        Mockito.when(file.getFileName()).thenReturn(fileName);
        return new FileUploadEvent(Mockito.mock(UIComponent.class), file, 1);
    }

    private void uploadFixture() {
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(2000, 1600), "GroupAtTheCross.jpg"));
    }

    private static CroppedImage crop(final int left, final int top, final int width, final int height) {
        return new CroppedImage(null, null, left, top, width, height);
    }

    // --- authorization ---

    @Test
    public void anAdminAndATripManagerMayUploadEveryoneElseIsRefused() {
        uploadFixture();
        Assert.assertTrue(bean.isUploadPending(), "A site admin may add badge images");

        session.clear();
        caller = callerFor(Person.Id.newInstance(), false);
        Mockito.when(priv.check("tripMgr", trip.getId(), caller.personId())).thenReturn(true);
        uploadFixture();
        Assert.assertTrue(bean.isUploadPending(), "A trip manager may add badge images");

        session.clear();
        caller = callerFor(Person.Id.newInstance(), false);
        Mockito.when(priv.check(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any())).thenReturn(false);
        uploadFixture();
        Assert.assertFalse(bean.isUploadPending(), "A plain member is refused");

        caller = callerFor(null, false);
        uploadFixture();
        Assert.assertFalse(bean.isUploadPending(), "Anonymous is refused");

        caller = callerFor(Person.Id.newInstance(), true);
        trip = null;
        uploadFixture();
        Assert.assertFalse(bean.mayEdit(null), "No trip means no permission");
    }

    // --- upload ---

    @Test
    public void garbageOversizedAndEmptyUploadsAreRefused() {
        bean.handleUpload(uploadOf("not an image".getBytes(), "x.jpg"));
        Assert.assertFalse(bean.isUploadPending());

        final UploadedFile huge = Mockito.mock(UploadedFile.class);
        Mockito.when(huge.getSize()).thenReturn(BadgePhotoCommands.MAX_UPLOAD_BYTES + 1);
        bean.handleUpload(new FileUploadEvent(Mockito.mock(UIComponent.class), huge, 1));
        Assert.assertFalse(bean.isUploadPending());

        final UploadedFile empty = Mockito.mock(UploadedFile.class);
        Mockito.when(empty.getSize()).thenReturn(0L);
        bean.handleUpload(new FileUploadEvent(Mockito.mock(UIComponent.class), empty, 1));
        Assert.assertFalse(bean.isUploadPending());
    }

    @Test
    public void theImageCapRefusesTheTwentyFirst() {
        for (int i = 0; i < BadgePhotoCommands.MAX_IMAGES; i++) {
            trip.getBadgeImages().add(new BadgeImage("badgeImages/trip-1/" + i + ".jpg", "img " + i));
        }
        uploadFixture();
        Assert.assertFalse(bean.isUploadPending(), "The cap applies at upload time");

        trip.getBadgeImages().removeFirst();
        uploadFixture();
        trip.getBadgeImages().add(new BadgeImage("badgeImages/trip-1/extra.jpg", "raced in"));
        Assert.assertFalse(bean.applyCrop(trip, null), "And again at confirm time");
    }

    // --- crop / store ---

    @Test
    public void applyCropStoresAnExact1275AndPublishesItOnTheTrip() throws Exception {
        uploadFixture();
        session.put(BadgePhotoCommands.SELECTED_KEY, "BlueCross");
        // Preview space is 800x640 (2000x1600 capped); this maps to 1500x1500 at full resolution.
        Assert.assertTrue(bean.applyCrop(trip, crop(0, 0, 600, 600)));

        Assert.assertEquals(trip.getBadgeImages().size(), 1);
        final BadgeImage stored = trip.getBadgeImages().getFirst();
        Assert.assertTrue(stored.getKey().startsWith("badgeImages/trip-1/"), stored.getKey());
        Assert.assertEquals(stored.getLabel(), "GroupAtTheCross", "The filename is the default label");
        Assert.assertEquals(savedTrips, List.of(trip), "Saving the trip is what publishes the image");
        Assert.assertEquals(session.get(BadgePhotoCommands.SELECTED_KEY), stored.getKey(),
                "The new image becomes the page's selection for the reload");
        Assert.assertFalse(bean.isUploadPending(), "A stored crop consumes the token");

        final byte[] jpeg = bean.localGet(stored.getKey()).orElseThrow();
        final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        Assert.assertEquals(decoded.getWidth(), BadgePhotoCommands.BADGE_SIZE);
        Assert.assertEquals(decoded.getHeight(), BadgePhotoCommands.BADGE_SIZE);
    }

    /** An undersized source is stored anyway — but the manager is told, with the numbers. */
    @Test
    public void anUndersizedSourceWarnsButStillStores() throws Exception {
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(800, 600), "small.jpg"));
        try (org.mockito.MockedStatic<TripUtilCommands> util =
                Mockito.mockStatic(TripUtilCommands.class)) {
            Assert.assertTrue(bean.applyCrop(trip, null));
            util.verify(() -> TripUtilCommands.addFacesMessage(
                    ArgumentMatchers.eq(jakarta.faces.application.FacesMessage.SEVERITY_WARN),
                    ArgumentMatchers.contains("600×600"), ArgumentMatchers.anyString()));
        }
        Assert.assertEquals(trip.getBadgeImages().size(), 1, "Warned, not refused");
        final byte[] jpeg = bean.localGet(trip.getBadgeImages().getFirst().getKey()).orElseThrow();
        Assert.assertEquals(ImageIO.read(new ByteArrayInputStream(jpeg)).getWidth(),
                BadgePhotoCommands.BADGE_SIZE, "Upscaled to print size regardless");
    }

    /** A big-enough source must NOT growl — a warning on every upload trains people to ignore it. */
    @Test
    public void aBigEnoughSourceDoesNotWarn() {
        uploadFixture();
        try (org.mockito.MockedStatic<TripUtilCommands> util =
                Mockito.mockStatic(TripUtilCommands.class)) {
            Assert.assertTrue(bean.applyCrop(trip, null));
            util.verify(() -> TripUtilCommands.addFacesMessage(
                    ArgumentMatchers.eq(jakarta.faces.application.FacesMessage.SEVERITY_WARN),
                    ArgumentMatchers.anyString(), ArgumentMatchers.anyString()), Mockito.never());
        }
    }

    @Test
    public void aTypedNameBeatsTheFilename() {
        uploadFixture();
        viewMap.put(BadgePhotoCommands.LABEL_VIEW_KEY, "  Our Group Photo  ");
        Assert.assertTrue(bean.applyCrop(trip, null));
        Assert.assertEquals(trip.getBadgeImages().getFirst().getLabel(), "Our Group Photo");
    }

    @Test
    public void aBlankNameAndAMissingFilenameStillLabelSomething() {
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(1400, 1400), null));
        viewMap.put(BadgePhotoCommands.LABEL_VIEW_KEY, "   ");
        Assert.assertTrue(bean.applyCrop(trip, null));
        Assert.assertEquals(trip.getBadgeImages().getFirst().getLabel(), "Custom image");
    }

    @Test
    public void applyCropWithoutAnUploadOrWithAForeignTokenFails() {
        Assert.assertFalse(bean.applyCrop(trip, crop(0, 0, 100, 100)), "No upload parked");

        uploadFixture();
        final Trip other = Trip.builder().id("trip-2").title("Other").build();
        Assert.assertFalse(bean.applyCrop(other, crop(0, 0, 100, 100)),
                "A token staged for one trip cannot store on another");
        Assert.assertTrue(bean.isUploadPending(), "The refused upload survives");
    }

    @Test
    public void aFailedTripSaveRemovesTheOrphanObject() {
        uploadFixture();
        saveResult = false;
        Assert.assertFalse(bean.applyCrop(trip, null));
        Assert.assertTrue(trip.getBadgeImages().isEmpty(), "The unsaved entry is rolled back");
        Assert.assertTrue(bean.isUploadPending(), "A failed save must not eat the upload");
    }

    @Test
    public void confirmActionsReadDialogStateAndPublish() {
        uploadFixture();
        viewMap.put("photoCrop", crop(0, 0, 300, 300));
        bean.confirmCrop();
        Assert.assertEquals(outcomes, List.of(true));

        outcomes.clear();
        bean.confirmCrop();
        Assert.assertEquals(outcomes, List.of(false), "The consumed token cannot confirm twice");

        outcomes.clear();
        uploadFixture();
        viewMap.remove("photoCrop");
        bean.confirmFullPhoto();
        Assert.assertEquals(outcomes, List.of(true), "Use-full-photo confirms with no rect");
        Assert.assertEquals(trip.getBadgeImages().size(), 2);
    }

    @Test
    public void aCorruptParkedOriginalIsAMessageNotA500() {
        final PendingUploads.Pending garbage = pendingUploads.put("garbage".getBytes(),
                new PhotoProcessor.PreviewImage(new byte[] {1}, 10, 10, 10, 10),
                caller.personId().getValue(), BadgePhotoCommands.PURPOSE, trip.getId());
        session.put(BadgePhotoCommands.TOKEN_KEY, garbage.token());
        Assert.assertFalse(bean.applyCrop(trip, null));
        Assert.assertTrue(trip.getBadgeImages().isEmpty());
    }

    @Test
    public void cancelDropsTheParkedBytesImmediately() {
        uploadFixture();
        Assert.assertTrue(bean.isUploadPending());
        bean.cancelUpload();
        Assert.assertFalse(bean.isUploadPending());
        Assert.assertFalse(session.containsKey(BadgePhotoCommands.LABEL_DEFAULT_KEY));
        bean.cancelUpload();   // idempotent
    }

    // --- S3 mode ---

    @Test
    public void s3ModeStoresThroughMediaAndRefusalsPropagate() {
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.putObject(ArgumentMatchers.startsWith("badgeImages/trip-1/"),
                ArgumentMatchers.any(), ArgumentMatchers.eq("image/jpeg"),
                ArgumentMatchers.eq(BadgePhotoCommands.CACHE_SECONDS), ArgumentMatchers.eq(false)))
                .thenReturn(true);
        uploadFixture();
        Assert.assertTrue(bean.applyCrop(trip, null));
        Assert.assertEquals(trip.getBadgeImages().size(), 1);
        Assert.assertTrue(bean.localGet(trip.getBadgeImages().getFirst().getKey()).isEmpty(),
                "S3 mode keeps nothing in the local store");

        Mockito.when(media.putObject(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyBoolean()))
                .thenReturn(false);
        uploadFixture();
        Assert.assertFalse(bean.applyCrop(trip, null), "A refused S3 put is a refused crop");
        Assert.assertEquals(trip.getBadgeImages().size(), 1);
    }

    @Test
    public void s3ModeDeleteRemovesTheObjectAndInvalidatesTheCdn() {
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        final String key = "badgeImages/trip-1/42.jpg";
        trip.getBadgeImages().add(new BadgeImage(key, "old"));
        Assert.assertTrue(bean.delete(trip, key));
        Mockito.verify(media).deleteObject(key);
        Mockito.verify(media).invalidateCdn(List.of("/" + key));
    }

    // --- delete ---

    @Test
    public void deleteRemovesEverywhereAndResetsTheSelection() {
        uploadFixture();
        Assert.assertTrue(bean.applyCrop(trip, null));
        final String key = trip.getBadgeImages().getFirst().getKey();
        Assert.assertEquals(session.get(BadgePhotoCommands.SELECTED_KEY), key);

        bean.deleteFromUi(trip, key);
        Assert.assertEquals(params.get("badgeDeleted"), Boolean.TRUE);
        Assert.assertTrue(trip.getBadgeImages().isEmpty());
        Assert.assertTrue(bean.localGet(key).isEmpty(), "The bytes are gone too");
        Assert.assertFalse(session.containsKey(BadgePhotoCommands.SELECTED_KEY),
                "Deleting the selected image falls back to the page default");
    }

    @Test
    public void deleteLeavesAnUnselectedSessionAlone() {
        final String key = "badgeImages/trip-1/1.jpg";
        trip.getBadgeImages().add(new BadgeImage(key, "one"));
        session.put(BadgePhotoCommands.SELECTED_KEY, "BlueCross");
        Assert.assertTrue(bean.delete(trip, key));
        Assert.assertEquals(session.get(BadgePhotoCommands.SELECTED_KEY), "BlueCross");
    }

    @Test
    public void deleteRefusalsChangeNothing() {
        final String key = "badgeImages/trip-1/1.jpg";
        trip.getBadgeImages().add(new BadgeImage(key, "one"));

        bean.deleteFromUi(trip, "badgeImages/trip-1/other.jpg");
        Assert.assertEquals(params.get("badgeDeleted"), Boolean.FALSE, "Unknown key");
        Assert.assertEquals(trip.getBadgeImages().size(), 1);

        saveResult = false;
        Assert.assertFalse(bean.delete(trip, key), "A failed trip save aborts the delete");
        Assert.assertEquals(trip.getBadgeImages().size(), 1, "The entry is restored");

        saveResult = true;
        caller = callerFor(Person.Id.newInstance(), false);
        Assert.assertFalse(bean.delete(trip, key), "A non-manager may not delete");
        Assert.assertEquals(trip.getBadgeImages().size(), 1);
    }

    // --- lookups the page uses ---

    @Test
    public void isCustomAndUrlAnswerThePagesQuestions() {
        final String key = "badgeImages/trip-1/1.jpg";
        trip.getBadgeImages().add(new BadgeImage(key, "one"));
        Assert.assertTrue(bean.isCustom(trip, key));
        Assert.assertFalse(bean.isCustom(trip, "BlueCross"));
        Assert.assertFalse(bean.isCustom(trip, null));
        Assert.assertFalse(bean.isCustom(null, key));

        Assert.assertEquals(bean.url(key), "/badge-photos/" + key,
                "Local mode serves through the badge-photo servlet (no FacesContext = no prefix)");
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.publicUrl(key)).thenReturn("https://cdn.example.com/" + key);
        Assert.assertEquals(bean.url(key), "https://cdn.example.com/" + key);
    }

    @Test
    public void baseNameStripsPathAndExtension() {
        Assert.assertEquals(BadgePhotoCommands.baseName("GroupAtTheCross.jpg"), "GroupAtTheCross");
        Assert.assertEquals(BadgePhotoCommands.baseName("C:\\Users\\me\\photo.HEIC"), "photo");
        Assert.assertEquals(BadgePhotoCommands.baseName("dir/sub/pic.png"), "pic");
        Assert.assertEquals(BadgePhotoCommands.baseName(".hidden"), ".hidden");
        Assert.assertNull(BadgePhotoCommands.baseName(null));
        Assert.assertNull(BadgePhotoCommands.baseName(""));
        Assert.assertNull(BadgePhotoCommands.baseName("photos/"), "A bare directory has no name");
    }

    /** The real (un-overridden) seams must answer safely when there is no FacesContext at all. */
    @Test
    public void theRealSeamsFailClosedWithoutAFacesContext() {
        final BadgePhotoCommands bare = new BadgePhotoCommands();
        bare.handleUpload(uploadOf(PhotoFixtures.jpeg(100, 100), "x.jpg"));
        Assert.assertFalse(bare.isUploadPending(), "No context means no trip means refused");
        bare.confirmCrop();
        bare.confirmFullPhoto();
        bare.cancelUpload();
        bare.publishOutcome(true);
        bare.publishParam("badgeDeleted", true);
        bare.sessionPut("k", "v");
    }

    /** The scoped seams' real bodies, driven through a mocked FacesContext/PrimeFaces pair. */
    @Test
    public void theRealScopedSeamsWorkWithAMockedContext() {
        try (org.mockito.MockedStatic<jakarta.faces.context.FacesContext> fc =
                    Mockito.mockStatic(jakarta.faces.context.FacesContext.class);
                org.mockito.MockedStatic<org.primefaces.PrimeFaces> pfs =
                    Mockito.mockStatic(org.primefaces.PrimeFaces.class)) {
            final jakarta.faces.context.FacesContext ctx =
                    Mockito.mock(jakarta.faces.context.FacesContext.class, Mockito.RETURNS_DEEP_STUBS);
            fc.when(jakarta.faces.context.FacesContext::getCurrentInstance).thenReturn(ctx);
            final Map<String, Object> sess = new HashMap<>();
            Mockito.when(ctx.getExternalContext().getSessionMap()).thenReturn(sess);
            Mockito.when(ctx.getExternalContext().getRequestContextPath()).thenReturn("/app");
            final org.primefaces.PrimeFaces pf =
                    Mockito.mock(org.primefaces.PrimeFaces.class, Mockito.RETURNS_DEEP_STUBS);
            pfs.when(org.primefaces.PrimeFaces::current).thenReturn(pf);
            Mockito.when(pf.isAjaxRequest()).thenReturn(true);

            final BadgePhotoCommands bare = new BadgePhotoCommands();
            bare.sessionPut("k", "v");
            Assert.assertEquals(sess.get("k"), "v");
            bare.sessionPut("k", null);
            Assert.assertFalse(sess.containsKey("k"));

            bare.publishParam("badgeDeleted", true);
            Mockito.verify(pf.ajax()).addCallbackParam("badgeDeleted", true);
            Mockito.when(pf.isAjaxRequest()).thenReturn(false);
            bare.publishParam("badgeDeleted", false);

            try {
                final Field field = BadgePhotoCommands.class.getDeclaredField("media");
                field.setAccessible(true);
                field.set(bare, media);
            } catch (final ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            }
            Assert.assertEquals(bare.url("badgeImages/t/1.jpg"), "/app/badge-photos/badgeImages/t/1.jpg",
                    "The context path prefixes local URLs when a FacesContext exists");
        }
    }

    /** The local store's byte budget evicts oldest-first rather than growing without bound. */
    @Test
    public void theLocalStoreEvictsOldestFirstPastItsBudget() {
        uploadFixture();
        Assert.assertTrue(bean.applyCrop(trip, null));
        final String first = trip.getBadgeImages().getFirst().getKey();
        final long size = bean.localGet(first).orElseThrow().length;
        // A budget that holds one image but not two: the second store must evict the first, not itself.
        bean.localStoreMaxBytesForTest(size + 1);

        uploadFixture();
        Assert.assertTrue(bean.applyCrop(trip, null));
        final String second = trip.getBadgeImages().get(1).getKey();
        Assert.assertTrue(bean.localGet(first).isEmpty(), "The oldest object was evicted");
        Assert.assertTrue(bean.localGet(second).isPresent(), "The newest object survives");
    }
}
