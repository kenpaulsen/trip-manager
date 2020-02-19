package org.paulsens.trip.action;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.enterprise.context.ApplicationScoped;
import javax.faces.application.FacesMessage;
import javax.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;

@Slf4j
@Named("people")
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

    public Transaction createTransaction(final String userId) {
        return new Transaction(userId, OffsetDateTime.now(ZoneId.of("America/Los_Angeles")), null, "", "");
    }

    public Transaction createTransaction(final String userId, final OffsetDateTime date) {
        final Transaction tx = createTransaction(userId);
        tx.setTxDate(date);
        return tx;
    }

    public boolean saveTransaction(final Transaction tx) {
        // FIXME: Add Validations
        boolean result;
        try {
            result = DynamoUtils.getInstance().saveTransaction(tx).exceptionally(ex -> {
                    TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                            "Unable to save transaction for userId: " + tx.getUserId(), ex.getMessage());
                    log.error("Error while saving transaction: ", ex);
                    return false;
                }).join();
        } catch (final IOException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Unable to save transaction for userId: "
                    + tx.getUserId(), ex.getMessage());
            log.error("Error while saving transaction: ", ex);
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
                    return Optional.empty();
                }).join().orElse(new Person());
    }

    public List<Transaction> getTransactions(final String userId) {
        return DynamoUtils.getInstance().getTransactions(userId)
                .exceptionally(ex -> {
                    log.error("Error querying transactions for user " + userId + ": ", ex);
                    return Collections.emptyList();
                }).join();
    }

    public Transaction getTransactionStr(final String id, final String dateStr) {
        final OffsetDateTime date = ((dateStr == null) || dateStr.isEmpty()) ?
                null : OffsetDateTime.parse(dateStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return getTransaction(id, date);
    }

    public Transaction getTransaction(final String id, final OffsetDateTime date) {
        if (date == null) {
            return createTransaction(id);
        }
        return DynamoUtils.getInstance().getTransaction(id, date)
                .exceptionally(ex -> {
                    log.error("Error while getting person: '" + id + "'!", ex);
                    return Optional.empty();
                })
                .thenApply(opt -> opt.orElseGet(() -> createTransaction(id, date))).join();
    }
}
