package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * One line of a {@link TripExpenseReport}'s cash-advance worksheet: what was advanced to the submitter (or, for
 * {@link AdvanceType#RETURNED}, given back), in which currency, when, and the type's own answers. The amount is a
 * MAGNITUDE in hundredths of the entry's currency; the type carries the sign ({@code pay/ExpenseMath.advanceUsd}).
 * A participant entry for one person may have recorded a Payment {@link Transaction} for them, ONCE: the stamp
 * stays even when the entry later changes, and the page warns instead of touching the ledger.
 *
 * <p>The constructor normalizes: a fresh id when none is given, {@code OTHER} when no type, USD when no valid
 * currency, a non-negative amount, blank text as null, and never a null map or list.
 */
@Data
public final class AdvanceEntry implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private String id;
    private AdvanceType type;
    private LocalDate date;
    private String currency;
    private long amountMinor;
    private String note;
    /** The type's own answers, by {@link AdvanceType#partyKey()} and whatever a later type adds. */
    private Map<String, String> fields;
    /** The people who handed over cash ({@link AdvanceType#PARTICIPANT}), as {@code Person.Id} values. */
    private List<String> personIds;
    /** The Payment transaction this entry recorded for its one participant; null until it did. */
    private RecordedTransaction transaction;

    @Builder
    @JsonCreator
    public AdvanceEntry(
            @JsonProperty("id") final String id,
            @JsonProperty("type") final AdvanceType type,
            @JsonProperty("date") final LocalDate date,
            @JsonProperty("currency") final String currency,
            @JsonProperty("amountMinor") final long amountMinor,
            @JsonProperty("note") final String note,
            @JsonProperty("fields") final Map<String, String> fields,
            @JsonProperty("personIds") final List<String> personIds,
            @JsonProperty("transaction") final RecordedTransaction transaction) {
        this.id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
        this.type = (type == null) ? AdvanceType.OTHER : type;
        this.date = date;
        final String code = CurrencyRate.normalizeCode(currency);
        this.currency = (code == null) ? ExpenseLine.USD : code;
        this.amountMinor = Math.max(0L, amountMinor);
        this.note = BudgetLine.trimToNull(note);
        this.fields = new LinkedHashMap<>();
        if (fields != null) {
            fields.forEach(this::putField);
        }
        this.personIds = (personIds == null) ? new ArrayList<>() : new ArrayList<>(personIds);
        this.transaction = transaction;
    }

    public AdvanceEntry() {
        this(null, null, null, null, 0L, null, null, null, null);
    }

    /** Keeps a field only when both its name and its value say something. */
    private void putField(final String key, final String value) {
        final String name = BudgetLine.trimToNull(key);
        final String text = BudgetLine.trimToNull(value);
        if (name != null && text != null) {
            fields.put(name, text);
        }
    }

    @JsonIgnore
    public boolean isUsd() {
        return ExpenseLine.USD.equals(currency);
    }

    /** The type's "who" answer (who gave it, where the credit came from, who it went to); null when none. */
    @JsonIgnore
    public String getParty() {
        return (type.partyKey() == null) ? null : fields.get(type.partyKey());
    }

    /** The Payment transaction a participant entry recorded: enough to say what, for whom and when. */
    @Data
    public static final class RecordedTransaction implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private String txId;
        private String personId;
        private long amountCents;
        private LocalDateTime recordedOn;

        @Builder
        @JsonCreator
        public RecordedTransaction(
                @JsonProperty("txId") final String txId,
                @JsonProperty("personId") final String personId,
                @JsonProperty("amountCents") final long amountCents,
                @JsonProperty("recordedOn") final LocalDateTime recordedOn) {
            this.txId = txId;
            this.personId = personId;
            this.amountCents = amountCents;
            this.recordedOn = recordedOn;
        }

        public RecordedTransaction() {
            this(null, null, 0L, null);
        }
    }
}
