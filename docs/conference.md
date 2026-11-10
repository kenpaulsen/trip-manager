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
the trip editor (the Editor Admin's, like the title) and over `PUT /api/trips/{id}` (`"kind":"CONFERENCE"`; an
unknown kind is a 400 before anything is applied). Both kinds share the section-based trip page (below); the kind
decides which starter sections a new page gets and the registration wording.

## Access: Create Conferences

Conferences are a separately granted feature (owner decision 2026-11-02, an upsell). One org-scoped privilege,
`addConference` ("Create Conferences"), does both jobs, exactly as `addTrip` does for trips:

- **The entitlement** is the org's allow-list (`Organization.grantablePrivileges`, `admin/orgConfig.jsf`, Privileges
  tab, site admins only). An org whose list is unset offers everything, so the owner configures each org. The offer
  is checked AT USE (`OrgCommands.canCreateConferenceFor`), so withdrawing it stops new conferences at once even
  though existing grants stand.
- **The privilege** is granted by the org's admins to members (`admin/orgPeople.jsf`, "Create Conferences"). Org
  admins are NOT implied: they grant it to themselves. Site admins always pass.
- **Create only.** It lets a person create a conference (the org Trips page's "New Conference",
  `trip/edit.jsf?orgId=&kind=CONFERENCE`, or `POST /api/trips` with `"kind":"CONFERENCE"`); the creator gets the
  usual creator trip roles, and running a conference is the trip roles' job as before. It is not a trip grant
  either: `addTrip` alone may not create a conference, `addConference` alone may not create a pilgrimage.
