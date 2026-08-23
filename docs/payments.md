# Payments (multi-tenant) — read before touching any payment, organization, or processor code

Landed 2026-08-17. The mir2026 PayPal prototype (env-var singleton, xhtml-driven callbacks) is GONE; this
replaces it end to end. Design decisions here are user-locked — do not relitigate without the owner.

## Organizations: the tenancy boundary

`Organization` is the platform's tenancy root: **every Person belongs to ≥1 org** (multi-org supported),
every Trip belongs to one (`Trip.orgId`; the legacy `provider` display string is SYNCED from the org's FULL
name on every save — `TripCommands.syncProviderFromOrg` — for the public renderers; `isCfpw()` and the
landing filter key on the org's short name, falling back to `provider` only for org-less legacy rows), and
Transactions carry `orgId` (stamped from the trip on new writes; `org-migrate.sh` backfills legacy rows).
Design every new data holder along this boundary.

- Membership: `org_members` table (PK orgId, SK personId) is the source of truth; `Person.orgIds` is the
  derived reverse edge (surgically synced by `OrgCommands`, the ONLY writer of org structure). Admins live
  on `Organization.adminIds` (small list; admins are members). Removal enforces admin-first and the
  ≥1-org rule.
- Org ids are canonical UUIDs on purpose (privilege scope suffixes must round-trip).
- Site admins create/rename orgs (`admin/organizations.jsf`) and get the topbar **org switcher**
  (`sessionScope.currentOrgId`, a session preference, not an identity swap). Org admins manage their own
  org from the **org hub** `admin/orgSettings.jsf?orgId=…` (a dashboard of cards; Profile, Trips, People
  and Payment Processors live on their own `admin/org*.jsf` pages behind the same self-gating pattern —
  roster-gated in the page, NOT defaultAuth). See `docs/org-admin.md` for the full org-admin area,
  org-scoped privileges, and the per-org allow-list.
- Org pickers are `p:autoComplete` (contains, case-insensitive) — never a plain dropdown; 100+ orgs is a
  design constraint.

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
**trip → org → site settings** (`payment.*` in `KnownSettings`). Fields: processorConfigId, feesPaidBy,
donation toggle+label, confirmation template id (MAIL kind; installed starter `payment-confirmation`),
mailFrom/replyTo/bcc (**no cc — the SES wrapper has none**), extraTokens. The dialog's **Send Test Email**
renders the effective template with sample values to the signed-in admin.

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
`/quote`, `/{id}/complete`, `/{id}/cancel`; return URLs allowlisted per `payment.returnUrl.allowedPrefixes`)
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
