package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Deleting out of a Shared/Batch group.
 *
 * <p>The regression these exist for: {@code trip/transaction.xhtml}'s Delete button used to soft-delete one
 * row and stop there. A group's membership is stamped on every row and IS the divisor a Shared amount is
 * split by, so the survivors kept dividing by a person who no longer had a row: a $150 payment split three
 * ways still showed $50 shares afterwards, and $50 of it appeared in nobody's balance. The stale list also
 * made the group editor offer the removed person again, and saving there recreated their row under a new
 * txId -- silently undoing the delete.
 *
 * <p>So the assertion that matters is not "the row is gone" but <em>the surviving shares still add up to the
 * transaction</em>.
 */
public class GroupTransactionDeleteTest {

    private final TransactionsCommands txs = new TransactionsCommands();

    @Test
    public void removingOneMemberOfASharedGroupKeepsTheTotalAccountedFor() {
        final Person.Id anna = id("anna");
        final Person.Id ben = id("ben");
        final Person.Id cara = id("cara");
        final String gid = group(Transaction.Type.Shared, 150f, anna, ben, cara);

        Assert.assertEquals(txs.getUserAmount(row(anna, gid)), 50f, "a shared payment starts split three ways");

        Assert.assertTrue(txs.removeFromGroup(row(anna, gid)));

        Assert.assertTrue(txs.getGroupTransactionForUser(anna, gid).isEmpty(), "the removed row is gone");
        Assert.assertEquals(List.copyOf(txs.getUserIdsForGroup(row(ben, gid))), List.of(ben, cara),
                "every surviving row is restamped with who is actually left");
        Assert.assertEquals(txs.getUserAmount(row(ben, gid)) + txs.getUserAmount(row(cara, gid)), 150f,
                "THE regression: the surviving shares must still add up to the whole transaction");
        Assert.assertEquals(txs.getBalance(anna), 0d, "the person who left carries none of it");
    }

    /** A batch is independent identical charges, so a removal simply takes one charge away. */
    @Test
    public void removingOneMemberOfABatchLeavesTheOthersCharged() {
        final Person.Id anna = id("anna");
        final Person.Id ben = id("ben");
        final String gid = group(Transaction.Type.Batch, 100f, anna, ben);

        Assert.assertTrue(txs.removeFromGroup(row(anna, gid)));

        Assert.assertEquals(txs.getUserAmount(row(ben, gid)), 100f, "a batch member keeps the full charge");
        Assert.assertEquals(List.copyOf(txs.getUserIdsForGroup(row(ben, gid))), List.of(ben));
    }

    /** 0 members is no transaction: the last removal takes the whole thing, for either kind. */
    @Test
    public void removingTheLastMemberDeletesTheTransaction() {
        for (final Transaction.Type type : List.of(Transaction.Type.Batch, Transaction.Type.Shared)) {
            final Person.Id solo = id("solo-" + type);
            final String gid = group(type, 80f, solo);

            Assert.assertTrue(txs.removeFromGroup(row(solo, gid)), type + ": the last removal must succeed");
            Assert.assertTrue(txs.getGroupTransactionForUser(solo, gid).isEmpty(),
                    type + ": removing the last member leaves no transaction");
        }
    }

    /** The row's trip and event bindings go with it; they used to outlive every deleted transaction. */
    @Test
    public void aDeletedRowTakesItsBindingsWithIt() {
        final Person.Id anna = id("anna");
        final Person.Id ben = id("ben");
        final String tripId = "trip-" + UUID.randomUUID();
        final String eventId = "evt-" + UUID.randomUUID();
        final String gid = UUID.randomUUID().toString();
        Assert.assertTrue(txs.saveGroupTransaction(gid, null, Transaction.Type.Batch,
                Transaction.TransactionType.Bill, LocalDateTime.now(), 40f, "cat", "note", tripId, eventId,
                List.of(anna, ben)));

        final BindingCommands bind = txs.getBind();
        final Transaction annas = row(anna, gid);
        final String key = bind.key(anna.getValue(), annas.getTxId());
        Assert.assertFalse(bind.getBindings(key, BindingType.TRANSACTION, BindingType.TRIP).isEmpty());

        Assert.assertTrue(txs.removeFromGroup(annas));

        Assert.assertTrue(bind.getBindings(key, BindingType.TRANSACTION, BindingType.TRIP).isEmpty(),
                "an orphaned trip binding outlives the row forever; the trip ledger just null-checks the miss");
        Assert.assertTrue(bind.getBindings(key, BindingType.TRANSACTION, BindingType.TRIP_EVENT).isEmpty());
    }

