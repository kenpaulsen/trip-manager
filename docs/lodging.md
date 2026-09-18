# Lodging: accommodations, room types, rooms, offers, reservations

Landed 2026-09-06; several stays per person, the mid-stay room switch and the hotel's own availability
landed 2026-09-17. Read this before touching lodging pricing, lodging privileges, the room-assignment
workspace, the hotel's Availability tab, the rooms report, or the itinerary's lodging rows.

## Why it exists

A `TripEvent` has one start and one end for everyone. A late arriver (the case that started this: a
member landing four days after the group) saw the group's hotel dates on her itinerary, and the only
workaround was a second event for her alone, which split "who is staying here" in two. Room assignment
was a free-text string per person per trip (`person_data` key `room{tripId}`), the room inventory was a
hard-coded HTML table, and admins pasted EL into event notes to show rooms. Lodging is now a model.

## The model (`org.paulsens.trip.model`)

| Type | What it is |
|------|------------|
| `Accommodation` | A hotel or pansion: name, description, `Address` (now with `street2` and `country`), email, phone, website, `contactId`, gallery `photoIds`, inline `RoomType[]`, inline `Room[]`, `FloorMap[]`, `orgIds` (the organizations USING it), `createdBy`, `created`, `version`, `retired`. |
| `RoomType` | Name, description, `photoIds`, `minPeople` (>= 1), `maxPeople` (>= min, default 2). `getDisplayLabel()` is `"Double (1-2)"`. |
| `Room` | `roomTypeId`, `roomNumber`, `floor`, public `notes`, `adminNotes`, optional `MapRegion` (`kind` = `"rect"`, `x y w h` in PERCENT of the floor image; the `kind` field keeps polygons additive). |
| `FloorMap` | `floor` -> `mediaId` of the plan image. |
| `ReservationOffer` ("lodging option" in every label; "offer" only in code) | Per trip: `type` (`LODGING`), name, `accommodationId`, `roomTypeIds` (one or MORE room types, which often share a price; the legacy `roomTypeId` JSON property folds into the list on read), `tripEventId`, `PricingModel` (`PER_ROOM` / `PER_PERSON`), `nightlyPriceCents`, `nightlyPriceOverrides` (ISO date -> cents), `singleSupplementCents`, `minNights`, `validFrom/Until` (the option's DATE RANGE: the dates it covers; the default stay and every reservation must lie within it), `defaultStart/End`, `policyHtml`, `cancelFeeFixedCents`, `cancelFeeBps`, `disabled`, `version`. |
| `Reservation` | Per trip: `offerId`, `accommodationId`, `occupants` (`Person.Id[]`), minute-granular `start`/`end`, optional `roomId`, `status` (`ACTIVE`/`CANCELLED`), notes, `waiveSingleSupplement`, cancel fields (`cancelledAt/By`, `cancelFeeCents`, `credited`, `cancelReason`), `version`. |
| `RoomBlock` | Per ACCOMMODATION, never per trip: `roomIds` (one block can take a whole floor out at once), calendar-date `start`/`end`, free-text `reason`, `createdBy`, `created`, `version`. Nights on which rooms are NOT available to us for a reason that has nothing to do with our reservations: another group holds them, maintenance, the owner's family. `covers(roomId, night)`, `holds(roomId)`, `overlapsNights(from, to)` and `getNights()` all read `[start, end)`, so a block Sep 21 to Sep 26 holds five nights and the room is free on the morning of the 26th. The reason is free text on purpose: "another group", "boiler", "the owner's family" are all things a hotel says, and an enum would only be a list to keep extending. |

Every row carries a top-level `version` and is written with the conditional-put recipe (`attribute_not_exists`
on create, `version = expected` on edit); a lost race surfaces as "saved by someone else, reload".

### Tables and caches (`dynamo/LodgingDAO`)

| Table | Keys | Cache |
|-------|------|-------|
| `lodging_accommodations` | PK `id` | `PartitionScanCache` (whole small table; no delete, only `retired`) |
| `lodging_offers` | PK `tripId`, SK `id` | `PartitionCache` per trip |
| `lodging_reservations` | PK `tripId`, SK `id` (+ GSI `by-accommodation`: PK `accommodationId`, SK `stayEnd`, projection ALL) | `PartitionCache` per trip; the GSI read is UNCACHED |
| `lodging_blocks` | PK `accommodationId`, SK `id` | `PartitionCache` per hotel |

Rooms and room types are INLINE on the accommodation row (one optimistic put edits the inventory
atomically, no dangling room -> type references). The DAO refuses a row over 350 000 serialized bytes and
the commands cap an accommodation at 500 rooms. Concurrent editors of one hotel collide on the version,
which is accepted; per-room history would need a rooms table later (room ids are stable UUIDs, so that is
a data move, not a redesign). Facade: `DAO.saveAccommodation/getAccommodation(s)/saveReservationOffer/
getReservationOffer(s)/deleteReservationOffer/saveReservation/getReservation(s)/getReservationsAt/
saveRoomBlock/getRoomBlock(s)/deleteRoomBlock/deleteLodgingForTrip`, `CacheScope.LODGING` (which clears the
blocks namespace too). `InMemoryPersistence.TABLES` registers all four (local mode and the unit suite).

**Blocks got their own table rather than a list on the accommodation row.** That row already carries the
whole inventory under ONE optimistic version, so a hotel manager blocking a room for next week would
collide with an admin renaming a room, and one of them would be told to reload for a change that touched
nothing they were editing. A block is also the one lodging row a hotel's own staff write most often, and it
is keyed by the thing it belongs to. The cost is a second read on the board and in the dialogs
(`Cached.NO`, because a manager may have blocked a room a moment ago), which is one partition query.

That partition is also where the NEXT hotel-management row goes. The owner's stated intent for the
Availability tab is that it grows into the hotel's own view of its business, and a per-hotel partition takes
rates, allotments or a maintenance log beside the blocks without another table and without widening the
accommodation row. `HotelCommands` is the matching seam on the bean side: it answers "what does my hotel
look like", which is a different question from `LodgingCommands`' "where do I put this person", and it is
the only place a read deliberately spans the organization boundary.

**The `by-accommodation` GSI is how a HOTEL question gets answered at all.** Reservations are partitioned by
TRIP, which is right for every other read (the rooms page, the itinerary, recompute, the delete cascade),
and that makes "every trip's stays at this hotel" exactly the question the primary key cannot serve. The
sort key is the stay's end truncated to minutes (`yyyy-MM-ddTHH:mm`), so it compares lexically and a window
is a bounded `BETWEEN`. `LodgingDAO.saveReservation` promotes `accommodationId` and `stayEnd` out of the
`content` JSON **both or neither**, which keeps the index SPARSE: a reservation with no hotel or no end date
is simply not in it, and half a key pair would index a stay the query can never match. `getReservationsAt`
is deliberately uncached -- it is an admin question asked on demand, and a per-hotel cache would have to be
invalidated by every trip's writes.

`InMemoryPersistence` grew real secondary-index support for this, sparseness included: a fake that answered
nothing would make the whole cross-trip view untestable, and local mode would quietly claim every hotel was
empty. Its `resolvePk` now READS the partition placeholder out of the key-condition expression and throws
when it cannot find one, rather than taking the first placeholder it sees -- that guess was only ever right
because every earlier query carried exactly one, and this index's query carries three
(`accommodationId = :a AND stayEnd BETWEEN :lo AND :hi`).

The index needs a **one-time backfill per environment**, and the index must be created BEFORE the code
deploys (querying an index that does not exist is a hard `ValidationException`). Until the backfill runs the
hotel views under-report rather than lie: a missing row reads as "nobody else has this room". Steps, script
and verification query:
[migrations/reservations-by-accommodation-gsi.md](migrations/reservations-by-accommodation-gsi.md).

## Accommodations are GLOBAL (the one exception to org tenancy)

An accommodation is NOT owned by an organization (owner decision, 2026-09-06): one hotel row is used by
every org whose trips stay there, like a `Person` spans orgs. `Accommodation.orgIds` records which orgs
USE it; an org is appended the first time one of its trips gets an offer at that hotel (`saveOffer` ->
`recordOrgUse`). Nothing else in the app may copy this shape; `docs/org-admin.md` "Organizations: the
tenancy boundary" still governs trips, offers, reservations, money and everything else.

Discovery (so two orgs do not create the same hotel twice): `Accommodation.matchesDiscovery` compares
normalized name (case and whitespace), email (lower-cased), phone (digits only), website (host), and
street + city + zip. The create dialog runs `findDuplicates` first and warns with the matches; "Create
anyway" (`force`) overrides, because an admin may knowingly register a second property with the same phone.

Retirement (`retireAccommodation`) hides a hotel from the pickers; existing offers keep working.

## The hotel's inventory: blocks, the calendar, and what a hotel may see

Everything above is a TRIP's view of a hotel. `HotelCommands` (`#{hotel}`) is the HOTEL's view of itself,
and it exists because the question is genuinely different: `#{lodging}` answers "where do I put this person
on my trip", `#{hotel}` answers "what does my hotel look like in September", which crosses trips and
organizations. The night arithmetic both need is in `RoomAvailability`, a package-private static helper that
touches no DAO, so either bean may call it inside a render loop without owning the other.

**Blocks are a refusal, not a warning.** `RoomAvailability.blockProblem` names the block ("Room 401 is not
available Sep 23 – Sep 25: another group. Change the block on the hotel's Availability tab first.") and
`assignRoom`, `place`, `createReservations`, `updateReservation` and `splitStay` all check it ahead of the
capacity warning. This is deliberately unlike over-capacity, which is the warning `force` overrides:
capacity is OUR call to overrule -- three people in a double is a decision an admin may make -- but a room
another group is sleeping in is not ours to hand out, so there is no "Block anyway". A blocked empty room
gets the CSS state `rs-blocked` rather than `rs-empty` (it is not ours to fill); a room both blocked and
slept in reads `rs-over`, because somebody has to move and that is the same urgency.

**The availability calendar** is a `p:schedule` month view on the hotel's Availability tab, built from
`HotelCommands.calendar` / `schedule`. Each day that has anybody or anything on it gets one all-day summary
pill carrying "23/28 rooms · 33 people" (`DayCell.getSummary`) in a load band (`cal-free` / `cal-some` /
`cal-most` / `cal-full`, plus `cal-conflict`); each block gets its own pill spanning its nights. Every pill
carries the numbers, so the color never stands alone. Clicking a day opens `dayDetail`, which breaks that day
down room by room. It reads across EVERY trip at the hotel through the `by-accommodation` index, and nothing
is stored, so the page may build it per render.

**Both of the schedule's zones are pinned to UTC (`timeZone="UTC" clientTimeZone="UTC"`), and neither may be
dropped.** A night here is a calendar DATE, not an instant: Oct 2 is Oct 2 in Zagreb and in California.
PrimeFaces encodes every event as `ISO_OFFSET_DATE_TIME` after `atZone(timeZone)`, and FullCalendar re-zones
what it receives into `clientTimeZone`. Left unset, `timeZone` follows the CONTAINER (UTC in production)
while the client follows the VIEWER, so every pill drew a day early for anyone west of UTC: a block stored
Oct 2 to Oct 11 rendered Oct 1 to Oct 9 (reported 2026-09-18). `timeZone` also decodes the `dateSelect`
payload, so pinning it keeps a clicked day and a drawn day the same day. No webtest could see this before
`HotelCalendarPwIT.theDatesHoldForAViewerWestOfUtc`, because CI runs the server and the browser in the same
zone and the two conversions cancel out; that test moves the browser to `America/Los_Angeles` on purpose.

**A block's dates are nights, exactly like a stay's.** The first date is the first night the rooms are gone;
the second is the morning they are free again. A block of Oct 2 to Oct 11 holds the nights of the 2nd through
the 10th: nobody may sleep there on the night of the 2nd, a guest who slept the night of the 1st may still
check out on the morning of the 2nd, and the rooms take bookings again for the night of the 11th. This is why
a block needs no start or end TIME -- it is measured in nights, and the hotel's own check-in and check-out
hours decide the clock. The Availability tab, the block dialog and the board's quick-block all say so on the
page, because "Oct 2 to Oct 11 (9 nights)" reads like a date range until you know the convention.

**It never shows a guest's name.** This is the one read in the app that crosses the tenancy boundary, and it
is allowed only because the accommodation itself is global. What crosses is counts and dates. A trip is named
only when the caller could have opened that trip anyway (`canManageTripLodging`); otherwise it reads "another
organization's trip", and on the trip board a room holding somebody else's guests shows the anonymous chip
"another trip · 2 people". Those people still COUNT toward the room's occupancy -- a bed another group's
guest is in is not free because their reservation lives in a different partition -- which is why
`occupancyOver` and `cellFor` fold the index's rows in beside the trip's own. `roomInUse` (the guard on
deleting a room) now asks the index too, instead of walking every org that uses the hotel and reading each
of its last 200 trips.

Gates: `canEdit` is `canEditAccommodation` (the people who may edit the rooms may say which are available);
`canRead` is that plus `canOpenLodgingAdmin`, so the lodging admins who book into the hotel can see how full
it is. A block with no rooms or no nights is refused, because an empty one would look applied and hold
nothing; past blocks stay in the table, faded, so a mistake remains findable.

## Privileges

| Base | Scope | Grants |
|------|-------|--------|
| `lodgingAdmin` | an ORG (`ORG_SCOPED_BASES`, on the org hub allow-list) | manage offers and reservations for that org's trips; open the site-level lodging admin page; create accommodations |
| `lodgingAdmin` | GLOBAL (`GLOBAL_BASES`, the `contentAdmin` dual pattern) | everything above for every org, plus edit every accommodation |
| `accommodationAdmin` | one ACCOMMODATION (`ACCOMMODATION_SCOPED_BASES`, scope = the hotel's UUID) | edit that hotel: details, room types, rooms, floor maps, photos, managers, AND its availability -- creating, editing and removing `RoomBlock`s from the Availability tab or from the trip board's room dialog (`HotelCommands.canEdit` is exactly this gate). Grants NOTHING on offers or reservations. |
| `lodgingManager` | one TRIP (`TRIP_SCOPED_BASES`, granted on the Trip Managers roster) | room the trip's people: the Assignments board, `assignRoom`, `unassignRoom`, the room dialog, and `splitStay` -- switching somebody's room part-way through a stay changes where they sleep, not what they pay. NOT the Offers or Reservations tabs, no prices, no bills, no other trip, and NOT `place` or "+ Another stay" (minting a reservation chooses the option that prices the stay). This is what a HOTEL's own staff get; added 2026-09-07, reversing "reservations are admin-only". |
| `tripMgr` | a trip | manage that trip's offers and reservations (the trip's Lodging tab) |
| site admin | | everything |

Editing an accommodation is privilege-based, never a field on the row. On create, `accommodationAdmin@acc`
is granted to the creator and to the contact. The Managers roster on the Details tab is that privilege's
holder list (`managers` / `addManagerById` / `removeManagerById`). Gates live in `LodgingCommands`:
`canEditAccommodation`, `canCreateAccommodation`, `canManageTripLodging`, `canOpenLodgingAdmin`.

## The hotel contact (the fourth sanctioned Person-creation path)

The contact of an accommodation is a real `Person`, found by email or AUTO-CREATED
(`LodgingCommands.findOrCreateContact`). Rules:

- valid email and a name are required; an existing person is joined, never modified;
- a created person gets first/last/email only: NO credentials, NO mail, no roster, family or registration
  touch; their first sign-in is the existing email-code activation flow;
- they join the creating admin's org context (`?orgId=` on the admin page, else the caller's first org;
  a caller with no org is refused) through `OrgCommands.addLodgingContact`, and are added to every further
  org the first time that org uses the hotel;
- they get `accommodationAdmin` for the hotel; the audit line is `PERSON ... CREATED as lodging contact`.

The other three creation paths are sign-up, a family manager's add-member flow, and the REST people API
(`PersonCommands.saveProfile` javadoc).

## Media

Photos and floor plans are `MediaItem` rows with `orgId` null (site-level) in ONE slot per hotel,
`lodging-{accId}`, bytes under `lodging/{accId}/...` (S3 when enabled, else a bounded in-memory store served
by `web/LodgingPhotoServlet` at `/lodging-photos/*`). The accommodation row references media ids
(`photoIds` gallery order, `RoomType.photoIds`, `FloorMap.mediaId`), so the media manager can hide or
delete a row and the hotel prunes dangling ids on its next save. `admin/media.xhtml` hides lodging slots
with the chat photos behind its "Include chat and lodging photos" toggle; `MediaCommands.getCurated`
excludes them. Uploads go through the shared crop dialog with `LodgingUploadCommands` (`lodgingUpload`):
kinds `accPhoto`, `roomTypePhoto`, `floorMap` (floor plans are stored whole, 2000 px long edge).

## Nights, pricing, bills (`pay/LodgingPricing`, `pay/LodgingBiller`)

- A NIGHT is a calendar date `d` with `start.toLocalDate() <= d < end.toLocalDate()`; times are informational.
  Same date = 0 nights; end before start = 0. (Deliberately not the 4 a.m. fudge of `getLodgingDays`.)
- `PER_PERSON`: each occupant pays `nightlyPriceCents(date)` per night. The single supplement is added on a
  night the person is ALONE: the only occupant of their assigned room that night, or a one-person
  reservation not placed yet (its own occupants are all that is known; placing them with someone recomputes
  it away). `waiveSingleSupplement` removes it either way. Room type is irrelevant.
- `PER_ROOM`: the room price for a night is split with `MoneyMath.splitEvenly` among that night's occupants
  of the room, sorted by person id (remainder cents to the lowest ids, so a recompute is deterministic). An
  unassigned reservation splits among its own occupants. A `PER_ROOM` offer ignores the supplement (the sole
  occupant already pays the whole room). Invariant: a room's occupants sum to the room price per night.
- Descriptions are deterministic, e.g. `Lodging: Double room — Pansion, Double, Room 114: 4 nights Sep 21–Sep 25,
  2026, $55.00/night per person`.
- A bill is a `Transaction` of type `Bill`, written NEGATIVE (balance = sum of amounts, negative "owes"),
  category "Lodging", id `{reservationId}-lodging-{personId}`, bound TRANSACTION -> TRIP and TRANSACTION ->
  TRIP_EVENT. Same amount and note = unchanged; zero = no row (an existing one is soft-deleted). A $0 offer
  writes nothing. There is no Invoice model.
- Bills recompute AUTOMATICALLY (audited) on every reservation create, date change, room change, waiver
  change and cancel, for the affected room(s) or the reservation alone when unassigned, AND on every edit of
  the option itself (price, supplement, per-night overrides: all of its active reservations), while
  `KnownSettings.LODGING_AUTO_RECOMPUTE` (`lodging.bills.autoRecompute`, default on, org-overridable) is on;
  off, the page growls "press Recompute". The explicit Recompute button stays for two cases only: the
  setting is off, or a bill was deleted by hand / the ledger and the reservations drifted (a repair, not a
  routine step). Auto-recompute can RAISE a paid occupant's bill after a roommate cancels; that is audited,
  not notified.
- Because a soft-deleted transaction is invisible through the DAO, a lodging bill an admin deleted by hand
  RETURNS on the next recompute.

## Cancelling

`cancelReservationWithFee(tripId, resId, credit, feeDollars, reason)`: the reservation becomes `CANCELLED`
(originals bills stay), the occupants leave the offer's event only if no other ACTIVE reservation of theirs
references it, and co-occupants' bills recompute. The fee defaults from the offer (`cancelFeeFixedCents` +
`cancelFeeBps` of the billed total, half-up, capped at the total) and is admin-editable in the dialog.
With credit on, ONE positive Bill per occupant, id `{reservationId}-cancel-{personId}`, for
(billed - fee share), description `Lodging cancellation credit: ...`; zero credits write nothing;
idempotent.

