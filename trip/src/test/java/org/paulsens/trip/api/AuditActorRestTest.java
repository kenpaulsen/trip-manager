package org.paulsens.trip.api;

import jakarta.servlet.http.HttpSession;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The actor an API write records.
 *
 * <p>{@code AuditActor} is built from two session keys: {@code userId} and {@code loginEmail}. The browser login
 * sets them on two different pages — the email on the address step, the id after the password check — so it is
 * easy to reproduce one and forget the other when writing a login endpoint from scratch.
 *
 * <p>Forgetting {@code loginEmail} does not fail. {@code from(session)} still returns an actor, the write still
 * succeeds, and the record still carries an id, so the audit table looks populated. It is only when somebody
 * reads it that the records turn out to say nothing a human can act on — and worse, the id-only actor is
 * indistinguishable from a correct record at a glance. {@code AuditCommands.describeActor} resolves the human
 * name by EMAIL, so with the email missing every message it builds degrades to "Someone".
 *
 * <p>Hence {@link #anApiSessionYieldsAFullyKnownActor}: {@code isKnown()} is not enough, both halves must be there.
 */
public class AuditActorRestTest {

    private static final Person.Id USER = Person.Id.from("user-1");

    @Test
    public void anApiSessionYieldsAFullyKnownActor() {
        final AuditActor actor = AuditActor.from(sessionWith("ken@example.com", USER));

        Assert.assertEquals(actor.email(), "ken@example.com",
                "Without the email, every audit MESSAGE degrades to \"Someone\" even though the record looks fine.");
        Assert.assertEquals(actor.id(), "user-1");
        Assert.assertTrue(actor.isKnown());
    }

    @Test
    public void anIdWithoutAnEmailStillReportsKnown_whichIsWhyIsKnownIsNotTheAssertionToMake() {
        // Pinning the trap itself: this is exactly what a login endpoint that forgets loginEmail produces,
        // and isKnown() cheerfully returns true for it.
        final AuditActor actor = AuditActor.from(sessionWith(null, USER));

        Assert.assertTrue(actor.isKnown());
        Assert.assertNull(actor.email());
    }

    @Test
    public void noSessionYieldsAnUnknownActorRatherThanThrowing() {
        // A background thread or an expired session must not take down the operation being audited.
        final AuditActor actor = AuditActor.from(null);

        Assert.assertFalse(actor.isKnown());
        Assert.assertNull(actor.email());
        Assert.assertNull(actor.id());
    }

    @Test
    public void theSessionPathAgreesWithTheKeysTheBrowserLoginWrites() {
        // AuditActor reads "loginEmail" and "userId" literally. AuthResource must write those exact names;
        // a rename on either side silently produces the id-only actor above.
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute("loginEmail")).thenReturn("admin@example.com");
        Mockito.when(session.getAttribute("userId")).thenReturn(USER);

        final AuditActor actor = AuditActor.from(session);

        Assert.assertEquals(actor.email(), "admin@example.com");
        Assert.assertEquals(actor.id(), "user-1");
    }

    private static HttpSession sessionWith(final String email, final Person.Id id) {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute("loginEmail")).thenReturn(email);
        Mockito.when(session.getAttribute("userId")).thenReturn(id);
        return session;
    }
}
