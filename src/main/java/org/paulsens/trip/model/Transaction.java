package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

@Data
public class Transaction implements Serializable {
    String userId;
    OffsetDateTime txDate;
    Float amount;
    String category;
    String note;

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
    }

    public Transaction() {
        this.userId = null;
        this.txDate = null;
        this.amount = null;
        this.category = null;
        this.note = null;
    }

    private String getNewId() {
        return UUID.randomUUID().toString();
    }
}
