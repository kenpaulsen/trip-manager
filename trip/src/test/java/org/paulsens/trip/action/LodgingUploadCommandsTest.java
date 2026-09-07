package org.paulsens.trip.action;

import jakarta.faces.component.UIComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.LodgingViews.AccommodationForm;
import org.paulsens.trip.action.LodgingViews.RoomTypeForm;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.media.PhotoFixtures;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.CroppedImage;
import org.primefaces.model.file.UploadedFile;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The lodging upload bean: the dialog contract per kind, the media row it records, and the local store. */
public class LodgingUploadCommandsTest {

    private final Map<String, String> session = new HashMap<>();
    private final Map<String, Object> viewMap = new HashMap<>();
    private final List<Boolean> outcomes = new ArrayList<>();
    private Caller caller;
    private boolean rowSaves = true;
    private LodgingCommands lodging;
    private LodgingUploadCommands bean;
    private String accId;
    private String typeId;

    @BeforeMethod
    public void setUp() {
        FakeData.initFakeData();
        FakeData.addFakeData();
        session.clear();
        viewMap.clear();
        outcomes.clear();
        rowSaves = true;
        caller = TestCallers.siteAdmin();
        lodging = new LodgingCommands(() -> caller);
        final AccommodationForm form = new AccommodationForm();
        form.setName("Upload " + RandomData.genAlpha(6));
        accId = lodging.saveAccommodation(form, FakeData.CFPW_ORG_ID);
        Assert.assertFalse(accId.isEmpty());
        final RoomTypeForm type = new RoomTypeForm();
        type.setName("Double");
        Assert.assertTrue(lodging.saveRoomType(accId, type));
        typeId = lodging.roomTypeRows(accId).get(0).getId();

        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.isUploadEnabled()).thenReturn(false);
        Mockito.when(media.get(ArgumentMatchers.anyString()))
                .thenAnswer(call -> DAO.getInstance().getMedia(call.getArgument(0), Cached.NO).orElse(null));
        bean = new LodgingUploadCommands() {
            @Override
            protected Caller caller() {
                return caller;
            }

            @Override
            @SuppressWarnings("unchecked")
            protected <T> T viewMap(final String key) {
                return (T) viewMap.get(key);
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
            protected void publishOutcome(final boolean stored) {
                outcomes.add(stored);
            }

            @Override
            protected String contextPath() {
                return "/ctx";
            }

            @Override
            protected boolean saveRow(final MediaItem item) {
                return rowSaves && super.saveRow(item);
            }
        };
        bean.setCollaboratorsForTest(media, lodging);
        bean.setPendingUploadsForTest(new PendingUploads());
    }

    @Test
    public void slotsAndUrls() {
        Assert.assertEquals(LodgingUploadCommands.slotFor("acc-1"), "lodging-acc-1");
        Assert.assertTrue(LodgingUploadCommands.isLodgingSlot("lodging-acc-1"));
        Assert.assertFalse(LodgingUploadCommands.isLodgingSlot("tripChat-x"));
        Assert.assertFalse(LodgingUploadCommands.isLodgingSlot(null));
        Assert.assertEquals(bean.urlFor("lodging/a/b.jpg"), "/ctx/lodging-photos/lodging/a/b.jpg");
        Assert.assertEquals(bean.urlFor(null), "");
        Assert.assertTrue(bean.localGet("nope").isEmpty());
    }

