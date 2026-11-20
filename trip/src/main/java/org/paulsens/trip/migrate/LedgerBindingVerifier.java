package org.paulsens.trip.migrate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.paulsens.trip.action.TripLedgerTotals;
import org.paulsens.trip.action.TripLedgerTotals.Bucket;
import org.paulsens.trip.migrate.LedgerData.Row;
import org.paulsens.trip.migrate.LedgerData.TripInfo;
import org.paulsens.trip.migrate.LedgerSnapshot.TripMoney;
import org.paulsens.trip.model.CompositeKey;

/**
 * The before and after proof of the ledger migration (docs/migrations/ledger-trip-binding.md), for EVERY person
 * and trip at system reach: nobody owes more or less, no row changed, every row is in exactly one statement, a
 * trip's totals moved only by the rows it gained, a running trip's balances did not move, and the ledger's
 * invariants hold. Seven numbered checks; a failed one lists the people, rows or trips it failed on.
 */
final class LedgerBindingVerifier {
    static final int CHECKS = 7;
    private static final TripMoney NOTHING = new TripMoney(0, 0);
    private static final TripLedgerTotals NO_TOTALS = new TripLedgerTotals(0d, 0d, 0d, 0);

    private final LedgerSnapshot before;
    private final LedgerData after;
    private final LocalDate today;
    private final Statements statements;
    private final Set<String> awaiting;
    private final List<String> lines = new ArrayList<>();
    private final List<String> details = new ArrayList<>();
    private final Set<Integer> failed = new TreeSet<>();
    private boolean onlyAwaiting = true;

    /**
     * A person's statements as the NEW per-organization ledger code builds them, by organization ("" for rows
     * that carry none). Checks 1, 2 and 4 read the new code through this alone; the real one is
     * {@link LedgerStatementSource#statements}, which runs {@code action/LedgerStatements} (the Balance page's own
     * arithmetic) over the data being verified.
     */
    @FunctionalInterface
    interface Statements {
        Map<String, OrgStatement> of(String personId);
    }

    /**
     * One organization's statement of one person.
     *
     * @param balance the balance the new code states (unrounded)
     * @param txIds   the rows the new code lists on it
     */
    record OrgStatement(double balance, List<String> txIds) {
    }

    /**
     * @param before     the figures before the apply
     * @param after      the ledger now
     * @param today      the day "has this trip ended" is asked on
     * @param statements the new statement code (see {@link LedgerStatementSource#statements})
     * @param awaiting   rows still waiting for the owner's approval: reported apart, a failure all the same
     */
    LedgerBindingVerifier(final LedgerSnapshot before, final LedgerData after, final LocalDate today,
            final Statements statements, final Set<String> awaiting) {
        this.before = before;
        this.after = after;
        this.today = today;
        this.statements = statements;
        this.awaiting = awaiting;
    }

    /** Runs all seven checks. */
    LedgerBindingVerifier verify() {
        checkBalances();
        checkOrgBalances();
        checkRowsUnchanged();
        checkStatements();
        checkTripTotals();
        checkTripBound();
        checkInvariants();
        return this;
    }

    /** One line per check ("PASS n ..." or "FAIL n ..."), each failure followed by what it failed on. */
    List<String> lines() {
        return lines;
    }

    /** The itemized report: per trip, the rows it gained and what they did to its totals. */
    List<String> details() {
        return details;
    }

    /** The numbers of the checks that failed. */
    Set<Integer> failed() {
        return failed;
    }

    /** Whether the ONLY failure is rows of check 7 that still wait for the owner's approval. */
    boolean onlyAwaitingApproval() {
        return failed.equals(Set.of(CHECKS)) && onlyAwaiting;
    }

    private void report(final int check, final String title, final List<String> problems) {
        if (problems.isEmpty()) {
            lines.add("PASS " + check + " " + title);
            return;
        }
        failed.add(check);
        lines.add("FAIL " + check + " " + title + ": " + problems.size() + " problem(s)");
        problems.forEach(problem -> lines.add("    " + problem));
    }

    private Set<String> everyone() {
        final Set<String> people = new TreeSet<>(before.balance().keySet());
        people.addAll(after.partitions());
        return people;
    }