## Reservations and the event

Every reservation requires an option (a $0 option is allowed). Occupants must be on the trip roster; nights
must reach the option's `minNights`; a disabled option refuses; a stay outside the option's date range
REFUSES with the range in the message (widen the option first; the default stay is held to the same rule
when the option is saved); a room-type mismatch (a room whose type the option does not sell) or
over-capacity is a WARNING, never a refusal (owner requirement); a room the HOTEL has blocked is a REFUSAL
(see "The hotel's inventory"). One person may hold several reservations that share no NIGHT (the next
section); each is its own card on the board and is placed on its own. Separate
reservations per distinct date range: the late arriver on a shared room is her own reservation on the same
room. Creating a reservation joins its occupants to the offer's `TripEvent`
(`TripCommands.updateEventParticipants`); reservations are admin-only for now (self-service is a later
API on the same commands). A reservation write is NOT atomic across the reservation row, the trip save and
the bills; a recompute heals.

## Several stays per person on one trip

The model always allowed it -- reservations are separate rows and nothing tied a person to one of them --
but the board could only ever create somebody's FIRST stay, so "she goes home for two nights and comes
back", and "he moves out of 105 on the 26th", had no gesture. Since 2026-09-17 they do.

**The guard is a NIGHT, not an instant.** `stayProblem` ends in `overlappingStay`, which refuses a stay when
one of its people already holds an ACTIVE stay on the trip that shares a night with it, and the comparison
is on CALENDAR DATES: a stay ending on the 26th and one starting on the 26th share a date but no night,
which is exactly the room-switch shape. Two stays at ONCE are refused because they would bill the same night
twice -- pricing counts a person once per night in a room and once per reservation in the ledger -- and the
refusal names what is in the way ("already has a stay Sep 21 – Oct 1 (Pansion, room 105); two stays cannot
share a night"), because a refusal an admin cannot act on is just a "no".

**Two gestures on the board**, deliberately different, because they need different things:

- **"+ Another stay"** on the person's own card arms that person with NO reservation id
  (`roomAssign.js: armNewStay`), so clicking a room takes the existing placement path and the existing
  "Place in a room" dialog handles it -- there is no second flow to keep in step. `placementFormFor` marks
  the form `anotherStay`, which leaves the DATE RANGE BLANK rather than defaulting it to the option's: the
  option's default dates are the ones the person is already here for, so offering them back would only ever
  come straight back as an overlap. The dialog says what they already hold, so the new dates are picked
  knowingly. The link is gated on `canManageTripLodging`, since a new reservation picks the option that
  prices it.
- **"Switch room mid-stay"** opens the split dialog (below). It is on the card and on the Reservations
  table, because both name a single stay.

**The board is per (stay, person), not per reservation.** One card per OCCUPANT per stay, so a shared
reservation is two cards (it used to be one, named after whoever sorted first); one room-cell chip per
(stay, person), so each ✕ unassigns the stay it names and one person can legitimately appear twice on one
room. `stampStays` numbers a person's cards "stay 1 of 2", "stay 2 of 2" in DATE order across the whole
board, and the lists sort to match as far as their own grouping allows: Unassigned by name then start,
Assigned by room, then name, then start. Reservation ids are UUIDs, so unsorted is random, and two cards for
one name arriving in random order read as a duplicate rather than as two different weeks.

**The split** (`LodgingCommands.splitStay`, form `LodgingViews.SplitForm`) turns one reservation into two at
a chosen night: `[start, date)` in the old room, `[date, end)` in the new one, carrying the same option,
occupants, notes and waiver. A night belongs to the date it starts on, so nothing is lost or counted twice
and the two halves can never overlap each other. What it does NOT change is the point: the option, the
occupants and the total nights are untouched, and the bills recompute to the same total. That is why it is
gated on `canAssignRooms` rather than `canManageTripLodging` -- the hotel's own `lodgingManager` may move
somebody between rooms, which changes where they sleep, not what they pay.

Refusals: a changeover date on either edge of the stay (both halves need at least one night, which is what
`SplitForm.getMinDate`/`getMaxDate` bound the picker to), the room they are already in, a room that is not
at the accommodation, and a room the hotel has BLOCKED over the second half. Over-capacity in the new room
is the usual warning, overridden by "Move anyway" (`force`). Leaving the new room blank is allowed: the
second half simply waits for a room. Two saves are not atomic, so if the second reservation fails to save
the first is restored to its original end date -- half a split is a night nobody is booked for.

## The itinerary

`LodgingCommands.itineraryRows(trip, frozenEventIds, personId)` builds `ItineraryRow`s, delegating to
`LodgingItinerary.rowsFor`. A LODGING event on which the person holds ACTIVE reservations (on offers
referencing it) contributes **one row per STAY, in date order**, not one row for the event: each row carries
that stay's own `effectiveStart/End`, `roomLabel`, `nights` and `reservationNotes`, plus `stayIndex` /
`stayCount`, and `overridden` is true only when that stay's dates DIFFER from the event's. Every other event
contributes its single row with its own dates. `trip/itinerary.xhtml`, `itinerary-print.xhtml` and
`itinerary-badge.xhtml` sort and render from these rows; the stay reads "Room 106 (Family), 10 nights"
(`ItineraryRow.getStayTail`), on its own line beside the person's note.

**It used to fold several reservations into ONE row** -- earliest start, latest end, nights summed -- and
that was wrong in both directions. A leave-and-return (Sep 21 to Oct 1, away, Oct 3 to Oct 4) read as
thirteen nights with the gap hidden, and the whole block sorted at the first date, above the flight that
took them home in between. One row per stay fixes both, because each row is then ordered by the date it
actually shows.

What the EVENT itself says must still appear exactly ONCE, so `ItineraryRow.firstStay` is true on a plain
row and on the first stay's row only. `trip/itinerary.xhtml` and `itinerary-print.xhtml` gate the event's own
notes and the per-person note editor on it -- that editor rides the EVENT id, not the reservation, so two
rows would offer two editors for one value. `itinerary-badge.xhtml` gates the same way; its room line is
per stay, so a badge for two stays prints both rooms and the event's notes once.

**Room numbers can be withheld.** `Trip.roomNumbersShown` (null = shown, the `chatEnabled` pattern) is the
Assignments tab's "Show room numbers on itineraries" switch (`LodgingCommands.setRoomNumbersShown`, gated on
`canAssignRooms`). Off while assignments are still being planned, `rowFor` leaves `roomLabel` null on every
itinerary page and the stay reads "Family, 10 nights"; the board and the Reservations tab are unaffected.

