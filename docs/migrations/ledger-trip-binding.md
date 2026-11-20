# Ledger trip binding -- every transaction gets a trip

A one-time migration ahead of per-organization balances: every live ledger row that is bound to no trip is bound
to the trip it belongs to, so a row's organization is always derivable from its trip. Code:
`org.paulsens.trip.migrate.LedgerBindingCli` and its `Ledger*` helpers; runner: the private repo's
`../medjugorje/scripts/ledger-bind.sh`. It adds `TRANSACTION -> TRIP` and `TRIP -> TRANSACTION` bindings and NOTHING
else: no transaction row is written, which is what makes "nobody owes more or less" provable.

Two consequences the owner accepted: a newly bound row joins that trip's books (its `tripFinView` / `tripFinAdmin`
holders can read it, its admins edit it), and the trip's totals on `tripTransactions.jsf` move by exactly the rows
it gains.

## The procedure

```sh
cd ../medjugorje/scripts
./ledger-dump.sh                                            # read-only raw scans -> ledger-migration/baseline-<ts>/
./ledger-bind.sh snapshot --dump <dump> --run <name>        # snapshot.json: the "before" figures
./ledger-bind.sh plan     --dump <dump> --run <name>        # plan.json, uncertain.csv, per-trip-summary.csv, plan.txt
#   the owner reviews uncertain.csv and fills approvedTripId where a row should be bound
./ledger-bind.sh rehearse --dump <dump> --run <name> --approved <reviewed csv>
./ledger-bind.sh snapshot --run <go-live>                   # at a quiet time: a FRESH snapshot of the live tables
./ledger-bind.sh apply    --run <go-live> --plan <plan.json> --approved <reviewed csv>           # dry run
./ledger-bind.sh apply    --run <go-live> --plan <plan.json> --approved <reviewed csv> --apply
#   the script invalidates the app's binding cache (TRIP_APP_URL and TRIP_ADMIN_EMAIL set), else: press Clear caches
./ledger-bind.sh verify   --run <go-live> --before <go-live>/snapshot.json --plan <plan.json> --approved <reviewed csv>
./ledger-bind.sh rollback --run <go-live> --apply            # only if needed; the same cache step follows
rm -rf ../../ledger-migration/<dump>                         # once verify passes and the owner is satisfied
```

1. **snapshot** computes the figures every check compares against, by TODAY'S arithmetic
   (`TransactionsCommands.getUserAmount` folded in double as `getBalance` does, nothing rounded per row): every
   partition's balance, its balance per organization, every trip's three totals (`TripLedgerTotals`), the trip-bound
   money of everyone on a trip that has not ended (`CheckinCommands.balanceOf`: cents, rounded per row), a hash of
   every row's stored JSON, and the live rows bound to no trip. Partitions come from the transactions scan, not from
   `people`, so a trip's own rows are counted.
2. **plan** reads only and decides every trip-less live row (rules below). `plan.json` is exactly what an apply
   binds; `uncertain.csv` is what it will not bind without the owner.
3. **The owner reviews `uncertain.csv`.** One line per row: name, person id, txId, date, the person's share and the
   whole amount, type, category, note, the group's members, each possible trip (title, dates, ended or not, what the
   person's rows already on it net to, id), why it is uncertain, the best guess and its reason, a note that the row
   joins the trip's books, and an empty `approvedTripId`. Fill in a trip id to bind the row; leave it blank to leave
   the row alone. Typed text that a spreadsheet would run as a formula carries a leading apostrophe.
