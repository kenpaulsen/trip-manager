# Priced checkout — read before touching pricing, CheckoutCommands, or a checkout payment

A trip can register people through **priced checkout** instead of the classic free-text form: each person's price is
computed from the choices they pick, their age on the trip's first day, and a discount code, and a family registers
and pays in one step. It is an option of EVERY trip (`Trip.checkout.enabled`), not only conferences
(`docs/conference.md`). Landed 2026-11 on the `conference` branches, on top of the Stripe processor work
(`docs/payments.md`).

## The design in one paragraph

Prices live on the trip's TYPED registration questions (`OptionChoice.priceCents`): "Full weekend / Friday only" is a
required pick-one question, a T-shirt is a pick-any add-on. The trip's `CheckoutConfig` holds only the POLICY that
applies across them: whether registration requires payment, what is due at registration (the full price, or a
per-person DEPOSIT with an optional balance due date), auto-confirm (default OFF since 2026-11-02), the registration
window, age bands and discount codes. Age bands and codes reuse lodging's `Concession` for their adjustment
(COMPLIMENTARY, FIXED_PRICE "at most $X", PERCENT, TOTAL); an age band adjusts the ADMISSION price only, never an
add-on (the per-question `ageBandsApply` opt-out was retired on 2026-11-02 as too fiddly; a stored flag is ignored).
Checkout is **pay-first**: nothing is registered until the money is recorded. The party's rows ride on the `Payment` as a
`PendingParty` and are written by the one completion path every payment already has, so the return page, Stripe's
webhook and an admin's Finish all register a party the same way. One concept each: questions, answers, payments,
Bills and the confirmation path are the existing ones, extended.

## Pricing (`org.paulsens.trip.checkout`, pure)

`CheckoutPricing.price(trip, birthdate, answers, codeText, now)` answers a `PriceBreakdown` built in this order:

1. **BASE**: the pick of the TYPE question, the first shown, required, pick-one question with a priced choice. Its
   label is the person's registration type. A $0 admission pick still gets its line, so it still names the type.
2. **ADD_ON**: every other picked priced choice, in question order. A $0 add-on gets no line.
3. **AGE_BAND**: right after the admission line, when the person's age on the trip's first day (`util/Ages.ageAt`)
   falls in a band. Only the admission pick is ever adjusted (`CheckoutPricing.isAdmissionQuestion`); a child's
   T-shirt costs what a T-shirt costs, and a children's dinner is its own choice at its own price. No birthdate:
   no band, and one NOTE line "No birthdate on file: priced as adult".
4. **DISCOUNT_CODE**: when the code is valid, its concession on the after-age subtotal of the choices it applies to.
5. **FLOOR**: a backstop to $0 (every concession already clamps, so it does not fire today).

Worked example (the golden test `CheckoutPricingTest`, `GoldenTrip`): Full weekend $150 / Friday $60 / Saturday $60,
a $20 T-shirt that age bands skip, bands 0-3 free and 4-10 "at most $50", codes SPEAKER ($0 for the full weekend,
five uses) and EARLY10 (10% off until January 31). An adult's full weekend is $150; a 7-year-old's is
"Full weekend $150.00; Child (4-10) at most $50.00 -$100.00 = $50.00"; SPEAKER with a T-shirt is
"Full weekend $150.00; T-shirt $20.00; code SPEAKER -$150.00 = $20.00".

`PriceBreakdown.summary()` is written into the person's Bill, which the ledger compares byte-for-byte on every
re-record: its wording is pinned by tests.