**A new lodging event starts with the hotel's address in its notes** (`LodgingCommands.lodgingEventNotes`,
HTML-escaped), so the itinerary shows where the stay is under its name. The title stays the accommodation's
name alone: it is the duplicate-detection key and what the offer dialog's event menu shows.

## Legacy room strings

`RegistrationCommands.getRoomPDV(tripId, personId)` answers first from an ACTIVE reservation with a room
(a transient value, never saved), else the stored `person_data` string, else a transient empty value; reading
no longer creates a row. `saveRoom` is refused while a reservation sets the room. `trip/rooms.xhtml` and the
tripRooms report are DERIVED lists (`roomingList`); the free-text inputs are gone, and the way to change a
room is the trip's Lodging tab. Converting COMPLETED trips' legacy values into static notes and dropping the
fallback is a filed GitHub issue on the private repo.

## Floors are free text, so a plan can land on the wrong one

`Accommodation.floors()` is the union of the floors its ROOMS name and the floors its PLANS name, so nothing
constrains the two to agree. Rename the rooms' floor (`Ground` to `0 - Ground`, say) and the plan is orphaned
on the old name: a floor with a picture and no rooms to map on it, beside a floor with rooms and no picture.
That is exactly the state reported on 2026-09-07, and every part of it is by design except that there was no
way out of it. Three guards, all cheap:

