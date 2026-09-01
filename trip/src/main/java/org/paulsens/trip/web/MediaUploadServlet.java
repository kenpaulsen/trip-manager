package org.paulsens.trip.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.MediaCommands;
import org.paulsens.trip.action.PrivilegeCommands;

/**
 * The curated-media upload endpoint behind the landing page's Documents dialog: {@code POST /media-upload}
 * stores one file into an allowlisted slot.
 *
 * <p>A dedicated XHR servlet with its own {@code multipart-config} (declared in the live web.xml, sibling
 * repo) for the same reason as {@link ChatUploadServlet}: the landing page is the site's most public page and
 * its JSF form must never go multipart -- Tomcat's {@code maxPartCount} default of 10 counts ordinary fields,
 * and a JSF ajax postback already spends most of that budget.
 *
 * <p>Requests must carry the same-origin {@link #CSRF_HEADER}, a signed-in session, and the
 * {@code mediaAdmin} privilege. The slot is checked against {@link #ALLOWED_SLOTS} so this endpoint cannot
 * write arbitrary slots -- anything else stays an admin-page operation.
 */
@Slf4j
public class MediaUploadServlet extends HttpServlet {

    /** Same-origin proof, like the chat API's header: only same-origin script can add it. */
    public static final String CSRF_HEADER = "X-Trip-Media";
    /** The only slots this endpoint may write; everything else goes through /admin/media.jsf. */
    static final Set<String> ALLOWED_SLOTS = Set.of("home-docs");
    /** Stored under the same prefix the hand-managed documents already use, so URLs look unchanged. */
    private static final String KEY_PREFIX = "downloads/";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Direct instance, not CDI: MediaCommands has no injected collaborators, and tests replace this. */
    private MediaCommands media = new MediaCommands();

    @Override
    protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        if (req.getHeader(CSRF_HEADER) == null) {
            error(resp, 403, "csrf", "Missing " + CSRF_HEADER + " header.");
            return;
        }
        final Caller caller = callerOf(req);
        if (!caller.isAuthenticated()) {
            error(resp, 401, "not_authenticated", "Sign in required.");
            return;
        }
        // Global media admins upload anywhere; an org's media editor only on that org's own site (the
        // upload is stamped with the site's org -- see MediaCommands.siteOrgId).
        if (!caller.hasHere(PrivilegeCommands.MEDIA_ADMIN)) {
            error(resp, 403, "forbidden", "Media administration required.");
            return;
        }
        final String slot = req.getParameter("slot");
        if (slot == null || !ALLOWED_SLOTS.contains(slot)) {
            error(resp, 400, "bad_slot", "That slot cannot be uploaded to here.");
            return;
        }

        final Part part;
        try {
            part = req.getPart("file");
        } catch (final IllegalStateException ex) {
            // The container's multipart limit tripped (file bigger than max-file-size in web.xml).
            error(resp, 413, "too_large", "That file is too large.");
            return;
        } catch (final ServletException | RuntimeException ex) {
            error(resp, 400, "bad_request", "The upload could not be read.");
            return;
        }
        if (part == null || part.getSize() == 0) {
            error(resp, 400, "no_file", "No file was included in the upload.");
            return;
        }
        final String fileName = safeFileName(part.getSubmittedFileName());
        if (fileName == null) {
            error(resp, 400, "bad_name", "The file needs a plain file name.");
            return;
        }
        if (!media.isUploadEnabled()) {
            error(resp, 503, "no_bucket", "Uploads are not available in this deployment.");
            return;
        }

        final boolean stored;
        try (InputStream in = part.getInputStream()) {
            stored = media.upload(KEY_PREFIX + fileName, in, part.getSize(), part.getContentType(),
                    req.getParameter("title"), req.getParameter("description"), slot, 0,
                    caller.auditActor().email(), null);
        } catch (final RuntimeException ex) {
            log.error("Media upload failed for {}", fileName, ex);
            error(resp, 503, "store", "The file could not be stored. Try again.");
            return;
        }
        if (!stored) {
            error(resp, 503, "store", "The file could not be stored. Try again.");
            return;
        }
        json(resp, 200, Map.of("key", KEY_PREFIX + fileName, "slot", slot));
    }

    /**
     * Reduces a browser-submitted name to a safe object-key segment: the basename only, restricted to word
     * characters, dots, dashes and spaces (spaces become dashes). Null when nothing safe remains.
     */
    static String safeFileName(final String submitted) {
        if (submitted == null || submitted.isBlank()) {
            return null;
        }
        final String base = submitted.substring(Math.max(submitted.lastIndexOf('/'),
                submitted.lastIndexOf('\\')) + 1);
        final String cleaned = base.trim()
                .replace(' ', '-')
                .replaceAll("[^A-Za-z0-9._-]", "");
        if (cleaned.isEmpty() || cleaned.replace(".", "").isEmpty()
                || cleaned.toLowerCase(Locale.ROOT).contains("..")) {
            return null;
        }
        return cleaned;
    }

    /** Seam for tests; production resolves the caller from the request's session. */
    protected Caller callerOf(final HttpServletRequest req) {
        return Caller.of(req.getSession(false));
    }

    /** Seam for tests. */
    void setMedia(final MediaCommands media) {
        this.media = media;
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
