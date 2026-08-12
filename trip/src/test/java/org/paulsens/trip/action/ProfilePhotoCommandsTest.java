package org.paulsens.trip.action;

import jakarta.faces.component.UIComponent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.media.PhotoFixtures;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.model.Person;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.CroppedImage;
import org.primefaces.model.file.UploadedFile;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The dialog bean: the auth matrix, the upload→crop token flow, and the slot bookkeeping around it. */
public class ProfilePhotoCommandsTest {

    private final Map<String, String> session = new HashMap<>();
    private final Map<String, Object> viewMap = new HashMap<>();
    private final Map<String, Object> params = new HashMap<>();
    private final java.util.List<Boolean> outcomes = new java.util.ArrayList<>();
    private boolean bgFlagOn;
    private boolean bgThrow;
    private byte[] cannedCutout;
    private Person subject;
    private Caller caller;
    private PersonCommands people;
    private ProfilePhotos profilePhotos;
    private PendingUploads pendingUploads;
    private ProfilePhotoCommands bean;

    @BeforeMethod
    public void setUp() throws Exception {
        session.clear();
        viewMap.clear();
        params.clear();
        outcomes.clear();
        bgFlagOn = false;
        bgThrow = false;
        cannedCutout = null;
        subject = new Person();
        caller = callerFor(subject.getId(), false);
        people = Mockito.mock(PersonCommands.class);
        profilePhotos = Mockito.mock(ProfilePhotos.class);
        pendingUploads = new PendingUploads();
        bean = new ProfilePhotoCommands() {
            @Override
            protected Caller caller() {
                return caller;
            }

            @Override
            protected Person subject() {
                return subject;
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
            protected boolean bgRemovalEnabled() {
                return bgFlagOn;
            }

            @Override
            protected java.util.Optional<byte[]> removeBackground(final byte[] source) {
                if (bgThrow) {
                    throw new org.paulsens.trip.media.PhotoRejectedException("canned rejection");
                }
                return java.util.Optional.ofNullable(cannedCutout);
            }
        };
        set("people", people);
        set("profilePhotos", profilePhotos);
        bean.setPendingUploadsForTest(pendingUploads);
    }

    private void set(final String field, final Object value) throws Exception {
        final Field declared = ProfilePhotoCommands.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(bean, value);
    }

    private static Caller callerFor(final Person.Id id, final boolean admin) {
        return new Caller(id, admin, AuditActor.from(null), new PrivilegeCommands());
    }

    private static FileUploadEvent uploadOf(final byte[] bytes) {
        final UploadedFile file = Mockito.mock(UploadedFile.class);
        Mockito.when(file.getContent()).thenReturn(bytes);
        Mockito.when(file.getSize()).thenReturn((long) bytes.length);
        return new FileUploadEvent(Mockito.mock(UIComponent.class), file, 1);
    }

    private void uploadFixture() {
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(1000, 800)));
    }

    // --- upload ---

    @Test
    public void selfCanUploadAndTheTokenParksForTheCropperImage() throws Exception {
        uploadFixture();
        Assert.assertTrue(bean.isUploadPending());
        // The dialog's image comes from the shared #{pendingPhoto} bean, off the same session token.
        final PendingPhotoView view = new PendingPhotoView() {
            @Override
            protected Caller caller() {
                return caller;
            }

            @Override
            protected String sessionGet(final String key) {
                return session.get(key);
            }
        };
        view.setPendingUploadsForTest(pendingUploads);
        final byte[] streamed = view.getCropperImage().getStream().get().readAllBytes();
        final BufferedImage preview = ImageIO.read(new ByteArrayInputStream(streamed));
        Assert.assertEquals(preview.getWidth(), PhotoProcessor.MAX_SMALL_WIDTH,
                "The cropper must be handed the display-sized preview");

        session.clear();
        Assert.assertEquals(view.getCropperImage().getStream().get().readAllBytes().length, 0,
                "No parked token streams empty, never an error");
        Assert.assertEquals(new PendingPhotoView().getCropperImage().getStream().get()
                .readAllBytes().length, 0, "The real seams fail closed without a FacesContext");
    }

    @Test
    public void aStrangerIsRefused() {
        caller = callerFor(Person.Id.newInstance(), false);
        Mockito.when(people.canAccessUserId(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(false);
        uploadFixture();
        Assert.assertFalse(bean.isUploadPending());
    }

    @Test
    public void aManagerAndAnAdminAreAllowed() {
        caller = callerFor(Person.Id.newInstance(), false);
        Mockito.when(people.canAccessUserId(ArgumentMatchers.any(), ArgumentMatchers.eq(subject.getId())))
                .thenReturn(true);
        uploadFixture();
        Assert.assertTrue(bean.isUploadPending(), "A family manager may edit a managed profile");

        session.clear();
        caller = callerFor(Person.Id.newInstance(), true);
        Mockito.when(people.canAccessUserId(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(false);
        uploadFixture();
        Assert.assertTrue(bean.isUploadPending(), "A site admin may edit any profile");
    }

    @Test
    public void anonymousAndMissingSubjectsAreRefused() {
        caller = callerFor(null, false);
        uploadFixture();
        Assert.assertFalse(bean.isUploadPending());

        caller = callerFor(Person.Id.newInstance(), true);
        subject = null;
        uploadFixture();
        Assert.assertFalse(bean.isUploadPending());
    }

    @Test
    public void garbageAndOversizedUploadsAreRefused() {
        bean.handleUpload(uploadOf("not an image".getBytes()));
        Assert.assertFalse(bean.isUploadPending());

        final UploadedFile huge = Mockito.mock(UploadedFile.class);
        Mockito.when(huge.getSize()).thenReturn(ProfilePhotoCommands.MAX_UPLOAD_BYTES + 1);
        bean.handleUpload(new FileUploadEvent(Mockito.mock(UIComponent.class), huge, 1));
        Assert.assertFalse(bean.isUploadPending());

        final UploadedFile empty = Mockito.mock(UploadedFile.class);
        Mockito.when(empty.getSize()).thenReturn(0L);
        bean.handleUpload(new FileUploadEvent(Mockito.mock(UIComponent.class), empty, 1));
        Assert.assertFalse(bean.isUploadPending());
    }

    // --- crop ---

    @Test
    public void applyCropStoresAnExact512IntoTheNextFreeSlot() throws Exception {
        uploadFixture();
        Mockito.when(profilePhotos.nextFreeSlot(subject.getId().getValue())).thenReturn(2);
        Mockito.when(profilePhotos.store(ArgumentMatchers.eq(subject.getId().getValue()),
                ArgumentMatchers.eq(2), ArgumentMatchers.any())).thenReturn(true);

        // Preview space is 800x640 (1000x800 capped); this rect maps to 500x500 at full resolution.
        final boolean stored = bean.applyCrop(subject, crop(0, 0, 400, 400), null);
        Assert.assertTrue(stored);
        final ArgumentCaptor<byte[]> jpeg = ArgumentCaptor.forClass(byte[].class);
        Mockito.verify(profilePhotos).store(ArgumentMatchers.eq(subject.getId().getValue()),
                ArgumentMatchers.eq(2), jpeg.capture());
        final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg.getValue()));
        Assert.assertEquals(decoded.getWidth(), PhotoProcessor.PROFILE_SIZE);
        Assert.assertEquals(decoded.getHeight(), PhotoProcessor.PROFILE_SIZE);
        Assert.assertFalse(bean.isUploadPending(), "A stored crop consumes the token");
    }

    @Test
    public void applyCropReplacesTheRequestedSlot() {
        uploadFixture();
        Mockito.when(profilePhotos.store(ArgumentMatchers.anyString(), ArgumentMatchers.eq(3),
                ArgumentMatchers.any())).thenReturn(true);
        Assert.assertTrue(bean.applyCrop(subject, crop(0, 0, 100, 100), 3));
        Mockito.verify(profilePhotos, Mockito.never()).nextFreeSlot(ArgumentMatchers.anyString());
    }

    @Test
    public void applyCropRefusesWhenEverySlotIsTaken() {
        uploadFixture();
        Mockito.when(profilePhotos.nextFreeSlot(ArgumentMatchers.anyString())).thenReturn(0);
        Assert.assertFalse(bean.applyCrop(subject, crop(0, 0, 100, 100), null));
        Mockito.verify(profilePhotos, Mockito.never()).store(ArgumentMatchers.anyString(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.any());
        Assert.assertTrue(bean.isUploadPending(), "The upload survives so they can pick another slot");
    }

    @Test
    public void applyCropWithoutAnUploadFails() {
        Assert.assertFalse(bean.applyCrop(subject, crop(0, 0, 100, 100), null));
    }

    @Test
    public void applyCropRefusesATokenStagedForSomeoneElse() {
        uploadFixture();
        // Same caller, different subject (an admin editing B after uploading for A).
        caller = callerFor(caller.personId(), true);
        final Person other = new Person();
        Assert.assertFalse(bean.applyCrop(other, crop(0, 0, 100, 100), null));
    }

    @Test
    public void applyCropSurvivesAFailedStore() {
        uploadFixture();
        Mockito.when(profilePhotos.nextFreeSlot(ArgumentMatchers.anyString())).thenReturn(1);
        Mockito.when(profilePhotos.store(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt(),
                ArgumentMatchers.any())).thenReturn(false);
        Assert.assertFalse(bean.applyCrop(subject, crop(0, 0, 100, 100), null));
        Assert.assertTrue(bean.isUploadPending(), "A store failure must not eat the upload");
    }

    // --- delete / cancel ---

    @Test
    public void deleteSlotDelegatesAndReportsHonestly() {
        Mockito.when(profilePhotos.deleteSlot(subject.getId().getValue(), 2)).thenReturn(true);
        Assert.assertTrue(bean.deleteSlot(subject, 2));
        Assert.assertFalse(bean.deleteSlot(subject, 3), "An empty slot deletes nothing");

        caller = callerFor(Person.Id.newInstance(), false);
        Assert.assertFalse(bean.deleteSlot(subject, 2), "A stranger may not delete");
        Mockito.verify(profilePhotos, Mockito.times(1)).deleteSlot(subject.getId().getValue(), 2);
    }

    @Test
    public void cancelDropsTheParkedBytesImmediately() {
        uploadFixture();
        Assert.assertTrue(bean.isUploadPending());
        bean.cancelUpload();
        Assert.assertFalse(bean.isUploadPending());
        bean.cancelUpload();   // idempotent
    }

    @Test
    public void theCropperStreamsEmptyWhenNothingIsPending() {
        Assert.assertFalse(bean.isUploadPending());
    }

    /** No cropper value means "use the whole photo": the centered max square, not a failure. */
    @Test
    public void applyCropWithoutARectTakesTheCenteredSquare() throws Exception {
        uploadFixture();
        Mockito.when(profilePhotos.nextFreeSlot(ArgumentMatchers.anyString())).thenReturn(1);
        Mockito.when(profilePhotos.store(ArgumentMatchers.anyString(), ArgumentMatchers.eq(1),
                ArgumentMatchers.any())).thenReturn(true);
        Assert.assertTrue(bean.applyCrop(subject, null, null));
        final ArgumentCaptor<byte[]> jpeg = ArgumentCaptor.forClass(byte[].class);
        Mockito.verify(profilePhotos).store(ArgumentMatchers.anyString(), ArgumentMatchers.eq(1),
                jpeg.capture());
        Assert.assertEquals(ImageIO.read(new ByteArrayInputStream(jpeg.getValue())).getWidth(),
                PhotoProcessor.PROFILE_SIZE);
    }

    /** The dialog's confirm actions read viewScope state and publish the outcome for oncomplete JS. */
    @Test
    public void confirmActionsReadDialogStateAndPublish() {
        uploadFixture();
        // Long on purpose: EL number literals (the Add button's value="#{0}") land in viewScope as Long.
        viewMap.put("photoCrop", crop(0, 0, 300, 300));
        viewMap.put("photoTargetSlot", 2L);
        Mockito.when(profilePhotos.store(ArgumentMatchers.anyString(), ArgumentMatchers.eq(2),
                ArgumentMatchers.any())).thenReturn(true);
        bean.confirmCrop();
        Assert.assertEquals(outcomes, java.util.List.of(true));

        outcomes.clear();
        bean.confirmCrop();
        Assert.assertEquals(outcomes, java.util.List.of(false), "The consumed token cannot confirm twice");

        outcomes.clear();
        uploadFixture();
        viewMap.remove("photoCrop");
        Mockito.when(profilePhotos.store(ArgumentMatchers.anyString(), ArgumentMatchers.eq(2),
                ArgumentMatchers.any())).thenReturn(true);
        bean.confirmFullPhoto();
        Assert.assertEquals(outcomes, java.util.List.of(true), "Use-full-photo confirms with no rect");
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
            final org.primefaces.PrimeFaces pf =
                    Mockito.mock(org.primefaces.PrimeFaces.class, Mockito.RETURNS_DEEP_STUBS);
            pfs.when(org.primefaces.PrimeFaces::current).thenReturn(pf);
            Mockito.when(pf.isAjaxRequest()).thenReturn(true);

            final ProfilePhotoCommands bare = new ProfilePhotoCommands();
            bare.sessionPut("k", "v");
            Assert.assertEquals(sess.get("k"), "v");
            bare.sessionPut("k", null);
            Assert.assertFalse(sess.containsKey("k"));

            bare.publishOutcome(true);
            Mockito.verify(pf.ajax()).addCallbackParam("cropStored", true);
            bare.publishParam("bgReady", true);
            Mockito.verify(pf.ajax()).addCallbackParam("bgReady", true);
            Mockito.when(pf.isAjaxRequest()).thenReturn(false);
            bare.publishOutcome(false);
            bare.publishParam("bgReady", false);
        }
    }

    /** The real (un-overridden) seams must answer safely when there is no FacesContext at all. */
    @Test
    public void theRealSeamsFailClosedWithoutAFacesContext() throws Exception {
        final ProfilePhotoCommands bare = new ProfilePhotoCommands();
        bare.handleUpload(uploadOf(PhotoFixtures.jpeg(100, 100)));
        Assert.assertFalse(bare.isUploadPending(), "No context means no subject means refused");
        bare.confirmCrop();
        bare.confirmFullPhoto();
        bare.cancelUpload();
        bare.publishOutcome(true);
        bare.publishParam("bgReady", true);
        Assert.assertFalse(bare.bgRemovalEnabled(), "No injected config means the flag reads off");
        Assert.assertEquals(bare.removeBackground(PhotoFixtures.jpeg(64, 64)).isPresent(),
                org.paulsens.trip.media.BackgroundRemover.isAvailable(),
                "The real seam infers exactly when the model is on the classpath");
    }

    private static CroppedImage crop(final int left, final int top, final int width, final int height) {
        return new CroppedImage(null, null, left, top, width, height);
    }

    // --- background replacement ---

    private byte[] transparentLeftRedRight() {
        final java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(100, 100,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 100; y++) {
            for (int x = 50; x < 100; x++) {
                img.setRGB(x, y, 0xFFFF0000);
            }
        }
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (final java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        return out.toByteArray();
    }

    @Test
    public void bgRemovalIsRefusedWhenTheFlagIsOff() {
        cannedCutout = transparentLeftRedRight();
        Mockito.when(profilePhotos.currentBytes(subject.getId().getValue(), 1))
                .thenReturn(java.util.Optional.of(PhotoFixtures.jpeg(512, 512)));
        bean.startBgRemoval(subject, 1);
        Assert.assertEquals(params.get("bgReady"), Boolean.FALSE);
        Assert.assertFalse(session.containsKey("profileBgCutoutToken"));
    }

    @Test
    public void bgRemovalParksTheCutoutAndPublishesItsUrl() {
        bgFlagOn = true;
        cannedCutout = transparentLeftRedRight();
        Mockito.when(profilePhotos.currentBytes(subject.getId().getValue(), 2))
                .thenReturn(java.util.Optional.of(PhotoFixtures.jpeg(512, 512)));
        bean.startBgRemoval(subject, 2);
        Assert.assertEquals(params.get("bgReady"), Boolean.TRUE);
        final String token = session.get("profileBgCutoutToken");
        Assert.assertNotNull(token);
        Assert.assertEquals(params.get("cutoutUrl"), "/profile-photos/preview/" + token);
        Assert.assertEquals(pendingUploads.peek(token, subject.getId().getValue()).orElseThrow().purpose(),
                "profileBg");
    }

    @Test
    public void bgRemovalReportsBusyAndEmptySlots() {
        bgFlagOn = true;
        Mockito.when(profilePhotos.currentBytes(subject.getId().getValue(), 1))
                .thenReturn(java.util.Optional.empty());
        bean.startBgRemoval(subject, 1);
        Assert.assertEquals(params.get("bgReady"), Boolean.FALSE, "Empty slot");

        Mockito.when(profilePhotos.currentBytes(subject.getId().getValue(), 1))
                .thenReturn(java.util.Optional.of(PhotoFixtures.jpeg(512, 512)));
        cannedCutout = null;   // the model is busy/unavailable
        bean.startBgRemoval(subject, 1);
        Assert.assertEquals(params.get("bgReady"), Boolean.FALSE, "Busy model");
    }

    @Test
    public void applyBackgroundCompositesAndStoresANewVersion() throws Exception {
        bgRemovalParksTheCutoutAndPublishesItsUrl();
        viewMap.put("bgPhotoColor", "#3366ff");
        Mockito.when(profilePhotos.store(ArgumentMatchers.eq(subject.getId().getValue()),
                ArgumentMatchers.eq(2), ArgumentMatchers.any())).thenReturn(true);

        Assert.assertTrue(bean.applyBackground(subject));
        final ArgumentCaptor<byte[]> jpeg = ArgumentCaptor.forClass(byte[].class);
        Mockito.verify(profilePhotos).store(ArgumentMatchers.eq(subject.getId().getValue()),
                ArgumentMatchers.eq(2), jpeg.capture());
        final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg.getValue()));
        Assert.assertEquals(decoded.getWidth(), PhotoProcessor.PROFILE_SIZE);
        // Left half of the cutout is transparent: the fill color shows through (JPEG-approximately).
        final int left = decoded.getRGB(50, 256);
        Assert.assertTrue(((left >> 16) & 0xFF) < 0x80, "Transparent side shows the blue fill: " + left);
        Assert.assertEquals(params.get("bgApplied"), Boolean.TRUE);
        Assert.assertFalse(session.containsKey("profileBgCutoutToken"), "Applied consumes the token");
    }

    @Test
    public void applyBackgroundGuards() {
        bgFlagOn = true;
        Assert.assertFalse(bean.applyBackground(subject), "No parked cutout");

        bgRemovalParksTheCutoutAndPublishesItsUrl();
        viewMap.put("bgPhotoColor", "chartreuse");
        Assert.assertFalse(bean.applyBackground(subject), "Not a hex color");
        viewMap.remove("bgPhotoColor");
        Assert.assertFalse(bean.applyBackground(subject), "No color at all");

        // Another subject cannot apply a cutout parked for someone else (admin editing two people).
        caller = callerFor(caller.personId(), true);
        viewMap.put("bgPhotoColor", "3366ff");
        Assert.assertFalse(bean.applyBackground(new Person()), "Foreign target refused");
    }

    /** A corrupt parked original is a message, not a 500 — and a flag-off apply refuses. */
    @Test
    public void rejectionPathsRefuseGracefully() {
        final PendingUploads.Pending garbage = pendingUploads.put("garbage".getBytes(),
                new PhotoProcessor.PreviewImage(new byte[] {1}, 10, 10, 10, 10),
                caller.personId().getValue(), "profile", subject.getId().getValue());
        session.put(ProfilePhotoCommands.TOKEN_KEY, garbage.token());
        Mockito.when(profilePhotos.nextFreeSlot(ArgumentMatchers.anyString())).thenReturn(1);
        Assert.assertFalse(bean.applyCrop(subject, null, null), "Corrupt original is rejected");

        bgFlagOn = false;
        Assert.assertFalse(bean.applyBackground(subject), "Flag off refuses apply");
        Assert.assertEquals(params.get("bgApplied"), Boolean.FALSE);
    }

    @Test
    public void bgRejectionAndStoreFailuresRefuseGracefully() {
        bgFlagOn = true;
        bgThrow = true;
        Mockito.when(profilePhotos.currentBytes(subject.getId().getValue(), 1))
                .thenReturn(java.util.Optional.of(PhotoFixtures.jpeg(512, 512)));
        bean.startBgRemoval(subject, 1);
        Assert.assertEquals(params.get("bgReady"), Boolean.FALSE, "A model rejection is a message");
        bgThrow = false;

        cannedCutout = "garbage".getBytes();
        bean.startBgRemoval(subject, 1);
        viewMap.put("bgPhotoColor", "#3366ff");
        Assert.assertFalse(bean.applyBackground(subject), "A garbage cutout must not store");

        cannedCutout = transparentLeftRedRight();
        bean.startBgRemoval(subject, 1);
        Mockito.when(profilePhotos.store(ArgumentMatchers.anyString(), ArgumentMatchers.eq(1),
                ArgumentMatchers.any())).thenReturn(false);
        Assert.assertFalse(bean.applyBackground(subject), "A failed store reports honestly");
    }

    @Test
    public void colorParsingIsStrict() {
        Assert.assertEquals(ProfilePhotoCommands.parseColor("#3366ff"), Integer.valueOf(0x3366ff));
        Assert.assertEquals(ProfilePhotoCommands.parseColor("A1B2C3"), Integer.valueOf(0xA1B2C3));
        Assert.assertNull(ProfilePhotoCommands.parseColor(null));
        Assert.assertNull(ProfilePhotoCommands.parseColor("#fff"));
        Assert.assertNull(ProfilePhotoCommands.parseColor("bluish"));
        Assert.assertNull(ProfilePhotoCommands.parseColor("#12345g"));
    }
}
