package org.paulsens.trip.action;

import jakarta.faces.component.UIComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.media.PhotoFixtures;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatSettings;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.CroppedImage;
import org.primefaces.model.file.UploadedFile;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** Chat's side of the shared upload dialog: gates, the rate limit, crop scaling, and the tray hand-off. */
public class ChatPhotoUploadTest {

    private record Published(boolean stored, ChatPhotos.StagedPhoto staged, String base) {
    }

    private final Map<String, String> session = new HashMap<>();
    private final Map<String, Object> viewMap = new HashMap<>();
    private final List<Published> published = new ArrayList<>();
    private Caller caller;
    private String tripId;
    private String denial;
    private long maxBytes;
    private ChatPhotos chatPhotos;
    private PendingUploads pendingUploads;
    private ChatPhotoUpload bean;

    @BeforeMethod
    public void setUp() {
        session.clear();
        viewMap.clear();
        published.clear();
        caller = new Caller(Person.Id.from("me"), false, AuditActor.from(null), new PrivilegeCommands());
        tripId = "trip-1";
        denial = null;
        maxBytes = 10L * 1024 * 1024;
        chatPhotos = Mockito.mock(ChatPhotos.class);
        pendingUploads = new PendingUploads();
        bean = new ChatPhotoUpload() {
            @Override
            protected Caller caller() {
                return caller;
            }

            @Override
            protected String tripId() {
                return tripId;
            }

            @Override
            protected ChatCommands.AttachGate checkAttach(final String trip, final Person.Id me) {
                final ChatSettings settings = Mockito.mock(ChatSettings.class);
                Mockito.when(settings.getMaxAttachmentBytes()).thenReturn(maxBytes);
                final ChatChannel channel = Mockito.mock(ChatChannel.class);
                Mockito.when(channel.getSettings()).thenReturn(settings);
                return new ChatCommands.AttachGate(channel, denial);
            }

            @Override
            protected ChatPhotos chatPhotos() {
                return chatPhotos;
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
            protected void publishOutcome(final boolean stored, final ChatPhotos.StagedPhoto staged,
                    final String publicBase) {
                published.add(new Published(stored, staged, publicBase));
            }
        };
        bean.setPendingUploadsForTest(pendingUploads);
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

    private static ChatPhotos.StagedPhoto staged() {
        return new ChatPhotos.StagedPhoto("chat/trip-1/x.jpg", "chat/trip-1/x-small.jpg",
                "image/jpeg", 5, 500, 500);
    }

    @Test
    public void aMemberUploadsAndTheTokenParks() {
        uploadFixture();
        Assert.assertTrue(bean.isUploadPending());
    }

    @Test
    public void gatesRefuse() {
        caller = new Caller(null, false, AuditActor.from(null), new PrivilegeCommands());
        uploadFixture();
        Assert.assertFalse(bean.isUploadPending(), "Anonymous");

        caller = new Caller(Person.Id.from("me"), false, AuditActor.from(null), new PrivilegeCommands());
        tripId = null;
        uploadFixture();
        tripId = "trip-1";
        Assert.assertFalse(bean.isUploadPending(), "No trip context");

        denial = "Photos are turned off for this chat.";
        uploadFixture();
        Assert.assertFalse(bean.isUploadPending(), "The chat gate's denial holds");
        denial = null;

        maxBytes = 10;
        uploadFixture();
        Assert.assertFalse(bean.isUploadPending(), "The channel's byte cap holds");
        maxBytes = 10L * 1024 * 1024;

        bean.handleUpload(uploadOf("not an image".getBytes()));
        Assert.assertFalse(bean.isUploadPending(), "Garbage is rejected");
    }

    @Test
    public void theRateLimitStopsALoop() {
        for (int i = 0; i < ChatPhotoUpload.UPLOADS_PER_WINDOW; i++) {
            uploadFixture();
            bean.cancelUpload();
        }
        uploadFixture();
        Assert.assertFalse(bean.isUploadPending(), "Upload " + ChatPhotoUpload.UPLOADS_PER_WINDOW
                + " must be rate limited");
    }

    @Test
    public void confirmCropScalesPreviewCoordsAndPublishesTheStagedPhoto() {
        uploadFixture();
        Mockito.when(chatPhotos.stage(ArgumentMatchers.eq("trip-1"), ArgumentMatchers.eq(Person.Id.from("me")),
                ArgumentMatchers.any(byte[].class), ArgumentMatchers.any())).thenReturn(staged());
        Mockito.when(chatPhotos.getPhotoPageBase()).thenReturn("/chat-photos/");

        // Preview is 800x640 (1000x800 capped): preview-space 400 square maps to 500 at full resolution.
        viewMap.put("photoCrop", new CroppedImage(null, null, 0, 0, 400, 400));
        bean.confirmCrop();

        final ArgumentCaptor<PhotoProcessor.CropRect> rect =
                ArgumentCaptor.forClass(PhotoProcessor.CropRect.class);
        Mockito.verify(chatPhotos).stage(ArgumentMatchers.eq("trip-1"),
                ArgumentMatchers.eq(Person.Id.from("me")), ArgumentMatchers.any(byte[].class),
                rect.capture());
        Assert.assertEquals(rect.getValue(), new PhotoProcessor.CropRect(0, 0, 500, 500));
        Assert.assertEquals(published.size(), 1);
        Assert.assertTrue(published.get(0).stored());
        Assert.assertEquals(published.get(0).staged().key(), "chat/trip-1/x.jpg");
        Assert.assertFalse(bean.isUploadPending(), "The staged token is consumed");
    }

    @Test
    public void useFullPhotoStagesUncropped() {
        uploadFixture();
        Mockito.when(chatPhotos.stage(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.any(byte[].class), ArgumentMatchers.isNull())).thenReturn(staged());
        bean.confirmFullPhoto();
        Assert.assertEquals(published, List.of(new Published(true, staged(), null)));
    }

    @Test
    public void confirmWithoutAnUploadPublishesFailure() {
        bean.confirmFullPhoto();
        Assert.assertEquals(published, List.of(new Published(false, null, null)));
    }

    @Test
    public void aProfileTokenCannotBecomeAChatPhoto() {
        // Parked for another purpose (the profile dialog) under the same session key.
        final PendingUploads.Pending foreign = pendingUploads.put(PhotoFixtures.jpeg(100, 100),
                new PhotoProcessor.PreviewImage(new byte[] {1}, 10, 10, 10, 10), "me", "profile", "p1");
        session.put(PendingUploads.SESSION_TOKEN_KEY, foreign.token());
        bean.confirmFullPhoto();
        Assert.assertEquals(published, List.of(new Published(false, null, null)));
        Mockito.verifyNoInteractions(chatPhotos);
    }

    @Test
    public void stagingFailuresPublishFailureAndKeepTheUpload() {
        uploadFixture();
        Mockito.when(chatPhotos.stage(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.any(byte[].class), ArgumentMatchers.isNull()))
                .thenThrow(new PhotoRejectedException("Not an image."));
        bean.confirmFullPhoto();
        Assert.assertEquals(published, List.of(new Published(false, null, null)));

        published.clear();
        Mockito.reset(chatPhotos);
        Mockito.when(chatPhotos.stage(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.any(byte[].class), ArgumentMatchers.isNull()))
                .thenThrow(new IllegalStateException("s3 down"));
        bean.confirmFullPhoto();
        Assert.assertEquals(published, List.of(new Published(false, null, null)));
        Assert.assertTrue(bean.isUploadPending(), "A store failure must not eat the upload");
    }

    @Test
    public void cancelDropsTheParkedBytes() {
        uploadFixture();
        bean.cancelUpload();
        Assert.assertFalse(bean.isUploadPending());
        bean.cancelUpload();
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
            Mockito.when(ctx.getExternalContext().getRequestContextPath()).thenReturn("");
            final Map<String, Object> view = new HashMap<>();
            view.put("theTripId", "trip-x");
            Mockito.when(ctx.getViewRoot().getViewMap()).thenReturn(view);
            final org.primefaces.PrimeFaces pf =
                    Mockito.mock(org.primefaces.PrimeFaces.class, Mockito.RETURNS_DEEP_STUBS);
            pfs.when(org.primefaces.PrimeFaces::current).thenReturn(pf);
            Mockito.when(pf.isAjaxRequest()).thenReturn(true);

            final ChatPhotoUpload bare = new ChatPhotoUpload();
            Assert.assertEquals(bare.tripId(), "trip-x");
            bare.sessionPut("k", "v");
            Assert.assertEquals(sess.get("k"), "v");
            Assert.assertEquals(bare.sessionGet("k"), "v");
            bare.sessionPut("k", null);
            Assert.assertFalse(sess.containsKey("k"));

            bare.publishOutcome(true, staged(), null);
            Mockito.verify(pf.ajax()).addCallbackParam("photoKey", "chat/trip-1/x.jpg");
            Mockito.verify(pf.ajax()).addCallbackParam("photoUrl", "/chat-photos/chat/trip-1/x.jpg");
            bare.publishOutcome(true, staged(), "https://cdn.example/");
            Mockito.verify(pf.ajax()).addCallbackParam("photoUrl", "https://cdn.example/chat/trip-1/x.jpg");
            bare.publishOutcome(false, null, null);
            Mockito.when(pf.isAjaxRequest()).thenReturn(false);
            bare.publishOutcome(true, staged(), null);
        }
    }

    @Test
    public void anEmptyUploadIsRefused() {
        final UploadedFile empty = Mockito.mock(UploadedFile.class);
        Mockito.when(empty.getSize()).thenReturn(0L);
        bean.handleUpload(new FileUploadEvent(Mockito.mock(UIComponent.class), empty, 1));
        Assert.assertFalse(bean.isUploadPending());
    }

    /** The real (un-overridden) seams must answer safely when there is no FacesContext at all. */
    @Test
    public void theRealSeamsFailClosedWithoutAFacesContext() {
        final ChatPhotoUpload bare = new ChatPhotoUpload();
        bare.handleUpload(uploadOf(PhotoFixtures.jpeg(100, 100)));
        Assert.assertFalse(bare.isUploadPending());
        bare.confirmCrop();
        bare.confirmFullPhoto();
        bare.cancelUpload();
        bare.publishOutcome(true, staged(), null);
        Assert.assertNotNull(bare.chatPhotos(), "The shared ChatPhotos instance is the real collaborator");
        // The real gate answers a denial for a trip that does not exist rather than throwing.
        Assert.assertNotNull(bare.checkAttach("no-such-trip", Person.Id.from("nobody")).denial());
    }
}
