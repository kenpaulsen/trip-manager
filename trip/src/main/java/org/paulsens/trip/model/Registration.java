package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Value;
import lombok.With;

/**
 * One person's registration for one trip.
 *
 * <p>Every field after {@link #options} is additive (2026-11, conferences) and null on rows stored before it
 * existed. They are all {@code @With} so a copy can never drop them: rebuilding a registration through a
 * constructor that predates a field silently erases it on the next save, so code that changes one field must
 * use the matching {@code withX}. See {@code docs/checkout.md} and {@code docs/checkin.md}.
 */
@Value
public class Registration implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /**
     * Reserved {@link #options} keys, underscore-prefixed so they can never collide with the numeric
     * {@code RegistrationOption} ids (the convention the mir2026 branch established). Additive JSON: rows
     * written before family accounts simply lack them.
     */
    public static final String OPT_REGISTERED_BY = "_registeredBy";
    /** One shared UUID per family submit, so the admin page can group a party visually. */
    public static final String OPT_PARTY = "_party";

    String tripId;                  // The trip id (partition key)
    Person.Id userId;               // The user id (sort key)
    LocalDateTime created;          // When they first registered
    @With
    Status status;                  // Registration Status
    @With
    Map<String, String> options;    // Extra information
    /** The {@link Payment} that paid for this registration through priced checkout, or null. */
    @With
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String paymentId;
    /**
     * The ticket: an unguessable bearer code ({@code TicketCodes}) that the ticket page and the check-in QR
     * carry. Every CONFIRMED registration has one; never shown in a roster listing.
     */
    @With
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String ticketCode;
    /** What the person bought ("Full weekend"), denormalized from {@link #priceBreakdown} for sorting. */
    @With
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String registrationType;
    /** Why this registration cost what it did, as computed at checkout. Never recomputed. */
    @With
    @JsonInclude(JsonInclude.Include.NON_NULL)
    PriceBreakdown priceBreakdown;
    /** choice id -> the attestation TEXT the person ticked, as worded when they ticked it. */
    @With
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    Map<String, String> attestations;
    /** When staff checked this person in at the event; null until then. */
    @With
    @JsonInclude(JsonInclude.Include.NON_NULL)
    LocalDateTime checkedInAt;
    /** Who checked them in; null for a check-in imported from the old system. */
    @With
    @JsonInclude(JsonInclude.Include.NON_NULL)
    Person.Id checkedInBy;
    /** When this person's name badge was last marked printed; null until then. */
    @With
    @JsonInclude(JsonInclude.Include.NON_NULL)
    LocalDateTime badgePrintedAt;

    @JsonCreator
    public Registration(
            @JsonProperty("tripId") final String tripId,
            @JsonProperty("userId") final Person.Id userId,
            @JsonProperty("created") final LocalDateTime created,
            @JsonProperty("status") final Status status,
            @JsonProperty("options") final Map<String, String> options,
            @JsonProperty("paymentId") final String paymentId,
            @JsonProperty("ticketCode") final String ticketCode,
            @JsonProperty("registrationType") final String registrationType,
            @JsonProperty("priceBreakdown") final PriceBreakdown priceBreakdown,
            @JsonProperty("attestations") final Map<String, String> attestations,
            @JsonProperty("checkedInAt") final LocalDateTime checkedInAt,
            @JsonProperty("checkedInBy") final Person.Id checkedInBy,
            @JsonProperty("badgePrintedAt") final LocalDateTime badgePrintedAt) {
        this.tripId = tripId;
        this.userId = userId;
        this.created = (created == null) ? LocalDateTime.now() : created;
        this.status = (status == null) ? Status.NOT_REGISTERED : status;
        this.options = (options == null) ? new HashMap<>() : options;
        this.paymentId = paymentId;
        this.ticketCode = ticketCode;
        this.registrationType = registrationType;
        this.priceBreakdown = priceBreakdown;
        this.attestations = (attestations == null) ? new HashMap<>() : attestations;
        this.checkedInAt = checkedInAt;
        this.checkedInBy = checkedInBy;
        this.badgePrintedAt = badgePrintedAt;
    }

    /** A registration with none of the checkout or check-in fields: what every row stored before them reads as. */
    public Registration(final String tripId, final Person.Id userId, final LocalDateTime created,
            final Status status, final Map<String, String> options) {
        this(tripId, userId, created, status, options, null, null, null, null, null, null, null, null);
    }

    public Registration(final String tripId, final Person.Id userId) {
        this(tripId, userId, null, null, null);
    }

    public Registration withStatusString(final String description) {
        return withStatus(Status.fromDescription(description));
    }

    /** Who submitted this registration (a family manager registering their member), or null pre-family rows. */
    @JsonIgnore
    public String getRegisteredBy() {
        return options.get(OPT_REGISTERED_BY);
    }

    /** The family-submit party id, or null when registered individually. */
    @JsonIgnore
    public String getParty() {
        return options.get(OPT_PARTY);
    }

    /** Whether staff have checked this person in at the event. */
    @JsonIgnore
    public boolean isCheckedIn() {
        return checkedInAt != null;
    }

    public enum Status {
        NOT_REGISTERED("Not Registered"),
        CONFIRMED("Confirmed"),
        PENDING("Pending");

        @Getter @JsonValue
        final String description;

        Status(final String description) {
            this.description = description;
        }

        @JsonCreator
        public static Status fromDescription(final String description) {
            return switch (description) {
                case "Not Registered" -> NOT_REGISTERED;
                case "Confirmed" -> CONFIRMED;
                case "Pending" -> PENDING;
                default -> null;
            };
        }

        @Override
        public String toString() {
            return description;
        }
    }
}
