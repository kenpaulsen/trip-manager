package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.LedgerCommands.OrgBalance;
import org.paulsens.trip.action.LedgerCommands.PersonBalance;
import org.paulsens.trip.action.LedgerCommands.Row;
import org.paulsens.trip.action.LedgerCommands.State;
import org.paulsens.trip.action.LedgerCommands.Statement;
import org.paulsens.trip.action.LedgerCommands.TripGroup;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.CompositeKey;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;

/**
 * The per-organization ledger's arithmetic, with no store and no request behind it: the subject(s)' live rows cut
 * down to what a viewer sees, bucketed by organization, folded into balances and laid out as statements grouped by
 * trip. {@link LedgerCommands} ({@code #{ledger}}) is this over the app's caches and the request's reach; the
 * ledger migration's verifier ({@code migrate/LedgerStatementSource}) is this over a raw dump at system reach.
 * Both go through here on purpose, so the proof that "every live row is on exactly one statement, and nobody owes
 * more or less" is a proof about the code the Balance page runs, not about a copy of it.
 *
 * <p>Everything it needs from outside comes through {@link Source}. One instance answers one call: the partitions
 * are read once, in the constructor.
 */
@Slf4j
public final class LedgerStatements {

    /** Where the rows, their bindings and the names come from, and who is looking. */
    public interface Source {
        /** The subject's rows, every organization, in the order the app reads a ledger; deleted ones may be in. */
        List<Transaction> partition(Person.Id subject);

        /** Whether the viewer is shown this subject's rows of this organization (null: rows with none). */
        boolean sees(Person.Id subject, String orgId);

        /** What a row (by its binding key) is bound to, of one type. */
        List<String> bindings(String rowKey, BindingType destType);

        /** The stored trip, or null when it is gone. */
        Trip trip(String tripId);

        String orgName(String orgId);

        String personName(Person.Id personId);

        /** The person's preferred (first) name, for a pill or a row label; blank when there is no such person. */
        String preferredName(Person.Id personId);

        /**
         * Whether this trip takes a payment in the app (its effective payment configuration can take one). Asked
         * only for a trip group with an amount due, so a statement of paid-up trips resolves no configuration.
         */
        boolean payableInApp(Trip trip);

        String eventTitle(String eventId);
    }

    /** For {@code getUserAmount}, THE share rule; nothing else of that bean is used here. */
    private static final TransactionsCommands SHARES = new TransactionsCommands();

