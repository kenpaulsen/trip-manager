package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.paulsens.trip.dynamo.DynamoUtils;

@Data
public final class TripEvent implements Serializable {
    private final String id;                    // Trip ID
    private String title;                       // Event title
    private String notes;                       // Event notes
    private LocalDateTime start;                // Start of the event
    private final Map<String, String> people;   // Mapping of userId to Status

    @Getter(value = AccessLevel.NONE) @Setter(value = AccessLevel.NONE)
    private transient Trip trip;

    private static final String NOTES_DEFAULT = "Enter Event Notes";
    public static final String HIDDEN = "{na}";

    public TripEvent(
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("notes") String notes,
            @JsonProperty("start") LocalDateTime start,
            @JsonProperty("people") Map<String, String> peopleStatus) {
        if ((title == null) || (id == null)) {
            throw new IllegalArgumentException("Title and id are both required!");
        }
        this.id = id;
        this.title = title;
        this.notes = (notes == null) ? NOTES_DEFAULT : notes;
        this.start = (start == null) ? LocalDateTime.now().plusDays(30) : start;
        this.people = (peopleStatus == null) ? new ConcurrentHashMap<>() : peopleStatus;
    }

    public TripEvent() {
        this.id = UUID.randomUUID().toString();
        this.title = null;
        this.notes = NOTES_DEFAULT;
        this.start = LocalDateTime.now().plusDays(30);
        this.people = new ConcurrentHashMap<>();
    }

    /**
     * This returns a list of people planned to participate in this {@code TripEvent}. It requires the {@link Trip}
     * itself because the {@code TripEvent} needs to see the possible users and doesn't store a reference to its parent
     * {@link Trip}. This will never return {@code null}, but will return empty if nobody is participating in this
     * event.  Note: we do maintain a {@code Map} of users to notes for that user, but users are assumed to be
     * participating if they are part of the {@link Trip}, unless explicitly excluded see:
     * {@link #setHidden(String, boolean)}.
     *
     * @return  The list of participants.
     */
    @JsonIgnore
    public List<String> getParticipants() {
        return getTrip().getPeople().stream()
                .filter(pid -> !people.containsKey(pid) || !people.get(pid).startsWith(HIDDEN))
                .collect(Collectors.toList());
    }

    /**
     * This can set the list of people that should be able to see this {@code TripEvent}. This cannot make it available
     * to anyone that isn't part of the {@link Trip}, however!
     *
     * @param participants  The {@code userId}'s of the participants whom are planned for this {@code TripEvent}.
     */
    @JsonIgnore
    public void setParticipants(List<String> participants) {
        // Look at the potential participants, if any are hidden and in the given participant list, make them visible
        getPeople().keySet().stream().filter(p -> isHidden(p) && participants.contains(p))
                .forEach(this::setVisible);
        // Now we must ALSO hide any that are not in this list
        getTrip().getPeople().stream().filter(p -> !participants.contains(p))
                .forEach(this::setHidden);
    }

    /**
     * Checks if this {@code TripEvent} is hidden for the given {@code userId}.
     * @param userId    The user to check.
     * @return  {@code true} if it should not be shown to the user, {@code false} if it is not hidden from the user.
     */
    @JsonIgnore
    public boolean isHidden(final String userId) {
        return getPeople().containsKey(userId) && getPeople().get(userId).startsWith(HIDDEN);
    }

    /**
     * Makes this {@code TripEvent} hidden or visible for the given {@code userId}. Pass {@code true} to hide this
     * {@code TripEvent}, and {@code false} to make it visible to the user.
     *
     * @param userId    The user to hide (or show) this {@code TripEvent}.
     * @param hide      The flag as to whether this event should be hidden ({@code true}) or not ({@code false}).
     */
    @JsonIgnore
    public void setHidden(final String userId, final boolean hide) {
        Optional.ofNullable(userId).ifPresent(uid -> {
            if (hide) {
                setHidden(uid);
            } else {
                setVisible(uid);
            }
        });
    }

    private Trip getTrip() {
        if (trip == null) {
            trip = DynamoUtils.getInstance().getTrips().join().stream()
                    .filter(t -> t.getTripEvents().contains(this)).findAny()
                    .orElseThrow(() -> new IllegalStateException("Trip Event without parent Trip!"));
        }
        return trip;
    }

    private void setHidden(final String userId) {
        final String old = people.get(userId);
        if ((old == null) || !old.startsWith(HIDDEN)) {
            // Add marker to ignore this for this user
            people.put(userId, HIDDEN + ((old == null) ? "" : old));
        }
    }

    private void setVisible(final String userId) {
        final String old = people.get(userId);
        if ((old != null) && old.startsWith(HIDDEN)) {
            // Remove marker
            people.put(userId, old.substring(HIDDEN.length()));
        }
    }
}