    /** The rows a person's statements list for one organization, as the app would read them. */
    private List<Row> statementRows(final String personId, final List<String> txIds) {
        final List<Row> rows = new ArrayList<>();
        for (final String txId : txIds) {
            final Row row = after.liveRow(CompositeKey.join(personId, txId));
            if (row != null) {
                rows.add(row);
            }
        }
        rows.sort(LedgerData.BY_DATE);
        return rows;
    }

    // ------------------------------------------------------------------ 1: balances

    private void checkBalances() {
        final List<String> problems = new ArrayList<>();
        for (final String person : everyone()) {
            double stated = 0d;
            for (final OrgStatement statement : statements.of(person).values()) {
                stated += statement.balance();
            }
            final long was = LedgerData.cents(before.balance().getOrDefault(person, 0d));
            final long now = LedgerData.cents(stated);
            if (was != now) {
                problems.add(person + ": balance " + money(was) + " before, " + money(now) + " by the new code");
            }
        }
        report(1, "every person's balance equals the new code's, to the cent (" + everyone().size() + " people)",
                problems);
    }

    // ------------------------------------------------------------------ 2: per-organization balances

    private void checkOrgBalances() {
        final List<String> problems = new ArrayList<>();
        for (final String person : everyone()) {
            final Map<String, Double> was = before.balanceByOrg().getOrDefault(person, Map.of());
            final Map<String, OrgStatement> now = statements.of(person);
            final Set<String> orgs = new TreeSet<>(was.keySet());
            orgs.addAll(now.keySet());
            double sum = 0d;
            for (final String org : orgs) {
                final double balance = now.containsKey(org) ? now.get(org).balance() : 0d;
                sum += balance;
                if (LedgerData.cents(was.getOrDefault(org, 0d)) != LedgerData.cents(balance)) {
                    problems.add(person + " in organization '" + org + "': "
                            + money(LedgerData.cents(was.getOrDefault(org, 0d))) + " before, "
                            + money(LedgerData.cents(balance)) + " now");
                }
            }
            final long total = LedgerData.cents(LedgerData.fold(after.liveRowsOf(person)));
            if (LedgerData.cents(sum) != total) {
                problems.add(person + ": the organizations sum to " + money(LedgerData.cents(sum))
                        + ", the org-blind total is " + money(total));
            }
        }
        report(2, "every per-organization balance is unchanged and they sum to the org-blind total", problems);
    }

    // ------------------------------------------------------------------ 3: rows unchanged

    private void checkRowsUnchanged() {
        final List<String> problems = new ArrayList<>();
        for (final Map.Entry<String, String> entry : before.liveHashes().entrySet()) {
            final Row row = after.row(entry.getKey());
            if (row == null) {
                problems.add("GONE " + entry.getKey());
            } else if (!row.live()) {
                problems.add("DELETED SINCE " + entry.getKey());
            } else if (!row.hash().equals(entry.getValue())) {
                problems.add("CHANGED " + entry.getKey());
            }
        }
        for (final Row row : after.liveRows()) {
            if (!before.liveHashes().containsKey(row.key())) {
                problems.add("ADDED SINCE " + row.key());
            }
        }
        report(3, "every live row is still stored, byte for byte (" + before.liveHashes().size()
                + " rows; ordinary activity since the snapshot shows here: take a fresh one)", problems);
    }

    // ------------------------------------------------------------------ 4: statements

