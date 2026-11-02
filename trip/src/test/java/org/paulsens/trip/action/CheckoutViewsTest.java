package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
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
import org.paulsens.trip.model.PriceBreakdown;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.RegistrationDraft;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripKind;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The page view helpers at the end of {@link CheckoutCommands}: what the registration page, the trip editor's
 * Pricing fieldset and the Registrations tab read (docs/checkout.md). No flow here; that is
 * {@link CheckoutCommandsTest}.
 */
public class CheckoutViewsTest {

    @BeforeClass
    public void seed() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    // ------------------------------------------------------------------ the registration page

    @Test
    public void attestationsAreThePickedChoicesThatNeedOne() {
        final CheckoutCommands views = viewsFor(null, false);
        final RegistrationOption admission = admission();
        final RegistrationOption extras = extras();
        final Map<String, String> answers = new HashMap<>();
        assertTrue(views.attestationsFor(admission, answers).isEmpty(), "nothing picked");
        answers.put(admission.getKey(), "hotel");
        assertEquals(views.attestationsFor(admission, answers).get(0).getId(), "hotel");
        answers.put(admission.getKey(), "full");
        assertTrue(views.attestationsFor(admission, answers).isEmpty(), "full needs no statement");
        answers.put(extras.getKey(), "shirt,vow");
        assertEquals(views.attestationsFor(extras, answers).size(), 1, "pick-any: only the attested one");
        assertTrue(views.attestationsFor(null, answers).isEmpty());
        assertTrue(views.attestationsFor(admission, null).isEmpty());
        assertTrue(views.attestationsFor(new RegistrationOption(9, "Text", "", true), answers).isEmpty());
        assertEquals(views.attestKey("p1", "hotel"), "p1|hotel");
    }

    @Test
    public void breakdownLinesShowPricesThenSignedChanges() {
        final CheckoutCommands views = viewsFor(null, false);
        final PriceBreakdown breakdown = new PriceBreakdown(null, List.of(
                new PriceBreakdown.Line("Full weekend", PriceBreakdown.LineKind.BASE, 15000L, null),
                new PriceBreakdown.Line("Child (4-10)", PriceBreakdown.LineKind.AGE_BAND, -10000L, null),
                new PriceBreakdown.Line("Late fee", PriceBreakdown.LineKind.DISCOUNT_CODE, 500L, null),
                new PriceBreakdown.Line("No birthdate", PriceBreakdown.LineKind.NOTE, 0L, null)),
                5500L, "Full weekend", LocalDateTime.now());
        final List<CheckoutCommands.LineView> lines = views.breakdownLines(breakdown);
        assertEquals(lines.get(0).amount(), "$150.00");
        assertEquals(lines.get(1).amount(), "-$100.00");
        assertEquals(lines.get(2).amount(), "+$5.00");
        assertEquals(lines.get(3).amount(), "");
        assertEquals(lines.get(1).kind(), "AGE_BAND");
        assertTrue(views.breakdownLines(null).isEmpty());
        assertEquals(views.formatCents(123456L), "$1,234.56");
    }

    @Test
    public void thePayerIsTheSubjectOnlyForAnAdmin() {
        final Person.Id me = Person.Id.newInstance();
        final Person.Id subject = Person.Id.newInstance();
        final Trip trip = trip();
        assertEquals(viewsFor(me, false).payerFor(trip, subject), me, "a family manager pays as themselves");
        assertEquals(viewsFor(me, true).payerFor(trip, subject), subject, "an admin pays on the subject's behalf");
        assertEquals(viewsFor(me, true).payerFor(trip, null), me);
    }

    @Test
    public void selectedIdsFollowThePartyOrder() {
        final Person first = Person.builder().first("A").last("One").build();
        final Person second = Person.builder().first("B").last("Two").build();
        final RegistrationDraft draft = new RegistrationDraft();
        draft.getSel().put(second.getId().getValue(), Boolean.TRUE);
        draft.getSel().put(first.getId().getValue(), Boolean.FALSE);
        final CheckoutCommands views = viewsFor(null, false);
        assertEquals(views.selectedIds(draft, List.of(first, second)), List.of(second.getId().getValue()));
        assertTrue(views.selectedIds(null, List.of(first)).isEmpty());
        assertTrue(views.selectedIds(draft, null).isEmpty());
    }

