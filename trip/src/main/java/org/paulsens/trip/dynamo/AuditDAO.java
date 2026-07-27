package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.AuditEvent;
import org.paulsens.trip.model.AuditQuery;
import org.paulsens.trip.model.AuditPage;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * The audit trail's queryable index, one row per event in the {@code audit} table.
 *
 * <p><b>Deliberately not cached.</b> Every other DAO here fronts its table with the shared Valkey cache, because
 * those tables are read constantly and change rarely. This one is the opposite: it is written on nearly every
 * request and read only when an admin opens the audit page. Caching it would spend memory and invalidation
 * traffic to speed up something nobody does, and a stale audit view is worse than a slow one.
 *
 * <p><b>Reading.</b> There are no secondary indexes, so ordering exists only within a day partition. A page is
 * assembled by querying today backwards, then the previous day, and so on, applying filters server-side, until
 * enough rows are collected. That walk is BOUNDED ({@link #MAX_DAYS_PER_PAGE}): a filter matching nothing --
 * say an actor who never did the thing being searched for -- would otherwise walk to the beginning of time on
 * every page. When the bound is hit the page says how far back it actually looked, so an empty result is
 * honestly "nothing in this window" rather than a silent "nothing, ever".
 *
 * <p><b>Writing.</b> Conditionally, and never blocking a request thread (see {@code DynamoAuditSink}). The
 * condition is the point: the sort key is a bare millisecond, so two events in the same millisecond target the
 * same row, and an unconditional {@code PutItem} would not fail -- it would OVERWRITE, destroying a record.
 * A rejected write is retried one millisecond later.
 */
@Slf4j
public class AuditDAO {
    static final String AUDIT_TABLE = "audit";
    static final String PARTITION = "day";
    static final String SORT = "ts";
    private static final String CONTENT = "content";

    /**
     * How many day-partitions one page request may examine.
     *
     * <p>60 days is roughly two months of history per click. Unbounded would mean a filter that matches nothing
     * walks back to 2021 -- thousands of queries -- while the admin stares at a spinner.
     */
    static final int MAX_DAYS_PER_PAGE = 60;

    /** How many times a colliding write is nudged forward before giving up. */
    private static final int MAX_WRITE_RETRIES = 10;

    /** Guards against walking past the start of recorded history. */
    private static final LocalDate EARLIEST = LocalDate.of(2021, 1, 1);

    private final ObjectMapper mapper;
    private final Persistence persistence;

    protected AuditDAO(final ObjectMapper mapper, final Persistence persistence) {
        this.mapper = mapper;
        this.persistence = persistence;
    }

    /**
     * Stores one event, retrying a millisecond later if that key is taken.
     *
     * @return true if it was stored. False means the record did not reach the table -- the caller (the sink)
     *         still has it and prints it, so it is not lost, only unindexed.
     */
    protected CompletableFuture<Boolean> saveAuditEvent(final AuditEvent event) {
        return saveWithRetry(event, 0);
    }

    private CompletableFuture<Boolean> saveWithRetry(final AuditEvent event, final int attempt) {
        if (attempt >= MAX_WRITE_RETRIES) {
            log.warn("Audit record could not find a free key after {} attempts: {}", MAX_WRITE_RETRIES, event);
            return CompletableFuture.completedFuture(false);
        }
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(PARTITION, persistence.toStrAttr(event.getPartition()));
        item.put(SORT, persistence.toStrAttr(event.getSortKey()));
        final String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (final IOException ex) {
            log.error("Unable to serialize audit event: {}", event, ex);
            return CompletableFuture.completedFuture(false);
        }
        item.put(CONTENT, persistence.toStrAttr(json));

        return persistence.putItem(b -> b.tableName(AUDIT_TABLE)
                        .item(item)
                        // The whole reason writes are conditional: without this, a same-millisecond collision
                        // silently replaces an existing audit record instead of failing.
                        .conditionExpression("attribute_not_exists(" + SORT + ")"))
                .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
                .exceptionallyCompose(ex -> {
                    if (isConditionalFailure(ex)) {
                        // Taken. Move one millisecond later, which keeps this record AFTER the one already
                        // there -- events arrive in order, so nudging the loser forward preserves that order.
                        return saveWithRetry(event.withNextMilli(), attempt + 1);
                    }
                    log.warn("Unable to store audit event (it remains in the log stream): {}", event, ex);
                    return CompletableFuture.completedFuture(false);
                });
    }

    private static boolean isConditionalFailure(final Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof ConditionalCheckFailedException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * One page of history, newest first.
     *
     * @param query what to look for and where to resume from.
     * @return the matching events plus how far back the search actually reached.
     */
    protected CompletableFuture<AuditPage> getAuditEvents(final AuditQuery query) {
        final LocalDate startDay = (query.getBefore() == null)
                ? LocalDate.now(ZoneOffset.UTC)
                : LocalDate.ofInstant(query.getBefore(), ZoneOffset.UTC);
        return walkBackwards(query, startDay, new ArrayList<>(), 0);
    }

    private CompletableFuture<AuditPage> walkBackwards(final AuditQuery query, final LocalDate day,
            final List<AuditEvent> collected, final int daysExamined) {
        final boolean exhausted = daysExamined >= MAX_DAYS_PER_PAGE
                || day.isBefore(EARLIEST)
                || (query.getSince() != null && day.isBefore(LocalDate.ofInstant(query.getSince(), ZoneOffset.UTC)));
        if (collected.size() >= query.getLimit() || exhausted) {
            final List<AuditEvent> page = collected.size() > query.getLimit()
                    ? new ArrayList<>(collected.subList(0, query.getLimit()))
                    : collected;
            // "Searched back to" is the honest part: with a bounded walk, an empty page means "nothing in this
            // window", and the caller must be able to tell that apart from "nothing at all".
            return CompletableFuture.completedFuture(new AuditPage(page, day.plusDays(1), !exhausted));
        }
        return queryDay(query, day)
                .thenCompose(dayEvents -> {
                    collected.addAll(dayEvents);
                    return walkBackwards(query, day.minusDays(1), collected, daysExamined + 1);
                });
    }

    /** One day partition, newest first, with the caller's filters applied server-side. */
    private CompletableFuture<List<AuditEvent>> queryDay(final AuditQuery query, final LocalDate day) {
        final Map<String, AttributeValue> values = new HashMap<>();
        values.put(":day", persistence.toStrAttr(day.toString()));

        final StringBuilder keyCond = new StringBuilder(PARTITION + " = :day");
        if (query.getBefore() != null && day.equals(LocalDate.ofInstant(query.getBefore(), ZoneOffset.UTC))) {
            // Only the first day needs an upper bound; every earlier day is wholly before the cursor.
            keyCond.append(" AND ").append(SORT).append(" < :before");
            values.put(":before", persistence.toStrAttr(AuditEvent.sortKeyFor(query.getBefore())));
        }

        return persistence.queryAll(b -> {
            b.tableName(AUDIT_TABLE)
                    .keyConditionExpression(keyCond.toString())
                    .expressionAttributeValues(values)
                    // Newest first WITHIN the partition; the walk supplies the across-partition ordering.
                    .scanIndexForward(false);
            b.build();
        }).thenApply(items -> items.stream()
                .map(item -> item.get(CONTENT))
                .filter(content -> content != null)
                .map(content -> parseEvent(content.s()))
                .filter(event -> event != null)
                .filter(query::matches)
                .toList())
                .exceptionally(ex -> {
                    // One unreadable day must not blank the whole page.
                    log.warn("Unable to read audit partition {}", day, ex);
                    return List.of();
                });
    }

    /**
     * Every event in a window, oldest first -- for CSV export, which wants the whole range rather than a page.
     * Bounded by the same day budget so an export cannot become an unbounded table walk.
     */
    protected CompletableFuture<List<AuditEvent>> exportAuditEvents(final AuditQuery query) {
        final AuditQuery unlimited = query.withLimit(Integer.MAX_VALUE);
        return getAuditEvents(unlimited).thenApply(page -> {
            final List<AuditEvent> events = new ArrayList<>(page.getEvents());
            events.sort(java.util.Comparator.comparing(AuditEvent::getTimestamp));
            return events;
        });
    }

    /** How long the walk may span, for callers that want to explain the window to a user. */
    static Duration maxWindow() {
        return Duration.ofDays(MAX_DAYS_PER_PAGE);
    }

    private AuditEvent parseEvent(final String json) {
        try {
            return mapper.readValue(json, AuditEvent.class);
        } catch (final IOException ex) {
            log.error("Unable to parse audit record: {}", json, ex);
            return null;
        }
    }
}
