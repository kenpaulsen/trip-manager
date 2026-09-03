package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.Value;

/**
 * One payment-processor integration owned by one {@link Organization} -- a row in the {@code payment_processors}
 * table (PK orgId, SK id), so tenancy is structural: a trip resolves configs as
 * {@code getConfig(trip.orgId, configId)} and a foreign config id simply misses.
 *
 * <p>Holds only NON-secret material. API secrets live in Secrets Manager under this config's id (see
 * {@code ProcessorSecrets}); {@code publicConfig} carries the processor-specific public identifiers (PayPal
 * client id, Stripe publishable key), with {@code sandboxPublicConfig} as the sandbox counterpart that powers
 * the paymentsAdmin sandbox toggle. Fee fields are the estimate model ({@code feeBps} basis points +
 * {@code feeFixedCents}); zero means "use the {@link ProcessorType} default".
 *
 * <p>Optimistic {@code version} saves, the {@link Family} recipe. No cross-org sharing: orgs own their configs.
 */
@Data
public final class PaymentProcessorConfig implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private Id id;
    private Organization.Id orgId;
    private String label;
    private ProcessorType type;
    private ProcessorMode mode;
    private boolean enabled;
    private Map<String, String> publicConfig;
    private Map<String, String> sandboxPublicConfig;
    private int feeBps;
    private int feeFixedCents;
    private Person.Id createdBy;
    private LocalDateTime created;
    private long version;

    @Builder
    @JsonCreator
    public PaymentProcessorConfig(
            @JsonProperty("id") final Id id,
            @JsonProperty("orgId") final Organization.Id orgId,
            @JsonProperty("label") final String label,
            @JsonProperty("type") final ProcessorType type,
            @JsonProperty("mode") final ProcessorMode mode,
            @JsonProperty("enabled") final boolean enabled,
            @JsonProperty("publicConfig") final Map<String, String> publicConfig,
            @JsonProperty("sandboxPublicConfig") final Map<String, String> sandboxPublicConfig,
            @JsonProperty("feeBps") final int feeBps,
            @JsonProperty("feeFixedCents") final int feeFixedCents,
            @JsonProperty("createdBy") final Person.Id createdBy,
            @JsonProperty("created") final LocalDateTime created,
            @JsonProperty("version") final long version) {
        this.id = (id == null) ? Id.newInstance() : id;
        this.orgId = orgId;
        this.label = (label == null) ? null : label.trim();
        this.type = type;
        this.mode = (mode == null) ? ProcessorMode.SANDBOX : mode;
        this.enabled = enabled;
        this.publicConfig = (publicConfig == null) ? new HashMap<>() : new HashMap<>(publicConfig);
        this.sandboxPublicConfig =
                (sandboxPublicConfig == null) ? new HashMap<>() : new HashMap<>(sandboxPublicConfig);
        this.feeBps = feeBps;
        this.feeFixedCents = feeFixedCents;
        this.createdBy = createdBy;
        this.created = created;
        this.version = version;
    }

    public PaymentProcessorConfig() {
        this(null, null, null, null, null, false, null, null, 0, 0, null, null, 0L);
    }

    /** The estimate rate in basis points: this config's override, else the processor default. */
    @JsonIgnore
    public int getEffectiveFeeBps() {
        return (feeBps > 0 || type == null) ? feeBps : type.getDefaultFeeBps();
    }

    /** The estimate fixed fee in cents: this config's override, else the processor default. */
    @JsonIgnore
    public int getEffectiveFeeFixedCents() {
        return (feeFixedCents > 0 || type == null) ? feeFixedCents : type.getDefaultFeeFixedCents();
    }

    /** Display label like "Acme Stripe (STRIPE, LIVE)" for pickers. */
    @JsonIgnore
    public String getDisplayLabel() {
        return label + " (" + (type == null ? "?" : type.name()) + ", " + mode.name() + ")";
    }

    /** Sandbox vs live production endpoints. A LIVE config may still carry sandbox credentials for testing. */
    public enum ProcessorMode {
        SANDBOX, LIVE
    }

    @Value
    public static class Id implements Serializable, Comparable<Id> {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

        @JsonValue
        String value;

        public static Id from(final String id) {
            return new Id(id);
        }

        public static Id newInstance() {
            return new Id(UUID.randomUUID().toString());
        }

        @Override
        public int compareTo(final Id o) {
            return value.compareTo(o.getValue());
        }
    }
}
