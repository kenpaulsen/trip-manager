package org.paulsens.trip.action;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.testutil.TestData;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import org.testng.annotations.Test;

public class TransactionsCommandsTest {
    final TransactionsCommands txCmds = new TransactionsCommands();
    final PersonCommands personCmds = new PersonCommands();

    @Test
    public void getUserAmountReturnsNullWhenTxIsNull() {
        final Transaction tx = txCmds.getTransaction("foo", null);
        final Float amount = txCmds.getUserAmount(tx);
        assertNull(amount);
    }

    @Test
    public void getUserAmountReturnsSplitValue() {
        final String sharedGroup = TestData.genAlpha(8);
        final String cat = TestData.genAlpha(9);
        final String note = TestData.genAlpha(15);
        final float amount = -103.5f;
        txCmds.saveGroupTx(sharedGroup, Transaction.Type.Shared, LocalDateTime.now(), amount, cat, note,
                createPerson(), createPerson(), createPerson(), createPerson());
        final List<String> groupUsers = txCmds.getUserIdsForGroupId(sharedGroup);
        assertEquals(groupUsers.size(), 4);
        final Transaction tx0 = txCmds.getGroupTransactionForUser(groupUsers.get(0), sharedGroup).orElse(null);
        assertEquals(txCmds.getUserAmount(tx0), amount / 4);
        final Transaction tx1 = txCmds.getGroupTransactionForUser(groupUsers.get(1), sharedGroup).orElse(null);
        assertEquals(txCmds.getUserAmount(tx1), amount / 4);
        final Transaction tx2 = txCmds.getGroupTransactionForUser(groupUsers.get(2), sharedGroup).orElse(null);
        assertEquals(txCmds.getUserAmount(tx2), amount / 4);
        final Transaction tx3 = txCmds.getGroupTransactionForUser(groupUsers.get(3), sharedGroup).orElse(null);
        assertEquals(txCmds.getUserAmount(tx3), amount / 4);
    }

        private String createPerson() {
            final String id = UUID.randomUUID().toString();
            personCmds.savePerson(new Person(id, "first", "middle", "last", LocalDate.now(),
                    null, null, null, null, null, null));
            return id;
        }
    }