    @Test
    public void partyIdsAreForThePayerAndTheTripsAdminsOnly() throws IOException {
        final Person payer = savedPerson();
        final Person other = savedPerson();
        final Trip trip = trip();
        final Registration row = new Registration(trip.getId(), payer.getId());
        final Payment payment = Payment.builder().tripId(trip.getId()).orgId(trip.getOrgId()).payerId(payer.getId())
                .status(Payment.Status.CREATED).createdAt(LocalDateTime.now()).build();
        payment.setCheckout(new PendingParty("party", payer.getId(), List.of(row), null, null));
        DAO.getInstance().createPayment(payment);

        assertEquals(viewsFor(payer.getId(), false).partyIdsOf(payment.getPaymentId()),
                List.of(payer.getId().getValue()));
        assertEquals(viewsFor(other.getId(), true).partyIdsOf(payment.getPaymentId()).size(), 1,
                "the trip's registration admin");
        assertTrue(viewsFor(other.getId(), false).partyIdsOf(payment.getPaymentId()).isEmpty(), "anyone else");
        assertTrue(viewsFor(payer.getId(), false).partyIdsOf("nope").isEmpty());
        assertTrue(viewsFor(payer.getId(), false).partyIdsOf(null).isEmpty());

        final Payment plain = Payment.builder().tripId(trip.getId()).payerId(payer.getId())
                .status(Payment.Status.CREATED).createdAt(LocalDateTime.now()).build();
        DAO.getInstance().createPayment(plain);
        assertTrue(viewsFor(payer.getId(), false).partyIdsOf(plain.getPaymentId()).isEmpty(), "not a checkout");
    }

    @Test
    public void resultRowsReadTheStoredRegistrationsTheCallerMaySee() throws IOException {
        final Person me = savedPerson();
        final Person stranger = savedPerson();
        final Trip trip = trip();
        final PriceBreakdown breakdown = new PriceBreakdown(null, List.of(), 5000L, "Full weekend",
                LocalDateTime.now());
        assertTrue(DAO.getInstance().saveRegistration(RegistrationCommands.withTicket(
                new Registration(trip.getId(), me.getId(), LocalDateTime.now(), Registration.Status.CONFIRMED,
                        new HashMap<>()).withRegistrationType("Full weekend").withPriceBreakdown(breakdown))));
        final List<String> ids = List.of(me.getId().getValue(), stranger.getId().getValue());

        final List<CheckoutCommands.ResultRow> mine = viewsFor(me.getId(), false).resultRows(trip, ids);
        assertEquals(mine.size(), 1, "a stranger's row is left out");
        assertEquals(mine.get(0).registrationType(), "Full weekend");
        assertEquals(mine.get(0).price(), "$50.00");
        assertEquals(mine.get(0).status(), "Confirmed");
        assertFalse(mine.get(0).ticketCode().isEmpty());

        final List<CheckoutCommands.ResultRow> admin = viewsFor(stranger.getId(), true).resultRows(trip, ids);
        assertEquals(admin.size(), 2);
        assertEquals(admin.get(1).status(), "Not Registered");
        assertEquals(admin.get(1).price(), "");
        assertTrue(viewsFor(me.getId(), false).resultRows(null, ids).isEmpty());
        assertTrue(viewsFor(me.getId(), false).resultRows(trip, null).isEmpty());
    }

    // ------------------------------------------------------------------ the Pricing fieldset

    @Test
    public void acceptConfigReportsEveryProblem() {
        final CheckoutCommands views = viewsFor(null, false);
        final Trip trip = trip();
        assertTrue(views.acceptConfig(trip));
        trip.getCheckout().getCodes().add(new DiscountCode());
        trip.getCheckout().getAgeBands().add(new AgeBand("A", 0, 10, null));
        trip.getCheckout().getAgeBands().add(new AgeBand("B", 5, null, null));
        assertEquals(views.configProblems(trip).size(), 2);
        assertFalse(views.acceptConfig(trip));
        assertTrue(views.configProblems(null).isEmpty());
    }

