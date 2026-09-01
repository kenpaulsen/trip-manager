package org.paulsens.trip.security;

import java.time.Instant;
import java.util.List;
import org.mockito.Mockito;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.PassCommands;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuthToken;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link TokenService} against the local-mode fake store. What must hold: a grant only exists while the
 * feature is on, ADMIN scope is granted never escalated, refresh rotates with remember-me's grace-and-theft
 * semantics, and every revocation path kills the whole family -- refresh row AND access children.
 */
public class TokenServiceTest {

    private final TokenService service = new TokenService(enabledConfig());

    private static ConfigCommands enabledConfig() {
        final ConfigCommands config = Mockito.mock(ConfigCommands.class);
        Mockito.when(config.getBoolean(KnownSettings.API_TOKEN_ENABLED)).thenReturn(true);
        Mockito.when(config.getInt(KnownSettings.API_TOKEN_ACCESS_MINUTES, 5, 240)).thenReturn(30);
        Mockito.when(config.getInt(KnownSettings.API_TOKEN_REFRESH_DAYS, 1, 365)).thenReturn(60);
        Mockito.when(config.getInt(KnownSettings.API_TOKEN_REFRESH_ADMIN_DAYS, 1, 30)).thenReturn(7);
        return config;
    }

    /** A real local-mode login ("user*"/"user", "admin"/"admin"), so refresh's creds re-read finds them. */
    private static Creds login(final String email, final String password) {
        final Creds creds = new PassCommands().login(email, password);
        Assert.assertNotNull(creds, "local-mode login for " + email + " should work");
        return creds;
    }

    private static AuthToken row(final String presentable) {
        final String selector = SelectorTokens.parse(presentable).orElseThrow().selector();
        return DAO.getInstance().getAuthToken(selector, Cached.NO).orElse(null);
    }

    @Test
    public void aDisabledFeatureIssuesRefreshesAndRevokesNothing() {
        final TokenService off = new TokenService(Mockito.mock(ConfigCommands.class));
        Assert.assertFalse(off.enabled());
        Assert.assertNull(off.issue(login("user2", "user"), AuthToken.Scope.MEMBER, null));
        Assert.assertNull(off.refresh("anything:atall"));
        off.revoke("anything:atall");
    }

    @Test
    public void aMemberGrantWritesBothRowsAndTiesTheChildToItsParent() {
        final TokenService.Grant grant = service.issue(login("user2", "user"), AuthToken.Scope.MEMBER, "Ken's phone");

        Assert.assertNotNull(grant);
        Assert.assertEquals(grant.scope(), "member");
        Assert.assertEquals(grant.accessExpiresIn(), 30 * 60L);
        final AuthToken refresh = row(grant.refreshToken());
        final AuthToken access = row(grant.accessToken());
        Assert.assertEquals(refresh.getKind(), AuthToken.Kind.REFRESH);
        Assert.assertEquals(refresh.getScope(), AuthToken.Scope.MEMBER);
        Assert.assertEquals(refresh.getLabel(), "Ken's phone");
        Assert.assertNotNull(refresh.getRole());
        Assert.assertEquals(access.getKind(), AuthToken.Kind.ACCESS);
        Assert.assertEquals(access.getParentSelector(), refresh.getSelector(),
                "the access token must point back at the refresh token for the revocation cascade");
        final long refreshLife = refresh.getExpires() - refresh.getCreated();
        Assert.assertEquals(refreshLife, 60 * 86_400L, "member refresh lifetime should be the member setting");

        service.revoke(grant.refreshToken());
    }

    @Test
    public void adminScopeIsGrantedToAdminsAndRefusedToMembers() {
        Assert.assertNull(service.issue(login("user3", "user"), AuthToken.Scope.ADMIN, null),
                "a member asking for admin scope must be refused, not downgraded");

        final TokenService.Grant grant = service.issue(login("admin", "admin"), AuthToken.Scope.ADMIN, null);
        Assert.assertEquals(grant.scope(), "admin");
        final AuthToken refresh = row(grant.refreshToken());
        Assert.assertEquals(refresh.getExpires() - refresh.getCreated(), 7 * 86_400L,
                "an admin-scoped refresh token must get the deliberately short admin lifetime");

        service.revoke(grant.refreshToken());
    }

    @Test
    public void refreshRotatesTheValidatorUnderTheSameSelector() {
        final TokenService.Grant first = service.issue(login("user4", "user"), AuthToken.Scope.MEMBER, null);
        final TokenService.Grant second = service.refresh(first.refreshToken());

        Assert.assertNotNull(second);
        Assert.assertNotNull(second.refreshToken(), "a CURRENT refresh must answer the rotated token");
        Assert.assertNotEquals(second.refreshToken(), first.refreshToken());
        Assert.assertEquals(SelectorTokens.parse(second.refreshToken()).orElseThrow().selector(),
                SelectorTokens.parse(first.refreshToken()).orElseThrow().selector(),
                "the selector must survive rotation -- it is what makes theft detectable");
        Assert.assertNotEquals(second.accessToken(), first.accessToken());

        service.revoke(second.refreshToken());
    }

