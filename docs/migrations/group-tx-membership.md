# Migration: group transaction membership (`groupPeople`)

**Goal:** Shared/Batch transactions used to discover their members by iterating **every person in the
system** and probing their transaction partitions for the groupId. The app now stores the member list
directly on each group transaction row (`groupPeople` inside the `content` JSON), written on every
group save.

There are now TWO things this script fixes. **Legacy rows** have no `groupPeople` at all (the default
mode), and **stale rows** have one that disagrees with the live membership (`--repair`, see below).

**Legacy rows** (saved before this change) have no `groupPeople`. Until they are migrated:

- `getUserAmount` for a **Shared** tx falls back to "just this user" — the amount shows **unsplit**
  on transaction pages.
- Editing a legacy batch in `admin/batchTx.jsf` only sees the row it was opened from, so saving could
  orphan the other members' rows.
- Each access logs: `Group tx '...' has no groupPeople -- legacy row, run the group-tx migration.`

Run this migration once per environment right after deploying the change. It is also the tool to fix
**other instances of the database** (e.g. the old us-west-2 tables vs. the live us-east-1 account) —
run it once per table location with the right `--profile`/`--region`.

## `--repair`: rows whose membership went stale

Until 2026-09-17 a group row could be deleted **on its own** -- the Delete button on
`trip/transaction.jsf`, and the group editor's member removal -- which left every surviving row still naming
the person who went. For a **Shared** group that list is the divisor, so a $150 payment split three ways kept
reporting $50 shares after a member was removed: $50 of it appeared in no balance, total or report. The stale
list also made the group editor offer the removed person again, and saving there recreated their row under a
new `txId`, silently undoing the delete.

The app no longer writes such rows (every removal goes through `TransactionsCommands.removeFromGroup`, which
restamps the survivors -- `docs/payments.md`, "Group transactions"). `--repair` cleans up the ones already in
the table:

```sh
./scripts/migrate-group-tx-membership.sh --repair --dry-run          # review first, ALWAYS
./scripts/migrate-group-tx-membership.sh --repair --profile cdk-deploy
```

It selects, in addition to the legacy rows, any row whose stamped list differs **as a set** from the userIds
that still have a live row in that group, and rewrites it to the live set. Order is not a difference: the
stored order is whatever a save happened to write. One table scan, so the cost is negligible.

What it does NOT do: recover money. Making the surviving rows agree about who is in the group is what puts the
shares back on the ledger; if a row was deleted that should not have been, the amount it carried was never
recorded anywhere else and has to be re-entered by hand. Run `--dry-run` first and read the list.

## What the script does

1. Scans the `transactions` table once.
2. Groups non-deleted rows by `groupId`; the member list for a group = the sorted set of `userId`s
   that still have a live row in that group.
3. For every row whose `content` has a `groupId` but no `groupPeople`, rewrites `content` with
   `groupPeople` set to that member list. With `--repair`, also every row whose `groupPeople` differs from
   that member list as a set.

Deleted rows are left untouched (they are display-history only). The script is idempotent: rows that
already agree with the live membership are skipped, so re-running is safe in either mode.

## Run it

```sh
# Preview (lists every row that would change; writes nothing):
./scripts/migrate-group-tx-membership.sh --dry-run

# Apply (live environment example -- production account, us-east-1):
./scripts/migrate-group-tx-membership.sh --profile prod --region us-east-1
```

Updates run **25 at a time** (`--concurrency <n>` to change). The table scan is a single pass; the
`UpdateItem` calls are what benefit from the parallelism, since each one is a separate `aws` CLI
invocation. On-demand DynamoDB absorbs this rate easily — if you ever see
`ProvisionedThroughputExceededException`, lower the concurrency and re-run (see below).

A row that fails is reported on stderr and counted, but does **not** abort the run; the script exits
non-zero if anything failed. Because it only ever touches rows that still lack `groupPeople`, simply
re-running it retries exactly the failed rows and nothing else.

## After running

The script writes DynamoDB **directly**, bypassing the shared Valkey cache, so cached transaction
partitions (`t1:tx:{userId}`) can serve the old JSON for up to the soft-TTL window. Either:

- use the admin "clear all caches" action, or
- restart Tomcat (same effect via cold cache), or
- wait: soft revalidate heals each partition within ~1 hour of next access.

## Verify

Open a Shared transaction in the UI: the per-user amount should show the split (total ÷ members), and
the `legacy row` WARN should no longer appear in `catalina.out`. Or spot-check a row:

```sh
aws dynamodb get-item --table-name transactions \
    --key '{"userId":{"S":"<uid>"},"txId":{"S":"<txid>"}}' \
    --query 'Item.content.S' --output text | jq '.groupPeople'
```
