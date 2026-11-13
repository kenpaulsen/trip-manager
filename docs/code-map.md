# Code map: action beans and the conference / trip page / checkout / check-in overview

Reference detail that used to sit in `CLAUDE.md`. Nothing loads this file automatically: read it when you need to find a
bean by its EL name, or before touching conferences, trip pages, checkout or check-in. Paths are relative to the repo
root.

## CDI action beans (`org.paulsens.trip.action`)

`@Named @ApplicationScoped` beans exposed to JSF EL. Current names: `trip` (TripCommands), `people` (PersonCommands),
`reg` (RegistrationCommands), `txCmds` (TransactionsCommands), `todo`, `bind`, `priv`, `pdv`, `pass`, `mail`, `chat`,
`audit`, `auditView`, `config` (ConfigCommands -- admin Settings page), `media`, `reports` (ReportCommands -- the trip
report pages' scalar rows; see `docs/reports.md`), `profilePhotos`, `chatPhotos` (ChatPhotos -- chat photo
storage/staging/album), `photoChat` (PhotoChatCommands -- per-photo comment threads/reactions; see
`docs/photo-comments.md` before touching), `content` (ContentCommands -- template-driven page sections) and
`contentTemplate` (TemplateCommands -- the template manager; see `docs/content-templates.md` before touching either --
MAIL-kind templates are runtime-editable email copy rendered by `MailCommands.sendManagedTemplate`, tokens filled by
Java only), `family` (FamilyCommands -- family accounts; the create-and-link security boundary, see
`docs/family-accounts.md`), `support` (SupportChatCommands -- the support:main channel; requests post without
membership), `org` (OrgCommands -- organizations, THE tenancy boundary: membership, org-scoped privileges + the per-org
allow-list, processor configs, the payment-config ladder; see `docs/org-admin.md` and `docs/payments.md` before
touching), `payment` (PaymentCommands -- the whole payment flow in Java: quote/start/complete/cancel + reconciliation +
paymentsAdmin sandbox; see `docs/payments.md`), `site` (SiteCommands -- the request's resolved `SiteContext`: shared /
org / marketing host, its page key, org name, org-site URLs; the ONLY sanctioned window onto which site a request is for
-- never copy its answers into session or view scope; see `docs/org-admin.md` "Organization sites"), `brand`
(BrandCommands -- the org site's look: theme/layout names, logo or wordmark, favicon, footer, contact card, donate link,
analytics id, ALL from the org's own org-only Branding settings; neutral/null on every other host so shared pages keep
their literal chrome; see `docs/org-admin.md` "Branding"), `brandingUpload` (BrandingUploadCommands -- the Appearance
page's four image uploads through the shared crop dialog, ONE bean carrying the `BrandingRole` chosen when the dialog
opens) and `brandingPhotos` (BrandingPhotos -- their versioned `org/{orgId}/branding/{role}-{version}.{ext}` store,
whose KEPT previous versions are what makes a replaced image recoverable; see `docs/org-admin.md` "Uploading the four
images"), `lodging` (LodgingCommands -- accommodations, room types, rooms, floor maps, offers, reservations, the
room-assignment board, lodging bills, the itinerary rows; see `docs/lodging.md` before touching), `budget`
(BudgetCommands -- the trip's planning budget: typed inputs, shared and per-person lines, lodging lines reading the
trip's options live, the page's rows and the dashboard headline; edit = tripMgr/tripFinAdmin, view adds tripFinView and
the global viewFinances; arithmetic in `pay/BudgetMath`; see `docs/budget.md`; both gates live in `TripFinanceGate`,
shared with the expense report), `expenses` (ExpenseCommands -- the trip's shared expense report: details, currencies,
expenses matched to budget lines, the submit stamp, budget vs actual, payments recorded behind the narrower settle gate
(site admin or `tripFinAdmin` only, `TripFinanceGate.canSettle`); arithmetic in `pay/ExpenseMath`; the PDF
(`ExpenseReportPdf`, receipts appended) and the email to the org's treasurer (`finance.treasurer.email`) through
`MailCommands.sendWithAttachment`, the ONE send that carries a file (raw MIME over SES `SendRawEmail`); both money PDFs
draw with `action/PdfSheet` and download from `web/TripMoneyPdfServlet`; see `docs/expense-report.md`),
`expenseReceipts` (ExpenseReceipts -- the report's receipts, images or PDFs, in the media bucket under
`receipts/{tripId}/` and slot `receipts-{tripId}`, served ONLY by `web/ExpenseReceiptServlet` at
`/expense-receipts/{id}` behind the report's view gate: never a CDN URL, fenced out of every media listing, and the CDN
denied the prefix by `MediaStack`), `lodgingAccess` (LodgingAccessCommands -- the lodging gates' bodies, re-exposed by
`#{lodging.can*}`, and `forTrip(tripId)`: ONE per-request `LodgingAccess` -- `internal`, `operatorAccIds`,
`allowedAccIds`, `canAssign`, `operatorHere` -- that `trip/lodging.xhtml` and `roomInvoice.xhtml` keep as
`requestScope.access`; also `mayNameTrip`, the one naming rule for cross-trip reads), `hotel` (HotelCommands -- the same
hotel seen from its own side: the nights its rooms are blocked and how full it is across EVERY trip staying there,
counts and dates but never a guest's name in the calendar; plus `tripRows`, the Trips tab's rows for the hotel's
OPERATORS alone, which DO name the guests at their hotel across orgs -- the documented tenancy extension; same doc) and
`lodgingUpload` (LodgingUploadCommands -- hotel photos, room-type photos and floor plans through the shared crop dialog
into slot `lodging-{accId}`), `ratePlans` (RatePlanCommands -- hotel rate plans and their write-through link to offers:
a plan is the hotel's COST side only (`canEditCost` is its one gate), `savePlan` fans the cost out to every linked
option and never recomputes a bill, `attach(AttachForm)` on the trip side creates the option WITH its own participant
price, `detach` cuts it loose; see `docs/lodging.md` "Rate plans"), `waiver` (WaiverCommands -- trip documents: an org's
versioned library, a trip's pinned versions with the re-sign decision, the two-box signature, the `WAIVERS_REQUIRED`
registration gate and the signed-copy PDF; see `docs/waivers.md`), `checkin` (CheckinCommands -- tickets, staff
check-in, counts, the trip-bound balance, batch badges and the export rows; see `docs/checkin.md`), `tripStylesheets`
(TripStylesheets -- a trip page's own stylesheets: one `text/css` library item each under `org/{orgId}/stylesheets/`,
saved in place with a `rev` bump and a CDN invalidation, gated to the org's admins and media admins, with the "Classes
on this page" report and the CodeMirror completion; local bytes served by `web/StylesheetServlet`; see
`docs/conference.md` "Look and feel"), `deploy`, `json`, `tripUtil`.

