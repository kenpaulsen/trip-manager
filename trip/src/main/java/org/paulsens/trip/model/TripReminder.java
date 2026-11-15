package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Value;
import lombok.With;
import org.paulsens.trip.security.Digests;

/**
 * One scheduled message of a trip: what it says, who gets it and when. See {@code docs/reminders.md}.
 *
 * <p>The rule this type exists to keep: <b>nothing is sent that a facilitator did not approve</b>. An approval
 * records a hash ({@link #approvalHash}) over the wording, the audience, the schedule AND the date that schedule
 * resolved to on the trip; {@link #isApprovedFor} recomputes it. So an edit through any path, or a trip whose
 * dates moved, stops the send by itself: no caller has to remember to reset the status.
 */
@Value
@With
public class TripReminder implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** What the reminder is about; every kind but {@link #CUSTOM} fixes its own audience. */
    public enum Kind {
        BALANCE_DUE("Balance due", Audience.WITH_BALANCE),
        WAIVER_UNSIGNED("Documents to sign", Audience.UNSIGNED_DOCUMENTS),
        PASSPORT("Passport", Audience.PASSPORT_ISSUE),
        COUNTDOWN("Before departure", Audience.ALL_CONFIRMED),
        CUSTOM("Custom message", null);

        private final String label;
        private final Audience audience;

        Kind(final String label, final Audience audience) {
            this.label = label;
            this.audience = audience;
        }

        public String getLabel() {
            return label;
        }

        /** The audience this kind always has; null for {@link #CUSTOM}, whose audience is chosen. */
        public Audience getAudience() {
            return audience;
        }
    }

    /** The date the offset counts from. */
    public enum Anchor {
        BALANCE_DUE_DATE("the balance due date"),
        TRIP_START("the trip start"),
        TRIP_END("the trip end"),
        FIXED_DATE("a date");

        private final String label;

