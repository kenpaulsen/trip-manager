package org.paulsens.trip.action;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.paulsens.trip.model.AdmissionOption;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Unit tests for the age / pricing / metadata helpers in {@link RegistrationCommands}.
 *
 * <p>Pricing is now driven by per-trip {@link AdmissionOption} data plus the age ladder:
 * <ul>
 *   <li>Age &le; 3 → $0 (free)</li>
 *   <li>Age 4–10 (child) → lower of the option price and the trip's child price cap</li>
 *   <li>Age &gt; 10 (adult) → the option's full price</li>
 *   <li>A numeric {@code _discount} ({@code "175"}, {@code "$175.00"}) → final total override</li>
 * </ul>
 */
public class RegistrationCommandsTest {

    private final RegistrationCommands cmds = new RegistrationCommands();

    // ---- Sample admission options (mirrors the SummerFest 2026 layout, trimmed) ----------
    private static final AdmissionOption FULL_STD =
            new AdmissionOption("full-std", "Full Weekend (Standard)", new BigDecimal("340.00"),
                    List.of("FRI", "SAT", "SUN"), false, true);
    private static final AdmissionOption FULL_ROSEN =
            new AdmissionOption("full-rosen", "Full Weekend (Rosen Guest)", new BigDecimal("320.00"),
                    List.of("FRI", "SAT", "SUN"), true, true);
    private static final AdmissionOption FRI =
            new AdmissionOption("fri", "Friday Only", new BigDecimal("120.00"),
                    List.of("FRI"), false, true);
    private static final AdmissionOption SAT =
            new AdmissionOption("sat", "Saturday Only", new BigDecimal("205.00"),
                    List.of("SAT"), false, true);
    private static final AdmissionOption HIDDEN =
            new AdmissionOption("hidden", "Retired Option", new BigDecimal("999.00"),
                    List.of("SAT"), false, false);

    private static final BigDecimal CHILD_CAP = new BigDecimal("179.00");

    // ===== getAge =====================================================================

    @Test
    public void getAge_returns999_whenPersonIsNull() {
        Assert.assertEquals(cmds.getAge(null), 999);
    }

    @Test
    public void getAge_returns999_whenBirthdateIsNull() {
        Assert.assertEquals(cmds.getAge(personWithBirthdate(null)), 999);
    }

    @Test
    public void getAge_returnsYearsBetweenBirthdateAndToday() {
        Assert.assertEquals(cmds.getAge(personAged(25)), 25);
    }

    // ===== deriveRegistrantType =======================================================

    @Test
    public void deriveRegistrantType_threeAndUnder() {
        Assert.assertEquals(cmds.deriveRegistrantType(personAged(0)),
                Registration.RegistrantType.THREE_AND_UNDER);
        Assert.assertEquals(cmds.deriveRegistrantType(personAged(3)),
                Registration.RegistrantType.THREE_AND_UNDER);
    }

    @Test
    public void deriveRegistrantType_child() {
        Assert.assertEquals(cmds.deriveRegistrantType(personAged(4)), Registration.RegistrantType.CHILD);
        Assert.assertEquals(cmds.deriveRegistrantType(personAged(10)), Registration.RegistrantType.CHILD);
    }

    @Test
    public void deriveRegistrantType_adult() {
        Assert.assertEquals(cmds.deriveRegistrantType(personAged(11)), Registration.RegistrantType.ADULT);
        Assert.assertEquals(cmds.deriveRegistrantType(personAged(50)), Registration.RegistrantType.ADULT);
    }

    @Test
    public void deriveRegistrantType_treatsMissingBirthdateAsAdult() {
        Assert.assertEquals(cmds.deriveRegistrantType(personWithBirthdate(null)),
                Registration.RegistrantType.ADULT);
    }

    // ===== getAvailableOptions ========================================================

    @Test
    public void availableOptions_adultSeesAllShownIncludingRosen() {
        final List<AdmissionOption> opts = cmds.getAvailableOptions(trip(), personAged(30));
        Assert.assertEquals(opts, List.of(FULL_STD, FULL_ROSEN, FRI, SAT),
                "adult sees every show=true option, in declared order");
    }

    @Test
    public void availableOptions_childExcludesRosenVariants() {
        final List<AdmissionOption> opts = cmds.getAvailableOptions(trip(), personAged(8));
        Assert.assertEquals(opts, List.of(FULL_STD, FRI, SAT),
                "children never see the Rosen-discounted variants");
    }