- **Making or unmaking a conference** (changing an existing trip's kind to or from CONFERENCE) takes the same
  privilege: `TripCommands.saveTrip` refuses it otherwise on every path (the REST edge passes its own caller's
  `OrgCommands`, because the bean's resolves from the JSF session), and the trip editor shows its Kind row only to
  a holder (`TripCommands.canChooseKind`, 2026-11-02; `tripKindsFor` still offers anyone else only the trip's own
  kind). A `PUT /api/trips/{id}` that would flip it answers 403.

Tests: `ConferenceAccessTest`, `TripsResourceTest` (`aConferenceTakesCreateConferences`,
`aTripManagerMayNotMakeAConference`), `ConferenceAccessPwIT`.

Beyond who may make one, the kind changes copy and a few defaults, never permissions:

- A NEW conference starts with itinerary sharing OFF (attendees are mostly strangers); a new pilgrimage starts
  with it on. `TripCommands.stampCreationDefaults`, once at creation: changing the kind later leaves the
  choice alone (`docs/shared-itineraries.md`).

- `TripKind.registerLabel` / `peopleNoun` word the public trip page's call to action and the registration button
  ("Join Pilgrimage" / "Register Selected Travelers" for a pilgrimage, byte-for-byte what it was).
- `FamilyCommands.missingProfileFields(person, trip)`: a conference never asks for a passport.
- Conferences are left out of the PILGRIMAGE-shaped public views (the per-language sidebar lists and their
  language sections, the Trip Listings content section, the countdown cards) through
  `TripCommands.publicPilgrimages()`. `getPublicTrips()` itself, which the REST `open` listing reads, keeps them.

## Typed, priceable registration questions

`RegistrationOption` gained a `type` (`TEXT`, the default and what every older question reads as; `CHOICE`;
`MULTI_CHOICE`; `YES_NO`), `required` and `choices` (`OptionChoice`); the age bands of a priced trip adjust the
admission question only (`docs/checkout.md`). An `OptionChoice` carries
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

The editor offers it on a CONFERENCE only (owner decision 2026-11-07, `OrgCommands.offeredOn`): a pilgrimage has no
Check-in tab, ticket mail or check-in cards, so both the Add Manager menu and the per-row "+ Add role" leave it out,
and `setTripRole` refuses a new grant on a pilgrimage. Only the grant is withheld: a holder left over from a kind
flip still shows on the roster and can still be revoked, and flipping back to a conference loses nothing.

## The trip page

EVERY trip's public page, whatever its kind (2026-11; it began as the conference page), is CONTENT SECTIONS, edited
in place like an organization's home page, under the trip's own page key **`page:trip:{tripUuid}:home`**
(`OrgPageBootstrap.tripPageKey`; `tripOf` parses it back and requires a UUID middle, so no crafted key can pass for an
org or marketing page; `page:trip:{id}:{name}` is reserved for sub-pages). Keyed by the TRIP, never by the host: the
same page renders on whichever site reaches the trip. The old single `Trip.description` field is no longer edited
anywhere (the editor's Trip Description fieldset is gone); a trip that still carries one is a **legacy page** (below)
until its editor converts it.

**Hosts.** The body is ONE include, `WEB-INF/tripPageBody.xhtml`, with two hosts: `trip/tripDetails.xhtml`, the
public per-trip page with the site chrome (transient for anonymous visitors, linked from the menus and listings), and
`trip/tripPage.xhtml`, the EMBED render the apps fetch (below). The host's initPage pins the trip
(`requestScope.theTrip`); the include's initPage then computes `requestScope.tripPageKey = site.tripPageKey(trip.id)`
(`SiteCommands.tripPageKey`: null unless the trip exists and `ListingScope.reachable` on this site), `tripLook`
and `legacyPage`, and fills the `contentSections.xhtml` host contract exactly as `contentPage.xhtml` does
(requestScope for an anonymous visitor or the embed, frozen ids in viewScope when signed in on the site page). A
build-time `c:if` on `legacyPage` renders either the sections (with a strip of anchors to every titled section, the
Register button, and the Edit page toggle) or the legacy page; a second build-time `c:if` on the include's `embed`
param (a literal per host, so a GET and its postbacks agree) leaves the chrome out of the embed. Every read in that
script names its scope: a bare-name read of a missing value probes the view map, which would mint a session, and
`embed` is read in the initPage and `c:if` tests only (a jsft command script cannot see a Facelets param). The page's
look is `TripPageCommands.lookFor(tripId)` (a `PageLook`: full width or not, its own root style or null, title
shown, section links shown, a screened stylesheet URL), the trip's page options (`Trip.page`, below) laid over the
org's Appearance settings, so a trip with none renders exactly as the org's home page does. `mainTemplate` decides
`fullWidth` before the page's initPage runs, so the page's build-time params (`fullWidth`, `rootStyle`, the
stylesheet `<link>` in `pageextrahead`) read `tripPage.lookFor(param.trip)` from the REQUEST parameter (memoized
per request), and the body carries a plain hidden `trip` field outside every branch so a postback (which posts without
the query string) rebuilds the same branch. `lookFor` is null for a trip this host does not reach, which EL reads as
false/empty: that page stays classic, byte for byte. By default a trip page renders NO `<h1>` title (the hero band
carries it, as on the named content pages); the trip tab strip stays for the members it renders for.

**Legacy pages.** A trip never stamped with a page (`pageSeededAt == null`) and with no content row under its key
yet renders the OLD page: the title, the tab strip, the "Register Now!" card, the stored description (if any), the Join
link (`TripPageCommands.isLegacyPage`, cached reads, memoized per request; a page someone authored by hand first wins
over the description). The apps show the same through the embed. It stays that way until one of the page's editors
(`ContentCommands.canEditPage`) clicks **Edit page** on it and confirms: `TripPageCommands.convertLegacyPage` gives
the trip its page and stamps it. A trip WITH a description gets that description, verbatim, as ONE untitled text-only
section (`OrgPageBootstrap.legacyRows`; neither a placeholder nor normalized, so the page reads as the old one did;
`RichTextRules.normalize` may tidy a lone `<p>` on the section's first save) and the description field is kept but
never edited again; a trip without one gets its kind's starters. The button then redirects into edit mode. Converting
saves the trip through the DAO, like seeding, never through `TripCommands.saveTrip`: a details-page button must not run
the editor's kind, pricing and provider rules.

**The embed.** `trip/tripPage.jsf?trip={id}` is what the iOS and Android apps fetch through `TripDto.detailsUrl`
(`api-contract.md`, "Trips"), cache, and show in a web view: the same body with NO chrome (no template, topbar, menu,
footer, tab strip, section strip, edit toolbar or scripts), no registration controls (the app owns registration:
`EmbedStyles.overrides` hides the hero and call-to-action buttons and the registration block's button; a tap would
land on an anonymous sign-in page in the system browser), and everything it needs to look right in ONE inline
`<style>` (`TripPageCommands.embedCss`: the current theme's `:root` block alone, found by index in the 200 KB theme,
`site.css`, `albums.css`, the trip's own stylesheet when its bytes can be read, then the overrides) so a copy saved on
a phone renders with its styles offline; images and video frames stay live, and a `<base href>` resolves a legacy
description's relative paths. The page paints its own opaque ground (the theme's `--surface-ground`, or the trip's
`--layout-full-bg`) and a legacy description sits on a `--surface-card` card: the apps draw the web view transparent
over a blurred photo, and a see-through page left dark text on it, unreadable (2026-11-09). Anonymous-safe like
`manifest.xhtml`: a transient view with `contentType="text/html"` (an app's Accept header names the API media type),
requestScope only; `contentSections.xhtml` takes a `readOnly` param so a signed-in browser's cookie never builds the
edit dialogs into it. `AnonymousFootprintPwIT` sweeps it.
`detailsUrl` addresses it on the OWNING organization's site (`TripsResource.detailsUrl` over `SiteUrls.baseUrlForTrip`,
as tickets do), where the page's ordinary reach rule admits the trip; built on the product host the apps' API calls go
to, it answered "This page is not available." for every trip the marketing page does not list (2026-11-08).

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
  hidden from the agenda, in start order, grouped by day (`TripPageCommands.publicAgenda`). Flights, buses and
  lodging are logistics and never appear; participants and private notes are never part of it.
- **`trip-registration`** (`TripRegistrationType`, `tripRegistration.xhtml`): open / full / closed (closed once the
  event starts), places left (CONFIRMED and PENDING registrations against `regLimit`), the trip's priced questions'
  shown choices and prices (else its `estimatedPrice`), a countdown, and the Register button
  (`TripPageCommands.registrationInfo`). A person's own price (age, codes) is checkout's, never this block's.

Both types have one optional "Event" property (blank: the page's own trip; a pick: show it on an org's home page);
`TripPageCommands.tripFor` resolves explicit pick, then the page key, then the host page's `theTripId` (a section
inside a container), never a trip the site does not reach. `ProgrammaticContentTemplate.fitsTripPage()` keeps the
one type that can only list OTHER trips (`pilgrimages`) off a trip page's Add dialog. `photo-albums` fits: it carries
the same optional "Event" property, and on a conference's page (or with an event picked) `MediaCommands.getHomeAlbumsFor`
answers that ONE event's album from its chat photos, whatever its age or photo count, so a conference page shows its
own pictures; anywhere else it is the recent trips' albums as before. The album cards' CSS and the photo-viewer glue
moved out of `homePage.xhtml` into `resources/css/albums.css` and `resources/trip-js/albums.js`, which both hosts
load (the glue reads the page's facts off `#albumsGlue`). The `#{tripPage}` bean
(`TripPageCommands`) reads only cached DAO lookups and memoizes per request: this is the anonymous render path
(`RenderPathCacheTest` covers the page's sections and templates).

**Countdowns** moved from an inline script in `mainTemplate`'s classic layout to `resources/trip-js/countdown.js`,
loaded once by `template.xhtml`, so the registration block counts down on either layout.

**Seeding.** The first save of a trip (the editor, REST create, or flipping a trip's kind) calls
`TripPageCommands.ensurePage` from `TripCommands.saveTrip`: `OrgPageBootstrap.tripRows` dispatches on the kind.
A CONFERENCE gets `conferenceRows` (hero with Register and a Schedule anchor, About from the trip's description,
Speakers with one placeholder card, Schedule, Registration, Venue, Questions with one Q/A, a closing call to
action); any other trip gets `pilgrimageRows` (a hero with the kind's Join button and an About anchor, About from the
description, and a YouTube video section seeded blank and hidden until an editor sets its URL). Deterministic ids per
trip, pinned to each starter's current version, saved only into an EMPTY page (`OrgCommands.seedIfEmpty`, shared
with the org home page). The trip records `pageSeededAt`, on the caller's own object so a draft re-save keeps it; an
emptied page is never refilled. The one exception is the legacy rule above: a trip that already EXISTED, unstamped,
and still carries a description is left alone by `saveTrip` (a brand-new trip created with a description seeds at
once, the description becoming its About). While any starter is missing (production before
`install-starter-templates.sh` ran; `tripRowTemplateIds` is the union of both kinds' starters) nothing is seeded or
stamped, and the next save retries. A trip whose id is not a UUID (imported or hand-made rows) can carry no page.

**Placeholders start hidden.** The rows that are only placeholders (the sample speaker, the venue, the sample
question, their two containers, and About when the trip has no description) are seeded with "Show until" set to
the seed time: the section's existing expiry (`ContentInstance.eventDate`, `isVisibleAt`), not a new flag. Visitors
never see "Speaker name"; editors see the sections dimmed in edit mode, and the content dialog says "placeholder:
hidden from visitors until you save it". **The first save publishes it**: a row is a seeded placeholder while its author
is still the bootstrap's and it carries that seed date (`OrgPageBootstrap.isSeededPlaceholder`; no flag of its own),
and `ContentCommands.saveContent` clears the date when the editor left it exactly as seeded (a date the editor set
themselves is kept). Saving the first item into a placeholder CONTAINER (a speaker into Speakers, a question into
Questions) publishes the container too, as its own audited save; and `restoreContent` of the seeded version of a
published row never hides it again. The rows `conf-page-placeholders.py` hid on an older page match the same rule.
The hero, schedule, registration block and closing call to action carry the trip's own data and show at once.

**Look and feel: the trip's own page options over the org's.** The org's Appearance page (`admin/orgConfig.jsf`)
supplies the defaults every page of the site shares: logo, favicon, link-preview image, palette, dark mode, background
and the layout. A trip may differ from them on its own page through `Trip.page` (`TripPageOptions`, the
`CheckoutConfig` shape: a lazy getter for the editor, `Trip.pageOrNull()` for render code, every field null = "the
organization's"):

- `layout`: `classic` or `full-width` (the `site.layout` setting's own values; `KnownSettings.SITE_LAYOUT.allows`).
- `backgroundUrl` / `backgroundColor`: an image (wins) or a hex color behind this page alone. Rendered through the
  SAME rule as the org's (`BrandCommands.rootStyleOf`, which `getRootStyle` also uses), passed to `template.xhtml` as
  the `rootStyle` ui:param. An image also sets `--layout-full-bg:transparent`, so the full-width layout's own ground
  (`.layoutFull` in `site.css`) no longer hides a background image, the org's or the trip's.
- `showTitle` (null = no: the hero carries it) and `showNav` (null = yes: the strip of section links).
- `stylesheetUrl`: a CSS file in the trip's organization's media library, loaded by this page alone. The field rules
  are `TripPageOptions.problems()`; the library rule is `TripPageCommands.pageProblems` (`MediaCommands.libraryItemFor`
  must resolve the URL to an item of that org with `text/css`), because any other URL would let a trip manager load
  arbitrary CSS into the org's site, which is its content admins' call. Rules belong under `.contentItem` (every
  section's wrapper) so the menu and the dialogs are never restyled. **The live editor** (2026-11, `TripStylesheets`,
  `#{tripStylesheets}`; `WEB-INF/stylesheetEditorDialog.xhtml`) sits ON the trip's details page as a floating,
  non-modal dialog (`tripPageBody.xhtml` hosts it for a signed-in site view, never the embed): the edit toolbar's
  **Styles** button, or the Trip Page dialog's "Open the style editor" link (`?styles=true` loads the sheet the trip
  links, `?styles=new` the starter; `prepare`), opens it over the real page, and every keystroke is written into a
  `<style id="tripLiveCss">` on the page, so it restyles as the designer types; while the sheet being edited is the
  one the page links, the page's own `<link data-trip-stylesheet>` is switched off so a removed rule disappears too,
  and Close puts the page back. **Save stores the stylesheet only** (`saveOnly`: create, or save in place with the
  title change as the rename; a new sheet is then the one being edited); linking a sheet to the trip stays the Trip
  Page dialog's menu, so a designer may work on a sheet without touching the trip (the dialog says so when the saved
  sheet is not the linked one). Who edits: site admins, the org's admins and its `mediaAdmin` holders (`canEdit` =
  `canManageOrg` or `canViewOrgMedia`); a trip manager only picks. ONE media item per stylesheet, `text/css`,
  under `org/{orgId}/stylesheets/{slug}.css` (`slugOf` the title, `-2`, `-3` on a collision); Save overwrites the
  SAME key (every trip's stored URL keeps working), bumps the row's `rev` so the rendered `<link>` carries `?r=N`
  (`TripPageCommands.lookOf` appends `MediaItem.getCacheBuster()` from the CACHED listing,
  `MediaCommands.cachedLibraryItemFor`; the stored value stays bare and `libraryItemFor` ignores a query suffix),
  and invalidates the CDN path once (`invalidateCdn`; CloudFront's cache key ignores query strings). The editor
  opens and saves in place ANY `text/css` library item stored in its organization's own folder as a `.css` file
  (`TripStylesheets.isOrgStylesheet`), not only the ones it created: the conference import's page stylesheets live
  under `org/{orgId}/conferences/...` (2026-11-07). Text only, at most 256 KB, no control characters and no
  `</style` (`TripStylesheets.check`; a lone `<` is legal CSS: inline SVG data URLs, comments), and the embed
  links rather than inlines any sheet that holds `</style`. The editor is `pe:codeMirror` in CSS mode; `completeMethod`
  (`complete`, Ctrl+Space) and the "Classes on this page" list (`classReport`: every `class` the page's rendered
  sections and typed rich text reference, tagged custom / hook / styled through `content/CssClassScan`) point a
  designer at the classes the page really uses; its plus drops an empty `.contentItem .name {}` at the cursor. A
  new stylesheet starts as `resources/css/trip-page-starter.css` (every hook as a commented empty rule); the
  designer guide is `admin/help/stylesheet.xhtml` (stateless, in the anonymous sweep). Local mode has no bucket:
  the bytes live in a `LocalObjectStore` served by `web/StylesheetServlet` at `/trip-stylesheets/{key}`, which is
  what `MediaCommands.getUrl` answers for a stylesheet key when no media host is configured (`FakeData` seeds one
  sample row for CFPW). The editor's state is viewScope scalars (`cssOpen`, `cssItemId`, `cssTitle`, `cssText`); a
  JSF `required` on its title would silently fail the page's other postbacks (one form), so `saveOnly` refuses a
  blank title instead.

Edited on the trip editor's "Trip Page" dialog (`WEB-INF/tripPageDialog.xhtml`, the trip's managers, every kind,
hidden while a trip is being created; binds into the `TripEditDrafts` draft, Done refuses with growls and stays open on `pageOk`, Save
persists; the page's edit-mode toolbar links there as "Page settings"), and on the API as `TripDto.page`
(`TripPageOptionsDto`, absent = leave alone, `""` = back to the org's, 400 `VALIDATION_FAILED` on a problem, read back
by managers only). `TripCommands.saveTrip` refuses the same problems, since a dialog closed with its X skips Done. A
styles template (an org-scoped STANDARD template holding a `<link rel="stylesheet">`, `content-templates.md`) remains
possible for a single section. Fonts loaded from the media CDN need a CORS policy (`MediaStack`), which it does not
have yet; Google Fonts load fine.

**Wide content.** An imported description is often wider than a phone. `site.css` keeps it inside the window:
`.grid > .col{min-width:0}` (primeflex's flex column otherwise grows to its widest content), and pictures, iframes and
videos inside a section shrink to fit.

**Later phases, not built.** A per-conference subdomain needs `SiteIndex`/`SiteContext` to resolve a label to a
trip (a new site mode carrying the trip id, `ListingScope` and branding for it); a custom domain additionally needs
its certificate, DNS and a host-to-trip map, plus a host-only cookie domain.

## Shared helpers

- `util/Ages.ageAt(birthdate, on)`: the one age computation. Pricing reads age on the trip's FIRST day.
- `Concession.Kind.FIXED_PRICE`: "costs at most `amountCents`", the child cap and the exact-price discount code
  in checkout pricing. Lodging's menus list their kinds by hand and do not offer it.

## Past conferences from the old deployment

The 2025 and 2026 conferences, their people, registrations, check-ins, ledger and roles come over once from the old
`mir2026` account through the conference import (`docs/migrations/conference-import.md`): plan, rehearse in memory,
apply after the `conference` deploy, verify.

## Local seed

`FakeData.CONF_TRIP_ID` ("2027 Mar: Peace Conference Demo", mirrored in the webtests' `SeededIds`): a public CFPW
conference 75 days out with a required priced admission question (including a hotel-guest choice behind an
attestation), a priced add-on that age pricing does not touch, a pick-one shirt size, free-text dietary needs, a
two-day agenda with one event hidden from the public schedule, Trinity (user4) CONFIRMED with a ticket and Dave
(user5) PENDING. The registration seed is guarded on the trip, not on a person, because a re-seed mints new fake
people.

## Tests

`ConferenceModelTest`, `ConferenceFoundationsTest`, `TripPageTest` (key, authorization, picker, template
choices, site reach, agenda, registration block, seeding by kind, the legacy page and its conversion),
`ConferenceTypesTest`, `TicketCodesTest`, `AgesTest`, `EmbedStylesTest`, additions to `PersonTypeTest`,
`RenderPathCacheTest`, `TripsResourceTest` (`detailsUrl`) and `RegistrationsResourceTest`. Webtests:
`TypedQuestionsPwIT` (menus, checkboxes and prices render; an unanswered required question is refused by name;
stored answers are choice ids), `ConferencePagePwIT` (anonymous and session-free, seeded sections and anchors,
public schedule only, places and prices, the manager's Edit page and nobody else's, a trip's own starter page) and
`TripPageLegacyPwIT` (a legacy trip's description on both hosts, the editors-only convert button, the converted
section, the embed's chrome-free session-free render).
