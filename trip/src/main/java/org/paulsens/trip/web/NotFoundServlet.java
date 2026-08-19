package org.paulsens.trip.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.paulsens.trip.action.PersonCommands;

/**
 * The 404/405 error target (wired via {@code <error-page>} in the live web.xml). Signed-in users keep the
 * old behavior -- a forward to the landing page, where the sidebar and menus help them re-orient. Everyone
 * else gets a small self-contained page with a link home.
 *
 * <p>The split is a load-shedding measure, not a cosmetic one. On 2026-08-19 a vulnerability scanner swept
 * 10k+ junk URLs in under an hour, and the then-catch-all error page rendered the full ~108KB JSF landing
 * page -- plus a session and a view-state burn -- for every probe; CPU pinned at 100% until the ALB health
 * checks timed out and ECS killed the task. Anonymous garbage now costs one fixed ~2KB write with
 * <b>no session</b> ({@code getSession(false)} only -- creating sessions for scanners is its own DoS) and
 * no JSF lifecycle. The page embeds everything inline: an external stylesheet or image reference would
 * itself 404 and re-enter this servlet.</p>
 */
public class NotFoundServlet extends HttpServlet {

    /**
     * {@code service} rather than {@code doGet}: error dispatch preserves the original request method, and
     * scanners probe with POST and DELETE too -- HttpServlet's default routing would answer those 405 with
     * its own body instead of this page.
     */
    @Override
    protected void service(final HttpServletRequest request, final HttpServletResponse response)
            throws ServletException, IOException {
        if (isSignedIn(request)) {
            request.getRequestDispatcher("/index.jsf").forward(request, response);
            return;
        }
        writeStaticNotFound(request, response);
    }

    private static boolean isSignedIn(final HttpServletRequest request) {
        final HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(PersonCommands.ACTIVE_USER_ID) != null;
    }

    private static void writeStaticNotFound(final HttpServletRequest request, final HttpServletResponse response)
            throws IOException {
        final int status = errorStatus(request);
        response.setStatus(status);
        response.setContentType("text/html");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(staticPage(status));
    }

    /** The container's error dispatch supplies the real code; direct hits on /notFound read as 404. */
    private static int errorStatus(final HttpServletRequest request) {
        final Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        return (code instanceof Integer i) ? i : HttpServletResponse.SC_NOT_FOUND;
    }

    private static String staticPage(final int status) {
        final String title = (status == HttpServletResponse.SC_METHOD_NOT_ALLOWED)
                ? "Method not allowed" : "Page not found";
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%d - %s</title>
                <style>
                  body { margin: 0; font-family: system-ui, -apple-system, sans-serif; min-height: 100vh;
                         display: flex; align-items: center; justify-content: center;
                         background: linear-gradient(160deg, #4b5d8a 0%%, #7a8cc0 100%%); color: #fff; }
                  main { text-align: center; padding: 2rem; max-width: 34rem; }
                  h1 { font-size: 5rem; margin: 0; font-weight: 600; opacity: 0.9; }
                  p  { font-size: 1.15rem; line-height: 1.6; margin: 1rem 0 2rem; }
                  .es { font-size: 0.95rem; opacity: 0.85; }
                  a.home { display: inline-block; padding: 0.75rem 2.25rem; border-radius: 2rem;
                           background: #fff; color: #4b5d8a; text-decoration: none; font-weight: 600; }
                  a.home:hover { background: #e8ecf7; }
                </style>
                </head>
                <body>
                <main>
                  <h1>%d</h1>
                  <p>%s.<br>The page you are looking for does not exist or may have moved.<br>
                     <span class="es">La p&aacute;gina que busca no existe o se ha movido.</span></p>
                  <a class="home" href="/">Home / Inicio</a>
                </main>
                </body>
                </html>
                """.formatted(status, title, status, title);
    }
}
