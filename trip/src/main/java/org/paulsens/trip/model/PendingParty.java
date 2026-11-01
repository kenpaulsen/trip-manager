package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * A priced checkout's party, waiting on its payment (docs/checkout.md): the EXACT registration rows to write once the
 * {@link Payment} it rides on is recorded. Pay-first: no registration exists before then, so an unpaid party never
 * reaches the Registrations tab; an abandoned checkout is just a CREATED payment. Each row already carries its
 * status (CONFIRMED or PENDING, from the trip's policy), answers, attestations, price breakdown and ticket code, so
 * finalizing is "write these if absent" and re-running it is harmless.
 */
@Data
public final class PendingParty implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** The {@code Registration.OPT_PARTY} stamp the rows share. */
    private String partyId;
    private Person.Id registeredBy;
    private List<Registration> rows;
    /** personId value -> the daily chat digest answer, applied on confirmation. */
    private Map<String, Boolean> digest;
    /** When the rows were written; null until then. */
    private LocalDateTime finalizedAt;
    /** What an admin should know (someone registered meanwhile, over capacity, a code over its limit). */
    private List<String> warnings;

    @JsonCreator
    public PendingParty(
            @JsonProperty("partyId") final String partyId,
            @JsonProperty("registeredBy") final Person.Id registeredBy,
            @JsonProperty("rows") final List<Registration> rows,
            @JsonProperty("digest") final Map<String, Boolean> digest,
            @JsonProperty("finalizedAt") final LocalDateTime finalizedAt,
            @JsonProperty("warnings") final List<String> warnings) {
        this.partyId = partyId;
        this.registeredBy = registeredBy;
        this.rows = (rows == null) ? new ArrayList<>() : new ArrayList<>(rows);
        this.digest = (digest == null) ? new HashMap<>() : new HashMap<>(digest);
        this.finalizedAt = finalizedAt;
        this.warnings = (warnings == null) ? new ArrayList<>() : new ArrayList<>(warnings);
    }

    public PendingParty() {
        this(null, null, null, null, null, null);
    }
}