    /** The racing client: the pre-rotation token within the grace window gets access but no new refresh. */
    @Test
    public void aPreRotationRefreshWithinTheGraceWindowGetsAccessWithoutRotating() {
        final TokenService.Grant first = service.issue(login("user5", "user"), AuthToken.Scope.MEMBER, null);
        final TokenService.Grant winner = service.refresh(first.refreshToken());

        final TokenService.Grant graced = service.refresh(first.refreshToken());
        Assert.assertNotNull(graced, "the pre-rotation token must still work within the grace window");
        Assert.assertNull(graced.refreshToken(),
                "the grace path must not rotate -- the winner already holds the live token");
        Assert.assertNotNull(row(graced.accessToken()));

        Assert.assertNotNull(service.refresh(winner.refreshToken()), "the family must survive the race");
    }

    /** The theft signature: a stale validator outside the grace window burns the whole family. */
    @Test
    public void aReplayedOldRefreshBurnsTheWholeFamilyAfterTheGraceWindow() {
        final TokenService.Grant first = service.issue(login("user6", "user"), AuthToken.Scope.MEMBER, null);
        final TokenService.Grant rotated = service.refresh(first.refreshToken());

        ageLastRotation(first.refreshToken(), SelectorTokens.ROTATION_GRACE_SECONDS + 1);
        Assert.assertNull(service.refresh(first.refreshToken()),
                "a stale validator outside the grace window must be refused");

        Assert.assertNull(service.refresh(rotated.refreshToken()),
                "theft detection must burn the token for the rightful holder too");
        Assert.assertNull(row(rotated.accessToken()), "the access children must die with the family");
    }

    @Test
    public void anAccessTokenCannotPoseAsARefreshToken() {
        final TokenService.Grant grant = service.issue(login("user7", "user"), AuthToken.Scope.MEMBER, null);
        Assert.assertNull(service.refresh(grant.accessToken()),
                "the short-lived credential must never be able to renew itself");
        service.revoke(grant.refreshToken());
    }

    @Test
    public void anExpiredRefreshTokenIsRefusedAndCleanedUp() {
        final TokenService.Grant grant = service.issue(login("user8", "user"), AuthToken.Scope.MEMBER, null);
        final AuthToken refresh = row(grant.refreshToken());
        refresh.setExpires(Instant.now().getEpochSecond() - 1);
        Assert.assertTrue(DAO.getInstance().saveAuthToken(refresh));

        Assert.assertNull(service.refresh(grant.refreshToken()));
        Assert.assertNull(row(grant.refreshToken()), "the expired row should have been deleted");
    }

    /** A refresh token whose account no longer exists dies at the creds re-read. */
    @Test
    public void aTokenForADeletedAccountDiesAtRefresh() {
        final long now = Instant.now().getEpochSecond();
        final SelectorTokens.Minted minted = SelectorTokens.mint();
        final AuthToken orphan = AuthToken.builder()
                .selector(minted.selector()).validatorHash(minted.validatorHash())
                .userId(Person.Id.from("ghost")).email("ghost-nobody@example.org")
                .created(now).lastUsed(now).expires(now + 3600)
                .kind(AuthToken.Kind.REFRESH).role("user").scope(AuthToken.Scope.MEMBER)
                .build();
        Assert.assertTrue(DAO.getInstance().saveAuthToken(orphan));

        Assert.assertNull(service.refresh(minted.presentable()));
        Assert.assertNull(row(minted.presentable()), "the orphaned row should have been revoked");
    }

    @Test
    public void revokeKillsTheFamilyAndIsIdempotentAndQuietOnGarbage() {
        final TokenService.Grant grant = service.issue(login("user2", "user"), AuthToken.Scope.MEMBER, null);
        final List<AuthToken> before = DAO.getInstance().listAuthTokensForUser(row(grant.refreshToken()).getUserId());
        Assert.assertTrue(before.size() >= 2);

        service.revoke(grant.refreshToken());
        Assert.assertNull(row(grant.refreshToken()));
        Assert.assertNull(row(grant.accessToken()), "access children must die with the refresh token");

        service.revoke(grant.refreshToken());
        service.revoke("no-separator");
        service.revoke(null);
        service.revoke("unknown:validator");
    }

    /** Backdates the stored rotation timestamp so tests can cross the grace window without sleeping. */
    private static void ageLastRotation(final String presentable, final long seconds) {
        final AuthToken token = row(presentable);
        token.setRotatedAt(token.getRotatedAt() - seconds);
        Assert.assertTrue(DAO.getInstance().saveAuthToken(token));
    }
}
