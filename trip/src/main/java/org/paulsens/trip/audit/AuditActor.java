package org.paulsens.trip.audit;

import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.paulsens.trip.model.Person;

/**
 * Who is doing the thing being audited.
 *
 * <p>Almost every call site had the same bug in the same way: it recorded whoever the action was ABOUT rather
 * than whoever performed it. Mail recorded the recipient, todo saved recorded the person whose list changed.
 * That reads fine until an admin acts on someone else's behalf, at which point the trail attributes the action
 * to the wrong person -- which is precisely when an audit trail is being consulted.
 *
 * <p>The actor is the signed-in session, full stop. Resolving it in one place means a call site cannot get it
 * wrong by passing the convenient variable that happens to be in scope.
 */
public record AuditActor(String email, String id) {

    /** Session keys, as set at login and read throughout the XHTML pages. */
    private static final String LOGIN_EMAIL = "loginEmail";
    private static final String USER_ID = "userId";

    private static final AuditActor UNKNOWN = new AuditActor(null, null);

    /** The id recorded for work the application started by itself. Not a person id; no person owns it. */
    public static final String SYSTEM_ID = "System";

    private static final AuditActor SYSTEM = new AuditActor(SYSTEM_ID, SYSTEM_ID);

    /**
     * The application acting on its own behalf -- the daily digest, a scheduled job, anything no human asked
     * for just now.
     *
     * <p>Distinct from {@link #UNKNOWN}, and the distinction is the point. An empty actor means "we do not know
     * who did this", which in an audit trail is a defect; {@code System} means "nobody did, this is the
     * application running on a timer", which is an answer. Reading a trail full of blank actors, you cannot
     * tell which of those you are looking at -- so the scheduled paths say so explicitly.
     */
    public static AuditActor system() {
        return SYSTEM;
    }

    /**
     * The signed-in user, or an empty actor outside a request (background threads, tests, the reset-password
     * flow where nobody is signed in yet).
     *
     * <p>Resolution order: the {@link RequestContext} ScopedValue first -- bound by the outermost filter and
     * inherited by every StructuredTaskScope fork, so subtasks of a request see its actor with no
     * hand-capturing -- then the FacesContext path as a fallback for Mojarra-internal threads and tests that
     * mock a Faces environment without the filter.</p>
     */
    public static AuditActor current() {
        if (RequestContext.SCOPE.isBound()) {
            return RequestContext.SCOPE.get().actor();
        }
        final FacesContext context = FacesContext.getCurrentInstance();
        if (context == null || context.getExternalContext() == null) {
            return UNKNOWN;
        }
        final Map<String, Object> session = context.getExternalContext().getSessionMap();
        if (session == null) {
            return UNKNOWN;
        }
        return new AuditActor(str(session.get(LOGIN_EMAIL)), str(session.get(USER_ID)));
    }

    /**
     * The signed-in user resolved from a raw {@link HttpSession}, for edges with no FacesContext (JAX-RS
     * resources, future sockets). Reads the same session keys as {@link #current()} so the two paths cannot
     * drift.
     */
    public static AuditActor from(final HttpSession session) {
        if (session == null) {
            return UNKNOWN;
        }
        return new AuditActor(str(session.getAttribute(LOGIN_EMAIL)), str(session.getAttribute(USER_ID)));
    }

    /**
     * The signed-in user, falling back to the given email when nobody is signed in.
     *
     * <p>The fallback is for self-service flows -- creating an account, resetting a forgotten password -- where
     * there is no session yet and the person acting genuinely is the account holder.
     */
    public static AuditActor currentOr(final String fallbackEmail) {
        final AuditActor actor = current();
        return (actor.email() == null) ? new AuditActor(fallbackEmail, null) : actor;
    }

    public boolean isKnown() {
        return email != null || id != null;
    }

    /**
     * The session's userId as a bare id string.
     *
     * <p>It may be a {@link Person.Id} or a plain String depending on which page set it -- {@code login-pass.xhtml}
     * assigns the object, {@code template.xhtml} assigns a String -- so both have to be handled.
     *
     * <p>{@code toString()} alone does NOT handle the object case, which is what this used to rely on. The old
     * comment reasoned that "Person.Id serializes to its value", and it does -- but that is {@code @JsonValue},
     * which governs Jackson, not {@code toString()}. {@code Person.Id} is a Lombok {@code @Value} with no
     * {@code toString()} override, so it renders as {@code Person.Id(value=abc)} and every audit record written
     * from a session that stored the object got that as its actor id: still non-null, still "known", and useless
     * for correlating a person's actions or filtering the admin audit page by actor.
     */
    private static String str(final Object value) {
        return switch (value) {
            case null -> null;
            case Person.Id id -> id.getValue();
            default -> value.toString();
        };
    }
}