    @Test
    public void theAmountCellWritesPercentOrDollarsByKind() {
        final CheckoutCommands views = viewsFor(null, false);
        final Concession concession = new Concession();
        concession.setKind(Concession.Kind.PERCENT);
        views.amountOf(concession).setValue(12.5);
        assertEquals(concession.getPercentBps(), 1250);
        assertEquals(views.amountOf(concession).getValue(), 12.5);
        views.amountOf(concession).setValue(400.0);
        assertEquals(concession.getPercentBps(), 10_000, "capped at 100%");
        concession.setKind(Concession.Kind.FIXED_PRICE);
        views.amountOf(concession).setValue(50.0);
        assertEquals(concession.getAmountCents(), 5000L);
        assertEquals(views.amountOf(concession).getValue(), 50.0);
        views.amountOf(concession).setValue(null);
        assertEquals(concession.getAmountCents(), 0L);
        views.amountOf(null).setValue(1.0);
        assertNull(views.amountOf(null).getValue());

        assertEquals(views.getConcessionKinds().size(), 4);
        assertFalse(views.getConcessionKinds().contains(Concession.Kind.PER_NIGHT));
        assertEquals(views.kindLabel(Concession.Kind.PERCENT), "% off");
        assertEquals(views.kindLabel(Concession.Kind.TOTAL), "$ off");
        assertEquals(views.kindLabel(Concession.Kind.FIXED_PRICE), "At most $");
        assertEquals(views.kindLabel(Concession.Kind.COMPLIMENTARY), "Free");
        assertEquals(views.kindLabel(Concession.Kind.PER_NIGHT), "$ off per night");
        assertEquals(views.kindLabel(null), "");
    }

    @Test
    public void rowsAreAddedAndRemovedByIdentityNotPosition() {
        final CheckoutCommands views = viewsFor(null, false);
        final Trip trip = trip();
        views.addBand(trip);
        views.addBand(trip);
        final AgeBand second = trip.getCheckout().getAgeBands().get(1);
        assertTrue(views.removeBand(trip, views.rowKey(trip.getCheckout().getAgeBands().get(0))));
        assertEquals(trip.getCheckout().getAgeBands(), List.of(second), "the clicked band went, not a position");
        assertFalse(views.removeBand(trip, "gone"));
        assertFalse(views.removeBand(null, "x"));
        assertFalse(views.removeBand(trip, null));

        views.addCode(trip);
        final DiscountCode code = trip.getCheckout().getCodes().get(0);
        assertFalse(views.removeCode(trip, "gone"));
        assertTrue(views.removeCode(trip, views.rowKey(code)));
        assertTrue(trip.getCheckout().getCodes().isEmpty());
        assertFalse(views.removeCode(null, "x"));
        assertFalse(views.removeCode(trip, null));
        views.addBand(null);
        views.addCode(null);
        assertEquals(views.rowKey(null), "");
    }

    @Test
    public void pricedChoicesListEveryPricedChoiceByQuestion() {
        final CheckoutCommands views = viewsFor(null, false);
        final Trip trip = trip();
        final List<CheckoutCommands.ChoiceItem> items = views.pricedChoices(trip);
        assertEquals(items.stream().map(CheckoutCommands.ChoiceItem::id).toList(),
                List.of("full", "hotel", "shirt"), "the free vow is not priced; the text question has none");
        assertEquals(items.get(0).label(), "Admission: Full weekend");
        assertTrue(views.pricedChoices(null).isEmpty());
    }

    // ------------------------------------------------------------------ the Registrations tab

