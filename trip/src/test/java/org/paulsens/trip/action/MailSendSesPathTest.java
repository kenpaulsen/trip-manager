package org.paulsens.trip.action;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.dynamo.LocalMode;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

/**
 * The SES half of {@link MailCommands#send} -- everything past the local-mode guard.
 *
 * <p>The guard exists because a webtest once mailed a real inbox (see the comment in {@code send}), and the
 * whole unit suite runs local, so the request-building, attribution and error-mapping code below the guard was
 * never executed by any test. Local mode is flipped OFF here -- through {@link LocalMode}'s own resolved slot,
 * restored in a finally -- with the SES client mocked, so no mail can leave regardless.
 */
public class MailSendSesPathTest {

    private Object savedResolved;
    private SesClient ses;
    private MailCommands mail;

    @BeforeClass
    public void goProductionWithAMockedClient() throws Exception {
        final Field resolved = LocalMode.class.getDeclaredField("resolved");
        resolved.setAccessible(true);
        savedResolved = resolved.get(null);
        resolved.set(null, Boolean.FALSE);
        ses = Mockito.mock(SesClient.class);
        mail = new MailCommands(ses);
    }

    @AfterClass(alwaysRun = true)
    public void restoreLocalMode() throws Exception {
        final Field resolved = LocalMode.class.getDeclaredField("resolved");
        resolved.setAccessible(true);
        resolved.set(null, savedResolved);
    }

    private static SendEmailResponse okResponse() {
        return (SendEmailResponse) SendEmailResponse.builder().messageId("ses-99")
                .sdkHttpResponse(software.amazon.awssdk.http.SdkHttpResponse.builder().statusCode(200).build())
                .build();
    }

    @Test
    public void aSendBuildsTheRequestAndAnswersTheResponse() {
        Mockito.clearInvocations(ses);
        Mockito.when(ses.sendEmail(ArgumentMatchers.any(SendEmailRequest.class))).thenReturn(okResponse());

        final SendEmailResponse response = mail.send("  Trips <no-reply@example.org>  ",
                "a@example.org, b@example.org", "bcc@example.org", "reply@example.org",
                "Subject", "<p>Body</p>", AuditActor.system());

        Assert.assertEquals(response.messageId(), "ses-99");
        final ArgumentCaptor<SendEmailRequest> req = ArgumentCaptor.forClass(SendEmailRequest.class);
        Mockito.verify(ses).sendEmail(req.capture());
        Assert.assertEquals(req.getValue().destination().toAddresses(),
                List.of("a@example.org", "b@example.org"), "the comma list splits into individual addresses");
        Assert.assertEquals(req.getValue().destination().bccAddresses(), List.of("bcc@example.org"));
        Assert.assertEquals(req.getValue().source(), "Trips <no-reply@example.org>", "the source is trimmed");
        Assert.assertEquals(req.getValue().replyToAddresses(), List.of("reply@example.org"));
    }

    /** No usable recipient is a data problem, not a retryable failure: SES must not even be called. */
    @Test
    public void anEmptyRecipientListShortCircuitsWithoutCallingSes() {
        Mockito.clearInvocations(ses);

        final SendEmailResponse response = mail.send("f@example.org", "   ", null, "r@example.org",
                "S", "B", AuditActor.system());

        Assert.assertNotNull(response, "an empty (not null, not failed) response keeps callers simple");
        Mockito.verify(ses, Mockito.never()).sendEmail(ArgumentMatchers.any(SendEmailRequest.class));
    }

    /** An SES failure maps to a NULL response -- the signal the digest sender treats as retry-worthy. */
    @Test
    public void anSesFailureAnswersNullRatherThanThrowing() {
        Mockito.when(ses.sendEmail(ArgumentMatchers.any(SendEmailRequest.class)))
                .thenThrow(new IllegalStateException("SES rejected it"));

        Assert.assertNull(mail.send("f@example.org", "to@example.org", null, "r@example.org",
                "S", "B", AuditActor.system()));
    }

