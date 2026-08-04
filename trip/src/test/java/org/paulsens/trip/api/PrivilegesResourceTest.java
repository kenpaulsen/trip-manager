package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link PrivilegesResource}, the resource that grants access.
 *
 * <p>The single assertion that matters most: a save must go through the ACTOR-TAKING overload of
 * {@code savePrivilege}. The no-arg one resolves its actor through FacesContext, which does not exist on a
 * JAX-RS thread, so every grant made over the API would be recorded as made by nobody -- on exactly the trail
 * that answers "who was able to do this".
 */
public class PrivilegesResourceTest extends ResourceTestSupport {

    private static final Person.Id ADMIN = Person.Id.from("priv-admin");
    private static final Person.Id SUBJECT = Person.Id.from("priv-subject");

    private PrivilegeCommands commands;
    private PrivilegesResource resource;

    @BeforeMethod
    public void bindBeans() {
        commands = bindMock(PrivilegeCommands.class);
        bindMock(PersonCommands.class);
        resource = resource(new PrivilegesResource());
    }

    private Privilege privilege(final String name) {
        return new PrivilegeCommands().createPrivilege(name, "a privilege", null, List.of(SUBJECT));
    }

    @Test
    public void everyEndpointNeedsPrivilegeAdmin() {
        signedInAs(ADMIN);

        assertError(resource.list(null), 403, ApiErrors.FORBIDDEN);
        assertError(resource.get("x", null), 403, ApiErrors.FORBIDDEN);
        assertError(resource.check("x", SUBJECT.getValue(), null), 403, ApiErrors.FORBIDDEN);
        assertError(resource.save("x", CSRF_OK, null, null), 403, ApiErrors.FORBIDDEN);
        assertError(resource.add("x", SUBJECT.getValue(), CSRF_OK, null), 403, ApiErrors.FORBIDDEN);
        assertError(resource.remove("x", SUBJECT.getValue(), CSRF_OK, null), 403, ApiErrors.FORBIDDEN);
        Mockito.verifyNoInteractions(commands);
    }

    @Test
    public void mutationsNeedTheCsrfHeader() {
        signedInAsSiteAdmin(ADMIN);

        assertError(resource.save("x", null, null, null), 403, ApiErrors.CSRF);
        assertError(resource.add("x", SUBJECT.getValue(), null, null), 403, ApiErrors.CSRF);
        assertError(resource.remove("x", SUBJECT.getValue(), null, null), 403, ApiErrors.CSRF);
        Mockito.verifyNoInteractions(commands);
    }

    @Test
    public void listAnswersGlobalOrTripScopedPrivileges() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(commands.getGlobalPrivileges()).thenReturn(List.of(privilege("g")));
        Mockito.when(commands.getTripPrivileges("t1")).thenReturn(List.of(privilege("a"), privilege("b")));

