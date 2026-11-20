package org.paulsens.trip.migrate;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.paulsens.trip.action.LedgerStatements;
import org.paulsens.trip.action.TripLedgerTotals;
import org.paulsens.trip.action.TripLedgerTotals.Bucket;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.migrate.LedgerBindingApplier.Added;
import org.paulsens.trip.migrate.LedgerBindingApplier.Approval;
import org.paulsens.trip.migrate.LedgerBindingApplier.Outcome;
import org.paulsens.trip.migrate.LedgerBindingApplier.Step;
import org.paulsens.trip.migrate.LedgerBindingPlan.Bind;
import org.paulsens.trip.migrate.LedgerBindingPlan.Kind;
import org.paulsens.trip.migrate.LedgerBindingPlan.Uncertain;
import org.paulsens.trip.migrate.LedgerBindingPlan.Why;
import org.paulsens.trip.migrate.LedgerBindingVerifier.OrgStatement;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.paulsens.trip.migrate.LedgerFixture.ORG;
import static org.paulsens.trip.migrate.LedgerFixture.OTHER_ORG;
import static org.paulsens.trip.migrate.LedgerFixture.TODAY;
import static org.paulsens.trip.migrate.LedgerFixture.uuid;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/**
 * The ledger trip-binding migration (docs/migrations/ledger-trip-binding.md) on synthetic ledgers: every planning
 * rule, the applier against rows that changed since the plan, the rollback, and each of the verifier's seven
 * checks failing on a ledger corrupted for it.
 */
public class LedgerBindingTest {
    private static final LocalDate EARLY_2024 = LocalDate.of(2024, 2, 1);

    private LedgerFixture ledger;

    @BeforeMethod
    public void newLedger() {
        ledger = new LedgerFixture();
    }

    private LedgerBindingPlan plan() {
        return new LedgerBindingPlanner(ledger.data(), TODAY).plan();
    }

    private static Bind bindOf(final LedgerBindingPlan plan, final String key) {
        return plan.binds().stream().filter(bind -> bind.key().equals(key)).findFirst().orElse(null);
    }

    private static Uncertain openOf(final LedgerBindingPlan plan, final String key) {
        return plan.uncertain().stream().filter(row -> row.key().equals(key)).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ the planner

    @Test
    public void aTripsOwnRowBindsToThatTripOnceItHasEnded() {
        final String ended = ledger.endedTrip("Ended");
        final String running = ledger.runningTrip("Running");
        final String foreign = ledger.trip(OTHER_ORG, "Other org", LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 9));
        final String own = ledger.row(ended, -500, LocalDate.of(2019, 1, 1));
        final String ownRunning = ledger.row(running, -500, EARLY_2024);
        final String ownForeign = ledger.row(foreign, ORG, -500, EARLY_2024);
        final LedgerBindingPlan plan = plan();
        assertEquals(bindOf(plan, own).kind(), Kind.TRIP_OWN, "no roster and no window are asked of a trip's row");
        assertEquals(bindOf(plan, own).tripId(), ended);
        assertEquals(openOf(plan, ownRunning).why(), Why.NOT_ENDED);
        assertEquals(openOf(plan, ownRunning).bestGuessTripId(), running);
        assertEquals(openOf(plan, ownForeign).why(), Why.ORG_MISMATCH);
        assertEquals(plan.count(Kind.TRIP_OWN), 1);
        assertEquals(plan.count(Why.NOT_ENDED), 1);
    }

    @Test
    public void aGroupBindsAsAUnitToTheOneTripItsMembersShare() {
        final String ann = ledger.person("Ann", "Alpha");
        final String bob = ledger.person("Bob", "Alpha");
        final String shared = ledger.endedTrip("Shared trip", ann, bob);
        ledger.laterTrip("Only Ann", ann);
        final String group = uuid();
        final String annRow = ledger.groupRow(ann, Transaction.Type.Shared, group, 100, EARLY_2024, List.of(ann, bob));
        final String bobRow = ledger.groupRow(bob, Transaction.Type.Shared, group, 100, EARLY_2024, List.of(ann, bob));
        final LedgerBindingPlan plan = plan();
        assertEquals(bindOf(plan, annRow).kind(), Kind.GROUP, "Ann alone has two trips; the group has one");
        assertEquals(bindOf(plan, annRow).tripId(), shared);
        assertEquals(bindOf(plan, bobRow).tripId(), shared);
        assertEquals(bindOf(plan, bobRow).groupId(), group);
        assertTrue(plan.uncertain().isEmpty());
    }

    @Test
    public void aGroupWithNoSingleCommonTripGoesToTheOwnerWhole() {
        final String ann = ledger.person("Ann", "Alpha");
        final String bob = ledger.person("Bob", "Alpha");
        final String cy = ledger.person("Cy", "Alpha");
        final String first = ledger.endedTrip("First", ann, cy);
        final String second = ledger.laterTrip("Second", bob, cy);
        final String apart = uuid();
        final String annRow = ledger.groupRow(ann, Transaction.Type.Batch, apart, -50, EARLY_2024, List.of(ann, bob));
        final String bobRow = ledger.groupRow(bob, Transaction.Type.Batch, apart, -50, EARLY_2024, List.of(ann, bob));
        final String both = uuid();
        final String cyRow = ledger.groupRow(cy, Transaction.Type.Batch, both, -50, EARLY_2024, List.of(cy));
        final LedgerBindingPlan plan = plan();
        assertTrue(plan.binds().isEmpty(), "never split a group, even where one member alone is certain");
        assertEquals(openOf(plan, annRow).why(), Why.GROUP_NO_COMMON_TRIP);
        assertEquals(openOf(plan, bobRow).why(), Why.GROUP_NO_COMMON_TRIP);
        assertNull(openOf(plan, annRow).bestGuessTripId(), "one vote each is a tie");
        assertEquals(openOf(plan, annRow).candidateTripIds(), List.of(first));
        assertEquals(openOf(plan, cyRow).why(), Why.GROUP_SEVERAL_TRIPS);
        assertEquals(openOf(plan, cyRow).bestGuessTripId(), first, "the earliest common trip is the guess");
        assertEquals(openOf(plan, cyRow).candidateTripIds(), List.of(first, second));
    }

    @Test
    public void aGroupMostlyOnOneTripGuessesIt() {
        final String ann = ledger.person("Ann", "Alpha");
        final String bob = ledger.person("Bob", "Alpha");
        final String cy = ledger.person("Cy", "Alpha");
        final String most = ledger.endedTrip("Most", ann, bob);
        final String group = uuid();
        final List<String> members = List.of(ann, bob, cy);
        final String annRow = ledger.groupRow(ann, Transaction.Type.Shared, group, 90, EARLY_2024, members);
        ledger.groupRow(bob, Transaction.Type.Shared, group, 90, EARLY_2024, members);
        final String cyRow = ledger.groupRow(cy, Transaction.Type.Shared, group, 90, EARLY_2024, members);
        final LedgerBindingPlan plan = plan();
        assertEquals(openOf(plan, annRow).bestGuessTripId(), most);
        assertEquals(openOf(plan, cyRow).why(), Why.GROUP_NO_COMMON_TRIP, "Cy is on no trip, so nothing is common");
        assertEquals(openOf(plan, cyRow).candidateTripIds(), List.of());
    }

