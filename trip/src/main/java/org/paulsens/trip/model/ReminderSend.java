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

/**
 * The record that one reminder was sent (or attempted) for one traveler on one send date: a row of the trip's
 * partition in {@code trip_reminders}, SK {@link #getSortKey}. It does two jobs. Written with
 * {@code attribute_not_exists} BEFORE the mail goes out, it is the claim that makes a send happen at most once
 * per (trip, reminder, date, traveler) across servers and restarts; read back, it is the page's Sent history.
 *
 * <p>The reminder's name, the subject and the address are captured here, so the history still reads correctly
 * after the reminder is edited or deleted.
 */
@Value
@With
public class ReminderSend implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** Every send row's sort key starts with this; the plan's does not. */
    public static final String SORT_PREFIX = "SEND#";

    /** Where the send stands. A row left {@code CLAIMED} is a send that died mid-flight: never retried silently. */
    public enum Outcome {
        CLAIMED,
        SENT,
        FAILED,
        NO_ADDRESS,
        /** The run happened and nobody was in the audience: the one row with no traveler. */
        NOBODY;

        public String getLabel() {
            return EnumLabels.of(this);
        }
    }

    String tripId;
    String reminderId;
    LocalDate sendDate;
    Person.Id personId;
    String reminderName;
    String personName;
    String toEmail;
    String subject;
    Outcome outcome;
    boolean pushed;
    boolean manual;
    Instant at;

    @Builder(toBuilder = true)
    @JsonCreator
    public ReminderSend(
            @JsonProperty("tripId") final String tripId,
            @JsonProperty("reminderId") final String reminderId,
            @JsonProperty("sendDate") final LocalDate sendDate,
            @JsonProperty("personId") final Person.Id personId,
            @JsonProperty("reminderName") final String reminderName,
            @JsonProperty("personName") final String personName,
            @JsonProperty("toEmail") final String toEmail,
            @JsonProperty("subject") final String subject,
            @JsonProperty("outcome") final Outcome outcome,
            @JsonProperty("pushed") final boolean pushed,
            @JsonProperty("manual") final boolean manual,
            @JsonProperty("at") final Instant at) {
        this.tripId = tripId;
        this.reminderId = reminderId;
        this.sendDate = sendDate;
        this.personId = personId;
        this.reminderName = reminderName;
        this.personName = personName;
        this.toEmail = toEmail;
        this.subject = subject;
        this.outcome = (outcome == null) ? Outcome.CLAIMED : outcome;
        this.pushed = pushed;
        this.manual = manual;
        this.at = at;
    }

    /** {@code SEND#<reminderId>#<sendDate>#<personId>}: one traveler, one reminder, one day. */
    @JsonIgnore
    public String getSortKey() {
        return sortKey(reminderId, sendDate, personId);
    }

    public static String sortKey(final String reminderId, final LocalDate sendDate, final Person.Id personId) {
        return SORT_PREFIX + reminderId + "#" + sendDate + "#" + ((personId == null) ? "" : personId.getValue());
    }
}
