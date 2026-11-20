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

The ledger trip-binding migration is `migrate/LedgerBindingCli` (run by the private repo's `scripts/ledger-bind.sh`):
snapshot, plan, rehearse, apply, verify, rollback; all but apply and rollback run offline on a dump (`--dump`). It
binds every trip-less live ledger row to its trip and writes bindings ONLY, never a transaction row;
`docs/migrations/ledger-trip-binding.md` holds the planning rules and the seven verify checks. Its trip totals are
`action/TripLedgerTotals`, the same rule `TransactionsCommands.getTripLedgerTotals` gives the page.

`scripts/`: `backfill-people-email.sh` (email-index GSI backfill), `migrate-group-tx-membership.sh`
(also `--repair`, for group rows whose stamped membership went stale), `sweep-orphan-tx-bindings.sh`
(deletes bindings left behind by deleted transactions; dry-run by default),
`rotate-password-pepper.sh` (pepper rotation and plaintext-password migration). One-time migration docs are in
`docs/migrations/`. `migrate/NoteElFlattenCli` (run by the private repo's `scripts/flatten-note-el.sh`) replaced
the room EL stored in trip events' private notes with text (`docs/migrations/note-el-flatten.md`); notes are never
evaluated now. The family-accounts migration scripts (report/migrate/consistency) live in the private
repo's `scripts/` -- see `docs/family-accounts.md`.

- `rotate-password-pepper.sh` is DRY-RUN unless `--apply`. Rotation adds version N+1 and RETAINS old keys (a
  supplied `--value` must be strict base64; `openssl base64 -d` is too lenient). `--migrate` runs
  `PasswordMigration` (`--parallel N`; idempotent, re-run until `plaintext=0`) from `trip/target/ROOT`, so
  `mvn install` first: a stale ROOT once ran an old argument parser and silently stayed in dry-run.
