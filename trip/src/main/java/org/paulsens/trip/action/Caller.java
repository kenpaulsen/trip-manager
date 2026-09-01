package org.paulsens.trip.action;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.security.TokenPrincipal;
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
    private final Map<String, Boolean> answers = new HashMap<>();
    private Set<String> globalHeld;

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

    /**
     * The caller behind a bearer-token request ({@code docs/api-tokens.md}).
     *
     * <p>Site-admin comes from {@link TokenPrincipal#siteAdmin()} -- the admin ROLE and the admin SCOPE
     * together -- which is the one choke point of the scope cap: since {@link #has} short-circuits through
     * {@code siteAdmin}, a member-scoped token held by an administrator behaves as that person without the
     * admin role, and no resource needs a scope check of its own. Explicit privilege rows still apply under
     * member scope, exactly as through the person's own session. Do not add a second cap; two is how they
     * drift.
     */
    public static Caller forToken(final TokenPrincipal principal) {
        if (principal == null) {
            return of((HttpSession) null);
        }
        return new Caller(principal.personId(), principal.siteAdmin(), principal.actor(),
                new PrivilegeCommands());
    }

    /**
     * A caller identified by an {@link AuditActor} alone.
     *
     * <p>For beans whose public API takes an actor and nothing else -- the chat moderation methods do -- and
     * which must still be able to say who is acting when no session is reachable: a unit test, or a call made
     * from a thread that has no request. Site-admin is NOT assumed, since an actor carries no role; such a
     * caller is authorized purely by the privileges the person actually holds.
     */
    public static Caller forActor(final AuditActor who) {
        final Person.Id id = (who == null || who.id() == null) ? null : Person.Id.from(who.id());
        return new Caller(id, false, who == null ? AuditActor.from(null) : who, new PrivilegeCommands());
    }

    /**
     * The caller behind ANY request -- JSF page, REST resource, or plain servlet -- resolved from the
     * {@code RequestContext} the outermost filter binds for every request (actor + role), so beans that
     * re-check authorization inside a write ({@code MediaCommands.mayManage}) answer the same for a page
     * and for the API. Off a bound request (a scheduler, a unit test without the seam) this is nobody.
     */
    public static Caller bound() {
        if (!org.paulsens.trip.audit.RequestContext.SCOPE.isBound()) {
            return of((HttpSession) null);
        }
        final org.paulsens.trip.audit.RequestContext ctx = org.paulsens.trip.audit.RequestContext.SCOPE.get();
        final AuditActor actor = ctx.actor();     // every RequestContext factory substitutes an anonymous actor
        final Person.Id id = actor.id() == null ? null : Person.Id.from(actor.id());
        return new Caller(id, ctx.userRole() != null && SITE_ADMIN_ROLE.equalsIgnoreCase(ctx.userRole()), actor,
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
     * Whether this caller holds the named privilege for the SITE this request is for: the global grant
     * anywhere, or -- on an organization's own site -- the grant scoped to that org. On a shared site an
     * org-scoped grant counts for nothing: it is a grant on the org's site, not on the org. The site comes
     * from the request's host ({@code SiteContext.current()}), never from the session.
     */
    public boolean hasHere(final String privilegeName) {
        if (has(privilegeName)) {
            return true;
        }
        final SiteContext site = SiteContext.current();
        return site.isOrg() && has(privilegeName, site.orgId().getValue());
    }

    /**
     * Whether this caller holds the named privilege, optionally scoped to a trip or an organization.
     *
     * <p>A site administrator holds everything, checked first. Without that short-circuit an administrator is
     * refused whenever nobody remembered to create the backing privilege row -- which is most of them, since
     * the rows exist to grant access to people who are not administrators.
     *
     * <p>Answers are memoized for the life of this caller, which is one request. The underlying check is a map
     * lookup followed by a linear scan of everyone holding that privilege, and the call sites are not one-shot:
     * {@code TripsResource.canRead} asks twice per trip inside a list filter, so a fifty-trip listing was a
     * hundred scans of the same two rows. Privileges cannot meaningfully change mid-request, so caching within
     * one is free of the staleness a longer-lived cache would have.
     */
    public boolean has(final String privilegeName, final String scopeId) {
        if (siteAdmin) {
            return true;
        }
        if (personId == null || privilegeName == null) {
            return false;
        }
        // Plain HashMap, not concurrent: a Caller belongs to one request and is never shared between threads.
        return answers.computeIfAbsent(
                privilegeName + '@' + (scopeId == null ? "" : scopeId),
                key -> privileges.check(privilegeName, scopeId, personId));
    }

    /**
     * Every GLOBAL privilege this caller holds, by name.
     *
     * <p>Exists for the road away from {@link #isSiteAdmin()}. That flag is the same all-or-nothing notion as
     * the legacy {@code showAll} view flag -- both derive from the session's role -- and it is on the way out;
     * what replaces it is asking whether somebody holds the specific privilege the operation needs. Having the
     * held set in one place makes that a rewrite of call sites rather than a redesign.
     *
     * <p>Trip-scoped privileges are deliberately not included: they are keyed per trip, so "all of them" is not
     * a bounded question. Ask {@link #has(String, String)} for those.
     *
     * <p>Computed once, from a single pass over the global privileges -- a bounded list, one per declared name.
     * A site administrator is reported as holding all of them, which is what {@link #has} already answers.
     */
    public Set<String> globalPrivileges() {
        if (globalHeld == null) {
            globalHeld = personId == null ? Set.of() : privileges.getGlobalPrivileges().stream()
                    .filter(priv -> siteAdmin || priv.getPeople().contains(personId))
                    .map(Privilege::getId)
                    .collect(Collectors.toUnmodifiableSet());
        }
        return globalHeld;
    }

    private static Person.Id personIdOf(final Object raw) {
        if (raw instanceof Person.Id id) {
            return id;
        }
        return raw == null ? null : Person.Id.from(raw.toString());
    }
}
