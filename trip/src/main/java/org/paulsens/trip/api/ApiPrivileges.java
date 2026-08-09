package org.paulsens.trip.api;

import jakarta.servlet.http.HttpSession;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.model.Person;

/**
 * Authorization for the REST edge.
 *
 * <p>A JSF page guards itself in its own markup -- {@code priv.check('tripView', tripId, userId)} decides
 * whether a panel renders. A resource has no markup and therefore no guard: whatever it does, it does for
 * anyone who can reach the URL. Every endpoint has to ask, in Java, and this is what it asks.
 *
 * <p>The "is this caller allowed" logic itself lives in {@link Caller}, which both edges share. What remains
 * here is the part that is genuinely about this API: resolving the viewer's relationship to a SUBJECT into an
 * {@link AccessLevel} for redaction, which no JSF page needs because its markup already decided what to render.
 */
public final class ApiPrivileges {

    /** Re-exported so resources read as one vocabulary; the canonical spellings live on the privilege bean. */
    public static final String PEOPLE_ADMIN = PrivilegeCommands.PEOPLE_ADMIN;
    public static final String PRIVILEGE_ADMIN = PrivilegeCommands.PRIVILEGE_ADMIN;
    public static final String CONFIG_ADMIN = PrivilegeCommands.CONFIG_ADMIN;
    public static final String AUDIT_ADMIN = PrivilegeCommands.AUDIT_ADMIN;
    public static final String MEDIA_ADMIN = PrivilegeCommands.MEDIA_ADMIN;
    public static final String CONTENT_ADMIN = PrivilegeCommands.CONTENT_ADMIN;
    public static final String EVENT_ADMIN = PrivilegeCommands.EVENT_ADMIN;
    public static final String EMAIL_ADMIN = PrivilegeCommands.EMAIL_ADMIN;
    public static final String SITE_DEPLOYER = PrivilegeCommands.SITE_DEPLOYER;
    public static final String TRIP_MGR = PrivilegeCommands.TRIP_MGR;
    public static final String TRIP_VIEW = PrivilegeCommands.TRIP_VIEW;
    public static final String TRIP_FIN_VIEW = PrivilegeCommands.TRIP_FIN_VIEW;
    public static final String TRIP_FIN_ADMIN = PrivilegeCommands.TRIP_FIN_ADMIN;
    public static final String ADD_TRIP = PrivilegeCommands.ADD_TRIP;
    public static final String ADD_TX = PrivilegeCommands.ADD_TX;

    private final Caller caller;
    private final PersonCommands people;

    ApiPrivileges(final Caller caller, final PersonCommands people) {
        this.caller = caller;
        this.people = people;
    }

    public static ApiPrivileges of(final Caller caller) {
        return new ApiPrivileges(caller, Beans.get(PersonCommands.class));
    }

    /** The shared caller, for beans that take one directly rather than a boolean hint. */
    public Caller caller() {
        return caller;
    }

    public boolean isSiteAdmin() {
        return caller.isSiteAdmin();
    }

    public boolean has(final String privilegeName) {
        return caller.has(privilegeName);
    }

    public boolean has(final String privilegeName, final String tripId) {
        return caller.has(privilegeName, tripId);
    }

    /**
     * Whether the caller may act for {@code subject} -- themselves, or someone whose booking they manage.
     *
     * <p>Takes the viewer's own {@code Person} because {@code managedUsers} lives on the viewer's record, not
     * the subject's: the question is "is this person on my list", not "who claims to manage them".
     */
    public boolean canActFor(final Person viewerPerson, final Person.Id subject) {
        return people.canAccessUserId(viewerPerson, subject);
    }

    /**
     * How much of {@code subject}'s record the caller may see, on a given trip.
     *
     * <p>Ordered most-specific first. Self before manager, and both before the trip roles, so that a trip
     * manager looking at their own record is not quietly demoted to what a trip manager sees of a stranger.
     */
    public AccessLevel levelFor(final Person viewerPerson, final Person.Id subject, final String tripId) {
        final Person.Id viewer = caller.personId();
        if (viewer != null && viewer.equals(subject)) {
            return AccessLevel.SELF;
        }
        if (canActFor(viewerPerson, subject)) {
            return AccessLevel.MANAGER;
        }
        if (caller.isSiteAdmin()) {
            return AccessLevel.SITE_ADMIN;
        }
        if (caller.has(TRIP_MGR, tripId)) {
            return AccessLevel.TRIP_ADMIN;
        }
        if (caller.has(TRIP_VIEW, tripId)) {
            return AccessLevel.TRIP_VIEWER;
        }
        return AccessLevel.PEER;
    }
}
