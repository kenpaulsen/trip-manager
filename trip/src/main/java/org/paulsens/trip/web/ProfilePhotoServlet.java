package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.ProfilePhotoCommands;
import org.paulsens.trip.action.ProfilePhotos;
import org.paulsens.trip.api.Beans;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.model.Person;

/**
 * GET-only serving for profile photos. Uploads do NOT come through here — they ride the JSF dialog's own
 * multipart form into {@code ProfilePhotoCommands} — so this servlet has no POST, no CSRF header, and no
 * multipart-config. Two paths:
 *
 * <ul>
 * <li>{@code /profile-photos/profilePics/...} — local-mode photo bytes (in production the CDN serves these
 *     and this path 404s, exactly like the chat-photo servlet's GET). Public-by-URL on purpose: the
 *     production CDN serves them to anyone holding the URL, and local mode must behave like production.</li>
 * <li>{@code /profile-photos/preview/{token}} — a pending upload's preview JPEG, in BOTH modes. This is the
 *     crop dialog's fallback image source (and the webtest's window into it); it requires the session that
 *     staged the upload, because a preview is pre-publication content nobody else should be able to pull.</li>
 * </ul>
 */
public class ProfilePhotoServlet extends HttpServlet {

    static final String PREVIEW_PATH = "/preview/";

    private ProfilePhotos profilePhotos;
    private PendingUploads pendingUploads = PendingUploads.getPendingUploads();

    void setProfilePhotos(final ProfilePhotos profilePhotos) {
        this.profilePhotos = profilePhotos;
    }

    void setPendingUploads(final PendingUploads pendingUploads) {
        this.pendingUploads = pendingUploads;
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final String path = req.getPathInfo();
        if (path == null || path.contains("..")) {
            resp.sendError(404);
            return;
        }
        if (path.startsWith(PREVIEW_PATH)) {
            servePreview(req, resp, path.substring(PREVIEW_PATH.length()));
            return;
        }
        final String key = path.startsWith("/") ? path.substring(1) : path;
        if (!key.startsWith("profilePics/")) {
            resp.sendError(404);
            return;
        }
        final Optional<byte[]> bytes = photos().localGet(key);
        if (bytes.isEmpty()) {
            resp.sendError(404);
            return;
        }
        serve(resp, bytes.get(), "image/jpeg", "private, max-age=3600");
    }

    private void servePreview(final HttpServletRequest req, final HttpServletResponse resp,
            final String token) throws IOException {
        final Person.Id me = signedInPerson(req);
        if (me == null) {
            resp.sendError(401);
            return;
        }
        final Optional<PendingUploads.Pending> pending = pendingUploads.peek(token, me.getValue());
        if (pending.isEmpty()) {
            resp.sendError(404);
            return;
        }
        // Background-removal cutouts are RGBA PNGs (the alpha IS the result); everything else is JPEG.
        final String type = ProfilePhotoCommands.BG_PURPOSE.equals(pending.get().purpose())
                ? "image/png" : "image/jpeg";
        serve(resp, pending.get().previewJpeg(), type, "private, no-store");
    }

    private static void serve(final HttpServletResponse resp, final byte[] bytes, final String contentType,
            final String cacheControl) throws IOException {
        resp.setContentType(contentType);
        resp.setContentLength(bytes.length);
        resp.setHeader("Cache-Control", cacheControl);
        resp.getOutputStream().write(bytes);
    }

    private ProfilePhotos photos() {
        ProfilePhotos photos = profilePhotos;
        if (photos == null) {
            // Resolved lazily so tests can inject; Beans.get selects with Any (the @FacesConfig trap).
            photos = Beans.get(ProfilePhotos.class);
            profilePhotos = photos;
        }
        return photos;
    }

    private static Person.Id signedInPerson(final HttpServletRequest req) {
        final HttpSession session = req.getSession(false);
        final Object raw = (session == null) ? null : session.getAttribute(PersonCommands.ACTIVE_USER_ID);
        if (raw == null) {
            return null;
        }
        return raw instanceof Person.Id pid ? pid : Person.Id.from(raw.toString());
    }
}
