package org.paulsens.trip.migrate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Transaction;

/**
 * A synthetic ledger for the migration tests, built as the raw items a table scan returns: every id is random or a
 * {@link FakeData} seed, every name and amount invented. {@link #data} parses it the way a dump is parsed;
 * {@link #dump} writes it in the {@code aws dynamodb scan --output json} shape the command line reads.
 */
final class LedgerFixture {
    /** The day the tests ask "has this trip ended" on. */
    static final LocalDate TODAY = LocalDate.of(2026, 1, 15);
    static final String ORG = FakeData.CFPW_ORG_ID;
    static final String OTHER_ORG = FakeData.ACME_ORG_ID;

    private static final ObjectMapper MAPPER = LedgerData.mapper();

    private final Map<String, List<Map<String, String>>> tables = new LinkedHashMap<>();

    LedgerFixture() {
        LedgerData.TABLES.forEach(table -> tables.put(table, new ArrayList<>()));
        organization(ORG, "First Org");
        organization(OTHER_ORG, "Second Org");
    }

    static String uuid() {
        return UUID.randomUUID().toString();
    }

    private void organization(final String id, final String name) {
        tables.get(LedgerData.ORGANIZATIONS).add(item("id", id, "content",
                MAPPER.createObjectNode().put("id", id).put("name", name).toString()));
    }

    /** A person with a name; answers the id. */
    String person(final String first, final String last) {
        final String id = uuid();
        tables.get(LedgerData.PEOPLE).add(item("id", id, "content",
                MAPPER.createObjectNode().put("id", id).put("first", first).put("last", last).toString()));
        return id;
    }

    /** A trip of an organization (null for none) with its roster; answers the id. */
    String trip(final String orgId, final String title, final LocalDate start, final LocalDate end,
            final String... roster) {
        final String id = uuid();
        final ObjectNode trip = MAPPER.createObjectNode().put("id", id).put("title", title);
        if (orgId != null) {
            trip.put("orgId", orgId);
        }
        if (start != null) {
            trip.put("startDate", start.atStartOfDay().toString() + ":00");
            trip.put("endDate", end.atStartOfDay().toString() + ":00");
        }
        final ArrayNode people = trip.putArray("people");
        List.of(roster).forEach(people::add);
        tables.get(LedgerData.TRIPS).add(item("id", id, "content", trip.toString()));
        return id;
    }

    /** A trip that ended well before {@link #TODAY}: May 2024. */
    String endedTrip(final String title, final String... roster) {
        return trip(ORG, title, LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 10), roster);
    }

    /** A second ended trip, September 2024: a row dated early 2024 is inside both trips' windows. */
    String laterTrip(final String title, final String... roster) {
        return trip(ORG, title, LocalDate.of(2024, 9, 1), LocalDate.of(2024, 9, 10), roster);
    }

    /** A trip that is running on {@link #TODAY}. */
    String runningTrip(final String title, final String... roster) {
        return trip(ORG, title, LocalDate.of(2026, 1, 10), LocalDate.of(2026, 2, 10), roster);
    }

    void register(final String tripId, final String personId, final Registration.Status status) {
        tables.get(LedgerData.REGISTRATIONS).add(item("tripId", tripId, "userId", personId, "content",
                MAPPER.createObjectNode().put("tripId", tripId).put("userId", personId)
                        .put("status", status.getDescription()).toString()));
    }

    /** A plain payment (positive) or bill (negative) row of {@link #ORG}; answers its key. */
    String row(final String userId, final double amount, final LocalDate date) {
        return row(userId, ORG, amount, date);
    }

    String row(final String userId, final String orgId, final double amount, final LocalDate date) {
        final Transaction tx = new Transaction(uuid(), Person.Id.from(userId), null, null,
                amount < 0 ? Transaction.TransactionType.Bill : Transaction.TransactionType.Payment,
                date == null ? null : date.atTime(12, 0), (float) amount, "Synthetic", "invented note", null);
        tx.setOrgId(orgId);
        return put(tx);
    }

    /** One member's row of a Shared or Batch group; answers its key. */
    String groupRow(final String userId, final Transaction.Type type, final String groupId, final double amount,
            final LocalDate date, final List<String> members) {
        final Transaction tx = new Transaction(uuid(), Person.Id.from(userId), groupId, type,
                amount < 0 ? Transaction.TransactionType.Bill : Transaction.TransactionType.Payment,
                date.atTime(12, 0), (float) amount, "Synthetic", "invented group note",
                members == null ? null : members.stream().map(Person.Id::from).toList());
        tx.setOrgId(ORG);
        return put(tx);
    }

    /** Stores the row, replacing an earlier one with the same key; answers its key. */
    String put(final Transaction tx) {
        final String userId = tx.getUserId().getValue();
        tables.get(LedgerData.TRANSACTIONS)
                .removeIf(item -> userId.equals(item.get("userId")) && tx.getTxId().equals(item.get("txId")));
        tables.get(LedgerData.TRANSACTIONS).add(item("userId", userId, "txId", tx.getTxId(), "content", json(tx)));
        return userId + "," + tx.getTxId();
    }

    /** The stored row, parsed afresh so a test may change it and {@link #put} it back. */
    Transaction tx(final String key) {
        return data().row(key).tx();
    }

    void remove(final String key) {
        tables.get(LedgerData.TRANSACTIONS)
                .removeIf(item -> key.equals(item.get("userId") + "," + item.get("txId")));
    }

    /** Binds in both directions, as the app does. */
    void bind(final String key, final String tripId) {
        bindForward(key, tripId);
        bindReverse(tripId, key);
    }

    void bindForward(final String key, final String tripId) {
        tables.get(LedgerData.BINDINGS).add(item("id1", "4_" + key, "id1_type", "TRANSACTION", "id2",
                "5_" + tripId, "id2_type", "TRIP"));
    }

    void bindReverse(final String tripId, final String key) {
        tables.get(LedgerData.BINDINGS).add(item("id1", "5_" + tripId, "id1_type", "TRIP", "id2", "4_" + key,
                "id2_type", "TRANSACTION"));
    }

    void unbind(final String key) {
        tables.get(LedgerData.BINDINGS).removeIf(item -> item.containsValue("4_" + key));
    }

    LedgerData data() {
        return LedgerData.fromRaw(tables);
    }

    /** Writes the tables as scan output, one file per table; answers the directory. */
    Path dump(final Path dir) throws IOException {
        Files.createDirectories(dir);
        for (final Map.Entry<String, List<Map<String, String>>> table : tables.entrySet()) {
            final ObjectNode root = MAPPER.createObjectNode();
            final ArrayNode items = root.putArray("Items");
            table.getValue().forEach(item -> items.add(attributes(item)));
            root.put("Count", table.getValue().size());
            Files.writeString(dir.resolve(table.getKey() + ".json"), root.toString());
        }
        return dir;
    }

    private static ObjectNode attributes(final Map<String, String> item) {
        final ObjectNode node = MAPPER.createObjectNode();
        item.forEach((name, value) -> node.putObject(name).put("S", value));
        return node;
    }

    private static Map<String, String> item(final String... pairs) {
        final Map<String, String> item = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            item.put(pairs[i], pairs[i + 1]);
        }
        return item;
    }

    private static String json(final Transaction tx) {
        try {
            return MAPPER.writeValueAsString(tx);
        } catch (final JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
