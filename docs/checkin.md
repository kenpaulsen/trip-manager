# Tickets and event check-in — read before touching tickets, check-in, counts, batch badges or their API

Phase 3 of conference support (`docs/conference.md`). A CONFIRMED registration carries a ticket: an unguessable
bearer code (`security/TicketCodes`, Phase 0). The ticket page shows it with a QR, staff look people up or scan the
QR and stamp the registration checked in, the check-in page counts arrivals by registration type and by day, the
batch badge page prints everyone's name badge with their QR, and the iOS app does the same through four endpoints
on `RegistrationsResource`. Nothing here adds a table, an entity or a status: it is all fields on `Registration`
(`ticketCode`, `checkedInAt`, `checkedInBy`, `badgePrintedAt`) read and written by ONE bean, `CheckinCommands`
(`#{checkin}`), which the pages and the REST resource both call.

This replaces the mir2026 check-in: QR images from an external service, a confirmation page that exposed a
person's answers to anyone holding their person id, and a "Paid" column that summed the person's whole ledger.

## Who may do what

| Gate (`CheckinCommands`) | Who | Used by |
|---|---|---|
| `canCheckIn` | site admin, `tripMgr@trip`, `checkinStaff@trip` | lookup, check in, the focus panel, `ticketFor`, the scan endpoints |
| `canClearCheckIn` | site admin, `tripMgr@trip` | Clear check-in (staff scan; they never edit) |
| `canViewCounts` | `canCheckIn`, plus the Reports dashboard union: `tripView`, `tripFinView`, `tripFinAdmin`, global `viewFinances` | the page itself, the Check-in tab, the dashboard card, `GET counts` |
| `canSeeMoney` | the dashboard's finance union: site admin, `viewFinances`, `tripFinView@trip` | the money row, `counts.money` |
| `canPrintBadges` | `canCheckIn` or `tripView@trip` | the batch badges and "Mark printed" |

