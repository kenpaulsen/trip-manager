---
paths:
  - "trip/src/main/java/org/paulsens/trip/dynamo/**"
  - "trip/src/main/java/org/paulsens/trip/cache/**"
  - "trip/src/test/java/org/paulsens/trip/dynamo/**"
  - "trip/src/test/java/org/paulsens/trip/cache/**"
---

# Persistence (`org.paulsens.trip.dynamo`) and caching (`org.paulsens.trip.cache`)

- `Persistence` interface -- abstracts the store. `DynamoPersistence` is the real one (async client);
  `InMemoryPersistence` is a real in-memory table store used in local mode and tests (`FakeData` seeds it).
- `LocalMode` is the single authority on local vs production: the rule itself is in `CLAUDE.md`, "Rules that apply
  everywhere".
- `DAO` -- singleton composing the domain DAOs: Person, Family, Trip, TripEvent, Registration, Transaction, Credentials,
  Todo, PersonDataValue, Privileges, Binding, Config, Media, Template, Content, Audit, Chat. Payments add:
  Organization/OrgMember (org_members is the membership source of truth, Person.orgIds the derived edge),
  PaymentProcessorConfig (org-partitioned; secrets live in Secrets Manager via `security/ProcessorSecrets`, never in
  rows), and Payment (UNCACHED state machine CREATED/CAPTURED/RECORDED with conditional-put transitions) -- read
  `docs/payments.md` before touching any of them. Lodging adds `LodgingDAO` (`lodging_accommodations` whole-table
  `PartitionScanCache`; `lodging_offers` and `lodging_reservations` per-trip `PartitionCache`s, the latter with a sparse
  `by-accommodation` GSI (PK `accommodationId`, SK `stayEnd`) behind the uncached cross-trip read `getReservationsAt`;
  `lodging_blocks` per-hotel `PartitionCache` for the nights a hotel has taken its own rooms back, kept off the
  accommodation row so a block never races a room edit; `lodging_rate_plans` a second per-hotel `PartitionCache` in the
  same shape for the hotel's reusable `RatePlan`s, which linked offers COPY from (write-through, never a read-time
  join); versioned conditional puts) -- `docs/lodging.md`. The `family` row is the source of truth for household
  membership (optimistic-version conditional puts); managers' `Person.managedUsers` lists are DERIVED from it -- see
  `docs/family-accounts.md` before touching anything family-related. Trip budgets add `BudgetDAO` (`trip_budgets`, PK
  `tripId`: ONE `TripBudget` row per trip with its lines inline, one `PointCache`, one versioned conditional put per
  save) -- `docs/budget.md`. Trip expense reports add `ExpenseReportDAO` (`trip_expense_reports`, the same shape: ONE
  shared `TripExpenseReport` per trip, currencies, expenses and receipt ids inline) -- `docs/expense-report.md`. Trip
  documents ("waivers") add `WaiverDAO`: `org_waivers` (PK orgId, SK id; every published version inline, versioned puts)
  and `waiver_acceptances` (PK tripId, SK personId#waiverId#version; WRITE-ONCE, uncached, never deleted) --
  `docs/waivers.md`.
- **Caching** (`org.paulsens.trip.cache`) -- full architecture doc: `docs/caching.md` (read it before
  touching any cache or DAO read path). All caching goes through the `CacheClient` abstraction --
  `ValkeyCacheClient` (production, shared across instances; TWO connections: foreground queue 5000,
  background queue 500 routed by `CacheLane` -- background work sheds early and can never starve the
  request path), `InMemoryCacheClient` (local), `NoopCacheClient` (off), with `NearCacheClient` (in-JVM
  heap for `Cached.YES` reads) decorating the production client. Each DAO uses a typed cache on top
  (`PointCache`, `PartitionCache`, `PartitionScanCache`, `AdjacencyCache`, plus `SearchIndex`/`TripIndex`).
  Freshness: every cached value carries its loaded-at stamp (`PointCache` envelope `"<epoch>|<json>"`,
  in-hash `__loaded_at__`, index markers) -- staleness is decided INLINE, and only a stale hit schedules a
  background refresh through the shared `Revalidator` (dedup → `RefreshPermits` → lock → reload; gates
  before spawn, always -- the 2026-08-18 incident was a per-hit probe ahead of the gates). Out-of-band
  writes are made visible by `DAO.invalidate(CacheScope)` (clears + broadcasts on `sys:v1:cache_inval`;
  REST: `POST /api/cache/invalidate`; scripts: `scripts/lib/cache-invalidate.sh`). `AuditDAO` and
  `CredentialsDAO` are deliberately uncached. `DAO.clearAllCaches()` clears the data namespace only --
  never a Valkey FLUSH, never sessions. NB: `NoopCacheClient.tryAcquireLock` grants every lock -- never
  use a cache lock for exclusion without probing the cache mode first.
