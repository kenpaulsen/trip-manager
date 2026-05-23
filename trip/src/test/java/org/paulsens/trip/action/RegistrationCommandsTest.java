package org.paulsens.trip.action;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Unit tests for the age/pricing/metadata helpers added to {@link RegistrationCommands}.
 *
 * <p>Pricing rules under test (SummerFest 2026 ladder):
 * <ul>
 *   <li>Age ≤ 3 → $0 (free)</li>
 *   <li>Age 4–10 → $179 (child price, immune to discounts)</li>
 *   <li>Age &gt; 10, {@code opt9="true"} → $320 base ($295 with early-bird)</li>
 *   <li>Age &gt; 10, {@code opt9="false"} → $340 base ($315 with early-bird)</li>
 *   <li>Age &gt; 10, {@code opt9} unset → $0 (UI prompts the user to choose)</li>
 *   <li>Numeric discount string ({@code "175"}, {@code "$175.00"}) → final total override</li>
 * </ul>
 */
public class RegistrationCommandsTest {

    private final RegistrationCommands cmds = new RegistrationCommands();

    // ===== getAge =====================================================================

    @Test
    public void getAge_returns999_whenPersonIsNull() {
        Assert.assertEquals(cmds.getAge(null), 999);
    }

    @Test
    public void getAge_returns999_whenBirthdateIsNull() {
        final Person p = personWithBirthdate(null);
        Assert.assertEquals(cmds.getAge(p), 999);
    }

    @Test
    public void getAge_returnsYearsBetweenBirthdateAndToday() {
        final Person p = personWithBirthdate(LocalDate.now().minusYears(25));
        Assert.assertEquals(cmds.getAge(p), 25);
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
        Assert.assertEquals(cmds.deriveRegistrantType(personAged(4)),
                Registration.RegistrantType.CHILD);
        Assert.assertEquals(cmds.deriveRegistrantType(personAged(10)),
                Registration.RegistrantType.CHILD);
    }

    @Test
    public void deriveRegistrantType_adult() {
        Assert.assertEquals(cmds.deriveRegistrantType(personAged(11)),
                Registration.RegistrantType.ADULT);
        Assert.assertEquals(cmds.deriveRegistrantType(personAged(50)),
                Registration.RegistrantType.ADULT);
    }

    @Test
    public void deriveRegistrantType_treatsMissingBirthdateAsAdult() {
        Assert.assertEquals(cmds.deriveRegistrantType(personWithBirthdate(null)),
                Registration.RegistrantType.ADULT);
    }

    // ===== computePaymentAmount — base prices =========================================

    @Test
    public void price_freeForThreeAndUnder() {
        final Registration reg = newReg();
        // Even with a paid discount tag, base of 0 stays 0.
        reg.getOptions().put(Registration.OPT_DISCOUNT, Registration.Discount.STANDARD);
        Assert.assertEquals(cmds.computePaymentAmount(personAged(2), reg),
                BigDecimal.ZERO);
    }

    @Test
    public void price_childIs179_regardlessOfDiscount() {
        final Registration reg = newReg();
        reg.getOptions().put(Registration.OPT_DISCOUNT, Registration.Discount.EARLY_BIRD);
        Assert.assertEquals(
                cmds.computePaymentAmount(personAged(8), reg).compareTo(new BigDecimal("179.00")),
                0);
    }

    @Test
    public void price_adultRosenStandardIs320() {
        final Registration reg = newReg();
        reg.getOptions().put(RegistrationCommands.ROSEN_CENTRE_OPT_KEY, "true");
        reg.getOptions().put(Registration.OPT_DISCOUNT, Registration.Discount.STANDARD);
        Assert.assertEquals(
                cmds.computePaymentAmount(personAged(25), reg).compareTo(new BigDecimal("320.00")),
                0);
    }