- The Floor Maps tab REFUSES a `?floor=` naming a floor the hotel does not have, falling back to the first
  one, so a stale link or bookmark can never create a floor by visiting it.
- `setFloorMap` warns when the target floor has no rooms: the plan is still stored (a plan may legitimately
  arrive before the rooms), but the admin is told it has nothing to map yet.
- `moveFloorMap(accId, from, to)` re-points a plan at another floor, keeping the image; `moveTargets` offers
  only floors that have no plan of their own, and `removeFloorMap` takes a plan off a floor entirely (the
  image stays in the media library, as removing a gallery photo does). A move CLEARS the map regions on both
  floors: boxes are percentages of a particular image, and after a move neither floor's boxes describe the
  image it now shows.

Free-text floor names also reach the floor chooser's links, so they are URL-encoded through
`tripUtil.encodeParam`: an unencoded `&` ENDS the parameter and a `+` arrives as a space, which would select
a floor nobody named.

## The itinerary is ordered by the date each row shows

`itineraryRows` sorts on `effectiveStart`, not on the event's own start. A reservation overrides the shared
LODGING event's dates for that person, so a late arriver's hotel row moves days down the trip while the
event stays put: ordering by the event listed her hotel above the flight that brought her to it, with both
rows showing the right dates (reported 2026-09-07). The sort is stable, so rows sharing a moment keep the
order the trip gave them, and undated rows sink to the bottom. The print and badge pages read the same
rows, so all three agree.

