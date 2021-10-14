package org.paulsens.trip.action;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;

@Slf4j
@Named("reg")
@ApplicationScoped
public class RegistrationCommands {
    public Registration createRegistration(final String tripId, final Person.Id userId) {
        return new Registration(tripId, userId);
    }

    public boolean saveRegistration(final Registration reg) {
        boolean result;
        try {
             result = DynamoUtils.getInstance().saveRegistration(reg)
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
        return DynamoUtils.getInstance()
                .getRegistrations(tripId)
                .exceptionally(ex -> {
                    log.error("Failed to get registrations for trip '" + tripId + "'!", ex);
                    return Collections.emptyList();
                }).join();
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
        return DynamoUtils.getInstance()
                .getRegistration(tripId, userId)
                .exceptionally(ex -> {
                    log.error("Failed to get registration for user '" + userId.getValue()
                            + "' on trip '" + tripId + "'!", ex);
                    return Optional.empty();
                }).join().orElse(createRegistration(tripId, userId));
    }
}
