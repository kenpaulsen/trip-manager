package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.AgeBand;
import org.paulsens.trip.model.CheckoutConfig;
import org.paulsens.trip.model.Concession;
import org.paulsens.trip.model.DiscountCode;
import org.paulsens.trip.model.OptionChoice;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.PendingParty;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ProcessorType;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.RegistrationDraft;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripKind;
import org.paulsens.trip.pay.PaymentMailer;
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
 * Priced checkout end to end against FakeData and the FAKE redirect processor (docs/checkout.md): pricing a family,
 * starting the payment, recording it through PaymentCommands' one completion path, and the rows, roster, Bills,
 * balances and mail that follow; the free and offline paths; and every refusal.
 */
public class CheckoutCommandsTest {
    private static final String ADMISSION = "1";
    private static final String EXTRAS = "2";

    private final TransactionsCommands txCmds = new TransactionsCommands();
    private PaymentMailer mailer;

    @BeforeClass
    public void seed() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    // ------------------------------------------------------------------ the full family flow

    @Test
    public void aFamilyPaysOnceAndIsRegisteredConfirmedWithTicketsAndZeroBalances() throws IOException {
        final Trip trip = pricedTrip(true);
        final Person parent = savedPerson(LocalDate.of(1980, 1, 1));
        final Person child = savedPerson(trip.getStartDate().toLocalDate().minusYears(7));
        manage(parent, child);
        final RegistrationDraft draft = new RegistrationDraft();
        pick(draft, parent, "full", "shirt");
        pick(draft, child, "full", null);
        final Flow flow = flowFor(parent, false);

        final CheckoutCommands.Quote quote = flow.checkout.quote(trip, draft, List.of(parent, child));
        assertTrue(quote.isReady(), quote.toString());
        assertEquals(quote.creditCents(), 22000L, "adult 150 + shirt 20, child capped at 50");
        assertTrue(quote.feeCents() > 0, "CFPW's seeded defaults are payer-pays");
        assertEquals(quote.totalCents(), quote.creditCents() + quote.feeCents());
        assertFalse(quote.isFree());
        assertEquals(quote.getTotal(), org.paulsens.trip.pay.MoneyMath.formatCents(quote.totalCents()));
        assertEquals(quote.getFee(), org.paulsens.trip.pay.MoneyMath.formatCents(quote.feeCents()));

        final CheckoutCommands.Started started = flow.checkout.start(trip, draft, List.of(parent, child),
                parent.getId(), false, "http://localhost/trip/joinTrip.jsf");
        assertNotNull(started);
        assertNull(started.completion());
        final String paymentId = started.start().getPaymentId();
        assertTrue(DAO.getInstance().getRegistrations(trip.getId(), Cached.NO).isEmpty(),
                "pay-first: nothing is registered while the payment is open");

        assertEquals(flow.payments.completePayment(paymentId, null).getStatus(), "recorded");
        final Registration parentReg = registration(trip, parent);
        final Registration childReg = registration(trip, child);
        assertEquals(parentReg.getStatus(), Registration.Status.CONFIRMED);
        assertEquals(childReg.getRegistrationType(), "Full weekend");
        assertEquals(childReg.getPriceBreakdown().getTotalCents(), 5000L);
        assertNotNull(parentReg.getTicketCode());
        assertEquals(parentReg.getPaymentId(), paymentId);
        assertEquals(parentReg.getOptions().get(Registration.OPT_PARTY), childReg.getOptions().get(
                Registration.OPT_PARTY), "one party stamp");
        final Trip after = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        assertTrue(after.getPeople().containsAll(List.of(parent.getId(), child.getId())), "on the roster");

        final Payment payment = DAO.getInstance().getPayment(paymentId).orElseThrow();
        final Transaction bill = DAO.getInstance().getTransaction(child.getId(),
                CheckoutCommands.billTxId(payment, child.getId()), Cached.NO).orElseThrow();
        assertEquals(bill.getAmount(), -50.0f);
        assertEquals(bill.getCategory(), CheckoutCommands.BILL_CATEGORY);
        assertTrue(bill.getNote().startsWith("Full weekend for "), bill.getNote());
        assertEquals(txCmds.getBalance(child.getId()), 0.0, "the bill and the credit cancel out");
        assertEquals(txCmds.getBalance(parent.getId()), 0.0);
        assertNotNull(payment.getCheckout().getFinalizedAt());
        Mockito.verify(mailer).sendCheckoutConfirmation(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());
        assertTrue(flow.checkout.intentSummary(payment).startsWith("2 people, $220.00, registered"));

        // The return page again: nothing new, no second mail.
        assertEquals(flow.checkout.complete(paymentId, null).getStatus(), "recorded");
        assertEquals(registration(trip, parent).getTicketCode(), parentReg.getTicketCode(), "never re-minted");
        Mockito.verify(mailer, Mockito.times(1)).sendCheckoutConfirmation(ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
        assertEquals(flow.checkout.breakdownFor(DAO.getInstance().getRegistrations(trip.getId(), Cached.NO),
                child.getId().getValue()).getTotalCents(), 5000L);
    }

    @Test
    public void withoutAutoConfirmAPaidRegistrationWaitsForApproval() throws IOException {
        final Trip trip = pricedTrip(false);
        final Person person = savedPerson(LocalDate.of(1990, 5, 5));
        final RegistrationDraft draft = new RegistrationDraft();
        pick(draft, person, "fri", null);
        final Flow flow = flowFor(person, false);
        final CheckoutCommands.Started started = flow.checkout.start(trip, draft, List.of(person), null, false,
                "http://localhost/trip/joinTrip.jsf");
        assertEquals(flow.payments.completePayment(started.start().getPaymentId(), null).getStatus(), "recorded");
        assertEquals(registration(trip, person).getStatus(), Registration.Status.PENDING);
        assertFalse(DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow().getPeople()
                .contains(person.getId()), "the roster waits for the approval");
    }

    /**
     * A deposit policy (docs/checkout.md): the processor charges each person's price capped at the deposit, the
     * Bills still bill the full prices, so the rest shows on each balance; an offline payment must match the
     * deposits unless partial, which admits anything up to the prices.
     */
    @Test
    public void aDepositPolicyChargesTheDepositsNowAndBillsTheFullPrices() throws IOException {
        final Trip trip = pricedTrip(true, 5000L, LocalDate.of(2027, 3, 1));
        final Person parent = savedPerson(LocalDate.of(1980, 1, 1));
        final Person infant = savedPerson(trip.getStartDate().toLocalDate().minusYears(2));
        manage(parent, infant);
        final RegistrationDraft draft = new RegistrationDraft();
        pick(draft, parent, "full", "shirt");
        pick(draft, infant, "full", "shirt");
        final Flow flow = flowFor(parent, false);

        final CheckoutCommands.Quote quote = flow.checkout.quote(trip, draft, List.of(parent, infant));
        assertTrue(quote.isReady(), quote.toString());
        assertEquals(quote.priceCents(), 19000L, "adult 150 + shirt 20; infant free + shirt 20");
        assertEquals(quote.creditCents(), 7000L, "the adult's $50 deposit; the infant's $20 is below it");
        assertEquals(quote.balanceCents(), 12000L);
        assertTrue(quote.isDeposit());
        assertEquals(quote.getDeposit(), "$50.00");
        assertEquals(quote.getPrice(), "$190.00");
        assertEquals(quote.getDueNow(), "$70.00");
        assertEquals(quote.getBalance(), "$120.00");
        assertEquals(quote.getBalanceDue(), "March 1, 2027");
        assertEquals(quote.totalCents(), quote.creditCents() + quote.feeCents(), "the fee is on what is charged");

        final CheckoutCommands.Started started = flow.checkout.start(trip, draft, List.of(parent, infant),
                parent.getId(), false, "http://localhost/trip/joinTrip.jsf");
        final String paymentId = started.start().getPaymentId();
        final Payment open = DAO.getInstance().getPayment(paymentId).orElseThrow();
        assertEquals(open.getAllocations().stream().mapToLong(Payment.Allocation::getAmountCents).sum(), 7000L);
        assertEquals(open.getTotalChargedCents(), 7000L + open.getCreditFeeCents());
        assertEquals(flow.payments.completePayment(paymentId, null).getStatus(), "recorded");
        assertEquals(registration(trip, parent).getPriceBreakdown().getTotalCents(), 17000L, "the price stays");
        assertEquals(txCmds.getBalance(parent.getId()), -120.0, "billed $170, paid the $50 deposit");
        assertEquals(txCmds.getBalance(infant.getId()), 0.0, "billed $20, paid $20");
        assertTrue(flow.checkout.intentSummary(open).startsWith("2 people, $190.00"), "the summary names the prices");

        // Offline under a deposit policy: the deposits, or any amount up to the prices with Partial.
        final Person admin = savedPerson(LocalDate.of(1970, 1, 1));
        final Person attendee = savedPerson(LocalDate.of(1985, 1, 1));
        final Flow adminFlow = flowFor(admin, true);
        final RegistrationDraft offline = new RegistrationDraft();
        pick(offline, attendee, "full", null);
        assertNull(adminFlow.checkout.recordOffline(trip, offline, List.of(attendee), attendee.getId(), "CHECK",
                "7", 150.0, null, false), "the full price is not the deposit: needs Partial");
        assertNull(adminFlow.checkout.recordOffline(trip, offline, List.of(attendee), attendee.getId(), "CHECK",
                "7", 160.0, null, true), "never more than the price");
        assertEquals(adminFlow.checkout.recordOffline(trip, offline, List.of(attendee), attendee.getId(), "CHECK",
                "7", 150.0, null, true).getStatus(), "recorded");
        assertEquals(txCmds.getBalance(attendee.getId()), 0.0, "a check for the whole price settles it");
        final Person other = savedPerson(LocalDate.of(1986, 1, 1));
        final RegistrationDraft deposit = new RegistrationDraft();
        pick(deposit, other, "full", null);
        assertEquals(adminFlow.checkout.recordOffline(trip, deposit, List.of(other), other.getId(), "CASH", null,
                50.0, null, false).getStatus(), "recorded");
        assertEquals(txCmds.getBalance(other.getId()), -100.0, "the deposit in cash, $100 left");
    }

    /** The trip editor's property-sheet summary and the "no processor" warning. */
    @Test
    public void thePolicySummaryNamesWhatTheEditorChose() throws IOException {
        final Flow flow = flowFor(savedPerson(LocalDate.of(1980, 1, 1)), false);
        assertEquals(flow.checkout.policySummary(null), "off");
        final Trip off = Trip.builder().title("Plain").build();
        assertEquals(flow.checkout.policySummary(off), "off");
        assertEquals(flow.checkout.policySummary(pricedTrip(false)),
                "on: full payment, approval required, 2 age bands, 1 discount code");
        final Trip deposit = pricedTrip(true, 20000L, null);
        assertEquals(flow.checkout.policySummary(deposit),
                "on: deposit $200.00 per person, auto-confirmed, 2 age bands, 1 discount code");
        deposit.getCheckout().getAgeBands().clear();
        deposit.getCheckout().getCodes().clear();
        assertEquals(flow.checkout.policySummary(deposit), "on: deposit $200.00 per person, auto-confirmed");
        assertTrue(flow.checkout.isPayable(deposit), "CFPW's seeded default processor");
        assertFalse(flow.checkout.isPayable(null));
    }

    // ------------------------------------------------------------------ free and offline

    @Test
    public void aFreePartyIsRegisteredAtOnceWithoutAProcessor() throws IOException {
        final Trip trip = pricedTrip(true);
        final Person infant = savedPerson(trip.getStartDate().toLocalDate().minusYears(2));
        final RegistrationDraft draft = new RegistrationDraft();
        pick(draft, infant, "full", null);
        final Flow flow = flowFor(infant, false);
        final CheckoutCommands.Started started = flow.checkout.start(trip, draft, List.of(infant), null, false,
                "http://localhost/trip/joinTrip.jsf");
        assertNull(started.start(), "nothing to pay, no processor");
        assertEquals(started.completion().getStatus(), "recorded");
        final Registration reg = registration(trip, infant);
        assertEquals(reg.getStatus(), Registration.Status.CONFIRMED);
        final Payment free = DAO.getInstance().getPayment(reg.getPaymentId()).orElseThrow();
        assertEquals(free.getCaptureId(), "FREE");
        assertEquals(free.getOffline(), new Payment.OfflineReceipt("FREE", null, LocalDate.now()),
                "FREE is a method too, received the day it was entered");
        assertEquals(started.completion().getPaymentId(), free.getPaymentId(), "the completion names the payment");
        assertEquals(started.completion().getOffline(), free.getOffline(), "and echoes its receipt");
        assertEquals(flow.payments.methodOf(free), "Free");
        assertEquals(txCmds.getBalance(infant.getId()), 0.0, "no bill for a free registration");
    }

    @Test
    public void aRegistrationAdminRecordsAnOfflinePayment() throws IOException {
        final Trip trip = pricedTrip(true);
        final Person admin = savedPerson(LocalDate.of(1970, 1, 1));
        final Person attendee = savedPerson(LocalDate.of(1985, 1, 1));
        final Flow flow = flowFor(admin, true);
        assertTrue(flow.checkout.canAdminister(trip));

        final RegistrationDraft draft = new RegistrationDraft();
        pick(draft, attendee, "full", null);
        assertNull(flow.checkout.recordOffline(trip, draft, List.of(attendee), attendee.getId(), "BITCOIN", null,
                150.0, null, false), "an unknown method is refused");
        assertNull(flow.checkout.recordOffline(trip, draft, List.of(attendee), attendee.getId(), "CHECK", "1042",
                100.0, null, false), "less than the total needs Partial");
        assertNull(flow.checkout.recordOffline(trip, draft, List.of(attendee), attendee.getId(), "CASH", null,
                200.0, null, true), "never more than the total");

        final PaymentCommands.Completion done = flow.checkout.recordOffline(trip, draft, List.of(attendee),
                attendee.getId(), "check", "1042", 100.0, LocalDate.of(2027, 1, 5), true);
        assertEquals(done.getStatus(), "recorded");
        final Registration reg = registration(trip, attendee);
        final Payment payment = DAO.getInstance().getPayment(reg.getPaymentId()).orElseThrow();
        assertEquals(payment.getCaptureId(), "CHECK 1042", "the label the ledger and the mail print, unchanged");
        assertEquals(payment.getOffline(), new Payment.OfflineReceipt("CHECK", "1042", LocalDate.of(2027, 1, 5)),
                "and the same facts structured: method, reference, the date given");
        assertEquals(payment.getCapturedAt(), LocalDate.of(2027, 1, 5).atStartOfDay());
        assertEquals(done.getPaymentId(), payment.getPaymentId());
        assertEquals(done.getOffline(), payment.getOffline(), "the completion echoes the receipt for REST");
        assertEquals(flow.payments.methodOf(payment), "Check 1042");
        assertEquals(PaymentCommands.paymentMethodOf(payment), "Check");
        assertEquals(PaymentCommands.paymentReferenceOf(payment), "1042");
        assertEquals(PaymentCommands.paymentReceivedOn(payment), LocalDate.of(2027, 1, 5));
        assertEquals(PaymentCommands.paidWith(payment), "Check 1042, received 1/5/2027");
        assertEquals(payment.getPayerId(), attendee.getId(), "the attendee's payment, entered by the admin");
        assertEquals(txCmds.getBalance(attendee.getId()), -50.0, "billed in full, $50 still owed");

        final Person comped = savedPerson(LocalDate.of(1960, 1, 1));
        final RegistrationDraft compDraft = new RegistrationDraft();
        pick(compDraft, comped, "full", null);
        assertEquals(flow.checkout.recordOffline(trip, compDraft, List.of(comped), comped.getId(), "COMP", null,
                null, null, false).getStatus(), "recorded");
        assertEquals(txCmds.getBalance(comped.getId()), 0.0, "a comp is credited in full");
        final Payment comp = DAO.getInstance().getPayment(registration(trip, comped).getPaymentId()).orElseThrow();
        assertEquals(comp.getCaptureId(), "COMP");
        assertEquals(comp.getOffline(), new Payment.OfflineReceipt("COMP", null, LocalDate.now()),
                "COMP is a method with no reference, received today when no date is given");
        assertEquals(PaymentCommands.paymentReferenceOf(comp), "");

        final Person cashPayer = savedPerson(LocalDate.of(1962, 2, 2));
        final RegistrationDraft cashDraft = new RegistrationDraft();
        pick(cashDraft, cashPayer, "full", null);
        assertEquals(flow.checkout.recordOffline(trip, cashDraft, List.of(cashPayer), cashPayer.getId(), "CASH",
                "  ", 150.0, LocalDate.of(2027, 2, 1), false).getStatus(), "recorded");
        final Payment cash = DAO.getInstance().getPayment(registration(trip, cashPayer).getPaymentId())
                .orElseThrow();
        assertEquals(cash.getCaptureId(), "CASH", "a blank reference is no reference");
        assertEquals(cash.getOffline(), new Payment.OfflineReceipt("CASH", null, LocalDate.of(2027, 2, 1)));
        assertEquals(PaymentCommands.paidWith(cash), "Cash, received 2/1/2027");

        // A processor payment answers the same questions from its processor and capture (no org: the generic name).
        final Payment card = Payment.builder().processorType(ProcessorType.FAKE).captureId("CAP-77")
                .capturedAt(LocalDate.of(2027, 3, 4).atTime(9, 30)).build();
        assertNull(card.getOfflineReceipt());
        assertEquals(flow.payments.methodOf(card), "the payment processor");
        assertEquals(PaymentCommands.paymentMethodOf(card), "the payment processor");
        assertEquals(PaymentCommands.paymentReferenceOf(card), "CAP-77");
        assertEquals(PaymentCommands.paymentReceivedOn(card), LocalDate.of(2027, 3, 4));
        assertEquals(PaymentCommands.paidWith(card), "the payment processor (id #CAP-77), 3/4/2027");
        final Payment uncaptured = Payment.builder().processorType(ProcessorType.FAKE).build();
        assertNull(PaymentCommands.paymentReceivedOn(uncaptured));
        assertEquals(PaymentCommands.paymentReferenceOf(uncaptured), "");
        assertEquals(PaymentCommands.paidWith(uncaptured), "the payment processor");

        final Person someone = savedPerson(LocalDate.of(1990, 1, 1));
        final RegistrationDraft other = new RegistrationDraft();
        pick(other, someone, "full", null);
        assertNull(flowFor(someone, false).checkout.recordOffline(trip, other, List.of(someone), someone.getId(),
                "CASH", null, 150.0, null, false), "only a registration admin records offline payments");
    }

    /** The admin who started a checkout for someone else (its payer is that person) may cancel it; others not. */
    @Test
    public void theAdminWhoStartedACheckoutForSomeoneMayCancelIt() throws IOException {
        final Trip trip = pricedTrip(true);
        final Person admin = savedPerson(LocalDate.of(1970, 1, 1));
        final Person attendee = savedPerson(LocalDate.of(1985, 1, 1));
        final Flow flow = flowFor(admin, true);
        final RegistrationDraft draft = new RegistrationDraft();
        pick(draft, attendee, "full", null);
        final CheckoutCommands.Started started = flow.checkout.start(trip, draft, List.of(attendee),
                attendee.getId(), false, "http://localhost/trip/joinTrip.jsf");
        final String paymentId = started.start().getPaymentId();
        assertEquals(DAO.getInstance().getPayment(paymentId).orElseThrow().getPayerId(), attendee.getId());

        final Person stranger = savedPerson(LocalDate.of(1990, 1, 1));
        assertFalse(flowFor(stranger, false).payments.cancelPayment(paymentId), "not theirs to cancel");
        assertTrue(flow.payments.cancelPayment(paymentId), "the registration admin's Change registration");
        assertEquals(DAO.getInstance().getPayment(paymentId).orElseThrow().getStatus(), Payment.Status.CANCELLED);
    }

    @Test
    public void aSandboxCheckoutRegistersNobody() throws IOException {
        final Trip trip = pricedTrip(true);
        final Person tester = savedPerson(LocalDate.of(1980, 1, 1));
        final RegistrationDraft draft = new RegistrationDraft();
        pick(draft, tester, "full", null);
        final Flow flow = flowFor(tester, true);
        final CheckoutCommands.Started started = flow.checkout.start(trip, draft, List.of(tester), null, true,
                "http://localhost/trip/joinTrip.jsf");
        assertTrue(DAO.getInstance().getPayment(started.start().getPaymentId()).orElseThrow().isSandbox());
        flow.payments.completePayment(started.start().getPaymentId(), null);
        assertTrue(DAO.getInstance().getRegistration(trip.getId(), tester.getId(), Cached.NO).isEmpty(),
                "a sandbox payment writes no registrations, as it writes no ledger rows");
    }

    // ------------------------------------------------------------------ refusals

    @Test
    public void everyProblemRefusesTheStartAndNothingIsCharged() throws IOException {
        final Trip trip = pricedTrip(true);
        final Person me = savedPerson(LocalDate.of(1980, 1, 1));
        final Person stranger = savedPerson(LocalDate.of(1981, 1, 1));
        final Flow flow = flowFor(me, false);

        final RegistrationDraft unanswered = new RegistrationDraft();
        unanswered.getSel().put(me.getId().getValue(), Boolean.TRUE);
        assertNull(start(flow, trip, unanswered, me), "the admission question is required");

        final RegistrationDraft hotel = new RegistrationDraft();
        pick(hotel, me, "hotel", null);
        assertNull(start(flow, trip, hotel, me), "the hotel choice needs its attestation");
        hotel.getAttest().put(RegistrationDraft.attestKey(me.getId().getValue(), "hotel"), Boolean.TRUE);
        assertTrue(flow.checkout.quote(trip, hotel, List.of(me)).isReady(), "ticked, it is fine");

        final RegistrationDraft strangers = new RegistrationDraft();
        pick(strangers, stranger, "full", null);
        assertNull(start(flow, trip, strangers, stranger), "not mine to register");
        assertFalse(flow.checkout.problemsBeforeStart(trip, strangers, List.of(stranger)).isEmpty(),
                "the REST edge hears the same refusal, coded");

        final RegistrationDraft badCode = new RegistrationDraft();
        pick(badCode, me, "fri", null);
        badCode.getCodes().put(me.getId().getValue(), "speaker");
        assertNull(start(flow, trip, badCode, me), "SPEAKER is for the full weekend only");

        assertNull(start(flow, trip, new RegistrationDraft(), me), "nobody selected");
        trip.getCheckout().setClosesAt(LocalDateTime.now().minusDays(1));
        final RegistrationDraft late = new RegistrationDraft();
        pick(late, me, "full", null);
        assertNull(start(flow, trip, late, me), "registration has closed");
        assertEquals(flow.checkout.problemsBeforeStart(trip, late, List.of(me)).get(0).code(),
                org.paulsens.trip.checkout.CheckoutProblem.CHECKOUT_CLOSED);
        assertTrue(DAO.getInstance().getRegistrations(trip.getId(), Cached.NO).isEmpty());
    }

    @Test
    public void someoneRegisteredMeanwhileIsNotedNeverOverwritten() throws IOException {
        final Trip trip = pricedTrip(true);
        final Person person = savedPerson(LocalDate.of(1980, 1, 1));
        final RegistrationDraft draft = new RegistrationDraft();
        pick(draft, person, "full", null);
        final Flow flow = flowFor(person, false);
        final String paymentId = flow.checkout.start(trip, draft, List.of(person), null, false,
                "http://localhost/trip/joinTrip.jsf").start().getPaymentId();
        // The office registers them by hand while the payment is open.
        assertTrue(DAO.getInstance().saveRegistration(new Registration(trip.getId(), person.getId())
                .withStatus(Registration.Status.PENDING)));

        assertEquals(flow.payments.completePayment(paymentId, null).getStatus(), "recorded");
        assertEquals(registration(trip, person).getStatus(), Registration.Status.PENDING, "left as it was");
        final PendingParty party = DAO.getInstance().getPayment(paymentId).orElseThrow().getCheckout();
        assertTrue(party.getWarnings().get(0).contains("was already registered"), party.getWarnings().toString());
        assertEquals(txCmds.getBalance(person.getId()), 0.0, "money moved, so the bill is still written");
    }

    @Test
    public void aRecordedButUnfinalizedPaymentIsRepairedOnTheNextVisit() throws IOException {
        final Trip trip = pricedTrip(true);
        final Person person = savedPerson(LocalDate.of(1980, 1, 1));
        final RegistrationDraft draft = new RegistrationDraft();
        pick(draft, person, "full", null);
        final Flow flow = flowFor(person, false);
        final String paymentId = flow.checkout.start(trip, draft, List.of(person), null, false,
                "http://localhost/trip/joinTrip.jsf").start().getPaymentId();
        // What an older build would leave: the payment RECORDED, the party never written.
        final Payment stored = DAO.getInstance().getPayment(paymentId).orElseThrow();
        stored.setCaptureId("OLD-BUILD");
        stored.setStatus(Payment.Status.RECORDED);
        DAO.getInstance().transitionPayment(stored, Payment.Status.CREATED);

        assertEquals(flow.payments.completePayment(paymentId, null).getStatus(), "recorded");
        assertEquals(registration(trip, person).getStatus(), Registration.Status.CONFIRMED);
        assertNotNull(DAO.getInstance().getPayment(paymentId).orElseThrow().getCheckout().getFinalizedAt());
    }

    // ------------------------------------------------------------------ views

    @Test
    public void choiceLabelsShowEachPersonsOwnPrice() throws IOException {
        final Trip trip = pricedTrip(true);
        final RegistrationOption admission = trip.getRegOptions().get(0);
        final OptionChoice full = admission.choice("full");
        final Person child = savedPerson(trip.getStartDate().toLocalDate().minusYears(6));
        final Flow flow = flowFor(child, false);
        assertEquals(flow.checkout.choiceLabel(trip, admission, full, child), "Full weekend ($50.00)");
        assertEquals(flow.checkout.choiceLabel(trip, admission, full, null), "Full weekend ($150.00)");
        final RegistrationOption extras = trip.getRegOptions().get(1);
        assertEquals(flow.checkout.choiceLabel(trip, extras, extras.choice("shirt"), child), "T-shirt ($20.00)",
                "age bands never touch the shirt");
        assertEquals(flow.checkout.choiceLabel(trip, admission, new OptionChoice("Free day"), child), "Free day");
        assertEquals(flow.checkout.choiceLabel(trip, admission, null, child), "");
        assertTrue(flow.checkout.isEnabled(trip));
        assertFalse(flow.checkout.isEnabled(null));
        assertEquals(flow.checkout.getOfflineMethods(), CheckoutCommands.OFFLINE_METHODS);
        assertEquals(flow.checkout.intentSummary(null), "");
        assertEquals(CheckoutCommands.allocate(List.of(new Payment.Allocation(child.getId(), 5000L),
                new Payment.Allocation(Person.Id.newInstance(), 3000L)), 6000L).get(1).getAmountCents(), 1000L);
    }

    // ------------------------------------------------------------------ fixtures

    /** A conference: Full weekend $150 / Friday $60 / hotel weekend $120 (attested), a $20 shirt add-on. */
    private static Trip pricedTrip(final boolean autoConfirm) throws IOException {
        return pricedTrip(autoConfirm, null, null);
    }

    private static Trip pricedTrip(final boolean autoConfirm, final Long depositCents, final LocalDate balanceDueOn)
            throws IOException {
        final RegistrationOption admission = new RegistrationOption(Integer.parseInt(ADMISSION), "Admission", "",
                true, RegistrationOption.QuestionType.CHOICE, true, List.of(
                        new OptionChoice("full", "Full weekend", 15000L, null, null, null, null),
                        new OptionChoice("fri", "Friday only", 6000L, null, null, null, null),
                        new OptionChoice("hotel", "Hotel weekend", 12000L, null, "I am staying at the hotel", null,
                                null)));
        final RegistrationOption extras = new RegistrationOption(Integer.parseInt(EXTRAS), "Extras", "", true,
                RegistrationOption.QuestionType.MULTI_CHOICE, false,
                List.of(new OptionChoice("shirt", "T-shirt", 2000L, null, null, null, null)));
        final CheckoutConfig policy = new CheckoutConfig(Boolean.TRUE, autoConfirm ? Boolean.TRUE : null, null, null,
                List.of(new AgeBand("Infant (0-3)", 0, 3, concession(Concession.Kind.COMPLIMENTARY, 0L)),
                        new AgeBand("Child (4-10)", 4, 10, concession(Concession.Kind.FIXED_PRICE, 5000L))),
                List.of(new DiscountCode("SPEAKER", null, concession(Concession.Kind.FIXED_PRICE, 0L), null,
                        List.of("full"), null, 5)), depositCents, balanceDueOn);
        final Trip trip = Trip.builder().title("Checkout " + RandomData.genAlpha(6)).kind(TripKind.CONFERENCE)
                .orgId(FakeData.CFPW_ORG_ID).startDate(LocalDateTime.now().plusDays(40))
                .endDate(LocalDateTime.now().plusDays(42)).regOptions(List.of(admission, extras)).checkout(policy)
                .build();
        trip.setChatEnabled(false);
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private static Concession concession(final Concession.Kind kind, final long cents) {
        return new Concession(kind, 0, cents, null, null, null);
    }

    private static void pick(final RegistrationDraft draft, final Person person, final String admission,
            final String extras) {
        final String pid = person.getId().getValue();
        draft.getSel().put(pid, Boolean.TRUE);
        final Registration reg = new Registration("draft", person.getId());
        reg.getOptions().put(ADMISSION, admission);
        if (extras != null) {
            reg.getOptions().put(EXTRAS, extras);
        }
        draft.getRegs().put(pid, reg);
    }

    private static CheckoutCommands.Started start(final Flow flow, final Trip trip, final RegistrationDraft draft,
            final Person person) {
        return flow.checkout.start(trip, draft, List.of(person), null, false, "http://localhost/trip/joinTrip.jsf");
    }

    private static Registration registration(final Trip trip, final Person person) {
        return DAO.getInstance().getRegistration(trip.getId(), person.getId(), Cached.NO).orElseThrow();
    }

    /** The two beans wired to each other for one caller (they build each other lazily in production). */
    private record Flow(PaymentCommands payments, CheckoutCommands checkout) {
    }

    private Flow flowFor(final Person person, final boolean admin) {
        final PrivilegeCommands privs = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(privs.check(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(false);
        Mockito.when(privs.check(ArgumentMatchers.eq(PrivilegeCommands.REGISTRATION_ADMIN), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(admin);
        Mockito.when(privs.check(ArgumentMatchers.eq(PrivilegeCommands.PAYMENTS_ADMIN), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(admin);
        final java.util.function.Supplier<Caller> callerSource = () -> new Caller(person.getId(), false,
                new AuditActor(person.getEmail(), person.getId().getValue()), privs);
        mailer = Mockito.mock(PaymentMailer.class);
        Mockito.when(mailer.sendCheckoutConfirmation(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(true);
        final CheckoutCommands[] checkout = new CheckoutCommands[1];
        final PaymentCommands payments = new PaymentCommands(callerSource, new OrgCommands(callerSource),
                new AuditCommands(), new PaymentRecorder(), PaymentProcessors::forConfig, () -> mailer,
                () -> checkout[0]);
        checkout[0] = new CheckoutCommands(callerSource, () -> payments, LocalDateTime::now);
        return new Flow(payments, checkout[0]);
    }

    private static Person savedPerson(final LocalDate birthdate) throws IOException {
        final Person person = Person.builder().first("Co" + RandomData.genAlpha(5)).last(RandomData.genAlpha(7))
                .email("co." + RandomData.genAlpha(10) + "@example.com").birthdate(birthdate).build();
        assertTrue(DAO.getInstance().savePerson(person));
        new OrgCommands(() -> new Caller(person.getId(), true, AuditActor.system(), new PrivilegeCommands()))
                .addMember(FakeData.CFPW_ORG_ID, person.getId());
        return person;
    }

    private static void manage(final Person manager, final Person managed) throws IOException {
        manager.getManagedUsers().add(managed.getId());
        assertTrue(DAO.getInstance().savePerson(manager));
    }
}
