package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.paulsens.trip.site.DomainProbe;

/**
 * The other half of {@link DomainProbe}: answers {@code GET /.well-known/trip-site-check?code=...} with the code
 * itself while this server has that code outstanding, and 404 otherwise. The probe arrives anonymous, on a host
 * no organization holds yet, so this touches no session and no JSF; any host may ask, and only a code this JVM
 * issued in the last few seconds is ever echoed. Mapped in the live web.xml as an EXACT path, which outranks
 * the {@code /.well-known/*} mapping of {@link WellKnownServlet}.
 */
public class SiteCheckServlet extends HttpServlet {

    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        final String answer = DomainProbe.answer(request.getParameter("code"));
        if (answer == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/plain");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getOutputStream().write(answer.getBytes(StandardCharsets.UTF_8));
    }
}