    @Test
    public void availableOptions_threeAndUnderTreatedLikeChild() {
        final List<AdmissionOption> opts = cmds.getAvailableOptions(trip(), personAged(2));
        Assert.assertEquals(opts, List.of(FULL_STD, FRI, SAT));
    }

    @Test
    public void availableOptions_hiddenOptionsExcluded() {
        Assert.assertFalse(cmds.getAvailableOptions(trip(), personAged(30)).contains(HIDDEN));
    }

    @Test
    public void availableOptions_nullTripIsEmpty() {
        Assert.assertTrue(cmds.getAvailableOptions(null, personAged(30)).isEmpty());
    }

    // ===== computeOptionPrice =========================================================

    @Test
    public void optionPrice_freeForThreeAndUnder() {
        Assert.assertEquals(cmds.computeOptionPrice(trip(), personAged(2), FULL_STD), BigDecimal.ZERO);
    }

    @Test
    public void optionPrice_childCappedWhenOptionExceedsCap() {
        Assert.assertEquals(cmds.computeOptionPrice(trip(), personAged(8), FULL_STD).compareTo(CHILD_CAP), 0,
                "$340 full-weekend is capped to the $179 child price");
    }

    @Test
    public void optionPrice_childKeepsOptionPriceBelowCap() {
        Assert.assertEquals(cmds.computeOptionPrice(trip(), personAged(8), FRI).compareTo(new BigDecimal("120.00")),
                0, "$120 single-day stays $120 for a child (below the $179 cap)");
    }

    @Test
    public void optionPrice_adultPaysFullPrice() {
        Assert.assertEquals(cmds.computeOptionPrice(trip(), personAged(30), FULL_STD)
                .compareTo(new BigDecimal("340.00")), 0);
    }

    @Test
    public void optionPrice_childWithNoCapPaysFullPrice() {
        final Trip noCap = Trip.builder().admissionOptions(List.of(FULL_STD)).build();
        Assert.assertEquals(cmds.computeOptionPrice(noCap, personAged(8), FULL_STD)
                .compareTo(new BigDecimal("340.00")), 0);
    }

    @Test
    public void optionPrice_nullOptionIsZero() {
        Assert.assertEquals(cmds.computeOptionPrice(trip(), personAged(30), null), BigDecimal.ZERO);
    }

    // ----- Eligibility hardening: free/child pricing only when the birthdate proves it ----

    @Test
    public void optionPrice_missingBirthdateIsNeverFreeOrCapped() {
        final Person noDob = personWithBirthdate(null); // getAge -> 999 (adult by default)
        Assert.assertEquals(cmds.computeOptionPrice(trip(), noDob, FULL_STD).compareTo(new BigDecimal("340.00")),
                0, "no birthdate must never yield the free or child-capped price");
    }

    @Test
    public void optionPrice_childIsNeverFree() {
        // Age 4 is one year over the free cutoff: pays the capped child price, never $0.
        final BigDecimal price = cmds.computeOptionPrice(trip(), personAged(4), FULL_STD);
        Assert.assertEquals(price.compareTo(CHILD_CAP), 0);
        Assert.assertTrue(price.signum() > 0, "a 4-year-old must not be free");
    }

    @Test
    public void optionPrice_adultNeverGetsChildCap() {
        // Age 11 is one year over the child cutoff: pays full price, never the $179 cap.
        Assert.assertEquals(cmds.computeOptionPrice(trip(), personAged(11), FULL_STD)
                .compareTo(new BigDecimal("340.00")), 0);
    }

    @Test
    public void payment_missingBirthdateChargedFullPrice() {
        Assert.assertEquals(cmds.computePaymentAmount(personWithBirthdate(null), regChoosing("full-std"), trip())
                .compareTo(new BigDecimal("340.00")), 0, "no birthdate must be charged the full price");
    }

    // ===== computePaymentAmount =======================================================

    @Test
    public void payment_usesChosenOptionForAdult() {
        final Registration reg = regChoosing("full-rosen");
        Assert.assertEquals(cmds.computePaymentAmount(personAged(30), reg, trip())
                .compareTo(new BigDecimal("320.00")), 0);
    }

    @Test
    public void payment_appliesChildCapToChosenOption() {
        final Registration reg = regChoosing("full-std");
        Assert.assertEquals(cmds.computePaymentAmount(personAged(8), reg, trip()).compareTo(CHILD_CAP), 0);
    }

