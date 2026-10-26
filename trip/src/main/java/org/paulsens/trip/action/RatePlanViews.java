package org.paulsens.trip.action;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.paulsens.trip.action.LodgingViews.CostExtraForm;
import org.paulsens.trip.action.LodgingViews.HasCostExtras;

/**
 * The scalar shapes of the hotel page's Rate plans tab and the Offers tab's "Add from hotel rate plan" menu
 * ({@link RatePlanCommands}). Same rules as {@link LodgingViews}: no domain objects, no-arg constructors,
 * pinned {@code serialVersionUID}s, and the money as dollars the dialog's number widgets hold.
 *
 * <p>A rate plan is the HOTEL's side only (owner decision 2026-10-26): no shape here carries a participant
 * price, so a Hotel Operator's table and dialog can be the same as an internal admin's. The participant price
 * of an option taken from a plan is the trip's, entered on the attach ({@link AttachForm}) or on the option
 * afterwards.
 */
public final class RatePlanViews {

    private RatePlanViews() {
    }

    /** One rate plan on the hotel page's table. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class RatePlanRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String name;
        /** The room types the plan covers, names joined with ", ". */
        private String roomTypes;
        /** "Sep 1 - Oct 31, 2026", "from Sep 1, 2026", "until Oct 31, 2026" or "any dates". */
        private String dates;
        /** The hotel's nightly cost ("$50.00/night per room"), or "not set"; its extras are {@link #costExtras}. */
        private String costSummary;
        /** The linked trips the caller may name, joined with ", "; unnamed ones counted ("2 other trips"). */
        private String linkedTrips;
        private int linkedCount;
        private boolean retired;
        private String notes;
        /** The lines under the cost: nightly extras summed per basis, then each one-time extra. */
        private List<String> costExtras = new ArrayList<>();
    }

    /**
     * The rate plan dialog's inputs. {@code planId} null = a new plan. One money block, the HOTEL COST
     * ({@code costKnown}, {@code costPricingModel}, {@code costNightlyPrice}, {@code costSupplement},
     * {@code costExtras}), which every editor of the hotel may state; {@code costKnown} starts ON, since stating
     * the cost is what the dialog is for.
     */
    @Data
    @NoArgsConstructor
    public static final class RatePlanForm implements Serializable, HasCostExtras {
        @Serial
        private static final long serialVersionUID = 1L;
        private String accId;
        private String planId;
        private String name;
        private List<String> roomTypeIds = new ArrayList<>();
        /** The first and last date the plan covers; either may be null (open on that side). */
        private LocalDate validFrom;
        private LocalDate validUntil;
        /** Off = the hotel has not stated a cost yet. On by default: stating the cost is what the dialog is for. */
        private boolean costKnown = true;
        private String costPricingModel = "PER_ROOM";
        private Double costNightlyPrice;
        private Double costSupplement;
        private List<CostExtraForm> costExtras = new ArrayList<>();
        private String notes;
        private boolean retired;
        /** Why the last save was refused, or what the fan-out could not finish; shown IN the dialog. */
        private String problem;

        public void setRoomTypeIds(final List<String> ids) {
            roomTypeIds = (ids == null) ? new ArrayList<>() : new ArrayList<>(ids);
        }

        public void setCostExtras(final List<CostExtraForm> extras) {
            costExtras = (extras == null) ? new ArrayList<>() : new ArrayList<>(extras);
        }
    }

    /**
     * One entry of the "Add from hotel rate plan" menu: a plan of a live hotel that covers the trip's dates.
     * {@link #getValue()} is what the menu item carries and what {@code RatePlanCommands.attach(tripId, choice)}
     * and {@link AttachForm#getChoice()} take back, so the page never splits ids itself. The same plan may be
     * added to a trip several times (two options at different participant prices), so a plan already on the
     * trip stays in the menu.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class PlanChoice implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        /** What separates the hotel id from the plan id in {@link #getValue()}. */
        public static final String SEPARATOR = "|";
        private String planId;
        private String accId;
        /** "Pansion: Hotel rate 2027 (Sep 1 - Oct 31, 2027)". */
        private String label;

        /** {@code accId|planId}: one menu value naming the plan's partition and row. */
        public String getValue() {
            return accId + SEPARATOR + planId;
        }
    }

    /**
     * The "Add from hotel rate plan" dialog's inputs ({@code RatePlanCommands.attachFormFor(tripId)} /
     * {@code attach(form)}): the trip, WHICH plan ({@code choice}, a {@link PlanChoice#getValue()}) and the new
     * option's PARTICIPANT PRICE ({@code priceKnown}, {@code pricingModel}, {@code nightlyPrice},
     * {@code singleSupplement}, dollars). {@code priceKnown} starts ON for the dialog; unticked, the option is
     * created "needs participant pricing" and takes no stays until the Offers tab prices it. The cost side is
     * never here -- it is the plan's, copied.
     */
    @Data
    @NoArgsConstructor
    public static final class AttachForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String tripId;
        /** {@code accId|planId}, the menu value of the chosen plan. */
        private String choice;
        private boolean priceKnown = true;
        private String pricingModel = "PER_ROOM";
        private Double nightlyPrice;
        private Double singleSupplement;
        /** Why the last attach was refused; shown IN the dialog. */
        private String problem;
    }
}
