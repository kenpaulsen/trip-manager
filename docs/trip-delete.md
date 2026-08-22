# Deleting a pilgrimage (destructive)

The "Delete Pilgrimage" button on `/trip/edit.jsf` permanently deletes a trip and every row that references
it. There is no soft-delete variant and no other caller: `TripDeleteCommands` (`#{tripDelete}`) owns the
authorization, the blocking conditions, and the cascade. Feature landed 2026-08-22.

## Who may delete

The trip's **Editor Admin** (`tripMgr`, which a site admin holds implicitly via `Caller.has`) who is **also
an admin of the organization that owns the pilgrimage** (`OrgCommands.canManageOrg`; site admins pass).
Org admins live on `Organization.adminIds` — roster-style membership, deliberately NOT a privilege row, so
they do not appear on `admin/editPrivs.xhtml`. A trip with no `orgId` falls back to site admins only
(`canManageOrg` refuses blank org ids for everyone, by design).

## What blocks a delete

The button renders in a lighter red while any condition holds; clicking it explains each reason via growl
instead of deleting. The server re-checks everything inside `deleteTrip` against fresh `Cached.NO` reads, so
a stale page cannot slip a delete through.

1. **People on the trip** (`Trip.people` non-empty).
2. **Approved registrations** (`Registration.Status.CONFIRMED`). Unapproved rows do not block — they are
   deleted with the trip, and the confirm dialog says how many.
3. **Payments not in a terminal state** — any payment row for the trip whose status is not `CANCELLED` or
   `FAILED`.
4. **Live transactions** — any trip-bound transaction (via `bindings`, `5_{tripId}` → `TRANSACTION`) whose
   `deleted` stamp is null.

**Financial history is never destroyed** (user decision 2026-08-22): once every bound transaction is
soft-deleted and every payment terminal, the delete proceeds and deliberately LEAVES the transaction rows,
payment rows, and their `bindings` links dangling as the record of money that once moved.

## The confirm dialog

Its own non-global `p:dialog` (`delTripDlg` — the page's `global="true"` confirmDialog belongs to the
trip-event delete). Type-`delete` challenge in the `adminManagePerson` style: the Delete button is
JS-disabled until the word is typed, and the server validates the challenge again. If the trip's chat still
holds messages or photos, the dialog says so in red — that is the "purge everything or cancel" decision
point (user decision: prompt, don't block).

## Cascade order (and why)

Rows that can only be FOUND through something else go while that something still exists; the trip row goes
LAST so a crash mid-cascade leaves a findable trip, and a re-run finishes the job (every step is idempotent).

1. **Chat** — `ChatCommands.purgeTripChat`: invite rows (`chat_invites`, which `purgeChannel` does not
   touch), the channel/messages/reactions/members purge, guests' `person:{id}` reverse-index rows in
   `chat_members` (captured from the member list BEFORE the purge deletes it), then
   `ChatPhotos.deleteAllForTrip`: album rows in slot `tripChat-{tripId}`, each photo's `photo:{s3Key}`
   comment channel, and a store sweep of everything under `chat/{tripId}/` (S3 prefix listing + one wildcard
   CDN invalidation; the local-mode object map in local mode).
2. **Registrations** — every row in the `tripId` partition, including rows the read path filters out.
3. **Todos + person_data** — todo rows first (their dataIds key the per-person `TodoStatus` rows), then a
   `person_data` sweep for those dataIds plus `room{tripId}`: candidates (roster + registrants + privilege
   holders, captured before their rows went) via the cached read path, then one raw table scan for anyone
   else — room rows are created lazily by mere page READS, so no roster predicts who has one.
4. **Trip-scoped privilege rows** (`tripMgr{id}`, `tripView{id}`, `tripFinAdmin{id}`, `tripFinView{id}`,
   `chatMgr{id}`) — deleted outright via `PrivilegesDAO.deletePrivilege` (the tripId is fused into their
   partition key; a deleted trip would strand them forever).
5. **Trip events** — before the trip row: `trip_events` rows carry NO tripId, so `Trip.getTripEventIds()`
   is the only handle on them.
6. **Badge images** — `BadgePhotoCommands.deleteAllForTrip`: every key on `Trip.badgeImages` plus a store
   sweep of `badgeImages/{tripId}/` (superseded versions, never-saved uploads).
7. **The trip row** — `TripDAO.deleteTrip`: the item, its point-cache entry, and both `TripIndex` sorted
   sets (`idx:trips`, `idx:person_trips` — the previously dead `removed` branch of `TripIndex.update`).

Audited as `AuditAction.TRIP_DELETE` (success with a per-table tally; refusals and failures as FAILURE).
The `audit` rows targeting the trip are the one reference that stays — append-only by IAM and by design,
they are the permanent record of the deletion itself.

## Known edges

- A concurrent `TripEditDrafts` draft held by another admin can re-create the trip row if they press Save
  after the delete (drafts are heap-only working copies). Accepted: rare, visible, and re-deletable.
- `chatContentSummary` counts raw message rows (tombstones and system notices included) up to a cap of
  1000, shown as `1000+`.
- The payment blocker check is a `payments` table scan (`getAllPayments` — the table is uncached and has no
  trip index). Blockers are memoized per request, and the page only computes them for users who can delete.

## Tests

- `TripDeleteCommandsTest` — authorization matrix, every blocker, the cascade, failure/re-run.
- `TripChatPurgeTest` — the chat cascade incl. invites and guest reverse rows.
- `TripDeleteDaoTest` — the raw-table (production) enumeration shapes the in-memory store cannot exercise.
- `BadgePhotoCommandsTest` — listed + stray badge sweeps, local and remote.
- `TripDeletePwIT` (webtest) — the page wiring: soft button + explanation, dialog, typed challenge, gone.
