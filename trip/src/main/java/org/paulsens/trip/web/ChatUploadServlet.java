package org.paulsens.trip.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ChatCommands;
import org.paulsens.trip.action.ChatPhotos;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.model.Person;

/**
 * The chat photo endpoint: {@code POST} stages one photo for the composer; {@code GET} serves local-mode
 * photos back (in production the CDN serves them and GET here 404s).
 *
 * <p>A dedicated servlet with its own {@code multipart-config} (declared in the live web.xml, sibling repo),
 * posted to by XHR — <b>not</b> a {@code p:fileUpload} in the site's single JSF form. Tomcat's
 * {@code maxPartCount} default of 10 counts ordinary fields, a JSF ajax postback already spends ~6, and the
 * chat page is the densest form on the site; a multipart JSF postback there is a 500 in
 * {@code Request.parseParts()} before any bean runs. The design doc calls this the multipart landmine, and
 * this servlet is the shape it prescribes.
 *
 * <p>Requests must carry the same {@code X-Trip-Chat} header as the chat REST API: a browser can only add it
 * from same-origin script, which is what stops a cross-site form post from uploading under someone's session.
 *
 * <p>One photo per request, by design: the composer stages each attachment as it is chosen (up to the
 * channel's cap, enforced at send), progress is per-photo, and one bad file rejects alone rather than failing
 * a batch.
 */
@Slf4j
public class ChatUploadServlet extends HttpServlet {

    /** Uploads per person per window — far above honest use (10/message), low enough to stop a loop. */
    static final int UPLOADS_PER_WINDOW = 40;
    static final int WINDOW_SECONDS = 600;

    private static final Pattern TRIP_ID = Pattern.compile("[A-Za-z0-9._-]{1,80}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Per-person upload timestamps. Process-local like the staging registry, and the same trade. */
    private final Map<String, Deque<Long>> recentUploads = new ConcurrentHashMap<>();

    @Override
    protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        if (req.getHeader(ChatCommands.CSRF_HEADER) == null) {
            error(resp, 403, "csrf", "Missing " + ChatCommands.CSRF_HEADER + " header.");
            return;
        }
        final Person.Id me = signedInPerson(req);
        if (me == null) {
            error(resp, 401, "not_authenticated", "Sign in required.");
            return;
        }
        final String tripId = req.getParameter("trip");
        if (tripId == null || !TRIP_ID.matcher(tripId).matches()) {
            error(resp, 400, "bad_trip", "Invalid trip id.");
            return;
        }
        if (!allowUpload(me.getValue())) {
            resp.setHeader("Retry-After", "60");
            error(resp, 429, "rate_limited", "Too many uploads. Wait a minute and try again.");
            return;
        }
        final ChatCommands.AttachGate gate = ChatCommands.getChatCommands().checkAttach(tripId, me);
        if (gate.denial() != null) {
            error(resp, 403, "forbidden", gate.denial());
            return;
        }

        final Part part;
        try {
            part = req.getPart("photo");
        } catch (final IllegalStateException ex) {
            // The container's multipart limit tripped (file bigger than max-file-size in web.xml).
            error(resp, 413, "too_large", tooLargeMessage(gate));
            return;
        } catch (final ServletException | RuntimeException ex) {
            error(resp, 400, "bad_request", "The upload could not be read.");
            return;
        }
        if (part == null || part.getSize() == 0) {
            error(resp, 400, "no_photo", "No photo was included in the upload.");
            return;
        }
        final long cap = gate.channel().getSettings().getMaxAttachmentBytes();
        if (part.getSize() > cap) {
            error(resp, 413, "too_large", tooLargeMessage(gate));
            return;
        }

        final byte[] bytes;
        try (InputStream in = part.getInputStream()) {
            bytes = in.readAllBytes();
        }
        try {
            final ChatPhotos photos = ChatPhotos.getChatPhotos();
            final ChatPhotos.StagedPhoto staged = photos.stage(tripId, me, bytes);
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("key", staged.key());
            body.put("smallKey", staged.smallKey());
            body.put("url", urlFor(req, photos, staged.key()));
            body.put("smallUrl", urlFor(req, photos, staged.smallKey()));
            body.put("width", staged.width());
            body.put("height", staged.height());
            body.put("size", staged.size());
            body.put("contentType", staged.contentType());
            json(resp, 200, body);
        } catch (final PhotoRejectedException ex) {
            error(resp, 422, "rejected", ex.getMessage());
        } catch (final IllegalStateException ex) {
            log.error("Chat photo store failed for trip {}", tripId, ex);
            error(resp, 503, "store", "The photo could not be stored. Try again.");
        }
    }

    /**
     * Local-mode serving. Public-by-URL on purpose — the production CDN serves these to anyone holding the
     * URL, and local mode must behave like production or it stops finding production's bugs.
     */
    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final String path = req.getPathInfo();
        final String key = (path == null || path.length() < 2) ? null : path.substring(1);
        if (key == null || !key.startsWith(ChatPhotos.KEY_PREFIX) || key.contains("..")) {
            resp.sendError(404);
            return;
        }
        final ChatPhotos.ServedPhoto photo = ChatPhotos.getChatPhotos().localGet(key).orElse(null);
        if (photo == null) {
            resp.sendError(404);
            return;
        }
        resp.setContentType(photo.contentType());
        resp.setContentLength(photo.bytes().length);
        resp.setHeader("Cache-Control", "private, max-age=3600");
        resp.getOutputStream().write(photo.bytes());
    }

    private static String tooLargeMessage(final ChatCommands.AttachGate gate) {
        final long mb = gate.channel().getSettings().getMaxAttachmentBytes() / (1024 * 1024);
        return "That photo is too large. Photos can be at most " + mb + " MB.";
    }

    /** The URL the composer should use: the CDN when configured, this servlet's GET otherwise. */
    private static String urlFor(final HttpServletRequest req, final ChatPhotos photos, final String key) {
        final String base = photos.getPublicBase();
        return (base != null) ? base + key : req.getContextPath() + "/chat-photos/" + key;
    }

    private static Person.Id signedInPerson(final HttpServletRequest req) {
        final HttpSession session = req.getSession(false);
        final Object raw = (session == null) ? null : session.getAttribute(PersonCommands.ACTIVE_USER_ID);
        if (raw == null) {
            return null;
        }
        return raw instanceof Person.Id pid ? pid : Person.Id.from(raw.toString());
    }

    private boolean allowUpload(final String personId) {
        final long now = System.currentTimeMillis();
        final Deque<Long> times = recentUploads.computeIfAbsent(personId, key -> new ArrayDeque<>());
        synchronized (times) {
            final long cutoff = now - WINDOW_SECONDS * 1_000L;
            while (!times.isEmpty() && times.peekFirst() < cutoff) {
                times.pollFirst();
            }
            if (times.size() >= UPLOADS_PER_WINDOW) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }

    private static void error(final HttpServletResponse resp, final int status, final String code,
            final String message) throws IOException {
        json(resp, status, Map.of("error", code, "message", message));
    }

    private static void json(final HttpServletResponse resp, final int status, final Map<String, Object> body)
            throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        MAPPER.writeValue(resp.getOutputStream(), body);
    }
}
