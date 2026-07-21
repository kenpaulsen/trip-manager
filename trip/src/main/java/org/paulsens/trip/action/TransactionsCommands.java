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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
        if (userId == null) {
            return List.of();
        }
        return DAO.getInstance().getTransactions(userId)
                .exceptionally(ex -> {
                    log.error("Error querying transactions for user {}: ", userId.getValue(), ex);
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
                    log.error("Error while getting Tx ({}) for userId: '{}'!", txId, userId, ex);
                    return Optional.empty();
                }).join().orElse(null);
    }

    public boolean hasTransaction(final Person.Id userId, final String txId) {
        if (userId == null || txId == null) {
            throw new IllegalArgumentException("You must provide the userId and a txId!");
        }
        return DAO.getInstance()
            .getTransaction(userId, txId)
            .exceptionally(_ -> Optional.empty())
            .orTimeout(5_000, TimeUnit.MILLISECONDS).join()
            .isPresent();
    }

    public Transaction getBoundTransaction(final String id, final String bindingType) {
        final BindingCommands bind = getBind();
        // Note: Tx's are keyed by people as part of the primary key, so we need both (i.e. "userId:txId")
        return bind.getBoundThing(id, bindingType, BindingType.TRANSACTION,
                comboKey -> bind.compositeKeyGetter(comboKey, (k1, k2) -> getTransaction(Person.Id.from(k1), k2)));
    }

    /**
     * Saves a Shared/Batch group of transactions. {@code origPeople} is the membership as it was when the page
     * loaded (from {@link #getUserIdsForGroup}); members removed from the new selection get their row deleted.
     * Every surviving row is stamped with the full member list so membership never requires probing other users.
     */
    public boolean saveGroupTx(final String gid, final List<Person.Id> origPeople, final Type type,
                final Transaction.TransactionType txType, final LocalDateTime date, final Float amount,
                final String cat, final String note, final String tripId, final String eventId,
                final Object... objArr) {
        final List<Person.Id> txPeople = (objArr == null) ? Collections.emptyList() :
                Arrays.stream(objArr).flatMap(this::castToPersonId).toList();
        final String groupId = isNullOrEmpty(gid) ? UUID.randomUUID().toString() : gid;
        final AtomicBoolean result = new AtomicBoolean(true);

        // Find existing that should no longer be part of this, delete their existing tx
        final List<Person.Id> existing = (origPeople == null) ? List.of() : origPeople;
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
        txPeople.forEach(uid -> {
            final Transaction tx = updateTx(getGroupTransactionForUser(uid, groupId)
                    .orElseGet(() -> new Transaction(uid, groupId, type)), date, amount, txType, cat, note);
            tx.setGroupPeople(txPeople);
            persistTx(tx, tripId, eventId, result);
        });

        return result.get();
    }

    public void sortTxByDate(final List<Transaction> txs) {
        txs.sort(Comparator.comparing(Transaction::getTxDate));
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
     * Returns the people who share in the given {@link Type#Batch} or {@link Type#Shared} transaction, read from
     * the membership list stamped on the row itself. Legacy rows saved before membership stamping fall back to
     * just the row's own user -- run the group-tx membership migration (see
     * {@code docs/migrations/group-tx-membership.md}) to fix those.
     */
    public List<Person.Id> getUserIdsForGroup(final Transaction tx) {
        if (tx == null) {
            return List.of();
        }
        if (tx.getGroupPeople() != null && !tx.getGroupPeople().isEmpty()) {
            return tx.getGroupPeople();
        }
        if (tx.getGroupId() != null) {
            log.warn("Group tx '{}' (group '{}') has no groupPeople -- legacy row, run the group-tx migration.",
                    tx.getTxId(), tx.getGroupId());
        }
        return List.of(tx.getUserId());
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
        return tx.isShared() ? tx.getAmount() / Math.max(1, getUserIdsForGroup(tx).size()) : tx.getAmount();
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

    public BindingCommands getBind() {
        if (bind == null) {
            log.warn("Did not get BindingCommands injected!");
            bind = new BindingCommands();
        }
        return bind;
    }

    private boolean isNullOrEmpty(final String str) {
        return (str == null) || str.isEmpty();
    }
}
