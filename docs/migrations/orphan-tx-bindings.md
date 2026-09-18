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

## After running

The script writes DynamoDB **directly**, bypassing the shared Valkey cache, so it calls
`trip_invalidate_cache binding` (`lib/cache-invalidate.sh`) on a live run: that needs `TRIP_APP_URL` and
`TRIP_ADMIN_EMAIL` exported, and otherwise prints the manual reminder. Either way, use the admin Settings
page's "Clear caches", or wait for the soft-TTL refresh.

## Verify

Re-run the dry form. It should report only `live` rows and "Nothing to do."
