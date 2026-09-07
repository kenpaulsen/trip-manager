package org.paulsens.trip.action;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The scalar shapes the lodging pages bind to: edit FORMS (what a dialog's inputs hold in the view) and ROWS
 * (what a table renders per request). None of them is a domain object, on purpose: the page-state rules park
 * only ids and scalars in viewScope, and every save re-reads the real row fresh and applies the form.
 *
 * <p>All mutable no-invariant POJOs with a no-arg constructor: the session codec (Kryo) runs no constructor,
 * so anything a constructor established would come back zeroed. Every one pins its {@code serialVersionUID}
 * (the {@code ModelSerializationTest} ratchet sweeps this package).
 */
public final class LodgingViews {

    private LodgingViews() {
    }

    /** The accommodation dialog's inputs. Money never appears here; a hotel has no price. */
    @Data
    @NoArgsConstructor
    public static final class AccommodationForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String name;
        private String description;
        private String email;
        private String phone;
        private String website;
        private String street;
        private String street2;
        private String city;
        private String state;
        private String zip;
        private String country;
        private String contactEmail;
        private String contactFirst;
        private String contactLast;
        /** Set by the create dialog after the duplicate warning: "create it anyway". */
        private boolean force;
    }

    @Data
    @NoArgsConstructor
    public static final class RoomTypeForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String name;
        private String description;
        private int minPeople = 1;
        private int maxPeople = 2;
    }

    @Data
    @NoArgsConstructor
    public static final class RoomForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String roomTypeId;
        private String roomNumber;
        private String floor;
        private String notes;
        private String adminNotes;
    }

    /** The offer dialog's inputs. Dollars in the UI ({@code p:inputNumber}), converted once on save. */
    @Data
    @NoArgsConstructor
    public static final class OfferForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        public static final String NEW_EVENT = "NEW";
        /** The arrival and departure times a stay defaults to when the dialog names none. */
        public static final LocalTime DEFAULT_ARRIVAL = LocalTime.of(15, 0);
        public static final LocalTime DEFAULT_DEPARTURE = LocalTime.of(10, 0);
        private String id;
        private String name;
        private String accommodationId;
        private List<String> roomTypeIds = new ArrayList<>();
        /** An existing LODGING event id, or {@link #NEW_EVENT} to create one from the default stay. */
        private String tripEventId;
        private String newEventTitle;
        private String pricingModel = "PER_ROOM";
        private Double nightlyPrice;
        private boolean perNightPricing;
        /**
         * ISO date -> dollars as TEXT, only read when {@link #perNightPricing} is on. Text because the page
         * binds a map subscript, whose value type EL cannot see, and a number widget then hands back whatever
         * it likes; the bean parses and refuses garbage.
         */
        private Map<String, String> perNight = new LinkedHashMap<>();
        private Double singleSupplement;
        private int minNights = 1;
        private LocalDateTime validFrom;
        private LocalDateTime validUntil;
        private LocalDateTime defaultStart;
        private LocalDateTime defaultEnd;
        /**
         * The dialog's RANGE pickers and times: the option's date range and the default stay as [from, to]
         * dates, plus arrival and departure times (a range picker has no time of day). {@link #resolveDates}
         * folds them into the four date-times above, which the commands and the REST door read.
         */
        private List<LocalDate> validRange = new ArrayList<>();
        private List<LocalDate> defaultRange = new ArrayList<>();
        private LocalTime arrivalTime;
        private LocalTime departureTime;
        private String policyHtml;
        /** "FLAT" or "PERCENT". */
        private String cancelFeeKind = "FLAT";
        private Double cancelFeeAmount;
        private boolean disabled;

        /*
         * The two shapes stay in sync through their setters: the dialog writes the ranges and times (in
         * whatever order the components decode), the commands, the REST door and the tests write the
         * date-times, and either side reads either. No explicit "resolve" step, which the callers forgot.
         */
        public void setValidRange(final List<LocalDate> range) {
            validRange = (range == null) ? new ArrayList<>() : new ArrayList<>(range);
            if (hasRange(validRange)) {
                validFrom = validRange.get(0).atStartOfDay();
                validUntil = validRange.get(validRange.size() - 1).atTime(LocalTime.of(23, 59));
            }
        }

        public void setDefaultRange(final List<LocalDate> range) {
            defaultRange = (range == null) ? new ArrayList<>() : new ArrayList<>(range);
            syncDefaultsFromRange();
        }

        public void setArrivalTime(final LocalTime time) {
            arrivalTime = time;
            syncDefaultsFromRange();
        }

        public void setDepartureTime(final LocalTime time) {
            departureTime = time;
            syncDefaultsFromRange();
        }

        public void setValidFrom(final LocalDateTime when) {
            validFrom = when;
            validRange = rangeOf(validFrom, validUntil);
        }

        public void setValidUntil(final LocalDateTime when) {
            validUntil = when;
            validRange = rangeOf(validFrom, validUntil);
        }

        public void setDefaultStart(final LocalDateTime when) {
            defaultStart = when;
            defaultRange = rangeOf(defaultStart, defaultEnd);
            arrivalTime = (when == null) ? arrivalTime : when.toLocalTime();
        }

        public void setDefaultEnd(final LocalDateTime when) {
            defaultEnd = when;
            defaultRange = rangeOf(defaultStart, defaultEnd);
            departureTime = (when == null) ? departureTime : when.toLocalTime();
        }

        private void syncDefaultsFromRange() {
            if (hasRange(defaultRange)) {
                defaultStart = defaultRange.get(0).atTime(arrivalTime == null ? DEFAULT_ARRIVAL : arrivalTime);
                defaultEnd = defaultRange.get(defaultRange.size() - 1)
                        .atTime(departureTime == null ? DEFAULT_DEPARTURE : departureTime);
            }
        }

        /** Kept for callers that used to fold the pickers explicitly; the setters already did. */
        public void resolveDates() {
            syncDefaultsFromRange();
        }

        /** Kept for symmetry; the date-time setters already fill the pickers. */
        public void fillRanges() {
            if (arrivalTime == null) {
                arrivalTime = (defaultStart == null) ? DEFAULT_ARRIVAL : defaultStart.toLocalTime();
            }
            if (departureTime == null) {
                departureTime = (defaultEnd == null) ? DEFAULT_DEPARTURE : defaultEnd.toLocalTime();
            }
        }
    }

    static boolean hasRange(final List<LocalDate> range) {
        return range != null && !range.isEmpty() && range.get(0) != null && range.get(range.size() - 1) != null;
    }

    static List<LocalDate> rangeOf(final LocalDateTime from, final LocalDateTime to) {
        final List<LocalDate> range = new ArrayList<>();
        if (from != null && to != null) {
            range.add(from.toLocalDate());
            range.add(to.toLocalDate());
        }
        return range;
    }

    /** The reservation dialogs' inputs (create and edit share it; create may name several people). */
    @Data
    @NoArgsConstructor
    public static final class ReservationForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String offerId;
        private List<String> personIds = new ArrayList<>();
        private LocalDateTime start;
        private LocalDateTime end;
        /** The dialog's range picker and times (see {@link OfferForm#resolveDates}). */
        private List<LocalDate> range = new ArrayList<>();
        private LocalTime arrivalTime;
        private LocalTime departureTime;
        private String roomId;
        private String notes;
        private boolean waiveSingleSupplement;
        /** Create only: one reservation per person (false) or everyone in one shared reservation (true). */
        private boolean shareOneRoom;

        public void setRange(final List<LocalDate> dates) {
            range = (dates == null) ? new ArrayList<>() : new ArrayList<>(dates);
            syncFromRange();
        }

        public void setArrivalTime(final LocalTime time) {
            arrivalTime = time;
            syncFromRange();
        }

        public void setDepartureTime(final LocalTime time) {
            departureTime = time;
            syncFromRange();
        }

        public void setStart(final LocalDateTime when) {
            start = when;
            range = rangeOf(start, end);
            arrivalTime = (when == null) ? arrivalTime : when.toLocalTime();
        }

        public void setEnd(final LocalDateTime when) {
            end = when;
            range = rangeOf(start, end);
            departureTime = (when == null) ? departureTime : when.toLocalTime();
        }

        private void syncFromRange() {
            if (hasRange(range)) {
                start = range.get(0).atTime(arrivalTime == null ? OfferForm.DEFAULT_ARRIVAL : arrivalTime);
                end = range.get(range.size() - 1)
                        .atTime(departureTime == null ? OfferForm.DEFAULT_DEPARTURE : departureTime);
            }
        }

        public void resolveDates() {
            syncFromRange();
        }

        public void fillRanges() {
            if (arrivalTime == null) {
                arrivalTime = (start == null) ? OfferForm.DEFAULT_ARRIVAL : start.toLocalTime();
            }
            if (departureTime == null) {
                departureTime = (end == null) ? OfferForm.DEFAULT_DEPARTURE : end.toLocalTime();
            }
        }
    }

    /** One line of the accommodations list. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class AccommodationRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String name;
        private String place;
        private int roomCount;
        private int roomTypeCount;
        private int usedByOrgs;
        private boolean editable;
        private boolean retired;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class RoomTypeRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String name;
        private String description;
        private int minPeople;
        private int maxPeople;
        private int roomCount;
        private int photoCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class RoomRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String roomNumber;
        private String floor;
        private String roomTypeId;
        private String typeName;
        private int minPeople;
        private int maxPeople;
        private String notes;
        private String adminNotes;
        private boolean mapped;
        /** Percent geometry when mapped, else zeros. */
        private double x;
        private double y;
        private double w;
        private double h;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class OfferRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String name;
        private String accommodationName;
        private String roomTypeName;
        private String eventTitle;
        private String pricing;
        private String validity;
        private String defaultStay;
        private int reservationCount;
        private boolean enabled;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class ReservationRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String occupants;
        private String offerName;
        private String room;
        private LocalDateTime start;
        private LocalDateTime end;
        private int nights;
        private String status;
        private String billed;
        private boolean active;
        private boolean supplementWaived;
        private String notes;
    }

    /** One occupant on the rooming list and the reports; people with no reservation come last. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class RoomingRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String personId;
        private String name;
        private String cell;
        private String accommodation;
        private String floor;
        private String room;
        private String roomType;
        private LocalDateTime start;
        private LocalDateTime end;
        private String notes;
        private String answers;
        private boolean reserved;
    }

    /** A person waiting for a room (or for a reservation) on the assignment workspace. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class PersonCard implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String personId;
        private String reservationId;
        private String name;
        private String age;
        private String sex;
        private int partySize;
        private LocalDateTime start;
        private LocalDateTime end;
        /** label -> answer, the roommate request first. */
        private Map<String, String> answers = new LinkedHashMap<>();
        private String notes;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class OccupantChip implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String reservationId;
        private String personId;
        private String name;
    }

    /** One room on the assignment workspace, with its occupancy over the chosen window. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class RoomCell implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String roomId;
        private String roomNumber;
        private String floor;
        private String typeName;
        private int minPeople;
        private int maxPeople;
        private int count;
        /** "rs-empty", "rs-partial", "rs-full" or "rs-over" -- the CSS state. */
        private String state;
        private List<OccupantChip> occupants = new ArrayList<>();
        private String notes;
        private String adminNotes;
        private boolean mapped;
        private double x;
        private double y;
        private double w;
        private double h;
    }

    /** The whole assignment workspace for one offer and window: people needing a room, and the rooms. */
    @Data
    @NoArgsConstructor
    public static final class RoomBoard implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String accommodationId;
        private String accommodationName;
        private List<String> floors = new ArrayList<>();
        private List<RoomCell> rooms = new ArrayList<>();
        private List<PersonCard> unassigned = new ArrayList<>();
        private List<PersonCard> noReservation = new ArrayList<>();
        private String floorMapMediaId;
    }

    /** What the assignment click did: assigned, or held for the over-capacity confirmation. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class AssignOutcome implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private boolean assigned;
        private boolean overCapacity;
        private String message;
        private String personId;
        private String reservationId;
        private String roomId;
    }

    /** The cancel dialog's numbers, read from what was actually billed. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class CancelPreview implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String reservationId;
        private String occupants;
        private long billedCents;
        private long feeCents;
        private long creditCents;
        private String feeRule;
        private String billed;
        private String fee;
        private String credit;
    }

    /** The accommodation dialog's contact lookup: who owns that email, if anyone. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class ContactHit implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private boolean found;
        private String personId;
        private String name;
    }

    /** One itinerary row: the event's own dates plus the reservation's when the person has one. */
    @Data
    @NoArgsConstructor
    public static final class ItineraryRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String type;
        private String title;
        private String notes;
        private LocalDateTime start;
        private LocalDateTime end;
        private LocalDateTime effectiveStart;
        private LocalDateTime effectiveEnd;
        private int participantCount;
        /** {@code Person.Id}s, not strings: the itinerary's tooltip resolves them with {@code people.getPerson}. */
        private List<org.paulsens.trip.model.Person.Id> participantIds = new ArrayList<>();
        private String privNote;
        private boolean lodging;
        private boolean overridden;
        private String reservationId;
        private String accommodationName;
        private String roomTypeName;
        private String roomLabel;
        private String reservationNotes;
        private int nights;
    }
}
