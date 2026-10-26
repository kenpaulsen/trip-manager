# Trip reports

Every report about one trip is reached from one place: the **Reports dashboard**,
`admin/reports/index.jsf?trip=<tripId>`. Before 2026-09 four report buttons sat in the Contacts page's table
header (Finances, Rooms, Events, Reports) and the "Reports" one opened a link table that did not list Events,
did not name the trip, carried no tab strip and had no way back. The header now carries Photos and Edit only.

## Navigation

- **In:** the trip tab strip (`WEB-INF/tripTabs.xhtml`) has a **Reports** tab, value `"reports"`. The strip
  renders on every trip page, so the dashboard is one click from anywhere in the trip. Trip-scoped admin
  deliberately stays out of the site-wide Admin menu (`WEB-INF/menu.xhtml`); that menu is for site-wide
  functions, and a trip's own pages are reached from the trip.
- **Out:** each report carries exactly ONE control back, a `p:linkButton id="reportsDone"` labelled "Reports"
  with a `pi pi-arrow-left` icon, a plain GET, never a postback. It is the org hub's `orgDone` idiom.
  `admin/tripEvents.xhtml` used to carry two "Back to Trip Contacts" buttons; both are gone.
  ONE exception, the Room Invoice, whose readers include people the dashboard refuses (it needs `tripView`
  or a finance role): there the back control is `reportsDone` only for a reader the dashboard admits, else
  `lodgingDone` ("Lodging", the trip's Lodging page) for a trip lodging admin, else `hotelDone` ("Hotel",
  `LodgingAccess.hotelTripsUrl(?acc=)`: the operator's hotel Trips tab on this trip). Before 2026-10-26 an
  operator's "Reports" click ended on "needs the Viewer role".
- A report page that includes `tripTabs` sets `requestScope.activeTab = "reports";` so the strip highlights
  where the reader is. (`exportTrip` used to set `"9"`, a value the strip never defined, and `tripAges` and
  `tsv-passport-info` set `"4"`, which lit up Registrations.)
- The back link carries `styleClass="noPrint"` (`resources/css/trip.css`) rather than a `rendered` gate: three
  cards open a `?print=true` view, and that view is read on screen before it is printed, so the link has to be
  there and has to vanish from the paper.

## Admission

The dashboard gates itself in `initPage` rather than through `defaultAuth`, and this is the reason: admission
is the UNION of three privileges, and `PrivilegeCommands.isAuthorized` can be told about only one
(`viewScope.reqPriv`). The old page named `tripView`, so a finance-only viewer was bounced off a page that had
two cards for them.

Admitted: `showAll` (site admin), `tripView@trip`, `tripFinView@trip`, the global `viewFinances`, or (since
the budget, 2026-10-26) `tripMgr@trip` or `tripFinAdmin@trip`, who edit the Budget and may hold nothing else on
the trip. The page reads that last pair through `budget.canViewBudget` (`seeBudget`). That is the same union
that shows the tab, so navigation and enforcement cannot disagree. Opening the dashboard
grants nothing: every card is separately `rendered=` on the gate of the page it opens.

| Card | Opens | Gate |
|------|-------|------|
| Roster | `admin/reports/tripPilgrims.jsf?print=true&trip=` | `tripViewer` |
| Rooms: Room List | `admin/reports/tripRooms.jsf?print=true&trip=` | `tripViewer` |
| Rooms: Room Invoice | `admin/reports/roomInvoice.jsf?trip=` | `seeFinances` (the page itself also admits a Hotel Operator of a hotel in play, for their hotel's part) |
| Rooms: Billed vs Cost | `admin/reports/billedVsCost.jsf?trip=` | `seeFinances` |
| Events | `admin/tripEvents.jsf?id=` (that page reads `?id=`, not `?trip=`) | `tripViewer` |
| Ground Transportation | `admin/reports/groundTransport.jsf?trip=` | `tripViewer` |
| Export | `admin/reports/exportTrip.jsf?trip=` | `tripViewer` |
| Finances | `trip/tripTransactions.jsf?trip=` | `seeFinances` |
| Payments | `admin/reports/tripPayments.jsf?print=true&trip=` | `seeFinances` |
| Budget | `admin/reports/budget.jsf?trip=` (number: the overall surplus, or "No budget yet") | `seeBudget` = `budget.canViewBudget` (see `docs/budget.md`) |

`tripViewer` is `showAll || tripView@trip`; `seeFinances` is `viewFinances || tripFinView@trip`. Both are
computed into `requestScope` every request, so a grant made while the page is open takes effect on the next
load. The **Rooms** card carries three buttons stacked (`.hub-stack`), each gated like its own page, and renders
for either holder. The cards use the shared `.hub-grid` / `.hub-head` / `.hub-num` / `.hub-sub` vocabulary in
`resources/css/trip.css`, which the organization Dashboard (`admin/orgSettings.xhtml`) uses as well; it lives
there so the two hubs cannot drift apart.

## The rooming report

`admin/reports/tripRooms.xhtml` prints **one page per accommodation**, in the order the trip reaches them,
with everyone still waiting for a bed on a last page of their own. Rows come from
`ReportCommands.roomingPages(tripId)`, which groups what `lodging.roomingList` returns.

**Every count is of PEOPLE.** The underlying list emits a row per occupant PER STAY, so a trip using two
hotels, or anyone who changes rooms part-way, has more rows than travellers. Counting rows is what made the
old single-page header claim 58 people for a trip of 46. Each page heading names its hotel and its distinct
head count; when the stay count differs it says so too ("Sep 21 - Oct 1, 14 stays"), so a reader who counts
the lines and gets a different number sees why. Above the pages, one line gives the trip's own total and how
many of those have a reservation.

The page breaks are CSS, not a generated PDF: `.rmPage + .rmPage` takes `break-before: page` under
`@media print`, which every current browser honors from its own print dialog. The rule is on the adjacent
sibling rather than `:first-of-type` so the report never opens with a blank sheet. Two more print rules earn
their place: `break-inside: avoid` on a row, so a person is never torn from their room across a fold, and
`display: table-header-group` on the head, so a list long enough to spill carries its column headings onto
the next sheet.

Banding alternates per ROOM rather than per row, so the people sharing a room read as one block; it is
decided in Java and rides the line as `stripe`, because the old page worked it out in a hidden column with a
`beforeEncode` that compared against the previous row.

Guarded by `ReportCommandsTest` (grouping, ordering, people-not-stays, the banding, the window) and
`RoomingReportPwIT`, which seeds a trip with two hotels plus somebody holding two stays and asserts the pages,
the counts and the print rule.

## The room invoice

`admin/reports/roomInvoice.xhtml` is **what the hotels charge**: the COST side of each lodging option, one
section per accommodation (earliest arrival first), one GROUP per lodging option, a subtotal per hotel and the
grand total. Every line reads **quantity x nights x rate = amount**, and that arithmetic is the owner's
requirement (2026-09-29): the first version grouped by ROOM and showed room-nights, which were correct but
could not be multiplied back to the money once a per-person option and its single supplement shared a line.

Until 2026-10-25 one schedule on the option drove both the participants' bills and this invoice, and "the
invoice equals the bills" was a tested invariant. The price/cost split (`docs/lodging.md`, "Price vs cost")
retired it on purpose: `ReservationOffer.price` is what a participant pays, `ReservationOffer.cost` is what
the hotel charges, and the invoice reads the cost side STRICTLY (`RoomInvoiceBuilder.scheduleOf(COST, offer)`
is `offer.getCost()`; since 2026-10-26 nothing is ever assumed from the price). The participants' side lives
in **Billed vs Cost** below.

- **A per-person option bills GUESTS.** Its lodging lines count guests; the **single supplement is a line of
  its own** (the guests who were alone, for the nights they were alone, at the supplement's rate). The
  WAIVED supplement is a price-side concession: the participant is spared it, the hotel still charges it, so
  it stays on this invoice.
- **A per-room option bills ROOMS**, with the guests who slept in those rooms as a note. A room shared by a
  late arriver counts every night anybody is in it; a reservation not yet placed in a room is a room of its
  own, because that is how it is billed.
- **A new line whenever the arithmetic would break**: guests or rooms with different nights, and nights the
  schedule prices differently (per-night overrides), each get their own line.
- **Room types never split a line** (the rate does not depend on them); the option's heading notes the mix,
  "3 Double rooms, 1 Single room", plus any reservations not yet placed.
- **Cost extras are lines of their own** (`LineKind.EXTRA`, COST side only): each of the option's
  `costExtras` (city tax, resort fee, cleaning fee, booking fee, coupon) is one line whose item is its label,
  quantity is the count its basis selects (`CostExtras.quantity`: guest-nights = occupants x nights over the
  option's stays, room-nights = distinct room x night pairs with an unplaced stay counting as its own room,
  stays = stays with at least one night, FIXED = 1), nights 1 and rate the signed unit amount, so the product
  still holds and a coupon comes out negative. Extras are never attributed to a person.
- **A cost concession is a negative line** (`LineKind.CONCESSION`): one per stay that carries a
  `costConcession`, quantity 1, no nights and no rate, amount = minus what `LodgingPricing` forgave on that
  stay's lines, and a note "Ada, Bob: 10% discount -- reason". The per-person rows for that stay are NET,
  their rate suffixed "(10% discount)". A PRICE concession is invisible here: it changes the bills, not the
  hotel's charge.
- **No cost, no invoice.** An option with active stays and no `cost` REFUSES the whole invoice
  (`RoomInvoice.blocked`; `missingCost` names the options hotel-qualified, "Double room (Pansion)"; sections
  empty, totals zero) -- owner decision 2026-10-26, replacing the day-older "invoice at the participant price
  and flag it": a line nobody agreed with the hotel must not reach the hotel looking agreed.
  `ReportCommands.invoiceReady(tripId, access)` / `invoiceBlockedMessage(tripId, access)` answer the question
  for ONE caller's scope -- an operator who is not internal on the trip is judged on their hotels' options
  alone, everyone else on the whole trip -- and `ReportCommands.blockedMessage(missing, operator)` words it
  for the reader: "No hotel cost is set for X and Y. Set the cost on the trip's Offers tab (Edit the option,
  tick 'Hotel cost is known') or ask the hotel's operator to set it on the hotel's Trips tab." for internal
  readers, "Set the cost on this trip's options on your hotel's Trips tab." for an operator. The page shows
  `riBlocked` with that text and renders no table, no total and no Download PDF; the servlet answers
  `409 Conflict` as `text/plain`; the trip's Lodging page and the hotel's Trips tab swap their "Hotel
  invoice" link for a `hotelInvoiceBlocked` button opening `invoiceBlockedDlg`
  (`LodgingCommands.invoiceReady/invoiceBlockedMessage` delegate to the report bean). A trip with no stays
  is ready: there is nothing to refuse.
- **The money is the pricing engine's.** The rules are `LodgingPricing.lines(Side.COST, ...)`'s (occupancy
  per room per night across every stay on it, the overrides, the concession), so the total equals what that
  engine says the hotel charges. Cancelled stays and their fees are left out; a stay whose option was
  deleted, or whose option has no schedule on this side, is counted as unpriced, and the page says so.

### Who may open it

Finance viewers (`seeFinances`: site admin, the global `viewFinances`, `tripFinView@trip`), the trip's own
lodging admins (`lodgingAccess.forTrip(tripId).internal`: `tripMgr`, the org's or the global `lodgingAdmin`,
since the bill is the hotel's COST side, which is exactly what they reconcile with the hotel) and, since the
2026-10-25 roles round, a **Hotel Operator** of a hotel in play on the trip (`.operatorHere`). The page computes
`requestScope.invoiceInternal = seeFinances || access.internal` and asks
`reports.roomInvoiceFor(tripId, lodgingAccess.forTrip(tripId), invoiceInternal)`:

- `finance` or `access.internal` (the trip's own lodging people): every section.
- an operator: the invoice is REBUILT over the stays at the hotels they operate on this trip
  (`ReportCommands.staysAt`), so its counts, its people table, its total AND its `missingCost` refusal are
  theirs alone -- an uncosted option at the trip's OTHER hotel never blocks their part. They see trip title,
  dates and guest names at their hotel (the documented tenancy extension) and never a participant price.
- anyone else: an empty invoice (the page has already redirected them).

The caller passes `finance` rather than the bean deciding it, because the finance privileges are not lodging
privileges and `LodgingAccess` deliberately knows nothing about them. The operator reaches the page from the
hotel's Trips tab and the trip's Lodging page ("Hotel invoice"), not from the Reports dashboard.

### Whose invoice it is

Under each hotel's name the page prints `.riInvoiceTo` and the PDF the same sentence: "Invoice to <org> for
<trip> room charges at <hotel>." (`RoomInvoiceHeading.invoiceLine`; the record trims nulls to "" and drops the
"to <org>" for a trip that has lost its organization, so nothing ever prints "null"). The trip's organization
is the hotel's customer, and a sheet handed to a hotel has to say so on the sheet. The page reads
`theTrip.organization.name`; the servlet resolves the same through `trip.getOrganization()`
(`RoomInvoicePdfServlet.orgNameOf`). This line replaced (2026-10-26) the heading "Hotel cost: what the hotels
charge for the trip's rooms. Participant billing is on the Finances pages.", which talked to the trip's own
people about their books on a document whose reader is the hotel.

### Charges per person

Under each hotel's option table is a second table, **Charges per person** (Person, Dates, Nights, Rate,
Amount, then a Total), added 2026-09-29 at the owner's request with the option table left unchanged.
Decisions made building it:

- **A row IS one person's cost line for one stay.** Each row is one occupant's `LodgingPricing.lines(COST,
  ...)` line (the same call, against the same room), net of any cost concession, so a person with two stays
  at the hotel, a room switch say, has two rows, by name then date. The table's total therefore equals the
  option table's subtotal LESS the hotel's extras, which belong to nobody; the page and the PDF say so under
  the total ("Plus $X in hotel extras ... $Y in all"), and `InvoiceSection.extrasCents` is the difference.
  Every `RoomInvoiceReportTest` scenario asserts `peopleCents + extrasCents == cents`.
- **Rate is what one night costs THAT person.** Per person: `$50.00`, plus `+ $10.00 single supplement`
  when they were alone, with `(3 nights)` when that was not every night. Per room it is their SHARE of the
  room: `$60.00 room, alone`, `$30.00 (share of a $60.00 room)`, or `Share of a $60.00 room, varies by
  night` when someone arrived or left part-way (the rate cannot be one number then). Per-night overrides read
  `Varies by night`. A concession appends its label: `$30.00 (share of a $60.00 room) (10% discount)`.
- **Names** are "Preferred Last" (`LodgingCommands.displayName`). The lodging option is shown under the
  name only when the hotel has more than one option, so a single-option hotel's table stays clean.
- **A room holding two options agrees too (since 2026-10-26).** A room's nights are priced under ONE lodging
  option -- every path that puts a stay into a room refuses a second option outright (`docs/lodging.md`, "One
  lodging option per room per night") -- so a mixed room is old data. Where one exists, `LodgingPricing`
  splits a per-room rate only among the occupants under the SAME option (`underOption`), so each stay pays its
  own option's whole room, exactly what the option table charges; `RoomInvoiceReportTest
  .aRoomHoldingTwoOptionsChargesEachItsWholeRoomInBothTables` guards the equality. The older "known edge" where
  the per-person table split the rate across the options and the totals could differ is gone.

In the PDF the per-person table follows the option table on the same sheet (no page break of its own), each
accommodation still starting a new page; a table that spills repeats ITS OWN column headings on the
continuation sheet, and a cell too long for its column is set in smaller type rather than overrunning.

Rows come from `ReportCommands.roomInvoice(tripId)` (= `RoomInvoiceBuilder.build(Side.COST, ...)`); the
package-private side-aware overload over reservations and lookups is what `RoomInvoiceReportTest` drives,
including the owner's own example. `RoomInvoiceBuilder` holds the tally (`OfferTally`), the line arithmetic
and the rate wording; `ReportCommands` keeps the entry points and the row types.

### The PDF download

The report page's **Download PDF** button is a plain GET to `/reports/room-invoice?trip=<id>`
(`web/RoomInvoicePdfServlet`, mapped in the live `web.xml`), which answers the same `RoomInvoice` drawn by
`action/RoomInvoicePdf` with **each accommodation starting a page of its own**, so one hotel's part can be sent
to that hotel alone. A hotel that outruns a sheet continues with "(continued)" and its column headings
repeated, the invoice line under the plain hotel name; the trip total closes the last page, and every page
carries "Page n of m". Extra rows read like any other line, a concession row has a blank nights and rate, a
negative amount and its note in prose beneath. A BLOCKED invoice never becomes a PDF: the servlet answers 409
first, and the renderer, handed one anyway, prints the refusal instead of sections
(`RoomInvoicePdfTest.aBlockedInvoicePrintsItsRefusalInsteadOfSections`).

- **The URL must not end in `.pdf`.** `PdfRedirectFilter` (mapped to `/*`) answers every such request in
  production with a 301 to the media CDN, and stands aside locally, so a `.pdf` URL passes every local test and
  breaks only once deployed. The file name travels in `Content-Disposition` instead; `AdminReportsPwIT`
  asserts the page never links a `.pdf`.
- **Gate:** internal = a finance viewer (site admin, the global `viewFinances`, or `tripFinView@trip`, through
  `Caller.of(session)`) or the trip's own lodging admin (`access.isInternal()`); otherwise an operator of a
  hotel in play (`new LodgingAccessCommands(() -> caller).forTrip(tripId).isOperatorHere()`; built on the
  servlet's own caller because the CDI bean's default caller source reads the FacesContext a servlet does not
  have). The servlet passes `roomInvoiceFor(tripId, access, internal)`, answers a blocked invoice with
  `409 Conflict` (`text/plain`, `ReportCommands.blockedMessage(missing, !internal)`, so an operator reads
  their own wording) and otherwise renders with the same flag, so an operator's PDF is their hotel's part
  alone. A visitor goes to login and back, a signed-in refusal is a 403. `Cache-Control: private, no-store`.
- **`RoomInvoicePdf.render(RoomInvoiceHeading, invoice, internal)`** is the entry point
  (`RoomInvoiceHeading(tripTitle, orgName)`; a null heading renders as blanks). The two-arg overload is gone;
  `internal` stays on the signature although nothing is drawn differently for an internal reader since the
  cost-assumed note went, so any future internal-only note needs an explicit `true`, never a forgotten
  argument.
- **Font:** Liberation Sans is embedded (subset), from OpenPDF's `openpdf-fonts-extra` jar (SIL OFL; OpenPDF
  itself is excluded). The PDF standard fonts are WinAnsi-only and cannot draw hotel and guest names such as
  Dragi&#263;evi&#263;. Drawing is PDFBox 3, pure Java.

## Billed vs cost

`admin/reports/billedVsCost.xhtml` (2026-10-25) puts the participants' lodging bills beside what the hotels
charge for the same stays: per hotel a table of lodging options (Lodging option, Billed, Cost, Difference)
and a table of people (Person, Lodging option, Dates, Nights, Billed, Cost, Difference), hotel subtotals and
a grand total line (`#bvcTotal`, class `bvcTotal`). Difference = Billed - Cost, positive when the participants
cover the hotel, negative amounts in `.bvcNeg`.

Rows come from `ReportCommands.billedVsCost(tripId)`: the PRICE build and the COST build of
`RoomInvoiceBuilder` zipped by accommodation id, by `offerId` and by `(reservationId, personId)`
(`RoomInvoiceBuilder.compare`). What lands where:

- an option row's **Billed** is its PRICE group total (price concessions already taken off); its **Cost** is
    its COST group total, extras and cost concessions included. Unlike the invoice, an option WITHOUT a cost
  does not block this report -- showing exactly such gaps is what it is for: the COST build runs LENIENT
  (`RoomInvoiceBuilder.build(..., strict = false)`), the option is left out of the cost side, its row's
  `costAssumed` flag (the name survives; it now means "no cost on file") makes the Cost cell read "not set"
  and the Difference "--", and `#bvcMissing` counts them ("2 lodging options have no hotel cost yet. Set it
  on the trip's Offers tab or ask the hotel operator.", from `missingCost` / `missingCostNames`).
- a person row is NET on both sides. Extras belong to no person, so the people table need not add up to the
  option table; the option table is the one that carries them.
- a row present on one side only shows `$0.00` on the other (an option with a cost and no price bills
  nobody); an option with neither schedule has no row at all.

Money on both sides, so the page is internal only (`seeFinances`, the dashboard's gate, checked in `initPage`
like the invoice); a Hotel Operator has no door here. It carries the usual `reportsDone` link, a "Room
Invoice" link and Print; no PDF this round (the page prints). The dashboard's Rooms card opens it with
`toBilledVsCost`.

## The Ground Transportation report

`admin/reports/groundTransport.xhtml` lists every `TripEvent.Type.GROUND` leg on the trip in departure order
(undated legs last), with the fields the Add Ground Transport dialog collects. Each leg is TWO rows: Time,
Route and Carrier across three centered columns, then the riders spanning the full width beneath, with no rule
between the pair and one under each leg. That shape is why the page builds its own table instead of using a
`p:dataTable`, which cannot emit a second, spanning row per record.

The Time column names the day once, `Sep 19, 2026`, or names both when a leg is still going after midnight,
`Sep 21, 2026 -> Sep 22, 2026`; the clock times sit beneath it. That span replaces a "next day" marker beside
the arrival. The derived duration sits under the route. Those strings are composed in
`ReportCommands` rather than by page converters, because the choice between one day and two is a decision, and
decisions in a page are what neither a unit test nor a reviewer can see.

Rows come from `ReportCommands.groundRows(tripId)` (`#{reports}`), scalars rebuilt each request into
`requestScope`; nothing binds into them, so there is no row identity to protect. The dashboard card's metric is
`reports.groundLegCount(tripId)`, which counts legs without resolving a single person.

Two behaviors worth knowing:

- **A legacy leg degrades, it does not get parsed.** A leg written by the bespoke editor stores its parts in
  `TripEvent.details` (from, to, carrier) and those are the columns. A leg saved before that editor stores
  none, because the title and notes are the composed rendering, and parsing them apart was rejected (notes get
  hand-edited). Such a row has `composed == false` and shows its title and notes instead.
- **An unknown rider is counted but not named.** The count follows the event's own participant list; a person
  id that no longer resolves simply drops out of the names.

`TripEventComposer.elapsed` is package-private, which is why `ReportCommands` lives in
`org.paulsens.trip.action`.

## What guards this

- `ReportCommandsTest` (unit): ordering, the legacy fallback, the name format and its order, the head count,
  the overnight flag, the derived duration, non-GROUND exclusion, the seeded legs, and the rooming pages.
- `RoomInvoicePdfTest` / `RoomInvoicePdfServletTest` (unit): a page per hotel, continuation sheets, the
  totals and footers read back out of the PDF, extra and concession rows, every hotel's page saying whose
  invoice it is (and the continuation sheet repeating it under the plain hotel name), a blocked invoice
  printing its refusal instead of sections; the servlet's gate (a finance viewer sees every hotel, an operator
  is admitted to their hotel's part, a stranger gets 403), login bounce, 404s and headers.
- `RoomInvoiceReportTest` (unit): per-option groups, the separate supplement line, lines split by stay and by
  nightly price, per-room guest notes, unplaced/cancelled/unpriced stays, the waiver on the price side only,
  an option invoiced at its own cost and never its price, an option without a cost blocking the whole
  invoice (`anOptionWithoutACostBlocksTheWholeInvoice`, `anOptionWithNoScheduleOnThisSideIsUnpricedOrBlocking`),
  every extra basis multiplying out, a cost concession as a negative line and a net person row, a price
  concession invisible at cost, the operator filter (`staysAt`, `roomInvoiceFor`), that
  `peopleCents + extrasCents == cents` everywhere, and the seeded trip: its invoice renders at the seeded
  cost, and its billed column equals the ledger's `Lodging` Bill rows.
- `BilledVsCostReportTest` (unit): the difference per option and per person, extras in the cost column only,
  an option without a cost reading "not set" and counting nothing, each concession on its own side, a
  one-sided row showing zero, hotels as sections, the empty and unknown trip.
- `AdminReportsPwIT`: the dashboard's cards and their hrefs, the room invoice over the fixture
  (`theRoomInvoiceRendersAtTheSeededCost`, $720.00 at the seeded $60 cost), Billed vs Cost beside the bill,
  the PDF download, the ground report's rendered rows, and the one-way-back rule on every report.
  `LodgingCostPwIT.anOptionWithoutAHotelCostBlocksTheInvoice` and
  `OperatorTripsPwIT.anOperatorIsToldTheInvoiceWaitsOnTheirCost` cover the refusal end to end: the trip
  page's button and dialog, the report page with no total and no PDF link, the servlet's 409, and the
  internal vs operator wording.
- `ReportsDashboardPwIT`: the per-privilege card sets (admin, finance viewer, trip viewer), the bounce for
  someone holding nothing, the active tab, the back link, and the empty state.
- `TripContactsPagePwIT`: the Contacts header carries Photos and Edit only, and the Reports tab reaches the
  dashboard carrying the trip id.
- `AuthGatePwIT`: both new URLs bounce an anonymous visitor with a 302, never a 500.

`FakeData` seeds FAKE_TRIP with three ground legs: one editor-composed (Split to Medjugorje, Globtour bus,
three riders), one that runs past midnight so both date shapes render, and one legacy-shaped with no stored
parts. All three paths are exercised locally and in the browser tests.
