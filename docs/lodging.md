# Lodging: accommodations, room types, rooms, offers, reservations

Landed 2026-09-06. Read this before touching lodging pricing, lodging privileges, the room-assignment
workspace, the rooms report, or the itinerary's lodging rows.

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

Every row carries a top-level `version` and is written with the conditional-put recipe (`attribute_not_exists`
on create, `version = expected` on edit); a lost race surfaces as "saved by someone else, reload".

### Tables and caches (`dynamo/LodgingDAO`)

| Table | Keys | Cache |
|-------|------|-------|
| `lodging_accommodations` | PK `id` | `PartitionScanCache` (whole small table; no delete, only `retired`) |
| `lodging_offers` | PK `tripId`, SK `id` | `PartitionCache` per trip |
| `lodging_reservations` | PK `tripId`, SK `id` | `PartitionCache` per trip |

Rooms and room types are INLINE on the accommodation row (one optimistic put edits the inventory
atomically, no dangling room -> type references). The DAO refuses a row over 350 000 serialized bytes and
the commands cap an accommodation at 500 rooms. Concurrent editors of one hotel collide on the version,
which is accepted; per-room history would need a rooms table later (room ids are stable UUIDs, so that is
a data move, not a redesign). Facade: `DAO.saveAccommodation/getAccommodation(s)/saveReservationOffer/
getReservationOffer(s)/deleteReservationOffer/saveReservation/getReservation(s)/deleteLodgingForTrip`,
`CacheScope.LODGING`. `InMemoryPersistence.TABLES` registers all three (local mode and the unit suite).

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

## Privileges

| Base | Scope | Grants |
|------|-------|--------|
| `lodgingAdmin` | an ORG (`ORG_SCOPED_BASES`, on the org hub allow-list) | manage offers and reservations for that org's trips; open the site-level lodging admin page; create accommodations |
| `lodgingAdmin` | GLOBAL (`GLOBAL_BASES`, the `contentAdmin` dual pattern) | everything above for every org, plus edit every accommodation |
| `accommodationAdmin` | one ACCOMMODATION (`ACCOMMODATION_SCOPED_BASES`, scope = the hotel's UUID) | edit that hotel: details, room types, rooms, floor maps, photos, managers. Grants NOTHING on offers or reservations. |
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
over-capacity is a WARNING, never a refusal (owner requirement). One person may hold several reservations
on disjoint dates; each is its own card on the board and is placed on its own. Separate
reservations per distinct date range: the late arriver on a shared room is her own reservation on the same
room. Creating a reservation joins its occupants to the offer's `TripEvent`
(`TripCommands.updateEventParticipants`); reservations are admin-only for now (self-service is a later
API on the same commands). A reservation write is NOT atomic across the reservation row, the trip save and
the bills; a recompute heals.

## The itinerary

`LodgingCommands.itineraryRows(trip, frozenEventIds, personId)` builds `ItineraryRow`s: for a LODGING event
with the person's ACTIVE reservation(s) on an offer referencing it, `effectiveStart/End` are the earliest
start / latest end of those reservations, `roomLabel` joins the assigned rooms' labels, `nights` and
`reservationNotes` come along, and `overridden` is true only when the effective dates DIFFER from the
event's (the page shows "(dates from your reservation)" then). Otherwise the row carries the event's own
dates. `trip/itinerary.xhtml`, `itinerary-print.xhtml` and `itinerary-badge.xhtml` sort and render from
these rows.

## Legacy room strings

`RegistrationCommands.getRoomPDV(tripId, personId)` answers first from an ACTIVE reservation with a room
(a transient value, never saved), else the stored `person_data` string, else a transient empty value; reading
no longer creates a row. `saveRoom` is refused while a reservation sets the room. `trip/rooms.xhtml` and the
tripRooms report are DERIVED lists (`roomingList`); the free-text inputs are gone, and the way to change a
room is the trip's Lodging tab. Converting COMPLETED trips' legacy values into static notes and dropping the
fallback is a filed GitHub issue on the private repo.

## Pages (private repo)

- `admin/lodging.jsf` (site-level; gate `canOpenLodgingAdmin`): list mode (managed + all) and detail mode
  with tabs Details (edit, retire, managers), Room Types, Rooms (bulk add), Floor Maps (upload, the
  rectangle annotator in `trip-js/floorMap.js`, saved through `saveFloorRegions` JSON), Photos. Reached from
  the Admin menu and the org hub's Lodging card (`?orgId=` only drives the Done button and the contact's org).
- `trip/lodging.jsf` (gate `canManageTripLodging`; `?offer=` opens the board on a given option): under a
  "Rooms" heading, the Assignments workspace (`trip-js/roomAssign.js`: click a person card, click a room card
  or map region; the selection is keyed by RESERVATION so two stays of one person are two cards; the server
  decides, `assignRoom` minting a reservation from the option's default stay when needed; over-capacity
  opens a confirm with "Assign anyway"; the board's window defaults to the option's whole date range; a
  card shows age and reveals the registration answers on hover, and in the flow while selected),
  Reservations (new / edit / cancel-with-credit / recompute; dates are ONE range picker "arrival -
  departure" plus arrival and departure times, the labels are arrival/departure never check-in/out), Offers
  (the "Lodging option" dialog asks for the accommodation and room types FIRST and names the option after
  the room types while the name is blank; then the option's dates and the default stay as range pickers
  with times; it can create the LODGING event). The forms keep their range/time fields and their date-time
  fields in sync through setters, so pages, REST and tests may write either shape.
- NB inside a JSFT `initPage`, nothing may follow `jsft.redirect(...)` outside an `else`: the script keeps
  running and reads a view map that is gone (the refused-visitor 500 of 2026-09-06).

## Deferred (GitHub issues on the private repo)

- Hotel-manager inventory that reservations are checked against, including cross-trip double-booking
  detection: reservations are trip-partitioned and two trips at the same hotel are not cross-checked.
- Legacy `room{tripId}` values of completed trips -> static notes; drop the `getRoomPDV` fallback.

## Tests

`LodgingPricingTest` (the worked-example table), `LodgingBillerTest`, `LodgingCommandsTest` (gates,
discovery, contact, offers, reservations, board, cancel), `LodgingDAOTest`, `LodgingResourceTest` (the
webtests' fixture door, `api/LodgingResource`, manage-gated), `LodgingUploadCommandsTest`,
`RoomAssignmentTest`; browser tests `LodgingAdminPwIT`, `TripLodgingPwIT`, `RoomAssignOverCapacityPwIT`,
`ItineraryLodgingPwIT`, `RoomsPagePwIT`, `OrgScopedPrivsPwIT`.