## Who opens the trip's Lodging tab

Two gates, deliberately different:

- `canManageTripLodging(tripId)` is the full tab: site admin, global `lodgingAdmin`, `lodgingAdmin` on the
  trip's org, or `tripMgr` on the trip. Offers, reservations, cancellations, recompute, every price.
- `canAssignRooms(tripId)` is that set PLUS a trip-scoped `lodgingManager`, and it is what the Assignments board,
  `roomBoard`, `roomDetail`, `assignRoom`, `unassignRoom` and `splitStay` answer to.

For a `lodgingManager` the page renders the Assignments tab alone, forces the tab index to 0 (a `?tab=` they
cannot open would index a tab that was never built), drops the "No reservation yet" column (its clicks
would all be refused, since placing an unreserved person picks the option that prices them), hides the
person card's "+ Another stay" link for the same reason, and hides its "Full registration" link (that page
would bounce them). "Switch room mid-stay" stays, because a split changes neither the option nor the total
nights. `cancelPreview` is gated too: what
was billed and what comes back is the trip's business, not the hotel's. The tab STRIP renders for them as
well, carrying Lodging alone, the way `registrationAdmin` carries Registrations alone.

An organization with an explicit `grantablePrivileges` allow-list will not offer the new role until
`lodgingManager` is added to that list; orgs with no list offer it already, and site admins always see it.

