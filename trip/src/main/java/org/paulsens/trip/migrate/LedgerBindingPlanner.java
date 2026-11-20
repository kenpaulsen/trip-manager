package org.paulsens.trip.migrate;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.paulsens.trip.action.LedgerStatements;
import org.paulsens.trip.migrate.LedgerBindingPlan.Bind;
import org.paulsens.trip.migrate.LedgerBindingPlan.Kind;
import org.paulsens.trip.migrate.LedgerBindingPlan.Uncertain;
import org.paulsens.trip.migrate.LedgerBindingPlan.Why;
import org.paulsens.trip.migrate.LedgerData.Row;
import org.paulsens.trip.migrate.LedgerData.TripInfo;

/**
 * Decides the trip of every live ledger row bound to none (docs/migrations/ledger-trip-binding.md), reading only.
 * The rules, in the order they apply:
 *
 * <ol>
 *   <li>A group (Shared or Batch) is decided as a unit and never split: the rest of a partly bound group joins
 *       the trip the others are on; a wholly unbound one binds to the ONE in-window trip common to every
 *       member, and with none or several the whole group goes to the owner.</li>
 *   <li>A trip's own row (its partition is the trip) binds to that trip.</li>
 *   <li>A single row with exactly one in-window trip binds to it.</li>
 *   <li>A single row with several is inferred, per person and organization, by walking the person's rows in date
 *       order, and accepted only when every trip then nets to zero (or one ended trip holds exactly the
 *       person's balance). One row that cannot be placed sends all of that person's inferred rows to the
 *       owner.</li>
 * </ol>
 * A trip is a candidate for a row when it belongs to the row's organization and the person is on it (roster, or a
 * registration that is not NOT_REGISTERED); it is "in window" from 18 months before its start to 4 months after
 * its end. NOTHING binds onto a trip that has not ended: reminders, tickets and check-in read the trip-bound
 * balance of a running trip, so those rows wait for the owner.
 */
