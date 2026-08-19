# Migration: cache freshness overhaul + event-driven invalidation (`cache_inval`)

For deployments of this codebase that are still on the pre-2026-08 cache design — specifically the
`mir2026` branch deployment (us-east-1, separate AWS account, EC2-era topology, typically
`TRIP_CACHE_MODE=memory` with no Valkey). The actively developed us-west-2 deployment adopted these
changes as they shipped and needs nothing from this document.

## What changed (three deploys on master)

1. **Envelope freshness** — `PointCache` values became `"<epochMillis>|<json>"`; the sibling `:at` keys
   are gone. All six typed caches share one `Revalidator` for staleness + background-refresh gating.
2. **Event-driven invalidation** — `DAO.invalidate(scope)`, the `sys:v1:cache_inval` pub/sub broadcast,
   `POST /api/cache/invalidate` (CONFIG_ADMIN), `web/CacheInvalidationListener` in the live `web.xml`,
   and `trip_invalidate_cache` hooks in the migration scripts. `CacheKeys.SOFT_TTL` rose 1h → 6h.
   A second (background) Valkey connection with a small queue isolates background work.
3. **Lazy trip events** — `Trip` stores event ids and resolves `TripEvent`s on first access.

## When to run

When merging master into `mir2026` (or any deployment on the old design). There is nothing to run
*before* the merge — this migration is about knowing what does and does not need doing.

## Steps

1. **DynamoDB: nothing.** No table, row, or GSI changes in any of the three deploys. The `trips` row
   already stored event ids (`"tripEvents": ["id", ...]`) and still does — byte-identical.
2. **web.xml:** your deployment's live descriptor must add the `CacheInvalidationListener` entry AFTER
   `TripBootstrapListener` (copy the block from the main deployment's
   `medjugorje/webapp/WEB-INF/web.xml`). Skipping it costs only the multi-instance heap-drop path.
3. **Cache contents: nothing.**
   - *Valkey deployments:* pre-envelope values are read as legacy, served once as stale, and rewritten
     enveloped by the background refresh. Orphaned `:at` keys expire via the 7-day GC TTL. No flush, no
     `t1:` version bump.
   - *`memory` mode:* the per-JVM cache is rebuilt on every restart, so the deploy restart is the
     migration.
   - *`off` mode:* nothing to do.
4. **Scripts:** the migration scripts now end live runs with `trip_invalidate_cache <scope>`. Opt in by
   exporting `TRIP_APP_URL` and `TRIP_ADMIN_EMAIL` (password prompted, or `TRIP_ADMIN_PASSWORD`).
   Without them, scripts print the old manual clear-caches instruction — nothing breaks.

## Verify

- App starts; the log shows `Subscribed to cache-invalidation channel sys:v1:cache_inval` (Valkey mode)
  or no such line with a clean start (memory/off — the subscribe is a no-op).
- `POST /api/cache/invalidate` with body `{"scope":"config"}` (admin session + `X-Trip-Api: 1` header)
  returns the cleared prefixes and writes a CONFIG audit event.
- After a restart with a pre-deploy session cookie, pages load without 500s (the Trip field change in
  deploy 3 altered nothing session-reachable; this check proves it for your deployment too).

## Cache interaction

- **`memory` mode:** the invalidation *clear* is real (it drops the per-JVM entries — an upgrade over
  "restart Tomcat"), while the pub/sub *broadcast* is inert, which is correct with one JVM.
- **Valkey mode:** the initiator clears the shared namespaces once; other instances only drop their
  in-JVM heap copies on the broadcast. Missed events heal via the 6h soft TTL.
- The endpoint takes scope names (`person`, `trip`, `tx`, ..., `all`), never raw key prefixes — see
  `DAO.CacheScope`.
