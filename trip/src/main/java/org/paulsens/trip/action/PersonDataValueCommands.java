package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;

@Slf4j
@Named("pdv")
@ApplicationScoped
public class PersonDataValueCommands {
    private static final long DYNAMO_TIMEOUT = 5_000L;

    // FIXME: This was copied from RegistrationCommands... need to think through what commands we need and write them
    public static PersonDataValue createPersonDataValue(
            final Person.Id userId, final DataId pdvId, final String type) {
        return PersonDataValue.builder()
                .userId(userId)
                .dataId(pdvId)
                .type(type)
                .content("")
                .build();
    }

    public static boolean savePersonDataValue(final PersonDataValue pdv) {
        boolean result;
        try {
            result = DAO.getInstance()
                    .savePersonDataValue(pdv)
                    .orTimeout(DYNAMO_TIMEOUT, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                                "Error saving PersonDataValue '" + pdv.getDataId() + "' of type '" + pdv.getType()
                                        + "' for user: '" + pdv.getUserId() + "'!",
                                ex.getMessage());
                        log.error("Error while saving PersonDataValue: ", ex);
                        return false;
                    }).join();
        } catch (final IOException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Error saving PersonDataValue '" + pdv.getDataId() + "' of type '" + pdv.getType()
                            + "' for user: '" + pdv.getUserId() + "'!", ex.getMessage());
            log.warn("Error while saving PersonDataValue: ", ex);
            result = false;
        }
        return result;
    }

    public static Map<DataId, PersonDataValue> getPersonDataValues(final Person.Id userId) {
        return DAO.getInstance()
                .getPersonDataValues(userId)
                .orTimeout(DYNAMO_TIMEOUT, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    log.error("Failed to get PersonDataValues for user: '" + userId + "'!", ex);
                    return new HashMap<>();
                }).join();
    }

    public static PersonDataValue getPersonDataValue(final Person.Id userId, final DataId pdvId) {
        if (userId == null) {
            log.error("getPersonDataValue() called with null userId.");
            return null;
        }
        if (pdvId == null) {
            log.error("getPersonDataValue() called with null PersonDataValue ID.");
            return null;
        }
        return DAO.getInstance()
                .getPersonDataValue(userId, pdvId)
                .orTimeout(DYNAMO_TIMEOUT, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    log.error("Failed to get PersonDataValue for user '" + userId + "' with id '" + pdvId + "'!", ex);
                    return Optional.empty();
                }).join().orElse(null);
    }
}