**Deposits** (2026-11-02) change what is CHARGED, never what is priced: `CheckoutConfig.dueNowCents(price)` is the
person's price capped at `depositCents` (null = the full price). `CheckoutCommands.prepare` carries both the full
prices (`allocations`, the Bills, the most an offline payment may record) and the due-now amounts
(`dueAllocations`, what the processor charges, with the payer-pays fee computed on them). The Bill is always the
full price, so the remainder is simply the person's trip balance, paid later from the trip's Pay page: the same
shape a partial offline payment always had. `balanceDueOn` is shown to registrants (the totals panel, the result
panel, the confirmation mail's status note) and never enforced.

`CheckoutValidator.problems` refuses: the window closed (`opensAt`/`closesAt`, or the trip started); a shown required
question unanswered; a pick naming an unknown or withdrawn choice; a picked choice whose attestation was not ticked;
a code that is unknown, inactive, expired, at its `maxUses`, or applies to nothing picked. `CheckoutCapacity.problems`
refuses a party that would pass `regLimit` or a choice's `capacity`, counting CONFIRMED and PENDING rows. Uses of a
code are COUNTED from stored breakdowns, never kept on the trip, so checkout traffic never writes the trip row. Every
problem's message is a whole sentence, because pages put it in a growl summary.

## The flow (`action/CheckoutCommands`, `#{checkout}`)

- `quote(trip, draft, party)`: the live totals under the form, each person's breakdown and problems, the fee when
  the payer pays it (the payment page's fee rule, `MoneyMath.creditFeeCents`).
- `start(trip, draft, party, payerId, sandbox, pageUrl)`: records waiver agreements first (consent stands even if
  the payment is abandoned, as in party registration), re-validates with the start-only checks (who may register whom,
  already registered, waivers, capacity), builds the pending rows (status CONFIRMED when `autoConfirmPaid` is
  TRUE, else PENDING, the default; answers; the party stamp; the parked chat digest answer; attestations with the TEXT ticked;
  breakdown; ticket for a CONFIRMED row) and the payment through `PaymentCommands.newPayment` and `startPrepared`,
  the same code the payment page uses. It answers a redirect (`approvalUrl`) or an inline start (Stripe's Payment
  Element, `clientSecret`); a party that owes nothing is recorded at once as an OFFLINE "FREE" payment.
- `recordOffline(...)`: registration admins only. CHECK, CASH, COMP or OTHER, with a reference, the amount and the
  date. The amount must equal what is due at registration (the prices, or the deposits) unless "partial", which admits
  any amount up to the full prices (the Bills still bill full prices and each balance shows the rest); COMP is always
  the full total. It records through `PaymentCommands.recordOffline`, the same completion path. The payment keeps
  the receipt STRUCTURED (`Payment.offline`, a `Payment.OfflineReceipt` of method, reference and received-on; FREE
  and COMP are methods) beside the one-line label in `captureId` ("CHECK 1042", "FREE"), which the ledger
  descriptions and the mail still print unchanged. Readers go through `Payment.getOfflineReceipt()`, which parses
  the label for offline rows stored before the receipt existed (2026-11), so every offline payment answers a
  method, and reports can total by it: the reconciliation page's Method column, the Registrations tab's
  "Paid with" line, the export's optional Payment-Method / Payment-Reference / Payment-Received columns, and the
  `party/offline` REST response (`docs/api-contract.md`).
- `finalizeParty(payment, trip)`, called by `PaymentCommands.recordNow` right after the ledger write and before the
  RECORDED transition, inside the same guarded step: writes each pending row not already there, joins the person to
  the trip's org, puts CONFIRMED people on the roster (one trip save) and runs `RegistrationCommands.afterConfirmed`
  (digest preference, audit, push: what Approve does), and writes each person's Bill
  (`{paymentId}-bill-{personId}`, category "Registration", through `pay/Bills`, shared with lodging). A paid person's
  trip balance is therefore zero. Someone registered in the meantime by another route is never overwritten, only
  noted; an overrun of capacity is noted too, never refused, because money has moved. A sandbox payment registers
  nobody, as it writes no ledger rows.
- The confirmation mail is the `checkout-confirmation` MAIL starter (`PaymentMailer.sendCheckoutConfirmation`): one
  line per person with type, price, status and ticket code, the code linking to the ticket page with the QR
  itself as an image under it (`/ticket-qr`; the QR is what check-in scans, `docs/checkin.md`).

## The pages (`../medjugorje/webapp`)

- `trip/joinTrip.xhtml` is the one registration page. With checkout on it becomes a pay-first form; a classic trip
  renders exactly as before. Each traveler's choices show that person's own price after their age band
  (`regQuestionInput.xhtml`'s `pricePerson`), a picked choice needing a statement shows its checkbox, and each
  traveler has a code box. The totals panel is `checkout.quote` on every render: each person's price with its
  breakdown lines, the fee when the payer covers it, the total and every problem. Already-registered rows are
  read-only: their answers are what was paid for.
- "Pay and register" is a full postback, like the payment page's Pay: it redirects for a redirect processor,
  mounts the shared `WEB-INF/payInlineStep.xhtml` in place for an inline one (the payment page uses the same
  fragment), or registers a $0 party at once. The processor returns to the same page with `?payment=` (or
  `&cancelled=true`); `initPage` completes or cancels and shows a result panel: registered (name, type, price,
  status, ticket per person), sandbox dry run, cancelled, failed, processing (a settling bank debit: nobody is
  registered yet) or mismatch. The payer is `checkout.payerFor`: the subject for a registration admin, else the
  caller, so a family manager acting for a member pays as themselves.
- Registration admins get "Record offline payment" (method, reference, amount, date received, partial) and may
  open the Registrations tab's `?id=` links on a priced trip (the page's gate becomes `registrationAdmin` there).
  The tab's "details" dialog then says how the row was paid ("Check 1042, received 10/30/2026", or the processor
  and its capture id), `admin/payments.xhtml` carries a Method column beside the capture id, and
  `admin/reports/exportTrip.xhtml` offers Payment-Method, Payment-Reference and Payment-Received, none selected
  by default so an export with the default columns is byte for byte what it was.
- The trip editor has TWO dialogs side by side on its property sheet (2026-11-02): Registration Options
  (`WEB-INF/regOptionsDialog.xhtml`, the questions and their choices' prices) and Registration Pricing
  (`WEB-INF/tripPricingDialog.xhtml`, the policy: the "Registration requires payment" switch, Full amount or Deposit
  per person with the balance due date, auto-confirm, the window, and the age-band and code tables in fieldsets
  collapsed while empty, rows removed by identity (`rowKey`)). Both `fitViewport`, so a short window scrolls the
  content instead of hiding the footer, and the pricing dialog is one 760px column (two inputs stacked per code
  cell) so it never scrolls sideways. Beside the pricing link the sheet shows `checkout.policySummary` ("on: deposit
  $200.00 per person, approval required, 2 age bands"). Done refuses an inconsistent policy and stays open, and
  `TripCommands.saveTrip` refuses it again on every save path (the dialog's X skips Done). Nothing persists until
  the trip page's Save. The pricing link renders only when the trip resolves to a processor (`isPayable`) or
  already requires payment (so it can be turned off); otherwise the cell explains, and a payments admin gets a
  link that opens Payment Settings, which sits directly above it and whose Done re-renders the cell (2026-11-02).
  The options table is four columns since 2026-11-02: each question's text above its help text, its Type above its
  choices (chips with prices, and the "Edit choices" link). New options start blank and shown to users, and
  `saveTrip` refuses a shown option with no name (`TripCommands.UNNAMED_OPTION`). The deposit radio sits above the amount field because JSF decodes in tree order:
  `setDueMode` runs first and `setDepositDollars` ignores an amount while the radio says Full amount.
- `admin/tripRegistrations.xhtml` adds Type and Price columns (the stored price, what the Bill says; a deposit
  charges less, and the balance shows on check-in and the person's transactions) and a details dialog with the
  stored breakdown;
  `admin/payments.xhtml` adds a Checkout column (`intentSummary`). An abandoned processor checkout is the only
  place an unpaid party is ever visible; an offline payment is RECORDED at once and never appears there.
- EL reads record COMPONENTS only: on `Quote` call `getTotal()`, `getFee()` and `isFree()` as methods.
- A visit that comes back (a cancel at the processor) keeps each person's code and ticks
  (`RegistrationDraft.carryCheckoutFrom`, called by `loadDraft`), and whoever may complete a checkout payment may
  cancel it, so the admin who started one for someone else can change it.

## Idempotency and failure

- Everything is re-runnable: rows are written only if absent, Bill ids are deterministic, ticket codes are minted
  once on the pending row, the roster is a set, `joinOnRegistration` is idempotent.
- A failed finalize leaves the payment CAPTURED: `admin/payments.jsf`'s Finish re-runs record and finalize.
- A gross mismatch parks the payment in CAPTURED before recording, so NO registration exists until an admin reconciles.
- A payment RECORDED with an unfinalized party (written by a build that predates checkout) is finalized on its next
  completion visit, without a second mail.
- A bank debit that is still settling keeps the payment CREATED: no registration exists until Stripe's webhook
  records it, and a debit that is later returned simply never registers anyone.
- **`Payment.hasCheckout()` is deliberately not `isCheckout()`**: Jackson pairs an `isX` with `getX` as one property,
  and an `@JsonIgnore` on it dropped the whole pending party from the stored row (test-caught, `CheckoutModelsTest`).

## REST

`GET /api/trips/{id}/registrations/pricing` answers the policy (including `depositCents` and `balanceDueOn`) to any
signed-in caller, with the discount codes only for the trip's staff (a code is a secret its holders were given).
`PUT` replaces it, trip managers only, refusing a policy with problems. Prices are the trip's typed questions on the
trips API. The quote answers `priceCents`, `creditCents` (due now), `balanceCents`, `depositCents` and
`balanceDueOn` beside the fee and the total.

The checkout itself is `POST .../registrations/party/quote`, `.../party/checkout` and `.../party/offline`
(`docs/api-contract.md`, "Priced checkout"), one `CheckoutRequest` body for all three. Each turns the request into
the page's `RegistrationDraft` and calls the same `CheckoutCommands` method the page does; nothing is priced or
checked at the edge. Two edge-only steps: the document answers are recorded first (as the party POST does), and
`problemsBeforeStart` answers every refusal as coded problems, since a growl reaches no native client. The return
URL is `PaymentsResource.isAllowed`, the payments API's rule.

## Pay-first guards on the classic paths

A checkout trip must not be reachable for free through the older doors:

- `RegistrationCommands.registerPartyOutcome` refuses a NEW registration `CHECKOUT_REQUIRED` while
  `checkout.isOn()` (the join page's classic submit and `POST .../registrations/party`).
- A paid answer (`CheckoutCommands.isPaidQuestion`: checkout on and the question priced) is kept as stored by the
  self-service edit paths: the party edit (`applyOptionEdits`) and a traveler's `PUT .../registrations/{id}`
  (`keepPaidAnswers`). Changing Friday-only to the full weekend would otherwise be free. Staff change a paid
  answer on the admin pages, and the price breakdown stays what was paid.

## Tests

`CheckoutPricingTest`, `CheckoutValidatorTest`, `CheckoutCapacityTest` (pure), `CheckoutModelsTest`,
`CheckoutCommandsTest` (a family through the FAKE processor: rows, tickets, roster, Bills, zero balances, one mail,
idempotent return; the PENDING policy; free and offline payments; sandbox; every refusal; registered meanwhile; the
RECORDED repair), additions to `PaymentMailerTest`, `RegistrationsResourceTest` (pricing, and the three checkout endpoints through
mocked beans) and `PaymentProcessorsTest`; the pay-first guards in `ConferenceFoundationsTest`; the page helpers in `CheckoutViewsTest`. Browser tests
(`../medjugorje/webtest`): `CheckoutPagePwIT`, `CheckoutFreePwIT`, `CheckoutOfflinePwIT`, `CheckoutInlinePwIT`,
`CheckoutConfigDialogPwIT` (the pricing dialog, incl. the short-window and no-sideways-scroll checks), built on
`WebFixtures.pricing(tripId, json)`; `CheckoutOfflinePwIT.aDepositPolicyChargesTheDepositNowAndBillsTheRest`.
