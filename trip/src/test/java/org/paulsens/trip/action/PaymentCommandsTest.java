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
import org.paulsens.trip.model.ProcessorType;
import org.paulsens.trip.model.FeesPaidBy;
import org.paulsens.trip.model.PaymentProcessorConfig;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.pay.FakeProcessor;
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

        final PaymentCommands.Start started = commands.startPayment(trip(), amounts, "1000", false,
                "http://localhost/trip/payment.jsf");
        assertNotNull(started, "The FAKE processor answers its checkout page");
        assertTrue(started.getApprovalUrl().startsWith("/trip/fakeCheckout.jsf?token=FAKE-"));
        assertFalse(started.isInline());
        assertEquals(started.getMerchantName(), "CFPW");
        assertEquals(started.getPayerEmail(), payer.getEmail());

        final String paymentId = started.getPaymentId();
        assertEquals(paymentId, paymentIdFrom(started.getApprovalUrl()),
                "the Start names the same payment the return URL carries");
        final org.paulsens.trip.push.PushNotifications pushes =
                Mockito.mock(org.paulsens.trip.push.PushNotifications.class);
        org.paulsens.trip.push.PushNotifications.setInstance(pushes);
        final PaymentCommands.Completion outcome;
        try {
            outcome = commands.completePayment(paymentId, null);
        } finally {
            org.paulsens.trip.push.PushNotifications.setInstance(null);
        }
        assertEquals(outcome.getStatus(), "recorded", outcome.getMessage());
        // The payer's phone hears about a REAL recorded payment (the sandbox path below never pushes).
        Mockito.verify(pushes).paymentRecorded(
                ArgumentMatchers.argThat(p -> paymentId.equals(p.getPaymentId())), ArgumentMatchers.any(),
                ArgumentMatchers.eq("CFPW"));

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
        assertTrue(commands.isSandboxAllowed(trip()), "paymentsAdmin@the-trip's-org enables sandbox");

        final PaymentCommands.Start started = commands.startPayment(trip(),
                Map.of(admin.getId().getValue(), "475"), null, true, "http://localhost/pay");
        assertNotNull(started);
        final String paymentId = started.getPaymentId();

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
        assertFalse(commandsFor(user, false).isSandboxAllowed(trip()));
        assertNull(commandsFor(user, false).startPayment(trip(),
                Map.of(user.getId().getValue(), "10"), null, true, "http://localhost/pay"));
        // The grant is per-org: an org-less legacy trip offers sandbox to site admins only.
        final Trip orgless = Trip.builder().id("orgless-" + user.getId().getValue()).title("Orgless").build();
        assertFalse(commandsFor(user, true).isSandboxAllowed(orgless));
        assertTrue(commandsFor(savedPersonAsSiteAdmin(), false).isSandboxAllowed(orgless));
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
        final String paymentId = commands.startPayment(trip(),
                Map.of(payer.getId().getValue(), "50"), null, false, "http://localhost/pay").getPaymentId();

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

        // The hold is STORED: the next completion door (the webhook, a reload of the return page) used to find
        // a CAPTURED row, call it crash recovery, and record the expected $50 against a $1.23 capture.
        final Payment held = DAO.getInstance().getPayment(paymentId).orElseThrow();
        assertEquals(held.getHeldReason(), "the processor captured $1.23, not the expected $52.32");
        assertEquals(commands.completePayment(paymentId, null).getStatus(), "mismatch", "the payer, again");
        assertEquals(commands.completeFromWebhook(paymentId).getStatus(), "mismatch", "the webhook");
        assertEquals(DAO.getInstance().getPayment(paymentId).orElseThrow().getStatus(), Payment.Status.CAPTURED);
        assertTrue(txCmds.getTransactions(payer.getId()).isEmpty(), "still nothing on the ledger");

        final PaymentCommands admin = commandsFor(savedPersonAsSiteAdmin(), false);
        assertTrue(admin.getOpenPayments().stream()
                .anyMatch(payment -> payment.getPaymentId().equals(paymentId)));
        assertEquals(admin.holdOf(held), "the processor captured $1.23, not the expected $52.32");
        assertEquals(admin.holdOf(null), "");
        assertEquals(commandsFor(savedPerson(), false).getOpenPayments(), List.of(),
                "Reconciliation is site-admin only");

        // Only an administrator's Finish releases it.
        assertEquals(admin.adminComplete(paymentId).getStatus(), "recorded");
        assertFalse(txCmds.getTransactions(payer.getId()).isEmpty());
        assertEquals(admin.holdOf(DAO.getInstance().getPayment(paymentId).orElseThrow()), "", "no longer held");
    }

    /** A CAPTURED row from before the reason was stored is told by its amounts; an offline one never is. */
    @Test
    public void aMismatchedRowFromAnOlderBuildIsHeldToo() throws IOException {
        final Person payer = savedPerson();
        final Payment legacy = Payment.builder()
                .tripId(FakeData.FAKE_TRIP_ID).orgId(FakeData.CFPW_ORG_ID).payerId(payer.getId())
                .processorConfigId(FakeData.CFPW_PROCESSOR_ID).processorType(ProcessorType.FAKE)
                .allocations(List.of(new Payment.Allocation(payer.getId(), 5000L)))
                .feesPaidBy(FeesPaidBy.ORGANIZATION).totalChargedCents(5000L)
                .orderRef("FAKE-legacy").captureId("CAP-legacy").capturedGrossCents(123L)
                .status(Payment.Status.CAPTURED).createdAt(java.time.LocalDateTime.now()).build();
        DAO.getInstance().createPayment(legacy);
        final PaymentCommands commands = commandsFor(payer, false);
        assertEquals(commands.completePayment(legacy.getPaymentId(), null).getStatus(), "mismatch");
        assertTrue(txCmds.getTransactions(payer.getId()).isEmpty());
        final PaymentCommands admin = commandsFor(savedPersonAsSiteAdmin(), false);
        assertEquals(admin.holdOf(legacy), "the processor captured $1.23, not the expected $50.00");
        final PaymentCommands.Completion first = admin.adminComplete(legacy.getPaymentId());
        assertEquals(first.getStatus(), "recorded", "a CAPTURED row is one Finish away for an administrator");
    }

    @Test
    public void adminCompleteFinishesACapturedPayment() throws IOException {
        final Person payer = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false);
        final String paymentId = commands.startPayment(trip(),
                Map.of(payer.getId().getValue(), "60"), null, false, "http://localhost/pay").getPaymentId();

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
        final String paymentId = commands.startPayment(trip(),
                Map.of(payer.getId().getValue(), "40"), null, false, "http://localhost/pay").getPaymentId();

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

        final String paymentId = commands.startPayment(trip(),
                Map.of(payer.getId().getValue(), "40"), null, false, "http://localhost/pay").getPaymentId();
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
        final PaymentCommands.Start started =
                commands.startPayment(trip(), null, "250", false, "http://localhost/pay");
        assertNotNull(started);
        final String paymentId = started.getPaymentId();
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
        final PaymentCommands.Start started =
                commands.startPayment(trip(), amounts, null, false, "http://localhost/pay");
        assertNotNull(started, "Blank boxes are not selections, not errors");
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
        final String paymentId = commands.startPayment(trip(),
                Map.of(payer.getId().getValue(), "30"), null, false, "http://localhost/pay").getPaymentId();
        assertEquals(commands.completePayment(paymentId, null).getStatus(), "recorded");
        assertEquals(txCmds.getBalance(payer.getId()), 30.0);
    }

    @Test
    public void adminCompleteOfAnUnknownIdIsNotFound() {
        assertEquals(commandsFor(savedPersonAsSiteAdmin(), false).adminComplete("nope").getStatus(),
                "notfound");
        assertFalse(commandsFor(savedPersonAsSiteAdmin(), false).cancelPayment("nope"));
    }

    // ------------------------------------------------------------------ inline (Stripe-shaped) flow

    @Test
    public void anInlineProcessorAnswersAClientSecretInsteadOfARedirect() {
        final Person payer = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false);
        final PaymentCommands.Start started = commands.startPayment(inlineTrip(),
                Map.of(payer.getId().getValue(), "25"), null, false, "http://localhost/trip/payment.jsf");
        assertNotNull(started);
        assertTrue(started.isInline());
        assertNull(started.getApprovalUrl());
        assertEquals(started.getPublishableKey(), "pk_fake");
        assertTrue(started.getClientSecret().startsWith("FAKE-"), "the fake's secret is its order ref");
        assertNull(started.getScriptUrl(), "null script = the page's own fake Stripe.js");
        assertEquals(started.getTotalFormatted(), "$26.42", "$25.00 grossed up: ceil((2500 + 49) / (1 - 0.0349))");
        final Payment stored = DAO.getInstance().getPayment(started.getPaymentId()).orElseThrow();
        assertEquals(stored.getOrderRef(), started.getClientSecret());

        assertEquals(commands.completePayment(started.getPaymentId(), null).getStatus(), "recorded",
                "the inline fake settles like a card");
    }

    @Test
    public void aSettlingBankPaymentIsPendingUntilTheWebhookStandInFinishesIt() {
        final Person payer = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false);
        final PaymentCommands.Start started = commands.startPayment(inlineTrip(),
                Map.of(payer.getId().getValue(), "40"), null, false, "http://localhost/trip/payment.jsf");
        assertTrue(commands.fakeMarkAsync(started.getClientSecret()));
        assertFalse(commands.fakeMarkAsync("FAKE-nope"));

        final PaymentCommands.Completion pending = commands.completePayment(started.getPaymentId(), null);
        assertEquals(pending.getStatus(), "pending");
        assertTrue(pending.getMessage().contains("processing"), pending.getMessage());
        assertTrue(pending.getMessage().contains("nothing more is needed"), pending.getMessage());
        assertEquals(pending.getReference(), started.getClientSecret(), "the page's settle link needs it");
        assertEquals(DAO.getInstance().getPayment(started.getPaymentId()).orElseThrow().getStatus(),
                Payment.Status.CREATED, "a settling payment stays CREATED until it clears");
        Mockito.verify(mailer, Mockito.never()).sendConfirmation(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());

        assertEquals(commands.fakeSettle("FAKE-nope").getStatus(), "notfound");
        final PaymentCommands.Completion settled = commands.fakeSettle(started.getClientSecret());
        assertEquals(settled.getStatus(), "recorded", settled.getMessage());
        Mockito.verify(mailer).sendConfirmation(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());

        // The payer's own return visit after the webhook: already recorded, and NO second mail.
        assertEquals(commands.completePayment(started.getPaymentId(), null).getStatus(), "recorded");
        Mockito.verify(mailer, Mockito.times(1)).sendConfirmation(ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    /**
     * THE race the webhook introduces: the return page and the webhook both finish the same CAPTURED row. The
     * loser's RECORDED put fails the condition; it must answer the winner's outcome and send nothing.
     */
    @Test
    public void theLoserOfTheRecordRaceSendsNoSecondConfirmation() throws IOException {
        final Person payer = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false);
        final String paymentId = commands.startPayment(trip(),
                Map.of(payer.getId().getValue(), "60"), null, false, "http://localhost/pay").getPaymentId();
        // The row as the webhook would find it: captured by one writer, not yet recorded.
        final Payment captured = DAO.getInstance().getPayment(paymentId).orElseThrow();
        captured.setCaptureId("CAP-race");
        captured.setCapturedGrossCents(captured.getTotalChargedCents());
        captured.setStatus(Payment.Status.CAPTURED);
        DAO.getInstance().transitionPayment(captured, Payment.Status.CREATED);

        // Writer A finishes between B's read of the row and B's RECORDED put: emulate by recording with a
        // second commands instance (same mailer) first, then letting the first instance's stale copy race.
        final PaymentRecorder recorder = new PaymentRecorder();
        final PaymentCommands racer = new PaymentCommands(() -> Caller.forActor(AuditActor.system()),
                new OrgCommands(() -> Caller.forActor(AuditActor.system())), new AuditCommands(),
                new PaymentRecorder() {
                    @Override
                    public Result record(final Payment payment, final String orgName, final String processorName) {
                        // B is mid-record: A completes the whole payment first.
                        commands.completeFromWebhook(payment.getPaymentId());
                        return recorder.record(payment, orgName, processorName);
                    }
                }, PaymentProcessors::forConfig, () -> mailer);

        assertEquals(racer.completeFromWebhook(paymentId).getStatus(), "recorded");
        Mockito.verify(mailer, Mockito.times(1)).sendConfirmation(ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
        assertEquals(DAO.getInstance().getPayment(paymentId).orElseThrow().getStatus(), Payment.Status.RECORDED);
    }

    @Test
    public void cancellingReleasesTheOrderAtTheProcessor() {
        final Person payer = savedPerson();
        final PaymentProcessor processor = Mockito.mock(PaymentProcessor.class);
        final org.mockito.ArgumentCaptor<Payment> started = org.mockito.ArgumentCaptor.forClass(Payment.class);
        Mockito.when(processor.createOrder(started.capture(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(PaymentProcessor.CreatedOrder.inline("pi_1", "pk", "sec"));
        final PaymentCommands commands = commandsFor(payer, false, (config, sandbox) -> processor);
        final String paymentId = commands.startPayment(trip(), Map.of(payer.getId().getValue(), "10"), null,
                false, "http://localhost/pay").getPaymentId();
        Mockito.when(processor.cancel("pi_1")).thenReturn(PaymentProcessor.OrderState.OPEN);
        assertTrue(commands.cancelPayment(paymentId));
        Mockito.verify(processor).cancel("pi_1");
        assertEquals(DAO.getInstance().getPayment(paymentId).orElseThrow().getStatus(), Payment.Status.CANCELLED);

        // The processor goes first: one that cannot be asked leaves our row alone, and so does one that throws.
        final String second = commands.startPayment(trip(), Map.of(payer.getId().getValue(), "10"), null,
                false, "http://localhost/pay").getPaymentId();
        Mockito.when(processor.cancel("pi_1")).thenReturn(PaymentProcessor.OrderState.UNKNOWN);
        assertFalse(commands.cancelPayment(second));
        assertEquals(DAO.getInstance().getPayment(second).orElseThrow().getStatus(), Payment.Status.CREATED);
        Mockito.doThrow(new IllegalStateException("down")).when(processor).cancel(ArgumentMatchers.any());
        assertFalse(commands.cancelPayment(second));
        assertEquals(DAO.getInstance().getPayment(second).orElseThrow().getStatus(), Payment.Status.CREATED);

        // A processor that cannot even be built (credentials removed) can never be asked: the row may go.
        final PaymentCommands orphaned = commandsFor(payer, false, (config, sandbox) -> {
            throw new PaymentProcessor.ProcessorException("credentials missing");
        });
        assertTrue(orphaned.cancelPayment(second));
        assertEquals(DAO.getInstance().getPayment(second).orElseThrow().getStatus(), Payment.Status.CANCELLED);
    }

    /**
     * A bank debit the processor is still settling looks exactly like a walked-away payment (CREATED, no
     * capture id). Neither the payer's "Change amounts" nor an admin's Abandon may cancel it: the debit clears
     * days later, and a CANCELLED row would leave that money with no ledger credit and nothing to reconcile.
     */
    @Test
    public void aPaymentTheBankIsSettlingCannotBeCancelledOrAbandoned() {
        final Person payer = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false);
        final String paymentId = commands.startPayment(inlineTrip(), Map.of(payer.getId().getValue(), "20"), null,
                false, "http://localhost/trip/payment.jsf").getPaymentId();
        final String orderRef = DAO.getInstance().getPayment(paymentId).orElseThrow().getOrderRef();
        assertTrue(commands.fakeMarkAsync(orderRef), "the payer paid by bank; the debit is in flight");
        assertEquals(commands.completePayment(paymentId, null).getStatus(), "pending");

        assertFalse(commands.cancelPayment(paymentId), "the payer cannot cancel it");
        final PaymentCommands admin = commandsFor(savedPersonAsSiteAdmin(), false);
        assertFalse(admin.abandon(paymentId), "and an administrator cannot abandon it");
        assertEquals(DAO.getInstance().getPayment(paymentId).orElseThrow().getStatus(), Payment.Status.CREATED);

        assertEquals(commands.fakeSettle(orderRef).getStatus(), "recorded", "the debit clears and is recorded");
        assertFalse(txCmds.getTransactions(payer.getId()).isEmpty());
    }

    /** A payment the processor already HAS is finished, not cancelled. */
    @Test
    public void cancellingAPaymentTheProcessorAlreadyHoldsRecordsItInstead() {
        final Person payer = savedPerson();
        final PaymentProcessor processor = Mockito.mock(PaymentProcessor.class);
        final org.mockito.ArgumentCaptor<Payment> started = org.mockito.ArgumentCaptor.forClass(Payment.class);
        Mockito.when(processor.createOrder(started.capture(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(PaymentProcessor.CreatedOrder.inline("pi_paid", "pk", "sec"));
        Mockito.when(processor.cancel("pi_paid")).thenReturn(PaymentProcessor.OrderState.PAID);
        final PaymentCommands commands = commandsFor(payer, false, (config, sandbox) -> processor);
        final String paymentId = commands.startPayment(trip(), Map.of(payer.getId().getValue(), "10"), null,
                false, "http://localhost/pay").getPaymentId();
        final long total = started.getValue().getTotalChargedCents();
        Mockito.when(processor.capture("pi_paid")).thenReturn(new PaymentProcessor.CaptureResult(
                PaymentProcessor.CaptureResult.Status.COMPLETED, "pi_paid", total, OptionalLong.empty()));

        assertFalse(commands.cancelPayment(paymentId), "not cancelled");
        assertEquals(DAO.getInstance().getPayment(paymentId).orElseThrow().getStatus(), Payment.Status.RECORDED);
        assertFalse(txCmds.getTransactions(payer.getId()).isEmpty(), "the money that moved is on the ledger");
    }

    /**
     * The webhook reports money for a payment that was given up here (the payer's saved form completed it after
     * a cancel, or a debit cleared that had been abandoned before the settling check existed). Answering
     * "cancelled" lost it; the row comes back CAPTURED and held for an administrator.
     */
    @Test
    public void aPaymentPaidAfterItWasGivenUpComesBackHeld() throws IOException {
        final Person payer = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false);
        final String paymentId = commands.startPayment(inlineTrip(), Map.of(payer.getId().getValue(), "20"), null,
                false, "http://localhost/trip/payment.jsf").getPaymentId();
        // Cancelled on our side only: the fake's order is still payable, as an un-released intent would be.
        assertTrue(commands.cancelFromWebhook(paymentId));
        assertEquals(commands.completePayment(paymentId, null).getStatus(), "cancelled",
                "the payer's own page never resurrects a cancelled payment");

        assertEquals(commands.completeFromWebhook(paymentId).getStatus(), "mismatch");
        final Payment back = DAO.getInstance().getPayment(paymentId).orElseThrow();
        assertEquals(back.getStatus(), Payment.Status.CAPTURED);
        assertEquals(back.getHeldReason(), "paid at the processor after the payment was cancelled here");
        assertEquals(back.getCapturedGrossCents(), back.getTotalChargedCents());
        assertTrue(txCmds.getTransactions(payer.getId()).isEmpty(), "held: nothing is recorded by itself");
        assertEquals(commands.completeFromWebhook(paymentId).getStatus(), "mismatch", "a redelivery changes nothing");

        final PaymentCommands admin = commandsFor(savedPersonAsSiteAdmin(), false);
        assertTrue(admin.getOpenPayments().stream().anyMatch(p -> p.getPaymentId().equals(paymentId)),
                "it is on the reconciliation list again");
        assertEquals(admin.adminComplete(paymentId).getStatus(), "recorded");
        assertFalse(txCmds.getTransactions(payer.getId()).isEmpty());

        // The same for FAILED; and a given-up payment the processor does NOT report as paid stays given up.
        final String failed = commands.startPayment(inlineTrip(), Map.of(payer.getId().getValue(), "20"), null,
                false, "http://localhost/trip/payment.jsf").getPaymentId();
        assertTrue(commands.failFromWebhook(failed, "returned"));
        assertEquals(commands.completeFromWebhook(failed).getStatus(), "mismatch");
        assertEquals(DAO.getInstance().getPayment(failed).orElseThrow().getHeldReason(),
                "paid at the processor after the payment was failed here");
        final String gone = commands.startPayment(inlineTrip(), Map.of(payer.getId().getValue(), "20"), null,
                false, "http://localhost/trip/payment.jsf").getPaymentId();
        assertTrue(commands.cancelPayment(gone), "released at the processor too");
        assertEquals(commands.completeFromWebhook(gone).getStatus(), "cancelled");
        assertEquals(DAO.getInstance().getPayment(gone).orElseThrow().getStatus(), Payment.Status.CANCELLED);
    }

    /**
     * The lock is the processor's to enforce; when it does not (it would not narrow the order), a card can pay
     * the bank-debit total. The money is in, so the payment is recorded, and the shortfall goes on the audit
     * trail for the organization (owner's decision 2026-11-12).
     */
    @Test
    public void aCardThatPaysABankPricedTotalIsRecordedAndFlaggedOnTheAuditTrail() {
        final Person payer = savedPerson();
        final PaymentProcessor processor = Mockito.mock(PaymentProcessor.class);
        Mockito.when(processor.createOrder(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(PaymentProcessor.CreatedOrder.inline("pi_card", "pk", "sec"));
        Mockito.when(processor.reprice("pi_card", 2017L, "us_bank_account"))
                .thenReturn(PaymentProcessor.Repriced.REPRICED);
        Mockito.when(processor.capture("pi_card")).thenReturn(new PaymentProcessor.CaptureResult(
                PaymentProcessor.CaptureResult.Status.COMPLETED, "pi_card", 2017L, OptionalLong.empty(), "card"));
        final AuditCommands audit = Mockito.spy(new AuditCommands());
        final PaymentCommands commands = commandsFor(payer, false, false, (config, sandbox) -> processor, audit);
        final String paymentId = commands.startPayment(inlineTrip(), Map.of(payer.getId().getValue(), "20"), null,
                false, "http://localhost/trip/payment.jsf").getPaymentId();
        final PaymentCommands.Reprice bank = commands.selectMethod(paymentId, "us_bank_account");
        assertTrue(bank.changed());
        assertFalse(bank.methodLocked(), "this processor did not lock the order");

        assertEquals(commands.completePayment(paymentId, null).getStatus(), "recorded");
        Mockito.verify(audit).payment(ArgumentMatchers.any(Payment.class), ArgumentMatchers.eq(false),
                ArgumentMatchers.eq("Paid with card at the total priced for us_bank_account: $20.17 collected, "
                        + "$1.07 less than the $21.24 that method is charged; recorded as paid, the organization "
                        + "absorbs the difference"), ArgumentMatchers.any());
        assertFalse(txCmds.getTransactions(payer.getId()).isEmpty());
    }

    /** The method the total was priced for paying it is the normal case: nothing to flag. */
    @Test
    public void aBankDebitThatPaysABankPricedTotalIsNotFlagged() {
        final Person payer = savedPerson();
        final PaymentProcessor processor = Mockito.mock(PaymentProcessor.class);
        Mockito.when(processor.createOrder(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(PaymentProcessor.CreatedOrder.inline("pi_ach", "pk", "sec"));
        Mockito.when(processor.reprice("pi_ach", 2017L, "us_bank_account"))
                .thenReturn(PaymentProcessor.Repriced.REPRICED_LOCKED);
        Mockito.when(processor.capture("pi_ach")).thenReturn(new PaymentProcessor.CaptureResult(
                PaymentProcessor.CaptureResult.Status.COMPLETED, "pi_ach", 2017L, OptionalLong.empty(),
                "us_bank_account"));
        final AuditCommands audit = Mockito.spy(new AuditCommands());
        final PaymentCommands commands = commandsFor(payer, false, false, (config, sandbox) -> processor, audit);
        final String paymentId = commands.startPayment(inlineTrip(), Map.of(payer.getId().getValue(), "20"), null,
                false, "http://localhost/trip/payment.jsf").getPaymentId();
        assertTrue(commands.selectMethod(paymentId, "us_bank_account").methodLocked());
        assertEquals(commands.completePayment(paymentId, null).getStatus(), "recorded");
        Mockito.verify(audit, Mockito.never()).payment(ArgumentMatchers.any(Payment.class),
                ArgumentMatchers.eq(false),
                ArgumentMatchers.contains("Paid with"), ArgumentMatchers.any());
    }

    /**
     * Whoever may finish a payment may re-price it: a registration admin registering a family comes back to
     * their own page, and used to be refused the bank price (404), so the family was charged the card total.
     */
    @Test
    public void aRegistrationAdminMayRePriceACheckoutPaymentTheyStartedForSomeone() throws IOException {
        final Person family = savedPerson();
        final Payment checkout = Payment.builder()
                .tripId(FakeData.FAKE2_TRIP_ID).orgId(FakeData.CFPW_ORG_ID).payerId(family.getId())
                .processorConfigId(FakeData.CFPW_INLINE_PROCESSOR_ID).processorType(ProcessorType.FAKE)
                .allocations(List.of(new Payment.Allocation(family.getId(), 2000L)))
                .feesPaidBy(FeesPaidBy.PAYER).creditFeeCents(124L).totalChargedCents(2124L)
                .createdAt(java.time.LocalDateTime.now()).build();
        checkout.setCheckout(new org.paulsens.trip.model.PendingParty());
        checkout.setOrderRef(new FakeProcessor(true).createOrder(checkout, "d", "http://r", "http://c").orderRef());
        DAO.getInstance().createPayment(checkout);

        final Person staff = savedPerson();
        final PaymentCommands regAdmin = commandsFor(staff, false, true, PaymentProcessors::forConfig,
                new AuditCommands());
        final PaymentCommands.Reprice bank = regAdmin.selectMethod(checkout.getPaymentId(), "us_bank_account");
        assertNotNull(bank, "the registration admin acts on a checkout payment like its payer");
        assertEquals(bank.totalCents(), 2017L);
        assertNull(commandsFor(staff, false).selectMethod(checkout.getPaymentId(), "card"),
                "without the role it is still someone else's payment");

        // Not a checkout: the role is about registrations, so a plain payment stays its payer's.
        final String plain = commandsFor(family, false).startPayment(inlineTrip(),
                Map.of(family.getId().getValue(), "20"), null, false, "http://localhost/trip/payment.jsf")
                .getPaymentId();
        assertNull(regAdmin.selectMethod(plain, "us_bank_account"));
    }

    @Test
    public void webhookEntryPointsRespectTheStateMachine() throws IOException {
        final Person payer = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false);
        Mockito.when(mailer.sendPaymentFailed(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(true);
        final String failing = commands.startPayment(trip(), Map.of(payer.getId().getValue(), "10"), null,
                false, "http://localhost/pay").getPaymentId();
        commands.noteFailure(failing, "card_declined");
        assertEquals(DAO.getInstance().getPayment(failing).orElseThrow().getStatus(), Payment.Status.CREATED,
                "a card decline is retryable, never terminal");
        commands.noteFailure("no-such-payment", "x");

        assertTrue(commands.failFromWebhook(failing, "the bank returned the debit"));
        assertEquals(DAO.getInstance().getPayment(failing).orElseThrow().getStatus(), Payment.Status.FAILED);
        Mockito.verify(mailer).sendPaymentFailed(ArgumentMatchers.argThat(p -> failing.equals(p.getPaymentId())),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq("CFPW"),
                ArgumentMatchers.eq("Test Processor"), ArgumentMatchers.eq("the bank returned the debit"),
                ArgumentMatchers.any());
        assertFalse(commands.failFromWebhook(failing, "again"), "FAILED is terminal");
        assertFalse(commands.failFromWebhook("no-such-payment", "x"));

        final String cancelling = commands.startPayment(trip(), Map.of(payer.getId().getValue(), "10"), null,
                false, "http://localhost/pay").getPaymentId();
        assertTrue(commands.cancelFromWebhook(cancelling));
        assertEquals(DAO.getInstance().getPayment(cancelling).orElseThrow().getStatus(),
                Payment.Status.CANCELLED);
        assertFalse(commands.cancelFromWebhook(cancelling));
        assertEquals(commands.completeFromWebhook("no-such-payment").getStatus(), "notfound");
        // The processor said "canceled", so it holds nothing: the fake's order goes the way Stripe's intent did.
        new FakeProcessor().cancel(DAO.getInstance().getPayment(cancelling).orElseThrow().getOrderRef());
        assertEquals(commands.completeFromWebhook(cancelling).getStatus(), "cancelled");

        // A sandbox failure mails nobody.
        final Person tester = savedPerson();
        final PaymentCommands sandboxed = commandsFor(tester, true);
        final String sandboxId = sandboxed.startPayment(trip(), Map.of(tester.getId().getValue(), "10"), null,
                true, "http://localhost/pay").getPaymentId();
        assertTrue(sandboxed.failFromWebhook(sandboxId, "returned"));
        Mockito.verify(mailer, Mockito.never()).sendPaymentFailed(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());
    }

    // ------------------------------------------------------------------ refunds (webhook-driven)

    /**
     * A dashboard refund reaches us as a webhook; the amount is re-read from the processor and lands as ONE
     * cumulative refund row on the payer. Partial then full rewrites that row; a CREATED payment, an unknown
     * one, nothing refunded, and an unreadable processor each leave the ledger alone.
     */
    @Test
    public void aProcessorRefundWritesOneCumulativeRowOnThePayer() {
        final Person payer = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false);
        final PaymentCommands.Start started = commands.startPayment(inlineTrip(),
                Map.of(payer.getId().getValue(), "100"), null, false, "http://localhost/trip/payment.jsf");
        final String paymentId = started.getPaymentId();
        final String orderRef = started.getClientSecret();

        assertEquals(commands.refundFromWebhook(paymentId).getStatus(), "unchanged",
                "a CREATED payment has no ledger rows to refund against");
        assertEquals(commands.completePayment(paymentId, null).getStatus(), "recorded");
        final int rows = txCmds.getTransactions(payer.getId()).size();
        assertEquals(commands.refundFromWebhook(paymentId).getStatus(), "unchanged", "nothing refunded yet");
        assertEquals(txCmds.getTransactions(payer.getId()).size(), rows);

        final PaymentCommands.Completion partial = commands.fakeRefund(orderRef, 2500L);
        assertEquals(partial.getStatus(), "refunded", partial.getMessage());
        assertEquals(txCmds.getTransactions(payer.getId()).size(), rows + 1);
        final Payment stored = DAO.getInstance().getPayment(paymentId).orElseThrow();
        assertEquals(stored.getStatus(), Payment.Status.RECORDED, "a refund never changes the payment's state");
        assertTrue(stored.getTxIds().contains(paymentId + "-refund"), "the payment remembers its refund row");
        assertEquals(txCmds.getTransaction(payer.getId(), paymentId + "-refund").getAmount(), -25.0f);

        // Redelivery and a second partial: the SAME row, the cumulative total.
        assertEquals(commands.refundFromWebhook(paymentId).getStatus(), "refunded");
        assertEquals(commands.fakeRefund(orderRef, 10000L).getStatus(), "refunded");
        assertEquals(txCmds.getTransactions(payer.getId()).size(), rows + 1, "rewritten in place");
        assertEquals(txCmds.getTransaction(payer.getId(), paymentId + "-refund").getAmount(), -100.0f);
        assertEquals(txCmds.getBalance(payer.getId()), 0.0, "the full refund brings the balance back");

        assertEquals(commands.refundFromWebhook("no-such-payment").getStatus(), "notfound");
        assertEquals(commands.fakeRefund("FAKE-nope", 1L).getStatus(), "notfound");

        // The processor cannot be read: pending, so the webhook asks Stripe to redeliver.
        final PaymentProcessor dark = Mockito.mock(PaymentProcessor.class);
        Mockito.when(dark.refundedCents(ArgumentMatchers.any())).thenReturn(java.util.OptionalLong.empty());
        assertEquals(commandsFor(payer, false, (cfg, sandbox) -> dark).refundFromWebhook(paymentId).getStatus(),
                "pending");
        final PaymentProcessor broken = Mockito.mock(PaymentProcessor.class);
        Mockito.when(broken.refundedCents(ArgumentMatchers.any())).thenThrow(new IllegalStateException("down"));
        assertEquals(commandsFor(payer, false, (cfg, sandbox) -> broken).refundFromWebhook(paymentId).getStatus(),
                "pending");
    }

    /**
     * THE refund race: two deliveries run at once (two refunds seconds apart, or a redelivery), in two ECS
     * tasks. A read the processor BEFORE the second refund ($25); B read it after ($100) and finished first;
     * A's stale row lands last. Without the lock the ledger would end at -$25 with nothing left to fix it.
     * With it, A's replace fails, A re-reads the processor ($100) and repairs its own row.
     */
    @Test
    public void aStaleRefundDeliveryLosesTheLockAndRepairsItsOwnRow() {
        final Person payer = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false);
        final PaymentCommands.Start started = commands.startPayment(inlineTrip(),
                Map.of(payer.getId().getValue(), "100"), null, false, "http://localhost/trip/payment.jsf");
        final String paymentId = started.getPaymentId();
        final String orderRef = started.getClientSecret();
        assertEquals(commands.completePayment(paymentId, null).getStatus(), "recorded");
        final int rows = txCmds.getTransactions(payer.getId()).size();

        // A's processor: answers the OLD total once, then the truth (its retry re-reads the processor).
        final PaymentProcessor staleThenFresh = Mockito.mock(PaymentProcessor.class);
        Mockito.when(staleThenFresh.refundedCents(orderRef))
                .thenReturn(java.util.OptionalLong.of(2500L), java.util.OptionalLong.of(10000L));
        final PaymentCommands b = commandsFor(payer, false);
        final java.util.concurrent.atomic.AtomicBoolean interleaved = new java.util.concurrent.atomic.AtomicBoolean();
        final PaymentCommands a = new PaymentCommands(() -> Caller.forActor(AuditActor.system()),
                new OrgCommands(() -> Caller.forActor(AuditActor.system())), new AuditCommands(),
                new PaymentRecorder() {
                    @Override
                    public String recordRefund(final Payment payment, final long refundedCents,
                            final String orgName, final String processorName) {
                        if (interleaved.compareAndSet(false, true)) {
                            // B runs to completion while A holds its stale $25 read; A's row lands after B's.
                            FakeProcessor.refund(orderRef, 10000L);
                            assertEquals(b.refundFromWebhook(paymentId).getStatus(), "refunded");
                        }
                        return super.recordRefund(payment, refundedCents, orgName, processorName);
                    }
                }, (cfg, sandbox) -> staleThenFresh, () -> mailer);

        assertEquals(a.refundFromWebhook(paymentId).getStatus(), "refunded");
        Mockito.verify(staleThenFresh, Mockito.times(2)).refundedCents(orderRef);
        assertEquals(txCmds.getTransactions(payer.getId()).size(), rows + 1, "still ONE refund row");
        assertEquals(txCmds.getTransaction(payer.getId(), paymentId + "-refund").getAmount(), -100.0f,
                "the later truth, not the stale read that landed last");
        assertEquals(DAO.getInstance().getPayment(paymentId).orElseThrow().getRefundedCents(), 10000L);

        // A delivery that keeps losing the lock gives up with a redelivery request, never a silent drop.
        final PaymentProcessor fresh = Mockito.mock(PaymentProcessor.class);
        Mockito.when(fresh.refundedCents(orderRef)).thenReturn(java.util.OptionalLong.of(5000L));
        final PaymentCommands alwaysLosing = new PaymentCommands(() -> Caller.forActor(AuditActor.system()),
                new OrgCommands(() -> Caller.forActor(AuditActor.system())), new AuditCommands(),
                new PaymentRecorder() {
                    @Override
                    public String recordRefund(final Payment payment, final long refundedCents,
                            final String orgName, final String processorName) {
                        // Somebody else always moves the row between our read and our replace.
                        final Payment current = DAO.getInstance().getPayment(paymentId).orElseThrow();
                        current.setRefundedCents(current.getRefundedCents() + 1L);
                        try {
                            DAO.getInstance().transitionPayment(current, Payment.Status.RECORDED);
                        } catch (final IOException ex) {
                            throw new IllegalStateException(ex);
                        }
                        return super.recordRefund(payment, refundedCents, orgName, processorName);
                    }
                }, (cfg, sandbox) -> fresh, () -> mailer);
        assertEquals(alwaysLosing.refundFromWebhook(paymentId).getStatus(), "pending");
        Mockito.verify(fresh, Mockito.times(PaymentCommands.REFUND_ATTEMPTS)).refundedCents(orderRef);
    }

    @Test
    public void aSandboxRefundComputesButWritesNothing() {
        final Person tester = savedPerson();
        final PaymentCommands sandboxed = commandsFor(tester, true);
        final PaymentCommands.Start started = sandboxed.startPayment(inlineTrip(),
                Map.of(tester.getId().getValue(), "50"), null, true, "http://localhost/trip/payment.jsf");
        assertEquals(sandboxed.completePayment(started.getPaymentId(), null).getStatus(), "sandbox");
        assertEquals(sandboxed.fakeRefund(started.getClientSecret(), 1000L).getStatus(), "sandbox");
        assertTrue(txCmds.getTransactions(tester.getId()).isEmpty(), "the ledger is untouched");
    }

    // ------------------------------------------------------------------ method-aware re-pricing

    /**
     * The payer picks a bank debit inside the inline form: a payer-pays total is re-priced to the bank rate
     * (0.8% capped at $5.00 against the card gross-up), the processor's order follows, and picking a card
     * again prices it back. Everyone else gets the current total unchanged.
     */
    @Test
    public void pickingABankDebitRePricesAPayerPaysTotalAndACardPricesItBack() {
        final Person payer = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false);
        final PaymentCommands.Start started = commands.startPayment(inlineTrip(),
                Map.of(payer.getId().getValue(), "20"), null, false, "http://localhost/trip/payment.jsf");
        assertTrue(started.isPayerPays(), "the Summer Demo trip inherits CFPW's payer-pays default");
        assertEquals(started.getTotalFormatted(), "$21.24", "the card estimate: ceil((2000 + 49) / 0.9651)");
        final String paymentId = started.getPaymentId();

        final PaymentCommands.Reprice bank = commands.selectMethod(paymentId, "us_bank_account");
        assertTrue(bank.changed());
        assertTrue(bank.methodLocked(), "the processor now refuses anything but a bank debit at this total");
        assertEquals(bank.totalCents(), 2017L, "2000 + ceil(2000 x 80 / 9920)");
        assertEquals(bank.totalFormatted(), "$20.17");
        assertEquals(bank.creditFeeCents(), 17L);
        final Payment stored = DAO.getInstance().getPayment(paymentId).orElseThrow();
        assertEquals(stored.getTotalChargedCents(), 2017L);
        assertEquals(stored.getCreditFeeCents(), 17L);
        assertEquals(stored.getStatus(), Payment.Status.CREATED);
        assertEquals(stored.getPricedForMethod(), "us_bank_account");

        assertFalse(commands.selectMethod(paymentId, "us_bank_account").changed(), "same method = nothing moves");
        final PaymentCommands.Reprice card = commands.selectMethod(paymentId, "card");
        assertTrue(card.changed());
        assertFalse(card.methodLocked());
        assertEquals(card.totalCents(), 2124L, "back to the card estimate");
        assertNull(DAO.getInstance().getPayment(paymentId).orElseThrow().getPricedForMethod());
        assertFalse(commands.selectMethod(paymentId, "link").changed(), "any non-bank method is the card rate");

        // Bank-priced again: a card cannot pay it (the processor refuses the confirm, so nothing completed) ...
        assertEquals(commands.selectMethod(paymentId, "us_bank_account").totalCents(), 2017L);
        assertEquals(commands.completePayment(paymentId, null).getStatus(), "pending");
        assertEquals(DAO.getInstance().getPayment(paymentId).orElseThrow().getStatus(), Payment.Status.CREATED);
        // ... and the bank debit it is priced for does. The capture agrees with the re-priced total.
        assertTrue(commands.fakeMarkAsync(stored.getOrderRef()));
        assertEquals(commands.fakeSettle(stored.getOrderRef()).getStatus(), "recorded");
        assertEquals(DAO.getInstance().getPayment(paymentId).orElseThrow().getCapturedGrossCents(), 2017L);
        assertFalse(commands.selectMethod(paymentId, "card").changed(), "a finished payment is never re-priced");
        assertTrue(txCmds.getTransactions(payer.getId()).stream()
                .anyMatch(tx -> tx.getNote().contains("(minus $0.17 Test Processor fee)")),
                "the ledger names the bank fee the payer actually covered");
    }

    @Test
    public void rePricingIsThePayersOnlyAndLeavesOrgPaysAndRedirectOrdersAlone() throws IOException {
        final Person payer = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false);
        final String paymentId = commands.startPayment(inlineTrip(), Map.of(payer.getId().getValue(), "20"), null,
                false, "http://localhost/trip/payment.jsf").getPaymentId();
        assertNull(commandsFor(savedPerson(), false).selectMethod(paymentId, "us_bank_account"),
                "another person's payment reads as unknown");
        assertNull(commands.selectMethod("no-such-payment", "card"));
        assertTrue(commandsFor(savedPersonAsSiteAdmin(), false).selectMethod(paymentId, "us_bank_account").changed(),
                "a site admin may act on any payment");

        // The organization absorbs fees: the payer's total is the credits, whatever they pay with.
        final Payment orgPays = Payment.builder()
                .tripId(FakeData.FAKE2_TRIP_ID).orgId(FakeData.CFPW_ORG_ID).payerId(payer.getId())
                .processorConfigId(FakeData.CFPW_INLINE_PROCESSOR_ID).processorType(ProcessorType.FAKE)
                .allocations(List.of(new Payment.Allocation(payer.getId(), 2000L)))
                .feesPaidBy(FeesPaidBy.ORGANIZATION).creditFeeCents(119L).totalChargedCents(2000L)
                .orderRef("FAKE-orgpays").createdAt(java.time.LocalDateTime.now()).build();
        DAO.getInstance().createPayment(orgPays);
        final PaymentCommands.Reprice unchanged = commands.selectMethod(orgPays.getPaymentId(), "us_bank_account");
        assertFalse(unchanged.changed());
        assertEquals(unchanged.totalCents(), 2000L);

        // A processor whose order cannot be re-priced (the SPI default): the quoted total stands.
        final PaymentProcessor fixed = Mockito.mock(PaymentProcessor.class);
        Mockito.when(fixed.reprice(ArgumentMatchers.any(), ArgumentMatchers.anyLong(), ArgumentMatchers.any()))
                .thenReturn(PaymentProcessor.Repriced.UNSUPPORTED);
        final PaymentCommands.Reprice stuck = commandsFor(payer, false, (cfg, sandbox) -> fixed)
                .selectMethod(paymentId, "card");
        assertFalse(stuck.changed());
        final PaymentProcessor refusing = Mockito.mock(PaymentProcessor.class);
        Mockito.when(refusing.reprice(ArgumentMatchers.any(), ArgumentMatchers.anyLong(), ArgumentMatchers.any()))
                .thenThrow(new PaymentProcessor.ProcessorException("no"));
        assertFalse(commandsFor(payer, false, (cfg, sandbox) -> refusing).selectMethod(paymentId, "card").changed(),
                "a processor refusal keeps the old total rather than showing one it will not charge");
    }

    @Test
    public void abandonIsSiteAdminOnlyAndCreatedOnly() throws IOException {
        final Person payer = savedPerson();
        final PaymentCommands commands = commandsFor(payer, false);
        final String paymentId = commands.startPayment(trip(), Map.of(payer.getId().getValue(), "10"), null,
                false, "http://localhost/pay").getPaymentId();
        assertFalse(commands.abandon(paymentId), "the payer is not a site admin");
        final PaymentCommands admin = commandsFor(savedPersonAsSiteAdmin(), false);
        assertFalse(admin.abandon("no-such-payment"));
        assertTrue(admin.abandon(paymentId));
        assertEquals(DAO.getInstance().getPayment(paymentId).orElseThrow().getStatus(), Payment.Status.CANCELLED);
        assertFalse(admin.abandon(paymentId), "only CREATED can be abandoned");
        assertFalse(admin.getOpenPayments().stream().anyMatch(p -> p.getPaymentId().equals(paymentId)));
    }

    // ------------------------------------------------------------------ helpers

    private Trip inlineTrip() {
        return DAO.getInstance().getTrip(FakeData.FAKE2_TRIP_ID, Cached.NO).orElseThrow();
    }

    private Trip trip() {
        return DAO.getInstance().getTrip(FakeData.FAKE_TRIP_ID, Cached.NO).orElseThrow();
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
        return commandsFor(person, paymentsAdmin, false, procs, new AuditCommands());
    }

    private PaymentCommands commandsFor(final Person person, final boolean paymentsAdmin,
            final boolean registrationAdmin,
            final java.util.function.BiFunction<PaymentProcessorConfig, Boolean, PaymentProcessor> procs,
            final AuditCommands audit) {
        final PrivilegeCommands privs = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(privs.check(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(false);
        Mockito.when(privs.check(ArgumentMatchers.eq(PrivilegeCommands.PAYMENTS_ADMIN),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(paymentsAdmin);
        Mockito.when(privs.check(ArgumentMatchers.eq(PrivilegeCommands.REGISTRATION_ADMIN),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(registrationAdmin);
        final java.util.function.Supplier<Caller> callerSource = () -> new Caller(person.getId(),
                Boolean.TRUE.equals(person.getNotes() != null), // site admin rides in the notes field marker
                new AuditActor(person.getEmail(), person.getId().getValue()), privs);
        this.mailer = Mockito.mock(PaymentMailer.class);
        Mockito.when(mailer.sendConfirmation(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(true);
        return new PaymentCommands(callerSource, new OrgCommands(callerSource), audit,
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
