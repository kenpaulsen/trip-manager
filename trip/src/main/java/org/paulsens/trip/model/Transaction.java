package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Data;

@Data
public final class Transaction implements Serializable {
    private final String txId;
    private final String userId;
    private final String groupId;       // Shared or Batch Group Id
    private final Type type;            // Shared, Batch, or Tx (defaults to Tx if null)
    private LocalDateTime txDate;
    private Float amount;
    private String category;
    private String note;
    private LocalDateTime deleted;

    public Transaction(
            @JsonProperty("txId") String txId,
            @JsonProperty("userId") String userId,
            @JsonProperty("groupId") String groupId,
            @JsonProperty("type") Type type,
            @JsonProperty("txDate") LocalDateTime txDate,
            @JsonProperty("amount") Float amount,
            @JsonProperty("category") String category,
            @JsonProperty("notes") String note) {
        if (userId == null) {
            throw new IllegalArgumentException("You must provide a userId for a new Transaction!");
        }
        this.txId = ((txId == null) || txId.isEmpty()) ? UUID.randomUUID().toString() : txId;
        this.userId = userId;
        this.groupId = groupId;
        this.type = type;
        this.txDate = txDate;
        this.amount = amount;
        this.category = category;
        this.note = note;
        this.deleted = null;
    }

    public Transaction(final String userId, final String groupId, final Type type) {
        if (userId == null) {
            throw new IllegalArgumentException("You must provide a userId for a new Transaction!");
        }
        this.txId = UUID.randomUUID().toString();
        this.userId = userId;
        this.groupId = groupId;
        this.type = type;
        this.txDate = LocalDateTime.now();
        this.amount = null;
        this.category = "";
        this.note = "";
    }

    public LocalDateTime delete() {
        // FIXME: Do I need to do something different for Batch? Shared?
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

    public enum Type {
        Batch, Shared, Tx;
    }
}