    /**
     * The virtual-threads-era attribution semantics: the explicit parameter always wins, and a null actor
     * resolves through the bound {@code RequestContext} -- the send blocks on the bound thread, so the audit
     * is written where the identity is visible. (Unbound spawns still resolve nobody; that is why the
     * explicit-actor overloads exist and why {@code TripThreads.startAs} does.)
     */
    @Test
    public void aNullActorResolvesFromTheBoundRequestContext() throws Exception {
        Mockito.when(ses.sendEmail(ArgumentMatchers.any(SendEmailRequest.class))).thenReturn(okResponse());
        final RequestContext bound = RequestContext.of(new AuditActor("bound@example.org", "b-1"));

        final String out = captureStdout(
                () -> ScopedValue.where(RequestContext.SCOPE, bound).run(this::sendWithNullActor));

        Assert.assertTrue(out.contains("bound@example.org"),
                "A null actor must resolve from the bound RequestContext, not record nobody: " + out);
    }

    private void sendWithNullActor() {
        mail.send("f@example.org", "to@example.org", null, "r@example.org", "S", "B", null);
    }

    private static String captureStdout(final Runnable action) {
        final PrintStream original = System.out;
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /**
     * The merge itself: rendering stays sequential (the EL slot is shared), the SES sends fan out one
     * StructuredTaskScope fork per recipient, and the response list keeps recipient order.
     */
    @Test
    public void sendTemplateRendersSequentiallyAndFansOutTheSends() {
        Mockito.clearInvocations(ses);
        Mockito.when(ses.sendEmail(ArgumentMatchers.any(SendEmailRequest.class))).thenReturn(okResponse());
        final com.sun.jsft.util.ELUtil el = Mockito.mock(com.sun.jsft.util.ELUtil.class);
        Mockito.when(el.eval("S")).thenReturn("S");
        Mockito.when(el.eval("B")).thenReturn("B");
        final Person one = new Person();
        one.setEmail("one@example.org");
        final Person two = new Person();
        two.setEmail("two@example.org");

        final List<SendEmailResponse> out;
        try (var elStatic = Mockito.mockStatic(com.sun.jsft.util.ELUtil.class)) {
            elStatic.when(com.sun.jsft.util.ELUtil::getInstance).thenReturn(el);
            out = new MailCommands(ses).sendTemplate("f@example.org", List.of(one, two), null,
                    "r@example.org", "S", "B", AuditActor.system());
        }

        Assert.assertEquals(out.size(), 2, "one response per usable recipient, in recipient order");
        Assert.assertEquals(out.get(0).messageId(), "ses-99");
        Mockito.verify(ses, Mockito.times(2)).sendEmail(ArgumentMatchers.any(SendEmailRequest.class));
    }

    /** Off a Faces thread the EL evaluates to null; sendTemplate must refuse loudly, not mail "null". */
    @Test
    public void sendTemplateWithoutAFacesContextFailsRatherThanMailingTheWordNull() {
        final Person person = new Person();
        person.setEmail("who@example.org");

        Assert.assertThrows(IllegalStateException.class,
                () -> mail.sendTemplate("f@example.org", List.of(person), null,
                        "r@example.org", "#{requestScope.subject}", "template-text", AuditActor.system()));
    }

    @Test
    public void sendTemplateSkipsARecipientWithNoUsableAddress() {
        Mockito.clearInvocations(ses);
        final Person noAddress = new Person();
        noAddress.setEmail("not an address");

        Assert.assertTrue(mail.sendTemplate("f@example.org", List.of(noAddress), null,
                "r@example.org", "S", "B", AuditActor.system()).isEmpty());
        Mockito.verify(ses, Mockito.never()).sendEmail(ArgumentMatchers.any(SendEmailRequest.class));
    }

    /** The documented quirk: previewTemplate off a Faces thread returns the literal text "null". */
    @Test
    public void previewTemplateOffAFacesThreadReturnsTheLiteralNull() {
        final Person person = new Person();

        Assert.assertEquals(mail.previewTemplate(person, "#{requestScope.to.first}"), "null",
                "renderTemplate exists precisely because this does not fail loudly");
    }
}