    @Test
    public void payment_zeroWhenNoOptionChosen() {
        Assert.assertEquals(cmds.computePaymentAmount(personAged(30), newReg(), trip()), BigDecimal.ZERO);
    }

    @Test
    public void payment_zeroForUnknownOptionId() {
        Assert.assertEquals(cmds.computePaymentAmount(personAged(30), regChoosing("nope"), trip()),
                BigDecimal.ZERO);
    }

    @Test
    public void payment_numericDiscountOverridesEverything() {
        final Registration reg = regChoosing("full-std"); // would be $340
        reg.getOptions().put(Registration.OPT_DISCOUNT, "175.00");
        Assert.assertEquals(cmds.computePaymentAmount(personAged(30), reg, trip())
                .compareTo(new BigDecimal("175.00")), 0);
    }

    @Test
    public void payment_dollarPrefixedOverrideWorks() {
        final Registration reg = regChoosing("full-rosen");
        reg.getOptions().put(Registration.OPT_DISCOUNT, "$50");
        Assert.assertEquals(cmds.computePaymentAmount(personAged(30), reg, trip())
                .compareTo(new BigDecimal("50")), 0);
    }

    @Test
    public void payment_numericOverrideOverridesChildPriceToo() {
        final Registration reg = regChoosing("full-std");
        reg.getOptions().put(Registration.OPT_DISCOUNT, "25");
        Assert.assertEquals(cmds.computePaymentAmount(personAged(8), reg, trip())
                .compareTo(new BigDecimal("25")), 0);
    }

    // ===== getAdmissionLabel ==========================================================

    @Test
    public void admissionLabel_returnsChosenOptionLabel() {
        Assert.assertEquals(cmds.getAdmissionLabel(trip(), regChoosing("sat")), "Saturday Only");
    }

    @Test
    public void admissionLabel_nullWhenNoneChosen() {
        Assert.assertNull(cmds.getAdmissionLabel(trip(), newReg()));
    }

    // ===== parseDiscountOverride ======================================================

    @Test
    public void parseDiscountOverride_returnsNullForNamedTags() {
        Assert.assertNull(cmds.parseDiscountOverride(Registration.Discount.EARLY_BIRD));
        Assert.assertNull(cmds.parseDiscountOverride(Registration.Discount.SCHOLARSHIP));
        Assert.assertNull(cmds.parseDiscountOverride(null));
        Assert.assertNull(cmds.parseDiscountOverride(""));
        Assert.assertNull(cmds.parseDiscountOverride("   "));
        Assert.assertNull(cmds.parseDiscountOverride("not-a-number"));
    }

    @Test
    public void parseDiscountOverride_returnsBigDecimalForNumerics() {
        Assert.assertEquals(cmds.parseDiscountOverride("175"),     new BigDecimal("175"));
        Assert.assertEquals(cmds.parseDiscountOverride("175.00"),  new BigDecimal("175.00"));
        Assert.assertEquals(cmds.parseDiscountOverride("$50"),     new BigDecimal("50"));
        Assert.assertEquals(cmds.parseDiscountOverride(" $50.50 "), new BigDecimal("50.50"));
    }

    // ===== applyAutoMetadata ==========================================================

    @Test
    public void applyAutoMetadata_setsRegistrantTypeFromAge() {
        final Registration reg = newReg();
        cmds.applyAutoMetadata(reg, personAged(2));
        Assert.assertEquals(reg.getRegistrantType(), Registration.RegistrantType.THREE_AND_UNDER);

        cmds.applyAutoMetadata(reg, personAged(7));
        Assert.assertEquals(reg.getRegistrantType(), Registration.RegistrantType.CHILD,
                "registrantType is re-derived on every call (covers birthdate corrections)");

        cmds.applyAutoMetadata(reg, personAged(40));
        Assert.assertEquals(reg.getRegistrantType(), Registration.RegistrantType.ADULT);
    }

    @Test
    public void applyAutoMetadata_doesNotClobberChosenAdmission() {
        final Registration reg = regChoosing("full-std");
        cmds.applyAutoMetadata(reg, personAged(30));
        Assert.assertEquals(reg.getAdmissionId(), "full-std",
                "the user's admission choice must be preserved across re-derivation");
    }

