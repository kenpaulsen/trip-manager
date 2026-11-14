# Payments (multi-tenant) — read before touching any payment, organization, or processor code

Landed 2026-08-17. The mir2026 PayPal prototype (env-var singleton, xhtml-driven callbacks) is GONE; this
replaces it end to end. Design decisions here are user-locked — do not relitigate without the owner.

## Organizations: the tenancy boundary

`Organization` is the platform's tenancy root: **every Person belongs to ≥1 org** (multi-org supported),
every Trip belongs to one (`Trip.orgId`; the legacy `provider` display string is SYNCED from the org's FULL
name on every save — `TripCommands.syncProviderFromOrg` — for the public renderers; `isCfpw()` and the
landing filter key on the org's short name, falling back to `provider` only for org-less legacy rows), and
Transactions carry `orgId` (stamped from the trip on new writes; `org-migrate.sh` backfills legacy rows).
Every writer that knows the trip must use `saveTransaction(tx, tripId)`: the one-arg form stamps nothing,
and `trip/transaction.xhtml`'s Save used it until 2026-09-02, so a row recorded on `acme.unitetrip.com`
was org-less — listed on the shared site, invisible on Acme's (a row already carrying an org keeps it, so
re-saving cannot re-tenant one). Re-running `org-migrate.sh` backfills such rows through their
TRANSACTION→TRIP binding.
Design every new data holder along this boundary.

- Membership: `org_members` table (PK orgId, SK personId) is the source of truth; `Person.orgIds` is the
  derived reverse edge (surgically synced by `OrgCommands`, the ONLY writer of org structure). Admins live
  on `Organization.adminIds` (small list; admins are members). Removal enforces admin-first and the
  ≥1-org rule.
- Org ids are canonical UUIDs on purpose (privilege scope suffixes must round-trip).
- Site admins CREATE orgs (`admin/organizations.jsf`) and get the topbar **org switcher**
  (`sessionScope.currentOrgId`, a session preference, not an identity swap). Org admins manage their own
  org from the **org hub** `admin/orgSettings.jsf?orgId=…` (a dashboard of cards; Profile, Trips, People
  and Payment Processors live on their own `admin/org*.jsf` pages behind the same self-gating pattern —
  roster-gated in the page, NOT defaultAuth). See `docs/org-admin.md` for the full org-admin area,
  org-scoped privileges, and the per-org allow-list.
- Org pickers are `p:autoComplete` (contains, case-insensitive) — never a plain dropdown; 100+ orgs is a
  design constraint.

### Money is site-scoped on read (2026-09-01)

