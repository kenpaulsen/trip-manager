package org.paulsens.trip.migrate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.paulsens.trip.migrate.LedgerBindingPlan.Bind;
import org.paulsens.trip.migrate.LedgerBindingPlan.Kind;
import org.paulsens.trip.migrate.LedgerBindingPlan.Uncertain;
import org.paulsens.trip.model.CompositeKey;
import org.paulsens.trip.model.Transaction;

/**
 * Writes a reviewed plan's bindings, and takes them back (docs/migrations/ledger-trip-binding.md). It writes
 * bindings ONLY: no transaction row is touched, which is what lets the verifier prove nobody's balance moved.
 *
 * <p>Every row is read again just before its write: one that was deleted, edited or bound since the plan is
 * skipped and listed, never forced. A row already on its trip in both directions is left alone, so a second run
 * writes nothing. Each binding is recorded BEFORE it is written (the recorder saves {@code rollback.json}), so a
 * run that dies between the two leaves a rollback that names at most one binding too many, never one too few,
 * and a rollback only removes a binding that is there.
 */
final class LedgerBindingApplier {
    private final LedgerStore store;
    private final Consumer<List<Added>> recorder;
    private final boolean write;

    /**
     * One binding to write.
     *
     * @param fingerprint the row's content when it was planned
     * @param kind        the rule, for the report
     */
    record Step(String userId, String txId, String tripId, String fingerprint, Kind kind) {
        String key() {
            return CompositeKey.join(userId, txId);
        }
    }

    /** The owner's answer for one row of the review file. */
    record Approval(String userId, String txId, String tripId) {
        String key() {
            return CompositeKey.join(userId, txId);
        }
    }

    /**
     * What an apply added for one row: exactly these directions are what a rollback removes.
     *
     * @param forward whether the TRANSACTION to TRIP binding was written (false: it was already there)
     * @param reverse whether the TRIP to TRANSACTION binding was written
     */
    public record Added(String userId, String txId, String tripId, boolean forward, boolean reverse) {
        String key() {
            return CompositeKey.join(userId, txId);
        }
    }

    /** One run: what it added, what it left alone and why, and what went wrong. */
    record Outcome(List<Added> added, List<String> skipped, List<String> failures) {
    }

    /**
     * @param recorder told the complete list of added bindings every time it changes, before each write
     */
    LedgerBindingApplier(final LedgerStore store, final Consumer<List<Added>> recorder) {
        this(store, recorder, true);
    }

    private LedgerBindingApplier(final LedgerStore store, final Consumer<List<Added>> recorder,
            final boolean write) {
        this.store = store;
        this.recorder = recorder;
        this.write = write;
    }

    /**
     * An applier that only READS: every check an apply or a rollback makes against the store, with "WOULD BIND"
     * and "WOULD REMOVE" where the real one writes. The pre-flight to run against the deployment before the
     * real thing.
     */
    static LedgerBindingApplier dryRun(final LedgerStore store) {
        return new LedgerBindingApplier(store, ignored -> { }, false);
    }

    /**
     * The plan's bindings plus the rows the owner approved. An approval is refused (and listed) when it names a
     * row the plan did not leave open, a trip other than the one the rest of its group is on, or only part of a
     * group or two trips for one: a group binds to one trip together or not at all.
     */
    static List<Step> steps(final LedgerBindingPlan plan, final List<Approval> approvals,
            final List<String> refusals) {
        final List<Step> steps = new ArrayList<>();
        plan.binds().forEach(bind -> steps.add(step(bind)));
        final Map<String, Uncertain> open = new LinkedHashMap<>();
        plan.uncertain().forEach(row -> open.put(row.key(), row));
        final Map<String, String> approved = new LinkedHashMap<>();
        for (final Approval approval : approvals) {
            final Uncertain row = open.get(approval.key());
            if (row == null) {
                refusals.add("REFUSED " + approval.key() + ": not a row this plan left for approval");
            } else if (row.requiredTripId() != null && !row.requiredTripId().equals(approval.tripId())) {
                refusals.add("REFUSED " + approval.key() + ": the rest of its group is on " + row.requiredTripId());
            } else if (approved.containsKey(approval.key())
                    && !approved.get(approval.key()).equals(approval.tripId())) {
                refusals.add("REFUSED " + approval.key() + ": approved twice, for two trips");
                approved.put(approval.key(), "");
            } else {
                approved.put(approval.key(), approval.tripId());
            }
        }
        final Set<String> split = splitGroups(plan, approved);
        for (final Uncertain row : plan.uncertain()) {
            final String tripId = approved.getOrDefault(row.key(), "");
            if (tripId.isEmpty()) {
                continue;
            }
            if (split.contains(row.groupId())) {
                refusals.add("REFUSED " + row.key() + ": group " + row.groupId()
                        + " must be approved as a whole, for one trip");
            } else {
                steps.add(new Step(row.userId(), row.txId(), tripId, row.fingerprint(), Kind.APPROVED));
            }
        }
        return steps;
    }

