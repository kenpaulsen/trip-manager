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
 * <p>Who sees what is decided in the bean, not here: a {@link RatePlanRow} handed to a Hotel Operator carries
 * a null {@code priceSummary}, and a {@link RatePlanForm} an operator submits has its price fields IGNORED
 * on save ({@code RatePlanCommands.canEditPrice}), so the shapes can be shared by both readers.
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
        /** The hotel's cost ("$50.00/night per room, +1 extra"), or "not set". */
        private String costSummary;
        /**
         * The participant price label, "needs participant pricing" while unset -- or NULL when the caller may
         * not see prices (a Hotel Operator), which is how the page knows to leave the column out.
         */
        private String priceSummary;
        /** True while the plan has no participant price (the "Needs participant pricing" badge). */
        private boolean needsPricing;
        /** The linked trips the caller may name, joined with ", "; unnamed ones counted ("2 other trips"). */
        private String linkedTrips;
        private int linkedCount;
        private boolean retired;
        private String notes;
    }

    /**
     * The rate plan dialog's inputs. {@code planId} null = a new plan. Two money blocks: the HOTEL COST
     * ({@code costKnown}, {@code costPricingModel}, {@code costNightlyPrice}, {@code costSupplement},
     * {@code costExtras}) every editor of the hotel may state, and the PARTICIPANT PRICE ({@code priceKnown},
     * {@code pricingModel}, {@code nightlyPrice}, {@code singleSupplement}) only an internal admin may set --
     * the bean strips it from anyone else's save, so an operator's form cannot move a participant's price.
     * {@code priceKnown} starts OFF: a new plan needs participant pricing until an admin says otherwise.
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
        /** Off = needs participant pricing. Internal admins only; ignored on anyone else's save. */
        private boolean priceKnown;
        private String pricingModel = "PER_ROOM";
        private Double nightlyPrice;
        private Double singleSupplement;
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
     * One entry of the "Add from hotel rate plan" menu: a plan of a live hotel that covers the trip's dates
     * and is not yet on the trip. {@link #getValue()} is what the menu item carries and what
     * {@code RatePlanCommands.attach(tripId, choice)} takes back, so the page never splits ids itself.
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
}
