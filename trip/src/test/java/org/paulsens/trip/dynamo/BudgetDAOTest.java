package org.paulsens.trip.dynamo;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.NoopCacheClient;
import org.paulsens.trip.model.BudgetLine;
import org.paulsens.trip.model.TripBudget;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/** The {@code trip_budgets} table through the DAO facade against the in-memory fake. */
public class BudgetDAOTest {

    @Test
    public void saveGetAndVersioning() throws IOException {
        final TripBudget budget = budget();
        assertTrue(DAO.getInstance().saveTripBudget(budget));
        assertEquals(budget.getVersion(), 1L);
        final TripBudget read = DAO.getInstance().getTripBudget(budget.getTripId(), Cached.NO).orElseThrow();
        assertEquals(read, budget);
        assertEquals(DAO.getInstance().getTripBudget(budget.getTripId(), Cached.YES).orElseThrow(), budget);

        read.setPeople(47);
        assertTrue(DAO.getInstance().saveTripBudget(read));
        budget.setPeople(1);
        assertThrows(ConditionalCheckFailedException.class, () -> DAO.getInstance().saveTripBudget(budget));
        assertEquals(budget.getVersion(), 1L, "A rejected save restores the caller's version");
        assertEquals(DAO.getInstance().getTripBudget(budget.getTripId(), Cached.NO).orElseThrow().getPeople(), 47);
    }

    @Test
    public void aSecondCreateForTheSameTripIsRejected() throws IOException {
        final TripBudget first = budget();
        assertTrue(DAO.getInstance().saveTripBudget(first));
        final TripBudget again = TripBudget.builder().tripId(first.getTripId()).build();
        assertThrows(ConditionalCheckFailedException.class, () -> DAO.getInstance().saveTripBudget(again));
        assertEquals(again.getVersion(), 0L);
    }

    @Test
    public void oversizedBudgetIsRefusedBeforeTheStore() {
        final TripBudget huge = budget();
        final String note = "x".repeat(2000);
        for (int i = 0; i < 200; i++) {
            huge.getLines().add(BudgetLine.builder().name("Line " + i).note(note).build());
        }
        assertThrows(IllegalArgumentException.class, () -> DAO.getInstance().saveTripBudget(huge));
        assertEquals(huge.getVersion(), 0L, "Refused before the version bump");
        assertTrue(DAO.getInstance().getTripBudget(huge.getTripId(), Cached.NO).isEmpty());
    }

    @Test
    public void deleteIsIdempotentAndBlankIdsReadNothing() throws IOException {
        final TripBudget budget = budget();
        assertTrue(DAO.getInstance().saveTripBudget(budget));
        assertTrue(DAO.getInstance().deleteTripBudget(budget.getTripId()));
        assertTrue(DAO.getInstance().getTripBudget(budget.getTripId(), Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getTripBudget(budget.getTripId(), Cached.YES).isEmpty());
        DAO.getInstance().deleteTripBudget(budget.getTripId());
        assertTrue(DAO.getInstance().getTripBudget(null, Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getTripBudget(" ", Cached.NO).isEmpty());
    }

    @Test
    public void theBudgetScopeClearsItsPrefix() {
        final List<String> cleared = DAO.getInstance().invalidate(DAO.CacheScope.BUDGET);
        assertEquals(cleared, List.of(CacheKeys.BUDGET_PREFIX));
        assertFalse(cleared.isEmpty());
    }

    @Test
    public void aCorruptRowReadsEmptyRatherThanThrowing() {
        // A standalone DAO over its own store, so the corrupt row can be written past the serializer.
        final InMemoryPersistence persistence = new InMemoryPersistence();
        final BudgetDAO dao = new BudgetDAO(DAO.getInstance().getMapper(), persistence, new InMemoryCacheClient());
        final String tripId = "corrupt-" + RandomData.genAlpha(8);
        final Map<String, AttributeValue> item = Map.of(
                BudgetDAO.TRIP_ID, AttributeValue.builder().s(tripId).build(),
                "content", AttributeValue.builder().s("{not json").build());
        persistence.putItem(b -> b.tableName(BudgetDAO.BUDGETS_TABLE).item(item));
        assertTrue(dao.getBudget(tripId).isEmpty(), "Unparseable content answers empty, never throws");
        assertTrue(dao.getBudget("absent-" + RandomData.genAlpha(8)).isEmpty(), "No row, no budget");
    }

    @Test
    public void aRefusedPutRestoresTheVersion() throws IOException {
        final Persistence refusing = Mockito.mock(Persistence.class);
        final PutItemResponse failed = (PutItemResponse) PutItemResponse.builder()
                .sdkHttpResponse(SdkHttpResponse.builder().statusCode(500).build()).build();
        Mockito.when(refusing.putItem(ArgumentMatchers.any())).thenReturn(failed);
        final BudgetDAO dao = new BudgetDAO(DAO.getInstance().getMapper(), refusing, new NoopCacheClient());
        final TripBudget budget = budget();
        assertFalse(dao.saveBudget(budget));
        assertEquals(budget.getVersion(), 0L, "Not saved: the caller keeps the version it loaded");
    }

    private static TripBudget budget() {
        return TripBudget.builder().tripId("trip-" + RandomData.genAlpha(10)).orgId("org-1").people(46)
                .tripNights(10).perPersonBudgetCents(40_000)
                .lines(List.of(BudgetLine.builder().name("Guide").amountCents(144_000).build())).build();
    }
}
