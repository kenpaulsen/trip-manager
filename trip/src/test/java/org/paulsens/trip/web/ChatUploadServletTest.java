package org.paulsens.trip.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.action.ChatCommands;
import org.paulsens.trip.action.ChatPhotos;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatSettings;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * HTTP-shape tests for the upload endpoint: every deny leg, both store outcomes, and the local-mode GET.
 * The beans behind it are static-mocked (their behavior is covered by their own suites); what this proves is
 * the wire contract the composer's XHR depends on.
 */
public class ChatUploadServletTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatUploadServlet servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private HttpSession session;
    private ByteArrayOutputStream out;
    private AtomicInteger status;

    @BeforeMethod
    public void setUp() throws IOException {
        servlet = new ChatUploadServlet();
        req = Mockito.mock(HttpServletRequest.class);
        resp = Mockito.mock(HttpServletResponse.class);
        session = Mockito.mock(HttpSession.class);
        out = new ByteArrayOutputStream();
        status = new AtomicInteger(200);
        final ServletOutputStream sink = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(final WriteListener listener) {
            }

            @Override
            public void write(final int b) {
                out.write(b);
            }
        };
        Mockito.when(resp.getOutputStream()).thenReturn(sink);
        Mockito.doAnswer(inv -> statusTo(inv.getArgument(0))).when(resp).setStatus(ArgumentMatchers.anyInt());
        Mockito.doAnswer(inv -> statusTo(inv.getArgument(0))).when(resp).sendError(ArgumentMatchers.anyInt());
        Mockito.when(req.getContextPath()).thenReturn("");
    }

    private Object statusTo(final Integer value) {
        status.set(value);
        return null;
    }

    private void signIn() {
        Mockito.when(req.getHeader(ChatCommands.CSRF_HEADER)).thenReturn("1");
        Mockito.when(req.getSession(false)).thenReturn(session);
        Mockito.when(session.getAttribute(PersonCommands.ACTIVE_USER_ID))
                .thenReturn(Person.Id.from("uploader-1"));
        Mockito.when(req.getParameter("trip")).thenReturn("trip-1");
    }

    private static ChatChannel channelWithDefaults() {
        return new ChatChannel(ChatChannel.Id.forTrip("trip-1"), "trip-1", ChatChannel.Kind.TRIP,
                "Trip chat", null, null, ChatSettings.defaults(), Instant.now(), null, null, null);
    }

    private static Part partOf(final byte[] bytes) throws IOException {
        final Part part = Mockito.mock(Part.class);
        Mockito.when(part.getSize()).thenReturn((long) bytes.length);
        Mockito.when(part.getInputStream()).thenReturn(new ByteArrayInputStream(bytes));
        return part;
    }

    private JsonNode body() throws IOException {
        return MAPPER.readTree(out.toByteArray());
    }

    @Test
    public void aRequestWithoutTheCsrfHeaderIsRefused() throws Exception {
        servlet.doPost(req, resp);
        Assert.assertEquals(status.get(), 403);
        Assert.assertEquals(body().get("error").asText(), "csrf");
    }

    @Test
    public void anAnonymousRequestIsRefused() throws Exception {
        Mockito.when(req.getHeader(ChatCommands.CSRF_HEADER)).thenReturn("1");
        servlet.doPost(req, resp);
        Assert.assertEquals(status.get(), 401);
    }

    @Test
    public void aPathShapedTripIdIsRefusedBeforeAnythingElse() throws Exception {
        Mockito.when(req.getHeader(ChatCommands.CSRF_HEADER)).thenReturn("1");
        Mockito.when(req.getSession(false)).thenReturn(session);
        Mockito.when(session.getAttribute(PersonCommands.ACTIVE_USER_ID)).thenReturn("uploader-1");
        Mockito.when(req.getParameter("trip")).thenReturn("../not/a/trip");
        servlet.doPost(req, resp);
        Assert.assertEquals(status.get(), 400);
        Assert.assertEquals(body().get("error").asText(), "bad_trip");
    }

    @Test
    public void theChatGateDenialComesBackVerbatim() throws Exception {
        signIn();
        try (MockedStatic<ChatCommands> statics = Mockito.mockStatic(ChatCommands.class)) {
            final ChatCommands chat = Mockito.mock(ChatCommands.class);
            statics.when(ChatCommands::getChatCommands).thenReturn(chat);
            Mockito.when(chat.checkAttach(ArgumentMatchers.eq("trip-1"), ArgumentMatchers.any()))
                    .thenReturn(new ChatCommands.AttachGate(null, "You are muted and cannot post right now."));

            servlet.doPost(req, resp);
        }
        Assert.assertEquals(status.get(), 403);
        Assert.assertTrue(body().get("message").asText().contains("muted"));
    }

    @Test
    public void aMissingPartIsABadRequest() throws Exception {
        signIn();
        try (MockedStatic<ChatCommands> statics = allowGate()) {
            Mockito.when(req.getPart("photo")).thenReturn(null);
            servlet.doPost(req, resp);
        }
        Assert.assertEquals(status.get(), 400);
        Assert.assertEquals(body().get("error").asText(), "no_photo");
    }

    @Test
    public void anOversizedPhotoGetsAFriendly413WithTheCap() throws Exception {
        signIn();
        try (MockedStatic<ChatCommands> statics = allowGate()) {
            final Part part = Mockito.mock(Part.class);
            Mockito.when(part.getSize()).thenReturn(20L * 1024 * 1024);
            Mockito.when(req.getPart("photo")).thenReturn(part);
            servlet.doPost(req, resp);
        }
        Assert.assertEquals(status.get(), 413);
        Assert.assertTrue(body().get("message").asText().contains("10 MB"), body().toString());
    }

    @Test
    public void theContainersOwnLimitAlsoBecomesA413() throws Exception {
        signIn();
        try (MockedStatic<ChatCommands> statics = allowGate()) {
            Mockito.when(req.getPart("photo")).thenThrow(new IllegalStateException("too big"));
            servlet.doPost(req, resp);
        }
        Assert.assertEquals(status.get(), 413);
    }

    @Test
    public void malformedMultipartIsABadRequest() throws Exception {
        signIn();
        try (MockedStatic<ChatCommands> statics = allowGate()) {
            Mockito.when(req.getPart("photo")).thenThrow(new ServletException("bad multipart"));
            servlet.doPost(req, resp);
        }
        Assert.assertEquals(status.get(), 400);
    }

    @Test
    public void aStagedPhotoComesBackWithLocalUrls() throws Exception {
        signIn();
        try (MockedStatic<ChatCommands> statics = allowGate();
                MockedStatic<ChatPhotos> photoStatics = Mockito.mockStatic(ChatPhotos.class)) {
            final ChatPhotos photos = Mockito.mock(ChatPhotos.class);
            photoStatics.when(ChatPhotos::getChatPhotos).thenReturn(photos);
            Mockito.when(photos.stage(ArgumentMatchers.eq("trip-1"), ArgumentMatchers.any(),
                    ArgumentMatchers.any(byte[].class))).thenReturn(new ChatPhotos.StagedPhoto(
                            "chat/trip-1/x.jpg", "chat/trip-1/x-small.jpg", "image/jpeg", 5, 1600, 900));
            Mockito.when(photos.getPublicBase()).thenReturn(null);
            final Part part = partOf(new byte[] {1, 2, 3});
            Mockito.when(req.getPart("photo")).thenReturn(part);

            servlet.doPost(req, resp);
        }
        Assert.assertEquals(status.get(), 200);
        final JsonNode json = body();
        Assert.assertEquals(json.get("key").asText(), "chat/trip-1/x.jpg");
        Assert.assertEquals(json.get("smallUrl").asText(), "/chat-photos/chat/trip-1/x-small.jpg");
        Assert.assertEquals(json.get("width").asInt(), 1600);
    }

    @Test
    public void aStagedPhotoComesBackWithCdnUrlsWhenConfigured() throws Exception {
        signIn();
        try (MockedStatic<ChatCommands> statics = allowGate();
                MockedStatic<ChatPhotos> photoStatics = Mockito.mockStatic(ChatPhotos.class)) {
            final ChatPhotos photos = Mockito.mock(ChatPhotos.class);
            photoStatics.when(ChatPhotos::getChatPhotos).thenReturn(photos);
            Mockito.when(photos.stage(ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(byte[].class))).thenReturn(new ChatPhotos.StagedPhoto(
                            "chat/trip-1/x.jpg", "chat/trip-1/x.jpg", "image/jpeg", 5, 400, 300));
            Mockito.when(photos.getPublicBase()).thenReturn("https://cdn.example.com/");
            final Part part = partOf(new byte[] {1});
            Mockito.when(req.getPart("photo")).thenReturn(part);

            servlet.doPost(req, resp);
        }
        Assert.assertEquals(body().get("url").asText(), "https://cdn.example.com/chat/trip-1/x.jpg");
    }

    @Test
    public void aRejectedPhotoIs422WithTheReason() throws Exception {
        signIn();
        try (MockedStatic<ChatCommands> statics = allowGate();
                MockedStatic<ChatPhotos> photoStatics = Mockito.mockStatic(ChatPhotos.class)) {
            final ChatPhotos photos = Mockito.mock(ChatPhotos.class);
            photoStatics.when(ChatPhotos::getChatPhotos).thenReturn(photos);
            Mockito.when(photos.stage(ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(byte[].class)))
                    .thenThrow(new PhotoRejectedException("Not an image."));
            final Part part = partOf(new byte[] {1});
            Mockito.when(req.getPart("photo")).thenReturn(part);

            servlet.doPost(req, resp);
        }
        Assert.assertEquals(status.get(), 422);
        Assert.assertEquals(body().get("message").asText(), "Not an image.");
    }

    @Test
    public void aStoreFailureIs503() throws Exception {
        signIn();
        try (MockedStatic<ChatCommands> statics = allowGate();
                MockedStatic<ChatPhotos> photoStatics = Mockito.mockStatic(ChatPhotos.class)) {
            final ChatPhotos photos = Mockito.mock(ChatPhotos.class);
            photoStatics.when(ChatPhotos::getChatPhotos).thenReturn(photos);
            Mockito.when(photos.stage(ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(byte[].class))).thenThrow(new IllegalStateException("s3 down"));
            final Part part = partOf(new byte[] {1});
            Mockito.when(req.getPart("photo")).thenReturn(part);

            servlet.doPost(req, resp);
        }
        Assert.assertEquals(status.get(), 503);
        Assert.assertEquals(body().get("error").asText(), "store");
    }

    @Test
    public void uploadsAreRateLimitedPerPerson() throws Exception {
        signIn();
        try (MockedStatic<ChatCommands> statics = allowGate();
                MockedStatic<ChatPhotos> photoStatics = Mockito.mockStatic(ChatPhotos.class)) {
            final ChatPhotos photos = Mockito.mock(ChatPhotos.class);
            photoStatics.when(ChatPhotos::getChatPhotos).thenReturn(photos);
            Mockito.when(photos.stage(ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(byte[].class))).thenReturn(new ChatPhotos.StagedPhoto(
                            "chat/trip-1/x.jpg", "chat/trip-1/x.jpg", "image/jpeg", 5, 100, 100));
            Mockito.when(photos.getPublicBase()).thenReturn(null);
            for (int i = 0; i < ChatUploadServlet.UPLOADS_PER_WINDOW; i++) {
                final Part part = partOf(new byte[] {1});
                Mockito.when(req.getPart("photo")).thenReturn(part);
                out.reset();
                servlet.doPost(req, resp);
                Assert.assertEquals(status.get(), 200, "upload " + i + " should be allowed");
            }
            out.reset();
            servlet.doPost(req, resp);
        }
        Assert.assertEquals(status.get(), 429);
        Assert.assertEquals(body().get("error").asText(), "rate_limited");
    }

    @Test
    public void localGetServesOnlyChatKeysAndOnlyKnownOnes() throws Exception {
        try (MockedStatic<ChatPhotos> photoStatics = Mockito.mockStatic(ChatPhotos.class)) {
            final ChatPhotos photos = Mockito.mock(ChatPhotos.class);
            photoStatics.when(ChatPhotos::getChatPhotos).thenReturn(photos);
            Mockito.when(photos.localGet("chat/trip-1/x.jpg")).thenReturn(
                    Optional.of(new ChatPhotos.ServedPhoto(new byte[] {9, 9}, "image/jpeg")));

            Mockito.when(req.getPathInfo()).thenReturn(null);
            servlet.doGet(req, resp);
            Assert.assertEquals(status.get(), 404, "no path");

            Mockito.when(req.getPathInfo()).thenReturn("/etc/passwd");
            servlet.doGet(req, resp);
            Assert.assertEquals(status.get(), 404, "outside the chat prefix");

            Mockito.when(req.getPathInfo()).thenReturn("/chat/../secret");
            servlet.doGet(req, resp);
            Assert.assertEquals(status.get(), 404, "traversal");

            Mockito.when(photos.localGet("chat/trip-1/missing.jpg")).thenReturn(Optional.empty());
            Mockito.when(req.getPathInfo()).thenReturn("/chat/trip-1/missing.jpg");
            servlet.doGet(req, resp);
            Assert.assertEquals(status.get(), 404, "unknown key");

            status.set(200);
            Mockito.when(req.getPathInfo()).thenReturn("/chat/trip-1/x.jpg");
            servlet.doGet(req, resp);
            Assert.assertEquals(status.get(), 200);
            Assert.assertEquals(out.toByteArray(), new byte[] {9, 9});
            Mockito.verify(resp).setContentType("image/jpeg");
        }
    }

    /** A gate that says yes, with default settings for the caps. */
    private static MockedStatic<ChatCommands> allowGate() {
        final MockedStatic<ChatCommands> statics = Mockito.mockStatic(ChatCommands.class);
        final ChatCommands chat = Mockito.mock(ChatCommands.class);
        statics.when(ChatCommands::getChatCommands).thenReturn(chat);
        Mockito.when(chat.checkAttach(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new ChatCommands.AttachGate(channelWithDefaults(), null));
        return statics;
    }
}
