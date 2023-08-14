package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Transaction.Type;
import org.paulsens.trip.model.Trip;

@Slf4j
@Named("txCmds")
@ApplicationScoped
public class TransactionsCommands {
    @Inject
    private BindingCommands bind;

    public Transaction createTransaction(final Person.Id userId) {
        return new Transaction(userId, null, null);
    }

    public boolean saveTransaction(final Transaction tx) {
        // FIXME: Maybe move sending email to here? Be careful on batch Tx to not spam yourself!
        // FIXME: Look where this is called from
        // FIXME: Add Validations
        boolean result;
        try {
            result = DAO.getInstance()
                    .saveTransaction(tx)
                    .exceptionally(ex -> {
                        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                                "Unable to save transaction for userId: " + tx.getUserId().getValue(), ex.getMessage());
                        log.error("Error while saving transaction: ", ex);
                        return false;
                    }).join();
        } catch (final IOException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Unable to save transaction for userId: "
                    + tx.getUserId().getValue(), ex.getMessage());
            log.error("Error while saving transaction: ", ex);
            result = false;
        }
        return result;
    }

    public List<Transaction> getTransactions(final Person.Id userId) {
        return DAO.getInstance().getTransactions(userId)
                .exceptionally(ex -> {
                    log.error("Error querying transactions for user " + userId + ": ", ex);
                    return Collections.emptyList();
                }).join();
    }

    public List<Transaction> getTripTransactions(final String tripId) {
        return DAO.getInstance().getTrip(tripId)
                .thenApply(optTrip -> optTrip.map(Trip::getPeople))
                .thenApply(optPeople -> optPeople.orElse(List.of()))
                .thenApply(people -> people.stream().flatMap(id -> getTransactions(id).stream()).toList())
                .join();
    }

    public Transaction getTransaction(final Person.Id userId, final String txId) {
        if (userId == null) {
            throw new IllegalArgumentException("You must provide the userId!");
        }
        if (isNullOrEmpty(txId)) {
            // Create a new Tx
            return createTransaction(userId);
        }
        return DAO.getInstance().getTransaction(userId, txId)
                .exceptionally(ex -> {
                    log.error("Error while getting Tx (" + txId + ") for userId: '" + userId + "'!", ex);
                    return Optional.empty();
                }).join().orElse(null);
    }

    public Transaction getBoundTransaction(final String id, final String bindingType) {
        final BindingCommands bind = getBind();
        // Note: Tx's are keyed by people as part of the primary key, so we need both (i.e. "userId:txId")
        return bind.getBoundThing(id, bindingType, BindingType.TRANSACTION,
                comboKey -> bind.compositeKeyGetter(comboKey, (k1, k2) -> getTransaction(Person.Id.from(k1), k2)));
    }

    public boolean saveGroupTx(final String gid, final Type type, final Transaction.TransactionType txType,
                final LocalDateTime date, final Float amount, final String cat, final String note, final String tripId,
                final String eventId, final Object... objArr) {
        final List<Person.Id> txPeople = (objArr == null) ? Collections.emptyList() :
                Arrays.stream(objArr).flatMap(this::castToPersonId).toList();
        final String groupId = isNullOrEmpty(gid) ? UUID.randomUUID().toString() : gid;
        final AtomicBoolean result = new AtomicBoolean(true);

        // Find existing that should no longer be part of this, delete their existing tx
        final List<Person.Id> existing = getUserIdsForGroupId(groupId);
        existing.stream().filter(uid -> !txPeople.contains(uid))
                .map(uid -> getGroupTransactionForUser(uid, groupId))
                .forEach(optTx -> optTx.ifPresent(tx -> {
                    tx.delete();
                    if (!saveTransaction(updateTx(tx, date, amount, txType, cat, note)))  {
                        log.error("Unable to delete group tx ({}) with note: {}", groupId, note);
                        result.set(false);
                    }
                }));

        // Find existing, update or create their tx (our "existing" variable doesn't contain deleted, search 1 by 1)
        txPeople.forEach(uid -> persistTx(updateTx(getGroupTransactionForUser(uid, groupId)
                .orElseGet(() -> new Transaction(uid, groupId, type)),
                date, amount, txType, cat, note), tripId, eventId, result));

        return result.get();
    }

    private void persistTx(final Transaction tx, final String tripId, final String eventId, final AtomicBoolean r) {
        final BindingCommands bind = getBind();
        final String txBindKey = bind.key(tx.getUserId().getValue(), tx.getTxId());
        tx.setDeleted(null); // Ensure not deleted
        if (saveTransaction(tx)) {
            // Now save any binding(s)
            if ((tripId != null) && !tripId.isEmpty()) {
                bind.setBindings(txBindKey, BindingType.TRANSACTION, BindingType.TRIP, List.of(tripId), true);
                if ((eventId != null) && !eventId.isEmpty()) {
                    bind.setBindings(txBindKey, BindingType.TRANSACTION, BindingType.TRIP_EVENT, List.of(eventId), true);
                }
            }
        } else {
            log.error("Unable to save group tx ({}) with note: {}", tx.getGroupId(), tx.getNote());
            r.set(false);
        }
    }

    private Stream<Person.Id> castToPersonId(final Object thing) {
        if (thing instanceof Person.Id) {
            return Stream.of((Person.Id) thing);
        }
        if (thing instanceof String) {
            return Stream.of(Person.Id.from(thing.toString()));
        }
        if (thing instanceof Object[]) {
            return Arrays.stream((Object[]) thing).flatMap(this::castToPersonId);
        }
        if (thing instanceof Collection<?>) {
            return ((Collection<?>) thing).stream().flatMap(this::castToPersonId);
        }
        throw new IllegalArgumentException("Can't turn " + thing.getClass().getName() + " into a Person.Id");
    }

    /**
     * Returns the {@link Transaction} for the given userId if it exists. It <em>WILL</em> return deleted
     * {@code Transaction}s.
     * @param userId    The user to search.
     * @param groupId   The groupId to match.
     * @return  Optionally the matching {@code Transaction}.
     */
    public Optional<Transaction> getGroupTransactionForUser(final Person.Id userId, final String groupId) {
        return DAO.getInstance().getTransactions(userId).thenApply(
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
        return DAO.getInstance().getPeople()
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

    private Transaction updateTx(final Transaction tx, final LocalDateTime date, final Float amount,
            final Transaction.TransactionType txType, final String cat, final String note) {
        tx.setTxDate(date);
        tx.setAmount(amount);
        tx.setTxType(txType);
        tx.setCategory(cat);
        tx.setNote(note);
        return tx;
    }

    private CompletableFuture<Boolean> hasGroupTransaction(final Person.Id userId, final String groupId) {
        return DAO.getInstance().getTransactions(userId).thenApply(
                txs -> txs.stream().anyMatch(tx -> groupId.equals(tx.getGroupId()) && (tx.getDeleted() == null)));
    }

    public BindingCommands getBind() {
        if (bind == null) {
            log.warn("Did not getting BindingCommands injected!");
            bind = new BindingCommands();
        }
        return bind;
    }

    private boolean isNullOrEmpty(final String str) {
        return (str == null) || str.isEmpty();
    }
}
