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
import org.paulsens.trip.dynamo.DAO;
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
        // Resolve the DAO FIRST, while local mode is still on. This is load-bearing, not tidiness.
        //
        // DAO.inst is a JVM-wide singleton whose Persistence is chosen ONCE, at construction, from
        // FakeData.isLocal(). Flipping local mode below and letting something else touch the DAO afterwards
        // builds a PRODUCTION DAO -- and the audit trail is exactly such a thing: every send() here writes an
        // EMAIL record, DynamoAuditSink drains its queue on a background thread, and that thread calls
        // DAO.getInstance(). Get the order wrong and this test writes its fixtures ("Email 'S' sent") into
        // the real audit table, then leaves the singleton pointing at production for every test that follows
        // in the same JVM. It did exactly that on 2026-08-24; thirteen rows had to be deleted by hand.
        //
        // Touching it here latches the fake Persistence before the flip, so the singleton is already safe no
        // matter what order the classes run in -- which is why this cannot be left to whichever test happens
        // to go first.
        DAO.getInstance();
        assertPersistenceIsFake("before flipping local mode");

        final Field resolved = LocalMode.class.getDeclaredField("resolved");
        resolved.setAccessible(true);
        savedResolved = resolved.get(null);
        resolved.set(null, Boolean.FALSE);

        // And again after, because the assertion above only proves the ordering was right -- this one proves
        // the flip did not somehow rebuild it. If this ever fails, STOP: the next send writes to production.
        assertPersistenceIsFake("after flipping local mode");

        ses = Mockito.mock(SesClient.class);
        mail = new MailCommands(ses);
    }

    /**
     * Fails when the DAO singleton is holding a real {@code DynamoPersistence} -- the one state in which this
     * class's audit records leave the JVM. Checked by type rather than by asking {@code FakeData.isLocal()},
     * because the whole point here is that local mode is about to say "production" while the persistence
     * underneath must not.
     */
    private static void assertPersistenceIsFake(final String when) throws Exception {
        final Field inst = DAO.class.getDeclaredField("inst");
        inst.setAccessible(true);
        final Object dao = inst.get(null);
        Assert.assertNotNull(dao, "the DAO singleton must already be resolved " + when);
        // Read it off auditDao specifically: that is the exact object this class's records travel through,
        // so it is the one whose Persistence decides whether they leave the JVM. DAO keeps no persistence
        // field of its own -- it hands the one instance to each sub-DAO at construction.
        final Field auditDao = DAO.class.getDeclaredField("auditDao");
        auditDao.setAccessible(true);
        final Object audit = auditDao.get(dao);
        final Field persistence = audit.getClass().getDeclaredField("persistence");
        persistence.setAccessible(true);
        final String type = persistence.get(audit).getClass().getName();
        Assert.assertFalse(type.contains("DynamoPersistence"),
                "DAO persistence is " + type + " " + when + " -- this test would write audit records to the "
                        + "REAL DynamoDB audit table, and leave the singleton pointing there for the rest of "
                        + "the suite. Resolve the DAO before flipping local mode.");
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

    /**
     * The bug that stopped every internal notice on 2026-08-24: a null Reply-To must OMIT the header, not
     * send an empty one.
     *
     * <p>{@code replyToAddresses((String) null)} does not mean "unset" -- it builds a one-element list
     * holding null, and {@code hasReplyToAddresses()} then answers true, so SES is handed one blank address
     * and rejects the entire request with "Invalid email address .". Nothing about the failure names
     * Reply-To, and the notice it killed had a perfectly valid From and recipient.
     *
     * <p>Every internal-notice caller passes null here (registration moved/cancelled, new registration,
     * transaction, account created) because those setting slots have a From and a recipient but no Reply-To
     * to read. The tests above all passed a real address, which is why this went out.
     */
    @Test
    public void aNullReplyToOmitsTheHeaderRatherThanSendingAnEmptyOne() {
        Mockito.clearInvocations(ses);
        Mockito.when(ses.sendEmail(ArgumentMatchers.any(SendEmailRequest.class))).thenReturn(okResponse());

        Assert.assertNotNull(mail.send("f@example.org", "to@example.org", null, null,
                "S", "B", AuditActor.system()), "a notice with no Reply-To must still send");

        final ArgumentCaptor<SendEmailRequest> req = ArgumentCaptor.forClass(SendEmailRequest.class);
        Mockito.verify(ses).sendEmail(req.capture());
        Assert.assertTrue(req.getValue().replyToAddresses().isEmpty(),
                "a null Reply-To must produce NO addresses, not one null one");
        Assert.assertNull(req.getValue().returnPath(),
                "and no envelope sender either -- an empty returnPath fails the send the same way, and "
                        + "leaving it unset lets SES use the identity's own MAIL FROM domain");
    }

    /** An unusable Reply-To is dropped like an unusable recipient, rather than failing the whole send. */
    @Test
    public void anUnusableReplyToIsDroppedRatherThanFailingTheSend() {
        Mockito.clearInvocations(ses);
        Mockito.when(ses.sendEmail(ArgumentMatchers.any(SendEmailRequest.class))).thenReturn(okResponse());

        Assert.assertNotNull(mail.send("f@example.org", "to@example.org", null, "   ",
                "S", "B", AuditActor.system()));

        final ArgumentCaptor<SendEmailRequest> req = ArgumentCaptor.forClass(SendEmailRequest.class);
        Mockito.verify(ses).sendEmail(req.capture());
        Assert.assertTrue(req.getValue().replyToAddresses().isEmpty(), "blank Reply-To is no Reply-To");
    }

    /**
     * A blank From short-circuits like a blank recipient. SES answers a blank source with the SAME opaque
     * "Invalid email address ." as a blank Reply-To, so failing here is what makes the log say which one.
     */
    @Test
    public void aBlankFromShortCircuitsWithoutCallingSes() {
        Mockito.clearInvocations(ses);

        Assert.assertNotNull(mail.send("  ", "to@example.org", null, "r@example.org",
                "S", "B", AuditActor.system()));
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
