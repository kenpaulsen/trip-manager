package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.paulsens.trip.action.ChatPhotos;

/**
 * Local-mode chat-photo serving (in production the CDN serves these and GET here 404s). Public-by-URL on
 * purpose — the production CDN serves them to anyone holding the URL, and local mode must behave like
 * production or it stops finding production's bugs.
 *
 * <p>This used to be the chat photo UPLOAD endpoint too (XHR POST + CSRF header + its own multipart-config),
 * back when a {@code p:fileUpload} could not exist anywhere on the chat page: the site's single JSF form must
 * never go multipart (the maxPartCount landmine). The shared upload dialog (template.xhtml's uploadDialogs
 * insert) later gave every page a dedicated multipart form OUTSIDE the main one, chat's composer moved onto
 * it ({@code ChatPhotoUpload} carries the gate checks and the rate limiter that lived here), and the POST
 * path was retired rather than left as a second, unmaintained way in.
 */
public class ChatUploadServlet extends HttpServlet {

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
}
