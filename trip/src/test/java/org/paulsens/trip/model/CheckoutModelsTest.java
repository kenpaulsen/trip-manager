package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/** The priced-checkout models (docs/checkout.md): the policy, its age bands and codes, and the pending party. */
public class CheckoutModelsTest {
    private static final ObjectMapper MAPPER = DAO.getInstance().getMapper();
    private static final LocalDateTime NOW = LocalDateTime.of(2027, 1, 10, 12, 0);

    @Test
    public void equality() {
        EqualsVerifier.forClass(CheckoutConfig.class).suppress(Warning.NONFINAL_FIELDS).verify();
        EqualsVerifier.forClass(AgeBand.class).suppress(Warning.NONFINAL_FIELDS).verify();
        EqualsVerifier.forClass(DiscountCode.class).suppress(Warning.NONFINAL_FIELDS).verify();
        EqualsVerifier.forClass(PendingParty.class).suppress(Warning.NONFINAL_FIELDS).verify();
    }

    @Test
    public void aTripStoredBeforeCheckoutHasItOff() throws Exception {
        final Trip legacy = MAPPER.readValue("{\"id\":\"t\",\"title\":\"Old\"}", Trip.class);
        assertFalse(legacy.getCheckout().isOn());
        assertFalse(legacy.getCheckout().isConfirmPaid(), "auto-confirm defaults OFF: a paid registration waits");
        assertFalse(legacy.getCheckout().isDeposit(), "the full price is due");
        assertEquals(legacy.getCheckout().dueNowCents(15000L), 15000L);
        assertSame(legacy.getCheckout(), legacy.getCheckout(), "lazy, then kept: the editor binds into it");
    }

    @Test
    public void thePolicyRoundTripsAndItsEditorFlagsStoreDefaultsAsNull() throws Exception {
        final CheckoutConfig policy = policy();
        final CheckoutConfig back = MAPPER.readValue(MAPPER.writeValueAsString(policy), CheckoutConfig.class);
        assertEquals(back, policy);
        policy.setOn(false);
        assertNull(policy.getEnabled());
        policy.setOn(true);
        assertEquals(policy.getEnabled(), Boolean.TRUE);
        policy.setConfirmPaid(true);
        assertEquals(policy.getAutoConfirmPaid(), Boolean.TRUE);
        policy.setConfirmPaid(false);
        assertNull(policy.getAutoConfirmPaid());
        assertTrue(new CheckoutConfig().getAgeBands().isEmpty());
    }

    /** A deposit policy: what is due now is capped per person, and the editor's radio drives the amount field. */
    @Test
    public void aDepositPolicyCapsWhatIsDueAndTheEditorRadioDrivesIt() throws Exception {
        final CheckoutConfig policy = policy();
        assertEquals(policy.getDueMode(), CheckoutConfig.DUE_FULL);
        policy.setDepositDollars(99.0);
        assertNull(policy.getDepositCents(), "an amount is ignored while the radio says full");
        policy.setDueMode(CheckoutConfig.DUE_DEPOSIT);
        assertTrue(policy.isDeposit());
        assertEquals(policy.getDepositCents(), Long.valueOf(0L), "starts at $0, which problems() refuses");
        assertTrue(policy.problems().stream().anyMatch(p -> p.contains("deposit must be more than $0.00")));
        policy.setDepositDollars(50.0);
        assertEquals(policy.getDepositCents(), Long.valueOf(5000L));
        assertEquals(policy.getDepositDollars(), 50.0);
        assertTrue(policy.problems().isEmpty(), policy.problems().toString());
        assertEquals(policy.dueNowCents(15000L), 5000L, "capped at the deposit");
        assertEquals(policy.dueNowCents(2000L), 2000L, "a price below the deposit is paid in full");
        assertEquals(policy.dueNowCents(0L), 0L);
        policy.setBalanceDueOn(NOW.toLocalDate().minusMonths(2));
        assertTrue(policy.problems().stream().anyMatch(p -> p.contains("before registration opens")));
        policy.setBalanceDueOn(NOW.toLocalDate().plusMonths(2));
        assertTrue(policy.problems().isEmpty());
        final CheckoutConfig back = MAPPER.readValue(MAPPER.writeValueAsString(policy), CheckoutConfig.class);
        assertEquals(back, policy);
        assertEquals(back.getBalanceDueOn(), NOW.toLocalDate().plusMonths(2));
        policy.setDueMode(CheckoutConfig.DUE_FULL);
        assertNull(policy.getDepositCents());
        assertNull(policy.getBalanceDueOn(), "the due date goes with the deposit");
        assertNull(policy.getDepositDollars());
        policy.setDueMode(CheckoutConfig.DUE_DEPOSIT);
        policy.setDepositDollars(-5.0);
        assertEquals(policy.getDepositCents(), Long.valueOf(0L), "never negative");
    }

    @Test
    public void theWindowBandsAndCodesAnswerLookups() {
        final CheckoutConfig policy = policy();
        assertTrue(policy.isOpenAt(NOW));
        assertFalse(policy.isOpenAt(NOW.minusMonths(2)), "before it opens");
        assertFalse(policy.isOpenAt(NOW.plusMonths(2)), "after it closes");
        assertEquals(policy.bandFor(2).getLabel(), "Infant");
        assertEquals(policy.bandFor(7).getRangeText(), "4-10");
        assertNull(policy.bandFor(40));
        assertEquals(policy.code(" speaker ").getCode(), "SPEAKER");
        assertNull(policy.code(""));
        assertNull(policy.code("nope"));
        assertTrue(policy.problems().isEmpty(), policy.problems().toString());
    }

