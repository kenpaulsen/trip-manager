package org.paulsens.trip.action;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.util.RandomData;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import org.testng.annotations.Test;

public class TransactionsCommandsTest {
    final TransactionsCommands txCmds = new TransactionsCommands();
    final PersonCommands personCmds = new PersonCommands();

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
        txCmds.saveGroupTx(sharedGroup, Transaction.Type.Shared, LocalDateTime.now(), amount, cat, note,
                createPerson(), createPerson(), createPerson(), createPerson());
        final List<Person.Id> groupUsers = txCmds.getUserIdsForGroupId(sharedGroup);
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
        personCmds.savePerson(new Person(id, "preferredName", "first", "middle", "last", LocalDate.now(),
                null, null, null, null, null, null, null));
        return id;
    }
}