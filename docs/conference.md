# Conferences — read before touching trip kinds, typed questions, tickets or the conference page

A conference is a `Trip` with `kind = CONFERENCE`, not a separate entity: it reuses the trip's roles, registration,
family party sign-up, waivers, payments, reports, chat and lodging. This replaces the `mir2026` branches, which
ran the 2025 Chicago and 2026 Orlando conferences as a hand-patched trip (the trip id hard-coded in ten files, hotel
logic in Java, a confirmation page that exposed attendees' answers to anyone holding their person id).

The work lands in phases on the `conference` branches of both repos. Phase 0 (the foundations below) is done.

| Phase | What | State |
|---|---|---|
| 0 | Trip kind, typed and priceable questions, the widened `Registration`, ticket codes, agenda fields, the Check-in Staff role | landed |
| 1 | Priced checkout: age bands, discount codes, pay-first party checkout on the `Payment` record, offline payments, price breakdowns in the admin views (`docs/checkout.md`) | waiting for the Stripe integration, so it builds on the new processor shape |
| 2 | The trip-keyed public conference page: speakers, schedule, registration block | next |
| 3 | Ticket page, staff check-in, counts, batch badges, check-in REST for the iOS app (`docs/checkin.md`) | after Phase 1 |
| 4 | One-time import of people and past registrations from the old us-east-1 account | last |

## Trip kind

`TripKind` is `PILGRIMAGE` or `CONFERENCE`. A trip stored before kinds existed has no `kind` and reads as a
pilgrimage (`Trip.getKind()` folds null, the `chatEnabled` pattern), so there is no migration. The kind is set on
both trip editors (the Editor Admin's, like the title) and over `PUT /api/trips/{id}` (`"kind":"CONFERENCE"`; an
unknown kind is a 400 before anything is applied).

The kind changes copy and a few defaults, never permissions:

- `TripKind.registerLabel` / `peopleNoun` word the public trip page's call to action and the registration button
  ("Join Pilgrimage" / "Register Selected Travelers" for a pilgrimage, byte-for-byte what it was).
- `FamilyCommands.missingProfileFields(person, trip)`: a conference never asks for a passport.
- Conferences are left out of the PILGRIMAGE-shaped public views (the per-language sidebar lists and their
  language sections, the Trip Listings content section, the countdown cards) through
  `TripCommands.publicPilgrimages()`. `getPublicTrips()` itself, which the REST `open` listing reads, keeps them.

## Typed, priceable registration questions

`RegistrationOption` gained a `type` (`TEXT`, the default and what every older question reads as; `CHOICE`;
`MULTI_CHOICE`; `YES_NO`), `required`, `choices` (`OptionChoice`) and `ageBandsApply`. An `OptionChoice` carries
`id`, `label`, `priceCents`, `capacity`, `attestationText`, `days` and `enabled`; every money and capacity field is
optional, so on an unpriced trip choices simply turn a text box into a dropdown.

**One concept, not two.** A conference's admission types ("Full weekend / Friday only / Saturday only") are a
required pick-one question whose choices have prices; add-ons (a T-shirt, a dinner) are a pick-any question.
There is no separate "admission option" model: questions, answers, the editor and the exports stay single.

Rules that matter:

- **Answers store choice IDS**, never labels, so renaming a choice keeps its answers. A pick-any answer is the
  ids comma-joined (the `ContentInstance` MULTI_CHOICE convention). Anything that SHOWS an answer goes through
  `RegistrationCommands.answerText` / `regAnswerText` (`RegistrationOption.displayOf`), which turns ids back into
  labels; an id that no longer names a choice is shown as stored rather than vanishing.
- **Never bind a selectMany straight to `Registration.options`.** It would put a `String[]` into a
  `Map<String,String>`, which then fails to serialize. `WEB-INF/regQuestionInput.xhtml` binds a pick-any question
  through `#{reg.multiAnswer(answers, opt.key).values}`, an adapter that reads and writes the comma-joined string.
- A `required` question that is SHOWN and unanswered refuses that traveler's registration with
  `PartyOutcome.ANSWERS_REQUIRED` (the growl names the questions). A hidden question is never required.
- A withdrawn choice (`enabled = false`, the editor's "Offered?" box) is never offered again but stays on the
  question so existing answers still display.
- `attestationText`, when set, is a statement the registrant must tick to pick the choice ("I am staying at the
  conference hotel"): the generic form of mir2026's Rosen Centre logic. Enforcement lands with checkout (Phase 1).
- `days` are the ISO dates a choice covers, for the per-day check-in counts (Phase 3).

Editing: `WEB-INF/regOptionsDialog.xhtml` gained Type and Required columns and a second dialog for one question's
choices, opened by that row's "choices" link with the question's key in an `f:param` (row identity baked in at
render). Both bind into the `TripEditDrafts` working copy; nothing persists until the trip page's Save. REST:
`RegOptionDto` and `RegChoiceDto` carry the same fields.

## The widened `Registration`

`Registration` gained `paymentId`, `ticketCode`, `registrationType`, `priceBreakdown` (`PriceBreakdown`),
`attestations`, `checkedInAt`, `checkedInBy` and `badgePrintedAt`, all nullable and omitted from JSON when empty.

**Every field is `@With`, and code that changes one field must use the matching `withX`.** Rebuilding a
registration through a constructor that predates a field erases that field on the next save. Two REST paths did
exactly that and were switched to `withOptions` (`RegistrationsResourceTest.anAnswerEditKeepsTheTicketAndCheckIn`
pins it).

`PriceBreakdown` is the stored explanation of a price: the inputs the engine read, the ordered rule lines, the
total and the registration type. It is computed once at checkout and never recomputed. `summary()` is written into
the registration's Bill description, which the ledger compares byte-for-byte on every re-record, so its wording is
pinned by `ConferenceModelTest.theSummaryWordingIsPinned`.

## Tickets

`security/TicketCodes`: 128 random bits as 26 RFC 4648 base32 characters, the bearer secret a ticket page and its
QR will carry (Phase 3). `normalize` accepts a typed or grouped code and maps 0/1/8 to O/I/B; `matches` compares in
constant time and never on a prefix.

**Invariant: every CONFIRMED registration has a ticket.** `RegistrationCommands.withTicket` mints one;
`saveRegistration` applies it to everything it stores (the backstop for every confirm path), and `approvePending`
applies it first so the object it holds carries the code. Older CONFIRMED rows get theirs on their next save.

## Agenda fields on events

`TripEvent.Detail` gained `PRESENTER`, `LOCATION`, `TRACK` and `AGENDA_HIDDEN`, stored in the existing `details`
map (no migration) and edited on the event dialog for EVENT-type events. They feed the conference page's public
schedule (Phase 2). `hasDetails()` now counts only the TRANSPORT keys (`Detail.isTransport`): an agenda event must
never switch the event dialog to the flight or bus form, and the ground-transport report must not treat it as a
composed leg. The REST event view sends any non-empty `details`, so the app sees agenda fields too.

## Check-in Staff

`checkinStaff` ("Check-in Staff") is a new trip-scoped role in `PrivilegeCommands.TRIP_SCOPED_BASES` and
`OrgCommands.TRIP_ROLE_NAMES`, so the trip editor's role picker and the org allow-list offer it. An organization
whose `grantablePrivileges` allow-list is set must have it ticked before it can be granted. The pages that use it
arrive in Phase 3.

## Shared helpers

- `util/Ages.ageAt(birthdate, on)`: the one age computation. Pricing reads age on the trip's FIRST day.
- `Concession.Kind.FIXED_PRICE`: "costs at most `amountCents`", the child cap and the exact-price discount code
  in checkout pricing. Lodging's menus list their kinds by hand and do not offer it.

## Local seed

`FakeData.CONF_TRIP_ID` ("2027 Mar: Peace Conference Demo", mirrored in the webtests' `SeededIds`): a public CFPW
conference 75 days out with a required priced admission question (including a hotel-guest choice behind an
attestation), a priced add-on that age pricing does not touch, a pick-one shirt size, free-text dietary needs, a
two-day agenda with one event hidden from the public schedule, Trinity (user4) CONFIRMED with a ticket and Dave
(user5) PENDING. The registration seed is guarded on the trip, not on a person, because a re-seed mints new fake
people.

## Tests

`ConferenceModelTest`, `ConferenceFoundationsTest`, `TicketCodesTest`, `AgesTest`, additions to
`TripsResourceTest` and `RegistrationsResourceTest`; webtest `TypedQuestionsPwIT` (menus, checkboxes and prices
render; an unanswered required question is refused by name; stored answers are choice ids).