## Pages (private repo)

- `admin/lodging.jsf` (site-level; gate `canOpenLodgingAdmin`): list mode (managed + all) and detail mode
  with tabs Details (edit, retire, managers), Room Types, Rooms (bulk add), Floor Maps (upload, the
  rectangle annotator in `trip-js/floorMap.js`, saved through `saveFloorRegions` JSON, plus Move plan and
  Remove plan), Photos, and **Availability** (gate `hotel.canRead`): the `p:schedule` month calendar, a
  legend, and the "Rooms held for something else" table with its block dialog (rooms multi-select, a range
  picker reading "first night - free again", free-text reason). The Availability tab was added LAST, index
  5, so every existing `?tab=` link keeps pointing where it always did. `viewScope.calMonth` says which month it
  OPENS on (`?month=`, which is also how a test pins it); the data does not come from it. The model is a
  `LazyScheduleModel`: the schedule fetches its events in a request of its OWN carrying the range it is about
  to draw, and paging is a client-side move the server is never told about in a way it can trust, since the
  `viewChange` event hands over the VIEW's name rather than a date. Loading what the calendar asks for is
  therefore the only way a month the user pages to has anything in it. Three consequences worth knowing:
  there is no `viewChange` listener at all (updating the calendar from its own event re-fires it, and the
  page spent itself in a loop before that was caught); there is no `eventSelect` listener either, because a
  lazy model holds no events at DECODE time and the clicked pill cannot be resolved out of it -- the pills
  are `pointer-events: none` instead, so every click in a day is the day's, and a block is changed in the
  table below where it is named; and a block that changes refreshes the calendar with `PF('hotelCal').update()`,
  which REFETCHES, rather than by re-rendering the component. The one remaining listener, `dateSelect`, writes
  a SCALAR into the view (`calDay`), because a `SelectEvent` carries its payload as an object that a JSFT
  command script cannot read, and `initialDate` binds to `hotel.monthStart`, which answers a `LocalDate`: the
  renderer casts that attribute, and a String there throws mid-render, which arrives as a page that never
  finishes loading. Reached from
  the Admin menu and the org hub's Lodging card (`?orgId=` only drives the Done button and the contact's org).
