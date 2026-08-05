package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.Registration;

@Slf4j
@Named("reg")
@ApplicationScoped
public class RegistrationCommands {
    private static final String ROOM = "room";

    public Registration createRegistration(final String tripId, final Person.Id userId) {
        return new Registration(tripId, userId);
    }

    public boolean saveRegistration(final Registration reg) {
        boolean result;
        try {
            result = DAO.getInstance().saveRegistration(reg);
        } catch (final RuntimeException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Error saving registration for '" + reg.getUserId() + "': " + reg.getTripId(),
                    ex.getMessage());
            log.error("Error while saving registration: ", ex);
            result = false;
        } catch (final IOException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Unable to save registration '" + reg.getUserId() + "': " + reg.getTripId(), ex.getMessage());
            log.warn("Error while saving registration: ", ex);
            result = false;
        }
        return result;
    }

    public List<Registration> getRegistrations(final String tripId) {
        try {
            return DAO.getInstance().getRegistrations(tripId);
        } catch (final RuntimeException ex) {
            log.error("Failed to get registrations for trip '" + tripId + "'!", ex);
            return Collections.emptyList();
        }
    }

    public int getNumPending(final String tripId) {
        int result = 0;
        for (final Registration reg : getRegistrations(tripId)) {
            if (reg.getStatus() == Registration.Status.PENDING) {
                result++;
            }
        }
        return result;
    }

    public Registration getRegistration(final String tripId, final Person.Id userId) {
        if (tripId == null) {
            log.error("getRegistration() called with null tripId");
            return null;
        }
        if (userId == null) {
            log.error("getRegistration() called with null userId");
            return null;
        }
        try {
            return DAO.getInstance().getRegistration(tripId, userId)
                    .orElse(createRegistration(tripId, userId));
        } catch (final RuntimeException ex) {
            log.error("Failed to get registration for user '" + userId.getValue()
                    + "' on trip '" + tripId + "'!", ex);
            return createRegistration(tripId, userId);
        }
    }

    public PersonDataValue getRoomPDV(final String tripId, final Person.Id userId) {
        if (tripId == null) {
            log.error("getRoom() called with null tripId");
            return null;
        }
        if (userId == null) {
            log.error("getRoom() called with null userId");
            return null;
        }
        PersonDataValue pdv = PersonDataValueCommands.getPersonDataValue(userId, getTripRoomDataId(tripId));
        if (pdv == null) {
            pdv = PersonDataValueCommands.createPersonDataValue(userId, getTripRoomDataId(tripId), ROOM);
            pdv.setContent("");
            PersonDataValueCommands.savePersonDataValue(pdv);
        }
        return pdv;
    }

    /**
     * Stores one person's room for a trip.
     *
     * <p>The room value is passed in rather than read back out of a previously-returned object, and that is the
     * whole point of the signature. The page used to bind an input straight to
     * {@code getRoomPDV(...).content} and then call a no-value {@code saveRoom}, which looked up the record
     * <em>again</em> and saved that. Two lookups return two objects: since the persistence redesign a read
     * deserializes a fresh one rather than handing back a shared instance, so the typed value was set on an
     * object nobody stored. The save reported success and the room reverted on reload.
     *
     * <p>The page comment at the time -- "must bind this way for the save button to work, needs to bind to the
     * real value" -- records exactly the assumption that stopped holding.
     */
    public boolean saveRoom(final String tripId, final Person.Id userId, final String room) {
        if (tripId == null || userId == null) {
            log.error("saveRoom() requires both a tripId and a userId");
            return false;
        }
        final PersonDataValue pdv = getRoomPDV(tripId, userId);
        if (pdv == null) {
            return false;
        }
        pdv.setContent((room == null) ? "" : room);
        return PersonDataValueCommands.savePersonDataValue(pdv);
    }

    private DataId getTripRoomDataId(final String tripId) {
        return DataId.from(ROOM + tripId);
    }
}
