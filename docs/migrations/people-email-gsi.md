# Migration: `people` email GSI (email-index)

**Goal:** `getPersonByEmail` (login, password reset, duplicate-account check, mail merge) must work
without scanning the whole `people` table. The app now queries a GSI named **`email-index`** whose
partition key is a top-level, **lowercased** `email` attribute on each people row.

New/updated people get the attribute automatically (`PersonDAO.savePerson` writes it). Existing rows
need a one-time backfill, and the GSI itself must be created once per environment.

## When to run

After deploying the code that contains this change (any order relative to the GSI creation is fine —
the GSI indexes rows as the attribute appears). Until both steps are done, `getPersonByEmail` returns
null for people whose row lacks the attribute — **so run this immediately after deploy**: first-time
logins and password resets depend on it.

## Step 1 — create the GSI (once per environment)

```sh
aws dynamodb update-table \
    --table-name people \
    --attribute-definitions AttributeName=email,AttributeType=S \
    --global-secondary-index-updates '[{"Create":{
        "IndexName":"email-index",
        "KeySchema":[{"AttributeName":"email","KeyType":"HASH"}],
        "Projection":{"ProjectionType":"ALL"}}}]'
```

Notes:
- On-demand tables need no provisioned throughput for the GSI.
- The index is **sparse**: rows without the `email` attribute (no email, or deleted people) are simply
  not indexed — that is intentional.
- Wait for `IndexStatus: ACTIVE` (`aws dynamodb describe-table --table-name people`).

## Step 2 — backfill existing rows

```sh
# Preview what would change:
./scripts/backfill-people-email.sh --dry-run

# Apply:
./scripts/backfill-people-email.sh
```

The script scans `people` once and, for each row, promotes the email found inside the `content` JSON
to the top-level `email` attribute (lowercased/trimmed). Deleted people and people without an email
get the attribute **removed** so they stay out of the index. It is idempotent — rerunning it is a
no-op for rows already correct.

Remember the environment: the live tables are in **us-east-1 in the production AWS account** (not the
laptop default profile). Pass `--profile <name> --region <region>` accordingly, e.g.:

```sh
./scripts/backfill-people-email.sh --profile prod --region us-east-1
```

## Step 3 — verify

```sh
aws dynamodb query --table-name people --index-name email-index \
    --key-condition-expression "email = :e" \
    --expression-attribute-values '{":e":{"S":"someone@example.com"}}'
```

Then log out/in on the site with a known account (login resolves the person by email on first use).

## Cache interaction

None required. The backfill does not change `content`, so cached Person JSON stays valid; the email
lookup cache (`t1:email` hash in Valkey) fills lazily per lookup and heals itself against the GSI.