    @Test
    public void thePendingRowsOfAPartlyBoundGroupJoinItsTrip() {
        final String ann = ledger.person("Ann", "Alpha");
        final String bob = ledger.person("Bob", "Alpha");
        final String ended = ledger.endedTrip("Ended");
        final String running = ledger.runningTrip("Running");
        final String gone = uuid();
        final String onEnded = group(ann, bob, ended);
        final String onRunning = group(ann, bob, running);
        final String onGone = group(ann, bob, gone);
        final String split = uuid();
        ledger.bind(ledger.groupRow(ann, Transaction.Type.Batch, split, -5, EARLY_2024, List.of(ann, bob)), ended);
        ledger.bind(ledger.groupRow(bob, Transaction.Type.Batch, split, -5, EARLY_2024, List.of(ann, bob)), running);
        final String third = ledger.groupRow(uuid(), Transaction.Type.Batch, split, -5, EARLY_2024, List.of(ann, bob));
        final LedgerBindingPlan plan = plan();
        assertEquals(bindOf(plan, onEnded).tripId(), ended, "no roster is asked: the group is on that trip already");
        assertEquals(openOf(plan, onRunning).why(), Why.NOT_ENDED);
        assertEquals(openOf(plan, onRunning).requiredTripId(), running, "an approval may name no other trip");
        assertEquals(openOf(plan, onGone).why(), Why.MISSING_TRIP);
        assertEquals(openOf(plan, third).why(), Why.GROUP_SPLIT);
    }

    /** A two-member group whose first row is bound to the trip; answers the key of the pending second row. */
    private String group(final String first, final String second, final String tripId) {
        final String group = uuid();
        ledger.bind(ledger.groupRow(first, Transaction.Type.Batch, group, -5, EARLY_2024, List.of(first, second)),
                tripId);
        return ledger.groupRow(second, Transaction.Type.Batch, group, -5, EARLY_2024, List.of(first, second));
    }

