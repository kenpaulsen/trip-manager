package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AdmissionOption;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Trip;

@Slf4j
@Named("reg")
@ApplicationScoped
public class RegistrationCommands {
    private static final String ROOM = "room";

    // ---- Pricing policy ------------------------------------------------------------------
    // NEW model: prices and the set of admission options are *data* on the Trip
    // ({@link Trip#getAdmissionOptions()} / {@link Trip#getChildPriceCap()}); only the age
    // ladder (which the requirements say "stays as-is") lives here as policy. Used by
    // register-new.xhtml.
    //
    // Age thresholds for the registrant-type ladder.
    private static final int AGE_FREE_MAX  = 3;
    private static final int AGE_CHILD_MAX = 10;
    // Sentinel age returned when birthdate is unknown — treats them as adult-by-default.
    private static final int AGE_UNKNOWN   = 999;

    /**
     * Per-trip registration option id capturing whether the registrant is staying at the Rosen
     * Centre Hotel. The legacy register.xhtml flow stored {@code "true"}/{@code "false"} here;
     * the new flow encodes Rosen-ness in the chosen {@link AdmissionOption} instead. This key is
     * still read when displaying older registrations that predate the admission-option field.
     */
    static final String ROSEN_CENTRE_OPT_KEY = "opt9";
    // Full-weekend admission option ids assumed for older registrations that have no admission
    // selection (see getEffectiveAdmissionLabel): "full-rosen" for Rosen guests, else "full".
    private static final String DEFAULT_ADMISSION_ID       = "full";
    private static final String DEFAULT_ADMISSION_ID_ROSEN = "full-rosen";

    // ---- LEGACY pricing (register.xhtml) -------------------------------------------------
    // DELETE this whole block (and the legacy methods at the bottom of the file, plus
    // RegistrationCommandsLegacyTest) once register-new.xhtml replaces register.xhtml.
    private static final BigDecimal PRICE_FREE           = BigDecimal.ZERO;
    private static final BigDecimal PRICE_CHILD          = new BigDecimal("179.00");
    private static final BigDecimal PRICE_ADULT_ROSEN    = new BigDecimal("320.00");
    private static final BigDecimal PRICE_ADULT_STANDARD = new BigDecimal("340.00");
    private static final BigDecimal DISCOUNT_EARLY_BIRD  = new BigDecimal("25.00");

    public Registration createRegistration(final String tripId, final Person.Id userId) {
        return new Registration(tripId, userId);
    }

