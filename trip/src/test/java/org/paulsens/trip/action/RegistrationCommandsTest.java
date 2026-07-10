package org.paulsens.trip.action;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.model.AdmissionOption;
import org.paulsens.trip.model.DiscountCode;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
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

    // ---- Sample discount codes (id distinct from the typed code) -------------------------
    private static final DiscountCode SAVE20 =        // $20 off any option
            new DiscountCode("dc-save20", "SAVE20", "$20 off", null,
                    DiscountCode.DiscountType.DISCOUNT_BY, new BigDecimal("20.00"), List.of(), true);
    private static final DiscountCode SAVE200 =       // $200 off any option
            new DiscountCode("dc-save200", "SAVE200", "$200 off", null,
                    DiscountCode.DiscountType.DISCOUNT_BY, new BigDecimal("200.00"), List.of(), true);
    private static final DiscountCode BIG500 =        // $500 off → floors at $0
            new DiscountCode("dc-big500", "BIG500", "$500 off", null,
                    DiscountCode.DiscountType.DISCOUNT_BY, new BigDecimal("500.00"), List.of(), true);
    private static final DiscountCode STAFF =         // exact $250, only for full-* options
            new DiscountCode("dc-staff", "STAFF", "Staff price", null,
                    DiscountCode.DiscountType.EXACT, new BigDecimal("250.00"),
                    List.of("full-std", "full-rosen"), true);
    private static final DiscountCode CHEAP150 =      // exact $150, only for full-std
            new DiscountCode("dc-cheap150", "CHEAP150", "Cheap", null,
                    DiscountCode.DiscountType.EXACT, new BigDecimal("150.00"), List.of("full-std"), true);
    private static final DiscountCode EXPIRED =       // DISCOUNT_BY but expired yesterday
            new DiscountCode("dc-expired", "EXPIRED", "Expired", LocalDate.now().minusDays(1),
                    DiscountCode.DiscountType.DISCOUNT_BY, new BigDecimal("50.00"), List.of(), true);
    private static final DiscountCode DISABLED =      // DISCOUNT_BY but disabled
            new DiscountCode("dc-disabled", "DISABLED", "Disabled", null,
                    DiscountCode.DiscountType.DISCOUNT_BY, new BigDecimal("50.00"), List.of(), false);

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

    // ===== Discount codes =============================================================

    @Test
    public void findDiscountCodeByCode_caseInsensitiveTrimmed() {
        Assert.assertSame(cmds.findDiscountCodeByCode(tripWithCodes(), "save20"), SAVE20);
        Assert.assertSame(cmds.findDiscountCodeByCode(tripWithCodes(), "  STAFF  "), STAFF);
    }

    @Test
    public void findDiscountCodeByCode_nullForUnknownOrBlank() {
        Assert.assertNull(cmds.findDiscountCodeByCode(tripWithCodes(), "NOPE"));
        Assert.assertNull(cmds.findDiscountCodeByCode(tripWithCodes(), ""));
        Assert.assertNull(cmds.findDiscountCodeByCode(tripWithCodes(), null));
        Assert.assertNull(cmds.findDiscountCodeByCode(null, "SAVE20"));
    }

    // --- validateDiscountCode ---

    @Test
    public void validate_nullWhenNoCodeApplied() {
        Assert.assertNull(cmds.validateDiscountCode(tripWithCodes(), regChoosing("full-std"), personAged(30)));
    }

    @Test
    public void validate_nullForValidDiscountBy() {
        Assert.assertNull(cmds.validateDiscountCode(
                tripWithCodes(), regWith("full-std", "dc-save20"), personAged(30)));
    }

    @Test
    public void validate_nullForValidExactMatch() {
        Assert.assertNull(cmds.validateDiscountCode(
                tripWithCodes(), regWith("full-std", "dc-staff"), personAged(30)));
    }

    @Test
    public void validate_errorForExactMismatch() {
        Assert.assertNotNull(cmds.validateDiscountCode(
                tripWithCodes(), regWith("fri", "dc-staff"), personAged(30)),
                "STAFF is exact-price for full-* only; choosing fri must be blocked");
    }

    @Test
    public void validate_errorForExpired() {
        Assert.assertNotNull(cmds.validateDiscountCode(
                tripWithCodes(), regWith("full-std", "dc-expired"), personAged(30)));
    }

    @Test
    public void validate_errorForDisabled() {
        Assert.assertNotNull(cmds.validateDiscountCode(
                tripWithCodes(), regWith("full-std", "dc-disabled"), personAged(30)));
    }

    @Test
    public void validate_errorForUnknownCodeId() {
        Assert.assertNotNull(cmds.validateDiscountCode(
                tripWithCodes(), regWith("full-std", "dc-ghost"), personAged(30)));
    }

    // --- computePaymentAmount with codes ---

    @Test
    public void price_discountBySubtractsFromOption() {
        // adult full-std $340 − $20 = $320
        Assert.assertEquals(cmds.computePaymentAmount(personAged(30), regWith("full-std", "dc-save20"),
                tripWithCodes()).compareTo(new BigDecimal("320.00")), 0);
    }

    @Test
    public void price_exactSetsTotal() {
        // adult full-std, STAFF exact $250
        Assert.assertEquals(cmds.computePaymentAmount(personAged(30), regWith("full-std", "dc-staff"),
                tripWithCodes()).compareTo(new BigDecimal("250.00")), 0);
    }

    @Test
    public void price_discountByFloorsAtZero() {
        // adult fri $120 − $500 → $0
        Assert.assertEquals(cmds.computePaymentAmount(personAged(30), regWith("fri", "dc-big500"),
                tripWithCodes()), BigDecimal.ZERO);
    }

    @Test
    public void price_invalidCodeNotApplied() {
        // expired code on full-std: price stays full $340 (continuation blocked separately)
        Assert.assertEquals(cmds.computePaymentAmount(personAged(30), regWith("full-std", "dc-expired"),
                tripWithCodes()).compareTo(new BigDecimal("340.00")), 0);
    }

    @Test
    public void price_exactMismatchNotApplied() {
        // STAFF on fri is a mismatch → not applied; price stays $120
        Assert.assertEquals(cmds.computePaymentAmount(personAged(30), regWith("fri", "dc-staff"),
                tripWithCodes()).compareTo(new BigDecimal("120.00")), 0);
    }

    @Test
    public void price_childKeepsChildPriceWhenCodeNotLower() {
        // child full-std: age-adjusted = min(340,179)=179; SAVE20 coded = 320; min = 179
        Assert.assertEquals(cmds.computePaymentAmount(personAged(8), regWith("full-std", "dc-save20"),
                tripWithCodes()).compareTo(CHILD_CAP), 0);
    }

    @Test
    public void price_childGetsCodeWhenLowerThanChildPrice_exact() {
        // child full-std: age-adjusted 179; CHEAP150 exact = 150; min = 150
        Assert.assertEquals(cmds.computePaymentAmount(personAged(8), regWith("full-std", "dc-cheap150"),
                tripWithCodes()).compareTo(new BigDecimal("150.00")), 0);
    }

    @Test
    public void price_childGetsCodeWhenLowerThanChildPrice_discountBy() {
        // child full-std: age-adjusted 179; SAVE200 coded = 340−200 = 140; min = 140
        Assert.assertEquals(cmds.computePaymentAmount(personAged(8), regWith("full-std", "dc-save200"),
                tripWithCodes()).compareTo(new BigDecimal("140.00")), 0);
    }

    @Test
    public void price_threeAndUnderStaysFreeWithCode() {
        Assert.assertEquals(cmds.computePaymentAmount(personAged(2), regWith("full-std", "dc-staff"),
                tripWithCodes()), BigDecimal.ZERO);
    }

    @Test
    public void price_numericOverrideBeatsCode() {
        final Registration reg = regWith("full-std", "dc-staff");
        reg.getOptions().put(Registration.OPT_DISCOUNT, "99.00");
        Assert.assertEquals(cmds.computePaymentAmount(personAged(30), reg, tripWithCodes())
                .compareTo(new BigDecimal("99.00")), 0);
    }

    // --- description / validity helpers ---

    @Test
    public void description_combinesCodeAndDescription() {
        Assert.assertEquals(cmds.getDiscountCodeDescription(tripWithCodes(), regWith("full-std", "dc-save20")),
                "SAVE20 — $20 off");
    }

    @Test
    public void description_nullWhenNoCode() {
        Assert.assertNull(cmds.getDiscountCodeDescription(tripWithCodes(), regChoosing("full-std")));
    }

    @Test
    public void isDiscountCodeValid_mirrorsValidate() {
        Assert.assertTrue(cmds.isDiscountCodeValid(tripWithCodes(), regWith("full-std", "dc-save20"), personAged(30)));
        Assert.assertFalse(cmds.isDiscountCodeValid(tripWithCodes(), regWith("fri", "dc-staff"), personAged(30)));
    }

    // ===== Full permutation matrix: discount-code type × admission-option type × age =====
    //
    // Proves every discount-code "type" (NONE / DISCOUNT_BY / EXACT) is exercised against every
    // representative admission-option "type": a high-priced option above the child cap that the
    // EXACT codes are valid for (full-std $340), a high-priced option the EXACT codes do NOT cover
    // (sat $205 → EXACT mismatch), and a low-priced option below the child cap (fri $120 → EXACT
    // mismatch). Crossed with each age tier (free ≤3, child 4–10, adult >10). Expected values are
    // hand-computed from the documented rule: final = min(ageAdjusted, codedFromBasePrice), where
    // an invalid/mismatched code is not applied. childCap = $179.
    @DataProvider(name = "discountMatrix")
    public static Object[][] discountMatrix() {
        return new Object[][] {
            // --- full-std $340 (EXACT codes valid) ---
            {"full-std", null,          2,  "0",   true},
            {"full-std", "dc-save20",   2,  "0",   true},
            {"full-std", "dc-save200",  2,  "0",   true},
            {"full-std", "dc-staff",    2,  "0",   true},
            {"full-std", "dc-cheap150", 2,  "0",   true},
            {"full-std", null,          8,  "179", true},
            {"full-std", "dc-save20",   8,  "179", true},
            {"full-std", "dc-save200",  8,  "140", true},
            {"full-std", "dc-staff",    8,  "179", true},
            {"full-std", "dc-cheap150", 8,  "150", true},
            {"full-std", null,          30, "340", true},
            {"full-std", "dc-save20",   30, "320", true},
            {"full-std", "dc-save200",  30, "140", true},
            {"full-std", "dc-staff",    30, "250", true},
            {"full-std", "dc-cheap150", 30, "150", true},
            // --- sat $205 (> cap; EXACT codes mismatch) ---
            {"sat", null,          2,  "0",   true},
            {"sat", "dc-save20",   2,  "0",   true},
            {"sat", "dc-save200",  2,  "0",   true},
            {"sat", "dc-staff",    2,  "0",   false},
            {"sat", "dc-cheap150", 2,  "0",   false},
            {"sat", null,          8,  "179", true},
            {"sat", "dc-save20",   8,  "179", true},
            {"sat", "dc-save200",  8,  "5",   true},
            {"sat", "dc-staff",    8,  "179", false},
            {"sat", "dc-cheap150", 8,  "179", false},
            {"sat", null,          30, "205", true},
            {"sat", "dc-save20",   30, "185", true},
            {"sat", "dc-save200",  30, "5",   true},
            {"sat", "dc-staff",    30, "205", false},
            {"sat", "dc-cheap150", 30, "205", false},
            // --- fri $120 (< cap; EXACT codes mismatch) ---
            {"fri", null,          2,  "0",   true},
            {"fri", "dc-save20",   2,  "0",   true},
            {"fri", "dc-save200",  2,  "0",   true},
            {"fri", "dc-staff",    2,  "0",   false},
            {"fri", "dc-cheap150", 2,  "0",   false},
            {"fri", null,          8,  "120", true},
            {"fri", "dc-save20",   8,  "100", true},
            {"fri", "dc-save200",  8,  "0",   true},
            {"fri", "dc-staff",    8,  "120", false},
            {"fri", "dc-cheap150", 8,  "120", false},
            {"fri", null,          30, "120", true},
            {"fri", "dc-save20",   30, "100", true},
            {"fri", "dc-save200",  30, "0",   true},
            {"fri", "dc-staff",    30, "120", false},
            {"fri", "dc-cheap150", 30, "120", false},
        };
    }

    @Test(dataProvider = "discountMatrix")
    public void discountMatrix_priceAndValidity(
            final String admissionId, final String codeId, final int age,
            final String expectedPrice, final boolean expectedValid) {
        final Trip t = tripWithCodes();
        final Registration reg = (codeId == null) ? regChoosing(admissionId) : regWith(admissionId, codeId);
        final Person person = personAged(age);
        final String label = "adm=" + admissionId + " code=" + codeId + " age=" + age;
        Assert.assertEquals(cmds.computePaymentAmount(person, reg, t).compareTo(new BigDecimal(expectedPrice)), 0,
                "price for " + label);
        Assert.assertEquals(cmds.isDiscountCodeValid(t, reg, person), expectedValid, "validity for " + label);
    }

    // ===== Event check-in =============================================================

    @Test
    public void checkIn_setsTimestampAndSaves() {
        final Registration reg = newReg();
        Assert.assertFalse(cmds.isCheckedIn(reg));
        Assert.assertTrue(cmds.checkIn(reg));
        Assert.assertTrue(cmds.isCheckedIn(reg));
        Assert.assertNotNull(reg.getCheckedIn());
    }

    @Test
    public void clearCheckIn_removesFlagAndSaves() {
        final Registration reg = newReg();
        Assert.assertTrue(cmds.checkIn(reg));
        Assert.assertTrue(cmds.clearCheckIn(reg));
        Assert.assertFalse(cmds.isCheckedIn(reg));
        Assert.assertNull(reg.getCheckedIn());
    }

    @Test
    public void checkIn_handlesNullRegistration() {
        Assert.assertFalse(cmds.checkIn(null));
        Assert.assertFalse(cmds.clearCheckIn(null));
        Assert.assertFalse(cmds.isCheckedIn(null));
    }

    @Test
    public void checkIn_isVisibleOnReloadedRegistration() {
        final Registration reg = newReg();
        Assert.assertTrue(cmds.checkIn(reg));
        final Registration reloaded = cmds.getRegistration(reg.getTripId(), reg.getUserId());
        Assert.assertTrue(cmds.isCheckedIn(reloaded));
        Assert.assertEquals(reloaded.getCheckedIn(), reg.getCheckedIn());
    }

    // ===== isAfterDeadline ============================================================

    private static final String DEADLINE = "2026-07-12T23:59:59";
    private static final String ZONE = "America/New_York";

    @Test
    public void isAfterDeadline_falseWellBeforeDeadline() {
        Assert.assertFalse(cmds.isAfterDeadline(regCreatedAt(LocalDateTime.of(2026, 1, 1, 12, 0)), DEADLINE, ZONE));
    }

    @Test
    public void isAfterDeadline_trueWellAfterDeadline() {
        Assert.assertTrue(cmds.isAfterDeadline(regCreatedAt(LocalDateTime.of(2026, 12, 1, 12, 0)), DEADLINE, ZONE));
    }

    @Test
    public void isAfterDeadline_falseForNullInputs() {
        final Registration reg = regCreatedAt(LocalDateTime.of(2026, 12, 1, 12, 0));
        Assert.assertFalse(cmds.isAfterDeadline(null, DEADLINE, ZONE));
        Assert.assertFalse(cmds.isAfterDeadline(reg, null, ZONE));
        Assert.assertFalse(cmds.isAfterDeadline(reg, DEADLINE, null));
    }

    @Test
    public void isAfterDeadline_falseForUnparseableInputs() {
        final Registration reg = regCreatedAt(LocalDateTime.of(2026, 12, 1, 12, 0));
        Assert.assertFalse(cmds.isAfterDeadline(reg, "July 12th, 2026", ZONE));
        Assert.assertFalse(cmds.isAfterDeadline(reg, DEADLINE, "Not/AZone"));
    }

    // ===== findRegistrants ============================================================

    @Test
    public void findRegistrants_matchesPartialLastNameCaseInsensitive() {
        final String tripId = RandomData.genAlpha(10);
        final Person smith = registeredPerson(tripId, "Bob", "Smith");
        final Person smythe = registeredPerson(tripId, "Ann", "Smythe");
        registeredPerson(tripId, "Cat", "Jones");

        final List<Person> smi = cmds.findRegistrants(tripId, "smi");
        Assert.assertEquals(smi.size(), 1);
        Assert.assertEquals(smi.get(0).getId(), smith.getId());

        final List<Person> sm = cmds.findRegistrants(tripId, "SM");
        Assert.assertEquals(sm.size(), 2);
        Assert.assertEquals(sm.get(0).getId(), smith.getId());   // sorted: Smith before Smythe
        Assert.assertEquals(sm.get(1).getId(), smythe.getId());
    }

    @Test
    public void findRegistrants_matchesFirstNameToo() {
        final String tripId = RandomData.genAlpha(10);
        final Person robert = registeredPerson(tripId, "Robert", "Jones");
        registeredPerson(tripId, "Ann", "Smith");

        final List<Person> result = cmds.findRegistrants(tripId, "robe");
        Assert.assertEquals(result.size(), 1);
        Assert.assertEquals(result.get(0).getId(), robert.getId());
    }

    @Test
    public void findRegistrants_sortsByLastThenFirst() {
        final String tripId = RandomData.genAlpha(10);
        final Person zed = registeredPerson(tripId, "Zed", "Adams");
        final Person amy = registeredPerson(tripId, "Amy", "Adams");
        final Person bob = registeredPerson(tripId, "Bob", "Baker");

        final List<Person> result = cmds.findRegistrants(tripId, "a");
        Assert.assertEquals(result.size(), 3);                    // "Baker" also contains 'a'
        Assert.assertEquals(result.get(0).getId(), amy.getId());
        Assert.assertEquals(result.get(1).getId(), zed.getId());
        Assert.assertEquals(result.get(2).getId(), bob.getId());
    }

    @Test
    public void findRegistrants_blankOrNullInputsReturnEmpty() {
        final String tripId = RandomData.genAlpha(10);
        registeredPerson(tripId, "Bob", "Smith");
        Assert.assertTrue(cmds.findRegistrants(tripId, null).isEmpty());
        Assert.assertTrue(cmds.findRegistrants(tripId, "   ").isEmpty());
        Assert.assertTrue(cmds.findRegistrants(null, "smith").isEmpty());
    }

    @Test
    public void findRegistrants_noMatchesReturnsEmpty() {
        final String tripId = RandomData.genAlpha(10);
        registeredPerson(tripId, "Bob", "Smith");
        Assert.assertTrue(cmds.findRegistrants(tripId, "nobody-by-this-name").isEmpty());
    }

    // ===== Helpers =====================================================================

    private Person registeredPerson(final String tripId, final String first, final String last) {
        final Person person = Person.builder()
                .id(Person.Id.newInstance())
                .first(first)
                .last(last)
                .build();
        Assert.assertTrue(PersonCommands.getPersonCommands().savePerson(person));
        Assert.assertTrue(cmds.saveRegistration(new Registration(tripId, person.getId())));
        return person;
    }

    private static Trip trip() {
        return Trip.builder()
                .admissionOptions(List.of(FULL_STD, FULL_ROSEN, FRI, SAT, HIDDEN))
                .childPriceCap(CHILD_CAP)
                .build();
    }

    private static Trip tripWithCodes() {
        return Trip.builder()
                .admissionOptions(List.of(FULL_STD, FULL_ROSEN, FRI, SAT, HIDDEN))
                .childPriceCap(CHILD_CAP)
                .discountCodes(List.of(SAVE20, SAVE200, BIG500, STAFF, CHEAP150, EXPIRED, DISABLED))
                .build();
    }

    private static Registration newReg() {
        return new Registration(RandomData.genAlpha(5), Person.Id.newInstance());
    }

    private static Registration regCreatedAt(final LocalDateTime created) {
        return new Registration(RandomData.genAlpha(5), Person.Id.newInstance(), created, null, null);
    }

    private static Registration regChoosing(final String admissionId) {
        final Registration reg = newReg();
        reg.getOptions().put(Registration.OPT_ADMISSION, admissionId);
        return reg;
    }

    private static Registration regWith(final String admissionId, final String discountCodeId) {
        final Registration reg = regChoosing(admissionId);
        reg.getOptions().put(Registration.OPT_DISCOUNT_CODE, discountCodeId);
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
