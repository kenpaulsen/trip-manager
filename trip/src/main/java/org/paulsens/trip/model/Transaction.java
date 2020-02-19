package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.Data;

@Data
public final class Transaction implements Serializable {
    private String userId;
    private OffsetDateTime txDate;
    private Float amount;
    private String category;
    private String note;
    private OffsetDateTime deleted;

    public Transaction(
            @JsonProperty("userId") String userId,
            @JsonProperty("txDate") OffsetDateTime txDate,
            @JsonProperty("amount") Float amount,
            @JsonProperty("category") String category,
            @JsonProperty("notes") String note) {
        this.userId = userId;
        this.txDate = txDate;
        this.amount = amount;
        this.category = category;
        this.note = note;
        this.deleted = null;
    }

    public Transaction() {
        this.userId = null;
        this.txDate = null;
        this.amount = null;
        this.category = null;
        this.note = null;
    }

    public OffsetDateTime delete() {
        this.deleted = OffsetDateTime.now(ZoneOffset.UTC);
        return deleted;
    }
}