    /** A group save whose new membership is empty deletes the group -- what emptying the editor now does. */
    @Test
    public void aGroupSaveWithNoMembersDeletesTheGroup() {
        final Person.Id anna = id("anna");
        final Person.Id ben = id("ben");
        final String gid = group(Transaction.Type.Shared, 200f, anna, ben);

        Assert.assertTrue(txs.saveGroupTransaction(gid, List.of(anna, ben), Transaction.Type.Shared,
                Transaction.TransactionType.Payment, LocalDateTime.now(), 200f, "cat", "note", null, null,
                List.of()));

        Assert.assertTrue(txs.getGroupTransactionForUser(anna, gid).isEmpty());
        Assert.assertTrue(txs.getGroupTransactionForUser(ben, gid).isEmpty());
    }

    /** Deleting a group by id acts on the membership the ROW carries, not on the seeds it was handed. */
    @Test
    public void deleteGroupByIdUsesTheStampedMembershipNotTheSeeds() {
        final Person.Id anna = id("anna");
        final Person.Id ben = id("ben");
        final Person.Id cara = id("cara");
        final String gid = group(Transaction.Type.Batch, 25f, anna, ben, cara);

        // Only ONE seed, as a page that loaded a stale member list would supply.
        Assert.assertTrue(txs.deleteGroupById(gid, List.of(anna)));

        Assert.assertTrue(txs.getGroupTransactionForUser(ben, gid).isEmpty(),
                "a member the caller never named must still lose their row");
        Assert.assertTrue(txs.getGroupTransactionForUser(cara, gid).isEmpty());
        Assert.assertTrue(txs.deleteGroupById(gid, List.of(anna)), "an already-empty group is the requested state");
        Assert.assertFalse(txs.deleteGroupById("", List.of(anna)), "no group id, nothing to delete");
    }

    /** Each entry point refuses the shape it cannot handle; deleteForPerson is what pages should call. */
    @Test
    public void theEntryPointsRefuseTheWrongShapeAndDeleteForPersonDispatches() {
        final Person.Id anna = id("anna");
        final String gid = group(Transaction.Type.Shared, 60f, anna, id("ben"));

        Assert.assertFalse(txs.deleteTransaction(row(anna, gid)),
                "a group row deleted as a plain one is exactly the bug this closes");
        Assert.assertFalse(txs.removeFromGroup(null));
        Assert.assertFalse(txs.deleteGroup(null));
        Assert.assertFalse(txs.deleteTransaction(null));
        Assert.assertFalse(txs.deleteForPerson(null));

        final Transaction plain = new Transaction(anna, null, Transaction.Type.Tx);
        plain.setAmount(10f);
        Assert.assertTrue(txs.saveTransaction(plain));
        Assert.assertFalse(txs.removeFromGroup(plain), "a plain row is not a group");
        Assert.assertTrue(txs.deleteForPerson(plain), "deleteForPerson dispatches a plain row to the plain path");
        Assert.assertTrue(txs.getTransactions(anna).stream()
                .noneMatch(tx -> tx.getTxId().equals(plain.getTxId())));

        Assert.assertTrue(txs.deleteForPerson(row(anna, gid), AuditActor.current()),
                "and a group row to removeFromGroup");
    }

    /** Only an EXISTING group emptied of everyone needs the confirmation; nothing else does. */
    @Test
    public void needsGroupDeleteConfirmOnlyForAnExistingGroupWithNobodyLeft() {
        Assert.assertTrue(txs.needsGroupDeleteConfirm("g1", List.of()));
        Assert.assertTrue(txs.needsGroupDeleteConfirm("g1", null));
        Assert.assertFalse(txs.needsGroupDeleteConfirm("g1", List.of(id("anna"))),
                "somebody is still selected, so this is an ordinary save");
        Assert.assertFalse(txs.needsGroupDeleteConfirm(null, List.of()),
                "a NEW group with nobody in it is a mistake to growl about, not a transaction to delete");
        Assert.assertFalse(txs.needsGroupDeleteConfirm("", List.of()));
    }

    /** A legacy row carries no membership, so it is the only member this code can see. */
    @Test
    public void aLegacyRowWithNoStampedMembershipDeletesItself() {
        final Person.Id solo = id("legacy");
        final Transaction legacy = new Transaction(solo, "legacy-group-" + UUID.randomUUID(),
                Transaction.Type.Shared);
        legacy.setAmount(70f);
        Assert.assertTrue(txs.saveTransaction(legacy));

        Assert.assertTrue(txs.removeFromGroup(legacy));
        Assert.assertTrue(txs.getGroupTransactionForUser(solo, legacy.getGroupId()).isEmpty());
    }

    private String group(final Transaction.Type type, final float amount, final Person.Id... members) {
        final String gid = UUID.randomUUID().toString();
        Assert.assertTrue(txs.saveGroupTransaction(gid, null, type, Transaction.TransactionType.Payment,
                LocalDateTime.now(), amount, "cat", "note", null, null, List.of(members)));
        return gid;
    }

    private Transaction row(final Person.Id who, final String groupId) {
        return txs.getGroupTransactionForUser(who, groupId).orElseThrow();
    }

    private static Person.Id id(final String prefix) {
        return Person.Id.from(prefix + "-" + UUID.randomUUID());
    }
}
