---
paths:
  - "scripts/**"
  - "docs/migrations/**"
  - "trip/src/main/java/org/paulsens/trip/migrate/**"
---

# Scripts & data migrations

The conference import from an older deployment (people, past conferences, registrations, ledger, roles) is the
`org.paulsens.trip.migrate` CLI, run by the private repo's `scripts/conf-import/` -- plan (reads only), rehearse
(in memory), apply, verify, rollback; `docs/migrations/conference-import.md` holds the owner's rules and the checks.

`scripts/`: `backfill-people-email.sh` (email-index GSI backfill), `migrate-group-tx-membership.sh`
(also `--repair`, for group rows whose stamped membership went stale), `sweep-orphan-tx-bindings.sh`
(deletes bindings left behind by deleted transactions; dry-run by default),
`rotate-password-pepper.sh` (pepper rotation and plaintext-password migration). One-time migration docs are in
`docs/migrations/`. The family-accounts migration scripts (report/migrate/consistency) live in the private
repo's `scripts/` -- see `docs/family-accounts.md`.