    @Test
    public void theRegistrationsTabReadsTypePaidAndDetails() throws IOException {
        final CheckoutCommands views = viewsFor(null, true);
        final Trip trip = trip();
        final Person person = savedPerson();
        final PriceBreakdown breakdown = new PriceBreakdown(
                new PriceBreakdown.Inputs(8, LocalDate.now(), List.of("hotel", "gone"), "hotel", "SPEAKER",
                        List.of("hotel")),
                List.of(new PriceBreakdown.Line("Hotel weekend", PriceBreakdown.LineKind.BASE, 12000L, null)),
                12000L, "Hotel weekend", LocalDateTime.now());
        final Registration paid = new Registration(trip.getId(), person.getId(), LocalDateTime.now(),
                Registration.Status.CONFIRMED, new HashMap<>()).withRegistrationType("Hotel weekend")
                .withPriceBreakdown(breakdown).withPaymentId("pay-1").withTicketCode("TICKET")
                .withAttestations(Map.of("hotel", "I am staying at the hotel"));
        final Registration classic = new Registration(trip.getId(), Person.Id.newInstance());
        final List<Registration> regs = List.of(paid, classic);
        final String pid = person.getId().getValue();
        final String other = classic.getUserId().getValue();

        assertEquals(views.typeOf(regs, pid), "Hotel weekend");
        assertEquals(views.typeOf(regs, other), "");
        assertEquals(views.typeOf(regs, "missing"), "");
        assertEquals(views.paidText(regs, pid), "$120.00");
        assertEquals(views.paidText(regs, other), "", "a classic registration was not paid through checkout");
        assertEquals(views.paidText(List.of(classic.withPaymentId("p")), other), "paid");

        final CheckoutCommands.RegDetails details = views.details(trip, regs, pid);
        assertEquals(details.ageAtStart(), Integer.valueOf(8));
        assertEquals(details.choices(), List.of("Hotel weekend", "gone"), "an unknown id shows as stored");
        assertEquals(details.code(), "SPEAKER");
        assertEquals(details.attestations(), List.of("Hotel weekend: I am staying at the hotel"));
        assertEquals(details.total(), "$120.00");
        assertEquals(details.paymentId(), "pay-1");
        assertEquals(details.ticketCode(), "TICKET");
        assertEquals(details.lines().size(), 1);
        assertEquals(details.birthdate(), person.getBirthdate());

        final CheckoutCommands.RegDetails bare = views.details(trip, regs, other);
        assertEquals(bare.total(), "");
        assertTrue(bare.choices().isEmpty());
        assertEquals(bare.code(), "");
        assertNull(bare.ageAtStart());
        assertNull(views.details(trip, regs, "missing"));
        assertNull(views.details(null, regs, pid));
    }

    // ------------------------------------------------------------------ fixtures

    private static RegistrationOption admission() {
        return new RegistrationOption(1, "Admission", "", true, RegistrationOption.QuestionType.CHOICE, true,
                List.of(new OptionChoice("full", "Full weekend", 15000L, null, null, null, null),
                        new OptionChoice("hotel", "Hotel weekend", 12000L, null, "I am staying at the hotel", null,
                                null)));
    }

    private static RegistrationOption extras() {
        return new RegistrationOption(2, "Extras", "", true, RegistrationOption.QuestionType.MULTI_CHOICE, false,
                List.of(new OptionChoice("shirt", "T-shirt", 2000L, null, null, null, null),
                        new OptionChoice("vow", "Free vow", 0L, null, "I promise", null, null)));
    }

    private static Trip trip() {
        final Trip trip = Trip.builder().title("Views " + RandomData.genAlpha(6)).kind(TripKind.CONFERENCE)
                .orgId(FakeData.CFPW_ORG_ID).startDate(LocalDateTime.now().plusDays(30))
                .endDate(LocalDateTime.now().plusDays(32))
                .regOptions(List.of(admission(), extras(), new RegistrationOption(3, "Diet", "", true)))
                .checkout(new CheckoutConfig()).build();
        trip.setChatEnabled(false);
        return trip;
    }

    private static Person savedPerson() throws IOException {
        final Person person = Person.builder().first("Vw" + RandomData.genAlpha(5)).last(RandomData.genAlpha(7))
                .email("vw." + RandomData.genAlpha(10) + "@example.com").birthdate(LocalDate.of(1990, 1, 1)).build();
        assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    /** A caller who is (or is not) the trip's registration admin; {@code me} null is an anonymous caller. */
    private static CheckoutCommands viewsFor(final Person.Id me, final boolean admin) {
        final PrivilegeCommands privs = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(privs.check(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(false);
        Mockito.when(privs.check(ArgumentMatchers.eq(PrivilegeCommands.REGISTRATION_ADMIN), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(admin);
        final Supplier<Caller> callerSource = () -> new Caller(me, false,
                new AuditActor("views@example.com", me == null ? null : me.getValue()), privs);
        return new CheckoutCommands(callerSource, () -> new PaymentCommands(callerSource), LocalDateTime::now);
    }
}
