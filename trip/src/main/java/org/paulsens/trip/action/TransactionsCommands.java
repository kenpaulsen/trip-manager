package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.enterprise.context.ApplicationScoped;
import javax.faces.application.FacesMessage;
import javax.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Transaction.Type;

@Slf4j
@Named("txCmds")
@ApplicationScoped
public class TransactionsCommands {
    public Transaction createTransaction(final Person.Id userId) {
        return new Transaction(userId, null, null);
    }

    public boolean saveTransaction(final Transaction tx) {
        // FIXME: Add Validations
        boolean result;
        try {
            result = DynamoUtils.getInstance()
                    .saveTransaction(tx)
                    .exceptionally(ex -> {
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

    public List<Transaction> getTransactions(final Person.Id userId) {
        return DynamoUtils.getInstance().getTransactions(userId)
                .exceptionally(ex -> {
                    log.error("Error querying transactions for user " + userId + ": ", ex);
                    return Collections.emptyList();
                }).join();
    }

    public Transaction getTransaction(final Person.Id userId, final String txId) {
        if (userId == null) {
            throw new IllegalArgumentException("You must provide the userId!");
        }
        if (isNullOrEmpty(txId)) {
            // Create a new Tx
            return createTransaction(userId);
        }
        return DynamoUtils.getInstance().getTransaction(userId, txId)
                .exceptionally(ex -> {
                    log.error("Error while getting Tx (" + txId + ") for userId: '" + userId + "'!", ex);
                    return Optional.empty();
                }).join().orElse(null);
    }

    public boolean saveGroupTx(final String gid, final Type type, final LocalDateTime date, final Float amount,
                               final String cat, final String note, final String ... peopleArr) {
        final List<Person.Id> txPeople = peopleArr == null ? Collections.emptyList() :
                Arrays.asList(peopleArr).stream().map(Person.Id::from).collect(Collectors.toList());
        final String groupId = isNullOrEmpty(gid) ? UUID.randomUUID().toString() : gid;
        final AtomicBoolean result = new AtomicBoolean(true);

        // Find existing that should no longer be part of this, delete their existing tx
        final List<Person.Id> existing = getUserIdsForGroupId(groupId);
        existing.stream().filter(uid -> !txPeople.contains(uid))
                .map(uid -> getGroupTransactionForUser(uid, groupId))
                .forEach(optTx -> optTx.ifPresent(tx -> {
                    tx.delete();
                    if (!saveTransaction(createOrUpdateTx(tx, date, amount, cat, note)))  {
                        log.error("Unable to delete group tx ({}) with note: {}", groupId, note);
                        result.set(false);
                    }
                }));

        // Find existing, update or create their tx (our "existing" variable doesn't contained deleted, search 1 by 1)
        txPeople.forEach(uid -> {
            final Transaction old = getGroupTransactionForUser(uid, groupId)
                    .orElseGet(() -> new Transaction(uid, groupId, type));
            old.setDeleted(null); // Ensure not deleted
            if (!saveTransaction(createOrUpdateTx(old, date, amount, cat, note))) {
                log.error("Unable to save group tx ({}) with note: {}", groupId, note);
                result.set(false);
            }
        });
        return result.get();
    }

    /**
     * Returns the {@link Transaction} for the given userId if it exists. It <em>WILL</em> return deleted
     * {@code Transaction}s.
     * @param userId    The user to search.
     * @param groupId   The groupId to match.
     * @return  Optionally the matching {@code Transaction}.
     */
    public Optional<Transaction> getGroupTransactionForUser(final Person.Id userId, final String groupId) {
        return DynamoUtils.getInstance().getTransactions(userId).thenApply(
                txs -> txs.stream().filter(tx -> groupId.equals(tx.getGroupId())).findAny()).join();
    }

    /**
     * Returns the people who are part of the given {@code groupId}. Deleted {@link Transaction}s are ignored.
     * @param groupId   The {@link Type#Batch} or {@link Type#Shared} groupId.
     * @return  The userId's which share in the batch or shared transaction.
     */
    public List<Person.Id> getUserIdsForGroupId(final String groupId) {
        // FIXME: It might be nice to have each transaction associated w/ a Trip, currently it isn't so we can't
        // FIXME: limit the potential people in a Batch.
        return DynamoUtils.getInstance().getPeople()
                .thenApply(all -> all.stream().map(Person::getId)
                        .filter(userId -> hasGroupTransaction(userId, groupId).join())
                        .collect(Collectors.toList()))
                .join();
    }

    /**
     * This returns the portion of the transaction the user is responsible for. Normally it is all of the amount,
     * however, shared transactions are split between people, so it may not be all for this user.
     * @return The amount the user is responsible for.
     */
    public Float getUserAmount(final Transaction tx) {
        if (tx == null || tx.getAmount() == null) {
            return null;
        }
        return tx.isShared() ? tx.getAmount() / getUserIdsForGroupId(tx.getGroupId()).size() : tx.getAmount();
    }

    private Transaction createOrUpdateTx(
            final Transaction tx, final LocalDateTime date, final Float amount, final String cat, final String note) {
        tx.setTxDate(date);
        tx.setAmount(amount);
        tx.setCategory(cat);
        tx.setNote(note);
        return tx;
    }

    private CompletableFuture<Boolean> hasGroupTransaction(final Person.Id userId, final String groupId) {
        return DynamoUtils.getInstance().getTransactions(userId).thenApply(
                txs -> txs.stream().anyMatch(tx -> groupId.equals(tx.getGroupId()) && (tx.getDeleted() == null)));
    }

    private boolean isNullOrEmpty(final String str) {
        return (str == null) || str.isEmpty();
    }
}