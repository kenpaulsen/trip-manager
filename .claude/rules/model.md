---
paths:
  - "trip/src/main/java/org/paulsens/trip/model/**"
  - "trip/src/test/java/org/paulsens/trip/model/**"
---

# Domain model (`org.paulsens.trip.model`)

Lombok-annotated, Jackson-serialized to/from DynamoDB. Core types: `Person` (nested `Person.Id`), `Family`, `Trip`,
`TripEvent`, `Registration`, `RegistrationOption`, `Transaction`, `Creds`, `TodoItem`/`TodoStatus`, `PersonDataValue`,
`Privilege`, `BindingType`, `Config`/`SettingDef`/`SettingSection`, `MediaItem`, `AuditEvent`/`AuditQuery`/`AuditPage`,
lodging (`Accommodation` with inline `RoomType`/`Room`/`FloorMap`, `RoomBlock`, `ReservationOffer` carrying TWO
`RateSchedule`s -- `price` = what a participant pays, the bills; `cost` = what the hotel charges, the Room Invoice, null
= "not set", which REFUSES the invoice (never assumed from the price since 2026-10-26) -- plus `CostExtra`s and an
optional `RatePlan` link; `Reservation` with a `priceConcession` and a `costConcession` (`Concession`, no side field:
the field IS the side); the hotel-owned `RatePlan` (the hotel's cost side only -- no participant price on a plan);
`PricingModel`, `OfferType`; the trip's planning budget `TripBudget` with inline `BudgetLine`s (`BudgetLineKind`
SHARED/INDIVIDUAL, `BudgetCategory`) and `BudgetLodgingLine`s that read one of the trip's options LIVE,
`docs/budget.md`; the trip's expense report `TripExpenseReport` with inline `CurrencyRate`s (foreign units per dollar,
in millionths), `ExpenseLine`s (one currency each, optionally matched to a budget line, with a paid status: Not yet
reimbursed / Paid by organization / Already reimbursed; dollar figures derived on read, never stored), `AdvanceEntry`s
(the cash-advance WORKSHEET: typed `AdvanceType` entries, a magnitude in their own currency with the type carrying the
sign, a `fields` name-value map for the type's own answers, and the one Payment `Transaction` a participant entry may
have recorded) and `ExpensePayment`s (signed, recorded by a finance admin against the expenses they settle),
`docs/expense-report.md` -- `docs/lodging.md` "Price vs cost" before touching any money field; the four legacy flat
price keys still fold on read and mirror on write for one release, `docs/migrations/lodging-price-cost.md`), plus
`model/chat/*` (15 chat types) and `model/deploy/*`. **Accommodations are GLOBAL, not org-owned** -- the one deliberate
exception to the organization tenancy rule, like `Person`; `Accommodation.orgIds` records the orgs USING a hotel and
`tripIds` the trips holding an option there (both written by `HotelUsage` on every offer save), and editing is gated by
the per-hotel `accommodationAdmin` privilege (display "Hotel Operator"), never a field on the row. That exception has
exactly two documented extensions -- the hotel's Availability calendar (counts, dates) and the operator's Trips tab
(trip titles, dates, guest names at THEIR hotel; never a price or a bill) -- `docs/lodging.md` "Roles". **Every type
that can land in viewScope must be `Serializable`** -- a non-serializable one breaks the session save and 500s every
later request (looks like a site-wide outage). `ModelSerializationTest` is the guard.

`Person.privacy` (`PrivacySettings`) holds the owner's field-visibility choices; `PersonDto.redactedFor`
(REST) and `PrivacyView` (pages) are the two enforcement points -- read `docs/privacy-settings.md` before
touching Person field visibility, redaction, or the profile/contacts pages.

Conferences, typed registration questions, trip pages, priced checkout, tickets and check-in all hang off `Trip`,
`RegistrationOption` and `Registration`: their overview is `docs/code-map.md`, and `docs/conference.md`,
`docs/checkout.md` and `docs/checkin.md` are required reading before touching them.
