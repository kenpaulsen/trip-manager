package org.paulsens.trip.action;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.LocalMode;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.ses.SesAsyncClient;
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
    private SesAsyncClient ses;
    private MailCommands mail;

    @BeforeClass
    public void goProductionWithAMockedClient() throws Exception {
        final Field resolved = LocalMode.class.getDeclaredField("resolved");
        resolved.setAccessible(true);
        savedResolved = resolved.get(null);
        resolved.set(null, Boolean.FALSE);
        ses = Mockito.mock(SesAsyncClient.class);
        mail = new MailCommands(ses);
    }

    @AfterClass(alwaysRun = true)
    public void restoreLocalMode() throws Exception {
        final Field resolved = LocalMode.class.getDeclaredField("resolved");
        resolved.setAccessible(true);
        resolved.set(null, savedResolved);
    }

    @Test
    public void aSendBuildsTheRequestAndAnswersTheResponse() {
        final SendEmailResponse ok = (SendEmailResponse) SendEmailResponse.builder().messageId("ses-99")
                .sdkHttpResponse(software.amazon.awssdk.http.SdkHttpResponse.builder().statusCode(200).build())
                .build();
        Mockito.when(ses.sendEmail(ArgumentMatchers.any(SendEmailRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(ok));

        final SendEmailResponse response = mail.send("  Trips <no-reply@example.org>  ",
                "a@example.org, b@example.org", "bcc@example.org", "reply@example.org",
                "Subject", "<p>Body</p>", AuditActor.system()).join();

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
                "S", "B", AuditActor.system()).join();

        Assert.assertNotNull(response, "an empty (not null, not failed) response keeps callers simple");
        Mockito.verify(ses, Mockito.never()).sendEmail(ArgumentMatchers.any(SendEmailRequest.class));
    }

    /** An SES failure maps to a NULL response -- the signal the digest sender treats as retry-worthy. */
    @Test
    public void anSesFailureAnswersNullRatherThanThrowing() {
        Mockito.when(ses.sendEmail(ArgumentMatchers.any(SendEmailRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("SES rejected it")));

        Assert.assertNull(mail.send("f@example.org", "to@example.org", null, "r@example.org",
                "S", "B", AuditActor.system()).join());
    }

    /** Off a Faces thread the EL evaluates to null; sendTemplate must refuse loudly, not mail "null". */
    @Test
    public void sendTemplateWithoutAFacesContextFailsRatherThanMailingTheWordNull() {
        final Person person = new Person();
        person.setEmail("who@example.org");

        Assert.assertThrows(() -> mail.sendTemplate("f@example.org", List.of(person), null,
                "r@example.org", "#{requestScope.subject}", "template-text", AuditActor.system()).join());
    }

    @Test
    public void sendTemplateSkipsARecipientWithNoUsableAddress() {
        Mockito.clearInvocations(ses);
        final Person noAddress = new Person();
        noAddress.setEmail("not an address");

        Assert.assertTrue(mail.sendTemplate("f@example.org", List.of(noAddress), null,
                "r@example.org", "S", "B", AuditActor.system()).join().isEmpty());
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
