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

Admitted: `showAll` (site admin), `tripView@trip`, `tripFinView@trip`, or the global `viewFinances`. That is
the same expression that shows the tab, so navigation and enforcement cannot disagree. Opening the dashboard
grants nothing: every card is separately `rendered=` on the gate of the page it opens.

| Card | Opens | Gate |
|------|-------|------|
| Roster | `admin/reports/tripPilgrims.jsf?print=true&trip=` | `tripViewer` |
| Room List | `admin/reports/tripRooms.jsf?print=true&trip=` | `tripViewer` |
| Events | `admin/tripEvents.jsf?id=` (that page reads `?id=`, not `?trip=`) | `tripViewer` |
| Ground Transportation | `admin/reports/groundTransport.jsf?trip=` | `tripViewer` |
| Export | `admin/reports/exportTrip.jsf?trip=` | `tripViewer` |
| Finances | `trip/tripTransactions.jsf?trip=` | `seeFinances` |
| Payments | `admin/reports/tripPayments.jsf?print=true&trip=` | `seeFinances` |

`tripViewer` is `showAll || tripView@trip`; `seeFinances` is `viewFinances || tripFinView@trip`. Both are
computed into `requestScope` every request, so a grant made while the page is open takes effect on the next
load. The cards use the shared `.hub-grid` / `.hub-head` / `.hub-num` / `.hub-sub` vocabulary in
`resources/css/trip.css`, which the organization Dashboard (`admin/orgSettings.xhtml`) uses as well; it lives
there so the two hubs cannot drift apart.

## The Ground Transportation report

`admin/reports/groundTransport.xhtml` lists every `TripEvent.Type.GROUND` leg on the trip in departure order
(undated legs last), with the fields the Add Ground Transport dialog collects: route, carrier, departure and
arrival date and time, the derived duration, and the riders. The head count is the first line of the People
column and the riders' preferred and last names follow in smaller type, ordered by last name then preferred.

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
  the overnight flag, the derived duration, non-GROUND exclusion, and the seeded legs.
- `AdminReportsPwIT`: the dashboard's seven cards and their hrefs, the ground report's rendered rows, and the
  one-way-back rule on every report.
- `ReportsDashboardPwIT`: the per-privilege card sets (admin, finance viewer, trip viewer), the bounce for
  someone holding nothing, the active tab, the back link, and the empty state.
- `TripContactsPagePwIT`: the Contacts header carries Photos and Edit only, and the Reports tab reaches the
  dashboard carrying the trip id.
- `AuthGatePwIT`: both new URLs bounce an anonymous visitor with a 302, never a 500.

`FakeData` seeds FAKE_TRIP with two ground legs, one editor-composed (Split to Medjugorje, Globtour bus, three
riders) and one legacy-shaped (no details), so both render paths are exercised locally and in the browser
tests.