        Anchor(final String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** Who receives it: always evaluated on the day it is sent, never frozen at approval. */
    public enum Audience {
        ALL_CONFIRMED("Everyone confirmed on the trip"),
        WITH_BALANCE("Confirmed travelers who still owe a balance"),
        UNSIGNED_DOCUMENTS("Confirmed travelers with a document left to sign"),
        PASSPORT_ISSUE("Confirmed travelers with a missing or expiring passport"),
        PENDING("People whose registration is awaiting approval");

        private final String label;

        Audience(final String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public enum Status {
        DRAFT, APPROVED
    }

    String id;
    Kind kind;
    String name;
    Anchor anchor;
    int offsetDays;
    LocalDate fixedDate;
    Audience audience;
    String subject;
    String body;
    String pushText;
    Status status;
    Person.Id approvedBy;
    Instant approvedAt;
    String approvedHash;

    @Builder(toBuilder = true)
    @JsonCreator
    public TripReminder(
            @JsonProperty("id") final String id,
            @JsonProperty("kind") final Kind kind,
            @JsonProperty("name") final String name,
            @JsonProperty("anchor") final Anchor anchor,
            @JsonProperty("offsetDays") final int offsetDays,
            @JsonProperty("fixedDate") final LocalDate fixedDate,
            @JsonProperty("audience") final Audience audience,
            @JsonProperty("subject") final String subject,
            @JsonProperty("body") final String body,
            @JsonProperty("pushText") final String pushText,
            @JsonProperty("status") final Status status,
            @JsonProperty("approvedBy") final Person.Id approvedBy,
            @JsonProperty("approvedAt") final Instant approvedAt,
            @JsonProperty("approvedHash") final String approvedHash) {
        this.id = id;
        this.kind = (kind == null) ? Kind.CUSTOM : kind;
        this.name = (name == null) ? "" : name;
        this.anchor = (anchor == null) ? Anchor.TRIP_START : anchor;
        this.offsetDays = offsetDays;
        this.fixedDate = fixedDate;
        // A built-in kind's audience is part of what it IS, so a stored or posted value cannot widen it.
        this.audience = (this.kind.getAudience() != null) ? this.kind.getAudience()
                : (audience == null) ? Audience.ALL_CONFIRMED : audience;
        this.subject = (subject == null) ? "" : subject;
        this.body = (body == null) ? "" : body;
        this.pushText = (pushText == null) ? "" : pushText;
        this.status = (status == null) ? Status.DRAFT : status;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.approvedHash = approvedHash;
    }

    /**
     * The day this reminder goes out on this trip; null when the trip lacks the date it counts from (no balance
     * due date, no start date), which the page shows as "no date" and which can be neither approved nor sent.
     */
    public LocalDate sendDate(final Trip trip) {
        if (anchor == Anchor.FIXED_DATE) {
            // A chosen date IS the send date: a stale offset left over from another schedule never moves it.
            return fixedDate;
        }
        final LocalDate base = anchorDate(trip);
        return (base == null) ? null : base.plusDays(offsetDays);
    }

    private LocalDate anchorDate(final Trip trip) {
        if (trip == null) {
            return null;
        }
        return switch (anchor) {
            case BALANCE_DUE_DATE -> trip.getCheckout().getBalanceDueOn();
            case TRIP_START -> (trip.getStartDate() == null) ? null : trip.getStartDate().toLocalDate();
            case TRIP_END -> (trip.getEndDate() == null) ? null : trip.getEndDate().toLocalDate();
            default -> null;
        };
    }

    /** The hash an approval of this exact message on this exact day carries; null when there is no day. */
    public String approvalHash(final LocalDate sendDate) {
        if (sendDate == null) {
            return null;
        }
        // Lengths are hashed with the text so no two field splits of the same characters collide.
        final StringBuilder all = new StringBuilder();
        for (final String part : new String[] {kind.name(), anchor.name(), Integer.toString(offsetDays),
                String.valueOf(fixedDate), audience.name(), subject, body, pushText, sendDate.toString()}) {
            all.append(part.length()).append(':').append(part).append('|');
        }
        return Digests.sha256Base64(all.toString());
    }

    /** Whether a facilitator approved exactly what would go out on this trip today. THE send gate. */
    public boolean isApprovedFor(final Trip trip) {
        final String now = approvalHash(sendDate(trip));
        return status == Status.APPROVED && now != null && Digests.matches(approvedHash, now);
    }

    /** Approved once, but the wording, audience or date is no longer what was approved. */
    public boolean isApprovalStale(final Trip trip) {
        return status == Status.APPROVED && !isApprovedFor(trip);
    }

    /** This reminder approved as it stands on this trip; unchanged (still a draft) when it has no send date. */
    public TripReminder approved(final Trip trip, final Person.Id by, final Instant at) {
        final String hash = approvalHash(sendDate(trip));
        if (hash == null) {
            return asDraft();
        }
        return toBuilder().status(Status.APPROVED).approvedBy(by).approvedAt(at).approvedHash(hash).build();
    }

    /** This reminder with any approval withdrawn. */
    public TripReminder asDraft() {
        return toBuilder().status(Status.DRAFT).approvedBy(null).approvedAt(null).approvedHash(null).build();
    }

    @JsonIgnore
    public boolean isPush() {
        return !pushText.isBlank();
    }

    /** "14 days before the balance due date", "On the trip start", "On Mar 3, 2027". */
    @JsonIgnore
    public String getScheduleText() {
        if (anchor == Anchor.FIXED_DATE) {
            return (fixedDate == null) ? "No date chosen" : "On " + fixedDate;
        }
        if (offsetDays == 0) {
            return "On " + anchor.getLabel();
        }
        final int days = Math.abs(offsetDays);
        return days + ((days == 1) ? " day " : " days ") + ((offsetDays < 0) ? "before " : "after ")
                + anchor.getLabel();
    }
}
