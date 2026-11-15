package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * A trip's scheduled messages: ONE row per trip in {@code trip_reminders} (PK tripId, SK {@link #SORT_KEY}),
 * the reminders inline, saved by one versioned conditional put so two facilitators cannot silently overwrite
 * each other's wording or approval. The From and Reply-To are the trip's, shared by every reminder. See
 * {@code docs/reminders.md}.
 */
@Data
public final class TripReminderPlan implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** The plan's sort key; every other item of the trip's partition is a {@link ReminderSend}. */
    public static final String SORT_KEY = "PLAN";

    private String tripId;
    private String orgId;
    private String mailFrom;
    private String replyTo;
    private List<TripReminder> reminders;
    private long version;

    @Builder
    @JsonCreator
    public TripReminderPlan(
            @JsonProperty("tripId") final String tripId,
            @JsonProperty("orgId") final String orgId,
            @JsonProperty("mailFrom") final String mailFrom,
            @JsonProperty("replyTo") final String replyTo,
            @JsonProperty("reminders") final List<TripReminder> reminders,
            @JsonProperty("version") final long version) {
        this.tripId = tripId;
        this.orgId = orgId;
        this.mailFrom = mailFrom;
        this.replyTo = replyTo;
        this.reminders = (reminders == null) ? new ArrayList<>() : new ArrayList<>(reminders);
        this.version = version;
    }

    public TripReminderPlan() {
        this(null, null, null, null, null, 0L);
    }

    /** The reminder with this id; null when there is none. */
    public TripReminder reminder(final String id) {
        for (final TripReminder reminder : reminders) {
            if (reminder.getId().equals(id)) {
                return reminder;
            }
        }
        return null;
    }

    /** Stores {@code reminder} in place of the one with its id, or appends it when it is new. */
    public void put(final TripReminder reminder) {
        for (int i = 0; i < reminders.size(); i++) {
            if (reminders.get(i).getId().equals(reminder.getId())) {
                reminders.set(i, reminder);
                return;
            }
        }
        reminders.add(reminder);
    }

    /** Removes the reminder with this id; false when there was none. */
    public boolean remove(final String id) {
        return reminders.removeIf(reminder -> reminder.getId().equals(id));
    }
}
