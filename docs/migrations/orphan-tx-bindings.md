# Cleanup: orphaned TRANSACTION bindings

**Goal:** delete the `bindings` rows that point at a transaction which no longer exists.

Until 2026-09-17 nothing removed a deleted transaction's bindings. The Delete button on
`trip/transaction.jsf`, the trash icon on the ledger, and the group editor's member removal each wrote the
soft-delete and stopped, leaving the row's `TRANSACTION->TRIP` and `TRANSACTION->TRIP_EVENT` edges behind --
both directions of each, since a bidirectional binding is two independent rows (`BindingDAO`). Every delete
path now drops its own bindings (`TransactionsCommands.deleteRow`); this script sweeps up what the old ones
left.

Nothing is broken by the orphans: the trip-side ledger (`trip/tripTransactions.jsf`) resolves each bound id
and null-checks the miss, which is exactly why they were invisible for years. What they cost is work --
every render of that page walks every dead edge -- and clarity, since `getBindings` on a trip no longer
answers what it claims to.

A production audit on 2026-09-17 found **718 orphaned edges = 1436 rows** (685 TRIP, 33 TRIP_EVENT, each in
both directions) against 5409 live edges.

## Why an orphan can never come back

A deleted transaction is never revived. `TransactionDAO` filters `deleted` out of every partition load and
evicts the row from the cache, so `getGroupTransactionForUser` cannot find it and a re-added group member
gets a **new** `txId` with new bindings of its own. Deleting the old edges is therefore safe in a way that
deleting most orphans is not.

## Run it

**Dry run is the default** -- unlike the additive migrations, this one removes rows.

```sh
# Review. Prints the classification and every row it would delete; writes nothing.
./scripts/sweep-orphan-tx-bindings.sh --profile cdk-deploy --region us-west-2

# Apply, after reading that listing.
./scripts/sweep-orphan-tx-bindings.sh --profile cdk-deploy --region us-west-2 --apply
```

Both forms write a **journal** of the rows in question before touching anything, and print its path; that
file is the undo (see "If it deleted too much" below).

Deletions run 25 at a time (`--concurrency <n>`). A failed row is reported and counted but never aborts the
run; the script exits non-zero if anything failed, and re-running retries exactly those rows (DynamoDB's
`DeleteItem` is idempotent, and a row already gone is no longer in the scan).

`--bindings-table` / `--transactions-table` point it at another environment's tables; the other deployment
(`mir2026` in us-east-1, a DIFFERENT AWS account) needs its own run with that account's profile.

## Two classes, and why only one is swept by default

| class | meaning | swept |
|---|---|---|
| `DEAD` | the transaction row exists and carries `deleted`. Provably gone. | by default |
| `MISSING` | no transaction row at all -- hard-deleted, or written against another environment's table. | `--include-missing` |

"I can see that it is deleted" is a stronger claim than "I could not find it", and the difference matters
when the thing being decided is a delete. The production audit found zero `MISSING` rows.

The script also **refuses to act on a partial scan**: a truncated transactions scan would classify every
transaction it failed to fetch as `MISSING`, which under `--include-missing` would mean proposing to delete
live bindings. The AWS CLI paginates on its own, so a `LastEvaluatedKey` in either response means it stopped
early, and the run aborts.

## If it deleted too much: how to get it back

Three routes, best first.

### 1. The run's own journal (precise, seconds, no AWS ceremony)

Every run writes one, **before** it deletes anything, and says where:

```
Journal (the undo for this run): ./orphan-tx-bindings-journal-20260918T051200Z.json
```

It holds the COMPLETE rows the run deletes, one DynamoDB item per line. That is a lossless copy, not a
summary: a binding row is exactly four string attributes (`id1`, `id1_type`, `id2`, `id2_type`) and nothing
else -- verified across all 12254 production rows -- so there is no hidden field a journal could drop. Put
them all back with:

```sh
./scripts/sweep-orphan-tx-bindings.sh --profile cdk-deploy --region us-west-2 \
    --restore-from ./orphan-tx-bindings-journal-20260918T051200Z.json
```

Restore is idempotent (`PutItem` is an overwrite, so a row that never went is rewritten with the same
content), it refuses a journal that is not one binding item per line, and it invalidates the `binding` cache
scope afterwards like the sweep does. A partial restore can simply be re-run.

Pass `--journal <file>` to choose the path. **Keep the journal until you are satisfied** -- it is the only
recovery route that is exact, instant, and needs no new table. A dry run writes one too, describing rows that
are still there; restoring that is a harmless no-op.

### 2. Point-in-time recovery (the journal is lost, or something else went wrong too)

The `bindings` table has PITR on with a **35-day** window (and deletion protection, so the table itself
cannot be dropped). Confirm the window before relying on it:

```sh
aws dynamodb describe-continuous-backups --table-name bindings --profile cdk-deploy --region us-west-2
```

The catch worth knowing in advance: **DynamoDB PITR always restores to a NEW table.** There is no in-place
rewind and no rename, and `BindingDAO` hardcodes the table name `bindings`, so you cannot just point the app
at the restored copy. The realistic sequence is:

```sh
# 1. Restore to a side table at a time before the sweep.
aws dynamodb restore-table-to-point-in-time --profile cdk-deploy --region us-west-2 \
    --source-table-name bindings --target-table-name bindings-restore \
    --restore-date-time 2026-09-18T05:00:00Z

# 2. Diff it against the live table and re-put what is missing. The rows are four string attributes, so
#    a scan of each side plus `comm` on the sorted (id1, id2) pairs is the whole comparison; feed the
#    missing items back through --restore-from, which takes any file of binding items.
```

Then delete `bindings-restore` so it stops costing anything. Restoring a 1.8MB table is quick and cheap, but
it is minutes and manual steps, which is why the journal is route 1.

### 3. The monthly AWS Backup snapshot (last resort)

`trip-dynamodb-monthly` covers every table by wildcard with 1-year retention. Same restore-to-a-new-table
shape as PITR, but up to a month stale, so it is only useful if both the journal and the PITR window are
gone.

### What no route can recover

Nothing here reconstructs a binding whose row was never in the table. The sweep only ever deletes rows it
read in the same run, so that case does not arise -- but it is the reason the script refuses to act on a
partial scan rather than guessing.

## After running

The script writes DynamoDB **directly**, bypassing the shared Valkey cache, so it calls
`trip_invalidate_cache binding` (`lib/cache-invalidate.sh`) on a live run: that needs `TRIP_APP_URL` and
`TRIP_ADMIN_EMAIL` exported, and otherwise prints the manual reminder. Either way, use the admin Settings
page's "Clear caches", or wait for the soft-TTL refresh.

## Verify

Re-run the dry form. It should report only `live` rows and "Nothing to do."
