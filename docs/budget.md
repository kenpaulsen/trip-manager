# Trip budget

A per-trip planning budget that a trip administrator creates and keeps revising as conditions change: shared
costs, lodging, per-person extras, and the figures derived from them. Web only for now
(`admin/reports/budget.xhtml` in the private repo); it is built the way every app-reachable feature is (a
bean plus a thin REST wrapper), so the apps can read it later without a second implementation.

## Why it exists

Trip administrators planned budgets in a Google Sheet (the "Budget" tab of `2026-09-HolyAngels`). The sheet
is the oracle for everything below: its lines, inputs and derived figures are reproduced to the cent by
`BudgetMathTest` and `TripBudgetPwIT`. What the sheet could not do is read the trip's own lodging, so the
budget's lodging comes from the trip's lodging options instead of a typed nightly rate.

## The model

| Type | What it is |
|------|------------|
| `model/TripBudget` | ONE row per trip: `people`, `tripNights`, `perPersonBudgetCents`, `toBillCents` (the four typed inputs), `lines`, `lodging`, `orgId`, `createdBy`, `created`, `version` |
| `model/BudgetLine` | A typed line: `kind` (`BudgetLineKind`), `category` (`BudgetCategory`, SHARED only), `name`, `amountCents`, `perNight` (INDIVIDUAL only), `note`, `payee`, `percentOf` + `percentBps` (a percentage of another line), `offSite` |
| `model/BudgetLodgingLine` | A lodging line: `offerId` (one of the trip's `ReservationOffer`s), `label` (hotel and option name captured at save), `nights`, `people`, `rooms`, `singles`, `payee`, `note`, `offSite` |
| `BudgetLineKind` | `SHARED` (a whole-group cost, divided across everyone) or `INDIVIDUAL` (each participant pays it) |
| `BudgetCategory` | In menu order: `TRANSPORTATION`, `GUIDE`, `SUPPLIES`, `TIPS`, `SCHOLARSHIP` ("Scholarships"), `LEADER` ("Leader expenses"), `GENERAL` ("Other", the catch-all; the stored name predates the named categories, so older lines still read). Shown in the Shared Expenses table's Category column; no per-category subtotal |

Money is `long` cents in the model and `Double` dollars in forms (USD only). The constructors normalize, so
Jackson, the builders and the no-arg constructors all produce valid rows: a SHARED line always has a category
and is never per night, an INDIVIDUAL line has no category, amounts and counts never go negative, `singles`
never exceeds `people`, and blank text is null.

**Percent lines.** A line with `percentOf` takes `percentBps` basis points (1250 = 12.5%, capped at 1000%) of
another line OF THE SAME KIND (Tips = 10% of Guide; a per-person line only of another per-person line). Its
`amountCents` is derived, not typed: `BudgetMath.resolvePercents` recomputes every percent line on every save
(recursively, so a percent of a percent works), so an edit of the base reaches its followers in the same put
and every reader keeps using `amountCents`. A save is refused when the base is missing, of the other kind,
itself, or would close a cycle (`BudgetMath.percentProblem`); a line others follow cannot be deleted or switch
kind until they change.

**On site or not.** Every line, lodging lines included, is either paid on site (the default; it is listed with
the Payees) or not (Other trip items: flights, insurance, a hotel paid ahead). The flag is stored INVERTED as
`offSite`, the way an option stores `disabled`: a row written before it existed, a builder that never mentions
it, and a form revived from a session all read as on site. The forms expose it as `onSite` for the checkbox.

### One row, one put

`trip_budgets` (PK `tripId`, no sort key, no index) holds the whole budget inline, behind one `PointCache`
(`CacheKeys.BUDGET_PREFIX`, `DAO.CacheScope.BUDGET`). `dynamo/BudgetDAO` is the `FamilyDAO` shape: version 0
may only create (`attribute_not_exists`), any other version only replaces that exact stored version, and a
lost race throws `ConditionalCheckFailedException` with the version restored. Why one row rather than a row
per line: a budget is tens of lines, the page always reads all of it, and one put keeps a save atomic, so
"Saved by someone else: reload the page and try again." refuses the whole sheet instead of half-merging it.
The bean caps lines at 200 and lodging lines at 50; the DAO refuses a serialized row over 350 KB before the
store does. Trip delete removes it with one `deleteItem` (`TripDeleteCommands`, `docs/trip-delete.md`).

## Lodging comes from the trip's lodging options

A lodging line points at one of the trip's lodging options and reads its two schedules LIVE: `price` (what
participants are charged) and `cost` + `costExtras` (what the hotel charges). Not rate plans directly: a
`RatePlan` carries the hotel's cost side only, and the participant price is set on the option when the plan
is attached, so only the option has both sides. A plan-linked option shows its plan's name.

