# Lodging: accommodations, room types, rooms, offers, reservations

Landed 2026-09-06; several stays per person, the mid-stay room switch and the hotel's own availability
landed 2026-09-17; the three roles (Lodging Admin, Hotel Operator, Trip Lodging Admin), price vs cost,
concessions and hotel rate plans 2026-10-25; the follow-up of 2026-10-26 made a rate plan the HOTEL's side only
(no participant price on a plan), made the Room Invoice REFUSE rather than assume a missing cost, and gave the
Offers tab its plan-first shape. Read this before touching lodging pricing, lodging privileges,
the room-assignment workspace, the hotel's Availability or Trips tab, the Room Invoice, or the itinerary's
lodging rows. Everywhere the UI says "Lodging option" the code says offer; "Hotel Operator" is the display
name of the stored base `accommodationAdmin`.

## Why it exists

A `TripEvent` has one start and one end for everyone. A late arriver (the case that started this: a
member landing four days after the group) saw the group's hotel dates on her itinerary, and the only
workaround was a second event for her alone, which split "who is staying here" in two. Room assignment
was a free-text string per person per trip (`person_data` key `room{tripId}`), the room inventory was a
hard-coded HTML table, and admins pasted EL into event notes to show rooms. Lodging is now a model.

## The model (`org.paulsens.trip.model`)

| Type | What it is |
|------|------------|
| `Accommodation` | A hotel or pansion: name, description, `Address` (now with `street2` and `country`), email, phone, website, `contactId`, gallery `photoIds`, inline `RoomType[]`, inline `Room[]`, `FloorMap[]`, `orgIds` (the organizations USING it), `tripIds` (the trips holding a lodging option here, written beside `orgIds` by `HotelUsage` on every offer save; the operator's Trips tab reads it), `createdBy`, `created`, `version`, `retired`. |
| `RoomType` | Name, description, `photoIds`, `minPeople` (>= 1), `maxPeople` (>= min, default 2). `getDisplayLabel()` is `"Double (1-2)"`. |
| `Room` | `roomTypeId`, `roomNumber`, `floor`, public `notes`, `adminNotes`, optional `MapRegion` (`kind` = `"rect"`, `x y w h` in PERCENT of the floor image; the `kind` field keeps polygons additive). |
| `FloorMap` | `floor` -> `mediaId` of the plan image. |
| `ReservationOffer` ("lodging option" in every label; "offer" only in code) | Per trip: `type` (`LODGING`), name, `accommodationId`, `roomTypeIds` (one or MORE room types, which often share a price; the legacy `roomTypeId` JSON property folds into the list on read), `tripEventId`, TWO money schedules -- `price` (a `RateSchedule`: what a participant PAYS; null = "needs participant pricing") and `cost` (what the hotel CHARGES us; null = "not set", which REFUSES the Room Invoice -- a cost is never assumed from the price since 2026-10-26) -- plus `costExtras` (`CostExtra[]`, never null, never on a bill), `ratePlanId` / `ratePlanVersion` (the hotel `RatePlan` it was copied from, blank = standalone, `isLinked()`), `minNights`, `validFrom/Until` (the option's DATE RANGE: the dates it covers; the default stay and every reservation must lie within it), `defaultStart/End`, `policyHtml`, `cancelFeeFixedCents`, `cancelFeeBps`, `disabled`, `version`. The four pre-split money keys (`pricingModel`, `nightlyPriceCents`, `nightlyPriceOverrides`, `singleSupplementCents`) FOLD into `price` on read and are still WRITTEN as mirrors of it for one release -- see "Price vs cost". |
| `RateSchedule` | One nightly rate and how it is charged: `pricingModel` (null -> `PER_ROOM`), `nightlyCents` (clamped >= 0), `overrides` (ISO date -> cents, copied in and out), `singleSupplementCents`; `nightlyCents(LocalDate)`, `isPerPerson()`, `hasOverrides()`. Knows nothing about which side it sits on: the FIELD it sits in (`price` or `cost`) is the side. |
| `CostExtra` | Something the hotel charges or credits on top of the nightly rate: `label`, `Basis` (`PER_PERSON_NIGHT` a city tax, `PER_ROOM_NIGHT` a resort fee, `PER_STAY` a cleaning fee, `FIXED` a booking fee), SIGNED `amountCents` -- a coupon is a negative extra, not a second concept. Cost side only; never attributed to a person. `pay/CostExtras` does the arithmetic. |
| `Concession` | A discount on ONE stay: `Kind` (`PERCENT` with `percentBps` 0..10000, `PER_NIGHT` and `TOTAL` with `amountCents`, `COMPLIMENTARY`), `reason`, `by`, `at`; `netCents(gross, nights)` clamped to `[0, gross]` so a discount never flips a bill into a credit; `label()` is "10% discount", "$5.00/night discount", "$20.00 discount" or "complimentary" and NEVER carries the reason. No `side` field: a `Reservation` holds up to two, `priceConcession` and `costConcession`, and the field is the side. |
| `Reservation` | Per trip: `offerId`, `accommodationId`, `occupants` (`Person.Id[]`), minute-granular `start`/`end`, optional `roomId`, `status` (`ACTIVE`/`CANCELLED`), notes, `waiveSingleSupplement` (the older, narrower PRICE-side concession, unchanged), `priceConcession`, `costConcession`, cancel fields (`cancelledAt/By`, `cancelFeeCents`, `credited`, `cancelReason`), `version`. |
| `RatePlan` | Per ACCOMMODATION (`lodging_rate_plans`), so the HOTEL's and able to span trips of several organizations: `name`, `roomTypeIds`, calendar-date `validFrom/Until` (`covers(from, to)`), `cost` (a nullable `RateSchedule`; null = not stated yet), `costExtras`, `notes`, `linkedTripIds` (the reverse index a plan edit fans out through; `link`/`unlink`), `retired` (a Boolean, so a builder that never mentions it makes a live plan), `createdBy`, `created`, `version`. A plan carries the HOTEL SIDE only (owner decision 2026-10-26): there is NO participant price on it, because the price differs per trip and an operator must never see one; `RatePlan.price` is gone, and a row written with a `price` key still reads (the DAO mapper ignores unknown properties) and drops it on its next save (`RatePlanTest.aRowWithTheRetiredPriceKeyStillReads`). Minimum nights, default stay, policy and cancellation fee stay on the trip's option too, because those are the trip's terms. |
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
| `lodging_rate_plans` | PK `accommodationId`, SK `id` | `PartitionCache` per hotel (`saveRatePlan/getRatePlan(s)/deleteRatePlan`; cleared by `CacheScope.LODGING`) |

Rooms and room types are INLINE on the accommodation row (one optimistic put edits the inventory
atomically, no dangling room -> type references). The DAO refuses a row over 350 000 serialized bytes and
the commands cap an accommodation at 500 rooms. Concurrent editors of one hotel collide on the version,
which is accepted; per-room history would need a rooms table later (room ids are stable UUIDs, so that is
a data move, not a redesign). Facade: `DAO.saveAccommodation/getAccommodation(s)/saveReservationOffer/
getReservationOffer(s)/deleteReservationOffer/saveReservation/getReservation(s)/getReservationsAt/
saveRoomBlock/getRoomBlock(s)/deleteRoomBlock/saveRatePlan/getRatePlan(s)/deleteRatePlan/deleteLodgingForTrip`,
`CacheScope.LODGING` (which clears the blocks and rate-plan namespaces too). `InMemoryPersistence.TABLES`
registers all five (local mode and the unit suite). `lodging_rate_plans` is CDK-created like the rest
(`AppStack.lodgingRatePlansTable`, `deploy-config.json` key `lodgingRatePlansTable`) and needs PITR enabled
BY HAND after its first deploy -- rollout order in
[migrations/lodging-price-cost.md](migrations/lodging-price-cost.md).

**Blocks got their own table rather than a list on the accommodation row.** That row already carries the
whole inventory under ONE optimistic version, so a hotel manager blocking a room for next week would
collide with an admin renaming a room, and one of them would be told to reload for a change that touched
nothing they were editing. A block is also the one lodging row a hotel's own staff write most often, and it
is keyed by the thing it belongs to. The cost is a second read on the board and in the dialogs
(`Cached.NO`, because a manager may have blocked a room a moment ago), which is one partition query.

That shape is also where the NEXT hotel-management row goes, and the first one arrived 2026-10: rate plans
are a second per-hotel partition cloned from the blocks wiring (same `(accommodationId, id)` keys, same
`PartitionCache` builder, one shared `loadByAccommodation`). The owner's stated intent for the hotel page is
that it grows into the hotel's own view of its business, and a per-hotel partition takes allotments or a
maintenance log the same way, without widening the accommodation row. `HotelCommands` is the matching seam
on the bean side: it answers "what does my hotel look like", which is a different question from
`LodgingCommands`' "where do I put this person", and it is where reads deliberately span the organization
boundary (see "Roles").

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
`HotelUsage.record`, which records the trip in `tripIds` in the same write). Nothing else in the app may copy
this shape; `docs/org-admin.md` "Organizations: the
tenancy boundary" still governs trips, offers, reservations, money and everything else.

Two reads deliberately ride on that exception, and only two: the hotel's Availability calendar (counts and
dates across every trip, since 2026-09-17) and the Hotel Operator's Trips tab (trip titles, dates and guest
names at THEIR hotel, since 2026-10-25) -- both under "Roles" below. Nothing else may cross.

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
capacity warning (through `LodgingCommands.roomProblem`, which runs the one-option-per-room check right after
it; see "One lodging option per room per night"). This is deliberately unlike over-capacity, which is the warning `force` overrides:
capacity is OUR call to overrule -- three people in a double is a decision an admin may make -- but a room
another group is sleeping in is not ours to hand out, so there is no "Block anyway". A blocked empty room
gets the CSS state `rs-blocked` rather than `rs-empty` (it is not ours to fill); a room both blocked and
slept in reads `rs-over`, because somebody has to move and that is the same urgency.

