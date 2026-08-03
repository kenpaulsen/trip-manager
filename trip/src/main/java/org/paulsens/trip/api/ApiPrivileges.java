package org.paulsens.trip.api;

import jakarta.servlet.http.HttpSession;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.model.Person;

/**
 * Authorization for the REST edge.
 *
 * <p>A JSF page guards itself in its own markup -- {@code priv.check('tripView', tripId, userId)} decides whether
 * a panel renders. A resource has no markup and therefore no guard: whatever it does, it does for anyone who can
 * reach the URL. Every endpoint has to ask, in Java, and this is what it asks.
 *
 * <p>Both underlying checks have a {@code FacesContext}-free form and this class uses only those.
 * {@code PersonCommands.hasRole(String)} resolves the session through {@code FacesContext}, a ThreadLocal that
 * does not exist on a JAX-RS thread; called from a servlet it returns {@code false} without complaint, so a site
 * administrator would look like an ordinary member and be refused every privileged action.
 */
public final class ApiPrivileges {

    /** Privilege names, spelled exactly as the XHTML pages spell them -- a typo here is a silent open door. */
    public static final String PEOPLE_ADMIN = "peopleAdmin";
    public static final String PRIVILEGE_ADMIN = "privilegeAdmin";
    public static final String CONFIG_ADMIN = "configAdmin";
    public static final String AUDIT_ADMIN = "auditAdmin";
    public static final String MEDIA_ADMIN = "mediaAdmin";
    public static final String EMAIL_ADMIN = "emailAdmin";
    public static final String SITE_DEPLOYER = "siteDeployer";
    public static final String TRIP_MGR = "tripMgr";
    public static final String TRIP_VIEW = "tripView";
    public static final String TRIP_FIN_VIEW = "tripFinView";
    public static final String TRIP_FIN_ADMIN = "tripFinAdmin";
    public static final String ADD_TRIP = "addTrip";
    public static final String ADD_TX = "addTx";

    private final HttpSession session;
    private final Person.Id viewer;
    private final PrivilegeCommands privileges;
    private final PersonCommands people;

    /**
     * Collaborators are passed in rather than looked up on demand. A {@code Beans.get(...)} inside a method
     * reaches for CDI on every call, which is both wasteful and untestable -- a unit test has no CDI container,
     * so the lookup throws, and only on whichever branch happens to reach it.
     */
    ApiPrivileges(final HttpSession session, final Person.Id viewer, final PrivilegeCommands privileges,
            final PersonCommands people) {
        this.session = session;
        this.viewer = viewer;
        this.privileges = privileges;
        this.people = people;
    }

    public static ApiPrivileges of(final HttpSession session, final Person.Id viewer) {
        return new ApiPrivileges(
                session, viewer, Beans.get(PrivilegeCommands.class), Beans.get(PersonCommands.class));
    }

    public boolean isSiteAdmin() {
        return PersonCommands.hasRole(session, "admin");
    }

    /** A global privilege. Site admins hold everything, so the role short-circuits the lookup. */
    public boolean has(final String privilegeName) {
        return has(privilegeName, null);
    }

    /** A privilege scoped to a trip; a null or blank {@code tripId} means the global privilege of that name. */
    public boolean has(final String privilegeName, final String tripId) {
        return isSiteAdmin() || privileges.check(privilegeName, tripId, viewer);
    }

    /**
     * Whether the caller may act for {@code subject} -- themselves, or someone whose booking they manage.
     *
     * <p>Takes the viewer's own {@code Person} because {@code managedUsers} lives on the viewer's record, not the
     * subject's: the question is "is this person on my list", not "who claims to manage them".
     */
    public boolean canActFor(final Person viewerPerson, final Person.Id subject) {
        return people.canAccessUserId(viewerPerson, subject);
    }

    /**
     * How much of {@code subject}'s record the caller may see, on a given trip.
     *
     * <p>Ordered most-specific first. Self before manager, and both before the trip roles, so that a trip manager
     * looking at their own record is not quietly demoted to what a trip manager sees of a stranger.
     */
    public AccessLevel levelFor(final Person viewerPerson, final Person.Id subject, final String tripId) {
        if (viewer != null && viewer.equals(subject)) {
            return AccessLevel.SELF;
        }
        if (canActFor(viewerPerson, subject)) {
            return AccessLevel.MANAGER;
        }
        if (isSiteAdmin()) {
            return AccessLevel.SITE_ADMIN;
        }
        if (privileges.check(TRIP_MGR, tripId, viewer)) {
            return AccessLevel.TRIP_ADMIN;
        }
        if (privileges.check(TRIP_VIEW, tripId, viewer)) {
            return AccessLevel.TRIP_VIEWER;
        }
        return AccessLevel.PEER;
    }
}
