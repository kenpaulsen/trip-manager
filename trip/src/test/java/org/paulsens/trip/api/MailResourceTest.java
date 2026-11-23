package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.MailCommands;
import org.paulsens.trip.action.OrgCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
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
        // example.com is one of local mode's verified sending domains (MailCommands.LOCAL_SENDING_DOMAINS).
        return new MailResource.SendRequest("from@example.com", to, null, null, "A subject", "A body");
    }

    private void person(final Person.Id id) {
        final Person person = new Person();
        person.setId(id);
        person.setFirst("Kenneth");
        person.setNickname("Ken");
        person.setLast("Paulsen");
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

        final Response response = resource.preview(CSRF_OK,
                new MailResource.PreviewRequest(null, "Hi {{firstName}} {{ lastName }}"));

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("rendered"), "Hi Ken Paulsen");
        Assert.assertFalse(body.containsKey("subject"), "no subject asked for, none answered");
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

    /** The subject renders too, as plain text: a subject is not HTML. */
    @Test
    public void previewRendersTheSubjectWhenOneIsSent() {
        signedInAsSiteAdmin(ADMIN);
        person(ADMIN);

        final Response response = resource.preview(CSRF_OK,
                new MailResource.PreviewRequest(null, "<p>{{legalFirstName}}</p>", "For {{fullName}} & co", null));

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("rendered"), "<p>Kenneth</p>");
        Assert.assertEquals(body.get("subject"), "For Ken Paulsen & co");
    }

    /**
     * medjugorje#79: the template was EL, so an org-scoped emailAdmin could run code on the server. It is never
     * evaluated now, and text still holding an expression is refused rather than rendered or mailed raw.
     */
    @Test
    public void anExpressionIsRefusedNeverEvaluated() {
        signedInAsSiteAdmin(ADMIN);
        person(ADMIN);

        for (final String template : List.of("#{Runtime.getRuntime().availableProcessors()}", "${7*7}",
                "Hi #{to.first}")) {
            final Response response = resource.preview(CSRF_OK, new MailResource.PreviewRequest(null, template));
            assertError(response, 422, ApiErrors.VALIDATION_FAILED);
        }
        Mockito.verifyNoInteractions(mail);
    }

    @Test
    public void anUnknownFieldOrATripFieldWithoutATripIs422() {
        signedInAsSiteAdmin(ADMIN);
        person(ADMIN);

        assertError(resource.preview(CSRF_OK, new MailResource.PreviewRequest(null, "{{noSuchField}}")),
                422, ApiErrors.VALIDATION_FAILED);
        assertError(resource.preview(CSRF_OK, new MailResource.PreviewRequest(null, "Room {{room}}")),
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

    /**
     * Audit 2026-11-11, F9: every organization's domain is verified in the one SES account, so SES accepts
     * any of them from anybody. The page refuses a From outside the caller's sending domains
     * ({@code OrgCommands.sendMerge}); this door forwarded whatever it was given.
     */
    @Test
    public void aFromOutsideTheCallersSendingDomainsIs422AndNothingIsSent() throws java.io.IOException {
        // An org narrowed to one domain, and its email admin: the other verified domains are other tenants'.
        final Organization narrowed = new Organization(null,
                "Mail org " + System.nanoTime(), null, null, null, null, null, 0L);
        narrowed.setMailDomains(List.of("example.com"));
        Assert.assertTrue(DAO.getInstance().saveOrganization(narrowed));
        final Person.Id emailAdmin = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.getOrCreate("emailAdmin", narrowed.getId().getValue(), "mail")
                .withNewPerson(emailAdmin), null));
        signedInAs(emailAdmin);
        final MailResource scoped = resource(new MailResource());

        for (final String forged : List.of("Treasurer <treasurer@unitetrip.com>", "x@not-verified.example",
                "no-at-sign", " ")) {
            final Response refused = scoped.send(CSRF_OK, new MailResource.SendRequest(forged,
                    List.of("a@x.org"), null, null, "A subject", "A body"));
            assertError(refused, 422, ApiErrors.VALIDATION_FAILED);
            Assert.assertEquals(((Map<?, ?>) refused.getEntity()).get("message"),
                    "The From address must use one of your verified sending domains.",
                    "the page's wording");
        }
        assertError(scoped.send(CSRF_OK, new MailResource.SendRequest(null, List.of("a@x.org"), null, null,
                "A subject", "A body")), 422, ApiErrors.VALIDATION_FAILED);
        Mockito.verifyNoInteractions(mail);

        // Their own domain passes the guard (and then finds nobody it may mail, which is a different refusal).
        Mockito.when(mail.validateEmail("a@x.org")).thenReturn("a@x.org");
        final Response own = scoped.send(CSRF_OK, send(List.of("a@x.org")));
        Assert.assertEquals(((Map<?, ?>) own.getEntity()).get("message"), "No usable recipient addresses.");
    }

    /**
     * The address that passes the organization bound is the address that is mailed. An entry such as
     * "outsider,&lt;member&gt;" has a bare address inside its angle brackets that is in bounds, but mailing the
     * whole entry would split it on the comma and reach the outsider (review of 281a02cf, F1).
     */
    @Test
    public void onlyTheBareAddressThatWasCheckedIsMailed() throws java.io.IOException {
        final Organization bounded = new Organization(null, "Bound org " + System.nanoTime(), null, null, null, null,
                null, 0L);
        bounded.setMailDomains(List.of("example.com"));
        Assert.assertTrue(DAO.getInstance().saveOrganization(bounded));
        final String orgId = bounded.getId().getValue();
        final Person member = Person.builder().first("In").last("Bounds")
                .email("member." + System.nanoTime() + "@example.org").build();
        Assert.assertTrue(DAO.getInstance().savePerson(member));
        final org.paulsens.trip.action.Caller siteAdmin = new org.paulsens.trip.action.Caller(
                Person.Id.newInstance(), true, AuditActor.from(null), new PrivilegeCommands());
        Assert.assertTrue(new OrgCommands(() -> siteAdmin).addMember(orgId, member.getId()));
        final Person.Id emailAdmin = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.getOrCreate("emailAdmin", orgId, "mail")
                .withNewPerson(emailAdmin), null));
        signedInAs(emailAdmin);
        Mockito.when(mail.validateEmail(ArgumentMatchers.anyString()))
                .thenAnswer(call -> org.paulsens.trip.util.EmailAddresses.normalize(call.getArgument(0)));

        final Response response = resource(new MailResource()).send(CSRF_OK, new MailResource.SendRequest(
                "no-reply@example.com", List.of("outsider@evil.example,<" + member.getEmail() + ">",
                        "<outsider@evil.example,<" + member.getEmail() + ">>"),
                "outsider@evil.example,<" + member.getEmail() + ">", null, "A subject", "A body"));

        assertOk(response);
        // The split is decided on the request thread, so this holds whatever the send threads have done so far.
        @SuppressWarnings("unchecked")
        final Map<String, Object> split = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(split.get("accepted"), List.of(member.getEmail()), "the bare entry's member only");
        Assert.assertEquals(split.get("rejected"), 1, "the nested entry names more than one address");
        Mockito.verify(mail, Mockito.timeout(5000)).send(ArgumentMatchers.eq("no-reply@example.com"),
                ArgumentMatchers.eq(member.getEmail()), ArgumentMatchers.eq(member.getEmail()), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.verify(mail, Mockito.never()).send(ArgumentMatchers.any(),
                ArgumentMatchers.contains("outsider"), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
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
        // timeout(): the sends run on spawned virtual threads after the response is already built.
        Mockito.verify(mail, Mockito.timeout(5000).times(1)).send(
                ArgumentMatchers.any(), ArgumentMatchers.eq("good@x.org"),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(AuditActor.class));
    }

    /** The page's mail allowlist covers this door too: it mailed a body as typed (review, 2026-11-24). */
    @Test
    public void theBodyIsMailedAsTheAllowlistCleansItAndTheAnswerSaysSo() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(mail.validateEmail("good@x.org")).thenReturn("good@x.org");
        final Response response = resource.send(CSRF_OK, new MailResource.SendRequest("from@example.com",
                List.of("good@x.org"), null, null, "A subject",
                "<p>Hello</p><iframe src=\"https://x.org/\"></iframe><script>x()</script>"));
        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> answer = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(answer.get("formattingRemoved"), true);
        final ArgumentCaptor<String> mailed = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mail, Mockito.timeout(5000)).send(ArgumentMatchers.any(), ArgumentMatchers.eq("good@x.org"),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), mailed.capture(),
                ArgumentMatchers.any(AuditActor.class));
        Assert.assertTrue(mailed.getValue().contains("Hello"), mailed.getValue());
        Assert.assertFalse(mailed.getValue().contains("iframe") || mailed.getValue().contains("script"),
                mailed.getValue());

        assertError(resource.send(CSRF_OK, new MailResource.SendRequest("from@example.com", List.of("good@x.org"),
                null, null, "A subject", "<video src=\"https://x.org/v.mp4\"></video>")), 422,
                ApiErrors.VALIDATION_FAILED);
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
     * <p>The sends outlive the request on their own virtual threads, where nothing is bound and
     * {@code AuditActor.current()} finds nobody; every EMAIL record went out unattributed for a period
     * because attribution was left to the sending thread.
     */
    @Test
    public void theActorTravelsWithTheSendRatherThanBeingResolvedLater() {
        signedInAsSiteAdmin(ADMIN);
        session("loginEmail", "admin@example.com");
        Mockito.when(mail.validateEmail("a@x.org")).thenReturn("a@x.org");

        assertOk(resource.send(CSRF_OK, send(List.of("a@x.org"))));

        Mockito.verify(mail, Mockito.timeout(5000)).send(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.argThat((AuditActor actor) -> "admin@example.com".equals(actor.email())));
    }

    @Test
    public void theProducedTypeIsTheMailMediaType() {
        Assert.assertEquals(new MailResource().versionedType(), ApiMediaTypes.MAIL_V1);
    }
}
