package org.paulsens.trip.dynamo;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.model.AuthToken;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * {@link AuthTokenDAO} round-trips against the in-memory fake store (which is the real local-mode store),
 * including the lazy {@code remember_me} migration path.
 */
public class AuthTokenDAOTest {

    private static AuthToken token(final String selector, final Person.Id who) {
        final long now = Instant.now().getEpochSecond();
        return AuthToken.builder()
                .selector(selector)
                .validatorHash("hash-" + selector)
                .prevValidatorHash("prev-" + selector)
                .rotatedAt(now - 5)
                .userId(who)
                .email("who@example.org")
                .created(now)
                .lastUsed(now)
                .expires(now + 3600)
                .kind(AuthToken.Kind.REMEMBER)
                .build();
    }

    /** Rows written before the rotation-grace columns existed have neither; they must read back as null. */
    @Test
    public void aRowWithoutGraceColumnsRoundTripsWithNulls() {
        final String selector = RandomData.genSecureToken(9);
        final long now = Instant.now().getEpochSecond();
        final AuthToken legacy = AuthToken.builder()
                .selector(selector).validatorHash("hash").userId(Person.Id.from("at-legacy"))
                .email("who@example.org").created(now).lastUsed(now).expires(now + 3600)
                .kind(AuthToken.Kind.REMEMBER)
                .build();

        Assert.assertTrue(DAO.getInstance().saveAuthToken(legacy));
        final AuthToken read = DAO.getInstance().getAuthToken(selector, Cached.NO).orElseThrow();
        Assert.assertNull(read.getPrevValidatorHash());
        Assert.assertNull(read.getRotatedAt());
        Assert.assertEquals(read, legacy);
        DAO.getInstance().deleteAuthToken(selector);
    }

    @Test
    public void saveGetDeleteRoundTrip() {
        final String selector = RandomData.genSecureToken(9);
        final AuthToken token = token(selector, Person.Id.from("at-round-trip"));

        Assert.assertTrue(DAO.getInstance().saveAuthToken(token));
        final Optional<AuthToken> read = DAO.getInstance().getAuthToken(selector, Cached.NO);
        Assert.assertTrue(read.isPresent());
        Assert.assertEquals(read.get(), token);

        Assert.assertTrue(DAO.getInstance().deleteAuthToken(selector));
        Assert.assertTrue(DAO.getInstance().getAuthToken(selector, Cached.NO).isEmpty());
    }

    /** The bearer-token fields are newer than the cookie fields; every one must survive a round trip. */
    @Test
    public void bearerTokenFieldsRoundTrip() {
        final String selector = RandomData.genSecureToken(9);
        final long now = Instant.now().getEpochSecond();
        final AuthToken access = AuthToken.builder()
                .selector(selector).validatorHash("hash").userId(Person.Id.from("at-access"))
                .email("who@example.org").created(now).lastUsed(now).expires(now + 1800)
                .kind(AuthToken.Kind.ACCESS).role("user").scope(AuthToken.Scope.MEMBER)
                .label("Ken's phone").parentSelector("parent123")
                .build();

        Assert.assertTrue(DAO.getInstance().saveAuthToken(access));
        final AuthToken read = DAO.getInstance().getAuthToken(selector, Cached.NO).orElseThrow();
        Assert.assertEquals(read, access);
        Assert.assertEquals(read.getKind(), AuthToken.Kind.ACCESS);
        Assert.assertEquals(read.getScope(), AuthToken.Scope.MEMBER);
        Assert.assertEquals(read.getParentSelector(), "parent123");
        DAO.getInstance().deleteAuthToken(selector);
    }

    @Test
    public void deleteAllForUserOnlyTouchesThatUsersRowsAndReturnsThem() {
        final Person.Id victim = Person.Id.from("at-victim-" + System.nanoTime());
        final Person.Id bystander = Person.Id.from("at-bystander-" + System.nanoTime());
        final String victimSel1 = RandomData.genSecureToken(9);
        final String victimSel2 = RandomData.genSecureToken(9);
        final String bystanderSel = RandomData.genSecureToken(9);
        DAO.getInstance().saveAuthToken(token(victimSel1, victim));
        DAO.getInstance().saveAuthToken(token(victimSel2, victim));
        DAO.getInstance().saveAuthToken(token(bystanderSel, bystander));

        final List<AuthToken> deleted = DAO.getInstance().deleteAuthTokensForUser(victim);
        Assert.assertEquals(deleted.size(), 2, "the deleted ROWS come back -- callers purge caches by selector");
        Assert.assertTrue(deleted.stream().allMatch(t -> victim.equals(t.getUserId())));

        Assert.assertTrue(DAO.getInstance().getAuthToken(victimSel1, Cached.NO).isEmpty());
        Assert.assertTrue(DAO.getInstance().getAuthToken(victimSel2, Cached.NO).isEmpty());
        Assert.assertTrue(DAO.getInstance().getAuthToken(bystanderSel, Cached.NO).isPresent(),
                "another person's token must survive");
    }

