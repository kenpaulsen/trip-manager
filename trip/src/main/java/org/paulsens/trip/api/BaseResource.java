package org.paulsens.trip.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;

/**
 * What every resource on this API needs and none of them should reinvent: response shaping, {@code Accept}
 * negotiation, the CSRF sentinel, and the identity of the caller.
 *
 * <p>All of this began as private methods on {@code ChatResource}. Copied into a dozen more resources it would
 * drift, and the drift is invisible from any one file — one resource forgetting {@code Vary: Accept}, or reading
 * the session directly and disagreeing with the filter about who is signed in.
 *
 * <p>Subclasses are plain per-request JAX-RS classes managed by HK2, not CDI beans, which is why identity comes
 * from an injected {@link HttpServletRequest} rather than from anything scoped.
 */
public abstract class BaseResource {

    /**
     * The CSRF sentinel header for resources added after chat.
     *
     * <p>Chat keeps its own {@code X-Trip-Chat}: its browser client is already deployed and sends that name
     * literally, so renaming it would break every open chat page the moment this deploys.
     */
    public static final String CSRF_HEADER = "X-Trip-Api";

    @Context
    protected HttpServletRequest request;

    /**
     * This resource's versioned media type, from {@link ApiMediaTypes}. Drives both what is echoed in
     * {@code Content-Type} and what an {@code Accept} header is matched against.
     */
    protected abstract String versionedType();

    /**
     * Echoes the versioned media type when the caller asked for it, so a client can confirm which version it got.
     *
     * <p>Two things are easily conflated here. The <em>semantics served</em> for an absent or wildcard
     * {@code Accept} are v1 — the OLDEST supported version, never the newest, so a released mobile build cannot
     * silently change behaviour the day a v2 ships. The <em>type stamped</em> in that case is plain
     * {@code application/json}: a client that did not name a version is not told it received one.
     */
    protected String negotiatedType() {
        final String accept = request == null ? null : request.getHeader("Accept");
        return accept != null && accept.contains(ApiMediaTypes.token(versionedType()))
                ? versionedType() : MediaType.APPLICATION_JSON;
    }

    /**
     * Vary: Accept is mandatory, not optional. With the version in the media type the URLs for v1 and a future v2
     * are byte-identical, so any cache keyed on URL alone -- including the browser's -- would happily hand a v1
     * body to a v2 request. There is no CDN in front of this app, which makes the browser cache the live risk.
     */
    protected Response ok(final Object entity) {
        return Response.ok(entity)
                .type(negotiatedType())
                .header("Vary", "Accept")
                .build();
    }

    protected Response error(final int status, final String code, final String message) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return Response.status(status)
                .type(negotiatedType())
                .header("Vary", "Accept")
                .entity(body)
                .build();
    }

    /**
     * The signed-in user. Prefers the attribute the auth filter already resolved, and falls back to the raw
     * session key so a resource still works if it is ever reached without the filter.
     */
    protected Person.Id personId() {
        final Object prop = request.getAttribute(TripAuthFilter.PERSON_ID_PROP);
        if (prop instanceof Person.Id pid) {
            return pid;
        }
        final HttpSession session = request.getSession(false);
        if (session != null) {
            final Object raw = session.getAttribute(PersonCommands.ACTIVE_USER_ID);
            if (raw instanceof Person.Id pid) {
                return pid;
            }
            if (raw != null) {
                return Person.Id.from(raw.toString());
            }
        }
        throw new NotAuthorizedException("Sign in required.");
    }

    /**
     * Site-admin status read from the session, because the ordinary check goes through {@code FacesContext} and
     * there is none here. Without this a site administrator is refused every moderation action over the API.
     */
    protected boolean isSiteAdmin() {
        return PersonCommands.hasRole(request.getSession(false), "admin");
    }

    protected AuditActor actor() {
        return AuditActor.from(request.getSession(false));
    }

    /** The authorization gate for this caller. Every endpoint that is not purely self-scoped consults it. */
    protected ApiPrivileges privileges() {
        return ApiPrivileges.of(request.getSession(false), personId());
    }

    /**
     * Whether a mutating request is missing its CSRF sentinel.
     *
     * <p>The defence is that a cross-origin form cannot set an arbitrary header at all, so the value carries no
     * secret and needs no comparison against session state -- its mere presence is the proof. Which also means it
     * defends nothing against script already running on this origin; see the note in the API plan.
     */
    protected static boolean csrfMissing(final String headerValue) {
        return headerValue == null || !"1".equals(headerValue.trim());
    }
}
