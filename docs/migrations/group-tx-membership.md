# Migration: group transaction membership (`groupPeople`)

**Goal:** Shared/Batch transactions used to discover their members by iterating **every person in the
system** and probing their transaction partitions for the groupId. The app now stores the member list
directly on each group transaction row (`groupPeople` inside the `content` JSON), written on every
group save.

**Legacy rows** (saved before this change) have no `groupPeople`. Until they are migrated:

- `getUserAmount` for a **Shared** tx falls back to "just this user" — the amount shows **unsplit**
  on transaction pages.
- Editing a legacy batch in `admin/batchTx.jsf` only sees the row it was opened from, so saving could
  orphan the other members' rows.
- Each access logs: `Group tx '...' has no groupPeople -- legacy row, run the group-tx migration.`

Run this migration once per environment right after deploying the change. It is also the tool to fix
**other instances of the database** (e.g. the old us-west-2 tables vs. the live us-east-1 account) —
run it once per table location with the right `--profile`/`--region`.

## What the script does

1. Scans the `transactions` table once.
2. Groups non-deleted rows by `groupId`; the member list for a group = the sorted set of `userId`s
   that still have a live row in that group.
3. For every row whose `content` has a `groupId` but no `groupPeople`, rewrites `content` with
   `groupPeople` set to that member list.

Deleted rows are left untouched (they are display-history only). The script is idempotent: rows that
already have `groupPeople` are skipped, so re-running is safe.

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
