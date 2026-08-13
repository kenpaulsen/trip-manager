package org.paulsens.trip.dynamo;

import java.time.Instant;
import java.util.Optional;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.RememberToken;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@link RememberMeDAO} round-trips against the in-memory fake store (which is the real local-mode store). */
public class RememberMeDAOTest {

    private static RememberToken token(final String selector, final Person.Id who) {
        final long now = Instant.now().getEpochSecond();
        return new RememberToken(selector, "hash-" + selector, "prev-" + selector, now - 5, who,
                "who@example.org", now, now, now + 3600);
    }

    /** Rows written before the rotation-grace columns existed have neither; they must read back as null. */
    @Test
    public void aRowWithoutGraceColumnsRoundTripsWithNulls() {
        final String selector = RandomData.genSecureToken(9);
        final long now = Instant.now().getEpochSecond();
        final RememberToken legacy = new RememberToken(selector, "hash", null, null,
                Person.Id.from("rm-legacy"), "who@example.org", now, now, now + 3600);

        Assert.assertTrue(DAO.getInstance().saveRememberToken(legacy));
        final RememberToken read = DAO.getInstance().getRememberToken(selector).orElseThrow();
        Assert.assertNull(read.getPrevValidatorHash());
        Assert.assertNull(read.getRotatedAt());
        Assert.assertEquals(read, legacy);
        DAO.getInstance().deleteRememberToken(selector);
    }

    @Test
    public void saveGetDeleteRoundTrip() {
        final String selector = RandomData.genSecureToken(9);
        final RememberToken token = token(selector, Person.Id.from("rm-round-trip"));

        Assert.assertTrue(DAO.getInstance().saveRememberToken(token));
        final Optional<RememberToken> read = DAO.getInstance().getRememberToken(selector);
        Assert.assertTrue(read.isPresent());
        Assert.assertEquals(read.get(), token);

        Assert.assertTrue(DAO.getInstance().deleteRememberToken(selector));
        Assert.assertTrue(DAO.getInstance().getRememberToken(selector).isEmpty());
    }

    @Test
    public void deleteAllForUserOnlyTouchesThatUsersRows() {
        final Person.Id victim = Person.Id.from("rm-victim-" + System.nanoTime());
        final Person.Id bystander = Person.Id.from("rm-bystander-" + System.nanoTime());
        final String victimSel1 = RandomData.genSecureToken(9);
        final String victimSel2 = RandomData.genSecureToken(9);
        final String bystanderSel = RandomData.genSecureToken(9);
        DAO.getInstance().saveRememberToken(token(victimSel1, victim));
        DAO.getInstance().saveRememberToken(token(victimSel2, victim));
        DAO.getInstance().saveRememberToken(token(bystanderSel, bystander));

        Assert.assertEquals(DAO.getInstance().deleteRememberTokensForUser(victim), 2);

        Assert.assertTrue(DAO.getInstance().getRememberToken(victimSel1).isEmpty());
        Assert.assertTrue(DAO.getInstance().getRememberToken(victimSel2).isEmpty());
        Assert.assertTrue(DAO.getInstance().getRememberToken(bystanderSel).isPresent(),
                "another person's token must survive");
    }

    /** Persistence blowing up answers false, never throws -- a broken store must not 500 a login page. */
    @Test
    public void aBrokenStoreAnswersFalseQuietly() {
        final Persistence broken = org.mockito.Mockito.mock(Persistence.class);
        org.mockito.Mockito.when(broken.putItem(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("store down"));
        org.mockito.Mockito.when(broken.deleteItem(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("store down"));
        final RememberMeDAO dao = new RememberMeDAO(broken);

        Assert.assertFalse(dao.saveToken(token(RandomData.genSecureToken(9), Person.Id.from("x"))));
        Assert.assertFalse(dao.deleteToken("whatever"));
    }

    @Test
    public void blankAndUnknownLookupsAnswerEmpty() {
        Assert.assertTrue(DAO.getInstance().getRememberToken(null).isEmpty());
        Assert.assertTrue(DAO.getInstance().getRememberToken("").isEmpty());
        Assert.assertTrue(DAO.getInstance().getRememberToken("never-issued").isEmpty());
        Assert.assertEquals(DAO.getInstance().deleteRememberTokensForUser(null), 0);
    }
}