    public boolean saveRegistration(final Registration reg) {
        boolean result;
        try {
             result = DAO.getInstance().saveRegistration(reg)
                     .exceptionally(ex -> {
                             TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                                     "Error saving registration for '" + reg.getUserId() + "': " + reg.getTripId(),
                                     ex.getMessage());
                             log.error("Error while saving registration: ", ex);
                            return false;
                        }).join();
        } catch (final IOException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Unable to save registration '" + reg.getUserId() + "': " + reg.getTripId(), ex.getMessage());
            log.warn("Error while saving registration: ", ex);
            result = false;
        }
        return result;
    }

    public List<Registration> getRegistrations(final String tripId) {
        return DAO.getInstance()
                .getRegistrations(tripId)
                .exceptionally(ex -> {
                    log.error("Failed to get registrations for trip '" + tripId + "'!", ex);
                    return Collections.emptyList();
                }).join();
    }

    public int getNumPending(final String tripId) {
        int result = 0;
        for (final Registration reg : getRegistrations(tripId)) {
            if (reg.getStatus() == Registration.Status.PENDING) {
                result++;
            }
        }
        return result;
    }

    public Registration getRegistration(final String tripId, final Person.Id userId) {
        if (tripId == null) {
            log.error("getRegistration() called with null tripId");
            return null;
        }
        if (userId == null) {
            log.error("getRegistration() called with null userId");
            return null;
        }
        return DAO.getInstance()
                .getRegistration(tripId, userId)
                .exceptionally(ex -> {
                    log.error("Failed to get registration for user '" + userId.getValue()
                            + "' on trip '" + tripId + "'!", ex);
                    return Optional.empty();
                }).join().orElse(createRegistration(tripId, userId));
    }

    public PersonDataValue getRoomPDV(final String tripId, final Person.Id userId) {
        if (tripId == null) {
            log.error("getRoom() called with null tripId");
            return null;
        }
        if (userId == null) {
            log.error("getRoom() called with null userId");
            return null;
        }
        PersonDataValue pdv = PersonDataValueCommands.getPersonDataValue(userId, getTripRoomDataId(tripId));
        if (pdv == null) {
            pdv = PersonDataValueCommands.createPersonDataValue(userId, getTripRoomDataId(tripId), ROOM);
            pdv.setContent("");
            PersonDataValueCommands.savePersonDataValue(pdv);
        }
        return pdv;
    }

    public void saveRoom(final String tripId, final Person.Id userId) {
        if (tripId == null) {
            log.error("setRoom() called with null tripId");
        }
        if (userId == null) {
            log.error("setRoom() called with null userId");
        }
        final PersonDataValue pdv = PersonDataValueCommands.getPersonDataValue(userId, getTripRoomDataId(tripId));
        if (pdv != null) {
            PersonDataValueCommands.savePersonDataValue(pdv);
        }
    }

    private DataId getTripRoomDataId(final String tripId) {
        return DataId.from(ROOM + tripId);
    }

    // ===== Age / pricing / metadata helpers ===========================================

    /**
     * @return the person's age in years today, or {@value #AGE_UNKNOWN} if the person or their
     *         birthdate is missing (treated as adult-by-default by downstream pricing).
     */
    public int getAge(final Person person) {
        final int result;
        if ((person == null) || (person.getBirthdate() == null)) {
            result = AGE_UNKNOWN;
        } else {
            result = Period.between(person.getBirthdate(), LocalDate.now()).getYears();
        }
        return result;
    }

    /** Maps the person's age onto the standard registrant-type vocabulary. */
    public String deriveRegistrantType(final Person person) {
        final int age = getAge(person);
        final String result;
        if (age <= AGE_FREE_MAX) {
            result = Registration.RegistrantType.THREE_AND_UNDER;
        } else if (age <= AGE_CHILD_MAX) {
            result = Registration.RegistrantType.CHILD;
        } else {
            result = Registration.RegistrantType.ADULT;
        }
        return result;
    }

    /**
     * Returns the admission options that should be offered to {@code person} on {@code trip}'s
     * registration page, in trip-declared order.
     *
     * <ul>
     *   <li>Only options flagged {@link AdmissionOption#getShow()} are returned.</li>
     *   <li>Children (age &le; {@value #AGE_CHILD_MAX}, including 3-and-under) never see the
     *       Rosen-Centre-discounted variants — those exist only to give adult hotel guests a
     *       discount, and the child cap already prices below them. Children still choose among
     *       the remaining options so we capture which day(s) they will attend for headcount.</li>
     * </ul>
     *
     * @return a (possibly empty) list; never {@code null}.
     */
    public List<AdmissionOption> getAvailableOptions(final Trip trip, final Person person) {
        if (trip == null) {
            return Collections.emptyList();
        }
        final boolean child = getAge(person) <= AGE_CHILD_MAX;
        return trip.getAdmissionOptions().stream()
                .filter(opt -> Boolean.TRUE.equals(opt.getShow()))
                .filter(opt -> !child || !Boolean.TRUE.equals(opt.getRosenDiscounted()))
                .toList();
    }

    /**
     * Price charged for {@code person} choosing {@code opt} on {@code trip}, in USD.
     *
     * <ul>
     *   <li>Age &le; {@value #AGE_FREE_MAX} → free ($0).</li>
     *   <li>Age {@value #AGE_FREE_MAX}&lt;age&le;{@value #AGE_CHILD_MAX} (child) → the lower of
     *       the option price and the trip's {@link Trip#getChildPriceCap() child price cap}
     *       (when a cap is configured); e.g. with a $179 cap a $340 option costs $179 but a
     *       $120 single-day option still costs $120.</li>
     *   <li>Otherwise (adult) → the option's full price.</li>
     * </ul>
     *
     * @return the price, or {@code BigDecimal.ZERO} if {@code opt} or its price is {@code null}.
     */
    public BigDecimal computeOptionPrice(final Trip trip, final Person person, final AdmissionOption opt) {
        if ((opt == null) || (opt.getPrice() == null)) {
            return BigDecimal.ZERO;
        }
        final int age = getAge(person);
        if (age <= AGE_FREE_MAX) {
            return BigDecimal.ZERO;
        }
        if (age <= AGE_CHILD_MAX) {
            final BigDecimal cap = (trip == null) ? null : trip.getChildPriceCap();
            return (cap == null) ? opt.getPrice() : opt.getPrice().min(cap);
        }
        return opt.getPrice();
    }

    /**
     * Computes the total payment due for this registration, in USD.
     *
     * <p>If {@link Registration#getDiscount()} parses as a number (e.g. {@code "175"},
     * {@code "175.00"}, {@code "$175"}), that value is the final total — an admin override that
     * trumps everything (used for scholarships, comped registrations, etc.). Otherwise the price
     * is that of the chosen {@link AdmissionOption} (resolved by {@link Registration#getAdmissionId()}
     * against the trip), adjusted for age via {@link #computeOptionPrice}. If no option has been
     * chosen yet, the result is $0 so the UI prompts the user to pick one.
     */
    public BigDecimal computePaymentAmount(final Person person, final Registration reg, final Trip trip) {
        final BigDecimal override = parseDiscountOverride(reg.getDiscount());
        if (override != null) {
            return override;
        }
        final AdmissionOption opt = (trip == null) ? null : trip.getAdmissionOption(reg.getAdmissionId());
        return computeOptionPrice(trip, person, opt);
    }

    /** @return the label of the chosen admission option, or {@code null} if none is selected. */
    public String getAdmissionLabel(final Trip trip, final Registration reg) {
        final AdmissionOption opt = ((trip == null) || (reg == null))
                ? null : trip.getAdmissionOption(reg.getAdmissionId());
        return (opt == null) ? null : opt.getLabel();
    }

    /**
     * @return whether this registration is a Rosen Centre Hotel guest. Newer registrations encode
     *   this via the chosen admission option ({@link AdmissionOption#getRosenDiscounted()}); older
     *   ones via the {@code opt9} field. When neither is present (older data), assumed {@code false}.
     */
    public boolean isRosenCentre(final Trip trip, final Registration reg) {
        if (reg == null) {
            return false;
        }
        final AdmissionOption opt = (trip == null) ? null : trip.getAdmissionOption(reg.getAdmissionId());
        if (opt != null) {
            return Boolean.TRUE.equals(opt.getRosenDiscounted());
        }
        return "true".equals(reg.getOptions().get(ROSEN_CENTRE_OPT_KEY));
    }

    /**
     * Admission option label for display, applying defaults for older registrations that predate
     * the admission-option field: a missing selection is treated as the full-weekend option
     * ({@link #DEFAULT_ADMISSION_ID_ROSEN} when {@link #isRosenCentre} is true, else
     * {@link #DEFAULT_ADMISSION_ID}).
     *
     * @return the matched option's label, or the fallback id itself if the trip has no such option.
     */
    public String getEffectiveAdmissionLabel(final Trip trip, final Registration reg) {
        if (reg == null) {
            return null;
        }
        String id = reg.getAdmissionId();
        if ((id == null) || id.isBlank()) {
            id = isRosenCentre(trip, reg) ? DEFAULT_ADMISSION_ID_ROSEN : DEFAULT_ADMISSION_ID;
        }
        final AdmissionOption opt = (trip == null) ? null : trip.getAdmissionOption(id);
        return (opt == null) ? id : opt.getLabel();
    }

    /**
     * Populates auto-derived registration metadata on {@code reg}'s options map. Intended to be
     * called from {@code register.xhtml} on every {@code initPage} (idempotent).
     *
     * <p>{@link Registration#OPT_REGISTRANT_TYPE} is always re-derived from {@code person}'s age
     * (so birthdate corrections take effect on the next load). The chosen admission option
     * ({@link Registration#OPT_ADMISSION}) and any admin discount override are left untouched.
     *
     * @return the same {@code reg} instance, for fluent use.
     */
    public Registration applyAutoMetadata(final Registration reg, final Person person) {
        reg.getOptions().put(Registration.OPT_REGISTRANT_TYPE, deriveRegistrantType(person));
        return reg;
    }

    // ===== LEGACY pricing/metadata (register.xhtml) ===================================
    // Kept so the existing register.xhtml keeps working alongside register-new.xhtml during
    // side-by-side review. DELETE this section (and the legacy constants + the
    // RegistrationCommandsLegacyTest) once register-new.xhtml replaces register.xhtml.

    /**
     * LEGACY. Computes the total payment due using the old Rosen-yes/no + age ladder (with the
     * $25 early-bird discount). Superseded by {@link #computePaymentAmount(Person, Registration,
     * Trip)}, which prices from the chosen {@link AdmissionOption}.
     */
    public BigDecimal computePaymentAmount(final Person person, final Registration reg) {
        final BigDecimal override = parseDiscountOverride(reg.getDiscount());
        final BigDecimal result;
        if (override != null) {
            result = override;
        } else {
            final BigDecimal basePrice = computeBasePrice(person, reg);
            final BigDecimal discountAmt = computeNamedDiscount(reg.getDiscount(), person);
            final BigDecimal afterDiscount = basePrice.subtract(discountAmt);
            result = (afterDiscount.signum() < 0) ? BigDecimal.ZERO : afterDiscount;
        }
        return result;
    }

    /**
     * LEGACY. Seeds {@code _registrantType}/{@code _discount}/{@code _regType} and forces
     * {@code opt9=false} for children. Superseded by {@link #applyAutoMetadata(Registration,
     * Person)}.
     */
    public Registration applyAutoMetadata(
            final Registration reg, final Person person, final String discount) {
        final Map<String, String> opts = reg.getOptions();
        opts.put(Registration.OPT_REGISTRANT_TYPE, deriveRegistrantType(person));
        final String discountSeed = (discount == null) ? Registration.Discount.STANDARD : discount;
        opts.putIfAbsent(Registration.OPT_DISCOUNT, discountSeed);
        opts.putIfAbsent(Registration.OPT_REGISTRATION_TYPE, Registration.RegistrationType.FULL);
        final int age = getAge(person);
        if ((age > AGE_FREE_MAX) && (age <= AGE_CHILD_MAX)) {
            opts.put(ROSEN_CENTRE_OPT_KEY, "false");
        }
        return reg;
    }

    /** LEGACY. Base (undiscounted) price by age + Rosen Centre selection. */
    private BigDecimal computeBasePrice(final Person person, final Registration reg) {
        final int age = getAge(person);
        final BigDecimal result;
        if (age <= AGE_FREE_MAX) {
            result = PRICE_FREE;
        } else if (age <= AGE_CHILD_MAX) {
            result = PRICE_CHILD;
        } else {
            final String rosen = reg.getOptions().get(ROSEN_CENTRE_OPT_KEY);
            if ("true".equals(rosen)) {
                result = PRICE_ADULT_ROSEN;
            } else if ("false".equals(rosen)) {
                result = PRICE_ADULT_STANDARD;
            } else {
                result = PRICE_FREE;
            }
        }
        return result;
    }

    /** LEGACY. Named-discount lookup (adults only); only early-bird is wired. */
    private BigDecimal computeNamedDiscount(final String discount, final Person person) {
        BigDecimal result = BigDecimal.ZERO;
        if ((getAge(person) > AGE_CHILD_MAX) && Registration.Discount.EARLY_BIRD.equals(discount)) {
            result = DISCOUNT_EARLY_BIRD;
        }
        return result;
    }

    /**
     * @return the parsed BigDecimal if {@code discount} is a numeric string (optionally prefixed
     *         with {@code "$"}), or {@code null} if it's null/blank/non-numeric (a named tag).
     */
    BigDecimal parseDiscountOverride(final String discount) {
        BigDecimal result = null;
        if ((discount != null) && !discount.isBlank()) {
            final String trimmed = discount.trim();
            final String stripped;
            if (trimmed.startsWith("$")) {
                stripped = trimmed.substring(1).trim();
            } else {
                stripped = trimmed;
            }
            try {
                result = new BigDecimal(stripped);
            } catch (final NumberFormatException ignored) {
                // Not a numeric override — caller will treat it as a named-discount tag.
                result = null;
            }
        }
        return result;
    }
}
