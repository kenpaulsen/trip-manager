package org.paulsens.trip.action;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The scalar rows of the hotel page's Trips tab ({@link HotelCommands#tripRows}): every trip with stays at
 * this hotel, its lodging options here, and its stays with their GUESTS' names. Same rules as
 * {@link LodgingViews}: no domain objects, no-arg constructors, pinned {@code serialVersionUID}s.
 *
 * <p>Naming guests to a hotel operator is the owner's decision of 2026-10-25 and the documented extension of
 * the "accommodations are global" tenancy exception: the operator of a hotel sees the trip titles, dates and
 * guest names of every stay at THEIR hotel, across organizations. What they never see is a price, a bill or a
 * price-side concession, which is why no row here carries one: the money here is the hotel's own COST side
 * ({@code costSummary}, {@code costConcession}), and an unstated cost reads "not set", never the price.
 */
public final class HotelViews {

    private HotelViews() {
    }

    /** One trip staying at the hotel, newest first on the tab. */
    @Data
    @NoArgsConstructor
    public static final class HotelTripRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String tripId;
        private String title;
        private String orgName;
        /** "Sep 21 - Oct 1, 2026". */
        private String dates;
        private LocalDate start;
        private LocalDate end;
        /** The trip's Assignments board opened on this hotel. */
        private String boardUrl;
        /** The trip's Room Invoice (the operator sees their hotel's section only). */
        private String invoiceUrl;
        /** Whether every active-stay option of the trip at THIS hotel has a cost, so that invoice renders. */
        private boolean invoiceReady;
        /**
         * Whether the caller is one of the trip's OWN lodging admins (internal). Only they see the trip's lodging
         * options here and their "Set cost" (owner decision 2026-10-26: an operator states the hotel's cost
         * through its rate plans, never on a trip's option); {@link #options} stays empty for anyone else.
         */
        private boolean internal;
        private List<HotelOptionRow> options = new ArrayList<>();
        private List<HotelStayRow> stays = new ArrayList<>();
    }

    /** One of the trip's lodging options at this hotel. No price: the operator's side is the cost. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class HotelOptionRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String offerId;
        private String name;
        /** The room types the option sells, names joined with ", ". */
        private String roomTypes;
        private String dates;
        /** The hotel's stated nightly cost ("$50.00/night per room"), or "not set". Never the price. */
        private String costSummary;
        /** The lines under the cost: nightly extras summed per basis, then each one-time extra. */
        private List<String> costExtras = new ArrayList<>();
    }

    /** One active stay at this hotel: who, when, which room. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class HotelStayRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String reservationId;
        /** The occupants' display names joined with ", ". */
        private String guests;
        private String dates;
        private int nights;
        /** "Room 105", or "not assigned". */
        private String roomLabel;
        /** The hotel's concession on this stay ("10% discount"), null when none. */
        private String costConcession;
        /** The lodging option the stay is on, by name. */
        private String offerName;
        /** What the hotel is paid for this stay's room nights, after its discount ("$300.00"), or "not set". */
        private String roomPrice;
        /** The same before the discount, shown struck through; null when the stay has no hotel discount. */
        private String roomPriceBefore;
    }

    /** One entry of the Trips tab's trip picker: the trip by id, labelled "Spring trip -- Dec 13 - Dec 25, 2026". */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class HotelTripChoice implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String tripId;
        private String label;
    }

    /**
     * The Trips tab as one request reads it: every trip at the hotel as a picker entry, newest first, and the
     * ONE trip shown ({@code selectedTripId}, the {@code ?trip=} asked for when it is one of them, else the
     * newest). {@code row} is null when the hotel has no trip at all.
     */
    @Data
    @NoArgsConstructor
    public static final class HotelTripsTab implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private List<HotelTripChoice> choices = new ArrayList<>();
        private String selectedTripId = "";
        private HotelTripRow row;

        /** The shown row as a list of zero or one, for the page's ui:repeat (its row commands need one). */
        public List<HotelTripRow> getRows() {
            return (row == null) ? new ArrayList<>() : new ArrayList<>(List.of(row));
        }
    }
}