- `trip/lodging.jsf` (gate `canManageTripLodging`; `?acc=` opens the board on a given hotel): under a
  "Rooms" heading, the Assignments workspace (`trip-js/roomAssign.js`: click a person card, click a room card
  or map region; the selection is keyed by RESERVATION so two stays of one person are two cards; the server
  decides, `assignRoom` minting a reservation from the option's default stay when needed; over-capacity
  opens a confirm with "Assign anyway"; the board's window defaults to the option's whole date range; a
  card shows age and reveals the registration answers on hover, and in the flow while selected; clicking a
  room with NOBODY selected opens it instead, `roomDetail` answering who is in it over the board's window,
  with each occupant's card open and both kinds of room note, the blocks over that window with a "Remove"
  link, an "Also in this room" list of other trips (count and dates, titled only when the caller could open
  that trip, else "another organization's trip"), and -- for whoever may edit the hotel --
  a one-line **quick block** for this room, because the room is already the subject; several rooms at once
  is what the hotel's own Availability tab is for. The card itself carries
  "+ Another stay" and "Switch room mid-stay"; the latter and the Reservations table's ⇄ button both open the
  **split dialog** through the `openSplit` remote command (changeover date bounded to inside the stay, new
  room menu, the refusal shown in place, and a "Move anyway" that appears only after a capacity warning). The
  board
  is scoped to an ACCOMMODATION, never to one lodging option: the toolbar names the hotel (a menu only when
  the trip has options at more than one), and everybody waiting for a room there is listed whichever option
  pays for them -- scoping the column to one option hid people while the room grid showed the whole hotel.
  Which option pays is asked when somebody with NO reservation is placed, in the "Place in a room" dialog
  (option, then the stay as a range picker plus arrival and departure times, defaulted from the option, with
  the capacity refusal shown in the dialog and a "Place anyway"); a person who already holds a reservation
  keeps the one-click path, since their option and dates already exist. Every write updates the WHOLE tab
  view, and the Assignments tab re-reads the option list on a wrapper that always renders: a child's
  `rendered=` cannot refresh the value that decides whether that child exists, which is why the first option
  a trip ever got needed a browser reload to appear. The board's own dialogs (reservation, cancel) and
  Reservations (new / edit / cancel-with-credit / recompute; dates are ONE range picker "arrival -
  departure" plus arrival and departure times, the labels are arrival/departure never check-in/out; a
  `p:datePicker` renders `span > input + button`, so NEVER put a width on the component -- that sizes the
  wrapper and drops the calendar button onto its own line: size the input through `.dateTime`/`.dateRange`
  and keep each label glued to its picker in a `.dateWhen`, the `.dateRow` recipe in `trip.css`, shared
  since 2026-09-17 with the trip editors' Trip Dates row and the trip-event dialog), Offers
  (the "Lodging option" dialog asks for the accommodation and room types FIRST and names the option after
  the room types while the name is blank; then the option's dates and the default stay as range pickers
  with times; it can create the LODGING event). The forms keep their range/time fields and their date-time
  fields in sync through setters, so pages, REST and tests may write either shape.
- NB inside a JSFT `initPage`, nothing may follow `jsft.redirect(...)` outside an `else`: the script keeps
  running and reads a view map that is gone (the refused-visitor 500 of 2026-09-06).

## Deferred (GitHub issues on the private repo)

- ~~Hotel-manager inventory that reservations are checked against, including cross-trip double-booking
  detection~~ -- **done 2026-09-17** (issue #38): `RoomBlock` is the inventory a placement is checked
  against, the `by-accommodation` GSI makes the cross-trip question answerable at all, and the board counts
  and shows other trips anonymously instead of pretending a shared room is empty.
- What remains of that issue: **per-organization allotments** (this org holds 12 of the 28 rooms for these
  nights, and its own admins may fill them without seeing the rest), and a **hotel-facing page spanning
  several hotels** -- today the Availability tab is one accommodation at a time, reached through the lodging
  admin page, which is the right door for a manager of one property and the wrong one for a small chain.
- Legacy `room{tripId}` values of completed trips -> static notes; drop the `getRoomPDV` fallback.

**Class split (2026-09-17, not a feature).** `LodgingCommands` reached the checkstyle 3000-line file limit,
so three pieces moved out: `LodgingItinerary` (the itinerary rows and the rooming list), `LodgingFloorMaps`
(floor-plan upload, move, remove and the saved regions) and `Countries` (the address autocomplete's fixed
list). The pages still bind to `#{lodging}`, which delegates -- the seam is for reading the code, not for the
EL -- so nothing on a page changed. `HotelCommands` is not part of that split: it is a new bean with a
different question (see "The hotel's inventory").

## Tests

`LodgingPricingTest` (the worked-example table), `LodgingBillerTest`, `LodgingCommandsTest` (gates,
discovery, contact, offers, reservations, board, the overlap guard, the split, cancel), `LodgingDAOTest`,
`LodgingResourceTest` (the webtests' fixture door, `api/LodgingResource`, manage-gated),
`LodgingUploadCommandsTest`, `LodgingViewsTest`, `RoomAssignmentTest`, plus the new
`HotelCommandsTest` (blocks, the calendar, and above all that the answer spans TRIPS while naming nobody),
`RoomAvailabilityTest` (the shared night arithmetic, away from any DAO), `RoomBlockTest` (`[start, end)`
like every other stay in the app) and `InMemoryIndexTest` (the fake's sparse secondary index, through the
one index that uses it: a fake answering nothing would make the cross-trip view untestable, and one
answering the WRONG partition would be worse); browser tests `LodgingAdminPwIT`, `TripLodgingPwIT` (which
covers "+ Another stay" and "stay 1 of 2"), `HotelCalendarPwIT` (the month's numbers and its block pill, a day opened room by room with no guest named,
the dates holding for a browser pinned west of UTC,
and the block table's edit and removal redrawing the calendar), `RoomBlockPwIT` (a blocked room refuses every way in, says who
has it, and frees at once when a manager lifts the block from the room dialog), `RoomAssignOverCapacityPwIT`,
`RoomBoardListsPwIT`, `ItineraryLodgingPwIT`, `RoomsPagePwIT`, `OrgScopedPrivsPwIT`.
