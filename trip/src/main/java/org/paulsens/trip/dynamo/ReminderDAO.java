package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ReminderSend;
import org.paulsens.trip.model.TripReminderPlan;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * {@code trip_reminders} (PK tripId, SK sk): a trip's scheduled messages and the record of what was sent
 * (see {@code docs/reminders.md}). Two kinds of item share the trip's partition:
 *
 * <ul>
 *   <li>{@code PLAN}: the one {@link TripReminderPlan}, saved by the {@link BudgetDAO} conditional put (version 0
 *       only creates, any other version only replaces that exact stored version; a lost race throws with the
 *       version restored).</li>
 *   <li>{@code SEND#...}: one {@link ReminderSend} per traveler, reminder and send date. {@link #claimSend} is
 *       WRITE-ONCE ({@code attribute_not_exists}): that put is what makes a reminder go out at most once.</li>
 * </ul>
 *
 * <p>Deliberately UNCACHED, like the waiver acceptances: the plan decides whether mail may go out, so a stale
 * approval must never be read, and a send claim has no business living in a cache whose local-mode twin IS the
 * store. The reads are one small partition per trip, on one admin page and one daily job.
 */
public class ReminderDAO {
    /** Package-visible so {@link InMemoryPersistence} can register the table for local mode. */
    static final String REMINDERS_TABLE = "trip_reminders";
    static final String TRIP_ID = "tripId";
    static final String ITEM = "sk";
    static final String VERSION_ATTR = VersionedWrites.VERSION;
    private static final String CONTENT = "content";
    /** Below DynamoDB's 400 KB item cap with room for the key attributes. */
    static final int MAX_PLAN_BYTES = 350_000;

    private final ObjectMapper mapper;
    private final JsonRows rows;
    private final Persistence persistence;

    protected ReminderDAO(final ObjectMapper mapper, final Persistence persistence) {
        this.mapper = mapper;
        this.rows = new JsonRows(mapper);
        this.persistence = persistence;
    }

    // ================================================================== the plan

    /**
     * Conditionally persists the plan and, on success, leaves its version bumped. On a version conflict the
     * version is restored and the exception propagates.
     *
     * @throws IllegalArgumentException when the serialized row would not fit the item cap.
     */
    protected Boolean savePlan(final TripReminderPlan plan) throws IOException {
        // Sized before the bump so a refused row keeps the version it was loaded with.
        VersionedWrites.requireFits(mapper.writeValueAsString(plan), MAX_PLAN_BYTES,
                size -> "The messages for trip " + plan.getTripId() + " are too large to store (" + size
                        + " bytes; max " + MAX_PLAN_BYTES + ").");
        final long expected = plan.getVersion();
        plan.setVersion(expected + 1);
        final Map<String, AttributeValue> item = key(plan.getTripId(), TripReminderPlan.SORT_KEY);
        item.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(plan)));
        item.put(VERSION_ATTR, AttributeValue.builder().n(Long.toString(plan.getVersion())).build());
        try {
            final boolean saved = persistence.putItem(
                    b -> VersionedWrites.conditionalPut(b, REMINDERS_TABLE, item, ITEM, expected))
                    .sdkHttpResponse().isSuccessful();
            if (!saved) {
                plan.setVersion(expected);
            }
            return saved;
        } catch (final ConditionalCheckFailedException ex) {
            plan.setVersion(expected);
            throw ex;
        }
    }

    protected Optional<TripReminderPlan> getPlan(final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return Optional.empty();
        }
        final Map<String, AttributeValue> key = key(tripId, TripReminderPlan.SORT_KEY);
        return Optional.ofNullable(rows.get(persistence, REMINDERS_TABLE, key, TripReminderPlan.class));
    }

    // ================================================================== the send ledger

    /**
     * Claims one send, once. A second claim of the same traveler, reminder and date is refused by the store.
     *
     * @return true when this call created the row (the caller may send), false when one already existed.
     */
    protected boolean claimSend(final ReminderSend send) throws IOException {
        final Map<String, AttributeValue> item = sendItem(send);
        try {
            return persistence.putItem(b -> b.tableName(REMINDERS_TABLE).item(item)
                    .conditionExpression("attribute_not_exists(#i)")
                    .expressionAttributeNames(Map.of("#i", ITEM)))
                    .sdkHttpResponse().isSuccessful();
        } catch (final ConditionalCheckFailedException ex) {
            return false;
        }
    }

    /**
     * Rewrites a send row. Unconditional. Two callers: the claim's holder, recording how its send ended, and
     * account deletion's {@link #scrubPerson}, which reads a row and writes it back without a name or address.
     * The scrub can therefore overwrite an outcome recorded between its read and its write; that window is the
     * few milliseconds of one put during an account deletion, and the cost is a history row that says "Sending".
     */
    protected boolean saveSend(final ReminderSend send) throws IOException {
        final Map<String, AttributeValue> item = sendItem(send);
        return persistence.putItem(b -> b.tableName(REMINDERS_TABLE).item(item)).sdkHttpResponse().isSuccessful();
    }

    /** Removes one send row, so the traveler can be sent that reminder again (the page's Resend). */
    protected boolean deleteSend(final ReminderSend send) {
        return deleteItem(send.getTripId(), send.getSortKey());
    }

    /** Every send recorded on a trip, newest first. */
    protected List<ReminderSend> getSends(final String tripId) {
        return partition(tripId).stream()
                .filter(item -> item.get(ITEM).s().startsWith(ReminderSend.SORT_PREFIX))
                .map(item -> item.get(CONTENT))
                .filter(Objects::nonNull)
                .map(content -> rows.parse(ReminderSend.class, content.s()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ReminderSend::getAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /**
     * Erases one person from a trip's send history (account deletion): their own rows lose the name and every
     * address, and a row that mailed THEIR address for someone else (a family manager's, for a dependent) loses
     * that one address and keeps the others. Addresses are compared whole, never as a substring: "ann@" must not
     * erase "joann@". The rows themselves stay, with their outcome, so "this reminder already ran" still holds.
     *
     * @return the number of rows rewritten.
     */
    protected int scrubPerson(final String tripId, final Person.Id personId, final String email,
            final String erasedName) throws IOException {
        final String address = (email == null) ? "" : email.trim();
        int rewritten = 0;
        for (final ReminderSend send : getSends(tripId)) {
            final boolean theirs = personId != null && personId.equals(send.getPersonId());
            final String to = (send.getToEmail() == null) ? "" : send.getToEmail();
            final String kept = theirs ? "" : without(to, address);
            if (theirs || !kept.equals(to)) {
                saveSend(send.withPersonName(theirs ? erasedName : send.getPersonName()).withToEmail(kept));
                rewritten++;
            }
        }
        return rewritten;
    }

    /** The comma-joined addresses of {@code to} minus {@code address}, compared whole and without case. */
    private static String without(final String to, final String address) {
        if (address.isEmpty() || to.isBlank()) {
            return to;
        }
        final List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (final String one : to.split(",")) {
            if (one.trim().equalsIgnoreCase(address)) {
                removed = true;
            } else {
                kept.add(one.trim());
            }
        }
        return removed ? String.join(", ", kept) : to;
    }

    // ================================================================== trip delete

    /** The trip-delete cascade's one call: the plan and every send row. Deleting twice is idempotent. */
    protected Boolean deleteTrip(final String tripId) {
        boolean deleted = true;
        for (final Map<String, AttributeValue> item : partition(tripId)) {
            deleted &= deleteItem(tripId, item.get(ITEM).s());
        }
        return deleted;
    }

    // ================================================================== plumbing

    private List<Map<String, AttributeValue>> partition(final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return List.of();
        }
        return persistence.queryPartition(REMINDERS_TABLE, TRIP_ID, tripId);
    }

    private boolean deleteItem(final String tripId, final String sortKey) {
        final Map<String, AttributeValue> key = key(tripId, sortKey);
        return persistence.deleteItem(b -> b.tableName(REMINDERS_TABLE).key(key)).sdkHttpResponse().isSuccessful();
    }

    private Map<String, AttributeValue> sendItem(final ReminderSend send) throws IOException {
        final Map<String, AttributeValue> item = key(send.getTripId(), send.getSortKey());
        item.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(send)));
        return item;
    }

    private Map<String, AttributeValue> key(final String tripId, final String sortKey) {
        final Map<String, AttributeValue> key = new HashMap<>();
        key.put(TRIP_ID, persistence.toStrAttr(tripId));
        key.put(ITEM, persistence.toStrAttr(sortKey));
        return key;
    }
}
