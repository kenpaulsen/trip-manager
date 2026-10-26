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
        /** Why the last save was refused, shown inside the dialog. */
        private String problem;
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

    /** A typed line. {@code first}/{@code last} are within its group, for the move buttons. */
    @Data
    @NoArgsConstructor
    public static final class LineRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String name;
        private String note;
        private String payee;
        private String amount;
        private boolean perNight;
        /** The per-person total of an INDIVIDUAL line: the amount, or "$10.00 × 10 nights = $100.00". */
        private String total;
        private boolean first;
        private boolean last;
    }

    /** One category of SHARED lines with its subtotal. */
    @Data
    @NoArgsConstructor
    public static final class CategoryGroup implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String category;
        private String label;
        private List<LineRow> rows = new ArrayList<>();
        private String subtotal;
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
        private List<CategoryGroup> groups = new ArrayList<>();
        private String sharedTotal;
        private List<LineRow> individualRows = new ArrayList<>();
        private String individualExtras;
        private String estimatedPerPerson;
        private List<LodgingRow> lodgingRows = new ArrayList<>();
        private String lodgingRevenue;
        private String lodgingCost;
        private String lodgingMargin;
        private boolean marginNegative;
        private String perPersonBudget;
        /** The typed per-person budget is at least the estimated per-person cost. */
        private boolean budgetAboveEstimate;
        private String toBill;
        private String budgetLeftOver;
        private boolean leftOverNegative;
        private String overall;
        private boolean overallNegative;
        private List<PayeeRow> payees = new ArrayList<>();
        private String grandTotal;
        /** The roster's head count and the trip dates' nights, beside the typed inputs. */
        private int tripPeople;
        private int tripNightsFromDates;
        private boolean peopleDiffers;
        private boolean nightsDiffers;
        private boolean hasLodging;
        private List<String> warnings = new ArrayList<>();
    }
}
