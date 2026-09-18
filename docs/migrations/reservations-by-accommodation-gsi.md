# Migration: `lodging_reservations` by-accommodation GSI (by-accommodation)

**Goal:** the hotel availability view (`LodgingDAO.getReservationsAt`, behind the lodging calendar and the
cross-trip occupancy check) must answer "every trip's stays at THIS hotel" without scanning the table.
Reservations are partitioned by **trip**, which is the right shape for every other read, so the hotel
question is exactly the one the primary key cannot serve. The app now queries a GSI named
**`by-accommodation`**: partition key `accommodationId` (S), sort key `stayEnd` (S), projection ALL.

Both keys are top-level attributes promoted out of the `content` JSON by `LodgingDAO.saveReservation`:
`accommodationId` is the hotel, `stayEnd` is the stay's end truncated to minutes (`yyyy-MM-ddTHH:mm`), so
the sort key compares lexically and a date window is a bounded `BETWEEN` query. The DAO writes **both or
neither** -- a reservation with no hotel or no end date stays out of the index, which is what makes it
sparse and cheap.

New and re-saved reservations get the attributes automatically. Existing rows need a one-time backfill, and
the index itself must be created once per environment.

## Order of operations

1. **Deploy the infrastructure change first** (`cdk deploy TripApp`, user-run) so the index exists.
2. Deploy the application.
3. Run the backfill.

There is **no outage window**, and that is by design rather than by luck: an empty or half-filled index does
not produce a wrong answer, it produces a *smaller* one. A reservation missing from the index reads as "no
other trip has this room on those nights", which is the same thing the code sees for a hotel nobody has
booked yet. So the hotel calendar and the occupancy warnings simply **under-report** between steps 1 and 3
-- an admin may be offered a room another trip already holds. Do not leave that gap open for long, and do
not treat the window as a reason to delay step 1: querying an index that does not exist is a hard
`ValidationException`, so the index must lead the code, never follow it.

## Step 1 -- create the GSI (once per environment)

The production table is CDK-managed, so the normal path is the stack deploy above, and this command is the
manual equivalent for a table CDK does not own (a hand-made dev table, or the separate `mir2026` us-east-1
deployment in its own AWS account):

```sh
aws dynamodb update-table \
    --table-name lodging_reservations \
    --attribute-definitions AttributeName=accommodationId,AttributeType=S \
                            AttributeName=stayEnd,AttributeType=S \
    --global-secondary-index-updates '[{"Create":{
        "IndexName":"by-accommodation",
        "KeySchema":[{"AttributeName":"accommodationId","KeyType":"HASH"},
                     {"AttributeName":"stayEnd","KeyType":"RANGE"}],
        "Projection":{"ProjectionType":"ALL"}}}]'
```

Notes:
- On-demand tables need no provisioned throughput for the GSI.
- The index is **sparse**: rows without both attributes (no hotel, or no end date) are simply not indexed
  -- that is intentional, and the backfill below preserves it.
- Projection is **ALL** because the caller wants whole `Reservation` rows (occupants, roomId, status);
  KEYS_ONLY would turn one query back into a GetItem per stay.
- The task role needs no new IAM grant: `grantReadWriteData` on the table already covers
  `${tableArn}/index/*`.
- Wait for `IndexStatus: ACTIVE` before running the backfill:

```sh
aws dynamodb describe-table --table-name lodging_reservations \
    --query 'Table.GlobalSecondaryIndexes[?IndexName==`by-accommodation`].IndexStatus'
```

Backfilling into an index that is still `CREATING` is not harmful (DynamoDB folds the writes into the
build), but the verification query in step 3 fails until it is ACTIVE, so waiting keeps the run readable.

## Step 2 -- backfill existing rows

```sh
# Preview what would change:
./scripts/backfill-reservations-index.sh --dry-run

# Apply:
./scripts/backfill-reservations-index.sh
```

The script scans `lodging_reservations` once and, for each row, promotes `accommodationId` and the
minute-truncated `end` from the `content` JSON to the two top-level attributes. A row missing either one
gets **both removed**, so it stays out of the index; half a key pair would index a stay the query can never
match. It never writes `content` or `version` -- the optimistic-version guard must not see this as an
application write, and rewriting `content` would invalidate every cached `Reservation` for nothing. It is
idempotent: rerunning it is a no-op for rows already correct.

Remember the environment: the live tables are in **us-west-2**, reached with the `cdk-deploy` profile, not
the laptop default:

```sh
./scripts/backfill-reservations-index.sh --profile cdk-deploy --region us-west-2 --dry-run
```

## Step 3 -- verify

```sh
aws dynamodb query --table-name lodging_reservations --index-name by-accommodation \
    --key-condition-expression "accommodationId = :a AND stayEnd BETWEEN :lo AND :hi" \
    --expression-attribute-values '{":a":{"S":"<accommodation-id>"},
                                    ":lo":{"S":"2026-01-01T00:00"},
                                    ":hi":{"S":"9999-12-31T23:59"}}'
```

Then open a hotel on `admin/lodging.jsf` and confirm its availability view shows stays from more than one
trip, which is the whole point of the index.

## Cache interaction

`getReservationsAt` is deliberately uncached (an admin question asked on demand; a per-hotel cache would
have to be invalidated by every trip's writes), so the index itself needs no cache work. The backfill still
invalidates the `lodging` scope when `TRIP_APP_URL` and `TRIP_ADMIN_EMAIL` are exported, because the scan
proves nothing about what the per-trip reservation caches are holding; see
[cache-invalidation.md](cache-invalidation.md).
