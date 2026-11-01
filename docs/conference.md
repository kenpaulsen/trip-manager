# Conferences — read before touching trip kinds, typed questions, tickets or the conference page

A conference is a `Trip` with `kind = CONFERENCE`, not a separate entity: it reuses the trip's roles, registration,
family party sign-up, waivers, payments, reports, chat and lodging. This replaces the `mir2026` branches, which
ran the 2025 Chicago and 2026 Orlando conferences as a hand-patched trip (the trip id hard-coded in ten files, hotel
logic in Java, a confirmation page that exposed attendees' answers to anyone holding their person id).

The work lands in phases on the `conference` branches of both repos. Phases 0 (the foundations), 2 (the public
conference page) and 3 (tickets and check-in) are done.

| Phase | What | State |
|---|---|---|
| 0 | Trip kind, typed and priceable questions, the widened `Registration`, ticket codes, agenda fields, the Check-in Staff role | landed |
| 1 | Priced checkout: age bands, discount codes, pay-first party checkout on the `Payment` record, offline payments, price breakdowns in the admin views (`docs/checkout.md`) | backend landed (on Stripe's processor shape); pages in progress |
| 2 | The trip-keyed public conference page: speakers, schedule, registration block | landed |
| 3 | Ticket page, staff check-in, counts, batch badges, check-in REST for the iOS app (`docs/checkin.md`) | landed (types and days read the typed answers, so it did not wait for Phase 1) |
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
- `days` are the ISO dates a choice covers, for the per-day check-in counts (`docs/checkin.md`).
- The **type question** (`RegistrationOption.typeQuestion`) is the first shown, required, pick-one question with a
  priced choice; its answer's label is a registration's type wherever `registrationType` is not stored.

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

`security/TicketCodes`: 128 random bits as 26 RFC 4648 base32 characters, the bearer secret the ticket page
(`trip/ticket.jsf?trip=&t=`) and its QR carry; the page, staff check-in, counts, badges and the API are in
`docs/checkin.md`. `normalize` accepts a typed or grouped code and maps 0/1/8 to O/I/B; `matches` compares in
constant time and never on a prefix.

**Invariant: every CONFIRMED registration has a ticket.** `RegistrationCommands.withTicket` mints one;
`saveRegistration` applies it to everything it stores (the backstop for every confirm path), and `approvePending`
applies it first so the object it holds carries the code. Older CONFIRMED rows get theirs on their next save, or
lazily from `CheckinCommands.ticketFor` (a check-in, a badge, the focus panel's ticket link).

## Agenda fields on events

`TripEvent.Detail` gained `PRESENTER`, `LOCATION`, `TRACK` and `AGENDA_HIDDEN`, stored in the existing `details`
map (no migration) and edited on the event dialog for EVENT-type events. They feed the conference page's public
schedule (Phase 2). `hasDetails()` now counts only the TRANSPORT keys (`Detail.isTransport`): an agenda event must
never switch the event dialog to the flight or bus form, and the ground-transport report must not treat it as a
composed leg. The REST event view sends any non-empty `details`, so the app sees agenda fields too.

## Check-in Staff

`checkinStaff` ("Check-in Staff") is a new trip-scoped role in `PrivilegeCommands.TRIP_SCOPED_BASES` and
`OrgCommands.TRIP_ROLE_NAMES`, so the trip editor's role picker and the org allow-list offer it. An organization
whose `grantablePrivileges` allow-list is set must have it ticked before it can be granted. It opens the Check-in tab
and page and the "Check-in: {title}" Admin menu entry (`docs/checkin.md`).

## The conference page

A conference's public page is CONTENT SECTIONS, edited in place like an organization's home page, under the trip's
own page key **`page:trip:{tripUuid}:home`** (`OrgPageBootstrap.tripPageKey`; `tripOf` parses it back and requires a
UUID middle, so no crafted key can pass for an org or marketing page; `page:trip:{id}:{name}` is reserved for
sub-pages). Keyed by the TRIP, never by the host: the same page renders on whichever site reaches the trip.

**Host.** `trip/tripDetails.xhtml`, already the public per-trip page (transient for anonymous visitors, linked from
the menus and listings). Its initPage computes `requestScope.confPageKey = site.tripPageKey(trip.id)`
(`SiteCommands.tripPageKey`: null unless the trip exists, `isConference()`, and `ListingScope.reachable` on this
site) and fills the `contentSections.xhtml` host contract exactly as `contentPage.xhtml` does (requestScope for an
anonymous visitor, frozen ids in viewScope when signed in). A build-time `c:if` on `confPageKey` then renders either
the conference sections (with a strip of anchors to every titled section, the Register button, and the Edit page
toggle) or the unchanged pilgrimage markup, so a pilgrimage builds none of the content tree. Every read in that
script names its scope: a pilgrimage leaves the key null, and a bare-name read of a missing value probes the view
map, which would mint a session. The page uses the classic layout (card and sidebar): `fullWidth` is decided by
`mainTemplate` before the page's initPage runs, so it cannot depend on the trip.

**Who edits it.** `ContentCommands.canEdit`/`mayEdit` gained `tripMgr@{trip}` for a trip page and its containers'
children (`ContentCommands.tripOf`, derived from the SECTION like `orgOf`, never the host): the first trip role that
edits content. `orgOfKey` resolves a trip page's org from the trip (a point-cached read), so that org's
`contentAdmin` edits it too and the person picker offers that org's members. Site admins and global content admins
as everywhere.

**Section types.** Existing bands (hero, text, split, CTA, FAQ, logos, stats, testimonial) plus:

- **Speakers** are `person` cards in a `people-list`: the board's card, extended rather than duplicated. A speaker
  usually has no account, so the row carries its own `photoUrl` (absolute http(s) only, the IMAGE_URL rule),
  `subtitle` (the talk title) and `bio` (RICH_TEXT, validated and cleaned on save). Programmatic properties are read
  live from the registry, so the fields appear on already-installed `person` templates.
- **`trip-schedule`** (`TripScheduleType`, `WEB-INF/ptypes/tripSchedule.xhtml`): the trip's EVENT-type events not
  hidden from the agenda, in start order, grouped by day (`ConferenceCommands.publicAgenda`). Flights, buses and
  lodging are logistics and never appear; participants and private notes are never part of it.
- **`trip-registration`** (`TripRegistrationType`, `tripRegistration.xhtml`): open / full / closed (closed once the
  event starts), places left (CONFIRMED and PENDING registrations against `regLimit`), the trip's priced questions'
  shown choices and prices (else its `estimatedPrice`), a countdown, and the Register button
  (`ConferenceCommands.registrationInfo`). A person's own price (age, codes) is checkout's, never this block's.

Both types have one optional "Event" property (blank: the page's own trip; a pick: show it on an org's home page);
`ConferenceCommands.tripFor` resolves explicit pick, then the page key, then the host page's `theTripId` (a section
inside a container), never a trip the site does not reach. `ProgrammaticContentTemplate.fitsTripPage()` keeps the
types that list OTHER trips (`pilgrimages`, `photo-albums`) off a trip page's Add dialog. The `#{conference}` bean
(`ConferenceCommands`) reads only cached DAO lookups and memoizes per request: this is the anonymous render path
(`RenderPathCacheTest` covers the page's sections and templates).

**Countdowns** moved from an inline script in `mainTemplate`'s classic layout to `resources/trip-js/countdown.js`,
loaded once by `template.xhtml`, so the registration block counts down on either layout.

**Seeding.** The first save of a conference (either editor, REST create, or flipping a trip's kind) calls
`ConferenceCommands.ensurePage` from `TripCommands.saveTrip`: `OrgPageBootstrap.tripRows` (hero with Register and a
Schedule anchor, About from the trip's description, Speakers with one placeholder card, Schedule, Registration,
Venue, Questions with one Q/A, a closing call to action), deterministic ids per trip, pinned to each starter's
current version, saved only into an EMPTY page (`OrgCommands.seedIfEmpty`, shared with the org home page). The trip
records `pageSeededAt`, on the caller's own object so a draft re-save keeps it; an emptied page is never refilled.
While any starter is missing (production before `install-starter-templates.sh` ran) nothing is seeded or stamped,
and the next save retries.

**Later phases, not built.** A per-conference subdomain needs `SiteIndex`/`SiteContext` to resolve a label to a
trip (a new site mode carrying the trip id, `ListingScope` and branding for it); a custom domain additionally needs
its certificate, DNS and a host-to-trip map, plus a host-only cookie domain.

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

`ConferenceModelTest`, `ConferenceFoundationsTest`, `ConferencePageTest` (key, authorization, picker, template
choices, site reach, agenda, registration block, seeding), `ConferenceTypesTest`, `TicketCodesTest`, `AgesTest`,
additions to `PersonTypeTest`, `RenderPathCacheTest`, `TripsResourceTest` and `RegistrationsResourceTest`. Webtests:
`TypedQuestionsPwIT` (menus, checkboxes and prices render; an unanswered required question is refused by name;
stored answers are choice ids) and `ConferencePagePwIT` (anonymous and session-free, seeded sections and anchors,
public schedule only, places and prices, the manager's Edit page and nobody else's, a pilgrimage unchanged).
