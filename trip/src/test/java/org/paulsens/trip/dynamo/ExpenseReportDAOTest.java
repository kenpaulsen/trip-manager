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
import org.paulsens.trip.model.ExpenseLine;
import org.paulsens.trip.model.TripExpenseReport;
import org.paulsens.trip.pay.ExpenseOracle;
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

/** The {@code trip_expense_reports} table through the DAO facade against the in-memory fake. */
public class ExpenseReportDAOTest {

    @Test
    public void saveGetAndVersioning() throws IOException {
        final TripExpenseReport report = report();
        assertTrue(DAO.getInstance().saveTripExpenseReport(report));
        assertEquals(report.getVersion(), 1L);
        final TripExpenseReport read =
                DAO.getInstance().getTripExpenseReport(report.getTripId(), Cached.NO).orElseThrow();
        assertEquals(read, report);
        assertEquals(DAO.getInstance().getTripExpenseReport(report.getTripId(), Cached.YES).orElseThrow(), report);

        read.setPaymentInstructions("Zelle");
        assertTrue(DAO.getInstance().saveTripExpenseReport(read));
        report.setPaymentInstructions("Check");
        assertThrows(ConditionalCheckFailedException.class, () -> DAO.getInstance().saveTripExpenseReport(report));
        assertEquals(report.getVersion(), 1L, "A rejected save restores the caller's version");
        assertEquals(DAO.getInstance().getTripExpenseReport(report.getTripId(), Cached.NO).orElseThrow()
                .getPaymentInstructions(), "Zelle");
    }

    @Test
    public void aSecondCreateForTheSameTripIsRejected() throws IOException {
        final TripExpenseReport first = report();
        assertTrue(DAO.getInstance().saveTripExpenseReport(first));
        final TripExpenseReport again = TripExpenseReport.builder().tripId(first.getTripId()).build();
        assertThrows(ConditionalCheckFailedException.class, () -> DAO.getInstance().saveTripExpenseReport(again));
        assertEquals(again.getVersion(), 0L);
    }

    @Test
    public void oversizedReportIsRefusedBeforeTheStore() {
        final TripExpenseReport huge = report();
        final String note = "x".repeat(2000);
        for (int i = 0; i < 200; i++) {
            huge.getLines().add(ExpenseLine.builder().description("Line " + i).note(note).build());
        }
        assertThrows(IllegalArgumentException.class, () -> DAO.getInstance().saveTripExpenseReport(huge));
        assertEquals(huge.getVersion(), 0L, "Refused before the version bump");
        assertTrue(DAO.getInstance().getTripExpenseReport(huge.getTripId(), Cached.NO).isEmpty());
    }

    @Test
    public void deleteIsIdempotentAndBlankIdsReadNothing() throws IOException {
        final TripExpenseReport report = report();
        assertTrue(DAO.getInstance().saveTripExpenseReport(report));
        assertTrue(DAO.getInstance().deleteTripExpenseReport(report.getTripId()));
        assertTrue(DAO.getInstance().getTripExpenseReport(report.getTripId(), Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getTripExpenseReport(report.getTripId(), Cached.YES).isEmpty());
        DAO.getInstance().deleteTripExpenseReport(report.getTripId());
        assertTrue(DAO.getInstance().getTripExpenseReport(null, Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getTripExpenseReport(" ", Cached.NO).isEmpty());
    }

    @Test
    public void theExpenseScopeClearsItsPrefix() {
        final List<String> cleared = DAO.getInstance().invalidate(DAO.CacheScope.EXPENSE);
        assertEquals(cleared, List.of(CacheKeys.EXPENSE_PREFIX));
        assertFalse(cleared.isEmpty());
    }

    @Test
    public void aCorruptRowReadsEmptyRatherThanThrowing() {
        // A standalone DAO over its own store, so the corrupt row can be written past the serializer.
        final InMemoryPersistence persistence = new InMemoryPersistence();
        final ExpenseReportDAO dao = new ExpenseReportDAO(DAO.getInstance().getMapper(), persistence,
                new InMemoryCacheClient());
        final String tripId = "corrupt-" + RandomData.genAlpha(8);
        final Map<String, AttributeValue> item = Map.of(
                ExpenseReportDAO.TRIP_ID, AttributeValue.builder().s(tripId).build(),
                "content", AttributeValue.builder().s("{not json").build());
        persistence.putItem(b -> b.tableName(ExpenseReportDAO.EXPENSES_TABLE).item(item));
        assertTrue(dao.getReport(tripId).isEmpty(), "Unparseable content answers empty, never throws");
        assertTrue(dao.getReport("absent-" + RandomData.genAlpha(8)).isEmpty(), "No row, no report");
    }

    @Test
    public void aRefusedPutRestoresTheVersion() throws IOException {
        final Persistence refusing = Mockito.mock(Persistence.class);
        final PutItemResponse failed = (PutItemResponse) PutItemResponse.builder()
                .sdkHttpResponse(SdkHttpResponse.builder().statusCode(500).build()).build();
        Mockito.when(refusing.putItem(ArgumentMatchers.any())).thenReturn(failed);
        final ExpenseReportDAO dao = new ExpenseReportDAO(DAO.getInstance().getMapper(), refusing,
                new NoopCacheClient());
        final TripExpenseReport report = report();
        assertFalse(dao.saveReport(report));
        assertEquals(report.getVersion(), 0L, "Not saved: the caller keeps the version it loaded");
    }

    private static TripExpenseReport report() {
        return TripExpenseReport.builder().tripId("trip-" + RandomData.genAlpha(10)).orgId("org-1")
                .advances(List.of(ExpenseOracle.advance(4_576_500L)))
                .lines(List.of(ExpenseLine.builder().description("Guide").amountMinor(144_000L).build())).build();
    }
}
