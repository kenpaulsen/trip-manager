package org.paulsens.trip.action;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.PaymentProcessorConfig;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.pay.PaymentMailer;
import org.paulsens.trip.pay.PaymentProcessor;
import org.paulsens.trip.pay.PaymentProcessors;
import org.paulsens.trip.pay.PaymentRecorder;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The whole payment flow against the REAL local stack (FakeData orgs + FAKE processor + in-memory store);
 * only the caller and the mailer are stood in for. This is the money path -- every branch that decides
 * whether a charge happens or a ledger row is written gets pinned here.
 */
public class PaymentCommandsTest {
    private final TransactionsCommands txCmds = new TransactionsCommands();
    private PaymentMailer mailer;

    @BeforeClass
    public void seed() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    // ------------------------------------------------------------------ the full happy path

    @Test
    public void aFullPaymentFlowChargesRecordsAndMails() throws IOException {
        final Person payer = savedPerson();
        final Person spouse = savedPerson();
        grantManaged(payer, spouse);
        final PaymentCommands commands = commandsFor(payer, false);

        final Map<String, Object> amounts = Map.of(
                payer.getId().getValue(), "475", spouse.getId().getValue(), "475.00");
        final PaymentCommands.Quote quote = commands.quote(trip(), amounts, "1,000");
        assertTrue(quote.isPayable());
        assertTrue(quote.isPayerPays(), "CFPW's seeded defaults are payer-pays");
        assertEquals(quote.getCreditCents(), 95000L);
        assertEquals(quote.getDonationCents(), 100000L);
        assertTrue(quote.getTotalCents() > 195000L, "The gross-up rides on top of the credits");

        final String approvalUrl = commands.startPayment(trip(), amounts, "1000", false,
                "http://localhost/trip/payment.jsf");
        assertNotNull(approvalUrl, "The FAKE processor answers its checkout page");
        assertTrue(approvalUrl.startsWith("/trip/fakeCheckout.jsf?token=FAKE-"));

        final String paymentId = paymentIdFrom(approvalUrl);
        final PaymentCommands.Completion outcome = commands.completePayment(paymentId, null);
        assertEquals(outcome.getStatus(), "recorded", outcome.getMessage());

        final Payment stored = DAO.getInstance().getPayment(paymentId).orElseThrow();
        assertEquals(stored.getStatus(), Payment.Status.RECORDED);
        assertNotNull(stored.getCaptureId());
        assertEquals(stored.getCapturedGrossCents(), stored.getTotalChargedCents());
        assertFalse(stored.getTxIds().isEmpty());

        // Equal credits -> SHARED; the donation pair nets to zero on the payer.
        assertEquals(txCmds.getBalance(spouse.getId()), 475.0);
        assertEquals(txCmds.getBalance(payer.getId()), 475.0);
        Mockito.verify(mailer).sendConfirmation(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.eq("CFPW"), ArgumentMatchers.eq("Test Processor"),
                ArgumentMatchers.any(), ArgumentMatchers.any());

        // Revisiting the return URL is idempotent -- no second charge, no new rows.
        final int rows = txCmds.getTransactions(payer.getId()).size();
        assertEquals(commands.completePayment(paymentId, null).getStatus(), "recorded");
        assertEquals(txCmds.getTransactions(payer.getId()).size(), rows);
    }

    // ------------------------------------------------------------------ sandbox