**A refusal that keeps a modal dialog open must be shown INSIDE that dialog.** A growl cannot serve one:
PrimeFaces gives the growl the modal overlay's own z-index (1007) and the dialog a higher one (1008), so the
overlay paints over the message. The user sees the screen flash and the dialog sit there, with no idea why,
and Playwright still reports the growl `isVisible()`, so no test notices either. This cost a real report
(2026-09-18): an overlapping-stay refusal on the reservation dialog was invisible. `placeDlg` and `splitDlg`
always showed their own problems for this reason; `resDlg` does now too, through `ReservationForm.problem`,
which every refusal in `createReservations` and `updateReservation` sets and every success clears. The growl
is still emitted for callers that have no dialog. `LodgingResource` answers the same text rather than a bare
"No reservation was created.", so an API caller and a webtest get the reason too.

**A created offer answers with its OWN id.** `POST /api/lodging/trips/{tripId}/offers` used to find the offer
it had just made by NAME, taking the last match. Two offers on one trip may share a name, which a trip using
two hotels naturally has ("Double room" at each), so the endpoint could answer with the other hotel's offer;
a caller that reserved on the id it was given booked the wrong hotel, and assigning a room from the one it
meant was refused with "That room is not at the accommodation". It now takes the id that was not in the
trip's offers before the save, and falls back to the name only to separate two creates racing each other.
Found 2026-09-18 as a webtest whose seed failed about half the time. `LodgingResourceTest`
(`eachCreatedOfferAnswersWithItsOwnIdEvenWhenTwoShareAName`) and `RoomingReportPwIT`, whose two hotels
deliberately name their offers alike, guard it. `LodgingCommands.saveOffer` still answers a boolean: the
file is AT the 3000-line checkstyle cap, so the id difference lives in the resource.

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

**The calendar never shows a guest's name.** It crosses the tenancy boundary only because the accommodation
itself is global, and what crosses there is counts and dates. A trip is named by the ONE naming rule for
cross-trip reads, `LodgingAccessCommands.mayNameTrip(tripId, accId)`: the caller is internal on that trip
(`canManageTripLodging`) OR operates this hotel (`canEditAccommodation`); anyone else reads "another
organization's trip", and on the trip board a room holding somebody else's guests shows the anonymous chip
"another trip · 2 people" (`LodgingCommands.otherTripLabel`, `HotelCommands.stayOf`). Those people still
COUNT toward the room's occupancy -- a bed another group's guest is in is not free because their reservation
lives in a different partition -- which is why `occupancyOver` and `cellFor` fold the index's rows in beside
the trip's own. `roomInUse` (the guard on deleting a room) asks the index too, instead of walking every org
that uses the hotel and reading each of its last 200 trips. The Trips tab goes further, for the hotel's
operators alone -- see "Hotel operators see trip titles, dates and guest names" under "Roles".

Gates: `canEdit` is `canEditAccommodation` (the people who may edit the rooms may say which are available);
`canRead` is that plus `canOpenLodgingAdmin`, so the lodging admins who book into the hotel can see how full
it is. A block with no rooms or no nights is refused, because an empty one would look applied and hold
nothing; past blocks stay in the table, faded, so a mistake remains findable.

## Roles: Lodging Admin, Hotel Operator, Trip Lodging Admin

Three experiences on top of the participant's (unchanged: a room on the itinerary, Bill rows on the ledger),
locked by the owner 2026-10-25. The stored base names did NOT change -- only display copy did -- so no
privilege row was migrated. "Internal" below means inside the organization running the trip: the people
who may see what a participant is charged.

| Role (display) | Privilege base | Internal? | Granted where | May do |
|---|---|---|---|---|
| **Lodging Admin** | `lodgingAdmin` on an ORG (`ORG_SCOPED_BASES`, on the org hub allow-list) or GLOBAL (`GLOBAL_BASES`, the `contentAdmin` dual pattern) | yes | org People page (`OrgCommands.grantOrgPrivilege`); the Global Privileges editor | create hotels (`canCreateAccommodation`), open `admin/lodging.jsf`, read every hotel's Availability, the FULL Lodging tab (options, reservations, prices, bills, price concessions) on that org's trips -- every trip for the global grant, which also edits every hotel. An org Lodging Admin does NOT edit an existing hotel unless also its operator (auto-granted on hotels they create), and that includes its rate plans: a plan has no price side for an organization to own (2026-10-26), and the price an org charges for a plan's rooms is entered on ITS trip's option when the plan is attached. |
| **Hotel Operator** | `accommodationAdmin` scoped to ONE hotel's UUID (`ACCOMMODATION_SCOPED_BASES`; display `PrivilegeCommands.ACCOMMODATION_ROLE_NAME`) | NO (external) | hotel create (creator + contact, `saveAccommodation`); the **Hotel operators** roster on the hotel page's Details tab (`managers` / `addManagerById` / `removeManagerById`; REST `POST accommodations/{acc}/operators {personId}` for fixtures). The roster is the ONLY grant path: no privilege editor knows the ACCOMMODATION scope. | the hotel's inventory as before (details, room types, rooms, floor maps, photos, retire, the roster, `RoomBlock`s -- `HotelCommands.canEdit` is exactly `canEditAccommodation`); the hotel page's **Trips** tab (every trip with an option or a stay there, with guest names); the Assignments board, room switches and quick blocks of every trip with an option or an active stay at their hotel (`canAssignAt`); the cost-only Room Invoice for their hotel's section; cost concessions on stays there ("Discount" on the Trips tab, `LodgingCommands.canSetCost`), and their hotel's rate plans in full -- the ONE place an operator states the hotel's cost (owner decision 2026-10-26, reversing 10-25: a trip's lodging OPTIONS, and their cost, are not shown to an operator at all) -- cost schedule, cost extras, room types, dates, notes, retired; `RatePlanCommands.canEditCost` = `canEditAccommodation` is the ONE plan gate, since a plan carries nothing an operator may not see. NEVER a participant price, a bill, or a price concession. |
| **Trip Lodging Admin** (prose only; no new base) | `tripMgr@trip`, or `lodgingAdmin` on the trip's org | yes | the Trip Managers roster (`OrgCommands.setTripRole`; `tripMgr` is auto-granted to a trip's creator) | the trip's Lodging tab as before -- options, reservations, cancellations, recompute, every price -- which is `canManageTripLodging`; also price concessions, attaching rate plans (pricing the option they become) and detaching them, and both sides of the trip's own options (an option's cost is `canManageTripLodging` alone; `canSetCost`, the stay-level cost discount, is that OR the hotel's operator). |
| site admin | | yes | | everything |
| `tripFinView@trip` / global `viewFinances` | | yes | | the Reports dashboard's money gate (`seeFinances`): the Room Invoice and its PDF, and the new billed-vs-cost report. No lodging privilege opens the dashboard. |