    static final Comparator<Transaction> BY_DATE = Comparator
            .comparing(Transaction::getTxDate, Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder()))
            .thenComparing(Transaction::getTxId);

    /** Trips newest first, one with no start date after the dated ones: the pickers' order, ties by id. */
    private static final Comparator<Trip> NEWEST_FIRST = TripCommands.NEWEST_FIRST.thenComparing(Trip::getId);

    /** A statement's groups: its trips newest first, then the one group of rows on no trip. */
    private static final Comparator<List<Placed>> GROUP_ORDER = Comparator
            .comparing((List<Placed> rows) -> rows.getFirst().trip() == null)
            .thenComparing(rows -> rows.getFirst().trip(), NEWEST_FIRST);

    /** One org's visible rows, by subject, in partition order. */
    private static final class OrgRows {
        private final Map<Person.Id, List<Transaction>> bySubject = new LinkedHashMap<>();

        int count() {
            return bySubject.values().stream().mapToInt(List::size).sum();
        }
    }

    /** A row on its way into a statement: the transaction, its share, and where its bindings put it. */
    private record Placed(Transaction tx, double amount, Trip trip, String eventId) {
    }

    private final Source source;
    private final List<Person.Id> subjects;
    /** Org key (an org id, or {@link LedgerCommands#UNASSIGNED_ORG_ID}) to its rows. */
    private final Map<String, OrgRows> rowsByOrg = new LinkedHashMap<>();

    /** Reads each subject's partition ONCE and keeps what the viewer sees of it. */
    public LedgerStatements(final Source source, final Collection<Person.Id> subjects) {
        this.source = source;
        this.subjects = subjects == null ? List.of()
                : List.copyOf(new LinkedHashSet<>(subjects.stream().filter(Objects::nonNull).toList()));
        for (final Person.Id subject : this.subjects) {
            source.partition(subject).forEach(tx -> take(subject, tx));
        }
    }

    private void take(final Person.Id subject, final Transaction tx) {
        final String orgId = blankToNull(tx.getOrgId());
        if (tx.getDeleted() != null || !source.sees(subject, orgId)) {
            return;
        }
        if (orgId == null) {
            // Should not exist (every new row takes its trip's organization). Shown to its owner all the
            // same, and loudly: a row that vanished from a balance is the one failure nobody would report.
            log.error("Ledger row {} of {} has no organization; reported as '{}'", tx.getTxId(),
                    subject.getValue(), LedgerCommands.UNASSIGNED_ORG_ID);
        }
        rowsByOrg.computeIfAbsent(orgId == null ? LedgerCommands.UNASSIGNED_ORG_ID : orgId, key -> new OrgRows())
                .bySubject.computeIfAbsent(subject, key -> new ArrayList<>()).add(tx);
    }

    /** The subjects, de-duplicated, in the order given. */
    public List<Person.Id> subjects() {
        return subjects;
    }

    // ------------------------------------------------------------------ balances

    /** Every organization the visible rows are with, by org key in the order first met, each with its balance. */
    public Map<String, OrgBalance> balances() {
        final Map<String, OrgBalance> found = new LinkedHashMap<>();
        rowsByOrg.forEach((orgKey, rows) -> found.put(orgKey, balanceOf(orgKey, rows)));
        return found;
    }

    private OrgBalance balanceOf(final String orgKey, final OrgRows rows) {
        double total = 0d;
        for (final List<Transaction> mine : rows.bySubject.values()) {
            total += fold(mine);
        }
        final long cents = cents(total);
        final boolean unassigned = LedgerCommands.UNASSIGNED_ORG_ID.equals(orgKey);
        return new OrgBalance(orgKey, unassigned ? LedgerCommands.unassignedOrgName() : source.orgName(orgKey),
                unassigned, total, cents, rows.count(), State.of(cents, rows.count()));
    }

    /** {@code getBalance}'s fold over these rows: each row's exact share, summed in the order given. */
    private static double fold(final List<Transaction> rows) {
        double total = 0d;
        for (final Transaction tx : rows) {
            total += shareOf(tx);
        }
        return total;
    }

    static double shareOf(final Transaction tx) {
        final Float share = SHARES.getUserAmount(tx);
        return share == null ? 0d : share;
    }

    /**
     * A finished total in whole cents: THE rounding of every ledger figure (the Balance page, the trip footers,
     * the ledger migration's verifier), so no two of them can round the same rows differently.
     */
    public static long cents(final double amount) {
        return Math.round(amount * 100d);
    }

    /** The org key of the most recently dated visible row; null when there are no rows at all. */
    String orgOfLatestRow() {
        String orgKey = null;
        LocalDateTime latest = null;
        for (final Map.Entry<String, OrgRows> entry : rowsByOrg.entrySet()) {
            for (final List<Transaction> rows : entry.getValue().bySubject.values()) {
                for (final Transaction tx : rows) {
                    if (orgKey == null || isAfter(tx.getTxDate(), latest)) {
                        orgKey = entry.getKey();
                        latest = tx.getTxDate();
                    }
                }
            }
        }
        return orgKey;
    }

    private static boolean isAfter(final LocalDateTime date, final LocalDateTime latest) {
        return date != null && (latest == null || date.isAfter(latest));
    }

    // ------------------------------------------------------------------ the statement

    /**
     * One organization's statement (by org key), or null when the visible rows hold none of it: the caller
     * decides whether that is an empty statement of a known organization ({@link #emptyStatement}) or nothing.
     * Binding reads only for this organization's rows.
     */
    public Statement statement(final String orgKey) {
        final OrgRows rows = orgKey == null ? null : rowsByOrg.get(orgKey);
        if (rows == null) {
            return null;
        }
        final boolean unassigned = LedgerCommands.UNASSIGNED_ORG_ID.equals(orgKey);
        final Map<String, Optional<Trip>> tripMemo = new HashMap<>();
        final List<Placed> placed = new ArrayList<>();
        for (final List<Transaction> mine : rows.bySubject.values()) {
            for (final Transaction tx : mine) {
                placed.add(place(tx, unassigned ? null : orgKey, tripMemo));
            }
        }
        placed.sort(Comparator.comparing(Placed::tx, BY_DATE));
        final OrgBalance balance = balanceOf(orgKey, rows);
        // Nothing is owed the organization (zero, or a credit): a trip's amount due here is covered elsewhere.
        final List<TripGroup> groups = groupsOf(placed, runningByRow(placed), balance.balanceCents() >= 0L);
        double charges = 0d;
        double payments = 0d;
        for (final TripGroup group : groups) {
            charges += group.charges();
            payments += group.payments();
        }
        return new Statement(orgKey, balance.orgName(), unassigned, balance.state(), balance.balance(),
                balance.balanceCents(), charges, payments, rows.count(), groups, breakdownOf(rows));
    }

    /** The statement of an organization the subject(s) have no visible rows with: its name and zeroes. */
    Statement emptyStatement(final String orgId, final String orgName) {
        return new Statement(orgId, orgName, false, State.NO_ACTIVITY, 0d, 0L, 0d, 0d, 0, List.of(),
                breakdownOf(null));
    }

    /**
     * THE rule for whether a row of organization {@code orgId} is shown under its bound {@code trip}: when the
     * trip is that organization's, and always for a row with no organization. The statement here and the app's
     * transaction rows ({@code TransactionsResource}) both ask it, so a charge is never grouped under one trip on
     * the website and titled with another in the app.
     */
    public static boolean showsUnder(final Trip trip, final String orgId) {
        return orgId == null || orgId.equals(trip.getOrgId());
    }

    /**
     * Where a row belongs in its organization's statement: on the trip its binding names, when that trip exists
     * and is this organization's ({@code orgId} null: the unassigned statement, which takes any trip). A row
     * bound to another organization's trip breaks "a row's organization is its trip's": it is shown unassigned,
     * never under a trip title the organization's staff have no business reading, and logged.
     */
    private Placed place(final Transaction tx, final String orgId, final Map<String, Optional<Trip>> tripMemo) {
        final String key = keyOf(tx);
        final String tripId = source.bindings(key, BindingType.TRIP).stream().findFirst().orElse(null);
        final Trip trip = tripId == null ? null : tripMemo.computeIfAbsent(tripId, this::storedTrip).orElse(null);
        if (trip == null) {
            return new Placed(tx, shareOf(tx), null, null);
        }
        if (!showsUnder(trip, orgId)) {
            log.error("Ledger row {} of {} is with organization {} but bound to trip {} of organization {}",
                    tx.getTxId(), tx.getUserId().getValue(), orgId, trip.getId(), trip.getOrgId());
            return new Placed(tx, shareOf(tx), null, null);
        }
        final String eventId = source.bindings(key, BindingType.TRIP_EVENT).stream().findFirst().orElse(null);
        return new Placed(tx, shareOf(tx), trip, eventId);
    }

    /** A binding may name a trip that is gone, or a row that never got an id: neither is a trip to group by. */
    private Optional<Trip> storedTrip(final String tripId) {
        return Optional.ofNullable(source.trip(tripId)).filter(trip -> trip.getId() != null);
    }

    /** The statement-wide running balance after each row, in date order, by row key. */
    private static Map<String, Double> runningByRow(final List<Placed> placed) {
        final Map<String, Double> running = new HashMap<>();
        double total = 0d;
        for (final Placed row : placed) {
            total += row.amount();
            running.put(keyOf(row.tx()), total);
        }
        return running;
    }

    private List<TripGroup> groupsOf(final List<Placed> placed, final Map<String, Double> statementRunning,
            final boolean orgNotOwed) {
        final Map<String, List<Placed>> byTrip = new LinkedHashMap<>();
        for (final Placed row : placed) {
            byTrip.computeIfAbsent(row.trip() == null ? "" : row.trip().getId(), key -> new ArrayList<>()).add(row);
        }
        final Labels labels = new Labels();
        return byTrip.values().stream()
                .sorted(GROUP_ORDER)
                .map(rows -> groupOf(rows.getFirst().trip(), rows, statementRunning, labels, orgNotOwed))
                .toList();
    }

    /** The names and event titles one statement looks up, each asked of the source once. */
    private static final class Labels {
        private final Map<String, String> names = new HashMap<>();
        private final Map<String, String> preferred = new HashMap<>();
        private final Map<String, String> eventTitles = new HashMap<>();
    }

    /**
     * THE fold of a trip group: its rows in the order given, their running balance, charges and payments.
     * {@code orgNotOwed} is the statement's verdict that its organization is owed nothing, which makes an amount
     * due here one a credit elsewhere covers; false where there is no organization balance to judge by.
     */
    private TripGroup groupOf(final Trip trip, final List<Placed> rows, final Map<String, Double> statementRunning,
            final Labels labels, final boolean orgNotOwed) {
        final List<Row> shown = new ArrayList<>();
        double running = 0d;
        double charges = 0d;
        double payments = 0d;
        for (final Placed row : rows) {
            running += row.amount();
            if (row.amount() < 0) {
                charges -= row.amount();
            } else {
                payments += row.amount();
            }
            shown.add(rowOf(row, running, statementRunning.get(keyOf(row.tx())), labels));
        }
        final long cents = cents(running);
        return new TripGroup(trip == null ? null : trip.getId(),
                trip == null ? LedgerCommands.unassignedTripTitle() : trip.getTitle(),
                trip == null ? null : trip.getStartDate(), trip == null ? null : trip.getEndDate(), trip != null,
                List.copyOf(shown), charges, payments, running, cents,
                trip != null && cents < 0 && source.payableInApp(trip), cents < 0 && orgNotOwed);
    }

    private Row rowOf(final Placed row, final double running, final double statementRunning, final Labels labels) {
        final Transaction tx = row.tx();
        final String owner = tx.getUserId().getValue();
        return new Row(tx.getTxId(), tx.getUserId(),
                labels.names.computeIfAbsent(owner, id -> source.personName(tx.getUserId())),
                labels.preferred.computeIfAbsent(owner, id -> source.preferredName(tx.getUserId())),
                tx.getGroupId(), tx.isBatch() || tx.isShared(),
                tx.isShared(), tx.getTxDate(), tx.getCategory(), tx.getNote(),
                tx.getTxType() == null ? "" : tx.getTxType().name(), row.amount(), running, statementRunning,
                row.trip() == null ? null : row.trip().getId(), row.eventId(),
                row.eventId() == null ? "" : labels.eventTitles.computeIfAbsent(row.eventId(), source::eventTitle));
    }

    // ------------------------------------------------------------------ one trip's books

    /**
     * The subject(s)' rows on ONE trip's books, as a statement of a single trip group: the rows whose TRANSACTION
     * to TRIP binding names the trip, plus the trip's own rows when the trip itself is the subject (the rule of
     * {@code TripFinanceGate.onBooks}). For a viewer who is there by a role on the trip, so the rows are NOT cut
     * by organization here beyond what {@link Source#sees} already dropped; the caller has asked whether this
     * viewer may read the trip's books at all. The group is there even with no rows (its title and dates are the
     * trip's), and the header names the trip's organization.
     */
    public Statement tripStatement(final Trip trip) {
        final List<Placed> placed = new ArrayList<>();
        for (final OrgRows rows : rowsByOrg.values()) {
            for (final List<Transaction> mine : rows.bySubject.values()) {
                mine.stream().filter(tx -> onBooks(trip, tx)).forEach(tx -> placed.add(onTrip(trip, tx)));
            }
        }
        placed.sort(Comparator.comparing(Placed::tx, BY_DATE));
        // One trip's books carry no organization balance (the viewer may have no reach in it), so an amount due
        // is never "covered" here: this statement reads as it always did.
        final TripGroup group = groupOf(trip, placed, runningByRow(placed), new Labels(), false);
        return new Statement(trip.getOrgId(), source.orgName(trip.getOrgId()), false,
                State.of(group.balanceCents(), placed.size()), group.balance(), group.balanceCents(),
                group.charges(), group.payments(), placed.size(), List.of(group), List.of());
    }

    private boolean onBooks(final Trip trip, final Transaction tx) {
        return trip.getId().equals(tx.getUserId().getValue())
                || source.bindings(keyOf(tx), BindingType.TRIP).contains(trip.getId());
    }

    private Placed onTrip(final Trip trip, final Transaction tx) {
        final String eventId = source.bindings(keyOf(tx), BindingType.TRIP_EVENT).stream().findFirst().orElse(null);
        return new Placed(tx, shareOf(tx), trip, eventId);
    }

    /**
     * Each subject's own balance with one organization (by org key), in the order the subjects were given; empty
     * for a single subject. No binding reads: what the family pills need without building a statement.
     */
    public List<PersonBalance> breakdown(final String orgKey) {
        return breakdownOf(orgKey == null ? null : rowsByOrg.get(orgKey));
    }

    private List<PersonBalance> breakdownOf(final OrgRows rows) {
        if (subjects.size() < 2) {
            return List.of();
        }
        final List<PersonBalance> people = new ArrayList<>();
        for (final Person.Id subject : subjects) {
            final List<Transaction> mine = rows == null ? List.of() : rows.bySubject.getOrDefault(subject, List.of());
            final double balance = fold(mine);
            people.add(new PersonBalance(subject, source.personName(subject), source.preferredName(subject), balance,
                    cents(balance), mine.size(), State.of(cents(balance), mine.size())));
        }
        return List.copyOf(people);
    }

    /** A row's binding key, as {@code BindingDAO} writes it. */
    static String keyOf(final Transaction tx) {
        return CompositeKey.join(tx.getUserId().getValue(), tx.getTxId());
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