4. **rehearse** runs the real applier and verifier on the dump IN MEMORY: apply, the seven checks, a second apply
   (which must write nothing) and the rollback (which must restore the dump's bindings exactly). It touches no store.
   Before the review it ends "PASSED SO FAR" (check 7 fails only on the rows awaiting approval).
5. **apply** runs `plan.json` as reviewed plus the approved rows, at a quiet time and only on the owner's go-ahead.
   Without `--apply` it is a dry run: every check below against the deployment, no write. Each row is read again,
   uncached, just before its write, and SKIPPED (listed in `apply.txt`) when it was deleted, edited (its content no
   longer matches the plan's fingerprint) or bound to another trip since the plan; a row already on its trip is left
   alone, so a second run writes nothing. A trip that is gone or belongs to another organization is a failure. Each
   binding is written with `DAO.saveBinding` (its answer checked; never `setBindings`, which replaces a row's bindings
   and only logs a failure) and both directions are read back. `rollback.json` lists exactly the bindings added, saved
   BEFORE each write so an interrupted run is still fully undoable; a re-run in the same folder keeps it. Each
   save replaces the file in one move (a temporary file beside it, then an atomic rename), so an interruption
   mid-save leaves the previous complete list, never an empty or cut-off file.
   Until 2026-11-20 every binding READ with the cache off answered empty (`AdjacencyCache` returned the set of a
   cache that keeps nothing instead of the rows it had just loaded): the read-back reported every correct write as
   "written, but not read back in both directions", the "already on this trip" and "bound to another trip" checks
   saw nothing, and a rollback would have found every binding ABSENT and removed none. `verify` and the snapshot
   scan the table directly, which is why they agreed the writes were right.
6. **The app's cache.** The CLI runs with the cache off and never touches the app's shared cache; after an apply or
   a rollback that wrote, `ledger-bind.sh` invalidates the app's binding cache itself when `TRIP_APP_URL` and
   `TRIP_ADMIN_EMAIL` are exported, and otherwise says to press Clear caches (admin Settings).
7. **verify** runs the seven checks of the ledger now against a snapshot, and exits 2 unless all pass. Rows changed by
   ordinary activity since the snapshot show up in check 3, which is why the snapshot is taken right before the apply
   (and again right before the deploy of the per-organization code).
8. **rollback** removes exactly the bindings in `rollback.json` (dry run without `--apply`), each only if it is still
   there, and reads back that it is gone. Point-in-time recovery of `bindings` is the backstop.
9. **Delete the dumps.** A dump holds ledger rows and every person's name (the dump script reads only the six tables
   the tool reads, and keeps of a person only the name and of a registration only its trip, person and status):
   once verify has passed on the deployed code and the owner is satisfied, delete each dump folder under
   `ledger-migration/`. The run folders (plan, snapshot, rollback list) are kept only while a rollback is still
   wanted.

`--dump <dir>` makes snapshot, plan and verify read a dump directory instead of the live tables (local mode: no AWS
at all); without it they scan the live tables once each, strongly consistently, through `DAO.scanTable`. A dump is
parsed directly rather than loaded into the in-memory store, because a row re-saved through the DAO is re-serialized
and check 3 compares the stored JSON byte for byte. `--today YYYY-MM-DD` pins the day "has this trip ended" is asked
on. Every file of a run is created mode 600 in a mode 700 folder: they name people, rows and amounts.

## The planning rules, in the order they apply

A trip is a **candidate** for a row when it belongs to the row's organization and the person (the row's `userId`) is
on it: on its roster (`Trip.people`) or holding a registration whose status is not `NOT_REGISTERED`. It is **in
window** when the row's date falls from 18 months before the trip's start to 4 months after its end. A trip **has
ended** when its end date is before today. Nothing is ever bound onto a trip that has not ended: balance reminders,
the ticket page and check-in read the trip-bound balance of a running trip, so a wrong guess there would mail somebody
a wrong amount. Those rows go to the owner, with the trip as the best guess.

1. **Groups first, never split.** All live rows of a `groupId` (Shared or Batch) are decided together. When some are
   already on a trip, the rest join that trip. When none are, the group binds to the ONE in-window trip common to
   every member; with none or several the whole group goes to the owner, even where one member alone would be certain.
