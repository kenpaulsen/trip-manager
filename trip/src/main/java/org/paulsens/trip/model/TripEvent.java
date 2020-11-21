package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Data;

@Data
public final class TripEvent implements Serializable {
    private final String id;                    // TripEvent ID
    private String title;                       // Event title
    private String notes;                       // Event notes
    private LocalDateTime start;                // Start of the event
    private List<String> participants;          // Who's doing this thing?
    private final Map<String, String> privNotes; // Mapping of userId to Status

    public TripEvent(
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("notes") String notes,
            @JsonProperty("start") LocalDateTime start,
            @JsonProperty("participants") List<String> participants,
            @JsonProperty("privNotes") Map<String, String> privNotes) {
        if (id == null) {
            throw new IllegalArgumentException("ID is required!");
        }
        this.id = id;
        this.title = (title == null) ? "Title" : title;
        this.notes = (notes == null) ? "" : notes;
        this.start = (start == null) ? LocalDateTime.now().plusDays(30) : start;
        this.participants = (participants == null) ? new ArrayList<>() : new ArrayList<>(participants);
        this.privNotes = (privNotes == null) ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(privNotes);
    }

    public TripEvent() {
        this(UUID.randomUUID().toString(), "", null, null, null, null);
    }

    /*
    public TripEvent(final TripEvent oldTE) {
        this(oldTE.getId(), oldTE.getTitle(), oldTE.getNotes(), oldTE.getStart(), oldTE.getParticipants(),
                getIndividualNotes(oldTE));
    }

    private static Map<String, String> getIndividualNotes(final TripEvent oldTE) {
        // Save copy of the original
        final Map<String, String> orig = oldTE.getPeople();
        final Map<String, String> copyOfOrig = new HashMap<>(orig);
        // Un-hide everything
        copyOfOrig.keySet().stream().filter(oldTE::isHidden).forEach(id -> oldTE.setHidden(id, false));
        // Get the Result...
        final Map<String, String> result = new HashMap<>(orig);
        // Restore the original...
        orig.clear();
        orig.putAll(copyOfOrig);
        return result;
    }
     */

    public synchronized boolean joinTripEvent(final String personId) {
        boolean added = false;
        if (!participants.contains(personId)) {
            added = participants.add(personId);
        }
        return added;
    }

    public synchronized boolean leaveTripEvent(final String personId) {
        return participants.remove(personId);
    }
}
