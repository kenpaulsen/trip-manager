package org.paulsens.trip.migrate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.paulsens.trip.action.TripLedgerTotals;
import org.paulsens.trip.action.TripLedgerTotals.Bucket;
import org.paulsens.trip.migrate.LedgerBindingApplier.Approval;
import org.paulsens.trip.migrate.LedgerBindingPlan.Bind;
import org.paulsens.trip.migrate.LedgerBindingPlan.Kind;
import org.paulsens.trip.migrate.LedgerBindingPlan.Uncertain;
import org.paulsens.trip.migrate.LedgerBindingPlan.Why;
import org.paulsens.trip.migrate.LedgerData.PersonName;
import org.paulsens.trip.migrate.LedgerData.Row;
import org.paulsens.trip.migrate.LedgerData.TripInfo;
import org.paulsens.trip.model.CompositeKey;
import org.paulsens.trip.model.Transaction;

/**
 * What a plan looks like to the owner (docs/migrations/ledger-trip-binding.md): {@code uncertain.csv}, one line
 * per row that needs a decision, with everything needed to make it and an empty {@code approvedTripId} to fill;
 * {@code per-trip-summary.csv}, what would be bound where; and the counts. Also reads the review file back.
 */
final class LedgerPlanFiles {
    static final List<String> UNCERTAIN_HEADER = List.of("firstName", "lastName", "personId", "txId", "date",
            "shareAmount", "fullAmount", "type", "txType", "category", "note", "groupId", "groupMembers",
            "possibleTrips", "why", "bestGuessTripId", "bestGuessTrip", "bestGuessReason", "booksNote",
            "approvedTripId");
    static final List<String> TRIP_HEADER = List.of("tripId", "title", "start", "end", "ended", "tripOwnRows",
            "groupRows", "deterministicRows", "inferredRows", "rowsToBind", "tripTotalChange",
            "invoiceTotalChange", "userTotalChange", "uncertainRowsGuessedHere");

    private static final String BOOKS = "Binding puts this row on the trip's books: the trip's finance viewers can "
            + "read it and its finance admins can edit it.";
    private static final String RUNNING = " The best-guess trip has NOT ended: its reminders, tickets and check-in "
            + "would count this row.";

    private final LedgerData data;
    private final LedgerBindingPlan plan;
    private final LocalDate today;

    LedgerPlanFiles(final LedgerData data, final LedgerBindingPlan plan) {
        this.data = data;
        this.plan = plan;
        this.today = LocalDate.parse(plan.asOf());
    }

    // ------------------------------------------------------------------ uncertain.csv

    /** The review file: a header, then one line per uncertain row, by person and date. */
    List<String> uncertainCsv() {
        final List<String> lines = new ArrayList<>();
        lines.add(LedgerCsv.line(UNCERTAIN_HEADER));
        plan.uncertain().stream()
                .sorted(Comparator.comparing(this::sortName).thenComparing(Uncertain::userId)
                        .thenComparing(row -> data.row(row.key()), LedgerData.BY_DATE))
                .forEach(row -> lines.add(LedgerCsv.line(uncertainCells(row))));
        return lines;
    }

    private String sortName(final Uncertain row) {
        return (lastName(row.userId()) + " " + firstName(row.userId())).toLowerCase(Locale.ROOT);
    }

    private List<String> uncertainCells(final Uncertain open) {
        final Row row = data.row(open.key());
        final Transaction tx = row.tx();
        final TripInfo guess = open.bestGuessTripId() == null ? null : data.trips().get(open.bestGuessTripId());
        return List.of(LedgerCsv.typed(firstName(row.userId())), LedgerCsv.typed(lastName(row.userId())),
                row.userId(), row.txId(), tx.getTxDate() == null ? "" : tx.getTxDate().toLocalDate().toString(),
                amount(row.share()), amount(tx.getAmount()), tx.getType() == null ? "Tx" : tx.getType().name(),
                tx.getTxType().name(), LedgerCsv.typed(tx.getCategory()), LedgerCsv.typed(tx.getNote()),
                open.groupId() == null ? "" : open.groupId(), groupMembers(row),
                String.join("\n", open.candidateTripIds().stream().map(id -> describe(id, row.userId())).toList()),
                open.reason(), guess == null ? "" : guess.id(), guess == null ? "" : LedgerCsv.typed(guess.title()),
                open.bestGuessReason() == null ? "" : open.bestGuessReason(),
                BOOKS + (guess != null && !guess.ended(today) ? RUNNING : ""), "");
    }

    private String firstName(final String userId) {
        final PersonName name = data.person(userId);
        return name == null ? "" : name.first();
    }

    /** A person's last name; for a trip's own rows, the trip, so the line still says whose it is. */
    private String lastName(final String userId) {
        final PersonName name = data.person(userId);
        final TripInfo trip = data.trips().get(userId);
        return name != null ? name.last() : trip == null ? "" : "(trip) " + trip.title();
    }

    private String groupMembers(final Row row) {
        if (!row.grouped()) {
            return "";
        }
        return LedgerCsv.typed(String.join("; ", data.group(row.tx().getGroupId()).stream()
                .map(member -> (firstName(member.userId()) + " " + lastName(member.userId())).strip())
                .sorted()
                .toList()));
    }

