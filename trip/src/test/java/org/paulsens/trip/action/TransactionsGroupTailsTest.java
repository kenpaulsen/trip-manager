package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The group-transaction lifecycle: membership stamping, removal on re-save, the trip/event bindings written by
 * {@code persistTx}, and the binding-driven lookup behind {@code getBoundTransaction}.
 */
public class TransactionsGroupTailsTest {

    private final TransactionsCommands txs = new TransactionsCommands();

    @Test
    public void aGroupSaveStampsMembershipBindsTheTripAndRemovalsDeleteTheRow() {
        final Person.Id anna = Person.Id.from("grp-anna-" + System.nanoTime());
        final Person.Id ben = Person.Id.from("grp-ben-" + System.nanoTime());
        final String gid = UUID.randomUUID().toString();
        final String tripId = "grp-trip-" + System.nanoTime();
        final String eventId = "grp-evt-" + System.nanoTime();

        Assert.assertTrue(txs.saveGroupTransaction(gid, null, Transaction.Type.Shared,
                Transaction.TransactionType.Bill, LocalDateTime.now(), 100f, "cat", "note",
                tripId, eventId, List.of(anna, ben)));

        final Transaction annas = txs.getGroupTransactionForUser(anna, gid).orElseThrow();
        Assert.assertEquals(List.copyOf(annas.getGroupPeople()), List.of(anna, ben),
                "membership is stamped on the row so it never requires probing other users");
        Assert.assertEquals(txs.getUserAmount(annas), 50f, "a shared bill splits across the members");

        // The persistTx bindings make the transaction findable FROM the trip.
        final BindingCommands bind = txs.getBind();
        final String comboKey = bind.key(anna.getValue(), annas.getTxId());
        Assert.assertFalse(bind.getBindings(comboKey, BindingType.TRANSACTION, BindingType.TRIP).isEmpty());
        Assert.assertNotNull(txs.getBoundTransaction(tripId, "TRIP"),
                "the reverse binding must resolve the transaction from the trip side");

        // Re-save with ben removed: his row is deleted, anna's survives with the new membership.
        Assert.assertTrue(txs.saveGroupTransaction(gid, List.of(anna, ben), Transaction.Type.Shared,
                Transaction.TransactionType.Bill, LocalDateTime.now(), 100f, "cat", "note",
                tripId, eventId, List.of(anna)));

        Assert.assertTrue(txs.getGroupTransactionForUser(ben, gid).isEmpty(),
                "a member dropped from the group must lose their row");
        Assert.assertEquals(List.copyOf(txs.getUserIdsForGroup(
                txs.getGroupTransactionForUser(anna, gid).orElseThrow())), List.of(anna));
    }

    /** A legacy row with no stamped membership falls back to the row's own user, loudly. */
    @Test
    public void aLegacyGroupRowFallsBackToItsOwnUser() {
        final Person.Id solo = Person.Id.from("legacy-solo");
        final Transaction legacy = new Transaction(solo, "legacy-group", Transaction.Type.Shared);

        Assert.assertEquals(txs.getUserIdsForGroup(legacy), List.of(solo));
        Assert.assertEquals(txs.getUserIdsForGroup(null), List.of());
        Assert.assertNull(txs.getUserAmount(null));
    }
}