    @Test
    public void applyAutoMetadata_returnsSameInstance() {
        final Registration reg = newReg();
        Assert.assertSame(cmds.applyAutoMetadata(reg, personAged(25)), reg);
    }

    // ===== isRosenCentre / getEffectiveAdmissionLabel (confirm.xhtml display) ==========

    @Test
    public void isRosenCentre_trueWhenChosenOptionIsRosenDiscounted() {
        Assert.assertTrue(cmds.isRosenCentre(trip(), regChoosing("full-rosen")));
    }

    @Test
    public void isRosenCentre_falseWhenChosenOptionIsNotRosenDiscounted() {
        Assert.assertFalse(cmds.isRosenCentre(trip(), regChoosing("full-std")));
    }

    @Test
    public void isRosenCentre_fallsBackToOpt9WhenNoAdmissionChosen() {
        final Registration yes = newReg();
        yes.getOptions().put(RegistrationCommands.ROSEN_CENTRE_OPT_KEY, "true");
        Assert.assertTrue(cmds.isRosenCentre(trip(), yes));

        final Registration no = newReg();
        no.getOptions().put(RegistrationCommands.ROSEN_CENTRE_OPT_KEY, "false");
        Assert.assertFalse(cmds.isRosenCentre(trip(), no));
    }

    @Test
    public void isRosenCentre_falseWhenNeitherFieldPresent() {
        Assert.assertFalse(cmds.isRosenCentre(trip(), newReg()),
                "older registration with no admission and no opt9 is assumed non-Rosen");
    }

    @Test
    public void isRosenCentre_falseForNullReg() {
        Assert.assertFalse(cmds.isRosenCentre(trip(), null));
    }

    @Test
    public void effectiveAdmissionLabel_usesChosenOption() {
        Assert.assertEquals(cmds.getEffectiveAdmissionLabel(trip(), regChoosing("sat")), "Saturday Only");
    }

    @Test
    public void effectiveAdmissionLabel_defaultsToFullRosenWhenOpt9True() {
        final Registration reg = newReg();
        reg.getOptions().put(RegistrationCommands.ROSEN_CENTRE_OPT_KEY, "true");
        // No _admission -> defaults to id "full-rosen", which resolves to that option's label.
        Assert.assertEquals(cmds.getEffectiveAdmissionLabel(trip(), reg), "Full Weekend (Rosen Guest)");
    }

    @Test
    public void effectiveAdmissionLabel_defaultsToFullWhenNotRosen() {
        // No _admission and no opt9 -> defaults to id "full". The sample trip has no "full"
        // option, so the raw fallback id is returned (documented behavior).
        Assert.assertEquals(cmds.getEffectiveAdmissionLabel(trip(), newReg()), "full");
    }

    @Test
    public void effectiveAdmissionLabel_defaultFullResolvesToLabelWhenTripHasIt() {
        final Trip t = Trip.builder()
                .admissionOptions(List.of(
                        new AdmissionOption("full", "Full Weekend (Standard)", new BigDecimal("340.00"),
                                List.of("FRI", "SAT", "SUN"), false, true)))
                .build();
        Assert.assertEquals(cmds.getEffectiveAdmissionLabel(t, newReg()), "Full Weekend (Standard)");
    }

    @Test
    public void effectiveAdmissionLabel_nullForNullReg() {
        Assert.assertNull(cmds.getEffectiveAdmissionLabel(trip(), null));
    }

    // ===== Helpers =====================================================================

    private static Trip trip() {
        return Trip.builder()
                .admissionOptions(List.of(FULL_STD, FULL_ROSEN, FRI, SAT, HIDDEN))
                .childPriceCap(CHILD_CAP)
                .build();
    }

    private static Registration newReg() {
        return new Registration(RandomData.genAlpha(5), Person.Id.newInstance());
    }

    private static Registration regChoosing(final String admissionId) {
        final Registration reg = newReg();
        reg.getOptions().put(Registration.OPT_ADMISSION, admissionId);
        return reg;
    }

    private static Person personAged(final int years) {
        return personWithBirthdate(LocalDate.now().minusYears(years));
    }

    private static Person personWithBirthdate(final LocalDate birthdate) {
        return Person.builder()
                .id(Person.Id.from(RandomData.genAlpha(5)))
                .first(RandomData.genAlpha(8))
                .last(RandomData.genAlpha(8))
                .birthdate(birthdate)
                .build();
    }
}
