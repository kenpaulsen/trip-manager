package org.paulsens.trip.action;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.TripEvent;

/**
 * The trip-event dialog's edit buffer: what {@code viewScope.evtForm} holds from the moment an opener runs until
 * Add/Apply or Cancel clears it. One form serves every kind of event in both modes -- the section the dialog
 * shows follows {@link #getSection()}, and the values of every section ride along so switching the Type menu
 * mid-entry loses nothing typed.
 *
 * <p>A form, not the event: the rule for these pages is that viewScope holds scalars or small Serializable
 * buffers, never a domain object (a {@code TripEvent} in a view is a {@code TripEvent} in the session, and the
 * next change to its shape is then a site-wide 500 for every returning visitor -- the 2026-08-14 outage). The
 * event is named by {@link #eventId} and resolved from the working copy on every Apply. Same shape as
 * {@code viewScope.offerForm} on the Lodging tab. No state is established in a constructor: the session's
 * serializer runs none, so every field must be safe at its Java default.
 */
@Data
@NoArgsConstructor
public class TripEventForm implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    public static final String ADD = "ADD";
    public static final String EDIT = "EDIT";

    /** {@link #ADD} or {@link #EDIT}. */
    private String mode;
    /** The working copy's event being edited; null while adding. */
    private String eventId;
    /** The {@link TripEvent.Type} NAME the Type menu holds. */
    private String type;
    /**
     * An existing event with no stored components: it opens in the generic editor whatever its type, and picking
     * a type in the menu opts it into the bespoke one ({@code TripCommands.retypeEventForm}).
     */
    private boolean generic;
    private String title;
    private String notes;
    private String from;
    private String to;
    private String flightNumber;
    private String carrier;
    private String duration;
    private LocalDateTime start;
    private LocalDateTime end;
    private List<Person.Id> participants;

    public List<Person.Id> getParticipants() {
        if (participants == null) {
            participants = new ArrayList<>();
        }
        return participants;
    }

    public boolean isEditing() {
        return EDIT.equals(mode);
    }

    /**
     * Which of the dialog's sections applies: the type's own for a flight or ground leg with components, the
     * generic form for an event, a legacy event, or an existing lodging event, and the lodging hand-off only
     * when ADDING lodging -- a lodging event's data lives on its offer, so it is never created here.
     */
    public String getSection() {
        if (generic || type == null) {
            return TripEvent.Type.EVENT.name();
        }
        if (TripEvent.Type.LODGING.name().equals(type)) {
            return isEditing() ? TripEvent.Type.EVENT.name() : TripEvent.Type.LODGING.name();
        }
        return type;
    }

    public boolean isFlight() {
        return TripEvent.Type.FLIGHT.name().equals(getSection());
    }

    public boolean isGround() {
        return TripEvent.Type.GROUND.name().equals(getSection());
    }

    public boolean isRoute() {
        return isFlight() || isGround();
    }

    public boolean isGenericSection() {
        return TripEvent.Type.EVENT.name().equals(getSection());
    }

    public boolean isLodgingCreate() {
        return TripEvent.Type.LODGING.name().equals(getSection());
    }

    /** Whether the event is (or is becoming) a lodging event, for the hint that points at the Lodging tab. */
    public boolean isLodging() {
        return TripEvent.Type.LODGING.name().equals(type);
    }

    /** The dialog's heading: "Add Flight", "Edit Ground Transport", "Add Lodging", "Edit Event". */
    public String getHeading() {
        return (isEditing() ? "Edit " : "Add ") + kindLabel();
    }

    private String kindLabel() {
        if (type == null) {
            return "Event";
        }
        if (TripEvent.Type.GROUND.name().equals(type)) {
            return "Ground Transport";
        }
        try {
            return TripEvent.Type.valueOf(type).getDisplayValue();
        } catch (final IllegalArgumentException ex) {
            return "Event";
        }
    }
}