What a site LISTS is what it shows of the ledger — the same rule as trips (`org-admin.md`, "What a site can
reach": `ListingScope.reaches(orgId)`). A production validation of the org sites found a payment started
on acme.unitetrip.com showing up on visitqueenofpeace.com; now:

- `TransactionsCommands.getTransactions(userId)` — the ONE read behind the Balance page
  (`trip/transactions.jsf`, sorted/family variants), the trip transaction views, the registration/party
  amounts, `admin/person.jsf`, `GET /api/transactions/...` — returns only the rows whose `orgId` the site
  reaches: an org host lists that org's rows; a shared host lists the rows of the orgs it lists plus
  org-less legacy rows (a row with no `orgId`). `getTransaction(userId, txId)` answers null for a row the
  site does not list, exactly like a row that does not exist.
- **Totals and balances shown on a site are computed from the listed rows only** (`getBalance`,
  `getFamilyBalance`, the pages' running columns): a person's vqop balance is their balance with the
  organizations vqop lists, and their Acme balance lives on Acme's site. There is no all-sites total on any
  page; the whole ledger is what the unbound/system context reads (mail, exports run under
  `RequestContext.system()` reach everything).
- `PaymentCommands.getOpenPayments()` (the `admin/payments.jsf` reconciliation list) is filtered the same way
  on `Payment.orgId`.
- The ledger itself is untouched: payment creation/capture/recording, `PaymentRecorder`, the group-transaction
  helpers (`getGroupTransactionForUser`, `saveGroupTransaction`) and the delete guards read the store
  directly. Scoping is a READ concern. `POST/PUT /api/transactions/people/{id}?trip=` now stamps the org
  from the trip like the page does (`saveTransaction(tx, tripId)`), so an API-created row is tenanted.
- **A trip finance role reaches that trip's books only** (2026-11-12, `action/TripFinanceGate`, the ledger
  half). The site scoping above decides which rows a HOST lists; it does not decide whose rows a
  `tripFinView` / `tripFinAdmin` holder may touch, and over REST the role alone used to be the whole check, so
  a finance admin of any trip could read, rewrite and delete every person's rows on every other trip. The
  transactions API now binds the row to the trip the privilege is on: staff read, update and delete only rows
  whose TRANSACTION→TRIP binding names `?trip=` (or the trip's own ledger rows), create a row only for
  someone on that trip (`TripCommands.isOnTrip`: roster, or a registration that is not NOT_REGISTERED), and a
  group save is held to the same two rules. A person (or their family manager) still reads their whole
  ledger; a site admin reaches every row. An update never re-binds a row to another trip. The website applies
  the same rule (2026-11-12): `trip/transaction.xhtml` opens a row for a trip's finance admin only through
  `txCmds.tripStaffMayOpen` (the row is on that trip's books; a new row's person is on the trip) and otherwise
  drops the role from its auth check, and `trip/transactions.xhtml` hands someone who is there ONLY by a finance
  role `txCmds.getTransactionsOnBooks` (that trip's rows) instead of the whole ledger with the other trips' rows
  hidden by a style class.

## Processor configs and secrets

`PaymentProcessorConfig` rows live in `payment_processors` (PK **orgId**, SK id — the partition IS the
tenancy check; a foreign config id simply misses). Types: PAYPAL, STRIPE (stageable, unbuilt), ZEFFY
(unbuilt), FAKE (local mode). Each carries public ids (`publicConfig.clientId` + a sandbox set), mode
(SANDBOX/LIVE), and fee-estimate overrides (`feeBps`/`feeFixedCents`, 0 = the `ProcessorType` default).

**Secrets never touch DynamoDB.** ONE Secrets Manager secret (`trip/payment-processors`, env
`TRIP_PAYMENT_SECRET`, both task definitions) holds a JSON object keyed by config id —
`security/ProcessorSecrets` (Pepper-style resolution; in-memory in local mode; the task role has Get+Put
because org admins paste credentials through the UI, write-only fields with set/unset badges). A write replaces the
WHOLE secret, so `put` merges into a fresh read of the store and is REFUSED when that read fails or the body is not
the expected JSON (2026-11: merging into a cache that never loaded erased every other organization's credentials); the
cache is one immutable map swapped whole.

## Configuration ladder

`TripPaymentConfig` (all fields nullable = inherit) lives on the Trip (`paymentConfig`, edited in the trip
editor's **Payment Settings** dialog against the TripEditDrafts draft) AND on the Organization
(`paymentDefaults`, org settings page). `OrgCommands.effectivePaymentConfig(trip)` resolves
**trip → org → site settings** (`payment.*` in `KnownSettings`). This trip → org → site shape is the
precedent the general per-org settings ladder follows (`org-admin.md`, "Per-org settings":
`Organization.settingsOverrides` + `ConfigCommands`' org-aware reads); the payment fields keep their own
typed config object rather than moving into that map. Fields: processorConfigId, feesPaidBy,
donation toggle+label, confirmation template id (MAIL kind; installed starter `payment-confirmation`),
mailFrom/replyTo/bcc (**no cc — the SES wrapper has none**), extraTokens. The dialog's **Send Test Email**
renders the effective template with sample values to a PROMPTED address (prefilled with the signed-in
admin's own; never assumed), and its **Preview** button shows the same render in-page
(`OrgCommands.previewPaymentMailSubject/Body`).

`mailFrom` is **not a text box** (2026-08-25): the dialog's From row is an Inherit/Custom menu plus the
shared `/WEB-INF/mailFromComposer.xhtml` — mailbox typed, domain picked from the owning org's allowed
SES-verified domains. `OrgCommands.applyPaymentFrom` composes and validates it on Done / Send Test /
Preview, refusing (growl, config untouched, dialog stays open via the `payFromOk` callback param) rather
than storing an address SES will bounce. `replyTo` and `bcc` stay free text — they are not SES-bound.
See the sending-domain section of `org-admin.md` for the allow-list itself. The trips API applies the same rule
to `paymentConfig.mailFrom` (refused unless it is on one of the owning org's allowed domains, 2026-11-13). Only a
CHANGED From is checked, so a client echoing back a From stored under the older, wider rule keeps saving its other
edits; but moving a trip to another organization re-checks the From it carries, sent or stored, the same way the
carried processor override is re-checked. The site's own `payment.mail.from` setting must be on a sending domain
that SES verifies AND that is shared (what the Settings page's From composers offer; the declaration's
`withSiteFrom()` flag, checked by `ConfigCommands` on save): payment mail with no trip or org From goes out for
every organization. At send time `PaymentMailer` checks the effective From once more against the trip's
organization and, when that organization may not send from it (a row stored before the move check, a custom
domain that changed hands), sends from the site's `payment.mail.from` instead, else the Site email, and logs it.

## The money rules (user-locked; the golden test is `PaymentRecorderTest`)

Lodging bills (`pay/LodgingBiller`, 2026-09) follow these rules too: NEGATIVE `Bill` rows with deterministic
ids, a POSITIVE `Bill` for a cancellation credit, fees in descriptions — `docs/lodging.md`.

Entered amounts are ALWAYS the amounts credited. All math in **long cents** (`pay/MoneyMath`);
`Transaction.amount`'s Float is produced once at the boundary.

- **Payer pays fees** ⇒ the trip-portion fee rides ON TOP: `charged = ceil((credits + fixed)/(1 − rate))`,
  fee exact by construction. **Org pays** ⇒ nothing added.
- **A donation's fee share is ALWAYS absorbed by the org** — shown as an estimate, never added to the
  charge, never deducted from the donation.
- **No separate fee transactions, ever.** Fees live in payment-row descriptions.
- Ledger on success (`pay/PaymentRecorder`, deterministic ids from the paymentId ⇒ idempotent re-record):
  - ≥2 EQUAL credits → ONE SHARED group (`{paymentId}-pay`, amount = credit total; read-time division
    shows each share). Unequal → per-person rows; single → one row.
  - Donation → TWO payer rows: `+D` (txType Payment, "before $X fee") and `−D` (txType **Donation**,
    "Thank you for your donation to {org}!") — visible, balance-neutral.
  - Every description carries the processor **capture id** (searchable in the processor console); every row
    is trip-bound and org-stamped. "split with" names the payer first, then others alphabetically.

## Group transactions: membership IS the divisor (2026-09-17)

A Shared or Batch transaction is not an entity -- it is the set of rows sharing a `groupId`, and each row
carries the whole member list in `groupPeople`. That list is what `getUserAmount` divides a **Shared** amount
by, so it is accounting data, not a convenience:

- **Batch** = identical independent charges. N members, N rows, each for the full amount. 0 members is a
  legitimate (empty) set, so it is simply no transaction.
- **Shared** = one amount split evenly. Each member's share is `amount / groupPeople.size()`. 0 members has
  nothing to divide between, so it is likewise no transaction -- but reaching 0 **undoes** an amount that may
  really have changed hands, which is why the pages warn before they let it happen.

Every removal therefore goes through `TransactionsCommands`, never a bare `delete()` + save:

- `deleteForPerson(tx)` -- the ONE front door for every delete affordance; dispatches on the row's type.
- `removeFromGroup(tx)` -- deletes that member's row **and rewrites `groupPeople` on every surviving row**;
  when they were the last member, deletes the group instead.
- `deleteGroup(tx)` / `deleteGroupById(groupId, knownMembers)` -- the whole transaction. A group save with an
  EMPTY member list does the same thing, which is how the editor's "unselect everyone" works.
- `deleteTransaction(tx)` -- a plain row, and it REFUSES a group row.
- `needsGroupDeleteConfirm(groupId, selection)` -- the rule behind the editor's confirmation, in Java: it
  publishes the `confirmGroupDelete` ajax callback param and the page opens its dialog.

Each of those drops the row's `TRANSACTION->TRIP` and `TRANSACTION->TRIP_EVENT` bindings and writes an audit
record (`AuditCommands.transactionDeleted`, plus one `groupTransactionDeleted` naming the transaction when the
group itself goes). A delete used to be the only ledger mutation with no trail at all.

**The bug this replaced, because the shape of it recurs.** `trip/transaction.xhtml`'s Delete button
soft-deleted one row and stopped. The survivors kept dividing by a person who no longer had a row, so a $150
payment split three ways still reported $50 shares and $50 of it appeared in **no** balance, total or report.
The stale list also made the group editor offer the removed person again, and saving there recreated their row
under a new `txId` -- silently undoing the delete. Rows already corrupted this way are repaired by
`scripts/migrate-group-tx-membership.sh --repair` (`docs/migrations/group-tx-membership.md`); it makes the
surviving rows agree about who is in the group, and the shares follow. It cannot recover money nobody recorded.

Note that `getGroupTransactionForUser` returns only LIVE rows -- `TransactionDAO` filters `deleted` out of
every partition load and evicts a deleted row from the cache. That is why a re-added member comes back under a
new `txId` rather than having their old row revived, and why a group save can never "find" the row it just
deleted.

REST: `DELETE /api/transactions/people/{personId}/{txId}` runs the same dispatch, and needs
`?confirmUndo=true` to remove the last member of a Shared group (409 `CONFLICT` otherwise).
`POST /api/transactions/groups` accepts an empty `people` list as "delete this group" when a `groupId` is
supplied; an empty list with no groupId is still a 400, since that would store nothing under an id the client
could never use.

## Priced checkout rides this pipeline (2026-11)

A registration checkout (`docs/checkout.md`) is an ordinary `Payment` with a `PendingParty` (`Payment.checkout`,
`hasCheckout()`): `CheckoutCommands` builds it through `newPayment`/`startPrepared` (split out of `startPayment`), and
`recordNow` calls `CheckoutCommands.finalizeParty` right after the ledger write and before `CAPTURED -> RECORDED`, in
the same guarded step, so every completion door (return page, Stripe webhook, Finish) registers the party and a
failure leaves the row CAPTURED for Finish. A checkout payment mails `checkout-confirmation` instead of
`payment-confirmation`, and may also be completed by the trip's `registrationAdmin`s. `ProcessorType.OFFLINE` (not
configurable, no processor) is a payment taken by hand or a $0 party: built already CAPTURED, its capture id the
method's label ("CHECK 1042", "COMP", "FREE"), recorded through `PaymentCommands.recordOffline` along the same path.
The label is a DISPLAY form, not the record: the same facts are stored structured in `Payment.offline`
(`Payment.OfflineReceipt`: method, reference, received-on), read through `getOfflineReceipt()`, which parses the
label for rows that predate it. `PaymentCommands.processorNameOf(payment)`, `paymentMethodOf`, `paymentReferenceOf`,
`paymentReceivedOn` and `paidWith` are the one spelling of "how was this paid" for every page and export, and
`Completion` carries the payment id and receipt back to the REST caller.

## The flow (`action/PaymentCommands` — Java-first, user-locked)

ALL flow logic is Java; `trip/payment.xhtml` and `api/PaymentsResource` (v2: `POST payments`,
`/quote`, `/{id}/complete`, `/{id}/cancel`; return URLs allowlisted per `payment.returnUrl.allowedPrefixes`
PLUS any organization site the live `SiteIndex` knows — `https://{slug}.{base}/…`, whole host, https only)
are thin callers of the same methods — that is what makes iOS/Android possible.

`Payment` rows (`payments` table, PK paymentId, **uncached**) are the durable state machine:
`CREATED → CAPTURED → RECORDED`, terminal CANCELLED/FAILED, transitions as conditional puts on the stored
status (double-submits serialize). Completion order: capture → verify (COMPLETED + real capture id/amount —
`PayPalProcessor` never reports a $0 success; ALREADY_CAPTURED recovers via getOrder) → gross==expected
check (mismatch HOLDS in CAPTURED, below) → idempotent record → RECORDED → confirmation mail (failure audited,
never blocks). `admin/payments.jsf` lists non-terminal payments with a "Finish" that reruns the same path and,
since 2026-11, an "Abandon" for a CREATED payment the payer walked away from (cancels the order at the
processor, then CANCELLED; a Stripe PaymentIntent never expires on its own).

**A capture whose answer was lost is PENDING, never FAILED (2026-11, both processors).** FAILED is terminal,
tells the payer "you were not charged" and leaves the reconciliation list. A PayPal timeout, a PayPal 5xx/429,
and an `ORDER_ALREADY_CAPTURED` whose recovery read fails all leave the row CREATED: the next return visit or
Finish converges through `ORDER_ALREADY_CAPTURED`. Only a refusal (a 4xx that says the capture did not happen)
is FAILED.

**Held payments (`Payment.heldReason`, 2026-11).** A CAPTURED payment may be HELD: the processor captured a
different amount than expected, or the webhook reported money for a payment that was CANCELLED or FAILED here
(`paidAfterGivenUp`: the payer's saved form completed it after a cancel; the row comes back as CAPTURED, but
only once the processor CONFIRMS the money; a read that fails or says "not settled yet" answers `pending`, the
webhook's 503, so the event is redelivered instead of being answered "cancelled" for good). The
reason is stored WITH the capture, because every completion door reaches the same `complete()`: the payer's
return page and REST call and the webhook answer `mismatch` for a held payment and record nothing; ONLY a site
admin's Finish (`adminComplete`) records it, and the reconciliation page's Status column shows the reason
(`payment.holdOf`). A CAPTURED row from before the field existed is told by its amounts (an OFFLINE payment's
gross is whatever was received, so it is never held).

**Giving a payment up asks the processor first (2026-11).** `cancelPayment` (the payer's Cancel, "Change
amounts", "Change registration", `POST /api/payments/{id}/cancel`) and the admin's Abandon call
`PaymentProcessor.cancel`, which answers where the order stands, and mark the row CANCELLED only on `OPEN`.
`SETTLING` (a bank debit in flight, which looks exactly like a walked-away payment here: CREATED, no capture
id) and `UNKNOWN` (the processor could not be reached) refuse with a growl; `PAID` finishes the payment through
`complete()` instead. The old order (CANCELLED first, then a processor cancel that could not fail) let an
abandoned debit clear days later into a CANCELLED row.

**Two shapes of start (2026-11).** `startPayment` answers a `Start`: `approvalUrl` for a REDIRECT processor
(PayPal, the fake checkout) or `publishableKey` + `clientSecret` (+ `scriptUrl`) for an INLINE one (Stripe):
the page then renders a second step with the processor's form mounted in place (`trip-js/payElement.js`
over Stripe.js; `trip-js/fakeStripe.js` locally) and the processor redirects to the same `?trip=..&payment=..`
completion path afterwards -- so the page's `initPage` is the one completion door for both shapes. The Pay
button is a FULL postback on purpose: the mounted iframes must never sit inside an ajax-updated region.

**PENDING has two flavors** (`CaptureResult.isSettling()`): with a processor reference, the payer's method was
ACCEPTED and settlement is outstanding (a US bank debit, 3-5 business days) -- the page says "Payment
processing ... nothing more is needed from you" and the row stays CREATED until the webhook (or Finish)
records it; without one, the payer simply has not finished (approved-uncaptured, a form still open) and may
retry. A declined card inside the Element is NOT a terminal failure: the payer retries on the same intent.

**The record race** (fixed 2026-11): the return page and the webhook can both finish one CAPTURED row. The
loser's RECORDED conditional put fails; it re-reads the row and answers the winner's outcome WITHOUT mailing,
pushing or auditing again (`adoptWinner`). Before the fix the loser mailed a second confirmation.

Audit: `AuditAction.PAYMENT` targeted at `payment:{id}` for start/complete/fail, `[SANDBOX] ` prefixed in
sandbox.

## Idempotency (reviewed 2026-11-02; holds across concurrent ECS tasks)

Every guard is a DynamoDB conditional write, never an in-process lock, because a blue/green deploy runs two
tasks and the webhook, the return page and an admin's Finish can hit different ones at the same moment:

- **Create**: `createPayment` is `attribute_not_exists` (one row per start; a double-submitted Pay button makes
  two payments, two orders, and the one the payer never confirms is abandoned, never charged).
- **Capture / record**: `transitionPayment` is conditional on the EXACT stored status, so of two writers moving
  CREATED→CAPTURED or CAPTURED→RECORDED one loses and `adoptWinner`s (no second mail, push or audit). The
  ledger rows are written BEFORE that put, by both writers, which is only safe because every row id is
  DETERMINISTIC and the write is a put: `{paymentId}-pay` (single credit), `{paymentId}-pay-{personId}` (each
  member of a SHARED group or an unequal split), `{paymentId}-don` / `-donx`, `{paymentId}-refund`. The SHARED
  rows were the hole (fixed 2026-11-02): the generic group writer looked a member's row up by group and minted
  a RANDOM id when none existed, so two concurrent recorders each created a row per member and the ledger
  double-credited. `PaymentRecorder` now writes group rows itself with the deterministic id (an existing
  group row keeps its old id). Processor captures are idempotent on their side (PayPal answers
  ORDER_ALREADY_CAPTURED → ALREADY_CAPTURED; Stripe's capture is a read).
- **Refunds**: `Payment.refundedCents` is an optimistic lock. Each delivery writes the ledger row (a put on the
  deterministic id) and then `replacePaymentIfUnchanged`, conditional on the stored row's CONTENT being
  byte-for-byte what it read; a lost race re-reads the payment AND the processor and goes again, so the
  stale-read interleaving (A read $25 before the second refund, B read $100 and finished first, A's row lands
  last) ends with A repairing its own row to $100. Four attempts, then `pending` → 503 so Stripe redelivers.
  Exact duplicates converge on their own (same total, same row). The content compare, not a version
  attribute, so rows written by older builds lock correctly too.
- **FAILED / CANCELLED and their mails** happen only after the conditional put succeeds: once.
- **Re-pricing** (`selectMethod`): the PaymentIntent is updated first, then the row (status-conditional). Two
  overlapping picks could leave the two disagreeing, which the capture's gross-vs-expected check turns into a
  `mismatch` HELD for an administrator rather than a wrong charge; the page serializes its requests and keeps
  Pay disabled while one is in flight, so this needs two browsers on one payment.

## Sandbox mode

The ORG-scoped `paymentsAdmin` privilege — `paymentsAdmin@the-trip's-org`, granted on the org People page;
site admins implicitly qualify, and the retired global variant grants nothing — shows a toggle on the
payment page (`PaymentCommands.isSandboxAllowed(trip)`, which is `isPaymentsAdmin(trip)`; an org-less legacy
trip offers sandbox to site admins only). The same grant opens the trip editor's Payment Settings row (2026-11-02),
and since 2026-11-12 it is also what `POST`/`PUT /api/trips` require to change a trip's `paymentConfig` and what a
`GET /api/trips/{id}` requires to read it back (a trip manager or an `addTrip` creator without the grant gets 403
on a changing write and no `paymentConfig` on the read; an unchanged or empty one passes).
Sandbox runs the SAME pipeline against the config's sandbox credential set, but the recorder **dry-runs** —
no ledger rows, no mail, and the would-have-written rows render in the result panel. Sandbox payments are
excluded from balances and the reconciliation list.

## Processors (`pay/`)

`PaymentProcessor` SPI (createOrder → a redirect `approvalUrl` OR an inline client secret; capture → verified
result, naming the method that paid when the processor says; cancel → release the order and answer where it
stands: `OPEN`, `SETTLING`, `PAID`, `UNKNOWN`), built per config by `PaymentProcessors.forConfig(config, sandbox)`
(memoized per config id+version+sandbox -- rotation without restart). `FakeProcessor` + `trip/fakeCheckout.xhtml`
drive the whole flow locally (webtest: `PaymentPagePwIT`): the CFPW org default is the REDIRECT fake, and the
INLINE fake (`publicConfig.inline=true`, `FakeData.CFPW_INLINE_PROCESSOR_ID`) is what the Summer Demo trip's
own payment config points at for laptop runs and what the webtest points its OWN trip at through the trips
API's `paymentConfig` (2026-11). The fake checkout page doubles as the inline fake's back end (`?async=1` =
pay as bank, `?settle=1` = the webhook stand-in); `FakeProcessor.refund` / `PaymentCommands.fakeRefund` stand
in for a dashboard refund, and the fake carries Stripe's bank pricing so re-pricing is exercised locally.
Zeffy: a future record-only kind.

### Stripe (shipped 2026-11)

- **One place for fees** (2026-11): `PaymentCommands.feesFor(config, payerPays, credits, donation, bank)` is the
  only computation of a payment's fee shares and total; the live quote, `newPayment`, `selectMethod` and
  `CheckoutCommands.quote` all read it. Processor names come from `ProcessorType.getLabel()`.
- **Shape**: `StripeProcessor` creates a PaymentIntent (`automatic_payment_methods`, so the org's Stripe
  dashboard decides which methods the form offers: cards, Link, US bank account, Apple Pay, Google Pay) and
  answers its client secret; the website mounts Stripe's **Payment Element** in `trip/payment.xhtml`, the
  apps present the native **PaymentSheet** with the same secret (`POST /api/payments` → `clientSecret`,
  `publishableKey`, `merchantName`, `paymentId`). Card data is Stripe's iframes / native sheet, never ours:
  the org's PCI scope is SAQ A. Hosted Checkout was considered and rejected (the owner wants an on-page form).
- **Ids**: `orderRef` = `captureId` = the PaymentIntent id (`pi_...`), what Stripe's dashboard, refunds and
  disputes key on; `capture` is a verification READ of the intent (`succeeded` + a positive USD
  `amount_received` is the ONLY success; `processing` = the settling PENDING; `canceled` = FAILED; a transport
  failure = plain PENDING, never FAILED, because FAILED is terminal and would strand paid money). The fee comes
  from the expanded balance transaction when present.
- **Keys**: per org, pasted on `admin/orgProcessors.jsf`. `publicConfig.clientId` = publishable key (REQUIRED
  for STRIPE), secret-store `clientSecret`/`clientSecretSandbox` = secret key, `webhookSecret`/
  `webhookSecretSandbox` = the two endpoints' signing secrets. The sandbox set is Stripe TEST mode; the
  processor refuses a key whose `_test_`/`_live_` segment disagrees with the set. Every SDK call passes the
  key in `RequestOptions`; `ArchitectureTest` bans the process-global `Stripe.apiKey`.
- **Webhook**: `api/StripeWebhookResource`, `POST /api/webhooks/stripe/{configId}` (the page shows the URL),
  deliberately not `@TripApi`: the body's HMAC (`Stripe-Signature`) is verified against THAT config's live
  then sandbox secret before anything is read. It reads only `metadata.paymentId`, the intent id, `status`
  and the failed method's type from the RAW event JSON (the SDK's typed object is empty when the endpoint's
  API version differs), checks the event belongs to the stored row (config, intent id, sandbox vs test
  endpoint, livemode) -- a mismatch is audited and answered 200 -- then calls the trusted
  `PaymentCommands.*FromWebhook` paths under the system actor: `payment_intent.succeeded` →
  `completeFromWebhook` (re-reads the intent; 503 when the read answers pending so Stripe redelivers),
  `payment_failed` with a `us_bank_account` → `failFromWebhook` (FAILED + the `payment-failed` mail; a card
  decline is `noteFailure`, audit only), `canceled` → `cancelFromWebhook`, `processing` → 200. Re-delivery is
  a no-op through the state machine; there is no event-id table.
- **Refunds (2026-11)**: the org refunds in its Stripe dashboard; `charge.refunded` (and
  `charge.refund.updated`) reach the same endpoint. The event's object is the CHARGE (or the refund), so the
  row is found by `metadata.paymentId` (Stripe copies the intent's metadata onto its charge) or by the intent
  id (= `orderRef`). `PaymentCommands.refundFromWebhook` re-reads the charge's cumulative `amount_refunded`
  from Stripe (`PaymentProcessor.refundedCents`, never the event's number) and
  `PaymentRecorder.recordRefund` writes ONE row on the PAYER, `{paymentId}-refund`, category `Refund`
  ("Stripe (id #pi_…) Refund of $100.00 (partial, of the $984.87 payment) from CFPW"). **The amount is the
  cumulative refund, credits first, CAPPED at what the payment credited (owner's decision 2026-11-12):** Stripe's
  figure is of the gross charge, which also carried the payer-paid fee (never a ledger row) and the donation (a
  balance-neutral +D / -D pair), so taking the gross back left a fully refunded payer owing the fee and the
  donation. A $153.20 charge ($100 credit, $3.20 fee, $50 donation) fully refunded writes -$100.00, and the
  description says so ("Refund of $153.20 from CFPW ($100.00 of it had been credited to this account; the
  processing fee and the donation never were)"). A second partial refund or a redelivery rewrites that same row;
  the original credit rows are never touched (money rows are never destroyed). Only a RECORDED payment is refundable
  against the ledger;
  anything else is audited and answered `unchanged`. An unreadable Stripe answers 503 so Stripe redelivers.
  Sandbox computes and writes nothing. No mail: Stripe's own refund receipt covers it, and the org knows.
  PayPal refunds stay a hand entry (no refund read; the SPI default is "unknown").
- **Method-aware re-pricing (2026-11, web only)**: when the payer covers fees, the Payment Element's
  `change` event names the picked method and `trip-js/payElement.js` posts it to
  `POST /api/payments/{id}/method`; `PaymentCommands.selectMethod` recomputes the gross-up for a
  `us_bank_account` at `ProcessorType.STRIPE`'s bank pricing (80 bps, $5.00 cap:
  `MoneyMath.bankCreditFeeCents`), re-prices the PaymentIntent (`PaymentProcessor.reprice`, which also narrows
  the intent to that method while it is bank-priced) and only then the `Payment` row, and the page rewrites
  every `[data-pay-total]` and calls `elements.fetchUpdates()` unless the answer says `methodLocked` (below). A
  card (or Link, or anything else) prices it back to the card estimate. Whoever may act on the payment
  (`PaymentCommands.mayComplete`: its payer, a site admin, a checkout's registration admins), CREATED
  only, payer-pays only, processors with bank pricing only; a processor refusal keeps the quoted total (the
  page must never show a figure the processor will not charge). The apps' PaymentSheet has no method
  callback and keeps the card estimate (the org keeps the difference; `actualFeeCents` records the truth).
- **The bank price is locked to bank debits (owner's decision 2026-11-12).** The page's word about the picked
  method is not proof of what pays: a payer could take the bank total and pay by card, and the organization
  absorbed the card fee. `PaymentProcessor.reprice(orderRef, cents, onlyMethod)` now narrows the intent to
  `us_bank_account` with the amount (`allowed_payment_method_types`, Stripe API 2026-08+), so Stripe itself
  refuses a card at that total, and remembers the methods the intent offered in its metadata
  (`offeredMethods`) so the card estimate can put them back (the narrowing replaces the dashboard's list).
  `Payment.pricedForMethod` records what the stored total is priced for. The answer's `methodLocked` tells
  `payElement.js` to leave the mounted form alone (a `fetchUpdates` would drop the other methods' tabs, and
  with them the payer's way back to a card); picking another method re-prices and unlocks. **Fallback:** an
  intent Stripe will not narrow is re-priced unlocked, as before, and a capture whose method is not the one
  the total was priced for is RECORDED AS PAID with a failure-outcome audit line naming the shortfall
  (`noteMethodMismatch`: "Paid with card at the total priced for us_bank_account: ... the organization
  absorbs the difference"). NOT verified against live Stripe from this repo: `FakeProcessor` models the lock
  (a card capture on a bank-locked order stays pending), and `StripeProcessorTest` pins the request shape.
- **Wallets**: "Register this site for wallets" (`OrgCommands.registerPaymentDomain`, Stripe's
  PaymentMethodDomain API) once per host per org account; `WellKnownServlet` serves Stripe's
  `apple-developer-merchantid-domain-association` on every host. The iOS app's Apple Pay certificate is per
  org account and user-run (setup-instructions.txt, "Stripe").
- **Fees**: `ProcessorType.STRIPE(290, 30, 80, 500)`: the card rate is the quote, the bank rate applies once
  the payer picks a US bank account on the website (above); in the apps the card estimate stands and the org
  keeps the difference. `actualFeeCents` records the truth either way.
- **No new AWS cost**: same Secrets Manager secret, same ALB (no path rules touch `/api/*`), same tables.

## Local mode seeds

CFPW org (`FakeData.CFPW_ORG_ID`, same UUID as `org-migrate.sh`) holds every fake person; payment defaults
= FAKE processor + payer-pays + donations on, so the Spring Demo seed (`FakeData.FAKE_TRIP_ID`) is
payable out of the box. "Acme Inc" +
Kevin/user3 as its NON-site-admin org admin is the tenant-isolation demo.

## Go-live (user-run, in order)

1. `cdk deploy TripApp` (4 new tables: organizations, org_members, payment_processors, payments + payment
   secret grants + `TRIP_PAYMENT_SECRET`) — then PITR by hand on all four.
2. `aws secretsmanager create-secret --name trip/payment-processors --secret-string '{}'` (us-west-2).
3. Deploy the app build.
4. `scripts/org-migrate.sh --profile cdk-deploy` (report) → review → `--apply` at quiet time → clear caches
   from admin Settings.
5. `scripts/install-starter-templates.sh` (the `payment-confirmation` MAIL starter).
6. Grant org admins on `/admin/organizations.jsf`; enter processor configs + paste credentials on the org
   settings page; Test connection; set the trip/org payment config; Send Test Email.
7. Grant `paymentsAdmin` to org members on the org People page (`/admin/orgPeople.jsf?orgId=…`) if
   non-site-admins need sandbox (bounded by the org's allow-list — `docs/org-admin.md`).
8. Stripe, per organization (2026-11): the numbered steps under "Stripe" in `medjugorje/setup-instructions.txt`
   (keys, the two webhook endpoints with the four `payment_intent.*` events plus `charge.refunded`, wallet
   domain registration per host, the iOS Apple Pay certificate, Stripe's own receipt off); the same steps in
   admin-facing words are the help pages `/admin/help/stripe.jsf` and `/admin/help/paypal.jsf` (medjugorje,
   linked from the processor page, the org hub and the trip editor's Payment Settings dialog); re-run
   `install-starter-templates.sh` for
   the `payment-failed` MAIL starter; ship the app builds that carry PaymentSheet BEFORE switching an org whose
   travelers pay from the apps (an older app gets no `approvalUrl` from a Stripe org and cannot pay).

Recurring cost: ≈$0.45/mo (one secret + four KB-scale tables) — under the $1 approval line.
