package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Data;

@Data
public final class Transaction implements Serializable {
    private final String txId;
    private final String userId;
    private LocalDateTime txDate;
    private Float amount;
    private String category;
    private String note;
    private LocalDateTime deleted;

    public Transaction(
            @JsonProperty("txId") String txId,
            @JsonProperty("userId") String userId,
            @JsonProperty("txDate") LocalDateTime txDate,
            @JsonProperty("amount") Float amount,
            @JsonProperty("category") String category,
            @JsonProperty("notes") String note) {
        if (userId == null) {
            throw new IllegalArgumentException("You must provide a userId for a new Transaction!");
        }
        this.txId = ((txId == null) || txId.isEmpty()) ? UUID.randomUUID().toString() : txId;
        this.userId = userId;
        this.txDate = txDate;
        this.amount = amount;
        this.category = category;
        this.note = note;
        this.deleted = null;
    }

    public Transaction(final String userId) {
        if (userId == null) {
            throw new IllegalArgumentException("You must provide a userId for a new Transaction!");
        }
        this.txId = UUID.randomUUID().toString();
        this.userId = userId;
        this.txDate = LocalDateTime.now();
        this.amount = null;
        this.category = "";
        this.note = "";
    }

    public LocalDateTime delete() {
        this.deleted = LocalDateTime.now();
        return deleted;
    }
}