    private void checkStatements() {
        final List<String> problems = new ArrayList<>();
        for (final String person : after.partitions()) {
            final Set<String> seen = new HashSet<>();
            for (final Map.Entry<String, OrgStatement> statement : statements.of(person).entrySet()) {
                final OrgStatement stated = statement.getValue();
                // A statement whose balance is not the fold of the rows it lists would pass checks 1 and 2 on
                // its figure and this one on its rows while showing a reader two things that disagree.
                final long listed = LedgerData.cents(LedgerData.fold(statementRows(person, stated.txIds())));
                if (listed != LedgerData.cents(stated.balance())) {
                    problems.add(person + " in organization '" + statement.getKey() + "': the statement says "
                            + money(LedgerData.cents(stated.balance())) + ", its rows add up to " + money(listed));
                }
                for (final String txId : stated.txIds()) {
                    final Row row = after.liveRow(CompositeKey.join(person, txId));
                    if (row == null) {
                        problems.add(CompositeKey.join(person, txId) + ": on a statement, but not a live row");
                    } else if (!row.orgId().equals(statement.getKey())) {
                        problems.add(row.key() + ": on the statement of organization '" + statement.getKey()
                                + "', not its own");
                    }
                    if (!seen.add(txId)) {
                        problems.add(CompositeKey.join(person, txId) + ": on more than one statement");
                    }
                }
            }
            after.liveRowsOf(person).stream()
                    .filter(row -> !seen.contains(row.txId()))
                    .forEach(row -> problems.add(row.key() + ": on no statement"));
        }
        report(4, "every live row is on exactly one statement of its owner, its own organization's, and each "
                + "statement's balance is the sum of its rows", problems);
    }

    // ------------------------------------------------------------------ 5: trip totals

    private void checkTripTotals() {
        final List<String> problems = new ArrayList<>();
        final Set<String> unboundBefore = new HashSet<>(before.unboundLive());
        final Set<String> tripIds = new TreeSet<>(before.tripTotals().keySet());
        tripIds.addAll(after.trips().keySet());
        int gained = 0;
        for (final String tripId : tripIds) {
            final TripLedgerTotals was = before.tripTotals().getOrDefault(tripId, NO_TOTALS);
            final Set<String> hadRows = new HashSet<>(before.tripRows().getOrDefault(tripId, List.of()));
            final TripLedgerTotals without = LedgerSnapshot.totals(after, tripId, row -> hadRows.contains(row.key()));
            for (final Bucket bucket : Bucket.values()) {
                if (was.cents(bucket) != without.cents(bucket)) {
                    problems.add(label(tripId) + " " + bucket + ": " + money(was.cents(bucket)) + " before, "
                            + money(without.cents(bucket)) + " now without its newly bound rows");
                }
            }
            gained += itemize(tripId, was, hadRows, unboundBefore, problems);
        }
        report(5, "every trip's three totals are identical, or differ by exactly its newly bound rows (" + gained
                + " rows newly bound, itemized below the checks)", problems);
    }

    /**
     * Lists the rows a trip gained and its totals with them; answers how many. A gained row that was on another
     * trip's books before is a problem here as well as on the trip that lost it: only a trip-less row may join.
     */
    private int itemize(final String tripId, final TripLedgerTotals was, final Set<String> hadRows,
            final Set<String> unboundBefore, final List<String> problems) {
        final List<Row> gained = new ArrayList<>();
        for (final String key : after.rowsOf(tripId)) {
            final Row row = after.liveRow(key);
            if (row != null && !hadRows.contains(key)) {
                gained.add(row);
            }
        }
        if (gained.isEmpty()) {
            return 0;
        }
        final TripLedgerTotals now = LedgerSnapshot.totals(after, tripId, row -> true);
        details.add(label(tripId) + ": " + gained.size() + " newly bound row(s)");
        for (final Bucket bucket : Bucket.values()) {
            details.add("    " + bucket + " total " + money(was.cents(bucket)) + " -> " + money(now.cents(bucket)));
        }
        gained.sort(LedgerData.BY_DATE);
        for (final Row row : gained) {
            details.add("    + " + row.key() + " " + TripLedgerTotals.bucketOf(tripId, row.tx()) + " "
                    + (row.share() == null ? "no amount" : row.share()));
            if (!unboundBefore.contains(row.key())) {
                problems.add(label(tripId) + " gained " + row.key() + ", which was not a trip-less row before");
            }
        }
        return gained.size();
    }

    private String label(final String tripId) {
        final TripInfo trip = after.trips().get(tripId);
        return trip == null ? "trip " + tripId + " (no longer exists)" : "trip " + tripId + " (" + trip.title() + ")";
    }

    // ------------------------------------------------------------------ 6: balances bound to a running trip