    @Test
    public void problemsNameEveryMistake() {
        final CheckoutConfig bad = new CheckoutConfig(Boolean.TRUE, null, NOW, NOW.minusDays(1), List.of(
                new AgeBand("A", 0, 10, null), new AgeBand("B", 5, 12, null), new AgeBand("C", 20, 15, null),
                new AgeBand("D", 65, null, null), new AgeBand("E", 70, null, null)), List.of(
                new DiscountCode("X", null, null, null, null, null, null),
                new DiscountCode("x", null, null, null, null, null, null),
                new DiscountCode(" ", null, null, null, null, null, null)));
        final String all = String.join(" | ", bad.problems());
        assertTrue(all.contains("close after it opens"), all);
        assertTrue(all.contains("0-10 and 5-12 overlap"), all);
        assertTrue(all.contains("20-15 ends before it starts"), all);
        assertTrue(all.contains("65+ and 70+ overlap"), all);
        assertTrue(all.contains("The code X is listed twice"), all);
        assertTrue(all.contains("needs its code"), all);
    }

    @Test
    public void ageBandsAndCodesHaveSafeDefaults() {
        final AgeBand band = new AgeBand();
        assertEquals(band.getLabel(), "");
        assertNotNull(band.getConcession());
        assertTrue(band.covers(99), "0 and up");
        assertEquals(new AgeBand(null, -3, 4, null).getMinAge(), 0);

        final DiscountCode code = new DiscountCode();
        assertEquals(code.getCode(), "");
        assertTrue(code.isActive());
        code.setActive(false);
        assertEquals(code.getEnabled(), Boolean.FALSE);
        code.setActive(true);
        assertNull(code.getEnabled());
        code.setCode(" early ");
        assertEquals(code.getCode(), "EARLY");
        assertTrue(code.appliesTo("anything"), "no list means every choice");
        assertNull(new DiscountCode(null, null, null, null, null, null, -1).getMaxUses());

        final DiscountCode dated = new DiscountCode("D", null, null, LocalDate.of(2027, 1, 31), List.of("full"),
                null, null);
        assertFalse(dated.isExpiredOn(LocalDate.of(2027, 1, 31)), "the last day still counts");
        assertTrue(dated.isExpiredOn(LocalDate.of(2027, 2, 1)));
        assertFalse(dated.isExpiredOn(null));
        assertTrue(dated.appliesTo("full"));
        assertFalse(dated.appliesTo("fri"));
    }

    /** The bug that shipped green once: a payment's pending party must survive the payments table's JSON. */
    @Test
    public void aPaymentKeepsItsPendingPartyThroughJson() throws Exception {
        final Person.Id ada = Person.Id.from("person-ada");
        final Payment payment = Payment.builder().tripId("t").payerId(ada).build();
        assertFalse(payment.hasCheckout());
        payment.setCheckout(new PendingParty("party-1", ada,
                List.of(new Registration("t", ada).withStatus(Registration.Status.CONFIRMED)
                        .withRegistrationType("Full weekend")), NOW, List.of("note")));
        final Payment back = MAPPER.readValue(MAPPER.writeValueAsString(payment), Payment.class);
        assertTrue(back.hasCheckout(), "the pending party was dropped from the stored row");
        assertEquals(back.getCheckout(), payment.getCheckout());
        assertEquals(back.getCheckout().getRows().get(0).getRegistrationType(), "Full weekend");
        assertTrue(new PendingParty().getRows().isEmpty());
    }

    /** A cancelled-and-back visit keeps each person's code and ticks, and nobody else's. */
    @Test
    public void aDraftCarriesOnePersonsCodeAndAttestations() {
        final RegistrationDraft old = new RegistrationDraft();
        old.getCodes().put("ada", "SPEAKER");
        old.getAttest().put(RegistrationDraft.attestKey("ada", "hotel"), Boolean.TRUE);
        old.getAttest().put(RegistrationDraft.attestKey("bob", "hotel"), Boolean.TRUE);
        final RegistrationDraft fresh = new RegistrationDraft();
        fresh.carryCheckoutFrom(old, "ada");
        fresh.carryCheckoutFrom(old, "carl");
        fresh.carryCheckoutFrom(null, "ada");
        fresh.carryCheckoutFrom(old, null);
        assertEquals(fresh.getCodes(), java.util.Map.of("ada", "SPEAKER"));
        assertEquals(fresh.getAttest(), java.util.Map.of(RegistrationDraft.attestKey("ada", "hotel"), Boolean.TRUE));
        new RegistrationDraft().carryCheckoutFrom(new RegistrationDraft(), "ada");
    }

    @Test
    public void aTripWithoutAPolicyHasNoProblemsAndGetsNoPolicy() {
        final Trip trip = Trip.builder().id("t").build();
        assertTrue(trip.checkoutProblems().isEmpty());
        trip.setCheckout(new CheckoutConfig(Boolean.TRUE, null, null, null, List.of(), List.of(
                new DiscountCode("A", null, null, null, null, null, null),
                new DiscountCode("a", null, null, null, null, null, null))));
        assertEquals(trip.checkoutProblems(), List.of("The code A is listed twice."));
    }

    private static CheckoutConfig policy() {
        return new CheckoutConfig(Boolean.TRUE, null, NOW.minusMonths(1), NOW.plusMonths(1), List.of(
                new AgeBand("Infant", 0, 3, new Concession(Concession.Kind.COMPLIMENTARY, 0, 0L, null, null, null)),
                new AgeBand("Child", 4, 10, new Concession(Concession.Kind.FIXED_PRICE, 0, 5000L, null, null, null))),
                List.of(new DiscountCode("speaker", "Speakers", null, null, List.of("full"), null, 5)));
    }
}
