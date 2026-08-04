package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.MailCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link MailResource}, the one resource whose effect cannot be undone.
 *
 * <p>Everything before the send loop is blast-radius control: the recipient cap, the per-address validation,
 * and the rule that a call reports what was ACCEPTED rather than claiming delivery it cannot know about.
 */
public class MailResourceTest extends ResourceTestSupport {

    private static final Person.Id ADMIN = Person.Id.from("mail-admin");

    private MailCommands mail;
    private PersonCommands people;
    private MailResource resource;

    @BeforeMethod
    public void bindBeans() {
        mail = bindMock(MailCommands.class);
        people = bindMock(PersonCommands.class);
        resource = resource(new MailResource());
    }

    private static MailResource.SendRequest send(final List<String> to) {
        return new MailResource.SendRequest("from@x.org", to, null, null, "A subject", "A body");
    }

    private void person(final Person.Id id) {
        final Person person = new Person();
        person.setId(id);
        Mockito.when(people.getPerson(id)).thenReturn(person);
    }

    @Test
    public void bothEndpointsNeedCsrfAndEmailAdmin() {
        signedInAsSiteAdmin(ADMIN);
        assertError(resource.preview(null, null), 403, ApiErrors.CSRF);
        assertError(resource.send(null, null), 403, ApiErrors.CSRF);

        signedInAs(ADMIN);
        final MailResource ordinary = resource(new MailResource());
        assertError(ordinary.preview(CSRF_OK, new MailResource.PreviewRequest(null, "t")),
                403, ApiErrors.FORBIDDEN);
        assertError(ordinary.send(CSRF_OK, send(List.of("a@x.org"))), 403, ApiErrors.FORBIDDEN);
        Mockito.verifyNoInteractions(mail);
    }

    @Test
    public void previewNeedsATemplate() {
        signedInAsSiteAdmin(ADMIN);

        assertError(resource.preview(CSRF_OK, null), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.preview(CSRF_OK, new MailResource.PreviewRequest(null, "  ")),
                400, ApiErrors.BAD_REQUEST);
    }

    @Test
    public void previewDefaultsToTheCallerAndRendersWithoutSending() {
        signedInAsSiteAdmin(ADMIN);
        person(ADMIN);
        Mockito.when(mail.renderTemplate(ArgumentMatchers.any(), ArgumentMatchers.eq("Hi #{person.first}")))
                .thenReturn("Hi Ken");

        final Response response = resource.preview(CSRF_OK,
                new MailResource.PreviewRequest(null, "Hi #{person.first}"));

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("rendered"), "Hi Ken");
        // Preview must be side-effect free.
        Mockito.verify(mail, Mockito.never()).send(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());
    }

    @Test
    public void previewAgainstAMissingPersonIs404() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(people.getPerson(ArgumentMatchers.any())).thenReturn(new Person());

        assertError(resource.preview(CSRF_OK, new MailResource.PreviewRequest("gone-id", "t")),
                404, ApiErrors.NOT_FOUND);
    }

    /** A bad expression must surface as an error, not render as the literal text "null". */
    @Test
    public void aTemplateThatCannotRenderIs422() {
        signedInAsSiteAdmin(ADMIN);
        person(ADMIN);
        Mockito.when(mail.renderTemplate(ArgumentMatchers.any(), ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("no such property"));

        assertError(resource.preview(CSRF_OK, new MailResource.PreviewRequest(null, "#{bad}")),
                422, ApiErrors.VALIDATION_FAILED);
    }

    @Test
    public void sendValidatesTheEnvelopeBeforeAnythingGoesOut() {
        signedInAsSiteAdmin(ADMIN);

        assertError(resource.send(CSRF_OK, null), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.send(CSRF_OK, send(List.of())), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.send(CSRF_OK,
                new MailResource.SendRequest("f@x", List.of("a@x.org"), null, null, "  ", "body")),
                400, ApiErrors.BAD_REQUEST);
        assertError(resource.send(CSRF_OK,
                new MailResource.SendRequest("f@x", List.of("a@x.org"), null, null, "subject", null)),
                400, ApiErrors.BAD_REQUEST);
        Mockito.verifyNoInteractions(mail);
    }

    /** The blast-radius cap: 201 addresses is a mistake, not a bigger campaign. */
    @Test
    public void sendRefusesMoreThanTheRecipientCap() {
        signedInAsSiteAdmin(ADMIN);
        final List<String> tooMany = java.util.stream.IntStream.rangeClosed(1, 201)
                .mapToObj(i -> "person" + i + "@x.org")
                .toList();

        assertError(resource.send(CSRF_OK, send(tooMany)), 400, ApiErrors.BAD_REQUEST);
        Mockito.verifyNoInteractions(mail);
    }

    @Test
    public void sendSkipsInvalidAddressesAndReportsTheSplit() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(mail.validateEmail("good@x.org")).thenReturn("good@x.org");
        Mockito.when(mail.validateEmail("bad")).thenReturn(null);

        final Response response = resource.send(CSRF_OK, send(List.of("good@x.org", "bad")));

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("accepted"), List.of("good@x.org"));
        Assert.assertEquals(body.get("rejected"), 1);
        Mockito.verify(mail, Mockito.times(1)).send(ArgumentMatchers.any(), ArgumentMatchers.eq("good@x.org"),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(AuditActor.class));
    }

    @Test
    public void aListWithNoUsableAddressSends422AndNothingElse() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(mail.validateEmail(ArgumentMatchers.anyString())).thenReturn(null);

        assertError(resource.send(CSRF_OK, send(List.of("bad1", "bad2"))), 422, ApiErrors.VALIDATION_FAILED);
        Mockito.verify(mail, Mockito.never()).send(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(AuditActor.class));
    }

    /**
     * The actor is captured on the request thread and passed INTO the send.
     *
     * <p>MailCommands writes its audit record inside the SES completion callback, where
     * {@code AuditActor.current()} finds no FacesContext; every EMAIL record went out unattributed for a
     * period because of exactly this.
     */
    @Test
    public void theActorTravelsWithTheSendRatherThanBeingResolvedLater() {
        signedInAsSiteAdmin(ADMIN);
        session("loginEmail", "admin@example.com");
        Mockito.when(mail.validateEmail("a@x.org")).thenReturn("a@x.org");

        assertOk(resource.send(CSRF_OK, send(List.of("a@x.org"))));

        Mockito.verify(mail).send(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.argThat((AuditActor actor) -> "admin@example.com".equals(actor.email())));
    }

    @Test
    public void theProducedTypeIsTheMailMediaType() {
        Assert.assertEquals(new MailResource().versionedType(), ApiMediaTypes.MAIL_V1);
    }
}
