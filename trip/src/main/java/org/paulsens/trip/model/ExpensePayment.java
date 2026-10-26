package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * A payment the treasurer recorded against a {@link TripExpenseReport}: how much, how, when, and which of the
 * report's lines it settles. The amount is free and SIGNED: positive is the organization paying the submitter,
 * negative the submitter paying the organization back, and it need not equal the lines it covers, because the
 * cash advance is netted against them (the note says why). A payment may cover no line at all, a pure
 * settlement of the balance.
 *
 * <p>The constructor normalizes: a fresh id when none is given, blank text as null, and never a null line list.
 */
@Data
public final class ExpensePayment implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private String id;
    private LocalDate date;
    private long amountCents;
    private String method;
    private String note;
    /** The ids of the {@link ExpenseLine}s this payment settles. */
    private List<String> lineIds;
    private Person.Id recordedById;
    private String recordedBy;
    private LocalDateTime recordedOn;

    @Builder
    @JsonCreator
    public ExpensePayment(
            @JsonProperty("id") final String id,
            @JsonProperty("date") final LocalDate date,
            @JsonProperty("amountCents") final long amountCents,
            @JsonProperty("method") final String method,
            @JsonProperty("note") final String note,
            @JsonProperty("lineIds") final List<String> lineIds,
            @JsonProperty("recordedById") final Person.Id recordedById,
            @JsonProperty("recordedBy") final String recordedBy,
            @JsonProperty("recordedOn") final LocalDateTime recordedOn) {
        this.id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
        this.date = date;
        this.amountCents = amountCents;
        this.method = BudgetLine.trimToNull(method);
        this.note = BudgetLine.trimToNull(note);
        this.lineIds = (lineIds == null) ? new ArrayList<>() : new ArrayList<>(lineIds);
        this.recordedById = recordedById;
        this.recordedBy = BudgetLine.trimToNull(recordedBy);
        this.recordedOn = recordedOn;
    }

    public ExpensePayment() {
        this(null, null, 0L, null, null, null, null, null, null);
    }
}
