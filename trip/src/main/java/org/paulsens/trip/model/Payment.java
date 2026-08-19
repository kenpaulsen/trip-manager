package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.Value;

/**
 * One payment attempt through a processor -- the durable record that makes double-submits, retries,
 * reconciliation, and refund tracing possible (the ledger {@link Transaction}s are derived FROM this on
 * success; the mir2026 "txId = order id" dedupe survives only as a secondary guard).
 *
 * <p>State machine: {@code CREATED -> CAPTURED -> RECORDED}, terminal {@code CANCELLED} / {@code FAILED}.
 * Transitions are conditional puts on the stored status ({@code PaymentDAO}), so a concurrent return-page
 * retry serializes rather than double-writing. A CAPTURED-but-not-RECORDED row is the crash-recovery
 * evidence: money moved, the ledger write is owed -- the return page and the admin reconciliation list both
 * retry it (deterministic transaction ids make that idempotent).
 *
 * <p>All money is LONG CENTS ({@code MoneyMath}); {@code allocations} are the amounts CREDITED per person
 * (fees ride on top per {@link FeesPaidBy}; the donation's fee share is always absorbed by the org).
 * {@code sandbox} payments (the paymentsAdmin toggle) reach the processor's sandbox APIs but never write
 * ledger rows and are excluded from balances and default reconciliation views.
 */
@Data
public final class Payment implements Serializable {
    private String paymentId;
    private String tripId;
    private String orgId;
    private Person.Id payerId;
    private String processorConfigId;
    private ProcessorType processorType;
    private List<Allocation> allocations;
    private long donationCents;
    private FeesPaidBy feesPaidBy;
    private long creditFeeCents;
    private long donationFeeCents;
    private long totalChargedCents;
    private boolean sandbox;
    private String orderRef;
    private String captureId;
    private long capturedGrossCents;
    private Long actualFeeCents;
    private Status status;
    private LocalDateTime createdAt;
    private LocalDateTime capturedAt;
    private LocalDateTime recordedAt;
    private List<String> txIds;

    @Builder
    @JsonCreator
    public Payment(
            @JsonProperty("paymentId") final String paymentId,
            @JsonProperty("tripId") final String tripId,
            @JsonProperty("orgId") final String orgId,
            @JsonProperty("payerId") final Person.Id payerId,
            @JsonProperty("processorConfigId") final String processorConfigId,
            @JsonProperty("processorType") final ProcessorType processorType,
            @JsonProperty("allocations") final List<Allocation> allocations,
            @JsonProperty("donationCents") final long donationCents,
            @JsonProperty("feesPaidBy") final FeesPaidBy feesPaidBy,
            @JsonProperty("creditFeeCents") final long creditFeeCents,
            @JsonProperty("donationFeeCents") final long donationFeeCents,
            @JsonProperty("totalChargedCents") final long totalChargedCents,
            @JsonProperty("sandbox") final boolean sandbox,
            @JsonProperty("orderRef") final String orderRef,
            @JsonProperty("captureId") final String captureId,
            @JsonProperty("capturedGrossCents") final long capturedGrossCents,
            @JsonProperty("actualFeeCents") final Long actualFeeCents,
            @JsonProperty("status") final Status status,
            @JsonProperty("createdAt") final LocalDateTime createdAt,
            @JsonProperty("capturedAt") final LocalDateTime capturedAt,
            @JsonProperty("recordedAt") final LocalDateTime recordedAt,
            @JsonProperty("txIds") final List<String> txIds) {
        this.paymentId = (paymentId == null || paymentId.isBlank())
                ? UUID.randomUUID().toString() : paymentId;
        this.tripId = tripId;
        this.orgId = orgId;
        this.payerId = payerId;
        this.processorConfigId = processorConfigId;
        this.processorType = processorType;
        this.allocations = (allocations == null) ? new ArrayList<>() : new ArrayList<>(allocations);
        this.donationCents = donationCents;
        this.feesPaidBy = (feesPaidBy == null) ? FeesPaidBy.ORGANIZATION : feesPaidBy;
        this.creditFeeCents = creditFeeCents;
        this.donationFeeCents = donationFeeCents;
        this.totalChargedCents = totalChargedCents;
        this.sandbox = sandbox;
        this.orderRef = orderRef;
        this.captureId = captureId;
        this.capturedGrossCents = capturedGrossCents;
        this.actualFeeCents = actualFeeCents;
        this.status = (status == null) ? Status.CREATED : status;
        this.createdAt = createdAt;
        this.capturedAt = capturedAt;
        this.recordedAt = recordedAt;
        this.txIds = (txIds == null) ? new ArrayList<>() : new ArrayList<>(txIds);
    }

    public Payment() {
        this(null, null, null, null, null, null, null, 0L, null, 0L, 0L, 0L, false, null, null, 0L, null,
                null, null, null, null, null);
    }

    /** Sum of the per-person credited amounts (fees and donation excluded). */
    @JsonIgnore
    public long getCreditTotalCents() {
        return allocations.stream().mapToLong(Allocation::getAmountCents).sum();
    }

    @JsonIgnore
    public boolean isPayerPaysFee() {
        return feesPaidBy == FeesPaidBy.PAYER;
    }

    public enum Status {
        CREATED, CAPTURED, RECORDED, CANCELLED, FAILED
    }

    /** One person's credited share of this payment (long cents). Order is the page's member order. */
    @Value
    public static class Allocation implements Serializable {
        @JsonProperty("personId")
        Person.Id personId;
        @JsonProperty("amountCents")
        long amountCents;

        @JsonCreator
        public Allocation(@JsonProperty("personId") final Person.Id personId,
                @JsonProperty("amountCents") final long amountCents) {
            this.personId = personId;
            this.amountCents = amountCents;
        }
    }
}
