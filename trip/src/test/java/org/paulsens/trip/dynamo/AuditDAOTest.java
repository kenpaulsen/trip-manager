package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditEvent;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.AuditPage;
import org.paulsens.trip.model.AuditQuery;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class AuditDAOTest {

    private AuditDAO dao;
    private InMemoryPersistence persistence;

    @BeforeMethod
    public void setup() {
        persistence = new InMemoryPersistence();
        dao = new AuditDAO(new ObjectMapper().findAndRegisterModules(), persistence);
    }

    @Test
    public void savedEventComesBack() {
        final AuditEvent event = event(Instant.now(), AuditAction.LOGIN, AuditOutcome.SUCCESS, "a@example.com");
        assertTrue(dao.saveAuditEvent(event).join());

        final List<AuditEvent> found = page(AuditQuery.builder().build()).getEvents();
        assertEquals(found.size(), 1);
        assertEquals(found.get(0).getActorEmail(), "a@example.com");
        assertEquals(found.get(0).getAction(), AuditAction.LOGIN);
        assertEquals(found.get(0).getOutcome(), AuditOutcome.SUCCESS);
    }

    @Test
    public void collidingWritesAreRetriedRatherThanOverwriting() {
        // THE test for this design. Two events in one millisecond target the same row, and an unconditional
        // PutItem would replace the first -- destroying an audit record with no error anywhere. Both must
        // survive, and the rejection must actually have happened (otherwise this passes for the wrong reason).
        final Instant sameMs = Instant.parse("2026-07-26T12:00:00Z");
        assertTrue(dao.saveAuditEvent(event(sameMs, AuditAction.LOGIN, AuditOutcome.SUCCESS, "a@x.com")).join());
        assertTrue(dao.saveAuditEvent(event(sameMs, AuditAction.LOGIN, AuditOutcome.FAILURE, "b@x.com")).join());

        assertTrue(persistence.getRejectionCount() > 0, "The second write should have hit the condition");
        assertEquals(persistence.size(), 2, "Both records must survive a same-millisecond collision");
    }

    @Test
    public void collisionRetryPreservesArrivalOrder() {
        // The retry moves FORWARD in time, so the record that lost the race sorts after the one that won.
        final Instant sameMs = Instant.parse("2026-07-26T12:00:00Z");
        dao.saveAuditEvent(event(sameMs, AuditAction.LOGIN, AuditOutcome.SUCCESS, "first@x.com")).join();
        dao.saveAuditEvent(event(sameMs, AuditAction.LOGIN, AuditOutcome.SUCCESS, "second@x.com")).join();

        // The cursor must clear the retry: the second record was nudged to sameMs+1, so a +1ms exclusive bound
        // would filter out the very record under test.
        final List<AuditEvent> newestFirst =
                page(AuditQuery.builder().before(sameMs.plusSeconds(1)).build()).getEvents();
        assertEquals(newestFirst.size(), 2, "Both records should be readable back");
        assertEquals(newestFirst.get(0).getActorEmail(), "second@x.com", "the later arrival should sort first");
        assertEquals(newestFirst.get(1).getActorEmail(), "first@x.com");
    }

    @Test
    public void manyEventsInOneMillisecondAllSurvive() {
        // Eight records once shared a millisecond in the real history; the retry must survive a pile-up.
        final Instant sameMs = Instant.parse("2026-07-26T12:00:00Z");
        for (int i = 0; i < 8; i++) {
            assertTrue(dao.saveAuditEvent(event(sameMs, AuditAction.EMAIL, AuditOutcome.SUCCESS, "u" + i + "@x.com"))
                    .join());
        }
        assertEquals(persistence.size(), 8, "No record may be lost to a key collision");
    }

    @Test
    public void resultsAreNewestFirstAcrossDays() {
        final Instant now = Instant.parse("2026-07-26T12:00:00Z");
        dao.saveAuditEvent(event(now.minus(3, ChronoUnit.DAYS), AuditAction.LOGIN, AuditOutcome.SUCCESS, "old@x"))
                .join();
        dao.saveAuditEvent(event(now.minus(1, ChronoUnit.DAYS), AuditAction.LOGIN, AuditOutcome.SUCCESS, "mid@x"))
                .join();
        dao.saveAuditEvent(event(now, AuditAction.LOGIN, AuditOutcome.SUCCESS, "new@x")).join();

        final List<AuditEvent> events = page(queryAt(now.plusMillis(1))).getEvents();
        assertEquals(events.stream().map(AuditEvent::getActorEmail).toList(),
                List.of("new@x", "mid@x", "old@x"), "Ordering must hold ACROSS day partitions, not just within");
    }

    @Test
    public void limitIsRespectedAndCursorContinues() {
        final Instant base = Instant.parse("2026-07-26T12:00:00Z");
        for (int i = 0; i < 10; i++) {
            dao.saveAuditEvent(event(base.minusSeconds(i), AuditAction.LOGIN, AuditOutcome.SUCCESS, "u" + i + "@x"))
                    .join();
        }

        final AuditPage first = page(AuditQuery.builder().before(base.plusMillis(1)).limit(4).build());
        assertEquals(first.getEvents().size(), 4);
        assertEquals(first.getEvents().get(0).getActorEmail(), "u0@x");

        final AuditPage second = page(AuditQuery.builder().before(first.nextCursor()).limit(4).build());
        assertEquals(second.getEvents().size(), 4);
        // The cursor is exclusive: the record that ended page one must not open page two.
        assertFalse(second.getEvents().stream().anyMatch(e -> e.getActorEmail().equals("u3@x")),
                "A cursor-paged read must not repeat the boundary record");
        assertEquals(second.getEvents().get(0).getActorEmail(), "u4@x");
    }

    @Test
    public void filtersNarrowResults() {
        final Instant now = Instant.parse("2026-07-26T12:00:00Z");
        dao.saveAuditEvent(event(now, AuditAction.LOGIN, AuditOutcome.SUCCESS, "alice@x.com")).join();
        dao.saveAuditEvent(event(now.minusSeconds(1), AuditAction.LOGIN, AuditOutcome.FAILURE, "bob@x.com")).join();
        dao.saveAuditEvent(event(now.minusSeconds(2), AuditAction.EMAIL, AuditOutcome.SUCCESS, "alice@x.com")).join();

        final Instant cursor = now.plusMillis(1);
        assertEquals(page(AuditQuery.builder().before(cursor).action(AuditAction.LOGIN).build())
                .getEvents().size(), 2);
        assertEquals(page(AuditQuery.builder().before(cursor).outcome(AuditOutcome.FAILURE).build())
                .getEvents().size(), 1);
        assertEquals(page(AuditQuery.builder().before(cursor).actor("alice").build())
                .getEvents().size(), 2);
        assertEquals(page(AuditQuery.builder().before(cursor).actor("alice").action(AuditAction.EMAIL).build())
                .getEvents().size(), 1);
    }

    @Test
    public void actorFilterAlsoMatchesTheTarget() {
        // "Show me everything about this person" is the real question; matching only the actor answers it wrongly.
        final Instant now = Instant.parse("2026-07-26T12:00:00Z");
        final AuditEvent adminEditedBob = new AuditEvent(now, AuditAction.PERSON, AuditOutcome.SUCCESS,
                "admin@x.com", null, "person", "bob@x.com", null, "Edited", null);
        dao.saveAuditEvent(adminEditedBob).join();

        final List<AuditEvent> found =
                page(AuditQuery.builder().before(now.plusMillis(1)).actor("bob@x.com").build()).getEvents();
        assertEquals(found.size(), 1, "An event where bob was the TARGET should show in a search for bob");
    }

    @Test
    public void boundedWalkReportsHowFarItLooked() {
        // An empty page must be distinguishable from "nothing exists". Without this the UI would report
        // "this user never did anything" when it merely stopped looking.
        final AuditPage page = page(AuditQuery.builder()
                .before(Instant.parse("2026-07-26T12:00:00Z"))
                .actor("nobody-matches-this@example.com")
                .build());

        assertTrue(page.isEmpty());
        assertFalse(page.isComplete(), "A page that ran out of budget must not claim to be complete");
        assertEquals(page.getSearchedBackTo(),
                java.time.LocalDate.parse("2026-07-26").minusDays(AuditDAO.MAX_DAYS_PER_PAGE - 1),
                "The page should say exactly how far back it searched");
    }

    @Test
    public void sinceStopsTheWalkEarly() {
        final Instant now = Instant.parse("2026-07-26T12:00:00Z");
        dao.saveAuditEvent(event(now.minus(10, ChronoUnit.DAYS), AuditAction.LOGIN, AuditOutcome.SUCCESS, "old@x"))
                .join();
        dao.saveAuditEvent(event(now, AuditAction.LOGIN, AuditOutcome.SUCCESS, "new@x")).join();

        final List<AuditEvent> found = page(AuditQuery.builder()
                .before(now.plusMillis(1))
                .since(now.minus(2, ChronoUnit.DAYS))
                .build()).getEvents();

        assertEquals(found.size(), 1);
        assertEquals(found.get(0).getActorEmail(), "new@x");
    }

    @Test
    public void exportReturnsOldestFirst() {
        // Export is chronological: a CSV of history reads forwards, unlike the newest-first screen.
        final Instant now = Instant.parse("2026-07-26T12:00:00Z");
        dao.saveAuditEvent(event(now.minusSeconds(5), AuditAction.LOGIN, AuditOutcome.SUCCESS, "first@x")).join();
        dao.saveAuditEvent(event(now, AuditAction.LOGIN, AuditOutcome.SUCCESS, "second@x")).join();

        final List<AuditEvent> exported =
                dao.exportAuditEvents(AuditQuery.builder().before(now.plusMillis(1)).build()).join();

        assertEquals(exported.get(0).getActorEmail(), "first@x");
        assertEquals(exported.get(1).getActorEmail(), "second@x");
    }

    @Test
    public void aReservedWordInTheKeyExpressionIsRejected() {
        // `day` is a DynamoDB reserved keyword, so it must be aliased through ExpressionAttributeNames. This
        // is asserted because the first version shipped without the alias: every query failed, every failure
        // was swallowed into an empty day, and the admin page reported "no records" over 36,000 rows.
        dao.saveAuditEvent(event(Instant.now(), AuditAction.LOGIN, AuditOutcome.SUCCESS, "a@x.com")).join();
        final AuditPage page = page(AuditQuery.builder().build());

        assertFalse(page.isDegraded(), "No partition should have failed to read: the key expression must alias "
                + "reserved words");
        assertEquals(page.getEvents().size(), 1);
    }

    @Test
    public void aTotalReadFailureIsReportedRatherThanLookingEmpty() {
        // "Nothing matched" and "nothing could be read" must not render identically.
        final AuditDAO brittle = new AuditDAO(new ObjectMapper().findAndRegisterModules(), new Persistence() {
            @Override
            public CompletableFuture<List<java.util.Map<String, software.amazon.awssdk.services.dynamodb.model
                    .AttributeValue>>> queryAll(final java.util.function.Consumer<
                            software.amazon.awssdk.services.dynamodb.model.QueryRequest.Builder> request) {
                return CompletableFuture.failedFuture(new IllegalStateException("boom"));
            }
        });

        final AuditPage page = brittle.getAuditEvents(AuditQuery.builder().build()).join();

        assertTrue(page.isEmpty());
        assertTrue(page.isDegraded(), "A page whose every query failed must say so, not present as empty");
        assertTrue(page.getFailedPartitions() > 0);
    }

    @Test
    public void unreadableDayDoesNotBlankThePage() {
        // A single unparseable or unreachable partition must degrade, not empty the whole view.
        final AuditDAO brittle = new AuditDAO(new ObjectMapper().findAndRegisterModules(), new Persistence() {
            @Override
            public CompletableFuture<List<java.util.Map<String, software.amazon.awssdk.services.dynamodb.model
                    .AttributeValue>>> queryAll(final java.util.function.Consumer<
                            software.amazon.awssdk.services.dynamodb.model.QueryRequest.Builder> request) {
                return CompletableFuture.failedFuture(new IllegalStateException("boom"));
            }
        });

        final AuditPage page = brittle.getAuditEvents(AuditQuery.builder().build()).join();
        assertTrue(page.isEmpty(), "A failing query should yield an empty page, not an exception");
    }

    private AuditPage page(final AuditQuery query) {
        return dao.getAuditEvents(query).join();
    }

    private static AuditQuery queryAt(final Instant cursor) {
        return AuditQuery.builder().before(cursor.plusMillis(1)).build();
    }

    private static AuditEvent event(final Instant when, final AuditAction action, final AuditOutcome outcome,
            final String actor) {
        return new AuditEvent(when, action, outcome, actor, null, null, null, null, "test message", null);
    }
}
