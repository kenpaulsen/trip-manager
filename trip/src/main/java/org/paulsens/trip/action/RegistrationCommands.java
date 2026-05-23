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
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.Registration;

@Slf4j
@Named("reg")
@ApplicationScoped
public class RegistrationCommands {
    private static final String ROOM = "room";

    /**
     * Per-trip registration option id used by the SummerFest 2026 trip to capture whether the
     * registrant will be staying at the Rosen Centre Hotel. Adults answering {@code "true"} get
     * the Rosen Centre price; {@code "false"} gets the standard price.
     *
     * <p>FIXME: This couples pricing to the SummerFest 2026 trip option layout. When a second
     * trip needs automated pricing, parameterize this per-trip (e.g. via a pricing-rule field
     * on {@link org.paulsens.trip.model.Trip}).
     */
    static final String ROSEN_CENTRE_OPT_KEY = "opt9";

    // ---- Pricing constants ---------------------------------------------------------------
    // Base (undiscounted) prices for the SummerFest 2026 trip.
    private static final BigDecimal PRICE_FREE             = BigDecimal.ZERO;
    private static final BigDecimal PRICE_CHILD            = new BigDecimal("179.00");
    private static final BigDecimal PRICE_ADULT_ROSEN      = new BigDecimal("320.00");
    private static final BigDecimal PRICE_ADULT_STANDARD   = new BigDecimal("340.00");
    // Named-discount amounts (subtracted from base).
    private static final BigDecimal DISCOUNT_EARLY_BIRD    = new BigDecimal("25.00");
    // Age thresholds for the registrant-type ladder.
    private static final int AGE_FREE_MAX  = 3;
    private static final int AGE_CHILD_MAX = 10;
    // Sentinel age returned when birthdate is unknown — treats them as adult-by-default.
    private static final int AGE_UNKNOWN   = 999;

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
     * Computes the total payment due for this registration, in USD.
     *
     * <p>If {@link Registration#getDiscount()} parses as a number (e.g. {@code "175"},
     * {@code "175.00"}, {@code "$175"}), that value is the final total — overriding the base
     * ladder entirely. Otherwise the value is treated as a named discount and subtracted from
     * the age/Rosen-Centre base price (negative results clamped to zero). Unknown / unset
     * discount tags contribute $0 off.
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
            if (afterDiscount.signum() < 0) {
                result = BigDecimal.ZERO;
            } else {
                result = afterDiscount;
            }
        }
        return result;
    }

    /**
     * Populates auto-derived registration metadata on {@code reg}'s options map. Intended to be
     * called from {@code register.xhtml} on every {@code initPage} (idempotent).
     *
     * <p>Behavior:
     * <ul>
     *   <li>{@link Registration#OPT_REGISTRANT_TYPE} is always re-derived from {@code person}'s
     *       age (birthdate corrections take effect on next load).</li>
     *   <li>{@link Registration#OPT_DISCOUNT} is seeded via {@code putIfAbsent} — if {@code
     *       discount} is non-null it is used as the seed; otherwise the seed is
     *       {@link Registration.Discount#STANDARD}. Once a value is present (page seed or admin
     *       override) it is preserved across subsequent calls.</li>
     *   <li>{@link Registration#OPT_REGISTRATION_TYPE} defaults to
     *       {@link Registration.RegistrationType#FULL} if absent.</li>
     *   <li>Preserves existing behavior: children (age 4–10) get {@link #ROSEN_CENTRE_OPT_KEY}
     *       forced to {@code "false"}.</li>
     * </ul>
     *
     * @return the same {@code reg} instance, for fluent use.
     */
    public Registration applyAutoMetadata(
            final Registration reg, final Person person, final String discount) {
        final Map<String, String> opts = reg.getOptions();
        opts.put(Registration.OPT_REGISTRANT_TYPE, deriveRegistrantType(person));
        final String discountSeed;
        if (discount == null) {
            discountSeed = Registration.Discount.STANDARD;
        } else {
            discountSeed = discount;
        }
        opts.putIfAbsent(Registration.OPT_DISCOUNT, discountSeed);
        opts.putIfAbsent(Registration.OPT_REGISTRATION_TYPE, Registration.RegistrationType.FULL);
        final int age = getAge(person);
        if ((age > AGE_FREE_MAX) && (age <= AGE_CHILD_MAX)) {
            opts.put(ROSEN_CENTRE_OPT_KEY, "false");
        }
        return reg;
    }

    /** Base (undiscounted) price by age + Rosen Centre selection. */
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
                // Adult who hasn't picked yet — surface $0 so the UI prompts them to choose.
                result = PRICE_FREE;
            }
        }
        return result;
    }

    /**
     * Named-discount lookup. Only applies to adults (age &gt; 10); children already pay a
     * discounted child price and 3-and-under is free. Future discounts (SCHOLARSHIP, SPEAKER,
     * PRIEST_OR_RELIGIOUS) plug in here. STANDARD and unknown tags return $0 off.
     */
    private BigDecimal computeNamedDiscount(final String discount, final Person person) {
        BigDecimal result = BigDecimal.ZERO;
        final int age = getAge(person);
        if (age > AGE_CHILD_MAX) {
            if (Registration.Discount.EARLY_BIRD.equals(discount)) {
                result = DISCOUNT_EARLY_BIRD;
            }
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