    @Test
    public void aSingleRowWithOneInWindowTripIsDeterministic() {
        final String ann = ledger.person("Ann", "Alpha");
        final String bob = ledger.person("Bob", "Beta");
        final String cy = ledger.person("Cy", "Gamma");
        final String trip = ledger.endedTrip("The trip", ann);
        ledger.register(trip, bob, Registration.Status.PENDING);
        ledger.register(trip, cy, Registration.Status.NOT_REGISTERED);
        ledger.trip(OTHER_ORG, "Another org's trip", LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 9), ann, cy);
        final String onRoster = ledger.row(ann, 100, EARLY_2024);
        final String registered = ledger.row(bob, 100, LocalDate.of(2022, 11, 1));
        final String edgeAfter = ledger.row(bob, 100, LocalDate.of(2024, 9, 10));
        final String tooLate = ledger.row(bob, 100, LocalDate.of(2024, 9, 11));
        final String tooEarly = ledger.row(bob, 100, LocalDate.of(2022, 10, 31));
        final String notRegistered = ledger.row(cy, 100, EARLY_2024);
        final Transaction undated = ledger.tx(ledger.row(ann, 100, null));
        final LedgerBindingPlan plan = plan();
        assertEquals(bindOf(plan, onRoster).kind(), Kind.DETERMINISTIC);
        assertEquals(bindOf(plan, onRoster).tripId(), trip, "another organization's trip is no candidate");
        assertEquals(bindOf(plan, registered).tripId(), trip, "18 months before the start is inside the window");
        assertEquals(bindOf(plan, edgeAfter).tripId(), trip, "4 months after the end is inside the window");
        assertEquals(openOf(plan, tooLate).why(), Why.NO_WINDOW);
        assertEquals(openOf(plan, tooEarly).why(), Why.NO_WINDOW);
        assertEquals(openOf(plan, tooEarly).bestGuessTripId(), trip, "the nearest trip is offered as a guess");
        assertEquals(openOf(plan, notRegistered).why(), Why.NO_ROSTER, "NOT_REGISTERED is not on the trip");
        assertEquals(openOf(plan, undated.getUserId().getValue() + "," + undated.getTxId()).why(), Why.NO_DATE);
        assertEquals(bindOf(plan, onRoster).fingerprint(), LedgerData.fingerprint(ledger.tx(onRoster)));
    }

    @Test
    public void aCandidateTripThatHasNotEndedIsNeverBound() {
        final String ann = ledger.person("Ann", "Alpha");
        final String bob = ledger.person("Bob", "Beta");
        final String running = ledger.runningTrip("Running", ann, bob);
        ledger.trip(ORG, "Ended lately", LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 9), bob);
        final String only = ledger.row(ann, 100, LocalDate.of(2025, 12, 1));
        final String either = ledger.row(bob, 100, LocalDate.of(2025, 7, 1));
        final LedgerBindingPlan plan = plan();
        assertTrue(plan.binds().isEmpty());
        assertEquals(openOf(plan, only).why(), Why.NOT_ENDED);
        assertEquals(openOf(plan, only).bestGuessTripId(), running);
        assertEquals(openOf(plan, either).why(), Why.NOT_ENDED, "one of its two trips is still running");
        assertNull(openOf(plan, either).bestGuessTripId());
    }

    @Test
    public void onlyOneRowWithARunningCandidateHoldsBackThePersonsInference() {
        final String ann = ledger.person("Ann", "Alpha");
        ledger.trip(ORG, "Mid 2025", LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 9), ann);
        ledger.trip(ORG, "Late 2025", LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 9), ann);
        ledger.runningTrip("Running", ann);
        final String settled = ledger.row(ann, -100, LocalDate.of(2024, 6, 1));
        final String reachesRunning = ledger.row(ann, 100, LocalDate.of(2025, 7, 1));
        final LedgerBindingPlan plan = plan();
        assertTrue(openOf(plan, settled).reason().contains("another row of this person"), "all or nothing");
        assertTrue(openOf(plan, reachesRunning).reason().contains("one of the trips that could hold it"));
    }

    @Test
    public void severalCandidatesAreInferredWhenPaymentsAndInvoicesReconcile() {
        final String ann = ledger.person("Ann", "Alpha");
        final String first = ledger.endedTrip("First", ann);
        final String second = ledger.laterTrip("Second", ann);
        final String bill1 = ledger.row(ann, -100, LocalDate.of(2024, 1, 1));
        final String pay1 = ledger.row(ann, 100, LocalDate.of(2024, 1, 5));
        final String bill2 = ledger.row(ann, -200, LocalDate.of(2024, 1, 10));
        final String pay2 = ledger.row(ann, 200, LocalDate.of(2024, 1, 15));
        final LedgerBindingPlan plan = plan();
        assertEquals(plan.count(Kind.INFERRED), 4, plan.uncertain().toString());
        assertEquals(bindOf(plan, bill1).tripId(), first, "a bill: the earliest trip not yet invoiced");
        assertEquals(bindOf(plan, pay1).tripId(), first, "a payment: the earliest trip not fully paid");
        assertEquals(bindOf(plan, bill2).tripId(), second);
        assertEquals(bindOf(plan, pay2).tripId(), second, "the first trip is paid up");
        assertTrue(bindOf(plan, pay2).reason().contains("nets to zero"), bindOf(plan, pay2).reason());
    }

    @Test
    public void aRemainderOnOneEndedTripEqualToTheBalanceIsAccepted() {
        final String ann = ledger.person("Ann", "Alpha");
        final String first = ledger.endedTrip("First", ann);
        ledger.laterTrip("Second", ann);
        final String bill = ledger.row(ann, -100, LocalDate.of(2024, 1, 1));
        final String part = ledger.row(ann, 60, LocalDate.of(2024, 1, 5));
        final String secondBill = ledger.row(ann, -30, LocalDate.of(2024, 1, 6));
        ledger.bind(secondBill, first);
        final LedgerBindingPlan plan = plan();
        assertEquals(bindOf(plan, bill).kind(), Kind.INFERRED);
        assertEquals(bindOf(plan, part).tripId(), first);
        assertTrue(bindOf(plan, part).reason().contains("equals the person's balance"), bindOf(plan, part).reason());
    }

    @Test
    public void inferenceThatDoesNotReconcileGoesToTheOwnerWithItsGuess() {
        final String ann = ledger.person("Ann", "Alpha");
        final String bob = ledger.person("Bob", "Beta");
        final String first = ledger.endedTrip("First", ann, bob);
        final String second = ledger.laterTrip("Second", ann, bob);
        final String bill1 = ledger.row(ann, -100, LocalDate.of(2024, 1, 1));
        final String bill2 = ledger.row(ann, -200, LocalDate.of(2024, 1, 2));
        final String bill3 = ledger.row(ann, -10, LocalDate.of(2024, 1, 3));
        final String pay = ledger.row(ann, 150, LocalDate.of(2024, 1, 4));
        ledger.bind(ledger.row(bob, -40, LocalDate.of(2024, 1, 1)), first);
        ledger.bind(ledger.row(bob, 40, LocalDate.of(2024, 1, 2)), first);
        ledger.bind(ledger.row(bob, -40, LocalDate.of(2024, 1, 1)), second);
        ledger.bind(ledger.row(bob, 40, LocalDate.of(2024, 1, 2)), second);
        final String extra = ledger.row(bob, 25, LocalDate.of(2024, 1, 3));
        final String lateBill = ledger.row(bob, -5, LocalDate.of(2024, 1, 4));
        final LedgerBindingPlan plan = plan();
        assertTrue(plan.binds().isEmpty());
        assertEquals(openOf(plan, bill1).why(), Why.NOT_RECONCILED);
        assertEquals(openOf(plan, bill1).bestGuessTripId(), first);
        assertEquals(openOf(plan, bill2).bestGuessTripId(), second);
        assertEquals(openOf(plan, bill3).bestGuessTripId(), first, "every trip invoiced: the first not fully paid");
        assertEquals(openOf(plan, pay).bestGuessTripId(), first);
        assertTrue(openOf(plan, pay).reason().contains("First nets") && openOf(plan, pay).reason().contains("Second"),
                openOf(plan, pay).reason());
        assertEquals(openOf(plan, extra).why(), Why.NOT_RECONCILED, "both trips are paid up: nowhere to put it");
        assertNull(openOf(plan, extra).bestGuessTripId());
        assertTrue(openOf(plan, extra).reason().contains("no in-window trip is waiting"));
        assertNull(openOf(plan, lateBill).bestGuessTripId(), "invoiced and paid up everywhere");
    }

    @Test
    public void aPlannedRowCountsOnItsTripDuringTheInference() {
        final String ann = ledger.person("Ann", "Alpha");
        final String first = ledger.endedTrip("First", ann);
        ledger.trip(ORG, "Much later", LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 9), ann);
        final String certain = ledger.row(ann, -100, LocalDate.of(2023, 1, 1));
        final String pay = ledger.row(ann, 100, LocalDate.of(2024, 3, 1));
        final LedgerBindingPlan plan = plan();
        assertEquals(bindOf(plan, certain).kind(), Kind.DETERMINISTIC, "only the first trip's window holds 2023");
        assertEquals(bindOf(plan, pay).kind(), Kind.INFERRED);
        assertEquals(bindOf(plan, pay).tripId(), first, "it pays the bill planned onto the first trip");
    }

    // ------------------------------------------------------------------ figures

    @Test
    public void aSharedRowIsNeverRoundedPerRow() {
        final String ann = ledger.person("Ann", "Alpha");
        final String bob = ledger.person("Bob", "Alpha");
        final String cy = ledger.person("Cy", "Alpha");
        final String trip = ledger.runningTrip("Running", ann, bob, cy);
        final String group = uuid();
        final List<String> members = List.of(ann, bob, cy);
        for (final String member : members) {
            ledger.bind(ledger.groupRow(member, Transaction.Type.Shared, group, 100, EARLY_2024, members), trip);
        }
        final LedgerData data = ledger.data();
        final LedgerSnapshot snapshot = LedgerSnapshot.of(data, TODAY);
        assertEquals(snapshot.balance().get(ann), (double) (100f / 3), "the Float share, exactly as the app folds it");
        assertEquals(LedgerStatements.cents(snapshot.balance().get(ann)), 3333);
        assertEquals(snapshot.tripTotals().get(trip).cents(Bucket.USER), 10000, "three shares, rounded once");
        assertEquals(snapshot.tripBound().get(trip).get(ann), new LedgerSnapshot.TripMoney(3333, 0),
                "check-in rounds each row, as CheckinCommands.balanceOf does");
        assertEquals(snapshot.balanceByOrg().get(ann).get(ORG), snapshot.balance().get(ann));
        assertEquals(snapshot.liveRows(), 3);
        final LedgerBindingVerifier verifier = verifier(snapshot, data).verify();
        assertEquals(verifier.failed(), Set.of(), String.join("\n", verifier.lines()));
    }

    @Test
    public void aSnapshotCountsDeletedBilledAndAmountlessRows() {
        final String ann = ledger.person("Ann", "Alpha");
        final String trip = ledger.runningTrip("Running", ann);
        final String bill = ledger.row(ann, -80, EARLY_2024);
        ledger.bind(bill, trip);
        final Transaction blank = ledger.tx(ledger.row(ann, 5, EARLY_2024));
        blank.setAmount(null);
        ledger.bind(ledger.put(blank), trip);
        final Transaction deleted = ledger.tx(ledger.row(ann, 999, EARLY_2024));
        deleted.delete();
        final String deletedKey = ledger.put(deleted);
        final String ownRow = ledger.row(trip, -300, EARLY_2024);
        ledger.bind(ownRow, trip);
        final LedgerSnapshot snapshot = LedgerSnapshot.of(ledger.data(), TODAY);
        assertEquals(snapshot.allRows(), 4);
        assertEquals(snapshot.deletedHashes().keySet(), Set.of(deletedKey));
        assertEquals(snapshot.balance().get(ann), -80d);
        assertEquals(snapshot.tripBound().get(trip).get(ann), new LedgerSnapshot.TripMoney(0, 8000));
        final TripLedgerTotals totals = snapshot.tripTotals().get(trip);
        assertEquals(totals.cents(Bucket.INVOICE), -8000);
        assertEquals(totals.cents(Bucket.TRIP), -30000, "a trip's own bill is a trip row, not an invoice");
        assertEquals(totals.cents(Bucket.USER), 0);
        assertEquals(totals.rows(), 3, "a row without an amount is listed all the same");
        assertEquals(snapshot.unboundLive(), List.of());
    }

    // ------------------------------------------------------------------ the applier

    /** Ann with a bound, paid-up trip and three rows to bind; Bob with one row that needs the owner. */
    private record Scene(String ann, String bob, String trip, String running, String bill, String pay,
            String ownRow, String open) {
    }

    private Scene scene() {
        final String ann = ledger.person("Ann", "Alpha");
        final String bob = ledger.person("Bob", "=Beta");
        final String trip = ledger.endedTrip("The trip", ann);
        final String running = ledger.runningTrip("Running", bob);
        ledger.bind(ledger.row(ann, 10, EARLY_2024), trip);
        return new Scene(ann, bob, trip, running, ledger.row(ann, -100, EARLY_2024),
                ledger.row(ann, 100, EARLY_2024.plusDays(1)), ledger.row(trip, -700, EARLY_2024),
                ledger.row(bob, 55, LocalDate.of(2025, 12, 1)));
    }

    private static LedgerBindingApplier applier(final LedgerStore store, final List<List<Added>> recorded) {
        return new LedgerBindingApplier(store, recorded::add);
    }

    @Test
    public void anApplyWritesBothDirectionsOnceAndARollbackRemovesExactlyThose() {
        final Scene scene = scene();
        final LedgerBindingPlan plan = plan();
        assertEquals(plan.binds().size(), 3);
        final LedgerData data = ledger.data();
        final LedgerData untouched = data.copy();
        final List<List<Added>> recorded = new ArrayList<>();
        final LedgerBindingApplier applier = applier(new LedgerStore.Memory(data), recorded);
        final List<Step> steps = LedgerBindingApplier.steps(plan, List.of(), new ArrayList<>());
        final Outcome first = applier.apply(steps, List.of());
        assertEquals(first.failures(), List.of());
        assertEquals(first.added().size(), 3);
        assertEquals(recorded.size(), 3, "the rollback record is saved before every write");
        assertEquals(data.tripsOf(scene.bill()), List.of(scene.trip()));
        assertTrue(data.rowsOf(scene.trip()).containsAll(List.of(scene.bill(), scene.pay(), scene.ownRow())));
        assertTrue(first.added().stream().allMatch(added -> added.forward() && added.reverse()));

        final Outcome second = applier.apply(steps, List.of());
        assertEquals(second.added(), List.of(), "a second apply writes nothing");
        assertEquals(second.skipped().size(), 3);
        assertTrue(second.skipped().get(0).contains("already on this trip"));

        final List<String> undone = applier.rollback(first.added());
        assertEquals(undone.size(), 6);
        assertTrue(undone.stream().allMatch(line -> line.startsWith("REMOVED")), undone.toString());
        assertEquals(data.forward(), untouched.forward(), "the binding that was there before stays");
        assertEquals(data.reverse(), untouched.reverse());
        assertTrue(applier.rollback(first.added()).stream().allMatch(line -> line.startsWith("ABSENT")));
    }

    @Test
    public void rowsThatChangedSinceThePlanAreSkippedNotForced() {
        final Scene scene = scene();
        final String edited = ledger.row(scene.ann(), 20, EARLY_2024);
        final String half = ledger.row(scene.ann(), 30, EARLY_2024);
        final LedgerBindingPlan plan = plan();
        final Transaction deleted = ledger.tx(scene.bill());
        deleted.delete();
        ledger.put(deleted);
        ledger.bind(scene.pay(), scene.running());
        final Transaction changed = ledger.tx(edited);
        changed.setAmount(21f);
        ledger.put(changed);
        ledger.bindForward(half, scene.trip());
        final LedgerData data = ledger.data();
        final Outcome outcome = applier(new LedgerStore.Memory(data), new ArrayList<>())
                .apply(LedgerBindingApplier.steps(plan, List.of(), new ArrayList<>()), List.of());
        assertEquals(outcome.failures(), List.of());
        assertTrue(outcome.skipped().contains("SKIPPED " + scene.bill() + ": deleted since the plan"));
        assertTrue(outcome.skipped().contains("SKIPPED " + scene.pay() + ": bound to another trip since the plan"));
        assertTrue(outcome.skipped().contains("SKIPPED " + edited + ": edited since the plan (plan again)"));
        assertEquals(Set.copyOf(outcome.added()),
                Set.of(new Added(scene.trip(), key(scene.ownRow()), scene.trip(), true, true),
                        new Added(scene.ann(), key(half), scene.trip(), false, true)),
                "a half-bound row gets only its missing direction, and only that is recorded");
        assertEquals(data.tripsOf(scene.pay()), List.of(scene.running()), "the newer binding is left alone");
        final LedgerBindingApplier applier = applier(new LedgerStore.Memory(data), new ArrayList<>());
        assertEquals(applier.rollback(outcome.added()).size(), 3);
        assertEquals(data.tripsOf(half), List.of(scene.trip()), "the half that was there before the apply stays");
    }

    private static String key(final String rowKey) {
        return rowKey.substring(rowKey.indexOf(',') + 1);
    }

    @Test
    public void anApplyRefusesATripThatIsGoneOrOfAnotherOrganization() {
        final Scene scene = scene();
        final Transaction bill = ledger.tx(scene.bill());
        final String print = LedgerData.fingerprint(bill);
        final String foreign = ledger.trip(OTHER_ORG, "Foreign", LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 9));
        final String orgless = ledger.trip(null, "No org", LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 9));
        final List<Step> steps = List.of(step(scene.bill(), uuid(), print), step(scene.bill(), foreign, print),
                step(scene.bill(), orgless, print));
        final Outcome outcome = applier(new LedgerStore.Memory(ledger.data()), new ArrayList<>())
                .apply(steps, List.of());
        assertEquals(outcome.added(), List.of());
        assertEquals(outcome.failures().size(), 3);
        assertTrue(outcome.failures().get(0).endsWith("no such trip"));
        assertTrue(outcome.failures().get(1).endsWith("belong to different organizations"));
        assertTrue(outcome.failures().get(2).endsWith("belong to different organizations"));
    }

    private static Step step(final String rowKey, final String tripId, final String fingerprint) {
        return new Step(rowKey.substring(0, rowKey.indexOf(',')), key(rowKey), tripId, fingerprint,
                Kind.DETERMINISTIC);
    }

    /** A store that takes a write and then loses it, or refuses it outright. */
    private static final class FaultyStore implements LedgerStore {
        private final LedgerStore real;
        private final boolean answer;

        FaultyStore(final LedgerData data, final boolean answer) {
            this.real = new LedgerStore.Memory(data);
            this.answer = answer;
        }

        @Override
        public Optional<Transaction> liveRow(final String userId, final String txId) {
            return real.liveRow(userId, txId);
        }

        @Override
        public List<String> tripsOf(final String rowKey) {
            return real.tripsOf(rowKey);
        }

        @Override
        public List<String> rowsOf(final String tripId) {
            return real.rowsOf(tripId);
        }

        @Override
        public Optional<String> tripOrg(final String tripId) {
            return real.tripOrg(tripId);
        }

        @Override
        public boolean bind(final String rowKey, final String tripId) {
            return answer;
        }

        @Override
        public boolean unbindForward(final String rowKey, final String tripId) {
            return answer;
        }

        @Override
        public boolean unbindReverse(final String tripId, final String rowKey) {
            return false;
        }
    }

    @Test
    public void aRefusedOrUnreadableWriteIsAFailureThatStaysOnTheRollbackRecord() {
        final Scene scene = scene();
        final List<Step> steps = LedgerBindingApplier.steps(plan(), List.of(), new ArrayList<>());
        final Outcome refused = applier(new FaultyStore(ledger.data(), false), new ArrayList<>())
                .apply(steps, List.of());
        assertEquals(refused.failures().size(), 3);
        assertTrue(refused.failures().get(0).contains("the store refused the binding"), refused.failures().get(0));
        assertEquals(refused.added().size(), 3, "recorded first, so a rollback knows of a write that half landed");
        final List<Added> earlier = List.of(new Added(scene.ann(), "earlier", scene.trip(), true, true));
        final Outcome lost = applier(new FaultyStore(ledger.data(), true), new ArrayList<>()).apply(steps, earlier);
        assertTrue(lost.failures().get(0).contains("not read back in both directions"), lost.failures().get(0));
        assertEquals(lost.added().get(0), earlier.get(0), "an interrupted run's record is kept");

        final LedgerData bound = ledger.data();
        new LedgerStore.Memory(bound).bind(scene.bill(), scene.trip());
        final List<Added> added = List.of(new Added(scene.ann(), key(scene.bill()), scene.trip(), true, true));
        final List<String> stuck = applier(new FaultyStore(bound, true), new ArrayList<>()).rollback(added);
        assertEquals(stuck.size(), 2);
        assertTrue(stuck.stream().allMatch(line -> line.startsWith("FAILED")), "a removal is believed only once "
                + "the binding reads as gone: " + stuck);
    }

    @Test
    public void approvalsBindOpenRowsAndGroupsOnlyAsAWhole() {
        final Scene scene = scene();
        final String cy = ledger.person("Cy", "Gamma");
        final String dee = ledger.person("Dee", "Gamma");
        final String later = ledger.laterTrip("Later", cy, dee);
        final String early = ledger.endedTrip("Early", cy, dee);
        final String whole = uuid();
        final String cyRow = ledger.groupRow(cy, Transaction.Type.Shared, whole, 80, EARLY_2024, List.of(cy, dee));
        final String deeRow = ledger.groupRow(dee, Transaction.Type.Shared, whole, 80, EARLY_2024, List.of(cy, dee));
        final String torn = uuid();
        final String tornCy = ledger.groupRow(cy, Transaction.Type.Batch, torn, -8, EARLY_2024, List.of(cy, dee));
        final String tornDee = ledger.groupRow(dee, Transaction.Type.Batch, torn, -8, EARLY_2024, List.of(cy, dee));
        final String pinned = group(cy, dee, scene.running());
        final LedgerBindingPlan plan = plan();
        final List<String> refusals = new ArrayList<>();
        final List<Step> steps = LedgerBindingApplier.steps(plan, List.of(
                approval(scene.open(), scene.running()), approval(cyRow, later), approval(deeRow, later),
                approval(tornCy, later), approval(pinned, later), approval(scene.bill(), later),
                approval(tornDee, early), approval(tornDee, later), approval(tornDee, early)), refusals);
        final List<Step> approved = steps.stream().filter(step -> step.kind() == Kind.APPROVED).toList();
        assertEquals(Set.copyOf(approved.stream().map(Step::key).toList()), Set.of(scene.open(), cyRow, deeRow),
                String.join("\n", refusals));
        assertEquals(approved.size(), 3);
        assertEquals(steps.size(), plan.binds().size() + 3);
        assertTrue(refusals.contains("REFUSED " + scene.bill() + ": not a row this plan left for approval"));
        assertTrue(refusals.contains("REFUSED " + pinned + ": the rest of its group is on " + scene.running()));
        assertTrue(refusals.contains("REFUSED " + tornDee + ": approved twice, for two trips"));
        assertTrue(refusals.contains("REFUSED " + tornCy + ": group " + torn
                + " must be approved as a whole, for one trip"), String.join("\n", refusals));
        final LedgerData data = ledger.data();
        final Outcome outcome = applier(new LedgerStore.Memory(data), new ArrayList<>()).apply(steps, List.of());
        assertEquals(outcome.failures(), List.of());
        assertEquals(data.tripsOf(cyRow), List.of(later));
        assertEquals(data.tripsOf(scene.open()), List.of(scene.running()),
                "the owner may approve a trip that has not ended");
    }

    private static Approval approval(final String rowKey, final String tripId) {
        return new Approval(rowKey.substring(0, rowKey.indexOf(',')), key(rowKey), tripId);
    }

    @Test
    public void theLiveStoreBindsThroughTheDaoAndSkipsADeletedRow() throws IOException {
        final DAO dao = DAO.getInstance();
        final Trip trip = Trip.builder().id(uuid()).title("Live store trip").people(List.of()).build();
        trip.setOrgId(ORG);
        assertTrue(dao.saveTrip(trip));
        final Person.Id owner = Person.Id.from(uuid());
        final Transaction kept = new Transaction(owner, null, null);
        kept.setAmount(12.5f);
        kept.setOrgId(ORG);
        final Transaction doomed = new Transaction(owner, null, null);
        doomed.setOrgId(ORG);
        assertTrue(dao.saveTransaction(kept));
        assertTrue(dao.saveTransaction(doomed));
        final String keptKey = owner.getValue() + "," + kept.getTxId();
        final List<Step> steps = List.of(step(keptKey, trip.getId(), LedgerData.fingerprint(kept)),
                step(owner.getValue() + "," + doomed.getTxId(), trip.getId(), LedgerData.fingerprint(doomed)));
        doomed.delete();
        assertTrue(dao.saveTransaction(doomed));

        final LedgerStore.Live store = new LedgerStore.Live(dao);
        assertEquals(store.tripOrg(trip.getId()), Optional.of(ORG));
        assertEquals(store.tripOrg(uuid()), Optional.empty());
        final LedgerBindingApplier applier = applier(store, new ArrayList<>());
        final Outcome outcome = applier.apply(steps, List.of());
        assertEquals(outcome.failures(), List.of());
        assertEquals(outcome.added(), List.of(new Added(owner.getValue(), kept.getTxId(), trip.getId(), true, true)));
        assertEquals(outcome.skipped().size(), 1, "the row deleted after the plan");
        assertEquals(dao.getBindings(keptKey, BindingType.TRANSACTION, BindingType.TRIP, Cached.NO),
                List.of(trip.getId()));
        assertEquals(dao.getBindings(trip.getId(), BindingType.TRIP, BindingType.TRANSACTION, Cached.NO),
                List.of(keptKey));
        assertEquals(applier.apply(steps, List.of()).added(), List.of(), "idempotent against the store too");

        assertEquals(applier.rollback(outcome.added()).size(), 2);
        assertEquals(store.tripsOf(keptKey), List.of());
        assertEquals(store.rowsOf(trip.getId()), List.of());
        assertEquals(dao.getTransaction(owner, kept.getTxId(), Cached.NO).orElseThrow().getAmount(), 12.5f,
                "no transaction row is written by an apply or a rollback");
    }

    // ------------------------------------------------------------------ the verifier

    private static final OrgStatement NO_STATEMENT = new OrgStatement(0d, List.of());

    private static LedgerBindingVerifier verifier(final LedgerSnapshot before, final LedgerData after) {
        return new LedgerBindingVerifier(before, after, TODAY, LedgerStatementSource.statements(after), Set.of());
    }

    /** The scene, planned and applied in memory; the verifier must pass every check but the open row's. */
    private LedgerData applied(final Scene scene) {
        final LedgerData data = ledger.data();
        final Outcome outcome = applier(new LedgerStore.Memory(data), new ArrayList<>())
                .apply(LedgerBindingApplier.steps(plan(), List.of(approval(scene.open(), scene.running())),
                        new ArrayList<>()), List.of());
        assertEquals(outcome.failures(), List.of());
        return data;
    }

    @Test
    public void aCleanApplyPassesEveryCheckButTheApprovedRunningTrip() {
        final Scene scene = scene();
        final LedgerSnapshot before = LedgerSnapshot.of(ledger.data(), TODAY);
        final LedgerData after = applied(scene);
        final LedgerBindingVerifier verifier = verifier(before, after).verify();
        assertEquals(verifier.failed(), Set.of(6), "a row approved onto a running trip moves its bound balance, "
                + "and check 6 says so: " + String.join("\n", verifier.lines()));
        assertEquals(verifier.lines().stream().filter(line -> line.startsWith("PASS")).count(), 6);
        assertTrue(verifier.details().get(0).contains("newly bound row(s)"), verifier.details().toString());
        assertTrue(verifier.details().stream().anyMatch(line -> line.contains("+ " + scene.bill() + " INVOICE")));
        assertTrue(verifier.details().stream().anyMatch(line -> line.contains("TRIP total 0.00 -> -700.00")));
        assertFalse(verifier.onlyAwaitingApproval());
    }

    @Test
    public void rowsStillWaitingForApprovalAreTheOnlyToleratedFailure() {
        final Scene scene = scene();
        final LedgerSnapshot before = LedgerSnapshot.of(ledger.data(), TODAY);
        final LedgerData after = ledger.data();
        applier(new LedgerStore.Memory(after), new ArrayList<>())
                .apply(LedgerBindingApplier.steps(plan(), List.of(), new ArrayList<>()), List.of());
        final LedgerBindingVerifier waiting = new LedgerBindingVerifier(before, after, TODAY,
                LedgerStatementSource.statements(after), Set.of(scene.open())).verify();
        assertEquals(waiting.failed(), Set.of(7));
        assertTrue(waiting.onlyAwaitingApproval(), String.join("\n", waiting.lines()));
        final LedgerBindingVerifier forgotten = verifier(before, after).verify();
        assertEquals(forgotten.failed(), Set.of(7));
        assertFalse(forgotten.onlyAwaitingApproval(), "a trip-less row nobody is asked about is a plain failure");
    }

    @Test
    public void check1FailsWhenTheNewCodeLosesARow() {
        final Scene scene = scene();
        final LedgerData data = ledger.data();
        final LedgerSnapshot before = LedgerSnapshot.of(data, TODAY);
        final LedgerBindingVerifier.Statements honest = LedgerStatementSource.statements(data);
        final LedgerBindingVerifier verifier = new LedgerBindingVerifier(before, data, TODAY,
                person -> withoutRow(data, person, honest.of(person), key(scene.bill())), Set.of()).verify();
        assertTrue(verifier.failed().containsAll(Set.of(1, 2, 4)), String.join("\n", verifier.lines()));
        assertTrue(verifier.lines().stream().anyMatch(line -> line.contains(scene.bill() + ": on no statement")));
    }

    /** The statements with one row dropped from the list AND from the figure, as code that lost it would. */
    private static Map<String, OrgStatement> withoutRow(final LedgerData data, final String person,
            final Map<String, OrgStatement> statements, final String txId) {
        final Map<String, OrgStatement> fewer = new TreeMap<>();
        statements.forEach((org, stated) -> fewer.put(org, restated(data, person,
                stated.txIds().stream().filter(id -> !id.equals(txId)).toList())));
        return fewer;
    }

    private static OrgStatement restated(final LedgerData data, final String person, final List<String> txIds) {
        return new OrgStatement(LedgerData.fold(txIds.stream().map(id -> data.liveRow(person + "," + id)).toList()),
                txIds);
    }

    @Test
    public void check2FailsWhenARowIsOnAnotherOrganizationsStatement() {
        final Scene scene = scene();
        final LedgerData data = ledger.data();
        final LedgerSnapshot before = LedgerSnapshot.of(data, TODAY);
        final LedgerBindingVerifier.Statements honest = LedgerStatementSource.statements(data);
        final LedgerBindingVerifier verifier = new LedgerBindingVerifier(before, data, TODAY,
                person -> Map.of(OTHER_ORG, honest.of(person).getOrDefault(ORG, NO_STATEMENT)), Set.of()).verify();
        assertTrue(verifier.failed().contains(2), String.join("\n", verifier.lines()));
        assertFalse(verifier.failed().contains(1), "the org-blind balance is still right");
        assertTrue(verifier.failed().contains(4));
        assertTrue(verifier.lines().stream().anyMatch(line -> line.contains("not its own")));
        assertNotNull(scene);
    }

    @Test
    public void check2FailsWhenTheOrganizationsDoNotSumToTheTotal() {
        final Scene scene = scene();
        final LedgerSnapshot before = LedgerSnapshot.of(ledger.data(), TODAY);
        final Transaction moved = ledger.tx(scene.pay());
        moved.setAmount(140f);
        ledger.put(moved);
        final LedgerData after = ledger.data();
        final LedgerBindingVerifier verifier = new LedgerBindingVerifier(before, after, TODAY,
                person -> Map.of(), Set.of()).verify();
        assertTrue(verifier.lines().stream().anyMatch(line -> line.contains("the organizations sum to 0.00")),
                String.join("\n", verifier.lines()));
    }

    @Test
    public void check3FailsOnAnyRowThatChangedVanishedOrAppeared() {
        final Scene scene = scene();
        final String vanishing = ledger.row(scene.ann(), 0, EARLY_2024);
        final String doomed = ledger.row(scene.ann(), 0, EARLY_2024);
        final LedgerSnapshot before = LedgerSnapshot.of(ledger.data(), TODAY);
        final Transaction renoted = ledger.tx(scene.bill());
        renoted.setNote("a different invented note");
        ledger.put(renoted);
        final Transaction deleted = ledger.tx(doomed);
        deleted.delete();
        ledger.put(deleted);
        ledger.remove(vanishing);
        final String added = ledger.row(scene.ann(), 0, EARLY_2024);
        final LedgerBindingVerifier verifier = verifier(before, ledger.data()).verify();
        assertTrue(verifier.failed().contains(3));
        assertFalse(verifier.failed().contains(1), "a note is not money: only the byte-for-byte check sees it");
        final String report = String.join("\n", verifier.lines());
        assertTrue(report.contains("CHANGED " + scene.bill()), report);
        assertTrue(report.contains("DELETED SINCE " + doomed), report);
        assertTrue(report.contains("GONE " + vanishing), report);
        assertTrue(report.contains("ADDED SINCE " + added), report);
    }

    @Test
    public void check4FailsWhenARowIsListedTwiceOrIsNotALiveRow() {
        final Scene scene = scene();
        final LedgerData data = ledger.data();
        final LedgerSnapshot before = LedgerSnapshot.of(data, TODAY);
        final LedgerBindingVerifier.Statements honest = LedgerStatementSource.statements(data);
        final LedgerBindingVerifier verifier = new LedgerBindingVerifier(before, data, TODAY,
                person -> twice(honest.of(person)), Set.of()).verify();
        assertTrue(verifier.failed().containsAll(Set.of(1, 4)), String.join("\n", verifier.lines()));
        final String report = String.join("\n", verifier.lines());
        assertTrue(report.contains(scene.bill() + ": on more than one statement"), report);
        assertTrue(report.contains(",phantom: on a statement, but not a live row"), report);
    }

    private static Map<String, OrgStatement> twice(final Map<String, OrgStatement> statements) {
        final Map<String, OrgStatement> doubled = new TreeMap<>(statements);
        final OrgStatement real = statements.getOrDefault(ORG, NO_STATEMENT);
        final List<String> rows = new ArrayList<>(real.txIds());
        rows.add("phantom");
        doubled.put(ORG, new OrgStatement(real.balance(), rows));
        doubled.put("", real);
        return doubled;
    }

    @Test
    public void check4FailsWhenAStatementsBalanceIsNotTheSumOfItsRows() {
        final Scene scene = scene();
        final LedgerData data = ledger.data();
        final LedgerSnapshot before = LedgerSnapshot.of(data, TODAY);
        final LedgerBindingVerifier.Statements honest = LedgerStatementSource.statements(data);
        final LedgerBindingVerifier verifier = new LedgerBindingVerifier(before, data, TODAY,
                person -> offByADollar(honest.of(person)), Set.of()).verify();
        assertTrue(verifier.failed().containsAll(Set.of(1, 2, 4)), String.join("\n", verifier.lines()));
        assertTrue(verifier.lines().stream().anyMatch(line -> line.contains("its rows add up to")));
        assertNotNull(scene);
    }

    private static Map<String, OrgStatement> offByADollar(final Map<String, OrgStatement> statements) {
        final Map<String, OrgStatement> wrong = new TreeMap<>();
        statements.forEach((org, stated) -> wrong.put(org, new OrgStatement(stated.balance() + 1d, stated.txIds())));
        return wrong;
    }

    @Test
    public void check5FailsWhenATripsOldRowsNoLongerTotalWhatTheyDid() {
        final Scene scene = scene();
        final String other = ledger.laterTrip("Other", scene.ann());
        final String moving = ledger.row(scene.ann(), 45, EARLY_2024);
        ledger.bind(moving, scene.trip());
        final LedgerSnapshot before = LedgerSnapshot.of(ledger.data(), TODAY);
        ledger.unbind(moving);
        ledger.bind(moving, other);
        final String newTrip = ledger.endedTrip("Made since the snapshot");
        final LedgerBindingVerifier verifier = verifier(before, ledger.data()).verify();
        assertTrue(verifier.failed().contains(5));
        assertFalse(verifier.failed().contains(1), "nobody's balance moved: only the trips' books did");
        final String report = String.join("\n", verifier.lines());
        assertTrue(report.contains("(The trip) USER: 55.00 before, 10.00 now without its newly bound rows"), report);
        assertTrue(report.contains("(Other) gained " + moving + ", which was not a trip-less row before"), report);
        assertFalse(report.contains(newTrip), "a new, empty trip totals nothing, as before");
    }

    @Test
    public void check5NamesATripThatNoLongerExists() {
        final Scene scene = scene();
        final LedgerData data = ledger.data();
        final LedgerSnapshot real = LedgerSnapshot.of(data, TODAY);
        final Map<String, TripLedgerTotals> totals = new TreeMap<>(real.tripTotals());
        final String gone = uuid();
        totals.put(gone, new TripLedgerTotals(0d, 0d, 12d, 1));
        final LedgerSnapshot before = new LedgerSnapshot(real.asOf(), real.allRows(), real.liveHashes(),
                real.deletedHashes(), real.balance(), real.balanceByOrg(), totals, real.tripRows(), real.tripBound(),
                real.unboundLive());
        final LedgerBindingVerifier verifier = verifier(before, data).verify();
        assertTrue(String.join("\n", verifier.lines()).contains("trip " + gone + " (no longer exists) USER: 12.00"));
        assertNotNull(scene);
    }

    @Test
    public void check6FailsWhenARunningTripsBoundBalanceMoves() {
        final Scene scene = scene();
        final String paid = ledger.row(scene.bob(), 70, LocalDate.of(2025, 12, 2));
        ledger.bind(paid, scene.running());
        final LedgerSnapshot before = LedgerSnapshot.of(ledger.data(), TODAY);
        ledger.bind(scene.open(), scene.running());
        final LedgerBindingVerifier verifier = verifier(before, ledger.data()).verify();
        assertTrue(verifier.failed().contains(6));
        assertTrue(String.join("\n", verifier.lines()).contains("paid 70.00 billed 0.00 before, paid 125.00 billed "
                + "0.00 now"), String.join("\n", verifier.lines()));
    }

    @Test
    public void check7FailsOnEveryBrokenInvariant() {
        final Scene scene = scene();
        final String cy = ledger.person("Cy", "Gamma");
        final String orgless = ledger.trip(null, "No org", LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 9));
        final String foreign = ledger.trip(OTHER_ORG, "Foreign", LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 9));
        final String missing = uuid();
        final Transaction stray = ledger.tx(ledger.row(cy, 1, EARLY_2024));
        stray.setOrgId(null);
        final String noOrg = ledger.put(stray);
        ledger.bind(noOrg, orgless);
        final String twoTrips = ledger.row(cy, 2, EARLY_2024);
        ledger.bind(twoTrips, scene.trip());
        ledger.bind(twoTrips, foreign);
        final String toMissing = ledger.row(cy, 3, EARLY_2024);
        ledger.bind(toMissing, missing);
        final String forwardOnly = ledger.row(cy, 4, EARLY_2024);
        ledger.bindForward(forwardOnly, scene.trip());
        final String reverseOnly = ledger.row(cy, 5, EARLY_2024);
        ledger.bindReverse(scene.trip(), reverseOnly);
        final String torn = uuid();
        ledger.bind(ledger.groupRow(cy, Transaction.Type.Batch, torn, -1, EARLY_2024, List.of(cy, scene.ann())),
                scene.trip());
        ledger.groupRow(scene.ann(), Transaction.Type.Batch, torn, -1, EARLY_2024, List.of(cy, scene.ann()));
        final String stale = uuid();
        ledger.bind(ledger.groupRow(cy, Transaction.Type.Batch, stale, -1, EARLY_2024, List.of(cy, scene.bob())),
                scene.trip());
        final String legacy = uuid();
        ledger.bind(ledger.groupRow(cy, Transaction.Type.Batch, legacy, -1, EARLY_2024, null), scene.trip());
        final LedgerData data = ledger.data();
        final LedgerBindingVerifier verifier = verifier(LedgerSnapshot.of(data, TODAY), data).verify();
        assertEquals(verifier.failed(), Set.of(7), String.join("\n", verifier.lines()));
        assertFalse(verifier.onlyAwaitingApproval());
        final String report = String.join("\n", verifier.lines());
        for (final String kind : List.of("trip-less row: 6", "row with no organization: 1 (" + noOrg,
                "row bound to several trips: 1 (" + twoTrips, "row bound to a missing trip: 1 (" + toMissing,
                "row whose organization is not its trip's: 2", "trip with no organization: 1 (" + orgless,
                "binding without its TRIP to TRANSACTION half: 1 (" + forwardOnly,
                "binding without its TRANSACTION to TRIP half: 1 (" + scene.trip() + " -> " + reverseOnly,
                "group not on one trip: 1 (" + torn,
                "group whose stamped membership is not its live rows: 1 (" + stale)) {
            assertTrue(report.contains(kind), kind + "\n" + report);
        }
        assertFalse(report.contains(legacy), "a row with no stamp counts itself, as the share rule reads it");
    }

    // ------------------------------------------------------------------ reading a ledger

    @Test
    public void aLedgerRefusesWhatItCannotRead() {
        final Map<String, List<Map<String, String>>> unreadable = Map.of(LedgerData.TRANSACTIONS,
                List.of(Map.of("userId", uuid(), "txId", uuid(), "content", "{not json")));
        assertThrows(IllegalStateException.class, () -> LedgerData.fromRaw(unreadable));
        final Map<String, List<Map<String, String>>> noContent = Map.of(LedgerData.TRANSACTIONS,
                List.of(Map.of("userId", uuid(), "txId", uuid())));
        assertThrows(IllegalStateException.class, () -> LedgerData.fromRaw(noContent));
        final Map<String, List<Map<String, String>>> badTrip = Map.of(LedgerData.TRIPS,
                List.of(Map.of("id", uuid(), "content", "{not json")));
        assertThrows(IllegalStateException.class, () -> LedgerData.fromRaw(badTrip));
    }

    @Test
    public void thePlanFilesListATripWithNoDatesLastInsteadOfThrowing() {
        final String ann = ledger.person("Ann", "Alpha");
        final String bob = ledger.person("Bob", "Beta");
        final String dateless = ledger.trip(ORG, "No dates", null, null, ann);
        final String ended = ledger.endedTrip("Ended", bob);
        final String guessed = ledger.row(ann, 10, EARLY_2024);
        final String own = ledger.row(ended, -40, EARLY_2024);
        final LedgerData data = ledger.data();
        final LedgerBindingPlan plan = new LedgerBindingPlanner(data, TODAY).plan();
        assertEquals(openOf(plan, guessed).bestGuessTripId(), dateless, "an undated trip is a best guess");
        assertEquals(bindOf(plan, own).tripId(), ended, "and another trip gains a bind");

        // A bare comparing(start) threw NullPointerException here, after plan.json was already written.
        final List<String> lines = new LedgerPlanFiles(data, plan).perTripCsv();
        assertEquals(lines.size(), 3, lines.toString());
        assertEquals(lines.get(0), LedgerCsv.line(LedgerPlanFiles.TRIP_HEADER));
        assertTrue(lines.get(1).startsWith(ended + ","), "dated trips first: " + lines);
        assertTrue(lines.get(2).startsWith(dateless + ","), "the trip with no dates last: " + lines);
    }

    @Test
    public void aLedgerReadsOtherBindingsDatelessTripsAndTheLiveTables() throws IOException {
        final String ann = ledger.person("Ann", "Alpha");
        final String dateless = ledger.trip(ORG, "No dates", null, null, ann);
        final String row = ledger.row(ann, 10, EARLY_2024);
        final LedgerData data = ledger.data();
        assertFalse(data.trips().get(dateless).ended(TODAY), "a trip with no dates never counts as ended");
        assertFalse(data.trips().get(dateless).windowHolds(EARLY_2024));
        assertEquals(openOf(plan(), row).why(), Why.NO_WINDOW);
        assertEquals(openOf(plan(), row).bestGuessTripId(), dateless, "the person's only trip is still the guess");
        assertEquals(data.orgName(ORG), "First Org");
        assertEquals(data.orgIds(), Set.of(ORG, OTHER_ORG));
        assertEquals(data.person(ann).first(), "Ann");
        assertFalse(data.unbindForward(row, dateless), "nothing to remove");
        assertEquals(LedgerData.fingerprint(ledger.tx(row)),
                LedgerData.sha256(DAO.getInstance().getMapper().writeValueAsString(ledger.tx(row))),
                "this tool's mapper writes a row exactly as the DAO's does");

        final LedgerData live = LedgerData.live(DAO.getInstance());
        assertTrue(live.liveRows().isEmpty(), "in local mode the cache is the ledger's store: a scan finds no rows");
        assertFalse(live.orgIds().isEmpty(), "the organizations table is one the fake backs");
    }
}
