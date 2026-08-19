package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public final class Transaction implements Serializable {
    private final String txId;
    private final Person.Id userId;
    private final String groupId;       // Shared or Batch Group Id
    private final Type type;            // Shared, Batch, or Tx (defaults to Tx if null)
    private TransactionType txType;
    private LocalDateTime txDate;
    private Float amount;
    private String category;
    private String note;
    private LocalDateTime deleted;
    // All members of this Shared/Batch group (including this row's userId). Written on every group save so
    // membership never requires probing other users' transactions. Null on legacy rows (see migration doc)
    // and on plain Tx rows.
    private List<Person.Id> groupPeople;
    /**
     * The {@link Organization} (tenancy boundary) this transaction belongs to; null on legacy rows until the
     * org migration backfills. Deliberately NOT a constructor parameter (the {@code Person.familyId} pattern):
     * Jackson populates it through the setter, so the many existing constructor call sites stay untouched.
     * Writers that know the trip stamp it from the trip's org.
     */
    private String orgId;

    @JsonCreator
    public Transaction(
            @JsonProperty("txId") String txId,
            @JsonProperty("userId") Person.Id userId,
            @JsonProperty("groupId") String groupId,
            @JsonProperty("type") Type type,
            @JsonProperty("txType") TransactionType txType,
            @JsonProperty("txDate") LocalDateTime txDate,
            @JsonProperty("amount") Float amount,
            @JsonProperty("category") String category,
            @JsonProperty("notes") String note,
            @JsonProperty("groupPeople") List<Person.Id> groupPeople) {
        if (userId == null) {
            throw new IllegalArgumentException("You must provide a userId for a new Transaction!");
        }
        this.txId = ((txId == null) || txId.isEmpty()) ? UUID.randomUUID().toString() : txId;
        this.userId = userId;
        this.groupId = groupId;
        this.type = type;
        this.txType = (txType == null) ? TransactionType.Payment : txType;
        this.txDate = txDate;
        this.amount = amount;
        this.category = category;
        this.note = note;
        this.deleted = null;
        this.groupPeople = (groupPeople == null) ? null : List.copyOf(groupPeople);
    }

    public Transaction(
            final String txId,
            final Person.Id userId,
            final String groupId,
            final Type type,
            final TransactionType txType,
            final LocalDateTime txDate,
            final Float amount,
            final String category,
            final String note) {
        this(txId, userId, groupId, type, txType, txDate, amount, category, note, null);
    }

    public Transaction(final Person.Id userId, final String groupId, final Type type) {
        if (userId == null) {
            throw new IllegalArgumentException("You must provide a userId for a new Transaction!");
        }
        this.txId = UUID.randomUUID().toString();
        this.userId = userId;
        this.groupId = groupId;
        this.type = type;
        this.txType = TransactionType.Payment;
        this.txDate = LocalDateTime.now();
        this.amount = null;
        this.category = "";
        this.note = "";
    }

    public LocalDateTime delete() {
        this.deleted = LocalDateTime.now();
        return deleted;
    }

    @JsonIgnore
    public boolean isBatch() {
        return type == Type.Batch;
    }

    @JsonIgnore
    public boolean isShared() {
        return type == Type.Shared;
    }

    @JsonIgnore
    public boolean isPayment() {
        return txType == TransactionType.Payment;
    }

    @JsonIgnore
    public boolean isBill() {
        return txType == TransactionType.Bill;
    }

    public enum Type {
        Batch, Shared, Tx;
    }

    /**
     * <p>This enum represents whether a transaction is a {@code Bill} or a {@code Payment}. A {@code Payment} is an
     * actual transaction that has taken place, money has exchanged hands. A {@code Bill} is a request for money that
     * is owed for goods or services. For example, a trip may cost $1,000, which is communicated as a {@code Bill}. A
     * {@code Payment} for $1,000 would cover this {@code Bill}, however, only the {@code Payment} involved an exchange
     * of money. However, the trip would be responsible for using that money to make a {@code Payment} for 1 or more
     * other expenses (perhaps separately from the user involved in these {@code Bill}s or {@code Payment}s).</p>
     *
     * <p>This can be used to show a user-oriented page that combines these together to show how much they owe or are
     * owed (if they overpaid). This can also be used to filter out {@code bill}s to show only {@code Payments} to /
     * from the trip.</p>
     *
     * <p>Note: A credit is a negative {@code Payment} -- money exchanges hands.</p>
     */
    public enum TransactionType {
        Bill, Payment,
        /**
         * The zeroing counterpart of a donation paid through the payments system: the donor's ledger shows a
         * {@code Payment} row (+amount) and this row (-amount, "Thank you for your donation…"), so the gift is
         * visible without changing their balance. Added 2026-08-17; rows only exist once the payment flow
         * writes them, so deploys that predate this value never read one.
         */
        Donation;
    }
}