Every gate answers false for a caller who is not signed in BEFORE it reads anything (no trip read, no session
touch), so the anonymous ticket page and the tab strip's anonymous build may ask them freely. All are false for an
unknown trip or one this site does not serve (`TripCommands.getTrip`'s rule).

`checkinStaff` is a trip role (the editor's role picker; an org with an allow-list must tick it). A Check-in Staff
member is usually not on the roster, so their Trips menu does not list the event: the Admin menu lists
"Check-in: {title}" for every ACTIVE trip where they hold the role explicitly (`staffTrips`; a site admin does not
get an entry per trip).

## Registration type and days

Check-in does not wait for priced checkout. A registration's TYPE is its stored `registrationType` (checkout will
write it from the price breakdown) or else the label of its answer to the trip's **type question**
(`RegistrationOption.typeQuestion`: the first SHOWN, required, pick-one question with a priced choice, Phase 0's
definition). Its DAYS are that choice's `days` (ISO dates), or every day of the trip when the choice names none.
`CheckinCommands.registrationType` / `daysOf` are the one spelling; the counts, the rows, the badges, the export and
the API all read them.

## The ticket page: `trip/ticket.jsf?trip=&t=`

The link the confirmation emails carry and the URL inside the QR (`SiteUrls.ticketUrl`: the trip's org's own site
when it has a subdomain, else `reg.mail.baseUrl`, the chat-invite rule `SiteUrls.baseUrlForTrip`). The checkout
confirmation links each confirmed person's code (`PaymentMailer.ticketLink`); the approval email's `{{ticketBlock}}`
does the same on a conference and is empty on a pilgrimage. Whoever holds the link may open it, so:

- The view is TRANSIENT for everyone (`stateless=true`) and the page touches no viewScope: `checkin.ticketView`
  resolves everything into `requestScope.tk`, and every read names `requestScope` (a bare-name read of a missing
  value probes the view map, which mints a session). `AnonymousFootprintPwIT` and `CheckinPwIT` assert no
  `JSESSIONID`.
- The QR is inline SVG rendered in Java (`util/TicketQr`: okapibarcode `QrCode`, ECC M, `SvgRenderer`, given a
  `viewBox` so CSS can size it). Never `p:barcode`: it streams the image through a session-backed dynamic-resource
  mapping.
- Levels, decided in Java: **INVALID** (no such ticket on this trip, a prefix, or a cancelled ticket shown to anyone
  but its owner): a 200 notice. **MINIMAL** (anyone): event, dates, name, type, the QR and the grouped code, plus a
  "Staff sign in" link back here. **OWNER** (the person or their family manager, `PersonCommands.canAccessUserId`):
  also status, checked in or not, the shown questions' answers by label, and paid/due on THIS trip, with Print.
  **STAFF** (`canCheckIn`): redirected to `/admin/checkin.jsf?trip=&t=<normalized code>`, so one scan lands staff on
  the controls.
- Never an email, a cell number or a person id on the page.

## The check-in page: `admin/checkin.jsf?trip=[&t=][&id=]`

Self-gated on `canViewCounts` (the dashboard's reason: `defaultAuth`'s single `reqPriv` cannot name a union). The
view holds the trip id, the search text and the focused person id, all scalars; registrations resolve per request
(`focus` and `counts` are memoized per request and forgotten by a write in the same request, so the render after an
action shows it).

- **Find a person** (`canCheckIn`): the search box posts on `keyup` with a 400 ms delay; `lookup` matches a whole
  ticket code EXACTLY (never a prefix), else every whitespace token must appear in the first, last or preferred
  name or the email; CONFIRMED and PENDING, at most 50, by name. Rows are `RegistrationCommands.RegRow` (the
  registrations table's scalar row, plus `name`, `registrationType`, `checkedInAt`; never the ticket). A row's
  "Check in" is an ajax button carrying the person in an `f:param`; picking a name is a plain GET (`?id=`).
- **Buttons hide by `style`, never by a state-dependent `rendered=`.** `rendered` is evaluated again when the click
  DECODES: with `rendered="#{!checkedIn}"`, a person another door checked in meanwhile made the click vanish with no
  message. The Check in and Clear buttons stay in the tree (gated only on status and role) and are hidden by style,
  so the server answers "already checked in" (`CheckinPwIT.aSecondCheckInIsToldAlreadyAndKeepsTheFirstStamp`). Each
  names `:form:growl` in its `update`: the template growl does not refresh itself on these postbacks.
- **Focus panel**: name, status, type, registered, the stamp, paid and due on this trip, the PICK answers
  (`staffAnswers`: admission, add-ons, sizes; never free text, where medical notes live) and attestations; Check in,
  Clear (managers), and a link to the ticket (minting it for an older row, `ticketFor`).
- **Scanned tickets** arrive as `?t=`: `personForTicket` focuses its person, or the page says the ticket is not for
  this event.
- **Camera**: `resources/trip-js/checkin.js` uses the browser's own `BarcodeDetector` and shows "Scan ticket" only
  where it exists (no vendored scanner; the iOS app is the primary scanner). A QR is untrusted input:
  `TicketScan.parse` accepts only an http(s) URL on this page's origin or the trip's ticket origin, path
  `/trip/ticket.jsf`, with a code-shaped `t`, and the script then loads THIS page's check-in URL for THIS page's
  trip with that code. It never navigates to the scanned URL. (The ticket origin is accepted as well as the page's
  own because tickets live on the org's site while staff may work from the shared one.)
- **Counts** (`canViewCounts`): "37 of 120 checked in", registered / confirmed / pending, by type and by day, on a
  plain `p:poll` every 20 s (no jsft event on it: a command event on `p:poll` fails the page build and surfaces as a
  silent redirect home). The money row (`canSeeMoney`) is NOT polled: it reads one ledger per confirmed person. The
  "Not yet checked in" list follows.

## Checking in

`checkInOutcome(tripId, personId)` reads the registration FRESH and answers a coded `Result` (the `*Outcome`
pattern; `checkIn` is the growling page wrapper): `NOT_ALLOWED`, `NOT_FOUND` (no row, or NOT_REGISTERED),
`NOT_CONFIRMED` (pending), `ALREADY_CHECKED_IN` (an existing stamp is NEVER overwritten: the second scan is told
when and by whom), else it stamps `checkedInAt = now` (to the second) and `checkedInBy`, applies `withTicket` (an
older confirmed row gets its ticket on the same save), and audits with the existing `AuditAction.REGISTRATION`
("checked in"). `clearCheckInOutcome` is the managers' undo (`CLEARED`, idempotent `NOT_CHECKED_IN`), audited
"cleared the check-in". One check-in per registration (owner decision): a multi-day event counts a person on every
day their type covers once they have arrived.

Two staff scanning the same person in the same second can both pass the fresh read; the later write then carries
its own stamp. The window is one DynamoDB round trip and the result is still "checked in", so there is no
conditional write.

## Money: the trip balance

`tripBalance(tripId, personId)` sums ONLY the person's ledger rows bound `TRANSACTION -> TRIP` to this trip (what
`PaymentRecorder` and the bill writers bind): payments are paid, everything else is billed, due = billed - paid when
positive. Never `TransactionsCommands.getBalance(userId)`, which mixes every trip and donation the person ever had.
Shown to staff in the focus panel, to the owner on their ticket, and summed over the confirmed people in the money
row. `CheckinCommandsTest.theTripBalanceIgnoresRowsNotBoundToTheTrip` pins it.

## Batch badges: `trip/itinerary-badge.jsf?all=true[&after=][&unprinted=true]`

The single-person itinerary badge page gained a batch mode, gated by `canPrintBadges` in its initPage (it leaves
`reqRole` unset on that branch so `defaultAuth` admits). Every CONFIRMED person's badge, by last name: the page's
badge picture, preferred name large, last name, registration type, the event, and the ticket QR (minting a missing
ticket). Six per US-letter sheet: 3.75 x 3.3 in inline blocks with `break-inside: avoid` (page breaks fall between
lines of inline blocks; browsers ignore break rules on grid items) and an `@page` of `letter portrait` in batch mode
only. `after=` (an ISO date or date-time: registered after it) is the mir2026 badge cutoff made a filter;
`unprinted=true` leaves out badges already printed.

The person ids are frozen ONCE into `viewScope.badgeIds` (scalars), so "Mark these N as printed" stamps
`badgePrintedAt` on exactly the sheet that was printed, whatever registered since. The Reports dashboard's Badges
card shows "N not yet printed" and opens either view.

## The export

`admin/reports/exportTrip.jsf` builds its rows in Java now (`checkin.exportRow`): the Options cell, the age and the
new optional columns Registration-Type, Ticket (grouped), Checked-In, Registered, Status, and one per typed (pick)
question by label (`Q: <shortDesc>` in the column picker). The default columns and their cells are byte-for-byte
what the EL built. `?pending=true` (the "Include pending registrations" switch) adds everyone with a PENDING
registration who is not on the roster yet; off by default (roster only). The checkout columns Total (the stored
price), Discount-Code and Attestation ("choice: statement", joined by "; ") read the row's stored breakdown and
attestations, blank for a registration no checkout priced.

## Navigation

- The trip tab strip's **Check-in** tab (after Reports) for a CONFERENCE and `canViewCounts`. Like Registrations it
  does not set `fullTabs`: Check-in Staff with no other role get exactly this tab. A pilgrimage gets no tab (the
  page works for any trip; the tab is where conferences need it).
- The Reports dashboard's **Check-in** card (the headline) and **Badges** card, conferences only.
- The Admin menu's "Check-in: {title}" entries (above).

## REST

On the existing `RegistrationsResource` (`docs/api-contract.md` "Tickets and check-in"): `GET lookup?q=`,
`GET ticket/{code}`, `POST`/`DELETE {personId}/checkin` (409 `ALREADY_CHECKED_IN` / `NOT_CONFIRMED`), `GET counts`
(`CheckinCountsDto`). `RegistrationDto` carries `ticketCode`, an absolute `ticketUrl`, `checkedInAt`, `checkedInBy`,
`registrationType` and (check-in responses) `name`; the roster listing and the lookup strip the ticket, a bearer
secret. The single `GET {personId}` includes the owner's ticket so the app can show its QR.

## Tests

`CheckinCommandsTest` (the gate matrix per caller kind including anonymous; lookup exactness and the cap; outcomes
including the kept first stamp and the lazy ticket; counts arithmetic; the trip-bound balance; ticket view levels;
badges and the export row), `RegistrationsCheckinResourceTest` (the scan flow, the 409s, clear, counts with and
without money, tickets out of listings), `TicketQrTest`. Webtests: `CheckinPwIT` (anonymous ticket 200 with no
session cookie and no email or id; the owner's answers; staff land on the controls from the ticket URL; a second
check-in is told "already" and keeps the stamp; only a manager clears; a viewer reads the counts and has the tab;
another event's ticket; the scanner's URL rule; the Admin menu entry), `BadgesPwIT` (the sheet, mark printed, the
dashboard cards, the export's columns and pending toggle), plus `AuthGatePwIT` and `AnonymousFootprintPwIT` entries.