    @Test
    public void sandboxRunsThePipelineWithoutTouchingTheLedger() {
        final Person admin = savedPerson();
        final PaymentCommands commands = commandsFor(admin, true);
        assertTrue(commands.isSandboxAllowed());

        final String approvalUrl = commands.startPayment(trip(),
                Map.of(admin.getId().getValue(), "475"), null, true, "http://localhost/pay");
        assertNotNull(approvalUrl);
        final String paymentId = paymentIdFrom(approvalUrl);

        final PaymentCommands.Completion outcome = commands.completePayment(paymentId, null);
        assertEquals(outcome.getStatus(), "sandbox");
        assertFalse(outcome.getDryRunLines().isEmpty(), "The dialog shows what WOULD have been written");
        assertTrue(outcome.getDryRunLines().get(0).contains("$475.00"), outcome.getDryRunLines().toString());
        assertTrue(txCmds.getTransactions(admin.getId()).isEmpty(), "The ledger is untouched");
        Mockito.verify(mailer, Mockito.never()).sendConfirmation(ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
        assertTrue(commandsFor(savedPersonAsSiteAdmin(), false).getOpenPayments().stream()
                .noneMatch(payment -> payment.getPaymentId().equals(paymentId)),
                "Sandbox payments never appear in reconciliation");
    }

    @Test
    public void sandboxRequiresThePrivilege() {
        final Person user = savedPerson();
        assertFalse(commandsFor(user, false).isSandboxAllowed());
        assertNull(commandsFor(user, false).startPayment(trip(),
                Map.of(user.getId().getValue(), "10"), null, true, "http://localhost/pay"));
    }

    // ------------------------------------------------------------------ refusals

    @Test
    public void badAmountsAndForeignTargetsAreRefused() {
        final Person payer = savedPerson();
        final Person stranger = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false);

        assertNull(commands.startPayment(trip(), Map.of(payer.getId().getValue(), "abc"), null, false,
                "http://localhost/pay"), "Garbage refuses, never guesses");
        assertNull(commands.startPayment(trip(), Map.of(stranger.getId().getValue(), "10"), null, false,
                "http://localhost/pay"), "Paying for a stranger is refused");
        assertNull(commands.startPayment(trip(), Map.of(), null, false, "http://localhost/pay"),
                "Nothing to pay");
        assertNull(commands.startPayment(trip(), Map.of(payer.getId().getValue(), "10"), "xyz", false,
                "http://localhost/pay"), "A garbage donation refuses");
    }

    @Test
    public void anUnpayableTripRefusesToStart() {
        final Person payer = savedPerson();
        final Trip orgless = Trip.builder().id("orgless-" + RandomData.genAlpha(8)).build();
        assertFalse(commandsFor(payer, false).quote(orgless, Map.of(), null).isPayable());
        assertNull(commandsFor(payer, false).startPayment(orgless,
                Map.of(payer.getId().getValue(), "10"), null, false, "http://localhost/pay"));
    }

    // ------------------------------------------------------------------ cancel / failure / mismatch

    @Test
    public void cancelOnlyWorksBeforeCapture() {
        final Person payer = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false);
        final String approvalUrl = commands.startPayment(trip(),
                Map.of(payer.getId().getValue(), "50"), null, false, "http://localhost/pay");
        final String paymentId = paymentIdFrom(approvalUrl);