Gates live in `LodgingAccessCommands` (`#{lodgingAccess}`) and are re-exposed one-line by `LodgingCommands`
so every existing `#{lodging.can*}` expression still works: `isGlobalLodgingAdmin` (site admin or global
`lodgingAdmin`), `canEditAccommodation(accId)`, `canCreateAccommodation` (global, or `lodgingAdmin` on ANY
org), `canManageTripLodging(tripId)` (global, or `tripMgr@trip`, or `lodgingAdmin` on the trip's org),
`canOpenLodgingAdmin` (= `canCreateAccommodation` OR operates at least one hotel), `canAssignRooms(tripId)`
and `canAssignAt(tripId, accId)` (next paragraph). Editing an accommodation is privilege-based, never a
field on the row.

**`LodgingAccess`: what one caller may do with one trip's lodging, computed once per request.**
`LodgingAccessCommands.forTrip(tripId)` answers a `LodgingAccess` (Serializable, scalars and id sets only --
a page may keep it for the request and never longer) memoized in the Faces request map under
`lodgingAccess:{tripId}`, so the many `#{access.*}` reads of one render cost one computation; REST and tests
have no FacesContext and get a bare compute each call. Its fields: `tripId`; `internal`
(= `canManageTripLodging`); `operatorAccIds` (the hotels the caller holds `accommodationAdmin` on,
INTERSECTED with the hotels IN PLAY on the trip = the `accommodationId`s of the trip's options UNION of its
ACTIVE reservations, `inPlayHotels`); `allowedAccIds` (internal: every hotel in play; operator: their
`operatorAccIds`); and the derived `canAssign` (internal OR any operated hotel), `operatorHere`,
`seesPrices` (= internal), `mayOpenBoard(accId)` (in `allowedAccIds`), `operatorOf(accId)`. `none(tripId)`
is the anonymous caller or a blank trip. `trip/lodging.xhtml` sets `requestScope.access =
lodgingAccess.forTrip(theTripId)` in its initPage and reads nothing else for its gates: the door is
`access.canAssign`, `requestScope.fullLodging = access.internal` keeps every pre-existing expression valid,
a `?acc=` outside `allowedAccIds` falls back to `lodging.defaultAccommodationId` every request (the board
trusts that id), and `access.operatorHere` renders the "Hotel invoice" link. `roomInvoice.xhtml` reads
`operatorHere` the same way for its door.

**`canAssignAt(tripId, accId)` scopes the board to a hotel.** `canAssignRooms(tripId)` is now the one-line
`forTrip(tripId).isCanAssign()`; the write paths derive the hotel from the reservation or the room and ask
`canAssignAt` instead -- `assignRoom`, `unassignRoom`, `splitFormFor`/`splitStay`, `roomBoard` (which used to
trust `?acc=`) and `roomDetail`; `accommodationChoices` and `defaultAccommodationId` filter the toolbar's
hotel menu to it. It deliberately reads `internal || operatorOf(accId)` rather than `mayOpenBoard`: an
internal caller who put a NEW hotel in play earlier in the same request (a first option at a second hotel)
must be able to room people there at once, and the per-request memo cannot see that write; an operator is
still held to their own hotels. The two leaks that let an operator see a price were closed in Java, not only
in the page: `placementFormFor` answers an empty form and `optionChoices` an empty map unless
`canManageTripLodging` (the option labels carry `pricingLabel`), and `roomBoard` leaves the "No reservation
yet" list empty for them. `setRoomNumbersShown` TIGHTENED to `canManageTripLodging`: whether itineraries
show room numbers is a trip presentation setting, not a rooming one. `place`, "+ Another stay",
"Full registration" and `cancelPreview` stay internal-only, each because it reaches an option's price or a
bill. "Switch room mid-stay" is an operator's too, since a split changes neither the option nor the total
nights.

**`lodgingManager` is retired (2026-10-25), folded into the Hotel Operator.** The trip-scoped role added
2026-09-07 gave a hotel's staff the Assignments board of ONE trip, granted trip by trip on the Trip Managers
roster -- which is backwards: the hotel is the constant, the trips come and go, and every new trip at the
same hotel needed a grant somebody forgot. An operator now rooms every trip with an option or a stay at
their hotel, with no per-trip step. The constant `PrivilegeCommands.LODGING_MANAGER` stays `@Deprecated` with
a note, but it is in no base list (`TRIP_SCOPED_BASES`, `BASE_DESCRIPTIONS`, `OrgCommands.TRIP_ROLE_NAMES`),
so no page offers or renders it; `heldTripRoles` iterates the base list, so the ONE inert production row
simply stopped rendering and nothing reads it.
`PrivilegeCommandsTest.theRetiredLodgingManagerIsInNoBaseListAndTheHotelOperatorIsNamed` and
`OrgCommandsTest` pin that. `W/trip/edit.xhtml` needed no edit. An org's `grantablePrivileges`
allow-list no longer needs the entry.

**The naming rule for anything that crosses trips is `mayNameTrip(tripId, accId)`.** A trip is NAMED when the
caller is internal on it OR operates the hotel in question; otherwise it reads "another organization's
trip". `LodgingCommands.otherTripLabel` (the board's "Also in this room") and `HotelCommands.stayOf` (the
calendar's day detail) both ask it; neither names a guest.

### Hotel operators see trip titles, dates and guest names at their hotel (the tenancy extension)

**Owner decision 2026-10-25:** a Hotel Operator may see, for stays at THEIR hotel across every organization,
the trip's title and dates, the NAMES of the guests staying there, and the hotel's own COST figures (cost
schedule, cost extras, cost concessions). What never crosses: a participant's
price, a bill, or a price concession; those are the trip's business. This is the SECOND deliberate extension of the "accommodations are global" exception (the first
was the Availability calendar, counts and dates only), and the two are the whole list.

Why names: the operator rooms these people and bills the organization for them. A hotel that cannot see who
is in room 105 cannot run a hotel, and the anonymous "another trip · 2 people" chip that is right for a
DIFFERENT organization's admin was wrong for the people whose building it is. The reach is still one hotel
at a time -- the operator of hotel A on a two-hotel trip sees A's rooms and A's guests and never B's,
however the URL is spelled (`OperatorTripsPwIT`).

Where it shows: the hotel page's **Trips** tab (`admin/lodging.jsf?acc=&tab=6`, rendered on
`requestScope.editable` = `canEditAccommodation`), built by `HotelCommands.tripRows(accId)` into the scalar
rows of `HotelViews` (`HotelTripRow` with `boardUrl` and `invoiceUrl`, `HotelOptionRow`, `HotelStayRow` with
`guests`) -- no row carries a price, which is why none CAN leak. The trips are discovered two ways and the
two lists are UNIONED: the `by-accommodation` index over the last `TRIPS_LOOKBACK_DAYS` (400) finds every
trip with an active stay, and the hotel's own `Accommodation.tripIds` finds every trip that saved an OPTION
here -- so an option-only trip is listed with its options and an EMPTY stays table, which is how the hotel
can state its cost before anyone is booked. `tripIds` is bookkeeping, not a query: `HotelUsage.record`
writes it beside the org use on every offer save (best effort -- a lost race on the hotel row never fails
the offer save that triggered it, and the next save records it again), `deleteOffer` prunes it once no
option of the trip names the hotel (`HotelUsage.forgetTrip`), trip delete sweeps it
(`forgetTripEverywhere`), and a recorded trip whose options here are gone is skipped at read time
(`hasOptionAt`). The trip's Assignments board (`/trip/lodging.jsf?trip=&acc=`) then shows the operator
every card and chip by name. The tab's "Hotel invoice" link renders only while `HotelTripRow.invoiceReady`
(the invoice over THIS hotel alone: `ReportCommands.invoiceReady` fed a synthetic operator-only
`LodgingAccess`); otherwise a `hotelInvoiceBlocked` button opens `invoiceBlockedDlg` with the operator's
wording of what is missing -- see "Price vs cost". The calendar still
names nobody: its day detail is reachable by every lodging admin who books into the hotel, not only its
operators. `HotelCommandsTest.theTripsTabNamesTripsAndGuestsForTheHotelsOperatorsAlone`, its
`aTripWithOnlyAnOptionAtTheHotelIsListedWithNoStays`,
`LodgingCommandsTest.savingAnOptionRecordsTheTripOnTheHotelAndDeletingTheLastOneForgetsIt` and
`LodgingAccessCommandsTest.aTripIsNamedToItsInternalsAndToTheHotelsOperator` guard the edges.

## The hotel contact (the fourth sanctioned Person-creation path)

The contact of an accommodation is a real `Person`, found by email or AUTO-CREATED
(`LodgingCommands.findOrCreateContact`). Rules:

- valid email and a name are required; an existing person is joined, never modified;
- a created person gets first/last/email only: NO credentials, NO mail, no roster, family or registration
  touch; their first sign-in is the existing email-code activation flow;
- they join the creating admin's org context (`?orgId=` on the admin page, else the caller's first org;
  a caller with no org is refused) through `OrgCommands.addLodgingContact`, and are added to every further
  org the first time that org uses the hotel;
- they get `accommodationAdmin` for the hotel -- they are its first Hotel Operator; the audit line is
  `PERSON ... CREATED as lodging contact`.

The other three creation paths are sign-up, a family manager's add-member flow, and the REST people API
(`PersonCommands.saveProfile` javadoc).

**A Hotel Operator can save the hotel's Details (fixed 2026-10-25).** The edit form pre-fills the contact's
email, so EVERY save used to call `findOrCreateContact`, whose org write (`OrgCommands.addLodgingContact`)
admits only org admins and lodging admins: a contact holding nothing but `accommodationAdmin` could not save
their own hotel's phone number. `saveAccommodation` now calls it only when the submitted email differs from
the current contact's (`contactChanged`), and `findOrCreateContact` lets an operator name an EXISTING person
as contact without the membership write they could not make anyway (the refusal stays for creating a person
or joining them to an org).

## Media

Photos and floor plans are `MediaItem` rows with `orgId` null (site-level) in ONE slot per hotel,
`lodging-{accId}`, bytes under `lodging/{accId}/...` (S3 when enabled, else a bounded in-memory store served
by `web/LodgingPhotoServlet` at `/lodging-photos/*`). The accommodation row references media ids
(`photoIds` gallery order, `RoomType.photoIds`, `FloorMap.mediaId`), so the media manager can hide or
delete a row and the hotel prunes dangling ids on its next save. `admin/media.xhtml` hides lodging slots
with the chat photos behind its "Include chat and lodging photos" toggle; `MediaCommands.getCurated`
excludes them. Uploads go through the shared crop dialog with `LodgingUploadCommands` (`lodgingUpload`):
kinds `accPhoto`, `roomTypePhoto`, `floorMap` (floor plans are stored whole, 2000 px long edge).

## Price vs cost (`pay/LodgingPricing`, `pay/LodgingBiller`, `pay/CostExtras`)

Until 2026-10-25 ONE set of money fields on an option drove both what a participant was billed and what the
Room Invoice said the hotel charges, and "invoice total = sum of the lodging bills" was a tested invariant.
Reality differs: the hotel's rate is often not the participant's, hotels comp or discount rooms that are not
always passed on, and taxes, city fees and coupons apply on the hotel side only. The split is deliberate and
the old equality is gone -- the new billed-vs-cost report is where the two are compared on purpose.

**Two schedules on the option.** `ReservationOffer.price` (a `RateSchedule`) is what the participant PAYS
and feeds the bills; `ReservationOffer.cost` is what the hotel CHARGES us and feeds the Room Invoice, with
`costExtras` beside it. The rules below run unchanged on either side: `LodgingPricing.price(...)` keeps its
signature and is `lines(Side.PRICE, ...)`; `cost(...)` is `lines(Side.COST, ...)`. The side picks the schedule
(`offer.getPrice()` or the cost schedule; the engine's own cost read is `effectiveCost()`, a fallback no
caller reaches, next bullet) and the concession (`reservation.getPriceConcession()` or
`getCostConcession()`); a side with NO schedule prices to an empty list, the same contract as a null offer.

