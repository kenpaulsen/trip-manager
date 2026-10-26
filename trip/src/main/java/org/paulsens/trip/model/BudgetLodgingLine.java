package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * One lodging line of a {@link TripBudget}: the quantities planned on one of the trip's lodging options
 * ({@link ReservationOffer}). The money is NOT here: the line reads the option's price and cost schedules
 * live, so a rate change on the Lodging tab shows up in the budget at once. {@code label} (hotel and option
 * name, captured at save) is only what the page shows once the option has been deleted.
 *
 * <p>Counts never go negative, and {@code singles} never exceeds {@code people}.
 */
@Data
public final class BudgetLodgingLine implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private String id;
    private String offerId;
    private String label;
    private int nights;
    private int people;
    private int rooms;
    private int singles;
    private String payee;
    private String note;

    @Builder
    @JsonCreator
    public BudgetLodgingLine(
            @JsonProperty("id") final String id,
            @JsonProperty("offerId") final String offerId,
            @JsonProperty("label") final String label,
            @JsonProperty("nights") final int nights,
            @JsonProperty("people") final int people,
            @JsonProperty("rooms") final int rooms,
            @JsonProperty("singles") final int singles,
            @JsonProperty("payee") final String payee,
            @JsonProperty("note") final String note) {
        this.id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
        this.offerId = BudgetLine.trimToNull(offerId);
        this.label = BudgetLine.trimToNull(label);
        this.nights = Math.max(0, nights);
        this.people = Math.max(0, people);
        this.rooms = Math.max(0, rooms);
        this.singles = Math.min(Math.max(0, singles), this.people);
        this.payee = BudgetLine.trimToNull(payee);
        this.note = BudgetLine.trimToNull(note);
    }

    public BudgetLodgingLine() {
        this(null, null, null, 0, 0, 0, 0, null, null);
    }
}