    @Test
    public void price_adultNonRosenStandardIs340() {
        final Registration reg = newReg();
        reg.getOptions().put(RegistrationCommands.ROSEN_CENTRE_OPT_KEY, "false");
        reg.getOptions().put(Registration.OPT_DISCOUNT, Registration.Discount.STANDARD);
        Assert.assertEquals(
                cmds.computePaymentAmount(personAged(25), reg).compareTo(new BigDecimal("340.00")),
                0);
    }

    @Test
    public void price_adultUnselectedRosenIsZero() {
        final Registration reg = newReg();
        // opt9 not set
        reg.getOptions().put(Registration.OPT_DISCOUNT, Registration.Discount.STANDARD);
        Assert.assertEquals(cmds.computePaymentAmount(personAged(30), reg),
                BigDecimal.ZERO);
    }

    // ===== computePaymentAmount — early-bird discount =================================

    @Test
    public void price_adultRosenWithEarlyBirdIs295() {
        final Registration reg = newReg();
        reg.getOptions().put(RegistrationCommands.ROSEN_CENTRE_OPT_KEY, "true");
        reg.getOptions().put(Registration.OPT_DISCOUNT, Registration.Discount.EARLY_BIRD);
        Assert.assertEquals(
                cmds.computePaymentAmount(personAged(25), reg).compareTo(new BigDecimal("295.00")),
                0);
    }

    @Test
    public void price_adultNonRosenWithEarlyBirdIs315() {
        final Registration reg = newReg();
        reg.getOptions().put(RegistrationCommands.ROSEN_CENTRE_OPT_KEY, "false");
        reg.getOptions().put(Registration.OPT_DISCOUNT, Registration.Discount.EARLY_BIRD);
        Assert.assertEquals(
                cmds.computePaymentAmount(personAged(25), reg).compareTo(new BigDecimal("315.00")),
                0);
    }

    @Test
    public void price_unknownDiscountTreatedAsNoDiscount() {
        final Registration reg = newReg();
        reg.getOptions().put(RegistrationCommands.ROSEN_CENTRE_OPT_KEY, "false");
        reg.getOptions().put(Registration.OPT_DISCOUNT, "scholarship"); // not yet wired
        Assert.assertEquals(
                cmds.computePaymentAmount(personAged(25), reg).compareTo(new BigDecimal("340.00")),
                0,
                "scholarship discount logic not implemented yet — should fall through to base price");
    }

    // ===== computePaymentAmount — numeric override ====================================

    @Test
    public void price_numericDiscountOverridesTotal() {
        final Registration reg = newReg();
        reg.getOptions().put(RegistrationCommands.ROSEN_CENTRE_OPT_KEY, "false"); // base=340
        reg.getOptions().put(Registration.OPT_DISCOUNT, "175.00");
        Assert.assertEquals(
                cmds.computePaymentAmount(personAged(25), reg).compareTo(new BigDecimal("175.00")),
                0);
    }

    @Test
    public void price_dollarPrefixedNumericOverrideWorks() {
        final Registration reg = newReg();
        reg.getOptions().put(RegistrationCommands.ROSEN_CENTRE_OPT_KEY, "true"); // base=320
        reg.getOptions().put(Registration.OPT_DISCOUNT, "$50");
        Assert.assertEquals(
                cmds.computePaymentAmount(personAged(25), reg).compareTo(new BigDecimal("50")),
                0);
    }

