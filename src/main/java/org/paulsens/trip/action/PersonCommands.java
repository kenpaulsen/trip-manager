package org.paulsens.trip.action;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import javax.enterprise.context.ApplicationScoped;
import javax.faces.application.FacesMessage;
import javax.faces.context.FacesContext;
import javax.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.model.Person;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

@Slf4j
@Named("people")
@ApplicationScoped
public class PersonCommands {
    public Person createPerson() {
        return new Person();
    }

    public boolean savePerson(final Person person) {
        boolean result = true;
        try {
            DynamoUtils.getInstance().savePerson(person)
                    .thenApply(PutItemResponse::toString)
                    .exceptionally(ex -> {
                        log.error("Boom: ", ex);
                        return "FAILED!";
                    })
                    .thenAccept(resp -> System.out.println("PUT Response: " + resp));
        } catch (IOException ex) {
            addMessage("Unable to save " + person.getId() + ": " + person.getFirst() + " " + person.getLast());
            log.warn("Error while saving user: ", ex);
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

    public Person getPerson(final String id) {
        return DynamoUtils.getInstance().getPerson(id)
                .exceptionally(ex -> {
                    log.error("Failed to get person '" + id + "'!", ex);
                    return new Person();
                })
                .join();
    }

    public void addMessage(final String msg) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(msg));
    }

    public void addMessage(final String msg, final Exception ex) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(msg, ex.getMessage()));
        log.error(msg, ex);
    }
}
