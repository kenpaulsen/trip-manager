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

## Processor configs and secrets

`PaymentProcessorConfig` rows live in `payment_processors` (PK **orgId**, SK id — the partition IS the
tenancy check; a foreign config id simply misses). Types: PAYPAL, STRIPE (stageable, unbuilt), ZEFFY
(unbuilt), FAKE (local mode). Each carries public ids (`publicConfig.clientId` + a sandbox set), mode
(SANDBOX/LIVE), and fee-estimate overrides (`feeBps`/`feeFixedCents`, 0 = the `ProcessorType` default).

**Secrets never touch DynamoDB.** ONE Secrets Manager secret (`trip/payment-processors`, env
`TRIP_PAYMENT_SECRET`, both task definitions) holds a JSON object keyed by config id —
`security/ProcessorSecrets` (Pepper-style resolution; in-memory in local mode; the task role has Get+Put
because org admins paste credentials through the UI, write-only fields with set/unset badges).

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
See the sending-domain section of `org-admin.md` for the allow-list itself.

## The money rules (user-locked; the golden test is `PaymentRecorderTest`)

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

## The flow (`action/PaymentCommands` — Java-first, user-locked)

ALL flow logic is Java; `trip/payment.xhtml` and `api/PaymentsResource` (v2: `POST payments`,
`/quote`, `/{id}/complete`, `/{id}/cancel`; return URLs allowlisted per `payment.returnUrl.allowedPrefixes`
PLUS any organization site the live `SiteIndex` knows — `https://{slug}.{base}/…`, whole host, https only)
are thin callers of the same methods — that is what makes iOS/Android possible.

`Payment` rows (`payments` table, PK paymentId, **uncached**) are the durable state machine:
`CREATED → CAPTURED → RECORDED`, terminal CANCELLED/FAILED, transitions as conditional puts on the stored
status (double-submits serialize). Completion order: capture → verify (COMPLETED + real capture id/amount —
`PayPalProcessor` never reports a $0 success; ALREADY_CAPTURED recovers via getOrder) → gross==expected
check (mismatch parks in CAPTURED) → idempotent record → RECORDED → confirmation mail (failure audited,
never blocks). `admin/payments.jsf` lists non-terminal payments with a "Finish" that reruns the same path —
this is the no-webhooks-yet safety net (webhooks arrive with Stripe).

Audit: `AuditAction.PAYMENT` targeted at `payment:{id}` for start/complete/fail, `[SANDBOX] ` prefixed in
sandbox.

## Sandbox mode

The ORG-scoped `paymentsAdmin` privilege — `paymentsAdmin@the-trip's-org`, granted on the org People page;
site admins implicitly qualify, and the retired global variant grants nothing — shows a toggle on the
payment page (`PaymentCommands.isSandboxAllowed(trip)`; an org-less legacy trip offers sandbox to site
admins only):
sandbox runs the SAME pipeline against the config's sandbox credential set, but the recorder **dry-runs** —
no ledger rows, no mail, and the would-have-written rows render in the result panel. Sandbox payments are
excluded from balances and the reconciliation list.

## Processors (`pay/`)

`PaymentProcessor` SPI (createOrder → approvalUrl redirect; capture → verified result), built per config by
`PaymentProcessors.forConfig(config, sandbox)` (memoized per config id+version+sandbox — rotation without
restart). `FakeProcessor` + `trip/fakeCheckout.xhtml` drive the whole flow locally (webtest:
`PaymentPagePwIT`). Stripe: implement as a Checkout Session behind the same SPI, plus a webhook resource
(the non-`@TripApi` precedent) funneling into `completePayment`. Zeffy: a future record-only kind.

## Local mode seeds

CFPW org (`FakeData.CFPW_ORG_ID`, same UUID as `org-migrate.sh`) holds every fake person; payment defaults
= FAKE processor + payer-pays + donations on, so `faketrip` is payable out of the box. "Acme Inc" +
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

Recurring cost: ≈$0.45/mo (one secret + four KB-scale tables) — under the $1 approval line.