    private void checkTripBound() {
        final List<String> problems = new ArrayList<>();
        final Set<String> tripIds = new TreeSet<>(before.tripBound().keySet());
        after.trips().values().stream().filter(trip -> !trip.ended(today)).forEach(trip -> tripIds.add(trip.id()));
        final Map<String, Map<String, TripMoney>> now = LedgerSnapshot.tripBound(after, tripIds);
        int compared = 0;
        for (final String tripId : tripIds) {
            final Map<String, TripMoney> was = before.tripBound().getOrDefault(tripId, Map.of());
            final Set<String> people = new TreeSet<>(was.keySet());
            people.addAll(now.get(tripId).keySet());
            compared += people.size();
            for (final String person : people) {
                final TripMoney then = was.getOrDefault(person, NOTHING);
                final TripMoney current = now.get(tripId).getOrDefault(person, NOTHING);
                if (!then.equals(current)) {
                    problems.add(label(tripId) + " person " + person + ": paid " + money(then.paidCents())
                            + " billed " + money(then.billedCents()) + " before, paid "
                            + money(current.paidCents()) + " billed " + money(current.billedCents()) + " now");
                }
            }
        }
        report(6, "the trip-bound balance of everyone on a trip that has not ended is unchanged (" + tripIds.size()
                + " trips, " + compared + " balances)", problems);
    }

    // ------------------------------------------------------------------ 7: invariants

    private void checkInvariants() {
        final List<String> problems = new ArrayList<>();
        final Map<String, List<String>> found = new LinkedHashMap<>();
        for (final Row row : after.liveRows()) {
            final List<String> trips = after.tripsOf(row.key());
            if (trips.isEmpty()) {
                note(found, awaiting.contains(row.key()) ? "trip-less row awaiting approval" : "trip-less row",
                        row.key());
            }
            if (row.orgId().isEmpty()) {
                note(found, "row with no organization", row.key());
            }
            if (trips.size() > 1) {
                note(found, "row bound to several trips", row.key());
            }
            for (final String tripId : trips) {
                final TripInfo trip = after.trips().get(tripId);
                if (trip == null) {
                    note(found, "row bound to a missing trip", row.key() + " -> " + tripId);
                } else if (!row.orgId().equals(trip.orgId())) {
                    note(found, "row whose organization is not its trip's", row.key() + " -> " + tripId);
                }
            }
        }
        after.trips().values().stream().filter(trip -> trip.orgId() == null)
                .forEach(trip -> note(found, "trip with no organization", trip.id()));
        checkDirections(found);
        checkGroups(found);
        found.forEach((kind, where) -> problems.add(kind + ": " + where.size() + " (" + String.join(" ", where)
                + ")"));
        onlyAwaiting = found.keySet().equals(Set.of("trip-less row awaiting approval"));
        report(CHECKS, "invariants: no trip-less or org-less row, no org-less trip, no org mismatch, one trip per "
                + "row, both binding directions, one trip per group, group membership equals its live rows",
                problems);
    }

    private static void note(final Map<String, List<String>> found, final String kind, final String where) {
        found.computeIfAbsent(kind, na -> new ArrayList<>()).add(where);
    }

    private void checkDirections(final Map<String, List<String>> found) {
        after.forward().forEach((key, trips) -> trips.stream()
                .filter(tripId -> !after.rowsOf(tripId).contains(key))
                .forEach(tripId -> note(found, "binding without its TRIP to TRANSACTION half", key + " -> " + tripId)));
        after.reverse().forEach((tripId, keys) -> keys.stream()
                .filter(key -> !after.tripsOf(key).contains(tripId))
                .forEach(key -> note(found, "binding without its TRANSACTION to TRIP half", tripId + " -> " + key)));
    }

    private void checkGroups(final Map<String, List<String>> found) {
        for (final String groupId : after.groupIds()) {
            final List<Row> rows = after.group(groupId);
            final Set<List<String>> trips = new HashSet<>();
            final Set<String> members = new TreeSet<>();
            for (final Row row : rows) {
                trips.add(after.tripsOf(row.key()).stream().sorted().toList());
                members.add(row.userId());
            }
            if (trips.size() > 1) {
                note(found, "group not on one trip", groupId);
            }
            if (rows.stream().anyMatch(row -> !LedgerData.membersOf(row).equals(members))) {
                note(found, "group whose stamped membership is not its live rows", groupId);
            }
        }
    }

    private static String money(final long cents) {
        return LedgerBindingPlanner.money(cents);
    }
}
