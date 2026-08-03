package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The two ways to save a group transaction.
 *
 * <p>{@code saveGroupTx} takes {@code Object...} because the batch-transaction page submits its selections
 * through PrimeFaces widgets that hand back arrays, collections and bare Strings interchangeably;
 * {@code castToPersonId} untangles that. {@code saveGroupTransaction} takes a typed list, for a caller such as a
 * REST resource that has nothing to untangle.
 *
 * <p>They are separate NAMES rather than overloads on purpose, and that is what these pin. {@code List} is more
 * specific than {@code Object...}, so an overload would silently capture every existing call site that happens
 * to pass a single list -- changing which coercion runs without changing the call, and only for some callers.
 */
public class GroupTransactionTypedEntryTest {

    private final TransactionsCommands transactions = new TransactionsCommands();

    @Test
    public void bothEntryPointsProduceTheSameMembership() {
        final Person.Id a = Person.Id.newInstance();
        final Person.Id b = Person.Id.newInstance();
        final String viaVarargs = "grp-varargs-" + System.nanoTime();
        final String viaTyped = "grp-typed-" + System.nanoTime();

        // The page's path: a collection arriving as one Object among varargs.
        Assert.assertTrue(save(viaVarargs, (Object) List.of(a, b)));
        // The API's path: an already-typed list.
        Assert.assertTrue(transactions.saveGroupTransaction(viaTyped, List.of(), Transaction.Type.Shared,
                Transaction.TransactionType.Bill, LocalDateTime.now(), 90.0f, "cat", "note", null, null,
                List.of(a, b)));

        Assert.assertEquals(membersOf(viaVarargs, a), membersOf(viaTyped, a),
                "The typed entry point must not reorder or drop members relative to the coerced one.");
        Assert.assertEquals(membersOf(viaTyped, a).size(), 2);
    }

    @Test
    public void theTypedEntryPointTreatsANullMemberListAsEmptyRatherThanThrowing() {
        // The varargs form already tolerates null (it means "nobody selected"); the typed one must agree,
        // since a JSON body with the field omitted deserializes to null.
        final String groupId = "grp-null-" + System.nanoTime();

        Assert.assertTrue(transactions.saveGroupTransaction(groupId, List.of(), Transaction.Type.Shared,
                Transaction.TransactionType.Bill, LocalDateTime.now(), 10.0f, "cat", "note", null, null, null));
    }

    @Test
    public void eachMemberOwesTheirShareOfASharedTransactionNotTheWholeAmount() {
        // Why the wire carries userAmount separately from amount: a client showing amount on a traveller's
        // ledger would tell each of three people they owe the entire bill.
        final Person.Id a = Person.Id.newInstance();
        final Person.Id b = Person.Id.newInstance();
        final Person.Id c = Person.Id.newInstance();
        final String groupId = "grp-share-" + System.nanoTime();
        transactions.saveGroupTransaction(groupId, List.of(), Transaction.Type.Shared,
                Transaction.TransactionType.Bill, LocalDateTime.now(), 600.0f, "cat", "note", null, null,
                List.of(a, b, c));

        final Transaction tx = transactions.getGroupTransactionForUser(a, groupId).orElseThrow();

        Assert.assertEquals(tx.getAmount(), 600.0f);
        Assert.assertEquals(transactions.getUserAmount(tx), 200.0f);
    }

    private boolean save(final String groupId, final Object members) {
        return transactions.saveGroupTx(groupId, List.of(), Transaction.Type.Shared,
                Transaction.TransactionType.Bill, LocalDateTime.now(), 90.0f, "cat", "note", null, null, members);
    }

    private List<Person.Id> membersOf(final String groupId, final Person.Id member) {
        return transactions.getGroupTransactionForUser(member, groupId)
                .map(transactions::getUserIdsForGroup)
                .orElse(List.of());
    }
}