    @Test
    public void price_numericOverrideOverridesChildPriceToo() {
        final Registration reg = newReg();
        reg.getOptions().put(Registration.OPT_DISCOUNT, "25");
        // Even a child gets the override total (admin-set special case).
        Assert.assertEquals(
                cmds.computePaymentAmount(personAged(8), reg).compareTo(new BigDecimal("25")),
                0);
    }

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
        Assert.assertEquals(cmds.parseDiscountOverride("175"),    new BigDecimal("175"));
        Assert.assertEquals(cmds.parseDiscountOverride("175.00"), new BigDecimal("175.00"));
        Assert.assertEquals(cmds.parseDiscountOverride("$50"),    new BigDecimal("50"));
        Assert.assertEquals(cmds.parseDiscountOverride(" $50.50 "), new BigDecimal("50.50"));
    }

    // ===== applyAutoMetadata ==========================================================

    @Test
    public void applyAutoMetadata_setsRegistrantTypeFromAge() {
        final Registration reg = newReg();
        cmds.applyAutoMetadata(reg, personAged(2), null);
        Assert.assertEquals(reg.getRegistrantType(),
                Registration.RegistrantType.THREE_AND_UNDER);

        cmds.applyAutoMetadata(reg, personAged(7), null);
        Assert.assertEquals(reg.getRegistrantType(),
                Registration.RegistrantType.CHILD,
                "registrantType should be re-derived on every call (covers birthdate corrections)");

        cmds.applyAutoMetadata(reg, personAged(40), null);
        Assert.assertEquals(reg.getRegistrantType(), Registration.RegistrantType.ADULT);
    }

    @Test
    public void applyAutoMetadata_nullDiscountSeedsStandard() {
        final Registration reg = newReg();
        cmds.applyAutoMetadata(reg, personAged(25), null);
        Assert.assertEquals(reg.getDiscount(), Registration.Discount.STANDARD);
    }

    @Test
    public void applyAutoMetadata_explicitDiscountSeedsThatValue() {
        final Registration reg = newReg();
        cmds.applyAutoMetadata(reg, personAged(25), Registration.Discount.EARLY_BIRD);
        Assert.assertEquals(reg.getDiscount(), Registration.Discount.EARLY_BIRD);
    }

    @Test
    public void applyAutoMetadata_doesNotClobberExistingDiscount() {
        final Registration reg = newReg();
        // Simulate an admin override that was saved previously.
        reg.getOptions().put(Registration.OPT_DISCOUNT, Registration.Discount.SCHOLARSHIP);

        cmds.applyAutoMetadata(reg, personAged(25), Registration.Discount.EARLY_BIRD);
        Assert.assertEquals(reg.getDiscount(), Registration.Discount.SCHOLARSHIP,
                "applyAutoMetadata should never overwrite an existing discount value");

        cmds.applyAutoMetadata(reg, personAged(25), null);
        Assert.assertEquals(reg.getDiscount(), Registration.Discount.SCHOLARSHIP);
    }

    @Test
    public void applyAutoMetadata_defaultsRegistrationTypeToFull() {
        final Registration reg = newReg();
        cmds.applyAutoMetadata(reg, personAged(25), null);
        Assert.assertEquals(reg.getRegistrationType(), Registration.RegistrationType.FULL);
    }

    @Test
    public void applyAutoMetadata_forcesOpt9FalseForChildren() {
        final Registration reg = newReg();
        cmds.applyAutoMetadata(reg, personAged(7), null);
        Assert.assertEquals(reg.getOptions().get(RegistrationCommands.ROSEN_CENTRE_OPT_KEY),
                "false");
    }

    @Test
    public void applyAutoMetadata_doesNotTouchOpt9ForAdults() {
        final Registration reg = newReg();
        reg.getOptions().put(RegistrationCommands.ROSEN_CENTRE_OPT_KEY, "true");
        cmds.applyAutoMetadata(reg, personAged(25), null);
        Assert.assertEquals(reg.getOptions().get(RegistrationCommands.ROSEN_CENTRE_OPT_KEY),
                "true",
                "adults' Rosen Centre choice must be preserved");
    }

    @Test
    public void applyAutoMetadata_doesNotTouchOpt9ForFreeChildren() {
        final Registration reg = newReg();
        cmds.applyAutoMetadata(reg, personAged(2), null);
        Assert.assertNull(reg.getOptions().get(RegistrationCommands.ROSEN_CENTRE_OPT_KEY),
                "3-and-under is free; opt9 should remain untouched");
    }

    @Test
    public void applyAutoMetadata_returnsSameInstance() {
        final Registration reg = newReg();
        Assert.assertSame(cmds.applyAutoMetadata(reg, personAged(25), null), reg);
    }

    // ===== Helpers =====================================================================

    private static Registration newReg() {
        return new Registration(RandomData.genAlpha(5), Person.Id.newInstance());
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