final class LedgerBindingPlanner {
    /** Trips by start, one with no dates last, ties by id. The plan's files sort with it too. */
    static final Comparator<TripInfo> BY_START = Comparator.comparing(TripInfo::start,
            Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(TripInfo::id);

    private final LedgerData data;
    private final LocalDate today;
    private final List<Bind> binds = new ArrayList<>();
    private final List<Uncertain> uncertain = new ArrayList<>();
    /** Row key to the trip decided so far, so the inference counts a planned row on its trip. */
    private final Map<String, String> planned = new LinkedHashMap<>();

    /** A trip's money for one person during the inference walk. */
    private static final class TripState {
        private double net;
        private boolean billed;

        void add(final Row row) {
            net += row.share() == null ? 0d : row.share();
            billed = billed || row.tx().isBill();
        }

        long cents() {
            return LedgerStatements.cents(net);
        }

        /** Invoiced and covered: a trip nobody has billed yet is still waiting for its payments. */
        boolean fullyPaid() {
            return billed && cents() >= 0;
        }
    }

    /** Where the walk put one row, and the rule that put it there. */
    private record Placement(String tripId, String rule) {
    }

    LedgerBindingPlanner(final LedgerData data, final LocalDate today) {
        this.data = data;
        this.today = today;
    }

    LedgerBindingPlan plan() {
        final List<Row> unbound = data.liveRows().stream()
                .filter(row -> data.tripsOf(row.key()).isEmpty())
                .sorted(Comparator.comparing(Row::userId).thenComparing(LedgerData.BY_DATE))
                .toList();
        final Set<String> groupIds = new LinkedHashSet<>();
        unbound.stream().filter(Row::grouped).forEach(row -> groupIds.add(row.tx().getGroupId()));
        groupIds.forEach(this::planGroup);
        final Map<String, List<Row>> severalTrips = new LinkedHashMap<>();
        for (final Row row : unbound) {
            if (!row.grouped()) {
                planSingle(row, severalTrips);
            }
        }
        severalTrips.values().forEach(this::infer);
        return new LedgerBindingPlan(today.toString(), List.copyOf(binds), List.copyOf(uncertain));
    }

    // ------------------------------------------------------------------ candidates

    private boolean tripOwn(final Row row) {
        return data.trips().containsKey(row.userId());
    }

    /** The trips a row could belong to at all, earliest first. */
    private List<TripInfo> candidates(final Row row) {
        if (tripOwn(row)) {
            return List.of(data.trips().get(row.userId()));
        }
        return data.trips().values().stream()
                .filter(trip -> row.orgId().equals(trip.orgId()) && data.onTrip(trip, row.userId()))
                .sorted(BY_START)
                .toList();
    }

    /** The candidates whose window holds the row's date; a trip's own row needs no window. */
    private List<TripInfo> inWindow(final Row row) {
        return tripOwn(row) ? candidates(row)
                : candidates(row).stream().filter(trip -> trip.windowHolds(row.date())).toList();
    }

    private static List<String> ids(final List<TripInfo> trips) {
        return trips.stream().map(TripInfo::id).toList();
    }

    /** What the review file lists for a row: the in-window trips, else every trip the person is on. */
    private List<String> possible(final Row row) {
        final List<TripInfo> window = inWindow(row);
        return ids(window.isEmpty() ? candidates(row) : window);
    }

    // ------------------------------------------------------------------ recording

    private void bind(final Row row, final String tripId, final Kind kind, final String reason) {
        binds.add(new Bind(row.userId(), row.txId(), tripId, kind, reason, row.tx().getGroupId(),
                LedgerData.fingerprint(row.tx())));
        planned.put(row.key(), tripId);
    }

    private void defer(final Row row, final Why why, final String detail, final String guess,
            final String guessReason, final String required) {
        uncertain.add(new Uncertain(row.userId(), row.txId(), row.tx().getGroupId(), why,
                detail == null ? why.text() : why.text() + ": " + detail, possible(row), guess, guessReason,
                required, LedgerData.fingerprint(row.tx())));
    }

    /** Binds the rows to the trip when it is theirs to join and over; otherwise they wait, with it as the guess. */
    private void bindIfSettled(final List<Row> rows, final TripInfo trip, final Kind kind, final String reason,
            final String required) {
        final boolean sameOrg = rows.stream().allMatch(row -> row.orgId().equals(trip.orgId()));
        for (final Row row : rows) {
            if (!sameOrg) {
                defer(row, Why.ORG_MISMATCH, trip.title(), trip.id(), reason, required);
            } else if (!trip.ended(today)) {
                defer(row, Why.NOT_ENDED, trip.title(), trip.id(), reason, required);
            } else {
                bind(row, trip.id(), kind, reason);
            }
        }
    }

    // ------------------------------------------------------------------ groups

    private void planGroup(final String groupId) {
        final List<Row> members = data.group(groupId);
        final List<Row> pending = members.stream().filter(row -> data.tripsOf(row.key()).isEmpty()).toList();
        final Set<String> boundTo = new TreeSet<>();
        members.forEach(row -> boundTo.addAll(data.tripsOf(row.key())));
        if (boundTo.size() > 1) {
            pending.forEach(row -> defer(row, Why.GROUP_SPLIT, null, null, null, null));
        } else if (boundTo.size() == 1) {
            joinBoundGroup(pending, boundTo.iterator().next());
        } else {
            planUnboundGroup(members);
        }
    }

    private void joinBoundGroup(final List<Row> pending, final String tripId) {
        final TripInfo trip = data.trips().get(tripId);
        if (trip == null) {
            pending.forEach(row -> defer(row, Why.MISSING_TRIP, tripId, null, null, null));
            return;
        }
        bindIfSettled(pending, trip, Kind.GROUP, "the rest of this group is already on this trip", tripId);
    }

    private void planUnboundGroup(final List<Row> members) {
        final Set<String> common = new LinkedHashSet<>(ids(inWindow(members.get(0))));
        members.forEach(row -> common.retainAll(ids(inWindow(row))));
        if (common.size() == 1) {
            bindIfSettled(members, data.trips().get(common.iterator().next()), Kind.GROUP,
                    "the one in-window trip common to all " + members.size() + " rows of the group", null);
        } else if (common.isEmpty()) {
            final String guess = mostShared(members);
            members.forEach(row -> defer(row, Why.GROUP_NO_COMMON_TRIP, null, guess,
                    guess == null ? null : "the in-window trip most of the group's members are on", null));
        } else {
            final String guess = common.iterator().next();
            members.forEach(row -> defer(row, Why.GROUP_SEVERAL_TRIPS, null, guess,
                    "the earliest of the trips common to every member", null));
        }
    }

    /** The in-window trip most members are on, or null on a tie or when nobody has one. */
    private String mostShared(final List<Row> members) {
        final Map<String, Integer> votes = new LinkedHashMap<>();
        members.forEach(row -> ids(inWindow(row)).forEach(id -> votes.merge(id, 1, Integer::sum)));
        final int best = votes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        final List<String> leaders = votes.keySet().stream().filter(id -> votes.get(id) == best).toList();
        return leaders.size() == 1 ? leaders.get(0) : null;
    }

    // ------------------------------------------------------------------ singles

    private void planSingle(final Row row, final Map<String, List<Row>> severalTrips) {
        if (tripOwn(row)) {
            bindIfSettled(List.of(row), data.trips().get(row.userId()), Kind.TRIP_OWN,
                    "the row is the trip's own (its partition is the trip)", null);
            return;
        }
        if (row.date() == null) {
            defer(row, Why.NO_DATE, null, null, null, null);
            return;
        }
        final List<TripInfo> candidates = candidates(row);
        final List<TripInfo> window = inWindow(row);
        if (candidates.isEmpty()) {
            defer(row, Why.NO_ROSTER, null, null, null, null);
        } else if (window.isEmpty()) {
            defer(row, Why.NO_WINDOW, null, nearest(candidates, row.date()).id(),
                    "the nearest of the person's trips by date, outside its window", null);
        } else if (window.size() == 1) {
            bindIfSettled(List.of(row), window.get(0), Kind.DETERMINISTIC,
                    "the person's only trip whose date window holds the row", null);
        } else {
            severalTrips.computeIfAbsent(row.userId() + "|" + row.orgId(), na -> new ArrayList<>()).add(row);
        }
    }

    private static TripInfo nearest(final List<TripInfo> candidates, final LocalDate date) {
        return candidates.stream()
                .min(Comparator.comparingLong((TripInfo trip) -> daysApart(trip, date)).thenComparing(BY_START))
                .orElseThrow();
    }

    private static long daysApart(final TripInfo trip, final LocalDate date) {
        return trip.start() == null ? Long.MAX_VALUE : Math.abs(ChronoUnit.DAYS.between(trip.start(), date));
    }

    // ------------------------------------------------------------------ inference

    /**
     * One person's rows, of one organization, that several trips could hold. Walks every live row of that person
     * and organization in date order: a row already on a trip (bound, or planned above) counts there; a bill to
     * place goes to the earliest in-window trip not yet invoiced (else the earliest not fully paid); a payment to
     * the earliest in-window trip not fully paid. All or nothing: the placements stand only if the trips then
     * reconcile in cents.
     */
    private void infer(final List<Row> rows) {
        final List<Row> running = rows.stream()
                .filter(row -> inWindow(row).stream().anyMatch(trip -> !trip.ended(today)))
                .toList();
        if (!running.isEmpty()) {
            rows.forEach(row -> defer(row, Why.NOT_ENDED,
                    running.contains(row) ? "one of the trips that could hold it"
                            : "another row of this person could belong to a trip that has not ended",
                    null, null, null));
            return;
        }
        final Row any = rows.get(0);
        final List<Row> ledger = data.liveRowsOf(any.userId()).stream()
                .filter(row -> row.orgId().equals(any.orgId()))
                .toList();
        final Map<String, TripState> states = new LinkedHashMap<>();
        final Map<String, Placement> placements = new LinkedHashMap<>();
        for (final Row row : ledger) {
            final String tripId = rows.contains(row) ? place(row, states, placements) : tripOf(row);
            if (tripId != null) {
                states.computeIfAbsent(tripId, na -> new TripState()).add(row);
            }
        }
        final String verdict = reconcile(states, placements.size() == rows.size(), LedgerData.fold(ledger));
        for (final Row row : rows) {
            final Placement placement = placements.get(row.key());
            if (verdict.startsWith("OK")) {
                bind(row, placement.tripId(), Kind.INFERRED, placement.rule() + "; " + verdict.substring(3));
            } else {
                defer(row, Why.NOT_RECONCILED, verdict, placement == null ? null : placement.tripId(),
                        placement == null ? null : placement.rule(), null);
            }
        }
    }

    /** The one trip a row that is not being inferred already counts on, or null. */
    private String tripOf(final Row row) {
        final List<String> bound = data.tripsOf(row.key());
        return bound.size() == 1 ? bound.get(0) : planned.get(row.key());
    }

    private String place(final Row row, final Map<String, TripState> states,
            final Map<String, Placement> placements) {
        final List<TripInfo> window = inWindow(row);
        Placement placement = null;
        if (row.tx().isBill()) {
            placement = first(window, states, false, "a bill: the earliest in-window trip not yet invoiced");
        }
        if (placement == null) {
            placement = first(window, states, true, row.tx().isBill()
                    ? "a bill: every in-window trip is invoiced, so the earliest one not fully paid"
                    : "a payment: the earliest in-window trip not fully paid");
        }
        if (placement == null) {
            return null;
        }
        placements.put(row.key(), placement);
        return placement.tripId();
    }

    /** The earliest trip still waiting: for payment ({@code unpaid}), or for its first bill. */
    private static Placement first(final List<TripInfo> window, final Map<String, TripState> states,
            final boolean unpaid, final String rule) {
        for (final TripInfo trip : window) {
            final TripState state = states.getOrDefault(trip.id(), new TripState());
            if (unpaid ? !state.fullyPaid() : !state.billed) {
                return new Placement(trip.id(), rule);
            }
        }
        return null;
    }

    /** "OK ..." with the reason the placements stand, or why they do not. */
    private String reconcile(final Map<String, TripState> states, final boolean allPlaced, final double balance) {
        if (!allPlaced) {
            return "no in-window trip is waiting for one of this person's rows";
        }
        final List<String> owing = states.keySet().stream().filter(id -> states.get(id).cents() != 0).toList();
        if (owing.isEmpty()) {
            return "OK every trip of this person nets to zero";
        }
        final TripInfo last = data.trips().get(owing.get(0));
        if (owing.size() == 1 && last != null && last.ended(today)
                && states.get(last.id()).cents() == LedgerStatements.cents(balance)) {
            return "OK the remainder sits on one ended trip and equals the person's balance";
        }
        final List<String> nets = owing.stream()
                .map(id -> title(id) + " nets " + money(states.get(id).cents()))
                .toList();
        return "the placements leave " + String.join("; ", nets);
    }

    private String title(final String tripId) {
        final TripInfo trip = data.trips().get(tripId);
        return trip == null ? tripId : trip.title();
    }

    static String money(final long cents) {
        return String.format(Locale.ROOT, "%.2f", cents / 100d);
    }
}
