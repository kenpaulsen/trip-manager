package org.paulsens.trip.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuthToken;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.web.Sessions;
import org.testng.annotations.Test;

/**
 * While an administrator is viewing the site AS somebody, the session's person is the one being viewed. A
 * passkey registered then would be the ADMINISTRATOR's authenticator signing in as that person, and the devices
 * list would be theirs to read and end -- so the sign-in endpoints that act on "the signed-in person" refuse for
 * as long as View As lasts.
 */
public class ViewAsSignInGuardTest extends ResourceTestSupport {

    private static final Person.Id ADMIN = Person.Id.from("view-as-admin");
    private static final Person.Id VIEWED = Person.Id.from("view-as-target");

    /** The session {@code Sessions.pushViewAs} leaves behind: the target's id, the admin's own id and address. */
    private void viewingAs() {
        signedInAs(VIEWED);
        session(Sessions.ADMIN_USER, ADMIN);
        session(Sessions.LOGIN_EMAIL, "admin@example.com");
        final List<HashMap<String, Object>> stack = new ArrayList<>();
        stack.add(new HashMap<>(Map.of(Sessions.ADMIN_USER, ADMIN)));
        session(Sessions.VIEW_AS_STACK, stack);
    }

    @Test
    public void passkeysCannotBeRegisteredOrRemovedWhileViewingAs() {
        viewingAs();
        final PasskeyResource passkeys = resource(new PasskeyResource());
        assertError(passkeys.registerStart(CSRF_OK), 403, ApiErrors.FORBIDDEN);
        assertError(passkeys.registerFinish(CSRF_OK, Map.of("credential", "{}")), 403, ApiErrors.FORBIDDEN);
        assertError(passkeys.delete(CSRF_OK, "any-credential"), 403, ApiErrors.FORBIDDEN);
    }

    @Test
    public void theViewedPersonsDevicesAreNeitherListedNorRevoked() {
        final long now = java.time.Instant.now().getEpochSecond();
        final String selector = "va-" + System.nanoTime();
        DAO.getInstance().saveAuthToken(AuthToken.builder().selector(selector).validatorHash("hash")
                .userId(VIEWED).email("viewed@example.org").created(now).lastUsed(now).expires(now + 3600)
                .kind(AuthToken.Kind.REFRESH).build());
        try {
            viewingAs();
            final AuthResource auth = resource(new AuthResource());
            assertError(auth.sessions(), 403, ApiErrors.FORBIDDEN);
            assertError(auth.revokeSession(selector, CSRF_OK), 403, ApiErrors.FORBIDDEN);
            org.testng.Assert.assertTrue(DAO.getInstance()
                    .getAuthToken(selector, org.paulsens.trip.cache.Cached.NO).isPresent(), "the device survives");
        } finally {
            DAO.getInstance().deleteAuthToken(selector);
        }
    }

    /** An administrator who is NOT viewing as anyone has {@code aUser} equal to their own id: nothing changes. */
    @Test
    public void anAdministratorsOwnSessionIsNotViewAs() {
        signedInAsSiteAdmin(ADMIN);
        session(Sessions.ADMIN_USER, ADMIN);
        assertOk(resource(new AuthResource()).sessions());
        assertError(resource(new PasskeyResource()).registerStart(CSRF_OK), 404, ApiErrors.NOT_FOUND);
    }

    /** The stack alone is enough: {@code aUser} missing must not reopen the door. */
    @Test
    public void aPushedSnapshotAloneCountsAsViewAs() {
        signedInAs(VIEWED);
        final List<HashMap<String, Object>> stack = new ArrayList<>();
        stack.add(new HashMap<>());
        session(Sessions.VIEW_AS_STACK, stack);
        assertError(resource(new AuthResource()).sessions(), 403, ApiErrors.FORBIDDEN);
    }

    /** {@code aUser} set to somebody else, with no stack: the other half of the condition, on its own. */
    @Test
    public void adminUserDifferingFromTheActiveUserAloneCountsAsViewAs() {
        signedInAs(VIEWED);
        session(Sessions.ADMIN_USER, ADMIN);
        assertError(resource(new AuthResource()).sessions(), 403, ApiErrors.FORBIDDEN);
    }
}