        assertOk(resource.list(null));
        Assert.assertEquals(((List<?>) resource.list("  ").getEntity()).size(), 1, "Blank means global");
        Assert.assertEquals(((List<?>) resource.list("t1").getEntity()).size(), 2);
    }

    @Test
    public void getAnswers404ForMissingAndForTheNoneSentinel() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(commands.getPrivilege("gone", null)).thenReturn(null);
        Mockito.when(commands.getPrivilege("none", null)).thenReturn(Privilege.NONE);

        assertError(resource.get("gone", null), 404, ApiErrors.NOT_FOUND);
        // Privilege.NONE is a sentinel, not a record; leaking it as a 200 would invent an empty privilege.
        assertError(resource.get("none", null), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void getReturnsThePrivilegeWithItsMembership() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(commands.getPrivilege("mail", null)).thenReturn(privilege("mail"));

        final Response response = resource.get("mail", null);

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("people"), List.of(SUBJECT.getValue()));
    }

    @Test
    public void checkReportsWhetherThePersonHoldsIt() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(commands.check("mail", null, SUBJECT)).thenReturn(true);

        final Response response = resource.check("mail", SUBJECT.getValue(), null);

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("held"), true);
    }

    /** The actor assertion this resource exists for. */
    @Test
    public void aSaveIsAttributedToTheSignedInActorNotToNobody() {
        signedInAsSiteAdmin(ADMIN);
        session("loginEmail", "admin@example.com");
        final Privilege saved = privilege("mail");
        Mockito.when(commands.getOrCreate(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.anyString())).thenReturn(saved);
        Mockito.when(commands.createPrivilege(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any(), ArgumentMatchers.anyList())).thenReturn(saved);
        Mockito.when(commands.savePrivilege(ArgumentMatchers.any(Privilege.class),
                ArgumentMatchers.any(AuditActor.class))).thenReturn(true);

        assertOk(resource.save("mail", CSRF_OK, null,
                new PrivilegesResource.PrivilegeBody("desc", List.of(SUBJECT.getValue()))));

        // The overload IS the assertion: the no-arg savePrivilege would compile equally well and record nothing.
        Mockito.verify(commands).savePrivilege(ArgumentMatchers.any(Privilege.class),
                ArgumentMatchers.argThat((AuditActor actor) ->
                        "admin@example.com".equals(actor.email())));
    }

    @Test
    public void aSaveWithNoBodyKeepsTheStoredDescription() {
        signedInAsSiteAdmin(ADMIN);
        final Privilege existing = privilege("mail");
        Mockito.when(commands.getOrCreate("mail", null, "")).thenReturn(existing);
        Mockito.when(commands.createPrivilege(ArgumentMatchers.eq("mail"), ArgumentMatchers.anyString(),
                ArgumentMatchers.any(), ArgumentMatchers.anyList())).thenReturn(existing);
        Mockito.when(commands.savePrivilege(ArgumentMatchers.any(Privilege.class),
                ArgumentMatchers.any(AuditActor.class))).thenReturn(true);

        assertOk(resource.save("mail", CSRF_OK, null, null));

        Mockito.verify(commands).createPrivilege("mail", "a privilege", null, List.of());
    }

    @Test
    public void aFailedSaveIsReported() {
        signedInAsSiteAdmin(ADMIN);
        final Privilege existing = privilege("mail");
        Mockito.when(commands.getOrCreate(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.anyString())).thenReturn(existing);
        Mockito.when(commands.createPrivilege(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any(), ArgumentMatchers.anyList())).thenReturn(existing);
        Mockito.when(commands.savePrivilege(ArgumentMatchers.any(Privilege.class),
                ArgumentMatchers.any(AuditActor.class))).thenReturn(false);

        assertError(resource.save("mail", CSRF_OK, null, null), 500, ApiErrors.STORE_FAILED);
    }

    /** add() answering false means "already held", which satisfies the caller's intent: 200, not an error. */
    @Test
    public void grantingAnAlreadyHeldPrivilegeIsSuccessWithTheDistinctionReported() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(commands.getPrivilege("mail", null))
                .thenReturn(new org.paulsens.trip.model.Privilege("mail", "d", java.util.List.of(SUBJECT)));
        Mockito.when(commands.add("mail", null, SUBJECT)).thenReturn(false);

        final Response response = resource.add("mail", SUBJECT.getValue(), CSRF_OK, null);

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("granted"), false);
        Assert.assertEquals(body.get("alreadyHeld"), true);
    }

    /**
     * The lie this fixes: add() answers false both for "already held" and "no such privilege", and the
     * response mapped false to {"alreadyHeld": true} -- so granting against a privilege nobody ever created
     * told the client the person already held it. Existence is now checked first and answered as a 404.
     */
    @Test
    public void grantingAgainstANonexistentPrivilegeIs404NotAlreadyHeld() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(commands.getPrivilege("neverCreated", null))
                .thenReturn(org.paulsens.trip.model.Privilege.NONE);

        final Response response = resource.add("neverCreated", SUBJECT.getValue(), CSRF_OK, null);

        assertError(response, 404, ApiErrors.NOT_FOUND);
        Mockito.verify(commands, Mockito.never())
                .add(Mockito.anyString(), Mockito.any(), Mockito.any());
    }

    @Test
    public void grantingANewHolderReportsGranted() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(commands.getPrivilege("mail", "t1"))
                .thenReturn(new org.paulsens.trip.model.Privilege("mail", "d", java.util.List.of()));
        Mockito.when(commands.add("mail", "t1", SUBJECT)).thenReturn(true);

        final Response response = resource.add("mail", SUBJECT.getValue(), CSRF_OK, "t1");

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("granted"), true);
    }

    @Test
    public void revokingReportsWhetherAnythingWasActuallyRevoked() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(commands.remove("mail", null, SUBJECT)).thenReturn(true).thenReturn(false);

        @SuppressWarnings("unchecked")
        final Map<String, Object> first =
                (Map<String, Object>) resource.remove("mail", SUBJECT.getValue(), CSRF_OK, null).getEntity();
        @SuppressWarnings("unchecked")
        final Map<String, Object> second =
                (Map<String, Object>) resource.remove("mail", SUBJECT.getValue(), CSRF_OK, null).getEntity();

        Assert.assertEquals(first.get("revoked"), true);
        Assert.assertEquals(second.get("didNotHold"), true);
    }

    @Test
    public void theProducedTypeIsThePrivilegesMediaType() {
        Assert.assertEquals(new PrivilegesResource().versionedType(), ApiMediaTypes.PRIVILEGES_V1);
    }
}
