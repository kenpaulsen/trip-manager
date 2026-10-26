package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * One expense on a {@link TripExpenseReport}: what was spent, when, in ONE currency, optionally matched to a
 * line of the trip's budget so the budget table can show what remains. A payment made in two currencies is two
 * lines. The amount is hundredths of the line's own currency ({@code amountMinor}); its dollar figure is
 * derived from the report's current rate whenever it is read ({@code pay/ExpenseMath}), never stored, so
 * fine-tuning a rate re-prices every line that uses it.
 *
 * <p>The constructor normalizes: a fresh id when none is given, Other when no category, USD when no currency,
 * a non-negative amount, blank text as null, never a null receipt list, and Not paid when no paid status. A
 * null date reads "Various".
 */
@Data
public final class ExpenseLine implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    public static final String USD = "USD";

    private String id;
    private LocalDate date;
    private ExpenseCategory category;
    private String description;
    private String currency;
    private long amountMinor;
    /** The id of a budget line or budget lodging line this expense counts against; null for none. */
    private String budgetLineId;
    private String note;
    /** The {@link MediaItem} ids of this line's receipts, in upload order. */
    private List<String> receiptIds;
    /** Whether it was settled outside the report (the organization paid it, or it was reimbursed already). */
    private ExpensePaidStatus paidStatus;

    @Builder
    @JsonCreator
    public ExpenseLine(
            @JsonProperty("id") final String id,
            @JsonProperty("date") final LocalDate date,
            @JsonProperty("category") final ExpenseCategory category,
            @JsonProperty("description") final String description,
            @JsonProperty("currency") final String currency,
            @JsonProperty("amountMinor") final long amountMinor,
            @JsonProperty("budgetLineId") final String budgetLineId,
            @JsonProperty("note") final String note,
            @JsonProperty("receiptIds") final List<String> receiptIds,
            @JsonProperty("paidStatus") final ExpensePaidStatus paidStatus) {
        this.id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
        this.date = date;
        this.category = (category == null) ? ExpenseCategory.GENERAL : category;
        this.description = BudgetLine.trimToNull(description);
        final String code = CurrencyRate.normalizeCode(currency);
        this.currency = (code == null) ? USD : code;
        this.amountMinor = Math.max(0L, amountMinor);
        this.budgetLineId = BudgetLine.trimToNull(budgetLineId);
        this.note = BudgetLine.trimToNull(note);
        this.receiptIds = (receiptIds == null) ? new ArrayList<>() : new ArrayList<>(receiptIds);
        this.paidStatus = (paidStatus == null) ? ExpensePaidStatus.NOT_PAID : paidStatus;
    }

    public ExpenseLine() {
        this(null, null, null, null, null, 0L, null, null, null, null);
    }

    /** Whether the amount is already in dollars and needs no rate. */
    @JsonIgnore
    public boolean isUsd() {
        return USD.equals(currency);
    }

    /** Whether the submitter is still out of pocket for it: not paid by the organization, nor reimbursed. */
    @JsonIgnore
    public boolean isOutOfPocket() {
        return !paidStatus.isSettled();
    }
}
