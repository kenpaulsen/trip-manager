package org.paulsens.trip.action;

import jakarta.servlet.http.HttpSession;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.ScopeUtil;

/**
 * Who is making a request, and what they are allowed to do -- resolved the same way whichever edge asked.
 *
 * <p>The application has two front doors and they find the signed-in user differently. A JSF page reaches it
 * through {@code FacesContext}, a ThreadLocal that exists only on a Faces request; a JAX-RS resource has an
 * {@code HttpSession} and no {@code FacesContext} at all. Every check that needed to work from both has so far
 * grown its own accommodation for that -- a session-taking {@code hasRole} overload here, a
 * {@code siteAdminHint} boolean threaded through six chat methods there -- and each one is a separate chance to
 * get it wrong in a way that fails SILENTLY, because a check that cannot see the session does not throw, it
 * just answers "no".
 *
 * <p>This is the one place that difference is handled. {@link #current()} for the Faces path, {@link #of} for a
 * servlet or JAX-RS path; after that a bean asks the same questions of the same type and cannot tell which edge
 * it is serving.
 *
 * <h2>Privileges, not {@code showAll}</h2>
 *
 * <p>Authorization here is by named privilege. The older all-or-nothing {@code showAll} view flag is on its way
 * out, and nothing built on this should reach for it: {@link #isSiteAdmin()} exists for the cases where the
 * role genuinely is the answer, and {@link #has} for everything else.
 */
public final class Caller {

    private static final String SITE_ADMIN_ROLE = "admin";

    private final Person.Id personId;
    private final boolean siteAdmin;
    private final AuditActor actor;
    private final PrivilegeCommands privileges;

    /**
     * Explicit-collaborator constructor, public so a test can build a caller without a container.
     *
     * <p>The factories are the way in for real code; this exists because the interesting cases here -- an
     * administrator, somebody holding one trip-scoped privilege, nobody at all -- are exactly the ones that
     * need a privilege lookup stood in for. Same seam {@code ChatCommands} opens for its rate limiter.
     */
    public Caller(final Person.Id personId, final boolean siteAdmin, final AuditActor actor,
            final PrivilegeCommands privileges) {
        this.personId = personId;
        this.siteAdmin = siteAdmin;
        this.actor = actor;
        this.privileges = privileges;
    }

    /**
     * The caller behind a servlet request's session. Used by the REST edge.
     *
     * <p>A null session yields a caller that holds nothing, rather than throwing. Failing closed is the whole
     * point: an unauthenticated request must look like somebody with no privileges, not like an error the
     * caller can distinguish from a refusal.
     */
    public static Caller of(final HttpSession session) {
        if (session == null) {
            return new Caller(null, false, AuditActor.from(null), new PrivilegeCommands());
        }
        return new Caller(
                personIdOf(session.getAttribute(PersonCommands.ACTIVE_USER_ID)),
                PersonCommands.hasRole(session, SITE_ADMIN_ROLE),
                AuditActor.from(session),
                new PrivilegeCommands());
    }

    /** The caller behind a JSF request, resolved through {@code FacesContext}. */
    public static Caller current() {
        final ScopeUtil scope = ScopeUtil.getInstance();
        final Object rawId = scope.getSessionMap(PersonCommands.ACTIVE_USER_ID);
        final Object rawRole = scope.getSessionMap(PersonCommands.ACTIVE_USER_ROLE);
        return new Caller(
                personIdOf(rawId),
                rawRole != null && SITE_ADMIN_ROLE.equalsIgnoreCase(rawRole.toString()),
                AuditActor.current(),
                new PrivilegeCommands());
    }

    public Person.Id personId() {
        return personId;
    }

    public boolean isSiteAdmin() {
        return siteAdmin;
    }

    /** For audit records, so a caller and the actor it is recorded as cannot drift apart. */
    public AuditActor auditActor() {
        return actor;
    }

    /** Whether anybody is signed in at all. */
    public boolean isAuthenticated() {
        return personId != null;
    }

    public boolean has(final String privilegeName) {
        return has(privilegeName, null);
    }

    /**
     * Whether this caller holds the named privilege, optionally scoped to a trip.
     *
     * <p>A site administrator holds everything, checked first. Without that short-circuit an administrator is
     * refused whenever nobody remembered to create the backing privilege row -- which is most of them, since
     * the rows exist to grant access to people who are not administrators.
     */
    public boolean has(final String privilegeName, final String tripId) {
        if (siteAdmin) {
            return true;
        }
        return personId != null && privileges.check(privilegeName, tripId, personId);
    }

    private static Person.Id personIdOf(final Object raw) {
        if (raw instanceof Person.Id id) {
            return id;
        }
        return raw == null ? null : Person.Id.from(raw.toString());
    }
}
