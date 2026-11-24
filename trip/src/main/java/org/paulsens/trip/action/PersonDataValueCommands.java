package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.cache.Cached;

@Slf4j
@Named("pdv")
@ApplicationScoped
// Not a utility class despite the all-static members: it is a CDI bean, and @ApplicationScoped proxying
// requires a public no-arg constructor, so the private constructor the check wants is impossible here.
@SuppressWarnings("checkstyle:HideUtilityClassConstructor")
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

    private static String saveError(final PersonDataValue pdv) {
        return Text.get("feedback.personDataValueCommands.saveError", pdv.getDataId(), pdv.getType(), pdv.getUserId());
    }

    public static boolean savePersonDataValue(final PersonDataValue pdv) {
        return PageSaves.saveOrGrowl(() -> DAO.getInstance().savePersonDataValue(pdv), () -> saveError(pdv),
                () -> saveError(pdv), "PersonDataValue");
    }

    public static Map<DataId, PersonDataValue> getPersonDataValues(final Person.Id userId) {
        try {
            return DAO.getInstance().getPersonDataValues(userId, Cached.NO);
        } catch (final RuntimeException ex) {
            log.error("Failed to get PersonDataValues for user: '" + userId + "'!", ex);
            return new HashMap<>();
        }
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
        try {
            return DAO.getInstance().getPersonDataValue(userId, pdvId, Cached.NO).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Failed to get PersonDataValue for user '" + userId + "' with id '" + pdvId + "'!", ex);
            return null;
        }
    }
}