Rules for these beans: `CLAUDE.md` ("Rules that apply everywhere") and `.claude/rules/page-state.md`.

### People lists and people search

Every page that lists or searches people follows one rule (owner decision 2026-11-12):

- **Matching** is `dynamo/PersonMatch`: each query word must match; names and cell digits match anywhere inside,
  email only from its start. The cached directory search (`PersonDAO.searchPeople`, behind `people.searchPeople`,
  `people.adminSearchPeople` and every people picker) and in-memory lists use the same class. Its index:
  `docs/caching.md` "The people search index".
- **Listing** is `people.listOrSearch` (a `PeopleResult`): a blank query shows everyone when they fit
  `PersonCommands.PEOPLE_LIST_LIMIT` (60), otherwise asks for a search; matches are capped at the same limit and
  flagged `truncated`. Org People (`org.searchMembers`), trip Contacts (`people.contactsRoster`) and the admin People
  page (`people.adminSearch`, which always asks first: the directory never fits) all use it.
- **Order** is `Person.PEOPLE_SORTER` (last, first, then id), set by the SERVER. People tables carry no `sortBy` or
  `filterBy`: a table whose value is rebuilt per request cannot hold a PrimeFaces in-place sort, and a `sortBy` naming
  a variable set in a row `beforeEncode` never sorted at all. The uncapped roster reports freeze
  `people.sortedIds(...)` in the view.
- **Row commands** carry their person in an `f:param` and are ajax; a table's rows are resolved in `initPage` (before
  decode) as well as before encode. The admin People page's Privileges column is `org.privilegeSummaries`
  (`PrivilegeSummaries`): whole partitions read once per request, site grants for site admins only, and only the
  orgs whose people the viewer may see.

## Conferences, trip pages, priced checkout, tickets and check-in

