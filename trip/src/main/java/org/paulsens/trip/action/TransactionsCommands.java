package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.enterprise.context.ApplicationScoped;
import javax.faces.application.FacesMessage;
import javax.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.model.Transaction;

@Slf4j
@Named("txCmds")
@ApplicationScoped
public class TransactionsCommands {
    public Transaction createTransaction(final String userId) {
        return new Transaction(userId);
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

    public boolean saveBatch(final LocalDateTime date, final float amount, final String cat,
            final String note, final String ... people) {
        if (people == null) {
            return true;
        }
        for (final String person : people) {
            if (!saveTransaction(new Transaction(null, person, date, amount, cat, note))) {
                return false;
            }
        }
        return true;
    }

    public List<Transaction> getTransactions(final String userId) {
        return DynamoUtils.getInstance().getTransactions(userId)
                .exceptionally(ex -> {
                    log.error("Error querying transactions for user " + userId + ": ", ex);
                    return Collections.emptyList();
                }).join();
    }

    public Transaction getTransaction(final String userId, final String txId) {
        if ((userId == null) || userId.isEmpty()) {
            throw new IllegalArgumentException("You must provide the userId!");
        }
        if ((txId == null)  || txId.isEmpty()) {
            // Create a new Tx
            return createTransaction(userId);
        }
        return DynamoUtils.getInstance().getTransaction(userId, txId)
                .exceptionally(ex -> {
                    log.error("Error while getting Tx (" + txId + ") for userId: '" + userId + "'!", ex);
                    return Optional.empty();
                }).join().orElseThrow(
                        () -> new IllegalArgumentException("Tx (" + txId + ") not found for user (" + userId + ")."));
    }
}
