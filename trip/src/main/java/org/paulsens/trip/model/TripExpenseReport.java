package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * A trip's expense report: what was actually spent, against the cash advanced for it. ONE row per trip in
 * {@code trip_expense_reports} (PK tripId), shared by everyone who may edit the trip's finances, its
 * currencies and lines inline and saved by one versioned conditional put ({@code ExpenseReportDAO}), the
 * budget's shape and for the budget's reason: a lost race refuses the whole report instead of merging half of
 * it. See {@code docs/expense-report.md}.
 *
 * <p>Every dollar figure is derived when the page renders ({@code pay/ExpenseMath}); the row holds only what
 * people type, the receipt ids, and the submit stamp.
 */
@Data
public final class TripExpenseReport implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private String tripId;
    private String orgId;
    /** The name on the report (the sheet's "Name"), free text; pre-filled with whoever started it. */
    private String submittedBy;
    private Person.Id submittedById;
    /** When it was submitted; null while it is a draft. */
    private LocalDateTime submittedOn;
    private long cashAdvanceCents;
    private String cashAdvanceDetails;
    private String paymentInstructions;
    /** Who the report was last emailed to (the treasurer), and when; null until it is. */
    private String emailedTo;
    private LocalDateTime emailedOn;
    private List<CurrencyRate> currencies;
    private List<ExpenseLine> lines;
    /** What the treasurer paid against the report, in the order recorded. */
    private List<ExpensePayment> payments;
    /** Receipts that belong to no single line (a cash-advance slip, a statement). */
    private List<String> receiptIds;
    private Person.Id createdBy;
    private LocalDateTime created;
    private long version;

    @Builder
    @JsonCreator
    public TripExpenseReport(
            @JsonProperty("tripId") final String tripId,
            @JsonProperty("orgId") final String orgId,
            @JsonProperty("submittedBy") final String submittedBy,
            @JsonProperty("submittedById") final Person.Id submittedById,
            @JsonProperty("submittedOn") final LocalDateTime submittedOn,
            @JsonProperty("cashAdvanceCents") final long cashAdvanceCents,
            @JsonProperty("cashAdvanceDetails") final String cashAdvanceDetails,
            @JsonProperty("paymentInstructions") final String paymentInstructions,
            @JsonProperty("emailedTo") final String emailedTo,
            @JsonProperty("emailedOn") final LocalDateTime emailedOn,
            @JsonProperty("currencies") final List<CurrencyRate> currencies,
            @JsonProperty("lines") final List<ExpenseLine> lines,
            @JsonProperty("payments") final List<ExpensePayment> payments,
            @JsonProperty("receiptIds") final List<String> receiptIds,
            @JsonProperty("createdBy") final Person.Id createdBy,
            @JsonProperty("created") final LocalDateTime created,
            @JsonProperty("version") final long version) {
        this.tripId = tripId;
        this.orgId = orgId;
        this.submittedBy = BudgetLine.trimToNull(submittedBy);
        this.submittedById = submittedById;
        this.submittedOn = submittedOn;
        this.cashAdvanceCents = Math.max(0L, cashAdvanceCents);
        this.cashAdvanceDetails = BudgetLine.trimToNull(cashAdvanceDetails);
        this.paymentInstructions = BudgetLine.trimToNull(paymentInstructions);
        this.emailedTo = BudgetLine.trimToNull(emailedTo);
        this.emailedOn = emailedOn;
        this.currencies = (currencies == null) ? new ArrayList<>() : new ArrayList<>(currencies);
        this.lines = (lines == null) ? new ArrayList<>() : new ArrayList<>(lines);
        this.payments = (payments == null) ? new ArrayList<>() : new ArrayList<>(payments);
        this.receiptIds = (receiptIds == null) ? new ArrayList<>() : new ArrayList<>(receiptIds);
        this.createdBy = createdBy;
        this.created = created;
        this.version = version;
    }

    public TripExpenseReport() {
        this(null, null, null, null, null, 0L, null, null, null, null, null, null, null, null, null, null, 0L);
    }

    /** The line with this id; null when there is none. */
    public ExpenseLine line(final String id) {
        for (final ExpenseLine line : lines) {
            if (line.getId().equals(id)) {
                return line;
            }
        }
        return null;
    }

    /** The rate row for this currency code; null for USD or a code the report does not use. */
    public CurrencyRate currency(final String code) {
        for (final CurrencyRate rate : currencies) {
            if (rate.getCode() != null && rate.getCode().equals(code)) {
                return rate;
            }
        }
        return null;
    }

    /** The lines in this currency: what a delete of the currency would orphan. */
    public List<ExpenseLine> linesUsing(final String code) {
        final List<ExpenseLine> out = new ArrayList<>();
        for (final ExpenseLine line : lines) {
            if (line.getCurrency().equals(code)) {
                out.add(line);
            }
        }
        return out;
    }

    /** The line holding this receipt; null when it is a report-level receipt or unknown. */
    public ExpenseLine lineWithReceipt(final String mediaId) {
        for (final ExpenseLine line : lines) {
            if (line.getReceiptIds().contains(mediaId)) {
                return line;
            }
        }
        return null;
    }

    /** Whether this receipt is on the report, at report level or on any line. */
    public boolean holdsReceipt(final String mediaId) {
        return receiptIds.contains(mediaId) || lineWithReceipt(mediaId) != null;
    }

    /** Every receipt id the report holds, report level first, then line by line. */
    @JsonIgnore
    public List<String> allReceiptIds() {
        final List<String> out = new ArrayList<>(receiptIds);
        for (final ExpenseLine line : lines) {
            out.addAll(line.getReceiptIds());
        }
        return out;
    }

    /** The payment with this id; null when there is none. */
    public ExpensePayment payment(final String id) {
        for (final ExpensePayment payment : payments) {
            if (payment.getId().equals(id)) {
                return payment;
            }
        }
        return null;
    }

    /** The payment that settles this line; null while none does. */
    public ExpensePayment paymentFor(final String lineId) {
        for (final ExpensePayment payment : payments) {
            if (payment.getLineIds().contains(lineId)) {
                return payment;
            }
        }
        return null;
    }

    /** Whether a recorded payment settles this line. */
    public boolean isPaid(final String lineId) {
        return paymentFor(lineId) != null;
    }

    /** The lines still to be paid: out of pocket and in no payment. */
    @JsonIgnore
    public List<ExpenseLine> unpaidLines() {
        final List<ExpenseLine> out = new ArrayList<>();
        for (final ExpenseLine line : lines) {
            if (line.isOutOfPocket() && !isPaid(line.getId())) {
                out.add(line);
            }
        }
        return out;
    }

    /**
     * Once a payment is recorded: Paid when no line is left unpaid, else Partly paid. Before that Submitted
     * once stamped, else Draft.
     */
    @JsonIgnore
    public ExpenseStatus getStatus() {
        if (!payments.isEmpty()) {
            return unpaidLines().isEmpty() ? ExpenseStatus.PAID : ExpenseStatus.PARTLY_PAID;
        }
        return (submittedOn == null) ? ExpenseStatus.DRAFT : ExpenseStatus.SUBMITTED;
    }
}
