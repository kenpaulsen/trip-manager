# Priced checkout — read before touching pricing, CheckoutCommands, or a checkout payment

A trip can register people through **priced checkout** instead of the classic free-text form: each person's price is
computed from the choices they pick, their age on the trip's first day, and a discount code, and a family registers
and pays in one step. It is an option of EVERY trip (`Trip.checkout.enabled`), not only conferences
(`docs/conference.md`). Landed 2026-11 on the `conference` branches, on top of the Stripe processor work
(`docs/payments.md`).

## The design in one paragraph

Prices live on the trip's TYPED registration questions (`OptionChoice.priceCents`): "Full weekend / Friday only" is a
required pick-one question, a T-shirt is a pick-any add-on. The trip's `CheckoutConfig` holds only the POLICY that
applies across them: on/off, auto-confirm, the registration window, age bands and discount codes. Age bands and codes
reuse lodging's `Concession` for their adjustment (COMPLIMENTARY, FIXED_PRICE "at most $X", PERCENT, TOTAL). Checkout
is **pay-first**: nothing is registered until the money is recorded. The party's rows ride on the `Payment` as a
`PendingParty` and are written by the one completion path every payment already has, so the return page, Stripe's
webhook and an admin's Finish all register a party the same way. One concept each: questions, answers, payments,
Bills and the confirmation path are the existing ones, extended.

## Pricing (`org.paulsens.trip.checkout`, pure)

`CheckoutPricing.price(trip, birthdate, answers, codeText, now)` answers a `PriceBreakdown` built in this order:

1. **BASE**: the pick of the TYPE question, the first shown, required, pick-one question with a priced choice. Its
   label is the person's registration type. A $0 admission pick still gets its line, so it still names the type.
2. **ADD_ON**: every other picked priced choice, in question order. A $0 add-on gets no line.
3. **AGE_BAND**: right after the choice it changed, when the person's age on the trip's first day
   (`util/Ages.ageAt`) falls in a band and the choice's question lets bands apply (`ageBandsApply`, off for a
   T-shirt). No birthdate: no band, and one NOTE line "No birthdate on file: priced as adult".
4. **DISCOUNT_CODE**: when the code is valid, its concession on the after-age subtotal of the choices it applies to.
5. **FLOOR**: a backstop to $0 (every concession already clamps, so it does not fire today).

Worked example (the golden test `CheckoutPricingTest`, `GoldenTrip`): Full weekend $150 / Friday $60 / Saturday $60,
a $20 T-shirt that age bands skip, bands 0-3 free and 4-10 "at most $50", codes SPEAKER ($0 for the full weekend,
five uses) and EARLY10 (10% off until January 31). An adult's full weekend is $150; a 7-year-old's is
"Full weekend $150.00; Child (4-10) at most $50.00 -$100.00 = $50.00"; SPEAKER with a T-shirt is
"Full weekend $150.00; T-shirt $20.00; code SPEAKER -$150.00 = $20.00".

`PriceBreakdown.summary()` is written into the person's Bill, which the ledger compares byte-for-byte on every
re-record: its wording is pinned by tests.

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
  already registered, waivers, capacity), builds the pending rows (status CONFIRMED when `autoConfirmPaid`, the
  default, else PENDING; answers; the party stamp; the parked chat digest answer; attestations with the TEXT ticked;
  breakdown; ticket for a CONFIRMED row) and the payment through `PaymentCommands.newPayment` and `startPrepared`,
  the same code the payment page uses. It answers a redirect (`approvalUrl`) or an inline start (Stripe's Payment
  Element, `clientSecret`); a party that owes nothing is recorded at once as an OFFLINE "FREE" payment.
- `recordOffline(...)`: registration admins only. CHECK, CASH, COMP or OTHER, with a reference, the amount and the
  date. The amount must equal the total unless "partial" (the Bills still bill full prices and each balance shows the
  rest); COMP is always the full total. It records through `PaymentCommands.recordOffline`, the same completion path.
- `finalizeParty(payment, trip)`, called by `PaymentCommands.recordNow` right after the ledger write and before the
  RECORDED transition, inside the same guarded step: writes each pending row not already there, joins the person to
  the trip's org, puts CONFIRMED people on the roster (one trip save) and runs `RegistrationCommands.afterConfirmed`
  (digest preference, audit, push: what Approve does), and writes each person's Bill
  (`{paymentId}-bill-{personId}`, category "Registration", through `pay/Bills`, shared with lodging). A paid person's
  trip balance is therefore zero. Someone registered in the meantime by another route is never overwritten, only
  noted; an overrun of capacity is noted too, never refused, because money has moved. A sandbox payment registers
  nobody, as it writes no ledger rows.
- The confirmation mail is the `checkout-confirmation` MAIL starter (`PaymentMailer.sendCheckoutConfirmation`): one
  line per person with type, price, status and ticket code, the code linking to the ticket page (its QR is what
  check-in scans, `docs/checkin.md`).

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
- The trip editor's Registration Options dialog has a Pricing fieldset: on/off, auto-confirm, the window, and the
  age-band and code tables, whose rows are removed by identity (`rowKey`). Done refuses an inconsistent policy and
  stays open, and `TripCommands.saveTrip` refuses it again on every save path (the dialog's X skips Done).
  Nothing persists until the trip page's Save.
- `admin/tripRegistrations.xhtml` adds Type and Paid columns and a details dialog with the stored breakdown;
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

`GET /api/trips/{id}/registrations/pricing` answers the policy to any signed-in caller, with the discount codes only
for the trip's staff (a code is a secret its holders were given). `PUT` replaces it, trip managers only, refusing a
policy with problems. Prices are the trip's typed questions on the trips API.

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
`CheckoutConfigDialogPwIT`, built on `WebFixtures.pricing(tripId, json)`.
