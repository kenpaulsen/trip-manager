package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import javax.enterprise.context.ApplicationScoped;
import javax.faces.application.FacesMessage;
import javax.faces.context.FacesContext;
import javax.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
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
        } catch (final IOException ex) {
            addMessage("Unable to save " + person.getId() + ": " + person.getFirst() + " " + person.getLast());
            log.warn("Error while saving user: ", ex);
            result = false;
        }
        return result;
    }

    public Transaction createTransaction(final String userId) {
        return new Transaction(userId, OffsetDateTime.now(ZoneId.of("America/Los_Angeles")), 0.0f, "Hotel", "");
    }

    public boolean saveTransaction(final Transaction tx) {
        boolean result = true;
        try {
            DynamoUtils.getInstance().saveTransaction(tx)
                    .thenApply(PutItemResponse::toString)
                    .exceptionally(ex -> {
                        log.error("Boom: ", ex);
                        return "FAILED!";
                    });
        } catch (final IOException ex) {
            addMessage("Unable to save transaction for userId: " + tx.getUserId());
            log.warn("Error while saving transaction: ", ex);
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

    public List<Transaction> getTransactions(final String userId) {
        return DynamoUtils.getInstance().getTransactions(userId)
                .exceptionally(ex -> {
                    log.error("Error querying transactions for user " + userId + ": ", ex);
                    return Collections.emptyList();
                }).join();
    }

    public void addMessage(final String msg) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(msg));
    }

    public void addMessage(final String msg, final Exception ex) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(msg, ex.getMessage()));
        log.error(msg, ex);
    }
}
