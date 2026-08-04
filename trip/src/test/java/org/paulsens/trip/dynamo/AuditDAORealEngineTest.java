package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditEvent;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.AuditPage;
import org.paulsens.trip.model.AuditQuery;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link AuditDAO} against a REAL DynamoDB engine, covering what {@code AuditDAOTest}'s fake cannot.
 *
 * <p>The audit partition key is {@code day}, which is a DynamoDB RESERVED KEYWORD. A query that names it
 * directly instead of aliasing it through {@code ExpressionAttributeNames} builds fine, passes against any
 * in-memory fake, and is rejected by the service. That is not hypothetical: it is how the audit page first
 * shipped -- every query failing, each failure swallowed into an empty day, and the page reporting "no records"
 * over a table holding 36,000.
 *
 * <p>{@code AuditDAOTest} keeps the fake because it instruments conditional-put rejections for the +1ms
 * collision rule, which a real engine does not expose. The two suites are complementary: that one proves no
 * record is lost to a collision, this one proves the queries are ones DynamoDB will actually accept.
 */
public class AuditDAORealEngineTest {

    private final AuditDAO dao = new AuditDAO(
            new ObjectMapper().findAndRegisterModules(), DynamoLocal.persistence());

    private AuditEvent event(final String actor, final Instant when, final String message) {
        return new AuditEvent(when, AuditAction.CONFIG, AuditOutcome.SUCCESS, actor, null,
                null, null, null, message, null);
    }

    /** The whole point: a real engine either accepts the query's expression or rejects the request outright. */
    @Test
    public void aWrittenRecordIsReadableBackThroughTheDayPartitionedQuery() {
        final String actor = DynamoLocal.uniqueId("audit-actor") + "@test";
        final Instant when = Instant.now();
        Assert.assertTrue(dao.saveAuditEvent(event(actor, when, "a real-engine record")).join());

        final AuditPage page = dao.getAuditEvents(AuditQuery.builder()
                .actor(actor)
                .limit(50)
                .build()).join();

        Assert.assertEquals(page.getEvents().size(), 1,
                "The day-partitioned query must be one DynamoDB accepts, reserved keyword and all");
        Assert.assertEquals(page.getEvents().get(0).getMessage(), "a real-engine record");
    }

    @Test
    public void severalRecordsInADayComeBackNewestFirst() {
        final String actor = DynamoLocal.uniqueId("audit-order") + "@test";
        final Instant base = Instant.now();
        for (int i = 0; i < 5; i++) {
            Assert.assertTrue(dao.saveAuditEvent(
                    event(actor, base.plusMillis(i * 10L), "message " + i)).join());
        }

        final List<AuditEvent> found = dao.getAuditEvents(AuditQuery.builder()
                .actor(actor).limit(50).build()).join().getEvents();

        Assert.assertEquals(found.size(), 5);
        for (int i = 1; i < found.size(); i++) {
            Assert.assertFalse(found.get(i).getTimestamp().isAfter(found.get(i - 1).getTimestamp()),
                    "newest first");
        }
    }

    @Test
    public void theCursorPagesBackwardsWithoutRepeatingOrSkipping() {
        final String actor = DynamoLocal.uniqueId("audit-page") + "@test";
        final Instant base = Instant.now();
        for (int i = 0; i < 6; i++) {
            Assert.assertTrue(dao.saveAuditEvent(
                    event(actor, base.plusMillis(i * 10L), "paged " + i)).join());
        }

        final AuditPage first = dao.getAuditEvents(AuditQuery.builder()
                .actor(actor).limit(3).build()).join();
        Assert.assertEquals(first.getEvents().size(), 3);

        final AuditPage second = dao.getAuditEvents(AuditQuery.builder()
                .actor(actor).before(first.nextCursor()).limit(3).build()).join();

        Assert.assertFalse(second.getEvents().isEmpty(), "The cursor must find the older page");
        final List<Instant> firstStamps = first.getEvents().stream().map(AuditEvent::getTimestamp).toList();
        second.getEvents().forEach(event -> Assert.assertFalse(firstStamps.contains(event.getTimestamp()),
                "A cursor page must not repeat a record from the previous page"));
    }

    @Test
    public void filtersNarrowTheResultRatherThanTheQuery() {
        final String actor = DynamoLocal.uniqueId("audit-filter") + "@test";
        final Instant when = Instant.now();
        Assert.assertTrue(dao.saveAuditEvent(event(actor, when, "findable needle")).join());
        Assert.assertTrue(dao.saveAuditEvent(event(actor, when.plusMillis(5), "other haystack")).join());

        Assert.assertEquals(dao.getAuditEvents(AuditQuery.builder()
                .actor(actor).text("needle").limit(50).build()).join().getEvents().size(), 1);
        Assert.assertEquals(dao.getAuditEvents(AuditQuery.builder()
                .actor(actor).outcome(AuditOutcome.FAILURE).limit(50).build()).join().getEvents().size(), 0);
        Assert.assertEquals(dao.getAuditEvents(AuditQuery.builder()
                .actor(actor).action(AuditAction.CONFIG).limit(50).build()).join().getEvents().size(), 2);
    }

    @Test
    public void aDayWithNothingInItIsAnEmptyPageNotAFailure() {
        final AuditPage page = dao.getAuditEvents(AuditQuery.builder()
                .actor(DynamoLocal.uniqueId("nobody") + "@test")
                .limit(10)
                .build()).join();

        Assert.assertTrue(page.getEvents().isEmpty());
        // The distinction that matters during an incident: nothing MATCHED, not nothing could be READ.
        Assert.assertFalse(page.isDegraded(), "An empty result must not look like a failed read");
    }

    @Test
    public void theSearchWindowIsReportedSoAPartialAnswerCannotPassAsComplete() {
        final AuditPage page = dao.getAuditEvents(AuditQuery.builder()
                .actor(DynamoLocal.uniqueId("window") + "@test")
                .limit(10)
                .build()).join();

        Assert.assertNotNull(page.getSearchedBackTo());
        Assert.assertFalse(page.getSearchedBackTo().isAfter(LocalDate.now(ZoneOffset.UTC)));
    }
}
