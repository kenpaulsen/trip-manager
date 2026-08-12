package org.paulsens.trip.action;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.web.Sessions;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link LoginCodeCommands} against local-mode fake data, where the code is always
 * {@link LoginCodeCommands#LOCAL_MODE_CODE} and personas {@code admin}/{@code user2} exist.
 *
 * <p>The properties worth pinning are the refusals and the silences: an unknown address gets the same answer
 * as a known one (and no mail), a burned or over-guessed code stops working, and a broken cache degrades to
 * "no code sent" rather than an error the form can relay.
 */
public class LoginCodeCommandsTest {

    private InMemoryCacheClient cache;
    private MailCommands mail;
    private LoginCodeCommands commands;

    @BeforeClass
    public void reseed() {
        // In local mode the shared cache IS the people store, and several suite classes call
        // clearAllCaches() without reseeding -- whichever class follows inherits a world where user2
        // does not exist and requestCode silently sends nothing (the CI-order failures of 2026-08-12;
        // new test files shifted the class order). The suite convention: need seeds, re-seed yourself.
        org.paulsens.trip.dynamo.FakeData.addFakeData();
    }

    @BeforeMethod
    public void fresh() {
        cache = new InMemoryCacheClient();
        mail = Mockito.mock(MailCommands.class);
        commands = new LoginCodeCommands(cache, new ConfigCommands(), mail);
    }

    @Test
    public void aRequestedCodeSignsTheUserInOnce() {
        Assert.assertTrue(commands.requestCode("user2"));
        Mockito.verify(mail).send(ArgumentMatchers.anyString(), ArgumentMatchers.eq("user2"),
                ArgumentMatchers.isNull(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.contains(LoginCodeCommands.LOCAL_MODE_CODE), ArgumentMatchers.any());

        final Map<String, Object> attrs = new HashMap<>();
        final Creds creds = commands.verifyAndLogin("user2", LoginCodeCommands.LOCAL_MODE_CODE,
                requestWith(attrs), null);
        Assert.assertNotNull(creds);
        Assert.assertEquals(attrs.get(PersonCommands.ACTIVE_USER_ID), creds.getUserId());
        Assert.assertEquals(attrs.get(Sessions.LOGIN_EMAIL), "user2");
        Assert.assertEquals(attrs.get(Sessions.CODE_LOGIN), Boolean.TRUE);

        // Single-use: the same (correct) code is dead the moment it works.
        Assert.assertNull(commands.verifyAndLogin("user2", LoginCodeCommands.LOCAL_MODE_CODE,
                requestWith(new HashMap<>()), null));
    }

    @Test
    public void aWrongCodeFailsAndTheRightOneStillWorks() {
        commands.requestCode("user2");
        Assert.assertNull(commands.verifyAndLogin("user2", "000000", requestWith(new HashMap<>()), null));
        Assert.assertNotNull(commands.verifyAndLogin("user2", LoginCodeCommands.LOCAL_MODE_CODE,
                requestWith(new HashMap<>()), null));
    }

    @Test
    public void tooManyWrongGuessesBurnTheCode() {
        commands.requestCode("user2");
        final int maxAttempts = new ConfigCommands().getInt(KnownSettings.LOGIN_CODE_MAX_ATTEMPTS, 3, 10);
        for (int guess = 0; guess < maxAttempts; guess++) {
            Assert.assertNull(commands.verifyAndLogin("user2", "999999", requestWith(new HashMap<>()), null));
        }
        // The budget is spent, so even the RIGHT code is refused now.
        Assert.assertNull(commands.verifyAndLogin("user2", LoginCodeCommands.LOCAL_MODE_CODE,
                requestWith(new HashMap<>()), null));
    }

    @Test
    public void aFreshCodeResetsTheGuessBudget() {
        commands.requestCode("user2");
        for (int guess = 0; guess < 10; guess++) {
            commands.verifyAndLogin("user2", "999999", requestWith(new HashMap<>()), null);
        }
        commands.requestCode("user2");
        Assert.assertNotNull(commands.verifyAndLogin("user2", LoginCodeCommands.LOCAL_MODE_CODE,
                requestWith(new HashMap<>()), null));
    }

    @Test
    public void anUnknownAddressGetsTheSameAnswerAndNoMail() {
        Assert.assertTrue(commands.requestCode("nobody-here@example.org"));
        Mockito.verifyNoInteractions(mail);
        Assert.assertNull(commands.verifyAndLogin("nobody-here@example.org", LoginCodeCommands.LOCAL_MODE_CODE,
                requestWith(new HashMap<>()), null));
    }

    @Test
    public void theSendCapStopsTheMailButNotTheAnswer() {
        final ConfigCommands config = new ConfigCommands();
        final int maxSends = config.getInt(KnownSettings.LOGIN_CODE_MAX_SENDS, 1, 10);
        for (int sends = 0; sends < maxSends + 3; sends++) {
            Assert.assertTrue(commands.requestCode("user2"));
        }
        Mockito.verify(mail, Mockito.times(maxSends)).send(ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.isNull(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    @Test
    public void aDisabledFeatureDoesNothingAtAll() {
        final ConfigCommands config = Mockito.mock(ConfigCommands.class);
        Mockito.when(config.getBoolean(KnownSettings.LOGIN_CODE_ENABLED)).thenReturn(false);
        final LoginCodeCommands disabled = new LoginCodeCommands(cache, config, mail);

        Assert.assertTrue(disabled.requestCode("user2"));
        Assert.assertNull(disabled.verifyAndLogin("user2", LoginCodeCommands.LOCAL_MODE_CODE,
                requestWith(new HashMap<>()), null));
        Mockito.verifyNoInteractions(mail);
    }

    @Test
    public void aBrokenCacheMeansNoMailAndTheSameAnswer() {
        final CacheClient broken = Mockito.mock(CacheClient.class);
        Mockito.when(broken.increment(ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(),
                ArgumentMatchers.any(Duration.class))).thenReturn(java.util.Optional.empty());
        Mockito.when(broken.putValue(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any(Duration.class))).thenReturn(false);
        final LoginCodeCommands down = new LoginCodeCommands(broken, new ConfigCommands(), mail);

        Assert.assertTrue(down.requestCode("user2"));
        Mockito.verifyNoInteractions(mail);
    }

    @Test
    public void blankInputsAreRefusedQuietly() {
        Assert.assertTrue(commands.requestCode("  "));
        Mockito.verifyNoInteractions(mail);
        Assert.assertNull(commands.verifyAndLogin(" ", "123456", requestWith(new HashMap<>()), null));
        Assert.assertNull(commands.verifyAndLogin("user2", "  ", requestWith(new HashMap<>()), null));
    }

    /**
     * A person who exists but has never had credentials -- an admin-imported pilgrim -- can sign in with a
     * code, and doing so creates their (randomly-seeded) credentials on the spot. Before this, their only way
     * in was asking for help.
     */
    @Test
    public void aPersonWithoutCredsGetsThemByProvingTheirInbox() {
        final PersonCommands people = new PersonCommands();
        final org.paulsens.trip.model.Person person = people.createPerson();
        person.setFirst("Imported");
        person.setLast("Pilgrim");
        person.setEmail("user-imported-" + System.nanoTime() + "@example.org");
        Assert.assertTrue(people.savePerson(person));
        waitForEmailIndex(person.getEmail());

        Assert.assertTrue(commands.requestCode(person.getEmail()));
        final Creds creds = commands.verifyAndLogin(person.getEmail(), LoginCodeCommands.LOCAL_MODE_CODE,
                requestWith(new HashMap<>()), null);
        Assert.assertNotNull(creds, "verifying the code should have created credentials");
        Assert.assertEquals(creds.getUserId(), person.getId());
    }

    /** The JSF entry point resolves the request off the FacesContext; otherwise identical to the REST path. */
    @Test
    public void theJsfOverloadFindsTheRequestItself() {
        commands.requestCode("user2");
        final Map<String, Object> attrs = new HashMap<>();
        final HttpServletRequest request = requestWith(attrs);
        try (org.mockito.MockedStatic<jakarta.faces.context.FacesContext> faces =
                Mockito.mockStatic(jakarta.faces.context.FacesContext.class)) {
            final jakarta.faces.context.FacesContext ctx =
                    Mockito.mock(jakarta.faces.context.FacesContext.class);
            final jakarta.faces.context.ExternalContext ext =
                    Mockito.mock(jakarta.faces.context.ExternalContext.class);
            faces.when(jakarta.faces.context.FacesContext::getCurrentInstance).thenReturn(ctx);
            Mockito.when(ctx.getExternalContext()).thenReturn(ext);
            Mockito.when(ext.getRequest()).thenReturn(request);

            Assert.assertNotNull(commands.verifyAndLogin("user2", LoginCodeCommands.LOCAL_MODE_CODE));
        }
        Assert.assertEquals(attrs.get(Sessions.CODE_LOGIN), Boolean.TRUE);
    }

    /** The CDI constructor wires the local cache/config/mail; local mode makes that safe to touch here. */
    @Test
    public void theDefaultConstructorWiresItself() {
        Assert.assertTrue(new LoginCodeCommands().requestCode("  "));
    }

    private static void waitForEmailIndex(final String email) {
        // The email index is written asynchronously behind savePerson; the code flow resolves the person BY
        // EMAIL, so wait for the mapping rather than racing it (same idiom as PassCommandsTailsTest).
        final long deadline = System.currentTimeMillis() + 5_000;
        try {
            while (org.paulsens.trip.dynamo.DAO.getInstance().getPersonByEmail(email) == null) {
                Assert.assertTrue(System.currentTimeMillis() < deadline, "email mapping never appeared");
                Thread.sleep(20);
            }
        } catch (final InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static HttpServletRequest requestWith(final Map<String, Object> attrs) {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(ArgumentMatchers.anyString()))
                .thenAnswer(call -> attrs.get(call.<String>getArgument(0)));
        Mockito.doAnswer(call -> attrs.put(call.getArgument(0), call.getArgument(1)))
                .when(session).setAttribute(ArgumentMatchers.anyString(), ArgumentMatchers.any());
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getSession(true)).thenReturn(session);
        return request;
    }
}
