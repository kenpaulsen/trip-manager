package org.paulsens.trip.action;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Person.Sex;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.util.RandomData;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import org.testng.annotations.Test;

public class TransactionsCommandsTest {
    final TransactionsCommands txCmds = new TransactionsCommands();
    final PersonCommands personCmds = new PersonCommands();

    /** The ledger table resolves per request and its delete link decodes by row position. */
    @Test
    public void getTransactionsSortedAnswersADeterministicMutableDateOrder() throws Exception {
        final Person.Id owner = createPerson();
        final LocalDateTime base = LocalDateTime.of(2026, 8, 1, 12, 0);
        for (final int day : new int[] {20, 5, 12}) {
            org.paulsens.trip.dynamo.DAO.getInstance().saveTransaction(new Transaction(
                    RandomData.genAlpha(8), owner, null, Transaction.Type.Tx,
                    Transaction.TransactionType.Bill, base.plusDays(day), 10f, "cat", "note"));
        }
        final List<Transaction> sorted = txCmds.getTransactionsSorted(owner);
        assertEquals(sorted.size(), 3);
        assertEquals(sorted.get(0).getTxDate(), base.plusDays(5));
        assertEquals(sorted.get(1).getTxDate(), base.plusDays(12));
        assertEquals(sorted.get(2).getTxDate(), base.plusDays(20));
        sorted.add(sorted.get(0));      // PrimeFaces sorts the value list in place: it must be mutable
        assertEquals(txCmds.getTransactionsSorted(null), List.of(), "Null user answers an empty list");
    }

    @Test
    public void getUserAmountReturnsNullWhenTxIsNull() {
        final Transaction tx = txCmds.getTransaction(Person.Id.from("foo"), null);
        final Float amount = txCmds.getUserAmount(tx);
        assertNull(amount);
    }

    @Test
    public void getUserAmountReturnsSplitValue() {
        final String sharedGroup = RandomData.genAlpha(8);
        final String cat = RandomData.genAlpha(9);
        final String note = RandomData.genAlpha(15);
        final float amount = -103.5f;
        final Person.Id p1 = createPerson();
        txCmds.saveGroupTx(sharedGroup, List.of(), Transaction.Type.Shared, Transaction.TransactionType.Bill,
                LocalDateTime.now(), amount, cat, note, null /* tripId */, null /* eventId */,
                p1, createPerson(), createPerson(), createPerson());
        // Membership is stamped on each row -- read it off any member's tx
        final Transaction memberTx = txCmds.getGroupTransactionForUser(p1, sharedGroup).orElse(null);
        final List<Person.Id> groupUsers = txCmds.getUserIdsForGroup(memberTx);
        assertEquals(groupUsers.size(), 4);
        final Transaction tx0 = txCmds.getGroupTransactionForUser(groupUsers.get(0), sharedGroup).orElse(null);
        assertEquals((float) txCmds.getUserAmount(tx0), amount / 4);
        final Transaction tx1 = txCmds.getGroupTransactionForUser(groupUsers.get(1), sharedGroup).orElse(null);
        assertEquals((float) txCmds.getUserAmount(tx1), amount / 4);
        final Transaction tx2 = txCmds.getGroupTransactionForUser(groupUsers.get(2), sharedGroup).orElse(null);
        assertEquals((float) txCmds.getUserAmount(tx2), amount / 4);
        final Transaction tx3 = txCmds.getGroupTransactionForUser(groupUsers.get(3), sharedGroup).orElse(null);
        assertEquals((float) txCmds.getUserAmount(tx3), amount / 4);
    }

    private Person.Id createPerson() {
        final Person.Id id = Person.Id.newInstance();
        personCmds.savePerson(new Person(id, "preferredName", "first", "middle", "last", Sex.Female, LocalDate.now(),
                null, null, null, null, null, null, null, null, null, null));
        return id;
    }
}