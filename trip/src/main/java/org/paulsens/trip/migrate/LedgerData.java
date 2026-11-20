package org.paulsens.trip.migrate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.paulsens.trip.action.TransactionsCommands;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.CompositeKey;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Transaction;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The ledger tables as ONE raw, whole-table read (docs/migrations/ledger-trip-binding.md): every transaction row,
 * soft-deleted ones included, with its stored JSON hashed as written; the TRANSACTION and TRIP bindings in both
 * directions; and the little of trips, registrations, people and organizations the planner and the verifier ask
 * about. Built either from a dump directory (one {@code <table>.json} per table, {@code aws dynamodb scan
 * --output json}) or from the live tables ({@link #live}); everything downstream reads this, so a figure computed
 * offline and one computed against the deployment come from the same code.
 *
 * <p>Parsed directly rather than loaded into the in-memory store: a row re-saved through the DAO is re-serialized,
 * and the verifier's "nothing but bindings changed" proof compares the stored JSON byte for byte. The bindings
 * are the one mutable part ({@link #copy} hands the rehearsal its own), because bindings are all an apply writes.
 */
final class LedgerData {
    static final String TRANSACTIONS = "transactions";
    static final String BINDINGS = "bindings";
    static final String TRIPS = "trips";
    static final String REGISTRATIONS = "registrations";
    static final String PEOPLE = "people";
    static final String ORGANIZATIONS = "organizations";
    static final List<String> TABLES = List.of(TRANSACTIONS, BINDINGS, TRIPS, REGISTRATIONS, PEOPLE, ORGANIZATIONS);

    /** A date before a trip's start that still belongs to it (deposits), and after its end (refunds, extras). */
    static final int MONTHS_BEFORE = 18;
    static final int MONTHS_AFTER = 4;

    /** The order a person's ledger is read in: the DAO sorts by date over a partition ordered by id. */
    static final Comparator<Row> BY_DATE = Comparator
            .comparing(Row::dateTime, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Row::txId);

    private static final String CONTENT = "content";
    private static final ObjectMapper MAPPER = DAO.createObjectMapper();
    /** Today's share rule itself, not a copy of it: the bean needs no container for this one method. */
    private static final TransactionsCommands SHARES = new TransactionsCommands();

    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final Map<String, List<Row>> liveByUser = new LinkedHashMap<>();
    private final Map<String, List<Row>> allByUser = new LinkedHashMap<>();
    private final Map<String, List<Row>> liveByGroup = new LinkedHashMap<>();
    private final Map<String, TripInfo> trips = new LinkedHashMap<>();
    private final Map<String, Set<String>> registered = new LinkedHashMap<>();
    private final Map<String, PersonName> people = new LinkedHashMap<>();
    private final Map<String, String> orgNames = new LinkedHashMap<>();
    private final Map<String, List<String>> forward;
    private final Map<String, List<String>> reverse;

    /**
     * One stored transaction.
     *
     * @param userId the partition: a person, or a trip for a trip's own rows
     * @param txId   the sort key
     * @param tx     the row as the app reads it
     * @param hash   SHA-256 of the keys and the stored JSON exactly as written
     * @param share  the per-user amount by today's rule ({@code TransactionsCommands.getUserAmount}), or null
     */
    record Row(String userId, String txId, Transaction tx, String hash, Float share) {
        String key() {
            return CompositeKey.join(userId, txId);
        }

        boolean live() {
            return tx.getDeleted() == null;
        }

        /** The row's organization, "" when it carries none (the figures key an org-less row that way). */
        String orgId() {
            return tx.getOrgId() == null ? "" : tx.getOrgId();
        }

        LocalDateTime dateTime() {
            return tx.getTxDate();
        }

        LocalDate date() {
            return tx.getTxDate() == null ? null : tx.getTxDate().toLocalDate();
        }

        boolean grouped() {
            return tx.getGroupId() != null && !tx.getGroupId().isBlank();
        }
    }

    /**
     * What the migration needs of a trip.
     *
     * @param people the roster ({@code Trip.people})
     */
    record TripInfo(String id, String title, String orgId, LocalDate start, LocalDate end, Set<String> people) {
        /** A trip with no dates at all never counts as ended: binding onto it would need the owner's say. */
        boolean ended(final LocalDate today) {
            return end != null && end.isBefore(today);
        }

        /** Whether a row dated then could belong to this trip: 18 months before its start to 4 after its end. */
        boolean windowHolds(final LocalDate date) {
            return date != null && start != null && !date.isBefore(start.minusMonths(MONTHS_BEFORE))
                    && !date.isAfter(end.plusMonths(MONTHS_AFTER));
        }

        String dates() {
            return start + " to " + end;
        }
    }

    /** A person's name, for the review file only. */
    record PersonName(String first, String last) {
    }

    private LedgerData(final Map<String, List<String>> forward, final Map<String, List<String>> reverse) {
        this.forward = forward;
        this.reverse = reverse;
    }

    // ------------------------------------------------------------------ reading

    /** Reads a dump directory; a missing table file is a refusal, never an empty table. */
    static LedgerData fromDump(final Path dir) throws IOException {
        final Map<String, List<Map<String, String>>> tables = new LinkedHashMap<>();
        for (final String table : TABLES) {
            final Path file = dir.resolve(table + ".json");
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("The dump has no " + table + ".json: " + dir);
            }
            final List<Map<String, String>> items = new ArrayList<>();
            for (final JsonNode item : MAPPER.readTree(file.toFile()).path("Items")) {
                items.add(attributes(item));
            }
            tables.put(table, items);
        }
        return fromRaw(tables);
    }

    /** Reads the live tables, each in one strongly consistent scan that no cache stands in front of. */
    static LedgerData live(final DAO dao) {
        final Map<String, List<Map<String, String>>> tables = new LinkedHashMap<>();
        for (final String table : TABLES) {
            tables.put(table, dao.scanTable(table).stream().map(LedgerData::attributes).toList());
        }
        return fromRaw(tables);
    }

    /** Builds the ledger from raw items: table name to items, each an attribute name to its string value. */
    static LedgerData fromRaw(final Map<String, List<Map<String, String>>> tables) {
        final LedgerData data = new LedgerData(new LinkedHashMap<>(), new LinkedHashMap<>());
        tables.getOrDefault(TRIPS, List.of()).forEach(data::addTrip);
        tables.getOrDefault(REGISTRATIONS, List.of()).forEach(data::addRegistration);
        tables.getOrDefault(PEOPLE, List.of()).forEach(data::addPerson);
        tables.getOrDefault(ORGANIZATIONS, List.of()).forEach(data::addOrganization);
        tables.getOrDefault(TRANSACTIONS, List.of()).forEach(data::addTransaction);
        tables.getOrDefault(BINDINGS, List.of()).forEach(data::addBinding);
        data.liveByUser.values().forEach(list -> list.sort(BY_DATE));
        data.allByUser.values().forEach(list -> list.sort(BY_DATE));
        data.liveByGroup.values().forEach(list -> list.sort(BY_DATE));
        return data;
    }

    /** The same rows with bindings of its own: what a rehearsal writes to. */
    LedgerData copy() {
        final LedgerData copy = new LedgerData(copyOf(forward), copyOf(reverse));
        copy.rows.putAll(rows);
        copy.liveByUser.putAll(liveByUser);
        copy.allByUser.putAll(allByUser);
        copy.liveByGroup.putAll(liveByGroup);
        copy.trips.putAll(trips);
        copy.registered.putAll(registered);
        copy.people.putAll(people);
        copy.orgNames.putAll(orgNames);
        return copy;
    }

    private static Map<String, List<String>> copyOf(final Map<String, List<String>> edges) {
        final Map<String, List<String>> copy = new LinkedHashMap<>();
        edges.forEach((from, to) -> copy.put(from, new ArrayList<>(to)));
        return copy;
    }

    private static Map<String, String> attributes(final JsonNode item) {
        final Map<String, String> attributes = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonNode> attribute : item.properties()) {
            final JsonNode value = attribute.getValue();
            final JsonNode scalar = value.has("S") ? value.get("S") : value.get("N");
            if (scalar != null) {
                attributes.put(attribute.getKey(), scalar.asText());
            }
        }
        return attributes;
    }

    private static Map<String, String> attributes(final Map<String, AttributeValue> item) {
        final Map<String, String> attributes = new LinkedHashMap<>();
        for (final Map.Entry<String, AttributeValue> attribute : item.entrySet()) {
            final String scalar = attribute.getValue().s() != null ? attribute.getValue().s()
                    : attribute.getValue().n();
            if (scalar != null) {
                attributes.put(attribute.getKey(), scalar);
            }
        }
        return attributes;
    }

    private void addTransaction(final Map<String, String> item) {
        final String userId = item.get("userId");
        final String txId = item.get("txId");
        final String content = item.get(CONTENT);
        final Transaction tx;
        try {
            tx = MAPPER.readValue(content == null ? "" : content, Transaction.class);
        } catch (final IOException | IllegalArgumentException ex) {
            // A row this tool cannot read is a row it cannot account for: stop, never skip.
            throw new IllegalStateException("Unreadable transaction row " + CompositeKey.join(userId, txId), ex);
        }
        final Row row = new Row(userId, txId, tx, sha256(userId + "\n" + txId + "\n" + content),
                SHARES.getUserAmount(tx));
        rows.put(row.key(), row);
        allByUser.computeIfAbsent(userId, na -> new ArrayList<>()).add(row);
        if (row.live()) {
            liveByUser.computeIfAbsent(userId, na -> new ArrayList<>()).add(row);
            if (row.grouped()) {
                liveByGroup.computeIfAbsent(tx.getGroupId(), na -> new ArrayList<>()).add(row);
            }
        }
    }

    private void addBinding(final Map<String, String> item) {
        final String from = item.get("id1_type");
        final String to = item.get("id2_type");
        if ("TRANSACTION".equals(from) && "TRIP".equals(to)) {
            addEdge(forward, rowKey(bare(item.get("id1"))), bare(item.get("id2")));
        } else if ("TRIP".equals(from) && "TRANSACTION".equals(to)) {
            addEdge(reverse, bare(item.get("id1")), rowKey(bare(item.get("id2"))));
        }
    }

    /** A stored binding id is "{typeId}_{value}": the number only namespaces the types. */
    private static String bare(final String stored) {
        return stored.substring(stored.indexOf('_') + 1);
    }

    /** A row's binding key, normalized the way {@code BindingDAO} writes it. */
    private static String rowKey(final String composite) {
        return CompositeKey.from(composite).getValue();
    }

    private void addTrip(final Map<String, String> item) {
        final JsonNode trip = content(item);
        final LocalDate start = date(trip.path("startDate"));
        final LocalDate end = date(trip.path("endDate"));
        final Set<String> roster = new LinkedHashSet<>();
        trip.path("people").forEach(person -> roster.add(person.asText()));
        final String id = item.get("id");
        trips.put(id, new TripInfo(id, trip.path("title").asText(""), text(trip.path("orgId")),
                start == null ? end : start, end == null ? start : end, roster));
    }

    private void addRegistration(final Map<String, String> item) {
        final Registration.Status status = Registration.Status.fromDescription(
                content(item).path("status").asText(""));
        // A status the model does not know reads as NOT_REGISTERED there too.
        if (status != null && status != Registration.Status.NOT_REGISTERED) {
            registered.computeIfAbsent(item.get("tripId"), na -> new HashSet<>()).add(item.get("userId"));
        }
    }

    private void addPerson(final Map<String, String> item) {
        final JsonNode person = content(item);
        people.put(item.get("id"), new PersonName(person.path("first").asText(""), person.path("last").asText("")));
    }

    private void addOrganization(final Map<String, String> item) {
        orgNames.put(item.get("id"), content(item).path("name").asText(""));
    }

    private static JsonNode content(final Map<String, String> item) {
        try {
            return MAPPER.readTree(item.getOrDefault(CONTENT, "{}"));
        } catch (final IOException ex) {
            throw new IllegalStateException("Unreadable row " + item.keySet(), ex);
        }
    }

    private static String text(final JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static LocalDate date(final JsonNode node) {
        return node.isTextual() ? LocalDateTime.parse(node.asText()).toLocalDate() : null;
    }

    // ------------------------------------------------------------------ rows

    /** Every row, deleted ones included, in the order read. */
    Collection<Row> rows() {
        return rows.values();
    }

    Row row(final String key) {
        return rows.get(key);
    }

    /** The live row a binding names, or null when it is deleted or gone. */
    Row liveRow(final String key) {
        final Row row = rows.get(key);
        return row != null && row.live() ? row : null;
    }

    List<Row> liveRows() {
        return rows.values().stream().filter(Row::live).toList();
    }

    /** Every partition holding a live row: people and, for a trip's own rows, trips. */
    Set<String> partitions() {
        return new TreeSet<>(liveByUser.keySet());
    }

    /** A partition's live rows in the order the app reads them. */
    List<Row> liveRowsOf(final String userId) {
        return liveByUser.getOrDefault(userId, List.of());
    }

    /** A partition as it is stored, soft-deleted rows included, in the order the app reads it. */
    List<Row> allRowsOf(final String userId) {
        return allByUser.getOrDefault(userId, List.of());
    }

    /** The live rows of a Shared or Batch group, across its members. */
    List<Row> group(final String groupId) {
        return liveByGroup.getOrDefault(groupId, List.of());
    }

    Set<String> groupIds() {
        return liveByGroup.keySet();
    }

    /** Who a group row is split between, as today's share rule reads its stamp: a row with none counts itself. */
    static Set<String> membersOf(final Row row) {
        final Set<String> members = new TreeSet<>();
        SHARES.getUserIdsForGroup(row.tx()).forEach(id -> members.add(id.getValue()));
        return members;
    }

    // ------------------------------------------------------------------ bindings

    /** The trips a row is bound to (its TRANSACTION to TRIP bindings). */
    List<String> tripsOf(final String rowKey) {
        return forward.getOrDefault(rowKey, List.of());
    }

    /** The row keys on a trip's books (its TRIP to TRANSACTION bindings), live or not. */
    List<String> rowsOf(final String tripId) {
        return reverse.getOrDefault(tripId, List.of());
    }

    Map<String, List<String>> forward() {
        return forward;
    }

    Map<String, List<String>> reverse() {
        return reverse;
    }

    void bindForward(final String rowKey, final String tripId) {
        addEdge(forward, rowKey, tripId);
    }

    void bindReverse(final String tripId, final String rowKey) {
        addEdge(reverse, tripId, rowKey);
    }

    boolean unbindForward(final String rowKey, final String tripId) {
        return removeEdge(forward, rowKey, tripId);
    }

    boolean unbindReverse(final String tripId, final String rowKey) {
        return removeEdge(reverse, tripId, rowKey);
    }

    private static void addEdge(final Map<String, List<String>> edges, final String from, final String to) {
        final List<String> list = edges.computeIfAbsent(from, na -> new ArrayList<>());
        if (!list.contains(to)) {
            list.add(to);
        }
    }

    private static boolean removeEdge(final Map<String, List<String>> edges, final String from, final String to) {
        final List<String> list = edges.get(from);
        if (list == null || !list.remove(to)) {
            return false;
        }
        if (list.isEmpty()) {
            edges.remove(from);
        }
        return true;
    }

    // ------------------------------------------------------------------ trips, people, organizations

    Map<String, TripInfo> trips() {
        return trips;
    }

    /** Whether the person is on the trip: on its roster, or registered with any status but NOT_REGISTERED. */
    boolean onTrip(final TripInfo trip, final String userId) {
        return trip.people().contains(userId) || registered.getOrDefault(trip.id(), Set.of()).contains(userId);
    }

    PersonName person(final String userId) {
        return people.get(userId);
    }

    String orgName(final String orgId) {
        return orgNames.getOrDefault(orgId, "");
    }

    Set<String> orgIds() {
        return orgNames.keySet();
    }

    // ------------------------------------------------------------------ arithmetic and hashing

    /**
     * Today's balance arithmetic ({@code TransactionsCommands.getBalance}): the per-user amounts added in double,
     * in the order given, with nothing rounded on the way.
     */
    static double fold(final Collection<Row> rows) {
        double total = 0d;
        for (final Row row : rows) {
            if (row.share() != null) {
                total += row.share();
            }
        }
        return total;
    }

    /** A finished total in whole cents: the one place a figure is rounded. */
    static long cents(final double total) {
        return Math.round(total * 100d);
    }

    /**
     * A row's content as this tool's own mapper writes it, hashed: equal for a row planned from a dump and the
     * same row read through the DAO at apply time, whatever key order or legacy fields its stored JSON has.
     */
    static String fingerprint(final Transaction tx) {
        try {
            return sha256(MAPPER.writeValueAsString(tx));
        } catch (final IOException ex) {
            throw new IllegalStateException("Unable to fingerprint transaction " + tx.getTxId(), ex);
        }
    }

    static String sha256(final String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The mapper the files of a run are written and read with. */
    static ObjectMapper mapper() {
        return MAPPER;
    }
}