- **Unset cost is "not set", and the Room Invoice REFUSES** (owner decision 2026-10-26, reversing the
  previous day's "assume the price"): an invoice with a guessed line would be sent to a hotel as if agreed.
  `RoomInvoiceBuilder.scheduleOf(COST, offer)` is `offer.getCost()` strictly, and ONE active-stay option in
  the caller's scope without a cost blocks the whole build (`RoomInvoice.blocked`, `missingCost` naming the
  options hotel-qualified: "Double room (Pansion)"). Every reader is told, in their own words
  (`ReportCommands.blockedMessage`): an internal reader is sent to the trip's Offers tab (or a hotel rate
  plan), an operator to the trip's lodging admin or one of their hotel's rate plans, since an operator sets
  no option's cost (2026-10-26). Only the Billed vs Cost report tolerates a missing cost, reading "not set" and
  counting 0, because showing exactly such gaps is what it is for. `ReservationOffer.effectiveCost()` /
  `isCostAssumed()` and the `effectiveCost()` read inside `LodgingPricing.Side.COST` are still in the code,
  but no caller reaches that fallback with a cost-less option: the invoice builder refuses before pricing
  and `LodgingConcessions.preview` answers `NO_COST` first. They are leftovers to remove, not a rule to lean
  on.
- **Unset price is a refusal, not a zero.** `price == null` means "needs participant pricing": the Offers tab
  says so instead of a rate, the option is absent from every reservation and placement menu, and
  `stayProblem` refuses the first thing ("'X' has no participant price yet; set it before reserving").
  A $0 price is still a price and still allowed; it writes no bill row.
- **Nights.** A NIGHT is a calendar date `d` with `start.toLocalDate() <= d < end.toLocalDate()`; times are
  informational. Same date = 0 nights; end before start = 0. (Deliberately not the 4 a.m. fudge of
  `getLodgingDays`.)
- **`PER_PERSON`.** Each occupant pays the schedule's `nightlyCents(date)` per night. The single supplement is
  added on a night the person is ALONE: the only occupant of their assigned room that night, or a one-person
  reservation not placed yet (its own occupants are all that is known; placing them with someone recomputes
  it away). Room type is irrelevant.
- **`PER_ROOM`.** The room rate for a night is split with `MoneyMath.splitEvenly` among that night's occupants
  of the room UNDER THE SAME OPTION (`LodgingPricing.underOption`; see "One lodging option per room per
  night"), sorted by person id (remainder cents to the lowest ids, so a recompute is deterministic). An
  unassigned reservation splits among its own occupants. A `PER_ROOM` schedule ignores the supplement (the
  sole occupant already pays the whole room). Invariant: an option's occupants of a room sum to the room rate
  per night. The `PER_PERSON` "alone" test reads the same list: somebody under another option is not company.
- **The waiver stays price-side.** `waiveSingleSupplement` removes the supplement on the PRICE side only: the
  hotel still charges it, so the cost side ignores the flag (a hotel that forgives it is a cost concession).
- **A bill** is a `Transaction` of type `Bill`, written NEGATIVE (balance = sum of amounts, negative "owes"),
  category "Lodging", id `{reservationId}-lodging-{personId}`, bound TRANSACTION -> TRIP and TRANSACTION ->
  TRIP_EVENT. Same amount and note = unchanged; zero = no row (an existing one is soft-deleted). There is no
  Invoice model: the Room Invoice re-prices from reservations and options and never reads the ledger.
- **Bills recompute AUTOMATICALLY** (audited) on every reservation create, date change, room change, waiver
  or price-concession change and cancel, for the affected room(s) or the reservation alone when unassigned,
  AND on every edit of the option's PRICE side (all of its active reservations), while
  `KnownSettings.LODGING_AUTO_RECOMPUTE` (`lodging.bills.autoRecompute`, default on, org-overridable; in the
  Settings page's "Lodging" section since 2026-10-25 -- it was declared but in no section, so invisible and
  not overridable) is on; off, the page growls "press Recompute". A cost-side edit recomputes nothing: no
  bill reads it. The explicit Recompute button stays for two cases only: the setting is off, or a bill was
  deleted by hand / the ledger and the reservations drifted (a repair, not a routine step). Auto-recompute can
  RAISE a paid occupant's bill after a roommate cancels; that is audited, not notified.
- Because a soft-deleted transaction is invisible through the DAO, a lodging bill an admin deleted by hand
  RETURNS on the next recompute.

**The JSON fold and the one-release mirror.** Rows written before the split carry the price as four flat
keys (`pricingModel`, `nightlyPriceCents`, `nightlyPriceOverrides`, `singleSupplementCents`). The
`ReservationOffer` constructor folds them, modeled on the `roomTypeId` fold: a `price` object wins; else
`"priced": false` means unpriced; else ANY legacy key present builds `price` from the four (every old row
wrote `nightlyPriceCents`, so every existing row, a $0 one included, reads as PRICED at its old numbers);
else -- nothing at all, a fresh builder -- the offer is unpriced. Writing, every row carries
`price`/`priced` AND the four legacy keys as `@JsonProperty` mirror getters delegating to `price`, because
`DAO.createObjectMapper` disables `FAIL_ON_UNKNOWN_PROPERTIES` for rolling deploys: an OLD instance reading a
new row without `nightlyPriceCents` would price it at $0 and soft-delete every bill on the trip. The mirror
(and the `@Deprecated` legacy setters that write the price side) come off ONE release later -- the checklist
is [migrations/lodging-price-cost.md](migrations/lodging-price-cost.md). `ReservationOfferTest` holds the
fold cases; `ReservationOffer.ser` was regenerated.

**Concessions (`model/Concession`).** A discount on ONE stay, applied AFTER the per-night sum on the side it
sits in. Kinds: `PERCENT` (`percentBps`, half-up), `PER_NIGHT` and `TOTAL` (`amountCents`), `COMPLIMENTARY`.
`PERCENT` and `COMPLIMENTARY` apply to each person's line (`netCents`, clamped to `[0, gross]` so a discount
never becomes a credit); `PER_NIGHT` and `TOTAL` are RESERVATION-level amounts, split with
`MoneyMath.splitEvenly` over the id-sorted occupants and taken off each line, floor zero. A `Line` now
carries `grossCents` and `concessionCents` beside the net `amountCents` (the 6-arg constructor survives for
the pre-split tests). Owner decision 3: a price concession REDUCES the bill IN PLACE -- still one row per
person per stay, and `describe` appends `", " + concession.label()` so the note names it; fully
complimentary nets to zero, and zero writes no row, so "complimentary = no row" costs the biller nothing new.
The label NEVER carries the reason: a bill note is compared byte-for-byte on every recompute, so a reason
edit would rewrite every row, and a reason may be internal ("hotel comped the guide"). Every un-concessioned
note is byte-identical to the pre-split wording (`describe` reads the schedule where it read the offer, and
appends nothing without a concession), so the deploy rewrote ZERO bill rows -- the deploy check is a
recompute on a seeded trip reporting `written 0`, and
`LodgingPricingTest.everyUnconcessionedDescriptionIsByteIdenticalToThePreSplitWording` is the regression
guard. A cost concession lives on the same reservation (`costConcession`), reduces the invoice line the same
way, is granted by the hotel's operator (or an internal admin) and is the one concession an operator may
see; the price concession is internal-only. The two dialogs are under "Pages" (since 2026-10-26 both open from the trip's Reservations tab, an Add/Edit
button in each discount column); the bean seam is `LodgingConcessions`, whose `preview` re-derives the
participant's new bill on the price side and, on the cost side, "Hotel cost for this stay: $300.00 -> $270.00"
(`LodgingConcessions.NO_COST`, "Hotel cost is not set for this option.", when the option has no cost), and
which `#{lodging.concessionFormFor/setConcession/concessionPreview}` delegate to, so
the write funnels through `persistReservation` and gets the reservation dialog's audit line and automatic
bill recompute.

**Cost extras (`model/CostExtra`, `pay/CostExtras`).** Taxes, fees and coupons the hotel adds on the cost
side (owner decision 4; Room Invoice only). Each has a `Basis` -- `PER_PERSON_NIGHT` (x
guest-nights: occupants x nights over the option's active stays), `PER_ROOM_NIGHT` (x distinct room x night
pairs), `PER_STAY` (x active stays with at least one night), `FIXED` (once per option group whatever the
counts) -- and a SIGNED `amountCents`, so a coupon is a negative extra and comes out negative
(`CostExtras.quantity` / `amountCents` / `describe`: "City tax: $2.50 × 12 guest-nights", "Coupon:
-$20.00"). Extras are never attributed to a person and never reach a bill or the price side; the invoice
totals them per option as `EXTRA` lines (`RoomInvoiceBuilder`, `docs/reports.md`). Every cost cell (the
Offers tab's "Hotel cost", the hotel page's Trips and Rate plans tabs) shows the nightly rate alone and lists
the extras UNDER it (`OfferSchedules.extraLines`, 2026-10-26: the column used to say only "+1 extra"):
nightly extras summed per basis ("+$4.00/night per person (City tax, Tourist tax)"; a per-person and a
per-room amount are never added together), then each one-time extra by name ("Cleaning: $20.00 per stay",
"Coupon: -$10.00 once").

## Cancelling

`cancelReservationWithFee(tripId, resId, credit, feeDollars, reason)`: the reservation becomes `CANCELLED`
(originals bills stay), the occupants leave the offer's event only if no other ACTIVE reservation of theirs
references it, and co-occupants' bills recompute. The fee defaults from the offer (`cancelFeeFixedCents` +
`cancelFeeBps` of the billed total, half-up, capped at the total) and is admin-editable in the dialog.
With credit on, ONE positive Bill per occupant, id `{reservationId}-cancel-{personId}`, for
(billed - fee share), description `Lodging cancellation credit: ...`; zero credits write nothing;
idempotent.