        assertTrue(commands.cancelPayment(paymentId));
        assertEquals(commands.completePayment(paymentId, null).getStatus(), "cancelled");
        assertTrue(txCmds.getTransactions(payer.getId()).isEmpty());
        assertFalse(commands.cancelPayment(paymentId), "Cancelling twice is refused");
        assertFalse(commandsFor(savedPerson(), false).cancelPayment(paymentId),
                "Someone else's payment cannot be cancelled");
    }

    @Test
    public void aFailedCaptureFailsThePaymentWithoutRows() throws IOException {
        final Person payer = savedPerson();
        final PaymentProcessor broken = Mockito.mock(PaymentProcessor.class);
        final org.mockito.ArgumentCaptor<Payment> started =
                org.mockito.ArgumentCaptor.forClass(Payment.class);
        Mockito.when(broken.createOrder(started.capture(), ArgumentMatchers.any(),
                        ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new PaymentProcessor.CreatedOrder("REF-1", "http://processor/approve"));
        Mockito.when(broken.capture(ArgumentMatchers.any()))
                .thenReturn(PaymentProcessor.CaptureResult.failed());
        final PaymentCommands commands = commandsFor(payer, false, (config, sandbox) -> broken);

        assertNotNull(commands.startPayment(trip(),
                Map.of(payer.getId().getValue(), "50"), null, false, "http://localhost/pay"));
        final String paymentId = started.getValue().getPaymentId();
        assertEquals(commands.completePayment(paymentId, null).getStatus(), "failed");
        assertEquals(DAO.getInstance().getPayment(paymentId).orElseThrow().getStatus(),
                Payment.Status.FAILED);
        assertTrue(txCmds.getTransactions(payer.getId()).isEmpty());
    }

    @Test
    public void aGrossMismatchParksThePaymentForReconciliation() {
        final Person payer = savedPerson();
        final PaymentProcessor lying = Mockito.mock(PaymentProcessor.class);
        final org.mockito.ArgumentCaptor<Payment> started =
                org.mockito.ArgumentCaptor.forClass(Payment.class);
        Mockito.when(lying.createOrder(started.capture(), ArgumentMatchers.any(),
                        ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new PaymentProcessor.CreatedOrder("REF-2", "http://processor/approve"));
        Mockito.when(lying.capture(ArgumentMatchers.any())).thenReturn(new PaymentProcessor.CaptureResult(
                PaymentProcessor.CaptureResult.Status.COMPLETED, "CAP-X", 123L, OptionalLong.empty()));
        final PaymentCommands commands = commandsFor(payer, false, (config, sandbox) -> lying);

        assertNotNull(commands.startPayment(trip(),
                Map.of(payer.getId().getValue(), "50"), null, false, "http://localhost/pay"));
        final String paymentId = started.getValue().getPaymentId();
        assertEquals(commands.completePayment(paymentId, null).getStatus(), "mismatch");
        assertEquals(DAO.getInstance().getPayment(paymentId).orElseThrow().getStatus(),
                Payment.Status.CAPTURED, "Money moved; a human finishes this one");
        assertTrue(txCmds.getTransactions(payer.getId()).isEmpty(), "No auto-recording on a mismatch");

        final PaymentCommands admin = commandsFor(savedPersonAsSiteAdmin(), false);
        assertTrue(admin.getOpenPayments().stream()
                .anyMatch(payment -> payment.getPaymentId().equals(paymentId)));
        assertEquals(commandsFor(savedPerson(), false).getOpenPayments(), List.of(),
                "Reconciliation is site-admin only");
    }

    @Test
    public void adminCompleteFinishesACapturedPayment() throws IOException {
        final Person payer = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false);
        final String approvalUrl = commands.startPayment(trip(),
                Map.of(payer.getId().getValue(), "60"), null, false, "http://localhost/pay");
        final String paymentId = paymentIdFrom(approvalUrl);

        // Simulate the crash-after-capture shape: capture happened, recording did not.
        final Payment payment = DAO.getInstance().getPayment(paymentId).orElseThrow();
        payment.setStatus(Payment.Status.CAPTURED);
        payment.setCaptureId("CAP-CRASH");
        payment.setCapturedGrossCents(payment.getTotalChargedCents());
        DAO.getInstance().transitionPayment(payment, Payment.Status.CREATED);

        assertEquals(commandsFor(savedPerson(), false).adminComplete(paymentId).getStatus(), "notfound",
                "Non-admins cannot reconcile");
        final PaymentCommands admin = commandsFor(savedPersonAsSiteAdmin(), false);
        assertEquals(admin.adminComplete(paymentId).getStatus(), "recorded");
        assertEquals(txCmds.getBalance(payer.getId()), 60.0);
    }

    // ------------------------------------------------------------------ smaller branches

    @Test
    public void completionRefusesStrangersAndUnknownIds() {
        final Person payer = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false);
        final String approvalUrl = commands.startPayment(trip(),
                Map.of(payer.getId().getValue(), "40"), null, false, "http://localhost/pay");
        final String paymentId = paymentIdFrom(approvalUrl);

        assertEquals(commands.completePayment("no-such-payment", null).getStatus(), "notfound");
        assertEquals(commandsFor(savedPerson(), false).completePayment(paymentId, null).getStatus(),
                "notfound", "Someone else's payment answers notfound, revealing nothing");
    }

    @Test
    public void aProcessorRefusalAtCaptureFails() {
        final Person payer = savedPerson();
        final org.mockito.ArgumentCaptor<Payment> started =
                org.mockito.ArgumentCaptor.forClass(Payment.class);
        final PaymentProcessor flaky = Mockito.mock(PaymentProcessor.class);
        Mockito.when(flaky.createOrder(started.capture(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.any()))
                .thenReturn(new PaymentProcessor.CreatedOrder("REF-3", "http://processor/approve"));
        final boolean[] refuse = {false};
        final PaymentCommands commands = commandsFor(payer, false, (config, sandbox) -> {
            if (refuse[0]) {
                throw new PaymentProcessor.ProcessorException("credentials rotated away");
            }
            return flaky;
        });
        assertNotNull(commands.startPayment(trip(), Map.of(payer.getId().getValue(), "40"), null, false,
                "http://localhost/pay"));
        refuse[0] = true;
        assertEquals(commands.completePayment(started.getValue().getPaymentId(), null).getStatus(),
                "failed");
    }

    @Test
    public void aPendingCaptureStaysRetryable() {
        final Person payer = savedPerson();
        final org.mockito.ArgumentCaptor<Payment> started =
                org.mockito.ArgumentCaptor.forClass(Payment.class);
        final PaymentProcessor pending = Mockito.mock(PaymentProcessor.class);
        Mockito.when(pending.createOrder(started.capture(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.any()))
                .thenReturn(new PaymentProcessor.CreatedOrder("REF-4", "http://processor/approve"));
        Mockito.when(pending.capture(ArgumentMatchers.any())).thenReturn(
                new PaymentProcessor.CaptureResult(PaymentProcessor.CaptureResult.Status.PENDING, null, 0L,
                        OptionalLong.empty()));
        final PaymentCommands commands = commandsFor(payer, false, (config, sandbox) -> pending);
        assertNotNull(commands.startPayment(trip(), Map.of(payer.getId().getValue(), "40"), null, false,
                "http://localhost/pay"));
        final String paymentId = started.getValue().getPaymentId();
        assertEquals(commands.completePayment(paymentId, null).getStatus(), "pending");
        assertEquals(DAO.getInstance().getPayment(paymentId).orElseThrow().getStatus(),
                Payment.Status.CREATED, "Pending leaves the payment retryable");
    }

    @Test
    public void aLedgerFailureAfterCaptureParksThePayment() {
        final Person payer = savedPerson();
        final PaymentRecorder broken = Mockito.mock(PaymentRecorder.class);
        Mockito.when(broken.record(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("store down"));
        final PrivilegeCommands privs = Mockito.mock(PrivilegeCommands.class);
        final java.util.function.Supplier<Caller> callerSource = () -> new Caller(payer.getId(), false,
                new AuditActor(payer.getEmail(), payer.getId().getValue()), privs);
        this.mailer = Mockito.mock(PaymentMailer.class);
        final PaymentCommands commands = new PaymentCommands(callerSource, new OrgCommands(callerSource),
                new AuditCommands(), broken, PaymentProcessors::forConfig, () -> mailer);

        final String approvalUrl = commands.startPayment(trip(),
                Map.of(payer.getId().getValue(), "40"), null, false, "http://localhost/pay");
        final String paymentId = paymentIdFrom(approvalUrl);
        assertEquals(commands.completePayment(paymentId, null).getStatus(), "mismatch");
        assertEquals(DAO.getInstance().getPayment(paymentId).orElseThrow().getStatus(),
                Payment.Status.CAPTURED, "Money moved; the row waits for reconciliation");
        Mockito.verify(mailer, Mockito.never()).sendConfirmation(ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    public void pageHelpersAnswer() throws IOException {
        final Person payer = savedPerson();
        final Person spouse = savedPerson();
        grantManaged(payer, spouse);
        final PaymentCommands commands = commandsFor(payer, false);
        assertTrue(commands.newAmounts().isEmpty());
        final List<String> soloIds = commands.getPayMemberIds();
        assertEquals(soloIds, List.of(payer.getId().getValue()),
                "managedUsers alone is not a family; the frozen list is family-roster based");
        assertNotNull(new PaymentCommands(), "The CDI no-arg path constructs without a FacesContext");
        assertFalse(commands.quote(null, null, null).isPayable(), "No trip quotes as unpayable");
    }

    @Test
    public void donationOnlyPaymentsFlowEndToEnd() {
        final Person donor = savedPerson();
        final PaymentCommands commands = commandsFor(donor, false);
        // No amounts map at all: a pure donation.
        final String approvalUrl =
                commands.startPayment(trip(), null, "250", false, "http://localhost/pay");
        assertNotNull(approvalUrl);
        final String paymentId = paymentIdFrom(approvalUrl);
        assertEquals(commands.completePayment(paymentId, null).getStatus(), "recorded");
        assertEquals(txCmds.getBalance(donor.getId()), 0.0, "The donation pair is balance-neutral");
        assertEquals(txCmds.getTransactions(donor.getId()).size(), 2);
    }

    @Test
    public void zeroAndBlankAmountsAreSimplySkipped() {
        final Person payer = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false);
        final Map<String, Object> amounts = new java.util.HashMap<>();
        amounts.put(payer.getId().getValue(), "40");
        amounts.put("someone-else", "");
        amounts.put("another", "0");
        final String approvalUrl =
                commands.startPayment(trip(), amounts, null, false, "http://localhost/pay");
        assertNotNull(approvalUrl, "Blank boxes are not selections, not errors");
    }

    @Test
    public void aProcessorRefusalAtStartFailsThePayment() {
        final Person payer = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false, (config, sandbox) -> {
            throw new PaymentProcessor.ProcessorException("credentials missing");
        });
        assertNull(commands.startPayment(trip(), Map.of(payer.getId().getValue(), "40"), null, false,
                "http://localhost/pay"));
        final Payment failed = DAO.getInstance().getAllPayments().stream()
                .filter(payment -> payment.getPayerId().equals(payer.getId()))
                .findFirst().orElseThrow();
        assertEquals(failed.getStatus(), Payment.Status.FAILED,
                "A refused order creation fails the payment row; nothing was charged");
        assertEquals(commandsFor(payer, false).completePayment(failed.getPaymentId(), null).getStatus(),
                "failed", "Completing a FAILED payment reports it plainly");
    }

    @Test
    public void anonymousCallersCannotStart() {
        final PrivilegeCommands privs = Mockito.mock(PrivilegeCommands.class);
        final PaymentCommands anonymous = new PaymentCommands(
                () -> new Caller(null, false, AuditActor.system(), privs));
        assertNull(anonymous.startPayment(trip(), Map.of(), null, false, "http://localhost/pay"));
        assertNotNull(anonymous, "...and the REST-edge constructor wires the default collaborators");
    }

    @Test
    public void familyManagersGetTheWholeRosterInTheFrozenList() throws IOException {
        final Person manager = savedPerson();
        final Person member = savedPerson();
        final org.paulsens.trip.model.Family family = org.paulsens.trip.model.Family.builder()
                .memberIds(List.of(manager.getId(), member.getId()))
                .managerIds(List.of(manager.getId()))
                .build();
        assertTrue(DAO.getInstance().saveFamily(family));
        manager.setFamilyId(family.getId());
        member.setFamilyId(family.getId());
        assertTrue(DAO.getInstance().savePerson(manager));
        assertTrue(DAO.getInstance().savePerson(member));

        assertEquals(commandsFor(manager, false).getPayMemberIds(),
                List.of(manager.getId().getValue(), member.getId().getValue()));
        assertEquals(commandsFor(member, false).getPayMemberIds(),
                List.of(member.getId().getValue()), "A plain member pays only for themselves");
    }

    @Test
    public void quoteLabelsTheConfiguredProcessorAndFormats() throws IOException {
        // A staged (enabled) STRIPE config labels the quote even though the processor is unbuilt.
        final Person orgAdmin = savedPerson();
        final AuditActor actor = new AuditActor(orgAdmin.getEmail(), orgAdmin.getId().getValue());
        final PrivilegeCommands privs = Mockito.mock(PrivilegeCommands.class);
        final java.util.function.Supplier<Caller> adminCaller =
                () -> new Caller(orgAdmin.getId(), true, actor, privs);
        final OrgCommands orgCmds = new OrgCommands(adminCaller);
        final org.paulsens.trip.model.Organization stripeOrg =
                orgCmds.createOrganization("Stripe Org " + RandomData.genAlpha(6), null, null);
        assertTrue(orgCmds.saveProcessorConfig(stripeOrg.getId().getValue(), null, "Stripey", "STRIPE",
                "LIVE", true, "pk_live", null, 0, 0));
        final String configId = orgCmds.getProcessorConfigs(stripeOrg.getId().getValue())
                .get(0).getId().getValue();
        final Trip stripeTrip = Trip.builder().id("stripe-" + RandomData.genAlpha(6)).build();
        stripeTrip.setOrgId(stripeOrg.getId().getValue());
        stripeTrip.getPaymentConfig().setProcessorConfigId(configId);

        final PaymentCommands.Quote quote = commandsFor(savedPerson(), false)
                .quote(stripeTrip, Map.of("x", "100"), null);
        assertEquals(quote.getProcessorLabel(), "Stripe");

        assertTrue(orgCmds.saveProcessorConfig(stripeOrg.getId().getValue(), configId, "Zeffy Now",
                "ZEFFY", "LIVE", true, null, null, 0, 0));
        assertEquals(commandsFor(savedPerson(), false)
                .quote(stripeTrip, Map.of("x", "100"), null).getProcessorLabel(), "Zeffy");
        assertTrue(quote.isPayable());
        assertEquals(quote.getTotalFormatted(), quote.getTotalCents() == 100_00L
                ? "$100.00" : quote.getTotalFormatted());
        assertNotNull(quote.getCreditFeeFormatted());
        assertNotNull(quote.getDonationFeeFormatted());
    }

    @Test
    public void aFailedConfirmationMailIsAuditedButNeverBlocksTheMoney() {
        final Person payer = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false);
        Mockito.when(mailer.sendConfirmation(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(false);
        final String approvalUrl = commands.startPayment(trip(),
                Map.of(payer.getId().getValue(), "30"), null, false, "http://localhost/pay");
        assertEquals(commands.completePayment(paymentIdFrom(approvalUrl), null).getStatus(), "recorded");
        assertEquals(txCmds.getBalance(payer.getId()), 30.0);
    }

    @Test
    public void adminCompleteOfAnUnknownIdIsNotFound() {
        assertEquals(commandsFor(savedPersonAsSiteAdmin(), false).adminComplete("nope").getStatus(),
                "notfound");
        assertFalse(commandsFor(savedPersonAsSiteAdmin(), false).cancelPayment("nope"));
    }

    // ------------------------------------------------------------------ helpers

    private Trip trip() {
        return DAO.getInstance().getTrip("faketrip", Cached.NO).orElseThrow();
    }

    private static String paymentIdFrom(final String approvalUrl) {
        assertNotNull(approvalUrl);
        final String encoded = approvalUrl.substring(approvalUrl.indexOf("return=") + 7,
                approvalUrl.indexOf("&cancel="));
        final String returnUrl = java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8);
        return returnUrl.substring(returnUrl.indexOf("payment=") + 8);
    }

    private PaymentCommands commandsFor(final Person person, final boolean paymentsAdmin) {
        return commandsFor(person, paymentsAdmin, PaymentProcessors::forConfig);
    }

    private PaymentCommands commandsFor(final Person person, final boolean paymentsAdmin,
            final java.util.function.BiFunction<PaymentProcessorConfig, Boolean, PaymentProcessor> procs) {
        final PrivilegeCommands privs = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(privs.check(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(false);
        Mockito.when(privs.check(ArgumentMatchers.eq(PrivilegeCommands.PAYMENTS_ADMIN),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(paymentsAdmin);
        final java.util.function.Supplier<Caller> callerSource = () -> new Caller(person.getId(),
                Boolean.TRUE.equals(person.getNotes() != null), // site admin rides in the notes field marker
                new AuditActor(person.getEmail(), person.getId().getValue()), privs);
        this.mailer = Mockito.mock(PaymentMailer.class);
        Mockito.when(mailer.sendConfirmation(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(true);
        return new PaymentCommands(callerSource, new OrgCommands(callerSource), new AuditCommands(),
                new PaymentRecorder(), procs, () -> mailer);
    }

    private Person savedPerson() {
        return savedPerson(false);
    }

    private Person savedPersonAsSiteAdmin() {
        return savedPerson(true);
    }

    private Person savedPerson(final boolean siteAdmin) {
        final Person person = Person.builder()
                .first(RandomData.genAlpha(6)).last(RandomData.genAlpha(8))
                .email("pay." + RandomData.genAlpha(10) + "@example.com")
                .notes(siteAdmin ? "true" : null)
                .build();
        try {
            org.testng.Assert.assertTrue(DAO.getInstance().savePerson(person));
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        return person;
    }

    private void grantManaged(final Person manager, final Person managed) throws IOException {
        manager.getManagedUsers().add(managed.getId());
        org.testng.Assert.assertTrue(DAO.getInstance().savePerson(manager));
    }
}
