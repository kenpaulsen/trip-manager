package org.paulsens.trip.action;

import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * A password change replaces the password and nothing else. The login row also holds the account's role and its
 * last sign-in, and writing a fresh row over it once turned every administrator who changed their password into
 * an ordinary user.
 *
 * <p>The fixture's address is deliberately not a local-mode persona: {@code admin*} and {@code user*} logins are
 * answered by {@code FakeData} with the role their name implies whatever the store holds, so a persona could
 * never show this.
 */
public class PassCommandsStoredRoleTest {

    private static final long LAST_LOGIN = 1_700_000_000L;

    @Test
    public void aPasswordChangeKeepsTheStoredRoleAndLastLogin() {
        final Person boss = TenancyFixtures.savedPerson("Boss");
        final DAO dao = DAO.getInstance();
        assertTrue(dao.saveCreds(new Creds(boss.getEmail(), boss.getId(), "admin", "oldpass01", LAST_LOGIN)));
        final PassCommands pass = new PassCommands();

        assertTrue(pass.setPass(boss.getEmail(), "oldpass01", "newpass02", "newpass02"));

        final Creds after = dao.getCredsForCodeLogin(boss.getEmail(), Cached.NO);
        assertEquals(after.getPriv(), "admin", "an administrator stays one across a password change");
        assertEquals(after.getLastLogin(), Long.valueOf(LAST_LOGIN));
        assertEquals(after.getUserId(), boss.getId());
        assertNotNull(pass.getCreds(boss.getEmail(), "newpass02"), "the new password signs in");
        assertNull(pass.getCreds(boss.getEmail(), "oldpass01"), "...and the old one no longer does");
    }
}