    private static Step step(final Bind bind) {
        return new Step(bind.userId(), bind.txId(), bind.tripId(), bind.fingerprint(), bind.kind());
    }

    /** The groups whose open rows were not all approved for the same trip. */
    private static Set<String> splitGroups(final LedgerBindingPlan plan, final Map<String, String> approved) {
        final Map<String, Set<String>> answers = new LinkedHashMap<>();
        for (final Uncertain row : plan.uncertain()) {
            if (row.groupId() != null) {
                answers.computeIfAbsent(row.groupId(), na -> new HashSet<>())
                        .add(approved.getOrDefault(row.key(), ""));
            }
        }
        final Set<String> split = new HashSet<>();
        answers.forEach((groupId, trips) -> addIfSeveral(split, groupId, trips));
        return split;
    }

    private static void addIfSeveral(final Set<String> split, final String groupId, final Set<String> trips) {
        if (trips.size() > 1) {
            split.add(groupId);
        }
    }

    /**
     * Binds each step's row to its trip, unless the row or the trip is no longer what the plan saw.
     *
     * @param already the bindings an earlier, interrupted run recorded: kept, so the rollback stays complete
     */
    Outcome apply(final List<Step> steps, final List<Added> already) {
        final List<Added> added = new ArrayList<>(already);
        final List<String> skipped = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        for (final Step step : steps) {
            final String problem = applyOne(step, added, skipped);
            if (problem != null) {
                failures.add("FAILED " + step.key() + " -> " + step.tripId() + ": " + problem);
            }
        }
        return new Outcome(added, skipped, failures);
    }

    /** One row; answers what went wrong, or null when it was written or deliberately left alone. */
    private String applyOne(final Step step, final List<Added> added, final List<String> skipped) {
        final String key = step.key();
        final Optional<Transaction> row = store.liveRow(step.userId(), step.txId());
        if (row.isEmpty()) {
            skipped.add("SKIPPED " + key + ": deleted since the plan");
            return null;
        }
        if (!LedgerData.fingerprint(row.get()).equals(step.fingerprint())) {
            skipped.add("SKIPPED " + key + ": edited since the plan (plan again)");
            return null;
        }
        final List<String> trips = store.tripsOf(key);
        final boolean forward = trips.contains(step.tripId());
        final boolean reverse = store.rowsOf(step.tripId()).contains(key);
        if (!trips.isEmpty() && !trips.equals(List.of(step.tripId()))) {
            skipped.add("SKIPPED " + key + ": bound to another trip since the plan");
            return null;
        }
        if (forward && reverse) {
            skipped.add("SKIPPED " + key + ": already on this trip");
            return null;
        }
        final Optional<String> org = store.tripOrg(step.tripId());
        if (org.isEmpty()) {
            return "no such trip";
        }
        if (org.get().isEmpty() || !org.get().equals(row.get().getOrgId())) {
            return "the trip and the row belong to different organizations";
        }
        if (!write) {
            skipped.add("WOULD BIND " + key + " -> " + step.tripId());
            return null;
        }
        final Added record = new Added(step.userId(), step.txId(), step.tripId(), !forward, !reverse);
        added.add(record);
        recorder.accept(List.copyOf(added));
        if (!store.bind(key, step.tripId())) {
            return "the store refused the binding (it stays in rollback.json; a rollback removes what exists)";
        }
        if (!store.tripsOf(key).contains(step.tripId()) || !store.rowsOf(step.tripId()).contains(key)) {
            return "written, but not read back in both directions";
        }
        return null;
    }

    /** Removes exactly the bindings an apply added; a line per binding, "FAILED" first when one would not go. */
    List<String> rollback(final List<Added> added) {
        final List<String> lines = new ArrayList<>();
        for (final Added record : added) {
            final String key = record.key();
            if (record.forward()) {
                lines.add(undo(store.tripsOf(key).contains(record.tripId()),
                        () -> store.unbindForward(key, record.tripId()),
                        () -> store.tripsOf(key).contains(record.tripId()), key + " -> " + record.tripId()));
            }
            if (record.reverse()) {
                lines.add(undo(store.rowsOf(record.tripId()).contains(key),
                        () -> store.unbindReverse(record.tripId(), key),
                        () -> store.rowsOf(record.tripId()).contains(key), record.tripId() + " -> " + key));
            }
        }
        return lines;
    }

    private String undo(final boolean present, final BooleanSupplier remove,
            final BooleanSupplier stillThere, final String what) {
        if (!present) {
            return "ABSENT " + what + " (nothing to remove)";
        }
        if (!write) {
            return "WOULD REMOVE " + what;
        }
        return remove.getAsBoolean() && !stillThere.getAsBoolean() ? "REMOVED " + what : "FAILED " + what;
    }
}