    /** One possible trip: its title, dates, what the person's rows already on it net to, and its id. */
    private String describe(final String tripId, final String userId) {
        final TripInfo trip = data.trips().get(tripId);
        final List<Row> onIt = data.liveRowsOf(userId).stream()
                .filter(row -> data.tripsOf(row.key()).contains(tripId))
                .toList();
        return LedgerCsv.typed(trip.title()) + " | " + trip.dates() + " | "
                + (trip.ended(today) ? "ended" : "NOT ENDED") + " | net now "
                + LedgerBindingPlanner.money(LedgerData.cents(LedgerData.fold(onIt))) + " | " + tripId;
    }

    private static String amount(final Float amount) {
        return amount == null ? "" : String.format(Locale.ROOT, "%.2f", amount);
    }

    // ------------------------------------------------------------------ per-trip-summary.csv

    /** One line per trip a row would join or is guessed onto, earliest trip first. */
    List<String> perTripCsv() {
        final Map<String, List<Bind>> byTrip = new LinkedHashMap<>();
        plan.binds().forEach(bind -> byTrip.computeIfAbsent(bind.tripId(), na -> new ArrayList<>()).add(bind));
        plan.uncertain().stream().filter(row -> row.bestGuessTripId() != null)
                .forEach(row -> byTrip.computeIfAbsent(row.bestGuessTripId(), na -> new ArrayList<>()));
        final List<String> lines = new ArrayList<>();
        lines.add(LedgerCsv.line(TRIP_HEADER));
        // The planner's null-safe order: a trip with no dates is a legitimate best guess, and a bare
        // comparing(start) threw on it after plan.json was written, so the summary and plan.txt never were.
        byTrip.keySet().stream()
                .map(id -> data.trips().get(id))
                .sorted(LedgerBindingPlanner.BY_START)
                .forEach(trip -> lines.add(LedgerCsv.line(tripCells(trip, byTrip.get(trip.id())))));
        return lines;
    }

    private List<String> tripCells(final TripInfo trip, final List<Bind> joining) {
        final List<Transaction> rows = joining.stream().map(bind -> data.row(bind.key()).tx()).toList();
        final TripLedgerTotals change = TripLedgerTotals.of(trip.id(), rows, this::shareOf);
        final long guessed = plan.uncertain().stream().filter(row -> trip.id().equals(row.bestGuessTripId())).count();
        return List.of(trip.id(), LedgerCsv.typed(trip.title()), String.valueOf(trip.start()),
                String.valueOf(trip.end()), String.valueOf(trip.ended(today)), count(joining, Kind.TRIP_OWN),
                count(joining, Kind.GROUP), count(joining, Kind.DETERMINISTIC), count(joining, Kind.INFERRED),
                String.valueOf(joining.size()), LedgerBindingPlanner.money(change.cents(Bucket.TRIP)),
                LedgerBindingPlanner.money(change.cents(Bucket.INVOICE)),
                LedgerBindingPlanner.money(change.cents(Bucket.USER)), String.valueOf(guessed));
    }

    private Float shareOf(final Transaction tx) {
        return data.row(CompositeKey.join(tx.getUserId().getValue(), tx.getTxId())).share();
    }

    private static String count(final List<Bind> binds, final Kind kind) {
        return String.valueOf(binds.stream().filter(bind -> bind.kind() == kind).count());
    }

    // ------------------------------------------------------------------ plan.txt

    /** The plan in counts: nothing here names a person or an amount. */
    List<String> summary() {
        final List<String> lines = new ArrayList<>();
        lines.add("Ledger trip-binding plan (as of " + plan.asOf() + ")");
        lines.add("  live rows: " + data.liveRows().size() + "; bound to no trip: "
                + (plan.binds().size() + plan.uncertain().size()));
        lines.add("  to bind: " + plan.binds().size());
        for (final Kind kind : Kind.values()) {
            if (kind != Kind.APPROVED) {
                lines.add("    " + kind + ": " + plan.count(kind));
            }
        }
        lines.add("  uncertain (uncertain.csv): " + plan.uncertain().size());
        for (final Why why : Why.values()) {
            lines.add("    " + why + ": " + plan.count(why));
        }
        return lines;
    }

    // ------------------------------------------------------------------ reading the review file back

    /** The rows of a reviewed {@code uncertain.csv} whose {@code approvedTripId} the owner filled in. */
    static List<Approval> approvals(final String csv) {
        final List<List<String>> records = LedgerCsv.parse(csv);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("The approved file is empty: it needs uncertain.csv's header.");
        }
        final List<String> header = records.get(0);
        final int person = column(header, "personId");
        final int txId = column(header, "txId");
        final int trip = column(header, "approvedTripId");
        final List<Approval> approvals = new ArrayList<>();
        for (final List<String> record : records.subList(1, records.size())) {
            final String tripId = record.size() > trip ? record.get(trip).strip() : "";
            if (!tripId.isEmpty()) {
                approvals.add(new Approval(record.get(person).strip(), record.get(txId).strip(), tripId));
            }
        }
        return approvals;
    }

    private static int column(final List<String> header, final String name) {
        final int index = header.indexOf(name);
        if (index < 0) {
            throw new IllegalArgumentException("The approved file has no " + name + " column.");
        }
        return index;
    }
}
