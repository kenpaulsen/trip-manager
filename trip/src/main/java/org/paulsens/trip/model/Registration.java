package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Value;
import lombok.With;

@Value
public class Registration implements Serializable {
    /**
     * Reserved keys for system-managed registration metadata, stored alongside the per-trip
     * {@link RegistrationOption} values in {@link #options}. Underscore-prefixed so they cannot
     * collide with per-trip option ids (which are numeric strings like {@code "1"}, {@code "2"}).
     *
     * <p>Values are free-form strings — the conventional vocabulary is exposed via the nested
     * holder classes ({@link RegistrantType}, {@link Discount}, {@link RegistrationType}), but
     * individual trips may use their own string values where it makes sense.
     */
    public static final String OPT_REGISTRANT_TYPE   = "_registrantType";
    public static final String OPT_DISCOUNT          = "_discount";
    public static final String OPT_REGISTRATION_TYPE = "_regType";
    /** Id of the chosen {@link AdmissionOption} (see {@link Trip#getAdmissionOptions()}). */
    public static final String OPT_ADMISSION         = "_admission";
    /** Id of the applied {@link DiscountCode} (see {@link Trip#getDiscountCodes()}); at most one. */
    public static final String OPT_DISCOUNT_CODE     = "_discountCode";

    String tripId;                  // The trip id (partition key)
    Person.Id userId;               // The user id (sort key)
    LocalDateTime created;          // When they first registered
    @With
    Status status;                  // Registration Status
    Map<String, String> options;    // Extra information (per-trip options + reserved metadata keys)

    @JsonCreator
    public Registration(
            @JsonProperty("tripId") final String tripId,
            @JsonProperty("userId") final Person.Id userId,
            @JsonProperty("created") final LocalDateTime created,
            @JsonProperty("status") final Status status,
            @JsonProperty("options") final Map<String, String> options) {
        this.tripId = tripId;
        this.userId = userId;
        this.created = (created == null) ? LocalDateTime.now() : created;
        this.status = (status == null) ? Status.NOT_REGISTERED : status;
        this.options = (options == null) ? new HashMap<>() : options;
    }

    public Registration(final String tripId, final Person.Id userId) {
        this(tripId, userId, null, null, null);
    }

    public Registration withStatusString(final String description) {
        return withStatus(Status.fromDescription(description));
    }

    /** @return value stored under {@link #OPT_REGISTRANT_TYPE}, or {@code null} if unset. */
    @JsonIgnore
    public String getRegistrantType() {
        return options.get(OPT_REGISTRANT_TYPE);
    }

    /** @return value stored under {@link #OPT_DISCOUNT}, or {@code null} if unset. */
    @JsonIgnore
    public String getDiscount() {
        return options.get(OPT_DISCOUNT);
    }

    /** @return value stored under {@link #OPT_REGISTRATION_TYPE}, or {@code null} if unset. */
    @JsonIgnore
    public String getRegistrationType() {
        return options.get(OPT_REGISTRATION_TYPE);
    }

    /** @return the chosen {@link AdmissionOption} id ({@link #OPT_ADMISSION}), or {@code null}. */
    @JsonIgnore
    public String getAdmissionId() {
        return options.get(OPT_ADMISSION);
    }

    /** @return the applied {@link DiscountCode} id ({@link #OPT_DISCOUNT_CODE}), or {@code null}. */
    @JsonIgnore
    public String getDiscountCodeId() {
        return options.get(OPT_DISCOUNT_CODE);
    }

    /** Standard values for {@link #OPT_REGISTRANT_TYPE}. */
    public static final class RegistrantType {
        public static final String THREE_AND_UNDER = "3_and_under";
        public static final String CHILD           = "child";
        public static final String ADULT           = "adult";
        private RegistrantType() {
        }
    }

    /** Standard values for {@link #OPT_DISCOUNT}. Numeric values like {@code "175.00"} or
     *  {@code "$175"} are interpreted as a total-price override (see RegistrationCommands). */
    public static final class Discount {
        public static final String EARLY_BIRD          = "early-bird";
        public static final String SCHOLARSHIP         = "scholarship";
        public static final String SPEAKER             = "speaker";
        public static final String PRIEST_OR_RELIGIOUS = "priest-or-religious";
        public static final String STANDARD            = "standard";
        private Discount() {
        }
    }

    /** Standard values for {@link #OPT_REGISTRATION_TYPE}. */
    public static final class RegistrationType {
        public static final String FULL          = "full";
        public static final String FRIDAY_ONLY   = "Friday-only";
        public static final String SATURDAY_ONLY = "Saturday-only";
        public static final String SUNDAY_ONLY   = "Sunday-only";
        private RegistrationType() {
        }
    }

    public enum Status {
        NOT_REGISTERED("Not Registered"),
        CONFIRMED("Confirmed"),
        PENDING("Pending");

        @Getter @JsonValue
        final String description;

        Status(final String description) {
            this.description = description;
        }

        @JsonCreator
        public static Status fromDescription(final String description) {
            return switch (description) {
                case "Not Registered" -> NOT_REGISTERED;
                case "Confirmed" -> CONFIRMED;
                case "Pending" -> PENDING;
                default -> null;
            };
        }

        @Override
        public String toString() {
            return description;
        }
    }
}