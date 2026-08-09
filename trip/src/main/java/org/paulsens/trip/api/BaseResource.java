package org.paulsens.trip.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;

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

    @Context
    protected HttpServletResponse response;

    /**
     * Memoized per request, which is safe because JAX-RS creates a resource instance per request.
     *
     * <p>Worth doing rather than rebuilding on each call: {@link #privileges()} is asked inside list filters --
     * {@code TripsResource} consults it twice per trip -- and each construction costs two CDI lookups, so a
     * fifty-trip listing was doing two hundred of them to answer a question whose inputs cannot change mid-request.
     */
    private ApiPrivileges cachedPrivileges;
    private Caller cachedCaller;

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

    /**
     * The caller, for beans that take one directly.
     *
     * <p>Built from the session rather than through {@link #privileges()}, which resolves the id via
     * {@link #personId()} and THROWS when nobody is signed in. {@code actor()} answers an unknown actor in that
     * situation rather than throwing, and these two must behave alike: a bean that takes a caller should be
     * able to refuse an anonymous one on its merits, not have the refusal arrive as an exception from a
     * different layer.
     *
     * <p>Memoized alongside {@link #privileges()} so a request that asks several privilege questions pays for
     * each underlying lookup once.
     */
    protected Caller caller() {
        if (cachedCaller == null) {
            cachedCaller = Caller.of(request.getSession(false));
        }
        return cachedCaller;
    }

    /** The authorization gate for this caller. Every endpoint that is not purely self-scoped consults it. */
    protected ApiPrivileges privileges() {
        if (cachedPrivileges == null) {
            // Shares this request's Caller, so its privilege cache is one cache, not two.
            cachedPrivileges = ApiPrivileges.of(caller());
        }
        return cachedPrivileges;
    }

    /**
     * The person with this id, or {@code null} if there is no such person.
     *
     * <p>{@code PersonCommands.getPerson} does NOT answer null on a miss -- it answers
     * {@code new Person()}, whose constructor mints a fresh random id. That is a reasonable default for a JSF
     * page binding a form to "the person being edited", and it is a trap for a resource: a plain
     * {@code person == null} check never fires, so a GET for a nonexistent id returns a blank stranger with a
     * made-up id instead of a 404, and a PUT applies the request body to that blank object and SAVES it,
     * creating a junk row on every write to an id that does not exist.
     *
     * <p>The fresh id is what makes the miss detectable: a real record's id equals the one that was asked for.
     */
    protected static Person findPerson(final Person.Id id) {
        if (id == null) {
            return null;
        }
        final Person person = Beans.get(PersonCommands.class).getPerson(id);
        return (person == null || !id.equals(person.getId())) ? null : person;
    }

    /**
     * The trip with this id, or {@code null} if there is no such trip.
     *
     * <p>Same trap as {@link #findPerson}: {@code TripCommands.getTrip} answers a miss with
     * {@code Trip.builder().build()}, whose builder mints a random id, so a null check on the result never
     * fires. Any resource that trusted it returned a blank made-up trip on a GET -- or worse, keyed a WRITE off
     * a trip id nobody ever created. The minted id is what makes the miss detectable: a real trip's id equals
     * the one that was asked for.
     */
    protected static Trip findTrip(final String tripId) {
        if (tripId == null) {
            return null;
        }
        final Trip trip = Beans.get(TripCommands.class).getTrip(tripId);
        return (trip == null || !tripId.equals(trip.getId())) ? null : trip;
    }

    /**
     * The trip with this id, or a 404 out of the whole request.
     *
     * <p>For endpoints whose path names a trip that simply must exist: instead of every method carrying its own
     * {@code if (findTrip(...) == null) return error(404, ...)}, this throws the same negotiated error body as
     * a {@link WebApplicationException}, which {@code JsonExceptionMapper} passes through untouched. Use
     * {@link #findTrip} instead when "no such trip" is an answer the endpoint wants to shape itself.</p>
     */
    protected Trip requireTrip(final String tripId) {
        final Trip trip = findTrip(tripId);
        if (trip == null) {
            throw new WebApplicationException(error(404, ApiErrors.NOT_FOUND, "No such trip."));
        }
        return trip;
    }

    /**
     * The person with this id, or a 404 out of the whole request.
     *
     * <p>The same guard as {@link #requireTrip}, for the personId half of the invented-record trap: several
     * {@code get*} beans answer a miss by CREATING what was asked for ({@code getRoomPDV} even saves it), so an
     * endpoint that skips this check turns a mistyped person id into a junk row keyed to nobody.</p>
     */
    protected Person requireSubject(final Person.Id subject) {
        final Person person = findPerson(subject);
        if (person == null) {
            throw new WebApplicationException(error(404, ApiErrors.NOT_FOUND, "No such person."));
        }
        return person;
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
