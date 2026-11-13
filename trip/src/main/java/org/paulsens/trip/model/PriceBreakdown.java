package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Value;
import org.paulsens.trip.pay.MoneyMath;

/**
 * Why one registration costs what it does: the inputs the pricing engine read, the ordered rule lines it
 * applied, and the result. Computed once at checkout and stored on the {@link Registration}, never recomputed,
 * so an admin can always answer "why did this person pay $50?" even after the trip's prices change (see
 * {@code docs/checkout.md}, "Explainability").
 *
 * <p>{@link #summary()} is written into the registration's Bill description, which the ledger compares
 * byte-for-byte on every re-record, so its wording is pinned by a test.
 */
@Value
public class PriceBreakdown implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    Inputs inputs;
    List<Line> lines;
    long totalCents;
    /** The registration type the person bought ("Full weekend"): the label of their admission choice. */
    String registrationType;
    LocalDateTime computedAt;

    @JsonCreator
    public PriceBreakdown(
            @JsonProperty("inputs") final Inputs inputs,
            @JsonProperty("lines") final List<Line> lines,
            @JsonProperty("totalCents") final long totalCents,
            @JsonProperty("registrationType") final String registrationType,
            @JsonProperty("computedAt") final LocalDateTime computedAt) {
        this.inputs = inputs;
        this.lines = (lines == null) ? List.of() : List.copyOf(lines);
        this.totalCents = totalCents;
        this.registrationType = registrationType;
        this.computedAt = computedAt;
    }

    /**
     * One line of the explanation, in order: "Full weekend $150.00; Child (4-10) at most $50.00 -$100.00;
     * code SPEAKER -$50.00 = $0.00". Base and add-on lines show their price; every other line shows its signed
     * change. Pinned by a test because it lands in Bill descriptions.
     */
    @JsonIgnore
    public String summary() {
        final List<String> parts = new ArrayList<>();
        for (final Line line : lines) {
            parts.add(line.describe());
        }
        final String body = parts.isEmpty() ? "No charge" : String.join("; ", parts);
        return body + " = " + MoneyMath.formatCents(totalCents);
    }

    /** What kind of rule produced a line. Serialized by name. */
    public enum LineKind {
        /** The admission choice's price. */
        BASE,
        /** Another priced choice (an add-on). */
        ADD_ON,
        /** An age band changed a choice's price. */
        AGE_BAND,
        /** A discount code changed the price. */
        DISCOUNT_CODE,
        /** The total was clamped at zero. */
        FLOOR,
        /** No price change; something the admin should know ("no birthdate on file: priced as adult"). */
        NOTE
    }

    /** One step of the calculation. {@code ruleRef} names what produced it, e.g. {@code choice:ab12cd34}. */
    @Value
    public static class Line implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

        String label;
        LineKind kind;
        long deltaCents;
        String ruleRef;

        @JsonCreator
        public Line(
                @JsonProperty("label") final String label,
                @JsonProperty("kind") final LineKind kind,
                @JsonProperty("deltaCents") final long deltaCents,
                @JsonProperty("ruleRef") final String ruleRef) {
            this.label = (label == null) ? "" : label;
            this.kind = (kind == null) ? LineKind.NOTE : kind;
            this.deltaCents = deltaCents;
            this.ruleRef = ruleRef;
        }

        String describe() {
            return kind == LineKind.NOTE ? label : label + " " + amountText();
        }

        /**
         * The line's amount as every reader prints it: the price of a base or an add-on, nothing for a note, a
         * signed change for everything else. Not a bean getter, so it is never stored with the line.
         */
        public String amountText() {
            return switch (kind) {
                case BASE, ADD_ON -> MoneyMath.formatCents(deltaCents);
                case NOTE -> "";
                default -> MoneyMath.formatSigned(deltaCents);
            };
        }
    }

    /** What the engine read: enough to re-explain the price without the trip's current configuration. */
    @Value
    public static class Inputs implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

        /** Age on the trip's first day; null when there was no birthdate on file. */
        Integer ageAtStart;
        LocalDate tripStart;
        /** Every choice id picked, across all priced questions. */
        List<String> choiceIds;
        /** The admission choice, which names the registration type; null when the trip has no such question. */
        String typeChoiceId;
        /** The discount code as typed (normalized), or null. */
        String codeText;
        /** The choices whose attestation the person ticked. */
        List<String> attestedChoiceIds;

        @JsonCreator
        public Inputs(
                @JsonProperty("ageAtStart") final Integer ageAtStart,
                @JsonProperty("tripStart") final LocalDate tripStart,
                @JsonProperty("choiceIds") final List<String> choiceIds,
                @JsonProperty("typeChoiceId") final String typeChoiceId,
                @JsonProperty("codeText") final String codeText,
                @JsonProperty("attestedChoiceIds") final List<String> attestedChoiceIds) {
            this.ageAtStart = ageAtStart;
            this.tripStart = tripStart;
            this.choiceIds = (choiceIds == null) ? List.of() : List.copyOf(choiceIds);
            this.typeChoiceId = typeChoiceId;
            this.codeText = codeText;
            this.attestedChoiceIds = (attestedChoiceIds == null) ? List.of() : List.copyOf(attestedChoiceIds);
        }

        @JsonIgnore
        public boolean isBirthdatePresent() {
            return ageAtStart != null;
        }
    }
}