    @Test
    public void listForUserAnswersEveryLiveRowForThatUser() {
        final Person.Id who = Person.Id.from("at-list-" + System.nanoTime());
        final String sel1 = RandomData.genSecureToken(9);
        final String sel2 = RandomData.genSecureToken(9);
        DAO.getInstance().saveAuthToken(token(sel1, who));
        DAO.getInstance().saveAuthToken(token(sel2, who));

        final List<AuthToken> listed = DAO.getInstance().listAuthTokensForUser(who);
        Assert.assertEquals(listed.size(), 2);
        Assert.assertTrue(DAO.getInstance().listAuthTokensForUser(null).isEmpty());
        DAO.getInstance().deleteAuthToken(sel1);
        DAO.getInstance().deleteAuthToken(sel2);
    }

    /** Persistence blowing up answers false, never throws -- a broken store must not 500 a login page. */
    @Test
    public void aBrokenStoreAnswersFalseQuietly() {
        final Persistence broken = org.mockito.Mockito.mock(Persistence.class);
        org.mockito.Mockito.when(broken.putItem(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("store down"));
        org.mockito.Mockito.when(broken.deleteItem(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("store down"));
        final AuthTokenDAO dao = new AuthTokenDAO(broken);

        Assert.assertFalse(dao.saveToken(token(RandomData.genSecureToken(9), Person.Id.from("x"))));
        Assert.assertFalse(dao.deleteToken("whatever"));
    }

    @Test
    public void blankAndUnknownLookupsAnswerEmpty() {
        Assert.assertTrue(DAO.getInstance().getAuthToken(null, Cached.NO).isEmpty());
        Assert.assertTrue(DAO.getInstance().getAuthToken("", Cached.NO).isEmpty());
        Assert.assertTrue(DAO.getInstance().getAuthToken("never-issued", Cached.NO).isEmpty());
        Assert.assertTrue(DAO.getInstance().deleteAuthTokensForUser(null).isEmpty());
    }

    // ---- Lazy migration off the pre-rename remember_me table ----

    /** Plants a row in the OLD table exactly as the pre-rename code wrote it: no kind column at all. */
    private static void plantLegacyRow(final Persistence persistence, final String selector, final Person.Id who) {
        final long now = Instant.now().getEpochSecond();
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(AuthTokenDAO.SELECTOR, AttributeValue.builder().s(selector).build());
        item.put(AuthTokenDAO.VALIDATOR_HASH, AttributeValue.builder().s("legacy-hash").build());
        item.put(AuthTokenDAO.USER_ID, AttributeValue.builder().s(who.getValue()).build());
        item.put(AuthTokenDAO.EMAIL, AttributeValue.builder().s("who@example.org").build());
        item.put(AuthTokenDAO.CREATED, AttributeValue.builder().n(String.valueOf(now)).build());
        item.put(AuthTokenDAO.LAST_USED, AttributeValue.builder().n(String.valueOf(now)).build());
        item.put(AuthTokenDAO.EXPIRES, AttributeValue.builder().n(String.valueOf(now + 3600)).build());
        persistence.putItem(b -> b.tableName(AuthTokenDAO.LEGACY_REMEMBER_TABLE).item(item));
    }

    private static boolean inTable(final Persistence persistence, final String table, final String selector) {
        final var response = persistence.getItem(b -> b.tableName(table)
                .key(Map.of(AuthTokenDAO.SELECTOR, AttributeValue.builder().s(selector).build())).build());
        return response.hasItem() && !response.item().isEmpty();
    }

    @Test
    public void aLegacyRowIsFoundCopiedForwardAndDrained() {
        final InMemoryPersistence persistence = new InMemoryPersistence();
        final AuthTokenDAO dao = new AuthTokenDAO(persistence);
        final String selector = RandomData.genSecureToken(9);
        plantLegacyRow(persistence, selector, Person.Id.from("at-migrate"));

        final AuthToken migrated = dao.getToken(selector).orElseThrow();
        Assert.assertEquals(migrated.getKind(), AuthToken.Kind.REMEMBER,
                "a pre-rename row has no kind column and every one of them is a remember-me cookie");
        Assert.assertEquals(migrated.getValidatorHash(), "legacy-hash");

        Assert.assertTrue(inTable(persistence, AuthTokenDAO.AUTH_TOKENS_TABLE, selector),
                "the row must have been copied forward");
        Assert.assertFalse(inTable(persistence, AuthTokenDAO.LEGACY_REMEMBER_TABLE, selector),
                "the old table must drain as rows are read");
        // And the next read comes straight from the new table.
        Assert.assertTrue(dao.getToken(selector).isPresent());
    }

    /** Revocation must kill rows the lazy migration has not reached yet, or a password change misses them. */
    @Test
    public void deleteAllForUserAndListForUserCoverTheLegacyTable() {
        final InMemoryPersistence persistence = new InMemoryPersistence();
        final AuthTokenDAO dao = new AuthTokenDAO(persistence);
        final Person.Id who = Person.Id.from("at-legacy-revoke");
        final String legacySel = RandomData.genSecureToken(9);
        final String currentSel = RandomData.genSecureToken(9);
        plantLegacyRow(persistence, legacySel, who);
        dao.saveToken(token(currentSel, who));

        Assert.assertEquals(dao.listForUser(who).size(), 2, "both tables must be listed");
        final List<AuthToken> deleted = dao.deleteAllForUser(who);
        Assert.assertEquals(deleted.size(), 2, "both tables must be swept");
        Assert.assertFalse(inTable(persistence, AuthTokenDAO.LEGACY_REMEMBER_TABLE, legacySel));
        Assert.assertFalse(inTable(persistence, AuthTokenDAO.AUTH_TOKENS_TABLE, currentSel));
    }
}
