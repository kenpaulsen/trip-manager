# One-time repair: flatten the EL in trip events' private notes

**Goal:** replace each expression stored in a private note (`TripEvent.privNotes`) with the text it used to show,
so the itinerary pages can stop evaluating notes as EL (medjugorje#116) without changing what any old trip shows.

## Why

Until 2026-11 four pages (`trip/itinerary.xhtml`, `itinerary-print.xhtml`, `itinerary-badge.xhtml`,
`admin/tripEvents.xhtml`) wrote every private note through `tripUtil.evalEL`, so any `#{...}` in a note ran on the
server with the full Faces EL context: static calls on `java.lang` included. A note is written by a trip manager in
the itinerary's note dialog, and by any trip member for their own note over `PUT /api/trips/{tripId}/events/{eventId}/note`,
so that was code execution for any trip member. The pages now write the note as stored, cleaned by the note
allowlist (`ReminderHtml.cleanNote`, on save and on every read), and `evalEL` is gone.

Managers relied on the evaluation for one thing: each traveler's room, pasted into a LODGING event's notes as
`#{reg.getRoomPDV(theTrip.id, person.id).content}`. A read-only scan of production on 2026-11-16 found that
expression and no other: 640 notes on 17 events of 16 trips, every one of them ended (the last on 2026-08-22). No
`${...}`, and nothing in an event's title or shared notes.

## What the repair does

`org.paulsens.trip.migrate.NoteElFlattenCli`, run by the private repo's `scripts/flatten-note-el.sh`:

- **plan** (reads only) scans the whole `trip_events` table, finds each trip's events through the trips' event
  lists, and replaces every room expression with the label `RegistrationCommands.getRoomPDV` answers for that
  traveler on that trip (an ACTIVE reservation's room first, then the legacy `person_data` room, else empty),
  HTML-escaped. The expression is RECOGNIZED by a pattern and answered by Java; nothing stored is evaluated. Any
  other expression, an event no trip lists, or an expression outside the private notes is a **blocker**: that event
  is reported and left untouched. `plan.json` holds each row as read and as it will be written; `plan.txt` summarizes
  per trip and lists the notes the display cleaner (`ReminderHtml.cleanNote`) will lose formatting from, for the
  owner to look at before the pages change. The first production plan listed 91, all old editor markup the cleaner
  then learned to keep, rebuilt: the named color `red` (also as `<font color>`), `<strike>`, `font-size: 0.8em`,
  `font-weight: bold`, and 43 relative links to the site's own `deltaklm.jsf` page. What remained was one stray
  `<b4>` tag.
- **apply --yes** writes exactly `plan.json`: each row is written only if it still holds the text the plan read
  (a conditional put on `content`), so an event someone saved in between is `REFUSED`, never overwritten. Each row
  as read is also saved as `before-<eventId>.json`; `applied.json` lists what was written. Then it verifies.
- **verify** re-reads the table: every planned row holds its new text, and no private note anywhere still holds
  `#{` or `${`.
- **rollback --yes** puts back each row `applied.json` names, if it still holds what apply wrote.

Exit status: 0 done, 1 usage or failure, 2 blockers, refused writes or failed checks.

## Run it

Before deploying the code that stops evaluating notes. Until that deploy the old pages show a flattened note exactly
as they showed the expression, so the repair is invisible; after it, an unflattened note would show the raw
expression.

```sh
cd trip && mvn install          # the script runs the built classes in trip/target/ROOT
cd ../medjugorje
./scripts/flatten-note-el.sh plan   --out ~/note-el-flatten     # read plan.txt: expect 640 / 17 / 16, 0 blockers
./scripts/flatten-note-el.sh apply  --out ~/note-el-flatten --apply
```

The run's own cache is off (every read and write goes straight to DynamoDB). After an apply or a rollback the
script invalidates the running app's `trip_event` and `trip` caches through `trip/scripts/lib/cache-invalidate.sh`
when `TRIP_APP_URL` and `TRIP_ADMIN_EMAIL` are set; otherwise it says to press **Clear caches** on the admin
Settings page, and the itineraries keep showing the old notes until that is done. If an apply or a rollback exits 1
(an error part way), the script does not invalidate; if `applied.json` lists rows, press **Clear caches**. The `cdk-deploy` profile signs in
through SSO, so the script (through the shared `scripts/java-cli.sh`) hands the Java process the profile's
exported credentials; run `aws sso login --profile cdk-deploy` first if they have expired.

## Verify

```sh
./scripts/flatten-note-el.sh verify --out ~/note-el-flatten
```

Then open two old itineraries (as a site admin, or "View As" a traveler) and check the room shows as before. After
the code deploys, run `verify` again: a note saved between the apply and the deploy could only have been typed by
hand.

## Undo

```sh
./scripts/flatten-note-el.sh rollback --out ~/note-el-flatten --apply
```

Only while the old pages are deployed: once notes are no longer evaluated, a restored expression shows as text. The
`before-*.json` files, point-in-time recovery and the monthly AWS Backup snapshot are the fallbacks.
