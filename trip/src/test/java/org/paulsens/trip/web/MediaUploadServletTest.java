package org.paulsens.trip.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.MediaCommands;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The wire contract of the docs-upload endpoint: every deny leg, both store outcomes, name hygiene. */
public class MediaUploadServletTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServletRequest req;
    private HttpServletResponse resp;
    private ByteArrayOutputStream out;
    private AtomicInteger status;
    private Caller caller;
    private MediaUploadServlet servlet;
    private MediaCommands media;

    @BeforeMethod
    public void setUp() throws IOException {
        req = Mockito.mock(HttpServletRequest.class);
        resp = Mockito.mock(HttpServletResponse.class);
        out = new ByteArrayOutputStream();
        status = new AtomicInteger(200);
        media = Mockito.mock(MediaCommands.class);
        caller = adminCaller();
        servlet = new MediaUploadServlet() {
            @Override
            protected Caller callerOf(final HttpServletRequest request) {
                return caller;
            }
        };
        servlet.setMedia(media);
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
        Mockito.doAnswer(inv -> {
            status.set(inv.getArgument(0));
            return null;
        }).when(resp).setStatus(ArgumentMatchers.anyInt());
        Mockito.when(req.getHeader(MediaUploadServlet.CSRF_HEADER)).thenReturn("1");
        Mockito.when(req.getParameter("slot")).thenReturn("home-docs");
        Mockito.when(req.getParameter("title")).thenReturn("A Title");
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
    }

    private static Caller adminCaller() {
        return new Caller(Person.Id.from("uploader"), true,
                new AuditActor("uploader@example.com", "uploader"), new PrivilegeCommands());
    }

    private JsonNode post() throws IOException {
        servlet.doPost(req, resp);
        return MAPPER.readTree(out.toByteArray());
    }

    private Part part(final String name, final long size) throws IOException {
        final Part part = Mockito.mock(Part.class);
        Mockito.when(part.getSize()).thenReturn(size);
        Mockito.when(part.getSubmittedFileName()).thenReturn(name);
        Mockito.when(part.getContentType()).thenReturn("application/pdf");
        Mockito.when(part.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[] {1}));
        return part;
    }

    @Test
    public void missingCsrfHeaderIs403() throws Exception {
        Mockito.when(req.getHeader(MediaUploadServlet.CSRF_HEADER)).thenReturn(null);
        Assert.assertEquals(post().get("error").asText(), "csrf");
        Assert.assertEquals(status.get(), 403);
    }

    @Test
    public void anonymousIs401() throws Exception {
        caller = new Caller(null, false, new AuditActor(null, null), new PrivilegeCommands());
        Assert.assertEquals(post().get("error").asText(), "not_authenticated");
        Assert.assertEquals(status.get(), 401);
    }

    @Test
    public void nonMediaAdminIs403() throws Exception {
        caller = new Caller(Person.Id.from("pleb"), false, new AuditActor("p@e.com", "pleb"),
                new PrivilegeCommands());
        Assert.assertEquals(post().get("error").asText(), "forbidden");
        Assert.assertEquals(status.get(), 403);
    }

    @Test
    public void unknownSlotIs400() throws Exception {
        Mockito.when(req.getParameter("slot")).thenReturn("profile");
        Assert.assertEquals(post().get("error").asText(), "bad_slot");
        Assert.assertEquals(status.get(), 400);
        Mockito.when(req.getParameter("slot")).thenReturn(null);
        out.reset();
        Assert.assertEquals(post().get("error").asText(), "bad_slot");
    }

    @Test
    public void missingOrEmptyPartIs400() throws Exception {
        Mockito.when(req.getPart("file")).thenReturn(null);
        Assert.assertEquals(post().get("error").asText(), "no_file");
        final Part empty = part("x.pdf", 0);
        Mockito.when(req.getPart("file")).thenReturn(empty);
        out.reset();
        Assert.assertEquals(post().get("error").asText(), "no_file");
    }

    @Test
    public void oversizePartIs413() throws Exception {
        Mockito.when(req.getPart("file")).thenThrow(new IllegalStateException("too big"));
        Assert.assertEquals(post().get("error").asText(), "too_large");
        Assert.assertEquals(status.get(), 413);
    }

    @Test
    public void unreadableMultipartIs400() throws Exception {
        Mockito.when(req.getPart("file")).thenThrow(new jakarta.servlet.ServletException("bad body"));
        Assert.assertEquals(post().get("error").asText(), "bad_request");
        Assert.assertEquals(status.get(), 400);
    }

    @Test
    public void uploadExceptionIs503() throws Exception {
        final Part fine = part("guide.pdf", 5);
        Mockito.when(req.getPart("file")).thenReturn(fine);
        Mockito.when(media.upload(ArgumentMatchers.anyString(), ArgumentMatchers.any(InputStream.class),
                        ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.anyInt(),
                        ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("s3 down"));
        Assert.assertEquals(post().get("error").asText(), "store");
        Assert.assertEquals(status.get(), 503);
    }

    @Test
    public void productionCallerResolvesFromTheSession() {
        // The default callerOf (overridden everywhere else here): a null session is an unauthenticated caller.
        Mockito.when(req.getSession(false)).thenReturn(null);
        final Caller resolved = new MediaUploadServlet().callerOf(req);
        Assert.assertFalse(resolved.isAuthenticated());
    }

    @Test
    public void junkFileNameIs400() throws Exception {
        final Part junk = part("....", 5);
        Mockito.when(req.getPart("file")).thenReturn(junk);
        Assert.assertEquals(post().get("error").asText(), "bad_name");
        Assert.assertEquals(status.get(), 400);
    }

    @Test
    public void noBucketIs503() throws Exception {
        Mockito.when(media.isUploadEnabled()).thenReturn(false);
        final Part fine = part("guide.pdf", 5);
        Mockito.when(req.getPart("file")).thenReturn(fine);
        Assert.assertEquals(post().get("error").asText(), "no_bucket");
        Assert.assertEquals(status.get(), 503);
    }

    @Test
    public void storeFailureIs503() throws Exception {
        final Part fine = part("guide.pdf", 5);
        Mockito.when(req.getPart("file")).thenReturn(fine);
        Mockito.when(media.upload(ArgumentMatchers.anyString(), ArgumentMatchers.any(InputStream.class),
                        ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.anyInt(),
                        ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(false);
        Assert.assertEquals(post().get("error").asText(), "store");
        Assert.assertEquals(status.get(), 503);
    }

    @Test
    public void successReturnsTheStoredKey() throws Exception {
        final Part fine = part("Travel Guide 2026.pdf", 5);
        Mockito.when(req.getPart("file")).thenReturn(fine);
        Mockito.when(media.upload(ArgumentMatchers.eq("downloads/Travel-Guide-2026.pdf"),
                        ArgumentMatchers.any(InputStream.class), ArgumentMatchers.eq(5L),
                        ArgumentMatchers.eq("application/pdf"), ArgumentMatchers.eq("A Title"),
                        ArgumentMatchers.any(), ArgumentMatchers.eq("home-docs"), ArgumentMatchers.eq(0),
                        ArgumentMatchers.eq("uploader@example.com"), ArgumentMatchers.isNull()))
                .thenReturn(true);
        final JsonNode body = post();
        Assert.assertEquals(status.get(), 200);
        Assert.assertEquals(body.get("key").asText(), "downloads/Travel-Guide-2026.pdf");
        Assert.assertEquals(body.get("slot").asText(), "home-docs");
    }

    /** On an organization's host the stored key is the org's namespace, and the response names it. */
    @Test
    public void onAnOrgHostTheKeyIsNamespacedUnderTheOrg() throws Exception {
        org.paulsens.trip.dynamo.DAO.getInstance();
        org.paulsens.trip.dynamo.FakeData.addFakeData();
        final String expected = MediaCommands.ORG_KEY_PREFIX + org.paulsens.trip.dynamo.FakeData.ACME_ORG_ID
                + "/downloads/guide.pdf";
        final Part fine = part("guide.pdf", 5);
        Mockito.when(req.getPart("file")).thenReturn(fine);
        Mockito.when(media.upload(ArgumentMatchers.eq(expected), ArgumentMatchers.any(InputStream.class),
                        ArgumentMatchers.eq(5L), ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.any(), ArgumentMatchers.eq("home-docs"), ArgumentMatchers.eq(0),
                        ArgumentMatchers.any(), ArgumentMatchers.isNull()))
                .thenReturn(true);
        final org.paulsens.trip.site.SiteContext acme = org.paulsens.trip.site.SiteContext.org(
                org.paulsens.trip.model.Organization.Id.from(org.paulsens.trip.dynamo.FakeData.ACME_ORG_ID),
                "acme", "acme.localhost");

        final JsonNode body = ScopedValue.where(org.paulsens.trip.audit.RequestContext.SCOPE,
                org.paulsens.trip.audit.RequestContext.of(null, null, acme)).call(this::post);

        Assert.assertEquals(status.get(), 200);
        Assert.assertEquals(body.get("key").asText(), expected);
    }

    @Test
    public void safeFileNameHygiene() {
        Assert.assertEquals(MediaUploadServlet.safeFileName("My Guide (v2).pdf"), "My-Guide-v2.pdf");
        Assert.assertEquals(MediaUploadServlet.safeFileName("C:\\Users\\me\\evil.pdf"), "evil.pdf");
        Assert.assertEquals(MediaUploadServlet.safeFileName("/etc/passwd"), "passwd");
        Assert.assertNull(MediaUploadServlet.safeFileName("../.."), "traversal reduces to nothing safe");
        Assert.assertNull(MediaUploadServlet.safeFileName("   "));
        Assert.assertNull(MediaUploadServlet.safeFileName(null));
        Assert.assertNull(MediaUploadServlet.safeFileName("()[]{}"));
    }
}
