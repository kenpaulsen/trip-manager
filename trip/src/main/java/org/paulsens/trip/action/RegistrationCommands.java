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
             result = DAO.getInstance().saveRegistration(reg)
                     .exceptionally(ex -> {
                             TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                                     "Error saving registration for '" + reg.getUserId() + "': " + reg.getTripId(),
                                     ex.getMessage());
                             log.error("Error while saving registration: ", ex);
                            return false;
                        }).join();
        } catch (final IOException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Unable to save registration '" + reg.getUserId() + "': " + reg.getTripId(), ex.getMessage());
            log.warn("Error while saving registration: ", ex);
            result = false;
        }
        return result;
    }

    public List<Registration> getRegistrations(final String tripId) {
        return DAO.getInstance()
                .getRegistrations(tripId)
                .exceptionally(ex -> {
                    log.error("Failed to get registrations for trip '" + tripId + "'!", ex);
                    return Collections.emptyList();
                }).join();
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
        return DAO.getInstance()
                .getRegistration(tripId, userId)
                .exceptionally(ex -> {
                    log.error("Failed to get registration for user '" + userId.getValue()
                            + "' on trip '" + tripId + "'!", ex);
                    return Optional.empty();
                }).join().orElse(createRegistration(tripId, userId));
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

    public void saveRoom(final String tripId, final Person.Id userId) {
        if (tripId == null) {
            log.error("setRoom() called with null tripId");
        }
        if (userId == null) {
            log.error("setRoom() called with null userId");
        }
        final PersonDataValue pdv = PersonDataValueCommands.getPersonDataValue(userId, getTripRoomDataId(tripId));
        if (pdv != null) {
            PersonDataValueCommands.savePersonDataValue(pdv);
        }
    }

    private DataId getTripRoomDataId(final String tripId) {
        return DataId.from(ROOM + tripId);
    }
}
