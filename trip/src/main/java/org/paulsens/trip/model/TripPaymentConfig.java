package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

/**
 * Payment configuration, embedded on a {@link Trip} ({@code paymentConfig}) AND on an {@link Organization}
 * ({@code paymentDefaults}) -- one type so the resolver and the dialogs are shared. Every field is nullable:
 * null means "inherit from the next rung" of the ladder (trip &rarr; org defaults &rarr; site settings),
 * resolved by {@code OrgCommands.effectivePaymentConfig}.
 */
@Data
public final class TripPaymentConfig implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private String processorConfigId;
    private FeesPaidBy feesPaidBy;
    private Boolean donationEnabled;
    private String donationLabel;
    /** A {@code TemplateKind.MAIL} template id; its NAME doubles as the subject line. */
    private String confirmationTemplateId;
    private String mailFrom;
    private String replyTo;
    private String bcc;
    /** Static extra {{token}} values merged into the confirmation render (admin-supplied strings). */
    private Map<String, String> extraTokens;

    @Builder
    @JsonCreator
    public TripPaymentConfig(
            @JsonProperty("processorConfigId") final String processorConfigId,
            @JsonProperty("feesPaidBy") final FeesPaidBy feesPaidBy,
            @JsonProperty("donationEnabled") final Boolean donationEnabled,
            @JsonProperty("donationLabel") final String donationLabel,
            @JsonProperty("confirmationTemplateId") final String confirmationTemplateId,
            @JsonProperty("mailFrom") final String mailFrom,
            @JsonProperty("replyTo") final String replyTo,
            @JsonProperty("bcc") final String bcc,
            @JsonProperty("extraTokens") final Map<String, String> extraTokens) {
        this.processorConfigId = trim(processorConfigId);
        this.feesPaidBy = feesPaidBy;
        this.donationEnabled = donationEnabled;
        this.donationLabel = trim(donationLabel);
        this.confirmationTemplateId = trim(confirmationTemplateId);
        this.mailFrom = trim(mailFrom);
        this.replyTo = trim(replyTo);
        this.bcc = trim(bcc);
        this.extraTokens = (extraTokens == null) ? new HashMap<>() : new HashMap<>(extraTokens);
    }

    public TripPaymentConfig() {
        this(null, null, null, null, null, null, null, null, null);
    }

    /** True only when fully resolved AND a processor is actually configured -- the "payments on" test. */
    @JsonIgnore
    public boolean isPayable() {
        return processorConfigId != null && !processorConfigId.isBlank();
    }

    /** Boolean view for EL (a null Boolean reads as false in a rendered test). */
    @JsonIgnore
    public boolean isDonationOn() {
        return Boolean.TRUE.equals(donationEnabled);
    }

    /**
     * Field-by-field overlay: this config's non-null fields win; nulls fall through to {@code next}.
     * The extraTokens maps MERGE (this rung's keys win). Both inputs are left untouched.
     */
    public TripPaymentConfig overlayOn(final TripPaymentConfig next) {
        final TripPaymentConfig base = (next == null) ? new TripPaymentConfig() : next;
        final Map<String, String> tokens = new HashMap<>(base.getExtraTokens());
        tokens.putAll(extraTokens);
        return TripPaymentConfig.builder()
                .processorConfigId(firstNonNull(processorConfigId, base.processorConfigId))
                .feesPaidBy(feesPaidBy != null ? feesPaidBy : base.feesPaidBy)
                .donationEnabled(donationEnabled != null ? donationEnabled : base.donationEnabled)
                .donationLabel(firstNonNull(donationLabel, base.donationLabel))
                .confirmationTemplateId(firstNonNull(confirmationTemplateId, base.confirmationTemplateId))
                .mailFrom(firstNonNull(mailFrom, base.mailFrom))
                .replyTo(firstNonNull(replyTo, base.replyTo))
                .bcc(firstNonNull(bcc, base.bcc))
                .extraTokens(tokens)
                .build();
    }

    private static String firstNonNull(final String preferred, final String fallback) {
        return (preferred == null || preferred.isBlank()) ? fallback : preferred;
    }

    private static String trim(final String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