2. **A trip's own rows** (`userId` is a trip id) bind to that trip; no roster or window is asked.
3. **Deterministic singles**: exactly one in-window candidate.
4. **Inferred singles** (several in-window candidates), per person and organization, all or nothing. The person's
   live rows of that organization are walked in date order. A row already on a trip (bound, or planned by rules 1 to
   3) counts there. A bill to place goes to the earliest in-window trip not yet invoiced, else to the earliest one
   not fully paid; a payment (anything that is not a bill) to the earliest in-window trip not fully paid, where
   "fully paid" means invoiced and netting to zero or more. The placements stand only if the trips then reconcile in
   cents: every trip nets to zero, or exactly one ended trip holds a remainder equal to the person's balance in that
   organization. One row with nowhere to go, or one row that could belong to a running trip, sends all of that
   person's inferred rows to the owner, each with its placement as the best guess.
5. **Uncertain**: everything else, with its reason (`NO_ROSTER`, `NO_WINDOW`, `NO_DATE`, `NOT_ENDED`,
   `ORG_MISMATCH`, `MISSING_TRIP`, `GROUP_NO_COMMON_TRIP`, `GROUP_SEVERAL_TRIPS`, `GROUP_SPLIT`, `NOT_RECONCILED`).

An approval is refused (and the run exits 2) when it names a row the plan did not leave open, a trip other than the
one the rest of the row's group is already on, or only part of a group or two trips for one group.

## What verify proves

For EVERY partition and trip, at system reach (no site filter):

1. every balance by today's arithmetic (the snapshot) equals the NEW code's balance, to the cent;
2. every per-organization balance equals the snapshot's, and they sum to the org-blind total;
3. every live row is still stored with an identical hash of its stored JSON (changed, deleted, gone and added rows
   are each listed);
4. every live row is on exactly one statement of its owner, its own organization's, the statements together are
   the person's live rows, and each statement's balance is the sum of its rows;
5. per trip, the three totals over the rows it had are identical, and the rows it gained are itemized (a gained row
   that was not trip-less before is a failure);
6. the trip-bound balance of everyone on a trip that has not ended is unchanged. A row the owner approves onto a
   running trip fails this check BY DESIGN: the report names the trip and the person, so the owner sees it is exactly
   the approved row;
7. invariants: no trip-less row, no row or trip without an organization, no row whose organization is not its trip's,
   no row on several trips or on a missing trip, every binding present in both directions, every group's rows on one
   trip, and every group row's stamped `groupPeople` equal to the group's live rows.

Checks 1, 2 and 4 read the new statement code through ONE seam, `LedgerBindingVerifier.Statements` (a person's
organizations, each with the balance the new code states and the row ids it lists). The seam is
`LedgerStatementSource`: `action/LedgerStatements`, the very class `LedgerCommands` (`#{ledger}`, the Balance page)
buckets, folds and lays statements out with, run over the parsed tables for a viewer who sees every organization.
It needs no store, cache or request, which is how it works on a dump; a whole partition is handed to it, soft-deleted
rows included, so leaving those off is the statement code's doing. Checks 1 and 2 compare the snapshot with the
balances that code STATES; check 4 also fails a statement whose balance is not the sum of the rows it lists.

`TripLedgerTotals` is the ONE bucket rule for a trip's three totals (a row whose `userId` is the trip is a "trip"
row; another `Bill` is an "invoice" row; the rest are "user" rows; each total is the fold of `getUserAmount`).
`TransactionsCommands.getTripLedgerTotals(tripRows, invoiceRows, userRows)` folds each list a page already
lists (the trip ledger page, `tripTransactions.jsf`, gathers its three lists through the site-filtered
`getTransaction` and hands them in), and the page renders its three footers from it, so the footers and this tool
are one rule, and nothing is read twice. The page gathers its three LISTS itself;
`TripLedgerTotalsTest.theFootersAreTheTotalsOfTheRowsEachHostLists` checks, on an organization's host and on a
shared one, that each footer is the total of exactly the rows its table lists.