    @Test
    public void theDialogHeaderFollowsTheKindAndUnknownKindsOrStrangersAreRefused() {
        Assert.assertEquals(bean.getDialogHeader(), "Accommodation photo", "nothing open yet");
        Assert.assertEquals(bean.getCropAspect(), "", "plans and photos crop free");
        bean.startUpload("floorMap", accId, "2");
        Assert.assertEquals(bean.getDialogHeader(), "Floor plan for floor 2");
        bean.startUpload("roomTypePhoto", accId, typeId);
        Assert.assertEquals(bean.getDialogHeader(), "Room type photo");
        bean.startUpload("mascot", accId, null);
        Assert.assertNull(session.get(LodgingUploadCommands.KIND_KEY), "an unknown kind opens nothing");
        caller = TestCallers.person(Person.Id.newInstance());
        bean.startUpload("accPhoto", accId, null);
        Assert.assertNull(session.get(LodgingUploadCommands.KIND_KEY), "a stranger opens nothing");
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(800, 600)));
        Assert.assertFalse(bean.isUploadPending());
        Assert.assertFalse(bean.applyCrop(null));
    }

    @Test
    public void aPropertyPhotoBecomesAMediaRowInTheSlotAndJoinsTheGallery() {
        bean.startUpload("accPhoto", accId, null);
        Assert.assertFalse(bean.isUploadPending());
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(1200, 900)));
        Assert.assertTrue(bean.isUploadPending());
        bean.confirmFullPhoto();
        Assert.assertEquals(outcomes, List.of(true));
        Assert.assertFalse(bean.isUploadPending(), "consumed");
        final Accommodation acc = lodging.findAccommodation(accId);
        Assert.assertEquals(acc.getPhotoIds().size(), 1);
        final MediaItem item = DAO.getInstance().getMedia(acc.getPhotoIds().get(0), Cached.NO).orElseThrow();
        Assert.assertEquals(item.getSlot(), "lodging-" + accId);
        Assert.assertNull(item.getOrgId(), "hotels are global: a site-level row");
        Assert.assertTrue(item.getS3Key().startsWith("lodging/" + accId + "/"));
        Assert.assertTrue(bean.localGet(item.getS3Key()).isPresent(), "served by the local store");
        Assert.assertEquals(lodging.galleryOf(accId).size(), 1);
        Assert.assertEquals(lodging.mediaUrl(item.getId()), "/lodging-photos/" + item.getS3Key());
        Assert.assertFalse(lodging.movePhoto(accId, null, item.getId(), 1), "already last");
    }

    @Test
    public void aRoomTypePhotoAndAFloorPlanLandWhereTheyBelong() {
        bean.startUpload("roomTypePhoto", accId, typeId);
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(1000, 700)));
        viewMap.put("photoCrop", new CroppedImage("x", new byte[0], 10, 10, 300, 200));
        bean.confirmCrop();
        Assert.assertEquals(outcomes, List.of(true));
        Assert.assertEquals(lodging.findAccommodation(accId).roomType(typeId).getPhotoIds().size(), 1);
        Assert.assertEquals(lodging.roomTypeGallery(accId, typeId).size(), 1);

        bean.startUpload("floorMap", accId, "1");
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(2400, 1600)));
        bean.confirmFullPhoto();
        Assert.assertEquals(outcomes, List.of(true, true));
        final String mediaId = lodging.floorMapMediaId(accId, "1");
        Assert.assertFalse(mediaId.isEmpty());
        Assert.assertEquals(DAO.getInstance().getMedia(mediaId, Cached.NO).orElseThrow().getTitle(), "Floor plan");
    }

    @Test
    public void expiredOrMismatchedUploadsAndBadFilesAreRefused() {
        bean.startUpload("accPhoto", accId, null);
        Assert.assertFalse(bean.applyCrop(null), "nothing parked");
        bean.handleUpload(uploadOf(new byte[] {1, 2, 3}));
        Assert.assertFalse(bean.isUploadPending(), "not an image");
        final UploadedFile empty = Mockito.mock(UploadedFile.class);
        Mockito.when(empty.getSize()).thenReturn(0L);
        bean.handleUpload(new FileUploadEvent(Mockito.mock(UIComponent.class), empty, 1));
        Assert.assertFalse(bean.isUploadPending());
        final UploadedFile huge = Mockito.mock(UploadedFile.class);
        Mockito.when(huge.getSize()).thenReturn(LodgingUploadCommands.MAX_UPLOAD_BYTES + 1);
        bean.handleUpload(new FileUploadEvent(Mockito.mock(UIComponent.class), huge, 1));
        Assert.assertFalse(bean.isUploadPending());

        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(800, 600)));
        Assert.assertTrue(bean.isUploadPending());
        // Reopening for another target abandons the parked upload: a token minted for one hotel cannot be
        // spent on another.
        session.put(LodgingUploadCommands.TARGET_KEY, "other");
        session.put(LodgingUploadCommands.KIND_KEY, "floorMap");
        Assert.assertFalse(bean.applyCrop(null));
        bean.cancelUpload();
        Assert.assertFalse(bean.isUploadPending());
    }

    @Test
    public void theLocalStoreEvictsOldestFirstAndS3FailuresAreReported() {
        bean.localStoreMaxBytesForTest(1L);
        bean.startUpload("accPhoto", accId, null);
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(800, 600)));
        bean.confirmFullPhoto();
        Assert.assertEquals(outcomes, List.of(true));
        final String first = DAO.getInstance().getMedia(lodging.findAccommodation(accId).getPhotoIds().get(0),
                Cached.NO).orElseThrow().getS3Key();
        bean.startUpload("accPhoto", accId, null);
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(800, 600)));
        bean.confirmFullPhoto();
        Assert.assertTrue(bean.localGet(first).isEmpty(), "evicted: the cap is one byte");

        final MediaCommands s3 = Mockito.mock(MediaCommands.class);
        Mockito.when(s3.isUploadEnabled()).thenReturn(true);
        Mockito.when(s3.putObject(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyBoolean())).thenReturn(false);
        Mockito.when(s3.publicUrl("k")).thenReturn("https://cdn/k");
        bean.setCollaboratorsForTest(s3, lodging);
        Assert.assertEquals(bean.urlFor("k"), "https://cdn/k");
        bean.startUpload("accPhoto", accId, null);
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(800, 600)));
        Assert.assertFalse(bean.applyCrop(null), "the bucket refused the object");
        Assert.assertTrue(bean.isUploadPending(), "...so the upload stays parked for a retry");
    }

    @Test
    public void anImageThatCannotFitTheBudgetOrARowThatWillNotSaveIsRefused() {
        bean.storedMaxBytesForTest(1L);
        bean.startUpload("accPhoto", accId, null);
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(800, 600)));
        Assert.assertFalse(bean.applyCrop(null), "no JPEG fits one byte: rejected, not stored");
        Assert.assertTrue(bean.isUploadPending());
        bean.storedMaxBytesForTest(LodgingUploadCommands.MAX_STORED_BYTES);
        rowSaves = false;
        Assert.assertFalse(bean.applyCrop(null), "the row could not be written");
        Assert.assertTrue(bean.isUploadPending());
        rowSaves = true;
        Assert.assertTrue(bean.applyCrop(null));
    }

    @Test
    public void aTargetThatNoLongerResolvesLeavesTheUploadParked() {
        bean.startUpload("roomTypePhoto", accId, "no-such-type");
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(800, 600)));
        Assert.assertFalse(bean.applyCrop(null), "the media row is written but the gallery link is refused");
        Assert.assertTrue(bean.isUploadPending());
    }

    @Test
    public void theSeamsAreHarmlessWithoutAFacesContext() {
        final LodgingUploadCommands bare = new LodgingUploadCommands();
        bare.publishOutcome(true);
        bare.sessionPut("k", "v");
        bare.sessionPut("k", null);
        Assert.assertNull(bare.sessionGet("k"));
        Assert.assertNull(bare.viewMap("photoCrop"));
        Assert.assertEquals(bare.contextPath(), "");
        Assert.assertNotNull(bare.caller());
        Assert.assertFalse(bare.isUploadPending());
    }

    private static FileUploadEvent uploadOf(final byte[] bytes) {
        final UploadedFile file = Mockito.mock(UploadedFile.class);
        Mockito.when(file.getContent()).thenReturn(bytes);
        Mockito.when(file.getSize()).thenReturn((long) bytes.length);
        Mockito.when(file.getFileName()).thenReturn("plan.jpg");
        return new FileUploadEvent(Mockito.mock(UIComponent.class), file, 1);
    }
}
