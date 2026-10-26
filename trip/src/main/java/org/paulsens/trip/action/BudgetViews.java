package org.paulsens.trip.action;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The scalar shapes the budget page binds to (the {@link LodgingViews} rules): edit FORMS a dialog's inputs
 * hold in viewScope, and ROWS rendered per request. Money in a form is {@code Double} dollars; in a row it is
 * already formatted text, with a {@code *Negative} flag wherever the page colors a sign. Every save re-reads
 * the budget fresh and applies the form; nothing here is a domain object.
 *
 * <p>All mutable POJOs with a no-arg constructor (Kryo runs none), every one with a pinned
 * {@code serialVersionUID}; {@code ModelSerializationTest} round-trips each nested class.
 */
public final class BudgetViews {

    private BudgetViews() {
    }

    // ================================================================== forms (viewScope)

    /** The four typed inputs. */
    @Data
    @NoArgsConstructor
    public static final class InputsForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private int people;
        private int tripNights;
        private Double perPersonBudget;
        private Double toBill;
    }

    /** The line dialog: a SHARED line (with a category) or an INDIVIDUAL one (optionally per night). */
    @Data
    @NoArgsConstructor
    public static final class LineForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String kind = "SHARED";
        private String category = "GENERAL";
        private String name;
        private Double amount;
        private boolean perNight;
        private String note;
        private String payee;
        /** True when the amount is a percentage of another line ({@code percent} of {@code percentOf}). */
        private boolean percentMode;
        private Double percent;
        private String percentOf;
        /** Stored inverted (see {@link #isOnSite}): a form revived without the field reads as on-site. */
        private boolean offSite;
        /** Why the last save was refused, shown inside the dialog. */
        private String problem;

        /** The dialog's "On-site Payments" checkbox. */
        public boolean isOnSite() {
            return !offSite;
        }

        public void setOnSite(final boolean onSite) {
            this.offSite = !onSite;
        }
    }

    /** The lodging-line dialog. The two flags hide the fields the chosen option does not need. */
    @Data
    @NoArgsConstructor
    public static final class LodgingLineForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String offerId;
        private int nights;
        private int people;
        private int rooms;
        private int singles;
        private String payee;
        private String note;
        /** Why the last save was refused, shown inside the dialog. */
        private String problem;
        private boolean needsRooms;
        private boolean needsSingles;
        /** The hotel's name, the payee a blank payee falls back to (the field's placeholder). */
        private String defaultPayee;
        /** Stored inverted (see {@link #isOnSite}): a form revived without the field reads as on-site. */
        private boolean offSite;

        /** The dialog's "On-site Payments" checkbox. */
        public boolean isOnSite() {
            return !offSite;
        }

        public void setOnSite(final boolean onSite) {
            this.offSite = !onSite;
        }
    }

    // ================================================================== rows (requestScope)

    /** One of the trip's lodging options as the lodging dialog's menu offers it. */
    @Data
    @NoArgsConstructor
    public static final class OfferChoice implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String offerId;
        private String label;
        private boolean needsRooms;
        private boolean needsSingles;
        private boolean priceMissing;
        private boolean costMissing;
    }

    /** A line the percent menu offers, or a budget the clone menu offers: an id and what to call it. */
    @Data
    @NoArgsConstructor
    public static final class Choice implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String label;
    }

    /** A typed line. {@code first}/{@code last} are within its table, for the move buttons. */
    @Data
    @NoArgsConstructor
    public static final class LineRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String name;
        /** The category's label ("Transportation"); empty for a per-person line. */
        private String category;
        private String note;
        private String payee;
        private String amount;
        /** "10% of Guide" for a percent line; null for a typed amount. */
        private String percentLabel;
        private boolean onSite;
        private boolean perNight;
        /** The per-person total of an INDIVIDUAL line: the amount, or "$10.00 × 10 nights = $100.00". */
        private String total;
        private boolean first;
        private boolean last;
    }

    /** One line NOT paid on site, for the Other trip items table. */
    @Data
    @NoArgsConstructor
    public static final class OtherRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String name;
        private String payee;
        private String amount;
    }

    /** One lodging line with its live figures. */
    @Data
    @NoArgsConstructor
    public static final class LodgingRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String label;
        private String ratePlanName;
        private String quantities;
        private String priceLabel;
        private String costLabel;
        private String payee;
        private String note;
        private String revenue;
        private String hotelCost;
        private String margin;
        private boolean marginNegative;
        private boolean onSite;
        private String perPersonPrice;
        /** The single-room add-on, or "none" under a per-room price. */
        private String singleAddOn;
        private String totalPerPersonGround;
        private String toBillReference;
        /** The typed to-bill figure is above / below this line's reference. */
        private boolean billAbove;
        private boolean billBelow;
        private boolean priceMissing;
        private boolean costMissing;
        private boolean deletedOption;
    }

    /** One payee's total. */
    @Data
    @NoArgsConstructor
    public static final class PayeeRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String name;
        private int items;
        private String amount;
    }

    /** Everything the budget page renders, computed per request. */
    @Data
    @NoArgsConstructor
    public static final class BudgetPage implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        /** False until the first save: the page then shows the pre-filled defaults. */
        private boolean exists;
        private long version;
        private int people;
        private int tripNights;
        /** Every SHARED line, one table, each row naming its category. */
        private List<LineRow> sharedRows = new ArrayList<>();
        private String sharedTotal;
        private List<LineRow> individualRows = new ArrayList<>();
        private String individualExtras;
        private String estimatedPerPerson;
        private List<LodgingRow> lodgingRows = new ArrayList<>();
        private String lodgingRevenue;
        private String lodgingCost;
        private String lodgingMargin;
        private boolean marginNegative;
        private boolean marginPositive;
        private String perPersonBudget;
        /** The typed per-person budget is at least the estimated per-person cost. */
        private boolean budgetAboveEstimate;
        /** The typed basic ground budget (shared + lodging) per person. */
        private String toBill;
        /**
         * The basic ground budget as shown: the typed figure alone, or "$1,000.00 to $1,300.00" when a
         * single-room add-on or per-person extras can raise what one participant pays.
         */
        private String toBillRange;
        /** (shared costs + lodging hotel cost) / people: what the basic ground budget has to cover. */
        private String estimatedGroundPerPerson;
        private String budgetLeftOver;
        private boolean leftOverNegative;
        /** Signed, as the dashboard headline shows it. */
        private String overall;
        private boolean overallNegative;
        /** "Overall surplus" or "Overall deficit", with the unsigned amount beside it. */
        private String overallLabel;
        private String overallAmount;
        private List<PayeeRow> payees = new ArrayList<>();
        /** The payees' total: what is paid on site. */
        private String onSiteTotal;
        private List<OtherRow> otherRows = new ArrayList<>();
        private String otherTotal;
        /** On-site payments plus other trip items: the estimated cost of the whole trip. */
        private String tripTotal;
        /** The roster's head count and the trip dates' nights, beside the typed inputs. */
        private int tripPeople;
        private int tripNightsFromDates;
        private boolean peopleDiffers;
        private boolean nightsDiffers;
        private boolean hasLodging;
        private List<String> warnings = new ArrayList<>();
    }
}