## Reservations and the event

Every reservation requires an option that has a participant PRICE (a $0 price is allowed; an UNPRICED option
-- "needs participant pricing" -- refuses first). Occupants must be on the trip
roster; nights must reach the option's `minNights`; a disabled option refuses; a stay outside the option's date range
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

**Editing may change the lodging option (2026-10-26).** `updateReservation` honors `ReservationForm.offerId`
(blank keeps the stored one; an unknown id refuses "Choose a lodging option."). The new option is validated
like a new stay (`stayProblem`: priced, enabled, roster, `minNights`, inside the option's dates, no
overlapping stay of the same people), the room -- if any -- must be at the new option's hotel, else "Room 114
is at Pansion; pick an option at that hotel, or take them out of the room first."
(`RoomAvailability.hotelProblem`), the room-type mismatch stays a warning, and the block and one-option
checks run as on every path. On success the stay's `offerId` and `accommodationId` follow the option, the
occupants join the new option's event and leave the old one's unless another active stay of theirs still
uses it (`leaveEventIfLast`; nothing moves when both options share an event), one audit line reads "Changed
option from 'X' to 'Y' for ...", and the bills recompute as for any write. Guarded by
`LodgingCommandsTest.editingAReservationMayChangeItsLodgingOption`.

## Every refusal is shown inside its dialog (2026-10-26)

A growl is invisible under a modal dialog (the overlay outranks it), and JSF's own conversion and validation
messages (a required field, a bad number, an unparseable date) used to go nowhere else. Every modal dialog on
`trip/lodging.xhtml` and `admin/lodging.xhtml` now carries a `p:messages` at its top (`resMsgs`, `offerMsgs`,
`placeMsgs`, `splitMsgs`, `pcMsgs`, `ccMsgs`, `ppMsgs`, `cancelMsgs`, `winMsgs`, `qbMsgs`; `accMsgs`, `rtMsgs`,
`roomMsgs`, `blockMsgs`, `bulkMsgs`, `cdMsgs`, `rpMsgs`), every submitting button in the dialog updates it, and
every save keeps the dialog open on `args.validationFailed || args.keepOpen`. The bean's own refusals keep
landing in the form's `problem` (the `.lo-problem` block) and NO LONGER raise a FacesMessage when a form is
there to carry them -- otherwise the same sentence shows twice; the growl remains for refusals with no form
(the board's one-click assign, gates, REST). The board's one-click assign opens `refusalDlg` for any refusal
that is not the over-capacity warning (a block, the one-option rule, a hotel mismatch); `PlacementForm` and
`SplitForm` carry `canForce`, true only for that warning, and the "Place anyway" / "Move anyway" buttons render
on it, so an outright refusal never offers an override.

**An EDIT keeps the booked dates when the option changes.** The reservation dialog's option menu is enabled
on edit; for a NEW reservation a change re-defaults the stay to the option's default dates, for an edit it
leaves the stay alone, so the switch is validated against the stay the person actually has (an option whose
dates do not cover it is refused, in the dialog). The webtest that found this wrote the original range back
after the switch and saw the save succeed: the dialog had quietly moved the stay inside the new option's range.

## One lodging option per room per night

Owner decision 2026-10-26: **a room's nights are priced under ONE lodging option, and a second is refused
outright** -- from every path that puts a stay into a room: `assignRoom` (the board's click), `place` (the
placement dialog), `createReservations` with a room, `updateReservation` with a room (a changed option
included) and `splitStay` (the second half's room). `RoomAvailability.optionProblem` is the check, run by
`LodgingCommands.roomProblem` right after `blockProblem` on each of those paths, and it answers "Room 003
already holds Trinity Paulsen under 'Double room, shared' for Jan 21 - Jan 31, 2027; a room's nights are
priced under one lodging option. Pick another room, or move them first." -- the occupants in the way, their
option, their dates -- when the room holds an ACTIVE stay of the trip under a different option on any shared
NIGHT (calendar dates, `sharesANight`: checking in the morning they leave is fine). The stay being edited or
split is excluded; two stays under the SAME option sharing a room are the shared-room case and stay allowed;
another trip's guests in the room are a capacity matter, not an option one.

Why a refusal and not the capacity warning: a per-room rate is split among a room's occupants and the Room
Invoice charges each option its whole room, so two options on one night would bill the room twice -- there is
no "anyway" that makes that right, which is also why `force` does not get past it (the REST room endpoint
answers the text with a 409, the create endpoint with the form's problem as it does a block). The pricing
side was made to agree for the rooms created before the rule: `LodgingPricing.underOption` filters a room's
stays to the priced stay's option, so an old mixed room charges each stay its own option's whole room and
the invoice's two tables add up alike (`docs/reports.md`, "Charges per person").

`AssignOutcome.refused` is true for every refusal that is not the capacity warning (block, option, hotel,
gate, a failed save), so the board can open a refusal dialog with `message` instead of growling under a
modal. Form-bound refusals (the reservation, placement, split, option, cost, concession and rate-plan
dialogs) set the form's `problem` and raise NO growl: every dialog carries a `p:messages`, so a growl too
would show the same text twice; the growl stays for callers with no form (the board's one-click assign, REST).
Tests: `RoomAvailabilityTest.aRoomPricedUnderAnotherOptionRefusesAStayAndNamesWhoIsInIt`,
`LodgingCommandsTest.aRoomPricedUnderOneOptionRefusesASecondOptionEveryWay`,
`LodgingPricingTest.aRoomHoldingTwoOptionsChargesEachStayItsOwnWholeRoom`, the invoice test above and
`LodgingResourceTest.theWholeFixtureChainWorksEndToEnd`.

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
gated on `canAssignAt` rather than `canManageTripLodging` -- the hotel's own operator may move somebody
between rooms, which changes where they sleep, not what they pay.

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
`canManageTripLodging` since 2026-10-25 -- a trip presentation setting, so internal callers only; it was
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

## What a Hotel Operator sees on the trip's Lodging tab

The gates are under "Roles" (`access.canAssign` is the door, `access.internal` the full tab). For an operator
the page renders the Assignments tab alone and forces the tab index to 0 (a `?tab=` they cannot open, or one
left in the view, would index a tab that was never built), drops the "No reservation yet" column (its clicks
would all be refused, since placing an unreserved person picks the option that prices them -- and
`roomBoard` leaves that list empty in Java anyway), hides the person card's "+ Another stay" link for the
same reason, hides its "Full registration" link (that page would bounce them), and does not render
`placeDlg` or the `openPlacement` remote command at all. Names are visible on every card and chip. "Switch
room mid-stay" stays, because a split changes neither the option nor the total nights. `cancelPreview` is
gated too: what was billed and what comes back is the trip's business, not the hotel's. The "Hotel invoice"
link renders for internal callers and operators alike, and for both it gives way to the `hotelInvoiceBlocked`
button while an option in their scope has no hotel cost ("Price vs cost"). The tab STRIP renders for them as
well, carrying
Lodging alone, the way `registrationAdmin` carries Registrations alone. The operator's own door onto all of
this is the hotel page's Trips tab, not the trip menu.

## A command inside a repeating row names its targets absolutely (2026-10-26)

The three cost-extras editors (the option dialog's `costPanel`, the operator's `cdPanel`, the plan dialog's
`rpCostPanel`) each have a per-row "Remove" link inside a `p:dataTable`, and each used to name its panel by
a RELATIVE id (`update="costPanel"`). A `p:dataTable` row is a naming container, so the search ran inside the
row, found nothing, and threw `ComponentNotFoundException` MID-RENDER on the FIRST row -- while the ajax
response was already streaming. The browser saw a 200, a chunked body that stopped, and an aborted
connection, which PrimeFaces shows as a button spinning forever; nothing reached the growl and the browser
console held nothing. The server log is the only place the exception appears (the classic "200 + chunked +
aborted" signature of a mid-render throw). The fix is the absolute id, `:form:costPanel` (and `:form:cdPanel`,
`:form:rpCostPanel`); the "Add extra" buttons sit OUTSIDE the table and may stay relative. Rule: a command
inside a repeating row (`p:dataTable`, `ui:repeat`, a tab built per row) names its `process=` and `update=`
targets absolutely, always. `LodgingCostPwIT.costExtrasAreAddedAndRemovedInTheOfferDialog` and
`RatePlanPwIT.aPlanDialogAddsAndRemovesCostExtras` add a row, remove another, and assert the first survives
both re-renders with what was typed.

## Pages (private repo)

- `admin/lodging.jsf` (site-level; gate `canOpenLodgingAdmin`): list mode (managed + all) and detail mode
  with tabs Details (edit, retire, managers), Room Types, Rooms (bulk add), Floor Maps (upload, the
  rectangle annotator in `trip-js/floorMap.js`, saved through `saveFloorRegions` JSON, plus Move plan and
  Remove plan), Photos, and **Availability** (gate `hotel.canRead`): the `p:schedule` month calendar, a
  legend, and the "Rooms held for something else" table with its block dialog (rooms multi-select, a range
  picker reading "first night - free again", free-text reason), and **Trips** (index 6, rendered on
  `requestScope.editable` = `canEditAccommodation`): the Hotel Operator's door onto every trip with an option
  or a stay at this hotel, ONE trip at a time (2026-10-26): `hotel.tripsTab(accId, ?trip=)` answers a
  `HotelTripsTab` -- every trip as a picker entry, newest start first, and the row of the asked-for trip, or
  of the newest when `?trip=` is absent or not one of them; changing the `tripPick` menu re-navigates to
  `?acc=&tab=6&trip=` (a plain GET, so a reload or a shared link shows the same trip). The shown trip is one
  `p:panel`: title and dates, the org's name, an "Assign rooms" link to `/trip/lodging.jsf?trip=&acc=` and a
  "Hotel invoice" link to `/admin/reports/roomInvoice.jsf?trip=&acc=` (the `acc` is where the invoice's back
  button returns an operator) -- or, while an option here has no cost, a `hotelInvoiceBlocked` button whose
  `invoiceBlockedDlg` names it; the row's trip id rides an `f:param` and the text lands in
  `viewScope.invoiceBlockedText`. Its lodging OPTIONS here, with their Cost column and "Set cost"
  (`costDlg`: `lodging.offerCostFormFor` / `setOfferCost`, gated `canManageTripLodging`, an `OfferCostForm`
  with NO price field), render only for the trip's OWN lodging admins (`HotelTripRow.internal`; `options`
  stays empty for anyone else): an operator states the hotel's cost in its rate plans (owner decision
  2026-10-26, reversing 10-25). Its stays: guest names, dates, nights, room, and **Room Price** -- what the
  hotel is paid for the stay's room nights (`LodgingPricing.cost` over the trip's active stays on that room;
  extras stay on the invoice), "not set" without a cost; under a hotel discount the undiscounted amount
  struck through (`roomPriceBefore`, `.ht-before`), the discounted one, and the discount's label below
  ("50% discount", "complimentary") -- plus a "Discount" button opening `costConcDlg`
  (`lodging.concessionFormFor(tripId, resId, 'COST')`: kind, amount, reason, the refusal shown IN the
  dialog). No participant price and no bill anywhere on this tab, by construction of `HotelViews`. The row is
  built in initPage (the row commands sit in a `ui:repeat` over `tripsTab.rows`, zero or one, and must exist
  at decode) and again in a `beforeEncode` on a wrapper that always renders, so a command elsewhere on the
  page shows the trip as it is after it ran. The list mode's per-hotel button reads **Manage** (was "Open",
  2026-10-26). Tabs are APPENDED after the
  last one so every `?tab=` link ever handed out keeps its meaning: Availability became 5 in 2026-09, Trips 6
  and **Rate plans** 7 in 2026-10 (see "Rate plans"); the next tab goes in at 8. `viewScope.calMonth` says
  which month it OPENS on (`?month=`, which is also how a test pins it); the data does not come from it. The
  model is a
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
- `trip/lodging.jsf` (door `access.canAssign`, full tab `access.internal` -- see "Roles"; `?acc=` opens the
  board on a given hotel, falling back to one the caller may open; the toolbar's "Hotel invoice" link renders
  for internal callers and operators): under a
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
- Also on `trip/lodging.jsf`: the option dialog's money block is TWO `p:fieldset`s, **Participant price**
  ("Participant price is set" over its inputs) and **Hotel cost** ("Hotel cost is known" over its inputs
  and the cost-extras editor: label, basis, amount rows, `offerExtras`, whose per-row Remove link names
  `:form:costPanel` ABSOLUTELY -- the gotcha above). An option LINKED to a rate plan edits its participant
  price here like any other; its Hotel cost fieldset shows a note in place of the inputs ("Set by the
  hotel's rate plan; detach the option to change it here"), since that side is the plan's, and its
  Accommodation menu is locked (`OfferRules.problem`: "An option added from a rate plan stays at that hotel;
  detach it to move it."). The Offers table carries a "Hotel cost" column reading "not set" when `cost` is
  null, and its "Pricing" column reads "needs participant pricing" when `price` is null. Its header carries
  two buttons in a fixed order (owner decision 2026-10-26): **"Add from hotel rate plan"** (`fromPlan`) is
  PRIMARY and first, **"New Lodging Option"** (`newOffer`) is secondary (`ui-button-outlined`), because
  taking a hotel's standing rate is the usual way an option comes to be. `fromPlan` loads
  `ratePlans.attachable(tripId)` and `ratePlans.attachFormFor(tripId)` into the view and opens
  `planPickDlg` -- a plan menu (every live plan of every non-retired hotel that covers the trip's dates, a
  plan already on the trip INCLUDED, labeled "Hotel: Plan (dates)") over a **Participant price** fieldset
  ("Participant price is set", pricing model, nightly price, single supplement) -- and "Add lodging option"
  calls `ratePlans.attach(viewScope.attachForm)`; a refusal shows in `ppProblem` and keeps the dialog open.
  With NOTHING to attach the button is styled `lo-disabled` (`requestScope.planChoiceCount == 0`, computed
  at render) but still answers: the command sets the `noPlans` callback param and the page opens `noPlanDlg`
  instead, which says no plan covers the trip's dates, where one is added (Admin > Lodging > the hotel >
  Rate plans) and that the viewer may lack the permission to do it -- the server decides which dialog,
  since a plan may have appeared since render. A linked row wears a short "plan" chip (`.lo-plan`, hover
  title `Added from hotel rate plan: "<name>"`), a "plan changed" drift marker when its `ratePlanVersion`
  lags the plan's (or the plan is gone: "(deleted plan)"), and a Detach button (`detachOffer` ->
  `ratePlans.detach`); every row's Delete is `disabled` while `reservationCount > 0`, its wrapper's hover
  title saying why ("2 reservation(s) use this option; cancel them first." -- `deleteOffer` itself still
  refuses while ANY reservation, cancelled ones included, names the option). The Reservations tab's Room
  cell prints the occupants' names ("Preferred Last", `ReservationRow.occupantNames`, id order) in smaller
  text under the room; its "Discount" column (the price concession) and "Hotel discount" column (the cost
  concession) each carry an Add/Edit button (`priceConc` -> `priceConcDlg` "Participant discount",
  `costConc` -> `costConcDlg` "Hotel discount"; `lodging.concessionFormFor(tripId, resId, 'PRICE'|'COST')`):
  kind, amount, reason, and a preview re-derived by `lodging.concessionPreview(form)` on every kind or
  amount change -- the participant's new bill on one side, "Hotel cost for this stay: $300.00 -> $270.00"
  on the other; `lodging.setConcession(form)` saves, and a refusal keeps the dialog open with the reason in
  it. Above the tabs, `invoiceBar` re-derives `requestScope.invoiceReady = lodging.invoiceReady(tripId)` in
  a `beforeEncode` and is in the update list of every command that can change it (an option saved,
  attached, detached or deleted): the "Hotel invoice" link renders only while every option in the caller's
  scope has a cost, else the `hotelInvoiceBlocked` button opens `invoiceBlockedDlg` carrying
  `lodging.invoiceBlockedMessage(tripId)`.
