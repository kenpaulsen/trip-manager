package org.paulsens.trip.action;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.enterprise.context.ApplicationScoped;
import javax.faces.application.FacesMessage;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.model.Person;

@Slf4j
@Named("people")
@Singleton
@ApplicationScoped
public class PersonCommands {
    public Person createPerson() {
        return new Person();
    }

    public boolean savePerson(final Person person) {
        boolean result;
        try {
             result = DynamoUtils.getInstance().savePerson(person).exceptionally(ex -> {
                    TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Error saving: " + person.getFirst()
                            + " " + person.getLast(), ex.getMessage());
                 log.error("Error while saving user: ", ex);
                 return false;
                }).join();
        } catch (final IOException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Unable to save: " + person.getFirst()
                    + " " + person.getLast(), ex.getMessage());
            log.error("Error while saving user: ", ex);
            result = false;
        }
        return result;
    }

    public List<Person> getPeople() {
        return DynamoUtils.getInstance().getPeople()
                .exceptionally(ex -> {
                    log.error("Failed to get list of people!", ex);
                    return Collections.emptyList();
                })
                .join();
    }

    public Person getPerson(final Person.Id id) {
        return DynamoUtils.getInstance().getPerson(id)
                .exceptionally(ex -> {
                    log.error("Failed to get person '" + id + "'!", ex);
                    return Optional.empty();
                }).join().orElse(new Person());
    }

    public Person getPersonByEmail(final String email) {
        return DynamoUtils.getInstance().getPersonByEmail(email)
                .exceptionally(ex -> {
                    log.error("Exception while trying to find person with email: " + email);
                    throw new IllegalStateException(ex);
                }).join();
    }

    public Person.Id id(final String id) {
        return Person.Id.from(id);
    }
}
