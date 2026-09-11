package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Data;
import lombok.Getter;

@Data
public final class TripEvent implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private final String id;                    // TripEvent ID
    private Type type;                          // TripEvent.Type
    private String title;                       // Event title
    private String notes;                       // Event notes
    private LocalDateTime start;                // Start of the event
    private LocalDateTime end;                  // End of the event
    private List<Person.Id> participants;          // Who's doing this thing?
    private final Map<Person.Id, String> privNotes; // Mapping of userId to Status

    /**
     * The components a manager typed into a bespoke editor (a flight's airports and number, a bus's route and
     * carrier), keyed by {@link Detail}; {@code title} and {@code notes} are always the composed rendering of
     * them. Absent on every event that predates the editor and on events typed as free text, which is the
     * signal the editor uses to fall back to its generic form -- parsing the composed text back apart was
     * rejected because notes get hand-edited ("CHANGED TO: 15:40", "Cancelled"). Set through the setter, not
     * the creator: the 8-argument constructor is the JSON and API shape and stays as it is, so a row written
     * without this field reads back with it null (the {@code Address.street2} precedent).
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> details;

    public TripEvent(
            @JsonProperty("id") String id,
            @JsonProperty("type") Type type,
            @JsonProperty("title") String title,
            @JsonProperty("notes") String notes,
            @JsonProperty("start") LocalDateTime start,
            @JsonProperty("end") LocalDateTime end,
            @JsonProperty("participants") List<Person.Id> participants,
            @JsonProperty("privNotes") Map<Person.Id, String> privNotes) {
        if (id == null) {
            throw new IllegalArgumentException("ID is required!");
        }
        this.id = id;
        this.type = type;
        this.title = (title == null) ? "Title" : title;
        this.notes = (notes == null) ? "" : notes;
        this.start = (start == null) ? LocalDateTime.now().plusDays(30) : start;
        this.end = (end == null) ? this.start.plusHours(3) : end;
        this.participants = (participants == null) ? new ArrayList<>() : new ArrayList<>(participants);
        this.privNotes = (privNotes == null) ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(privNotes);
    }

    public TripEvent() {
        this(UUID.randomUUID().toString(), null, "", null, null, null, null, null);
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

    public synchronized boolean joinTripEvent(final Person.Id personId) {
        boolean added = false;
        if (!participants.contains(personId)) {
            added = participants.add(personId);
        }
        return added;
    }

    public synchronized boolean leaveTripEvent(final Person.Id personId) {
        return participants.remove(personId);
    }

    /** Whether a bespoke editor has the components it needs; false for every legacy or free-text event. */
    @JsonIgnore
    public boolean hasDetails() {
        return details != null && !details.isEmpty();
    }

    /** One typed component, or null when it was never recorded. */
    public String detail(final Detail which) {
        return details == null ? null : details.get(which.key());
    }

    public List<Type> tripEventTypes() {
        return Arrays.stream(Type.values()).sorted(Comparator.comparing(Enum::name)).toList();
    }

    /**
     * The keys of {@link #getDetails()}. An enum rather than string constants so a caller cannot misspell one,
     * and so the stored key ({@link #key()}) can stay stable if a constant is ever renamed.
     */
    public enum Detail {
        FROM("from"),
        TO("to"),
        FLIGHT_NUMBER("flightNumber"),
        DURATION("duration"),
        CARRIER("carrier");

        private final String key;

        Detail(final String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    public enum Type {
        EVENT("Event"),
        FLIGHT("Flight"),
        GROUND("Bus, Van, Car"),
        LODGING("Lodging");

        @Getter
        final String displayValue;

        Type(final String text) {
            this.displayValue = text;
        }
    }
}
