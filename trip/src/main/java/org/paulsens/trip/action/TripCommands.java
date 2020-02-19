package org.paulsens.trip.action;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.enterprise.context.ApplicationScoped;
import javax.faces.application.FacesMessage;
import javax.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.model.Trip;

@Slf4j
@Named("trip")
@ApplicationScoped
public class TripCommands {
    public Trip createTrip() {
        return new Trip();
    }

    public boolean saveTrip(final Trip trip) {
        boolean result;
        try {
             result = DynamoUtils.getInstance().saveTrip(trip).exceptionally(ex -> {
                    TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Error saving '" + trip.getId()
                            + "': " + trip.getTitle(), ex.getMessage());
                     log.error("Error while saving trip: ", ex);
                    return false;
                }).join();
        } catch (final IOException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Unable to save '" + trip.getId() + "': "
                    + trip.getTitle(), ex.getMessage());
            log.warn("Error while saving trip: ", ex);
            result = false;
        }
        return result;
    }

    public List<Trip> getTrips() {
        return DynamoUtils.getInstance().getTrips()
                .exceptionally(ex -> {
                    log.error("Failed to get list of trips!", ex);
                    return Collections.emptyList();
                }).join();
    }

    public Trip getTrip(final String id) {
        return DynamoUtils.getInstance().getTrip(id)
                .exceptionally(ex -> {
                    log.error("Failed to get trip '" + id + "'!", ex);
                    return Optional.empty();
                }).join().orElse(new Trip());
    }
}
