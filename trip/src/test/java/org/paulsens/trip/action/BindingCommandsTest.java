package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

public class BindingCommandsTest {

    @Test
    public void canSaveBindings() {
        final BindingCommands bind = new BindingCommands();
        final String srcId = RandomData.genAlpha(12) + ',' + RandomData.genAlpha(2);
        final String destId = RandomData.genAlpha(10);
        Assert.assertTrue(bind.saveBinding(srcId, BindingType.REGISTRATION, destId, BindingType.PERSON, true));
        Assert.assertEquals(bind.getBindings(srcId, BindingType.REGISTRATION, BindingType.PERSON), List.of(destId));
    }

    @Test
    public void canSetAndRemoveManyBindings() {
        final BindingCommands bind = new BindingCommands();
        final BindingType t1 = BindingType.TRIP;
        final BindingType t2 = BindingType.TRANSACTION;
        final String srcId = RandomData.genAlpha(12);
        final List<String> idsToRemove =
                List.of(RandomData.genAlpha(1) + ',' + RandomData.genAlpha(3),
                        RandomData.genAlpha(2) + ',' + RandomData.genAlpha(2),
                        RandomData.genAlpha(3) + ',' + RandomData.genAlpha(1));
        final List<String> idsUnchanged =
                List.of(RandomData.genAlpha(7) + ',' + RandomData.genAlpha(1),
                        RandomData.genAlpha(8) + ',' + RandomData.genAlpha(2),
                        RandomData.genAlpha(9) + ',' + RandomData.genAlpha(3));
        final List<String> idsToAdd =
                List.of(RandomData.genAlpha(4) + ',' + RandomData.genAlpha(2),
                        RandomData.genAlpha(5) + ',' + RandomData.genAlpha(3),
                        RandomData.genAlpha(6) + ',' + RandomData.genAlpha(1));
        // Setup...
        final List<String> startingIds = new ArrayList<>();
        startingIds.addAll(idsToRemove);
        startingIds.addAll(idsUnchanged);
        bind.setBindings(srcId, t1, t2, startingIds, true);
        final List<String> cachedIds = bind.getBindings(srcId, t1, t2);
        for (final String id : startingIds) {
            Assert.assertTrue(cachedIds.contains(id), "Missing id: " + id);
        }
        Assert.assertEquals(cachedIds.size(), startingIds.size());
        // Apply changes...
        final List<String> idsToSet = new ArrayList<>();
        idsToSet.addAll(idsUnchanged);
        idsToSet.addAll(idsToAdd);
        final List<String> idsRemoved = bind.setBindings(srcId, t1, t2, idsToSet, true);
        // See if items to remove got removed
        for (final String id : idsToRemove) {
            Assert.assertTrue(idsRemoved.contains(id), "Should have removed id: " + id);
        }
        Assert.assertEquals(idsRemoved.size(), idsToRemove.size(), "Removed too many?");
        // Confirm expected new results
        final List<String> newResults = bind.getBindings(srcId, t1, t2);
        for (final String id : idsToSet) {
            Assert.assertTrue(newResults.contains(id), "Missing id: " + id);
        }
        Assert.assertEquals(newResults.size(), idsToSet.size());
        // Confirm reverse bindings were also removed
        for (final String id : idsRemoved) {
            Assert.assertFalse(bind.getBindings(id, t2, t1).contains(srcId), "Binding from '" + id + "' still exists!");
        }
        // Set one more time, but also exclude the unchanged, but don't remove reverse bindings -- testing 1-way delete
        final List<String> shouldBeTheUnchangedIds = bind.setBindings(srcId, t1, t2, idsToAdd, false);
        for (final String id : shouldBeTheUnchangedIds) {
            Assert.assertTrue(idsUnchanged.contains(id), "Should have listed '" + id + "' as deleted!");
            Assert.assertFalse(bind.getBindings(srcId, t1, t2).contains(id), "Binding '" + id + "' still exists!");
            Assert.assertTrue(bind.getBindings(id, t2, t1).contains(srcId), "Binding from '" + id + "' missing!");
        }
    }
}