package org.paulsens.trip.action;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The scalar shapes the expense report page binds to, by the {@link BudgetViews} rules: edit FORMS a dialog's
 * inputs hold in viewScope, and ROWS rendered per request. Money in a form is {@code Double} (dollars, or the
 * line's own currency); in a row it is already formatted text, with a {@code *Negative} flag wherever the page
 * colors a sign. Every save re-reads the report fresh and applies the form; nothing here is a domain object.
 *
 * <p>All mutable POJOs with a no-arg constructor (Kryo runs none), every one with a pinned
 * {@code serialVersionUID}; {@code ModelSerializationTest} round-trips each nested class.
 */
public final class ExpenseViews {
    /** The currency menu's "Add a currency..." entry: the line dialog then asks for the code and the rate. */
    public static final String NEW_CURRENCY = "__new";

    private ExpenseViews() {
    }

    // ================================================================== forms (viewScope)

    /** The report's own fields: who it is from, the cash advance, how to pay it, and when it was paid. */
    @Data
    @NoArgsConstructor
    public static final class HeaderForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String submittedBy;
        private Double cashAdvance;
        private String cashAdvanceDetails;
        private String paymentInstructions;
        private LocalDate paidOn;
        private String paidHow;
        /** Why the last save was refused. */
        private String problem;
    }

    /** The currency dialog: a code and its rate, foreign units per ONE dollar ("1 USD = 0.83 EUR"). */
    @Data
    @NoArgsConstructor
    public static final class CurrencyForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String code;
        private Double rate;
        private String note;
        /** True when editing a currency the report already has: the code is then fixed. */
        private boolean existing;
        /** Why the last save was refused, shown inside the dialog. */
        private String problem;
    }

    /**
     * The expense dialog. {@code currency} is a code, or {@link #NEW_CURRENCY} with {@code newCode} and
     * {@code newRate} filled in, in which case the save adds that currency and the line in one put.
     */
    @Data
    @NoArgsConstructor
    public static final class ExpenseForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private LocalDate date;
        private String category = "GENERAL";
        private String description;
        private String currency = "USD";
        private Double amount;
        private String budgetLineId;
        private String note;
        private String newCode;
        private Double newRate;
        /** Why the last save was refused, shown inside the dialog. */
        private String problem;

        /** Whether the dialog shows the new-currency fields. */
        public boolean isAddingCurrency() {
            return NEW_CURRENCY.equals(currency);
        }
    }

    // ================================================================== rows (requestScope)

    /** One currency the report uses: its rate both ways, how many lines use it, and their totals. */
    @Data
    @NoArgsConstructor
    public static final class CurrencyRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String code;
        /** "1 USD = 0.83 EUR". */
        private String rateText;
        /** "1 EUR = $1.2048". */
        private String inverseText;
        private String note;
        private int lines;
        /** "340.00 EUR". */
        private String foreignTotal;
        private String usdTotal;
        private boolean rateMissing;
    }

    /**
     * One row of the budget-versus-actual table: a budget line (shared, per person, or lodging) with what it
     * planned, what the matched expenses spent and what remains; or the "Not in budget" row ({@code notInBudget},
     * spent only).
     */
    @Data
    @NoArgsConstructor
    public static final class BudgetRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        /** "Shared", "Per person" or "Lodging". */
        private String section;
        private String label;
        private String category;
        private String budget;
        private String spent;
        private String remaining;
        private boolean remainingNegative;
        private boolean notInBudget;
    }

    /** One expense. {@code first}/{@code last} drive the move buttons. */
    @Data
    @NoArgsConstructor
    public static final class ExpenseRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        /** "Sep 20, 2026", or "Various" for a line without a date. */
        private String dateText;
        private String category;
        private String description;
        /** "23.00 EUR", or "$255.83" for a dollar line. */
        private String amountText;
        private String usd;
        private boolean foreign;
        private boolean rateMissing;
        /** The matched budget line's name; null when unmatched. */
        private String budgetLabel;
        private int receiptCount;
        private String note;
        private boolean first;
        private boolean last;
    }

    /** One receipt: what to call it and the two app URLs that serve it (never the media CDN). */
    @Data
    @NoArgsConstructor
    public static final class ReceiptRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String mediaId;
        private String title;
        private String url;
        /** A small image to show; null for a PDF, which the page shows as a chip. */
        private String thumbUrl;
        private boolean pdf;
        private String size;
    }

    /** Everything the page renders. */
    @Data
    @NoArgsConstructor
    public static final class ExpensePage implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private boolean exists;
        private long version;
        private String status;
        /** "draft", "submitted" or "paid", for the chip's style. */
        private String statusKey;
        private String tripTitle;
        private String tripDates;
        private String orgName;
        private String submittedBy;
        /** "Submitted by Ken Paulsen on Oct 7, 2026"; null while a draft. */
        private String submittedLine;
        private boolean submitted;
        private String cashAdvance;
        private String cashAdvanceDetails;
        private String paymentInstructions;
        private String paidOnText;
        private String paidHow;
        private List<CurrencyRow> currencyRows = new ArrayList<>();
        private boolean hasBudget;
        private List<BudgetRow> budgetRows = new ArrayList<>();
        private String budgetTotal;
        private String spentTotal;
        private String remainingTotal;
        private boolean remainingNegative;
        private List<ExpenseRow> lineRows = new ArrayList<>();
        private List<ReceiptRow> reportReceipts = new ArrayList<>();
        private String total;
        /** "Due to CFPW" or "Due to the submitter". */
        private String dueLabel;
        /** The amount due, unsigned. */
        private String due;
        private boolean dueToSubmitter;
        private int linesWithoutReceipt;
        private List<String> warnings = new ArrayList<>();
    }
}