Nothing is snapshotted: change a rate on the Lodging tab and the budget follows. An option that is later
deleted reads "(deleted option)" (after its captured label) and counts $0; an option with no price or no
cost counts $0 on that side and raises a warning. Per-date overrides are ignored: the flat `nightlyCents` is
used, because a budget plans a stay in aggregate and has no particular nights to look them up by.

The dialog asks for **nights** and **people** always, **rooms** only when either schedule is PER_ROOM or a
cost extra is per room-night or per stay, and **of which single** only when a PER_PERSON side has a single
supplement (`BudgetMath.needsRooms` / `needsSingles`). Fields the option does not need are hidden, and a save
zeroes them.

## Formulas (`pay/BudgetMath`, long cents, divisions HALF_UP to the cent)

One lodging line L on option O (P = price, C = cost, X = cost extras):

| Quantity | Formula |
|---|---|
| guestNights / roomNights / stays | people × nights / rooms × nights / rooms |
| revenue(L) | P null → 0 and `priceMissing`; PER_PERSON: people × nights × P.nightly + singles × nights × P.singleSupplement; PER_ROOM: rooms × nights × P.nightly |
| hotelCost(L) | C null → 0 and `costMissing` (extras count nothing then either); else the same shape on C plus Σ `CostExtras.amountCents(x, guestNights, roomNights, stays)` |
| margin(L) | revenue − hotelCost (signed) |
| perPersonPrice(L) | PER_PERSON: nights × P.nightly; PER_ROOM: revenue / people (0 when people = 0) |
| singleAddOn(L) | PER_PERSON: nights × P.singleSupplement; PER_ROOM: none, a single occupant pays the whole room |

The whole budget:

| Quantity | Formula | Sheet oracle |
|---|---|---|
| sharedTotal(category) / sharedTotal | Σ SHARED lines in the category / all | 5,940.00 · 3,848.53 · 6,113.66 / 15,902.19 |
| estimatedPerPerson | sharedTotal / people (0 when people = 0) | 345.70 |
| individualExtras | Σ over INDIVIDUAL lines of (perNight ? amount × tripNights : amount) | 0 |
| totalPerPersonGround(L) | estimatedPerPerson + perPersonPrice(L) + individualExtras | 945.70 |
| toBillReference(L) | perPersonBudget + perPersonPrice(L) + individualExtras | 1,000.00 |
| budgetLeftOver | perPersonBudget × people − sharedTotal ("should be a positive number") | 2,497.81 |
| lodgingRevenue / lodgingCost / lodgingMargin | Σ over the lodging lines | |
| overall | budgetLeftOver + lodgingMargin | |
| tripCents(line) | SHARED → amount; INDIVIDUAL → per-person total × people | |
| payee totals | on-site lines only: lodging line → its hotelCost under its payee (default: the hotel's name); a typed line → tripCents under its payee, or under its own name in brackets ("[Badges]") when it has none; grouped case-insensitively on the trimmed payee, first spelling kept | |
| On-site Payments | Σ payee totals | 29,914.19 with the lodging oracle |
| other trip items | the lines NOT on site, one row each, the same amounts | |
| estimated trip total | On-site Payments + Σ other trip items | |
| basic ground budget range | toBill, or toBill to toBill + max singleAddOn + individualExtras when either is positive | 1,000.00 to 1,200.00 |
| estimatedGroundPerPerson | (sharedTotal + lodgingCost) / people | 650.31 |

The sheet's "Budget left over" (shared only) is still computed but no longer has a card: the **Overall
surplus** or **Overall deficit** card (named by its sign, green or red, the amount unsigned beside the label)
is budgetLeftOver plus the lodging margin. The single-room add-on is shown per option and never folded
into a per-person total. The per-person budget and the to-bill figure stay single TYPED inputs: the
per-option table shows each option's computed reference beside them, flagged above or below.

**Lodging oracle** (the seeded rate plan's shape): price PER_PERSON $60 + $20 single, cost PER_ROOM $50 +
FIXED $12 "City tax"; nights 10, people 46, rooms 28, singles 10 → revenue 29,600.00, hotel cost 14,012.00,
margin 15,588.00, per-person price 600.00, single add-on 200.00, total per-person ground 945.70, to-bill
reference 1,000.00, overall 18,085.81. (The sheet's "Money to Huanita" cell, $32,600, includes something it
does not itemize; the inputs alone give $29,600, so the tests use the formulas, not that cell.)

## Cloning

**Clone budget from...** is offered while this trip's budget has no lines at all (`canClone`), so a clone never
overwrites anything. The menu (`cloneChoices`, looked up when the dialog opens, not on every render) lists the
other trips of the SAME organization, among the 400 most recent, whose budget the caller may read and that has
at least one line; another organization's budget is never offered or accepted (the tenancy rule). The clone
copies every typed line with fresh ids (percent links re-pointed at the copies), the per-person shared budget
and the basic ground budget. People and nights stay this trip's, and lodging lines are not copied: they point
at the source trip's own lodging options, and the growl says so.

## Pre-fills

A budget that has never been saved renders with `people` from the roster and `tripNights` from the trip's
dates (`LodgingPricing.nights`); the first save stores them. Typed values stick: the page shows the live
roster count and date nights beside the inputs with a **Use** button whenever they differ. A new lodging
line takes `people` from the budget and `nights` from the budget; choosing an option resets nights to the
option's default stay (else the trip dates).

## Privileges

No new privilege. Edit (`BudgetCommands.canEditBudget`): site admin, `tripMgr@trip`, `tripFinAdmin@trip`.
View (`canViewBudget`): an editor, `tripFinView@trip`, or the global `viewFinances`
(`PrivilegeCommands.VIEW_FINANCES`). Both answer false for an unknown trip or nobody signed in. The Reports
tab and dashboard admit `tripMgr` and `tripFinAdmin` too, so a trip manager with no other role still reaches
the Budget card (`docs/reports.md`, "Admission").

## The page (`admin/reports/budget.xhtml`, private repo)

Reached from the Reports dashboard's **Budget** card (`toBudget`, its number is `budgetHeadline`: the overall
surplus, or "No budget yet"); `reportsDone` back, `toLodging` to the trip's Lodging tab, and a print view
(`?print=true`) that edits nothing. Sections: Clone budget from... (empty budgets only), warnings
(`bgMissing`), Inputs (people, trip nights, Per-person shared budget, Basic ground budget (shared + lodging)),
Shared Expenses (one table with a Category column), Lodging, Individual extras, Per person by lodging option,
Summary, Payees (totalled as "On-site Payments"), Other trip items, and the estimated trip total.

The Summary has three cards and a fourth on its own line: **Per-person shared budget** (the typed figure, the
estimated per-person shared cost under it), **Basic ground budget (shared + lodging)** (the typed figure, as a
range when a single add-on or per-person extras can raise it, the estimated shared + lodging hotel cost per
person under it), **Lodging revenue** (hotel cost under it, a rule, then the lodging margin in green or red),
and the **Overall surplus / deficit** card.

State follows `docs/page-state-and-identity.md`: `requestScope.bp = budget.budgetPage(tripId)` every request
and again after every write (`SessionScopePolicyIT` lists `budget.budgetPage` as domain-valued); only the
scalar forms (`inputsForm`, `lineForm`, `lodgingForm`) live in viewScope. Row commands carry the line id in an
`f:param`: `editLine` / `lineUp` / `lineDown` / `deleteLine` (`WEB-INF/budgetLineActions.xhtml`, shared by the
shared and individual tables), `editLodging` / `deleteLodging`, plus `addLine` (`kind`, `category`),
`addIndividual` and `addLodging`. Moves stay within the line's own group. The two dialogs show a refused save
INSIDE the dialog (`lineProblem`, `lodgingProblem`, the 2026-10-26 rule) and stay open through the lodging
pages' `keepOpen` idiom; Cancel clears the form.

## REST (`api/BudgetResource`, `application/vnd.trip.budget.v1+json`)

| Method | Path | Body / answer |
|---|---|---|
| GET | `budget/trips/{tripId}` | `{inputs, lines, lodging, summary}` (summary = the page's `BudgetPage`) |
| PUT | `budget/trips/{tripId}/inputs` | `{people, tripNights, perPersonBudget, toBill}` → `{ok:true}` |
| POST / PUT / DELETE | `budget/trips/{tripId}/lines[/{lineId}]` | `{kind, category, name, amount, perNight, note, payee, onSite, percent, percentOf}` → `{id}` (`percentOf` makes it a percent line; `onSite` defaults to true) |
| POST / PUT / DELETE | `budget/trips/{tripId}/lodging[/{lineId}]` | `{offerId, nights, people, rooms, singles, payee, note, onSite}` → `{id}` |
| POST | `budget/trips/{tripId}/clone` | `{fromTripId}` → `{ok:true}`; 400 unless this budget is empty and the source is one this trip may clone |

Writes need the CSRF header (403) and the edit gate (403); a refusal is 400 `VALIDATION_FAILED` carrying the
bean's reason; an unknown trip is 404. Deleting a line that is already gone succeeds; deleting one a percent line
follows is refused.

## Tests

Unit (`trip/`): `TripBudgetTest`, `BudgetLineTest`, `BudgetLodgingLineTest` (normalization, builder parity,
Jackson), `BudgetMathTest` (both oracles, PER_ROOM shares, missing schedules, extras per basis, HALF_UP,
nobody counted, payee grouping and the bracketed fallback, on-site vs other items, percent chains and their
refusals, the basic ground budget ceiling and estimate), `BudgetDAOTest`, `BudgetCommandsTest` (gate matrix, pre-fills, `pickOffer`,
the oracles on the page rows, refusals, caps, moves, deletes, a deleted option, percent lines, off-site lines,
and cloning within one organization only), `BudgetResourceTest`, plus
cases in `AuditCommandsTest`, `TripDeleteCommandsTest`, `ModelSerializationTest`,
`SerializationCompatibilityTest` and `ApiSurfaceTest`.

Browser (`medjugorje/webtest`): `TripBudgetPwIT` (both oracles rendered in the new summary, the print view,
a percent line set in the dialog and following its base, an off-site line under Other trip items, the clone
dialog, pre-fills and Use,
the lodging dialog's fields per option, a plan-linked option, a cost-less option's warning, line dialog and
row commands by identity, negative figures, the gate matrix), with the Budget card and page added to
`ReportsDashboardPwIT`, `AdminReportsPwIT` and `AuthGatePwIT`.

## Out of scope

Actuals and an expense report, currencies other than USD, several budgets per trip, snapshotting prices.