- `admin/reports/roomInvoice.jsf` (door `requestScope.mayOpenInvoice` = `seeFinances` OR
  `lodgingAccess.forTrip(trip).operatorHere`; `requestScope.invoiceInternal` = `seeFinances` OR
  `access.internal` picks the wording of a refusal): the Room Invoice is COST-ONLY since 2026-10-25 (owner
  decision 6) and the operator gets only the sections of hotels they operate
  (`ReportCommands.roomInvoiceFor(tripId, access, internal)` -- the page decides finance-ness, the bean
  rebuilds the invoice over their stays; `RoomInvoicePdfServlet` applies the same filter, so the PDF an
  operator downloads is their hotel's part alone). While any option in that scope has no cost the page
  shows `riBlocked` with `lodging.invoiceBlockedMessage(tripId)` and renders no table, no total and no
  Download PDF (the servlet answers 409); under each hotel's heading `.riInvoiceTo` says whose bill it is
  ("Invoice to <org> for <trip> room charges at <hotel>."); the **billed-vs-cost** page
  (`admin/reports/billedVsCost.jsf`,
  `seeFinances` only, the third button on the dashboard's Rooms card; it prints rather than exporting a
  PDF) is where billed and cost are compared. Operators reach the
  invoice by the direct links on the hotel page and the trip's Lodging page, never through the Reports
  dashboard. Details: `docs/reports.md`.
- NB inside a JSFT `initPage`, nothing may follow `jsft.redirect(...)` outside an `else`: the script keeps
  running and reads a view map that is gone (the refused-visitor 500 of 2026-09-06).

## Rate plans (`model/RatePlan`, `lodging_rate_plans`, `action/RatePlanCommands`)

**What they are.** A hotel's reusable rate template: the hotel's `cost` and `costExtras` as its operator
states them, for some room types over a date range -- a row in the hotel's own partition, so one plan can
serve trips of several organizations. **A plan carries the HOTEL side only** (owner decision 2026-10-26,
tightening decision 2 of the day before, which had let an internal admin put an optional participant price
on a plan): the price differs per trip and is the organization's business, and an operator must never see
one, so `RatePlan.price` is gone, `RatePlanCommands.canEditPrice` is gone, and the plan table, dialog and
REST body are the same for every reader. A row written with a `price` key still reads and drops it on its
next save (`RatePlanTest.aRowWithTheRetiredPriceKeyStillReads`). Minimum nights, default stay, policy and
cancellation fee stay on the trip's option as well, because those are the trip's terms. The model, table,
DAO (`savePlan/getPlans/getPlan/deletePlan` behind `DAO.saveRatePlan/getRatePlans/getRatePlan/deleteRatePlan`,
`CacheKeys.LODGING_PLAN_PREFIX`, cleared by `CacheScope.LODGING`), CDK table and `setup-instructions.txt` row
landed 2026-10-25; `RatePlan.ser` is a serialization fixture.

**A linked option owns its participant price; its cost side follows the plan.** A trip takes a plan by
COPYING the plan's cost schedule and extras onto a `ReservationOffer` that remembers `ratePlanId` /
`ratePlanVersion`, and the plan records the trip in `linkedTripIds`. The option's price is entered when the
plan is attached (or on the Offers tab afterwards) and is never touched by the plan again. Read-time
resolution was rejected: every reader -- `LodgingPricing`, both reports, the PDF, REST, the offer rows --
would need a resolver, which is exactly the hidden join the "DAO reads return copies" rule exists to avoid.
Write-through keeps the offer self-contained, so none of those readers ever learns that plans exist, and
since no bill reads the cost side a plan edit never recomputes anything.

- Bean `RatePlanCommands` (`#{ratePlans}`) plus package-private `OfferSchedules`, the money half of
  `applyOfferForm` moved out of `LodgingCommands` (AT the 3000-line cap): `apply(offer, form)` for the
  dialog's two schedules, `applyCost(offer, costForm)` for the operator's dialog, `copyFromPlan(offer, plan)`
  (COPIES `cost` and `costExtras` -- never the plan's own objects, since the plan is a DAO row the caller may
  go on editing -- sets `ratePlanId` / `ratePlanVersion`, and leaves the offer's `price` alone),
  `applyAttachPrice(form, attach)` (the attach dialog's four price fields onto the new option's form), the
  plan dialog's `applyPlanCost` / `fillPlanForm` / `planProblem`, `attachProblem`, the labels
  (`pricingLabel`, `costLabel`, `operatorCostLabel`, `planCostLabel`) and `linkOf(offer, plan)`, the Offers
  tab's `PlanLink` (plan name + drift). `OfferRules` (2026-10-26) is the offer dialog's refusals, moved out
  of `LodgingCommands` for the same size reason: the linked-option hotel lock, un-pricing with active stays,
  the date and fee rules, then `OfferSchedules.problem` and the policy HTML. The scalar shapes are
  `RatePlanViews`: `RatePlanRow` (no price field, so one table serves every reader), `RatePlanForm` (`accId`
  INSIDE the form; `costKnown` starts ON, since stating the cost is what the dialog is for), `PlanChoice`
  (one menu entry whose `value` is `accId|planId`, `PlanChoice.SEPARATOR`, so the page never splits ids
  itself) and `AttachForm` (the trip, the `choice`, and the new option's participant price: `priceKnown`
  starts ON for the dialog, `pricingModel`, `nightlyPrice`, `singleSupplement`; `problem` for the refusal
  shown in the dialog).
- Gates: `canEditCost(accId)` = `canEditAccommodation` (the hotel's operators, global lodging admins) is the
  ONE write gate -- every field of a plan is the hotel's; package-private `canRead(accId)` =
  `canEditAccommodation` OR `canOpenLodgingAdmin`, whoever may open the hotel page.
- `rows(accId)`: the hotel's plans by name -- room-type names ("?" for a type no longer at the hotel), a
  dates label ("Sep 1 - Oct 31, 2026", "from ...", "until ...", "any dates"), the cost summary ("not set"
  while unstated), the linked trips as THIS caller may name them (`mayNameTrip` per trip: titles for the
  ones they may name, "2 other trips" for the rest), `linkedCount`, `retired`, `notes`. Empty for anyone
  who may not `canRead`.
- `savePlan(form)` (gate `canEditCost`): a name, at least one room type the hotel has, the last date not
  before the first, then `OfferSchedules.planProblem`. A versioned save, audited with "(cost changed)" when
  the cost or the extras moved, then `syncLinked(plan)`. There is no un-pricing to refuse on a plan any more;
  that rule lives on the OFFER (`OfferRules.problem`: an option with active stays cannot lose its participant
  price, because the stays already taken would price to nothing).
- `syncLinked(plan)` FIRST prunes stale links -- a linked trip that is gone, or that has no offer following
  the plan any more -- and re-saves the plan ONCE, so the offers then copy the plan's final version and read
  as current; then per surviving trip every offer carrying that `ratePlanId` (`Cached.NO`) re-copies the
  plan's cost side and is saved; audited per trip under the trip's org. It NEVER recomputes a bill: no bill
  reads the cost side and the offer's price is untouched. A lost conditional put on one offer is REPORTED,
  not retried ("Plan saved; 3 of 4 trips updated; trip 'X': saved by someone else" on `form.problem` and in
  a warn growl) and leaves that offer's `ratePlanVersion` behind, which the Offers tab shows as "plan
  changed" drift -- the admin re-saves the plan or detaches; nothing retries silently.
- `retirePlan(accId, planId, retired)`: a retired plan leaves the attach menu and `attach` refuses it;
  linked offers keep following it. The save moved the version, so it runs `syncLinked` too (the same
  numbers re-copied) rather than let every linked option read as drifted. The dialog's "Retired" checkbox
  reaches the same field through `savePlan`.
- `deletePlan(accId, planId)`: refused while `linkedTripIds` is non-empty ("'X' is on 2 trips. Detach it
  from 2 trips first."); a plan nothing follows goes through `DAO.deleteRatePlan`.
- `attachable(tripId)` (gate `canManageTripLodging`): every live plan of every non-retired hotel that
  `covers` the trip's dates, by hotel then plan, each a `PlanChoice` labeled "Pansion: Hotel rate 2027
  (Jan 1 - Dec 31, 2027)". A plan already on the trip is INCLUDED (2026-10-26): a trip may take the same
  plan twice, as two options at two participant prices (a shared double and a single at the same hotel
  rate). Empty for a trip without dates: nothing can be said to cover them.
- `attachFormFor(tripId)` / `attach(AttachForm)` -- the picker's path -- plus `attach(tripId, accId, planId,
  priceFields)` and the unpriced shorthands `attach(tripId, "accId|planId")` and `attach(tripId, accId,
  planId)` (gate `canManageTripLodging`): refuses a retired plan, one that does not cover the trip's dates,
  and a negative price (`OfferSchedules.attachProblem`). It builds an `OfferForm` from
  `lodging.offerFormFor(tripId, null)` -- which carries the trip's dates as BOTH range and default stay --
  then sets the plan's name, room types, `tripEventId = NEW`, `form.ratePlanId`, the price fields
  (`applyAttachPrice`; `priceKnown` off leaves the option "needs participant pricing"), and the plan's
  `validFrom`/`validUntil` as the option's DATE RANGE where set, so each open end falls back to the trip's
  dates and the default stay stays the trip's. It LINKS the trip on the plan and saves the plan FIRST when
  the trip is not on it yet -- that write bumps the plan's version, and the offer must copy the version it
  will then be compared against, or it would read as drifted from birth -- then calls `lodging.saveOffer`,
  which, seeing `ratePlanId`, copies the cost side from the plan (`RatePlanCommands.planBehind(form)`) and
  takes the price from the form; the LODGING event comes through `resolveEvent` as for any option. A refused
  offer save undoes only the link THIS attach made (another option of the trip may still follow the plan),
  and the refusal lands on `AttachForm.problem` for the dialog.
- A linked offer edited on the Offers tab edits its PRICE like any option and re-copies its COST side from
  the plan on EVERY save (`saveOffer` calls `copyFromPlan` whenever the form carries `ratePlanId`); the
  trip's own terms -- name, event, minimum nights, dates, default stay, policy, cancellation fee -- save
  normally, and the dialog shows a note in place of the Hotel cost fieldset alone. A change of hotel is
  refused ("An option added from a rate plan stays at that hotel; detach it to move it."): the plan's cost is
  THAT hotel's quote. A form naming a plan that is not at that hotel, or is gone, is refused too ("The rate
  plan this option follows is not at that hotel; detach it."); on the row, a linked offer whose plan is gone
  reads "(deleted plan)" with drift (`linkOf`), because there is nothing left to follow.
- `detach(tripId, offerId)` (gate `canManageTripLodging`): clears `ratePlanId` / `ratePlanVersion` (the
  schedules are already the offer's own copy) and saves the offer; the trip leaves the plan's
  `linkedTripIds` only when no OTHER option of the trip still follows that plan. No recompute: the numbers
  do not move.
- `unlink(accId, planId, tripId)` / `unlinkAll(tripId, offers)`: `TripDeleteCommands` reads the trip's
  offers BEFORE `deleteLodgingForTrip`, then has every hotel forget the trip
  (`HotelUsage.forgetTripEverywhere`) and every plan those offers followed unlink it; both tolerate a hotel
  or plan that has meanwhile vanished. `AuditEventBuilder.TARGET_RATE_PLAN` names the audit target.
- Page: the hotel page's **Rate plans** tab (index 7, rendered on `lodging.canOpenLodgingAdmin()`; the
  bean's `rows` answers nothing to a caller who may not `canRead` the hotel): a table (name with a "Retired"
  tag and the notes, room types, dates, Hotel cost, Linked trips) that is the SAME for every reader -- there
  is no price column, so nothing is conditionally rendered and an operator's table has no header to leak
  through; New / Edit / Delete render on `requestScope.planCostEditor` (`ratePlans.canEditCost`); `planDlg`
  has ONE money fieldset, **Hotel cost** ("Hotel cost is known", its cost-extras editor `costDlg`'s row for
  row through the shared `HasCostExtras` interface, its Remove link naming `:form:rpCostPanel` absolutely),
  notes and the Retired checkbox; a refused save keeps the dialog open with the reason in `rpProblem`.
  `requestScope.plans` and `planCostEditor` are set in initPage for decode and again in a `beforeEncode` for
  render. The tab's intro line tells the reader where the price goes: "A trip's lodging admin attaches one
  as a lodging option and sets that trip's participant price on the option."
- REST (`LodgingResource`, no registration change): `POST accommodations/{acc}/rate-plans` (body: `name`,
  `roomTypeIds`, `validFrom`/`validUntil` as ISO dates, the cost block -- `costKnown` (default true),
  `costPricingModel`, `costNightlyPrice`, `costSupplement`, `costExtras[{label, basis, amount}]` -- and
  `notes`; NO price keys; answers `{id}`; gate `canEditCost`), `GET accommodations/{acc}/rate-plans` (`rows`,
  gate `hotel.canRead`), `POST trips/{t}/offers/from-plan {accId, planId}` plus the option's OPTIONAL
  participant price `priceKnown, pricingModel, nightlyPrice, singleSupplement` (dollars; priced when
  `priceKnown` is true, or when it is absent and any price key is present, else "needs participant
  pricing"; `attach(tripId, accId, planId, AttachForm)`; the same plan may be attached again; answers `{id,
  tripEventId}` by diffing the trip's offer ids before and after and matching `ratePlanId`) and `POST
  trips/{t}/offers/{id}/detach`; from the price/cost split, `PUT trips/{t}/offers/{id}/cost` (`setOfferCost`;
  `costKnown:false` clears the stated cost back to "not set", which blocks the invoice), `PUT
  trips/{t}/reservations/{id}/concessions/{price|cost}` (`{kind, amount, reason}`; a missing or "" kind
  clears; `price` needs `canManageTripLodging`, `cost` needs `canSetCost`; the option-cost PUT needs
  `canManageTripLodging` alone since 2026-10-26) and `POST
  accommodations/{acc}/operators {personId}` (the roster, for fixtures). `POST trips/{t}/offers` gained
  optional `priceKnown` (false saves "needs participant pricing") and the cost side -- `costPricingModel`,
  `costNightlyPrice`, `costSupplement`, `costExtras[{label, basis, amount}]`, stated as soon as any cost key
  is present; the legacy `nightlyPrice` keeps its meaning (missing = $0, still PRICED), so
  `WebFixtures.lodgingOffer` is untouched.
- `FakeData` seeds one plan on the Pansion, "Hotel rate 2027" (every room type, DATED 2027-01-01 to
  2027-12-31, cost PER_ROOM $50 + a FIXED "City tax" $12, no price -- a plan has none), under
  `FakeData.CFPW_RATE_PLAN_ID` (`a4c7e2f9-1b3d-4e8a-9f06-7d2c5b8e1a43`), mirrored in the webtests'
  `SeededIds`. Dated rather than open on purpose: a plan covering every trip would leave "no plan covers
  this trip's dates" untestable in the browser suite. It is deliberately NOT linked to the seeded offer,
  which states its own cost of $60 (equal to its price, so the demo invoice renders at $60 x 12 nights =
  $720, `AdminReportsPwIT.theRoomInvoiceRendersAtTheSeededCost`) rather than the plan's $50.

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
- From the 2026-10-25 round (plan Part 7): cost-side cancellation fees on the invoice; a PDF for
  billed-vs-cost (the page prints); removing the legacy JSON mirror and the deprecated setters ONE release
  later ([migrations/lodging-price-cost.md](migrations/lodging-price-cost.md)).

**Class split (2026-09-17, not a feature).** `LodgingCommands` reached the checkstyle 3000-line file limit,
so three pieces moved out: `LodgingItinerary` (the itinerary rows and the rooming list), `LodgingFloorMaps`
(floor-plan upload, move, remove and the saved regions) and `Countries` (the address autocomplete's fixed
list). The pages still bind to `#{lodging}`, which delegates -- the seam is for reading the code, not for the
EL -- so nothing on a page changed. `HotelCommands` is not part of that split: it is a new bean with a
different question (see "The hotel's inventory"). The 2026-10 round moved four more pieces out the same
way: `OfferSchedules` (the money half of the option and plan dialogs), `OfferRules` (the offer dialog's
refusals, 2026-10-26), `LodgingConcessions` (the two concession dialogs' bodies) and `HotelUsage` (the hotel
row's org and trip bookkeeping); `RatePlanCommands`
is, like `HotelCommands`, a new bean with a new question.

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

The 2026-10-25 round added `LodgingAccessCommandsTest` (the per-trip access object: in-play hotels, the
operator intersection, the memo, the naming rule), the converted hotel-manager cases in `LodgingCommandsTest`
(operator grants, the two-hotel refusal, `canAssignAt`), `HotelCommandsTest`'s Trips-tab case,
`PrivilegeCommandsTest`'s retired-role assertion, `KnownSettingsTest.everyDeclaredSettingIsInASection`,
the model tests `RateScheduleTest`, `CostExtraTest`, `ConcessionTest`, `RatePlanTest` and
`ReservationOfferTest`'s fold cases (legacy row reads priced at its old numbers, an explicit `priced:false`
stays unpriced, a `price` object outranks the mirror, a fresh offer is unpriced, a new row carries object +
flag + mirror), `CostExtrasTest`, `LodgingPricingTest`'s cost-side and concession cases plus the
byte-identical-notes guard, and the browser test `OperatorTripsPwIT` (a two-hotel trip: the operator of A
sees A only, `?acc=B` falls back, no "$" in the body, guest names visible; plus the invoice page admitting
an operator and its back button returning them to their hotel's Trips tab, the Trips tab showing one picked
trip with guest names and Room Prices but no lodging options, the option-cost write refused to an operator,
and an operator granting a complimentary stay, struck through in Room Price).
The price/cost and rate-plan phases added `RoomInvoiceReportTest`'s
seeded case split in two (`theSeededTripsInvoiceRendersAtTheSeededCost`,
`theSeededTripsBilledColumnMatchesItsLodgingBills`), its `anOptionWithoutACostBlocksTheWholeInvoice` and
`anOptionWithNoScheduleOnThisSideIsUnpricedOrBlocking` and its operator-filter cases,
`BilledVsCostReportTest` (`anOptionWithoutACostReadsNotSetAndCountsNothing` among them),
`RoomInvoicePdfTest`'s `everyHotelsPageSaysWhoseInvoiceItIs`,
`aContinuationSheetRepeatsTheInvoiceLineWithThePlainHotelName` and
`aBlockedInvoicePrintsItsRefusalInsteadOfSections`,
`RoomInvoicePdfServletTest.anOperatorGetsTheirHotelsPartAndAFinanceViewerGetsEveryHotel`,
`LodgingCommandsTest`'s `anUnpricedOptionTakesNoStaysUntilItIsPricedAndKeepsItsPriceWhileTheyStand`,
`aCostEditLeavesTheBillsAloneWhereAPriceEditRecomputesThem`, `anOperatorSetsTheCostAtTheirHotelAndNeverThePrice`
and `concessionsShrinkTheBillOnThePriceSideAndNothingOnTheCostSide`,
`RatePlanTest.aRowWithTheRetiredPriceKeyStillReads`, `RatePlanCommandsTest` (an operator states the cost and
every reader sees the same row; a lodging admin who does not operate the hotel reads its plans and edits
nothing; validation before the save; `attachCopiesThePlansCostAndTheLinkedOptionOwnsItsPrice`;
`attachWithPriceFieldsAndTheSamePlanTwice`; a save prunes a link no option follows; `unlinkAll`;
`attachableExcludesRetiredAndNonCoveringPlansButKeepsAttachedOnes`;
`copyFromPlanNeverAliasesThePlansSchedulesAndLeavesThePriceAlone`; the rolled-back link, the lost writes, an
option outliving its deleted plan), `RatePlanViewsTest` (a new form states a cost by default, a row carries
the hotel's side only, the one-value `PlanChoice`, the attach form's defaults),
`LodgingResourceTest.ratePlansAreCreatedListedAttachedAndDetachedOverTheApi`, the `HotelUsage` coverage in
`LodgingCommandsTest.savingAnOptionRecordsTheTripOnTheHotelAndDeletingTheLastOneForgetsIt`,
`HotelCommandsTest.aTripWithOnlyAnOptionAtTheHotelIsListedWithNoStays`, `TripDeleteCommandsTest` (the hotel
forgets a deleted trip) and `AccommodationTest`, and the browser tests `LodgingCostPwIT`
(`anOptionWithoutAParticipantPriceCannotBeReserved`; `costExtrasAreAddedAndRemovedInTheOfferDialog` -- the
2026-10-26 regression: a row added, a second removed, the first surviving both re-renders, the tax a line of
its own on the invoice; `theHotelCostShowsOnTheOffersTabAndDrivesTheInvoice`;
`aPriceDiscountReducesTheBillAndACostDiscountDoesNot`; `anOptionWithoutAHotelCostBlocksTheInvoice` -- the
trip page's button and dialog, the report page's refusal with no total and no PDF link, the servlet's 409,
and all of it back once the cost is set), `RatePlanPwIT`
(`anOperatorCreatesAPlanWithCostOnlyAndSeesNoPriceColumn`, `aPlanDialogAddsAndRemovesCostExtras`,
`anAdminAttachesAPlanPricesTheOptionAndDetaches`, `theOffersTabLeadsWithTheRatePlanButton`,
`attachingTheSamePlanTwiceMakesTwoLinkedOptions`, `anAttachedOptionTakesThePlanDatesAndTheTripStay`,
`aTripNoPlanCoversGetsAnExplanationInsteadOfAnEmptyPicker`, `theSeededPansionHasARatePlan`),
`OperatorTripsPwIT.anOperatorIsToldTheInvoiceWaitsOnTheirCost` and `AdminReportsPwIT`'s
`theRoomsCardLinksTheBilledVsCostReport`, `theBilledVsCostReportReadsTheSeededCostBesideTheBill` and
`theRoomInvoiceRendersAtTheSeededCost`.
