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
        /**
         * Why the last save was refused, shown IN the dialog. A growl cannot serve a modal dialog: it renders
         * at the overlay's own z-index and below the dialog, so the overlay paints over it and the refusal is
         * invisible while the dialog stays open. {@link PlacementForm#problem} exists for the same reason.
         */
        private String problem;

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

    /**
     * The board's occupancy window: which nights the room counts are for. A range picker plus arrival and
     * departure times, like every other stay control. Typed, rather than raw view-map entries, so JSF
     * converts the picker's value to dates instead of guessing.
     */
    @Data
    @NoArgsConstructor
    public static final class StayWindowForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private List<LocalDate> range = new ArrayList<>();
        private LocalTime arrivalTime;
        private LocalTime departureTime;

        public void setRange(final List<LocalDate> dates) {
            range = (dates == null) ? new ArrayList<>() : new ArrayList<>(dates);
        }

        public LocalDateTime start() {
            return hasRange(range)
                    ? range.get(0).atTime(arrivalTime == null ? LocalTime.MIN : arrivalTime) : null;
        }

        public LocalDateTime end() {
            return hasRange(range) ? range.get(range.size() - 1)
                    .atTime(departureTime == null ? LocalTime.of(23, 59) : departureTime) : null;
        }
    }

    /**
     * The "place this person in this room" dialog: which lodging option pays for the stay, and the stay
     * itself. Always the CREATE path (a person who already holds the stay being placed has an option and
     * dates, so their click assigns straight away), where leaving the option implicit was the confusing part.
     * It is also how a SECOND stay is booked: {@code anotherStay} leaves the range blank rather than
     * defaulting it to the option's, because the dates that are already taken are the one thing the new stay
     * cannot have.
     */
    @Data
    @NoArgsConstructor
    public static final class PlacementForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String personId;
        private String personName;
        private String accommodationId;
        private String roomId;
        private String roomLabel;
        private String offerId;
        private List<LocalDate> range = new ArrayList<>();
        private LocalTime arrivalTime;
        private LocalTime departureTime;
        /** Set by "Place anyway" after the capacity warning. */
        private boolean force;
        /** Shown in the dialog when the room cannot take them on those dates. */
        private String problem;
        /** The reservation dialog's concession, offered at placement too (2026-09-11). */
        private boolean waiveSingleSupplement;
        /** True when this person already stays here: an extra stay, not their first. */
        private boolean anotherStay;
        /** "Sep 21 – Oct 1 in room 105", what they already hold, so the new dates are picked knowingly. */
        private String existingStays;

        public void setRange(final List<LocalDate> dates) {
            range = (dates == null) ? new ArrayList<>() : new ArrayList<>(dates);
        }

        public void setArrivalTime(final LocalTime time) {
            arrivalTime = time;
        }

        public void setDepartureTime(final LocalTime time) {
            departureTime = time;
        }

        /** The stay these three fields describe; null when no range has been picked. */
        public LocalDateTime start() {
            return hasRange(range)
                    ? range.get(0).atTime(arrivalTime == null ? OfferForm.DEFAULT_ARRIVAL : arrivalTime) : null;
        }

        public LocalDateTime end() {
            return hasRange(range) ? range.get(range.size() - 1)
                    .atTime(departureTime == null ? OfferForm.DEFAULT_DEPARTURE : departureTime) : null;
        }

        /**
         * Fills the stay from an option's default, for the first render and whenever the option changes. An
         * EXTRA stay keeps only the times: the option's default dates are the ones the person is already
         * here for, so offering them back would only ever be refused as an overlap.
         */
        public void applyDefaults(final LocalDateTime defaultStart, final LocalDateTime defaultEnd) {
            range = anotherStay ? new ArrayList<>() : rangeOf(defaultStart, defaultEnd);
            arrivalTime = (defaultStart == null) ? OfferForm.DEFAULT_ARRIVAL : defaultStart.toLocalTime();
            departureTime = (defaultEnd == null) ? OfferForm.DEFAULT_DEPARTURE : defaultEnd.toLocalTime();
        }
    }

    /**
     * The "switch room mid-stay" dialog: one stay becomes two, sharing everything but the room and the night
     * they change over. Rooming, not pricing -- the option, the occupants and the total nights are untouched
     * -- which is why the hotel's own staff may do it.
     */
    @Data
    @NoArgsConstructor
    public static final class SplitForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String reservationId;
        private String accommodationId;
        /** Who is moving, and the stay they are moving within, for the dialog's header line. */
        private String names;
        private String roomLabel;
        private LocalDateTime start;
        private LocalDateTime end;
        /** The night they sleep in the NEW room first: strictly inside the stay. */
        private LocalDate date;
        /** The room from that night; blank leaves the second half waiting for a room. */
        private String roomId;
        /** Set by "Move anyway" after a capacity warning. */
        private boolean force;
        /** Shown in the dialog when the second half cannot go where it is asked to. */
        private String problem;

        /** The earliest changeover the picker offers: one night in, or null when the stay has no dates. */
        public LocalDate getMinDate() {
            return (start == null) ? null : start.toLocalDate().plusDays(1);
        }

        /** The latest changeover: the last night, so both halves keep at least one. */
        public LocalDate getMaxDate() {
            return (end == null) ? null : end.toLocalDate().minusDays(1);
        }
    }

    /** The block dialog's inputs: which rooms, which nights, and why. */
    @Data
    @NoArgsConstructor
    public static final class BlockForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private List<String> roomIds = new ArrayList<>();
        private List<LocalDate> range = new ArrayList<>();
        /** Free text: "another group", "boiler", "the owner's family". An enum would only keep growing. */
        private String reason;

        public void setRoomIds(final List<String> ids) {
            roomIds = (ids == null) ? new ArrayList<>() : new ArrayList<>(ids);
        }

        public void setRange(final List<LocalDate> dates) {
            range = (dates == null) ? new ArrayList<>() : new ArrayList<>(dates);
        }

        /** The first blocked night; null until a range is picked. */
        public LocalDate start() {
            return hasRange(range) ? range.get(0) : null;
        }

        /** The morning the room is free again; null until a range is picked. */
        public LocalDate end() {
            return hasRange(range) ? range.get(range.size() - 1) : null;
        }
    }

    /** One block on the Availability tab's table and in the board's room dialog. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class BlockRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String roomsLabel;
        private int roomCount;
        private LocalDate start;
        private LocalDate end;
        private int nights;
        private String reason;
        /** Already over: the table fades it rather than hiding it, so a mistake stays findable. */
        private boolean past;
    }

    /** One day of the hotel's availability calendar: how full it is, over every trip staying there. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class DayCell implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private LocalDate date;
        private int roomsOccupied;
        private int roomsTotal;
        private int roomsBlocked;
        private int people;
        /** People staying that night whose stay has no room yet -- work still to do, not a shortage. */
        private int unplaced;
        /** Rooms both blocked and slept in that night: somebody has to move. */
        private int conflicts;
        /** "cal-free", "cal-some", "cal-most" or "cal-full" -- the CSS load band. */
        private String load;

        /** "23/28 rooms · 33 people", the pill's whole text. Numbers, never a colour alone. */
        public String getSummary() {
            return roomsOccupied + "/" + roomsTotal + " rooms · " + people
                    + (people == 1 ? " person" : " people");
        }
    }

    /** A month of {@link DayCell}s, for the page and for the REST answer. */
    @Data
    @NoArgsConstructor
    public static final class HotelCalendar implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String accommodationId;
        private String accommodationName;
        /** ISO {@code yyyy-MM}: a scalar, because a view may hold nothing else. */
        private String month;
        private int roomsTotal;
        private List<DayCell> days = new ArrayList<>();
    }

    /** One stay in a room on a given day, as the HOTEL sees it: whose trip, how many, not who. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class DayStay implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        /** The trip's title, or "another organization's trip" when the caller may not see it. */
        private String tripLabel;
        private String orgName;
        private int people;
        private LocalDateTime start;
        private LocalDateTime end;
    }

    /** One room on a given day: what holds it, and what is booked into it. */
    @Data
    @NoArgsConstructor
    public static final class DayRoom implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String roomId;
        private String roomNumber;
        private String floor;
        private String typeName;
        private boolean blocked;
        private String blockReason;
        private List<DayStay> stays = new ArrayList<>();
    }

    /** One day of the hotel opened from the calendar: its cell, then a line per room. */
    @Data
    @NoArgsConstructor
    public static final class DayDetail implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private LocalDate date;
        private DayCell cell = new DayCell();
        private List<DayRoom> rooms = new ArrayList<>();
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
        /** The room they are in, on the board's Assigned list; null on the other lists. */
        private String roomLabel;
        /** Which of this person's stays at this hotel the card is, 1-based; 0 when they have none. */
        private int stayIndex;
        /** How many stays they hold here. Above 1 the card says which, so two cards never read alike. */
        private int stayCount;
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
        /** "Sep 21 – Oct 1": one chip per STAY, so the same person can appear twice on one room. */
        private String dates;
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
        /** The hotel holds this room on some night of the window (another group, maintenance). */
        private boolean blocked;
        private int blockedNights;
        /** "Sep 23 – Sep 25: another group", beside the glyph -- a colour never stands alone. */
        private String blockLabel;
        /** People here on another trip at this hotel; counted in the occupancy, never named. */
        private int otherTripPeople;
        private boolean mapped;
        private double x;
        private double y;
        private double w;
        private double h;
    }

    /**
     * One room opened from the board: what the room card shows, plus the people in it with their
     * registration answers. Clicking a room with nobody selected asks "who is in here?", which the chips on
     * the card and the tooltip on the map could not answer.
     */
    @Data
    @NoArgsConstructor
    public static final class RoomDetail implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String roomId;
        private String roomNumber;
        private String floor;
        private String typeName;
        private int minPeople;
        private int maxPeople;
        private int count;
        /** The same CSS state the board cell carries, so the dialog reads the same as the card. */
        private String state;
        private String notes;
        private String adminNotes;
        private List<PersonCard> occupants = new ArrayList<>();
        /** What the HOTEL has taken this room out for over the window; editable in place by its managers. */
        private List<BlockRow> blocks = new ArrayList<>();
        /** Other trips in this room over the window: counts and dates, never names (a tenancy boundary). */
        private List<DayStay> otherTrips = new ArrayList<>();
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
        /** Everyone already in a room at this hotel, by room then name: selecting one and clicking another
         *  room MOVES them (2026-09-11). */
        private List<PersonCard> assigned = new ArrayList<>();
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
        /**
         * Which of the person's stays on this event the row is, 1-based; 0 for a row with no reservation.
         * A leave-and-return is two rows, so the event's own notes must render on exactly one of them.
         */
        private int stayIndex;
        private int stayCount;
        /** True on a plain row and on the FIRST stay's row: what the event itself says belongs there once. */
        private boolean firstStay = true;

        /**
         * What follows "Room N" on the itinerary's stay line: {@code " (Double), 3 nights"} after a shown room,
         * {@code "Double, 3 nights"} when the number is withheld (the type is parenthetical only when it
         * qualifies a room), {@code "3 nights"} when there is neither.
         */
        public String getStayTail() {
            final boolean room = roomLabel != null && !roomLabel.isBlank();
            final boolean type = roomTypeName != null && !roomTypeName.isBlank();
            final String nightsText = nights + (nights == 1 ? " night" : " nights");
            if (room && type) {
                return " (" + roomTypeName + "), " + nightsText;
            }
            if (room) {
                return ", " + nightsText;
            }
            return type ? roomTypeName + ", " + nightsText : nightsText;
        }
    }
}