**Conferences** (`docs/conference.md`, required reading before touching any of these): a conference is a `Trip` with
`kind` `TripKind.CONFERENCE` (null reads as PILGRIMAGE; kind changes copy, never permissions -- except that creating a
conference, or making or unmaking one, takes the org-scoped `addConference`, which org admins are NOT implied and the
org's allow-list must offer at use: docs/conference.md, "Access"); `RegistrationOption` is TYPED (`QuestionType`
TEXT/CHOICE/MULTI_CHOICE/YES_NO, `required`, `OptionChoice`s with optional price, capacity, attestation, days) -- a
conference's admission types are a priced pick-one question, NOT a separate model; answers store choice IDS and every
page shows them through `reg.answerText`; `Registration` carries `paymentId`, `ticketCode` (`security/TicketCodes`;
every CONFIRMED row has one, enforced in `saveRegistration`), `registrationType`, `priceBreakdown` (`PriceBreakdown`,
stored once, never recomputed), `attestations` and the check-in stamps; `TripEvent` agenda details
(`PRESENTER`/`LOCATION`/`TRACK`/ `AGENDA_HIDDEN`; `hasDetails()` counts transport keys only); the trip role
`checkinStaff`. EVERY trip's PUBLIC PAGE (both kinds since 2026-11) is content sections under
`page:trip:{tripUuid}:home` (`OrgPageBootstrap.tripPageKey`), the body `WEB-INF/tripPageBody.xhtml` hosted by
`trip/tripDetails.xhtml` (the site) and `trip/tripPage.xhtml` (the chrome-free EMBED the apps fetch through
`TripDto.detailsUrl`, styles inlined by `TripPageCommands.embedCss`), edited by the trip's `tripMgr` and its org's
content admins, seeded once on save (`TripPageCommands.ensurePage`; starters by kind, `OrgPageBootstrap.tripRows`). A
trip saved before every trip had a page still renders its old `description` read-only (a LEGACY page,
`TripPageCommands.isLegacyPage`) until "Edit page" converts it (`convertLegacyPage`: the description verbatim as one
text-only section); nothing edits `Trip.description` any more. The bean's views (`#{tripPage}`: `tripFor`,
`publicAgenda`, `registrationInfo`) are cached-read-only render code. Its look is the trip's own `Trip.page`
(`TripPageOptions`: layout, background, title, section links, a stylesheet from the org's media library; null = the
org's Appearance setting; `TripPageCommands.lookFor` renders it, `pageProblems` guards it on both save paths) laid over
the org's Appearance page (`docs/conference.md`, "Look and feel"); its placeholder sections are seeded with a passed
"Show until" (`ContentInstance.eventDate`) so visitors never see them, and their first save publishes them
(`OrgPageBootstrap.isSeededPlaceholder`, cleared in `ContentCommands.saveContent`; a child's save publishes its
container). `photo-albums` fits a conference page and shows that event's own album. **Priced checkout**
(`docs/checkout.md`, required reading before touching pricing or a checkout payment): the pure engine in `checkout/`
(`CheckoutPricing`, `CheckoutValidator`, `CheckoutCapacity`), the policy `Trip.checkout` (`CheckoutConfig`: the payment
switch, an optional per-person `depositCents` + `balanceDueOn`, auto-confirm (null = OFF), window, `AgeBand`s (admission
price only), `DiscountCode`s; prices stay on the typed questions), bean `checkout` (`CheckoutCommands`), PAY-FIRST: the
party rides the `Payment` as a `PendingParty` and `PaymentCommands.recordNow` writes it via `finalizeParty`;
`ProcessorType.OFFLINE` for checks, cash, comps and $0 parties; `pay/Bills` is the one deterministic Bill writer
(lodging and checkout). `Payment.hasCheckout()` must never become a bean getter (`docs/checkout.md`, "Idempotency").
Tickets and check-in (`docs/checkin.md`, required reading before touching them): ONE bean, `CheckinCommands`
(`#{checkin}`), behind the anonymous TRANSIENT ticket page (`trip/ticket.jsf`, inline SVG QR from `util/TicketQr`, never
`p:barcode`), the staff check-in page, the batch badges, the export rows and the four check-in endpoints on
`RegistrationsResource`; a registration's type and days come from the trip's type question
(`RegistrationOption.typeQuestion`), a person's money is `tripBalance` (rows bound to THAT trip only, never
`getBalance`), and listings never carry a ticket.
