package org.paulsens.trip.dynamo;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

public class BindingDAOTest {

    @Test
    public void canRetrieveBindingAndReverseToo() {
        final BindingDAO dao = new BindingDAO(DynamoLocal.persistence());
        final String id1 = RandomData.genAlpha(12);
        final String id2 = RandomData.genAlpha(7) + ',' + RandomData.genAlpha(7);
        final String id3 = RandomData.genAlpha(10);
        final String id4 = RandomData.genAlpha(8) + ',' + RandomData.genAlpha(7);
        // Save a binding
        Assert.assertTrue(dao.saveBinding(id1, BindingType.PERSON, id2, BindingType.TRANSACTION, true));

        // Verify saved forward and backward
        Assert.assertEquals(dao.getBindings(id1, BindingType.PERSON, BindingType.TRANSACTION), List.of(id2));
        Assert.assertEquals(dao.getBindings(id2, BindingType.TRANSACTION, BindingType.PERSON), List.of(id1));

        // Add one-way binding to a different type, results should be unchanged for original, plus new binding available
        Assert.assertTrue(dao.saveBinding(id1, BindingType.PERSON, id3, BindingType.TRIP, false));
        // Verify
        Assert.assertEquals(dao.getBindings(id1, BindingType.PERSON, BindingType.TRANSACTION), List.of(id2));
        Assert.assertEquals(dao.getBindings(id2, BindingType.TRANSACTION, BindingType.PERSON), List.of(id1));
        Assert.assertEquals(dao.getBindings(id1, BindingType.PERSON, BindingType.TRIP), List.of(id3));
        Assert.assertEquals(dao.getBindings(id3, BindingType.TRIP, BindingType.PERSON), List.of());

        // Re-add the same binding, make sure this is a noop
        Assert.assertTrue(dao.saveBinding(id1, BindingType.PERSON, id2, BindingType.TRANSACTION, true));
        Assert.assertEquals(dao.getBindings(id1, BindingType.PERSON, BindingType.TRANSACTION), List.of(id2));
        Assert.assertEquals(dao.getBindings(id2, BindingType.TRANSACTION, BindingType.PERSON), List.of(id1));

        // Add a second binding of the same type, make sure it gets added w/o removing the old one
        // (bindings come back in deterministic sorted order, not insertion order)
        Assert.assertTrue(dao.saveBinding(id1, BindingType.PERSON, id4, BindingType.TRANSACTION, true));
        Assert.assertEquals(dao.getBindings(id1, BindingType.PERSON, BindingType.TRANSACTION),
                Stream.of(id2, id4).sorted().toList());
        Assert.assertEquals(dao.getBindings(id2, BindingType.TRANSACTION, BindingType.PERSON), List.of(id1));
        Assert.assertEquals(dao.getBindings(id4, BindingType.TRANSACTION, BindingType.PERSON), List.of(id1));
    }

    @Test
    public void cachingPreventsDBAccess() {
        AtomicInteger dbAccess = new AtomicInteger(0);
        final BindingDAO dao = new BindingDAO(FakeData.createFakePersistenceWithQueryMonitor(
                q -> dbAccess.incrementAndGet()));
        final String id1 = RandomData.genAlpha(3);
        Assert.assertEquals(dbAccess.get(), 0);
        Assert.assertEquals(dao.getBindings(id1, BindingType.TRIP, BindingType.TRANSACTION), List.of());
        Assert.assertEquals(dbAccess.get(), 1);
        Assert.assertEquals(dao.getBindings(id1, BindingType.TRIP, BindingType.TRANSACTION), List.of());
        Assert.assertEquals(dbAccess.get(), 1);
        Assert.assertEquals(dao.getBindings(id1, BindingType.TRIP, BindingType.PERSON), List.of());
        Assert.assertEquals(dbAccess.get(), 1);
        Assert.assertEquals(dao.getBindings(id1, BindingType.PERSON, BindingType.PERSON), List.of());
        Assert.assertEquals(dbAccess.get(), 2);
        dao.clearCache();
        Assert.assertEquals(dbAccess.get(), 2);
        Assert.assertEquals(dao.getBindings(id1, BindingType.TRIP, BindingType.PERSON), List.of());
        Assert.assertEquals(dbAccess.get(), 3);
        Assert.assertEquals(dao.getBindings(id1, BindingType.TRIP_EVENT, BindingType.PERSON), List.of());
        Assert.assertEquals(dbAccess.get(), 4);
        final String id2 = RandomData.genAlpha(4) + ',' + RandomData.genAlpha(3);
        Assert.assertTrue(dao.saveBinding(id1, BindingType.TRIP, id2, BindingType.TRANSACTION, false));
        Assert.assertEquals(dbAccess.get(), 4);
        Assert.assertEquals(dao.getBindings(id1, BindingType.TRIP, BindingType.PERSON), List.of());
        Assert.assertEquals(dbAccess.get(), 4);
    }
    }
