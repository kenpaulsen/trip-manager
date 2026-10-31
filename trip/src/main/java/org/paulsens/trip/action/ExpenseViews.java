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

    /** The report's own fields: who it is from and how to pay it (the cash advanced is the worksheet). */
    @Data
    @NoArgsConstructor
    public static final class HeaderForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String submittedBy;
        private String paymentInstructions;
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
        /** An {@code ExpensePaidStatus} name: Not paid, or settled outside the report. */
        private String paidStatus = "NOT_PAID";
        /** Why the last save was refused, shown inside the dialog. */
        private String problem;

        /** Whether the dialog shows the new-currency fields. */
        public boolean isAddingCurrency() {
            return NEW_CURRENCY.equals(currency);
        }
    }

    /**
     * The payment dialog: the lines it settles (ticked from the unpaid ones), the amount (signed, prefilled
     * from the ticked lines), how and when it was paid, and a note saying why the amount differs from the lines.
     */
    @Data
    @NoArgsConstructor
    public static final class PaymentForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private LocalDate date;
        private Double amount;
        private String method;
        private String note;
        private List<String> lineIds = new ArrayList<>();
        /** Why the last save was refused, shown inside the dialog. */
        private String problem;
    }

    /**
     * The cash-advance worksheet dialog, one entry of one {@code AdvanceType}: the type's "who" answer
     * ({@code party}, or the people picked for a participant entry), the amount as a positive magnitude in
     * {@code currency} (the type carries the sign; {@code NEW_CURRENCY} adds one as the expense dialog does),
     * an optional date and a note. {@code recordTransaction} asks for a Payment transaction for the ONE person
     * picked; {@code transactionLine} reads back the one already recorded.
     */
    @Data
    @NoArgsConstructor
    public static final class AdvanceForm implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        /** An {@code AdvanceType} name. */
        private String type = "CASH";
        private LocalDate date;
        private String currency = "USD";
        private Double amount;
        private String party;
        private List<String> personIds = new ArrayList<>();
        private boolean recordTransaction;
        private String note;
        private String newCode;
        private Double newRate;
        /** "A payment of $100.00 was recorded for Ken Paulsen on Oct 30, 2026."; null until one is. */
        private String transactionLine;
        /** The id of that transaction; null until one is. */
        private String transactionId;
        /** Why the last save was refused, shown inside the dialog. */
        private String problem;

        /** Whether the dialog shows the new-currency fields. */
        public boolean isAddingCurrency() {
            return NEW_CURRENCY.equals(currency);
        }

        /** Exactly one participant picked: the only case the report records a transaction for. */
        public boolean isOnePerson() {
            return personIds != null && personIds.size() == 1;
        }
    }

    // ================================================================== rows (requestScope)

    /** One line of the cash-advance worksheet, numbered as the page prints it. */
    @Data
    @NoArgsConstructor
    public static final class AdvanceRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private int number;
        private String typeLabel;
        /** "Sep 20, 2026", or blank for an entry without a date. */
        private String dateText;
        /** Who gave it, where it came from, who it went to, or the participants' names. */
        private String party;
        /** "500.00 EUR", or "$1,000.00" for a dollar entry. */
        private String amount;
        /** Signed: "-$300.00" for money returned. */
        private String usd;
        private boolean negative;
        private boolean rateMissing;
        private String note;
        /** The Payment transaction this entry recorded; null when none. */
        private String transactionLine;
    }

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

    /** One expense. {@code first}/{@code last} drive the move buttons; the paid fields drive its chip. */
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
        /** "org", "reimbursed" or "paid" for the chip's style; "not_paid" when there is no chip. */
        private String paidKey;
        /** "Org paid", "Reimbursed" or "Paid Oct 7"; null for an expense still to be paid. */
        private String paidChip;
        /** The payment that settles it; null while none does. */
        private String paymentId;
        private boolean first;
        private boolean last;
    }

    /** One recorded payment. */
    @Data
    @NoArgsConstructor
    public static final class PaymentRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String dateText;
        /** Signed: "-$500.00" is the submitter paying the organization back. */
        private String amount;
        private boolean negative;
        private String method;
        private String note;
        /** "Taxi, Stamps", or "No specific expenses". */
        private String lines;
        private int lineCount;
        /** "Recorded by Ken Paulsen on Oct 7, 2026". */
        private String recordedLine;
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
        /** "draft", "submitted", "partly_paid" or "paid", for the chip's style. */
        private String statusKey;
        private String tripTitle;
        private String tripDates;
        private String orgName;
        private String submittedBy;
        /** "Submitted by Ken Paulsen on Oct 7, 2026"; null while a draft. */
        private String submittedLine;
        private boolean submitted;
        /** The cash advanced: the worksheet's signed total. */
        private String cashAdvance;
        private boolean advanceNegative;
        private List<AdvanceRow> advanceRows = new ArrayList<>();
        private int advanceCount;
        private String paymentInstructions;
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
        /** Whether any line was settled outside the report, so the totals show the split below. */
        private boolean hasSettled;
        private String paidByOrg;
        private String reimbursed;
        /** What the submitter is not yet reimbursed for (out of pocket): the total less the two above. */
        private String outOfPocket;
        /** "Due to CFPW" or "Due to the submitter". */
        private String dueLabel;
        /** The amount due, unsigned. */
        private String due;
        private boolean dueToSubmitter;
        private List<PaymentRow> paymentRows = new ArrayList<>();
        /** The payments added up, signed. */
        private String paymentsTotal;
        /** "Still due to CFPW", "Still due to the submitter", or "Settled". */
        private String stillDueLabel;
        /** What is left after the payments, unsigned. */
        private String stillDue;
        private boolean stillDueToSubmitter;
        private boolean settled;
        /** Whether this caller may record payments (site admin or the trip's finance admins). */
        private boolean canRecordPayments;
        /** How many expenses are still to be paid: out of pocket and in no payment. */
        private int unpaidCount;
        private int linesWithoutReceipt;
        /** The organization's treasurer address the report can be emailed to; null when none is set. */
        private String treasurerEmail;
        /** "Emailed to treasurer@example.com on Oct 7, 2026"; null until it has been. */
        private String emailedLine;
        private List<String> warnings = new ArrayList<>();
    }
}
