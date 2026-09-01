# API tokens — bearer auth for the REST edge

Read this before touching API authentication: `TripAuthFilter`, `AuthResource`, `RememberMeService`, the
`auth_tokens` table, or anything under `security/` that mints or validates a credential. It is the design for
[medjugorje#14](https://github.com/kenpaulsen/medjugorje/issues/14) — replacing the cookie-session interim for
native mobile clients with bearer tokens — and it holds three invariants that every change must preserve:

1. **Fully-attributed audit.** A token-authenticated mutation records an actor with email AND id. A half-known
   actor looks like a populated record until someone tries to read it; this has already happened once, on the
   mail path.
2. **Revocation bites fast, and its worst case is bounded and known.** The bound is stated in
   [Revocation](#revocation), not discovered in the field.
3. **The hot path stays hot.** Validation runs on every API request; it is one foreground Valkey GET plus
   arithmetic, never a DynamoDB read.

## The token model — one table, three kinds

Every credential this application hands out for later presentation — the remember-me browser cookie, a mobile
refresh token, a mobile access token — is the same shape: an opaque `selector:validator` pair, where the row
(keyed by selector) stores only the SHA-256 of the validator. Lookup is by selector; the validator is compared
constant-time (`Digests.matches`). A cache dump or a table dump therefore contains no presentable credential,
the same rule `loginCodeKey` already follows for emailed codes.

They live in ONE DynamoDB table, **`auth_tokens`** (PK `selector` (S), on-demand, no GSI, TTL on `expires`):

| Attribute | Set for | Meaning |
|---|---|---|
| `selector` | all | Partition key; 9 random chars (`RandomData.genSecureToken(9)`) |
| `validatorHash` | all | Base64 SHA-256 of the 32-char validator |
| `prevValidatorHash`, `rotatedAt` | REMEMBER, REFRESH | Rotation grace (30s) — see [theft](#issuance-refresh-and-theft) |
| `userId`, `email` | all | The owner — both, so `AuditActor` is never half-known |
| `kind` | all new rows | `REMEMBER` \| `REFRESH` \| `ACCESS`; **absent = `REMEMBER`** (legacy rows) |
| `role`, `scope` | REFRESH, ACCESS | Stamped at issuance/refresh — see [scopes](#scopes--admin-vs-member) |
| `label` | REFRESH | Client-supplied device name, for the devices UI only |
| `parentSelector` | ACCESS | The refresh token that minted it (revocation cascade) |
| `created`, `lastUsed`, `expires` | all | Epoch seconds; `expires` doubles as the DynamoDB TTL attribute |

The model is `AuthToken` (né `RememberToken`), the DAO is `AuthTokenDAO` (né `RememberMeDAO`): field-by-field
`AttributeValue` mapping (no Jackson blob in Dynamo), `stringOrNull`/`numberOrNull` for attributes that
post-date early rows, registered in `InMemoryPersistence` so local mode and the whole unit suite work. Bulk
operations (`deleteAllForUser`, `listForUser`) are full table scans **on purpose**: the events are rare, the
table is tiny, and a GSI would exist only for them while breaking the in-memory fake. `deleteAllForUser`
returns the deleted rows (not a count) — the caller must purge the validation cache per ACCESS selector, and a
count cannot do that. Do not "optimize" the return type away.

ACCESS rows never rotate, so `prevValidatorHash`/`rotatedAt` stay null for them — the grace machinery exists
for credentials that are re-presented and replaced, and an access token is neither.

### Why the table is named `auth_tokens` and how `remember_me` dies

DynamoDB cannot rename a table, and `remember_me` stopped describing the contents the day refresh tokens
landed in it. So: a new CDK-created `auth_tokens` table, and a **lazy migration** — for one release,
`AuthTokenDAO.getToken` falls back on a miss to the old `remember_me` table, copies the row forward, and
deletes the original. No script, no migration window, no mass re-login, no burned-token theft alarms. The
follow-up release drops the fallback and deletes `remember_me` (disable its deletion protection in CDK first).
The alternative — no migration, every remember-me cookie silently expiring once (unknown selector ⇒ cookie
expired client-side, no alarm) — is graceful but costs every browser user a re-login; rejected for that
reason, kept here in case the fallback ever has to be abandoned.

Like `remember_me` before it, `auth_tokens` carries no PITR: rows are re-creatable credentials, not data. The
monthly AWS Backup plan picks it up via wildcard selection automatically. Schema entry:
`medjugorje/setup-instructions.txt`.

## Issuance, refresh, and theft

Three endpoints, all on `AuthResource` (already registered in `TripApiApplication.getClasses()`, already
outside the `@TripApi` binding because login must work unauthenticated, already owning the enumeration-safe
error shapes — a new resource would duplicate all three):

- `POST /api/auth/token` — email+password or email+code, exactly `login`'s checks including the single
  indistinguishable failure message. Returns `{accessToken, accessExpiresIn, refreshToken, scope}`.
- `POST /api/auth/token/refresh` — presents the refresh token; rotates it; returns a fresh pair.
- `POST /api/auth/token/revoke` — kills the presented refresh token and its ACCESS children.

All three are **sessionless**: they must never call `getSession(true)`. A token client that accidentally also
holds a `JSESSIONID` is exactly the two-parallel-identity-models mess the `AuthResource` javadoc warns
against, and there is an explicit test asserting no session is created.

The mechanics are shared with remember-me through `security/SelectorTokens`, a static utility extracted from
`RememberMeService`: `mint()` (selector/validator pair), `parse(presented)` (rejects malformed input before
any I/O — a garbage flood never reaches Dynamo), and `judge(row, presentedHash, now)` answering
`CURRENT | GRACE | EXPIRED | THEFT`. Refresh rotation is remember-me's rotation: same selector, new validator,
previous validator honored for 30 seconds (a backgrounded app fires racing requests with the same token, the
same way a session-expired browser does), and a stale validator outside the grace window is the theft
signature — row deleted, ALARM audit, exactly as `RememberMeService.validateAndRotate` has always done.
Reusing same-selector rotation instead of the OAuth-style new-selector-per-refresh buys theft detection for
free; do not change the rotation style without replacing that property.

`role` and `scope` are **stamped on the rows at issuance and refresh**, when credentials are read
(`Cached.NO`) anyway. Access validation trusts the stamp for the access lifetime; the refresh call is the
freshness checkpoint at which a demoted admin loses admin power. This is the same staleness bound a live
session has today — `Sessions.establish` stamps `ACTIVE_USER_ROLE` at login and nothing re-reads it — so the
token path is not weaker than the path it replaces, and password change / credential deletion revoke
everything outright (see [Revocation](#revocation)). Resolving role per request instead would put an uncached
credentials read on every API call, which invariant 3 forbids.

Lifetimes come from `KnownSettings` ([Settings](#settings)); the access lifetime (~30 min) is deliberately
shorter than the old 240-minute session timeout because refreshing is silent — the client treats
`401 NOT_AUTHENTICATED` on an access token as "refresh and retry", never as an error to surface.

## The hot path: cached validation

> **This section deliberately reverses a documented policy.** `DAO.java`'s constructor and the old
> `RememberMeDAO` javadoc say auth rows are never cached, because "a stale read is a security bug" and "a
> revoked link must die now". That rule was written for credentials read once per browser restore or per
> login. An access token is read **per request** — the policy's cost model inverted — so validation caches,
> and the two properties the policy protected are preserved differently: revocation stays immediate via
> explicit `removeKey` on the shared Valkey, and staleness is bounded by the soft TTL
> ([Revocation](#revocation) has the numbers). Everything read less than per-request keeps the old rule:
> credentials, refresh-token reads, passkeys, invites stay uncached.

Validation is a `PointCache<AuthToken>` — the standard point-read template, reused as-is (it has no DAO
coupling; `LoginCodeCommands` already obtains the client via `DAO.getInstance().getCacheClient()`):

- `keyPrefix` = `CacheKeys.authTokenKey(...)` under **`auth:v1:tok:`**. The `auth:v1:` namespace is the
  load-bearing choice, twice over: it is outside `t1:`, so `DAO.clearAllCaches()` and every `CacheScope`
  clear leave tokens alone; and `NearCacheClient` only heap-caches `t1:` keys, so a token entry lives ONLY in
  the shared Valkey — which is precisely why one `removeKey` revokes on every node at once, with no broadcast,
  no near-cache drop, no second task caveat.
- `softTtl` = `CacheKeys.AUTH_TOKEN_SOFT_TTL` (5 minutes), a compile-time constant like every other soft TTL
  — settings must never be read on a cache read path (ConfigDAO sits on the caches).
- `gcTtl` sized to the maximum access lifetime plus slack — hygiene only, never the expiry mechanism.
- `softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))`, like every DAO.
- Write-through on issuance, so the first validation of a new token is already a hit.

Per request: one foreground-lane Valkey GET, envelope decode, then checks enforced inline **on the value in
hand, every time, cached or not**: `kind == ACCESS`, `expires` against now, SHA-256 of the presented
validator compared constant-time against the stored hash. The cache can serve a deleted row for a bounded
window; it can never serve an *expired* or *wrong-validator* success, because those judgments never trust the
cache's age. A stale hit schedules the standard background reload (`Revalidator`: dedup → `RefreshPermits` →
lock → Dynamo re-read on the background lane); a reload that finds the row deleted removes the entry — the
healing backstop. A cache miss falls through to one Dynamo point read and rewrites the entry.

## Dual acceptance and identity plumbing

`Authorization: Bearer <selector:validator>` is resolved **once, at the servlet layer, inside
`SessionRecoveryFilter`** — because that filter is the single binding point of the `RequestContext`
ScopedValue (its javadoc makes "one binding point" a rule), and an actor bound before token resolution would
audit every token request as UNKNOWN. The filter's own concern stays session recovery; the token work lives in
a static `BearerTokens.resolve(request)` (guard: header present and path under `/api/`), which validates via
the cache above and stashes an immutable `TokenPrincipal` record — `personId, email, role, scope, selector` —
as a request attribute. The filter then binds `new RequestContext(actor, role)` from the principal instead of
the (absent) session. Nothing mutable enters the ScopedValue; the record rule holds.

From there, one preference order, applied everywhere identity is asked:

- `TripAuthFilter` accepts the principal attribute OR the session attribute; same 401 JSON otherwise. JSF and
  browser callers are untouched — one filter, two credentials.
- `BaseResource.personId()`, `actor()`, `caller()` prefer the principal; `caller()` builds
  `Caller.of(TokenPrincipal)` ([scopes](#scopes--admin-vs-member)).
- `csrfMissing` becomes a `protected` **instance** method (call sites compile unchanged) and answers false
  when a bearer principal is present. The sentinel exists because a cookie is sent ambiently; a bearer token
  is only sent on purpose, so CSRF does not apply to it. Cookie-session mutations keep the sentinel exactly
  as before.

Known quirk, do not "fix" it backwards: an API request restored by `RememberMeFilter` runs with an anonymous
`RequestContext` today (its actor attaches later, per-resource — admitted in that filter's javadoc). The
bearer path binds the actor at the top like a real session does; the remember-me API path is the outlier.

## Scopes — admin vs member

A token carries a `scope`: `member` or `admin`. `admin` is only issuable (and only re-stampable at refresh)
when the credentials actually hold the admin role. The cap is enforced in **exactly one place**:
`Caller.of(TokenPrincipal)`, built on the existing public explicit constructor, sets
`siteAdmin = role is admin AND scope == admin`. Since `ApiPrivileges` and `Caller.has()` short-circuit
through `siteAdmin`, a member-scoped token held by an administrator behaves as that person without the admin
role — and the ops resources (Mail, Deploy, Privileges, Config, Cache), which all gate on site-admin or
admin-held privileges, need zero per-resource changes. Do not add per-resource scope checks; a second choke
point is how the two drift.

The deliberate residue: explicit privilege rows still apply under member scope. A person holding `TRIP_MGR`
keeps it through a member-scoped token, exactly as through their own session — member scope means "no more
than this user's non-admin powers", not "read-only". Admin-scoped refresh tokens are the crown jewels, so
their lifetime is clamped separately and short ([Settings](#settings)); remember-me's blanket admin exclusion
stays as-is for cookies.

(`caching.md` notes a wished-for "non-interactive machine token" for migration scripts. An admin-scoped
token is that, once issuance-from-CLI is worked out — a follow-up, not part of this design.)

## Revocation

Every revocation path does the same three things, in order: delete the Dynamo row(s) (authoritative), then
`removeKey` each ACCESS selector's cache entry (immediate everywhere — see the namespace argument above),
with the soft-TTL background reload as the backstop if a removeKey is lost (the cache client's contract is
fail-open, so a removal can fail silently).

| Path | Trigger | Rows killed |
|---|---|---|
| `POST /api/auth/token/revoke` | client sign-out | the refresh token + its ACCESS children (`parentSelector`) |
| `DELETE /api/auth/sessions/{selector}` | devices UI | same cascade; owner-checked |
| `TokenService.revokeAllFor(userId)` | password change (`PassCommands.setPass` funnel), credential deletion (`PassCommands.deleteCreds`) | every row of every kind for the user |
| theft judgment | stale validator outside grace | that refresh token, plus ALARM audit |

The `PassCommands` hooks are the **existing** `revokeAllFor` call sites — because cookies and tokens share
one table and one scan, password change and account lockout revoke mobile devices with no new call sites.
That is the payoff of the single-table decision; a second table would need a second hook in both funnels.

How long can a dead token live?

| Event | Access token usable for |
|---|---|
| Dynamo delete + removeKey succeed (normal) | 0 — next validation misses, reloads, 401s |
| removeKey lost (cache brown-out) | ≤ `AUTH_TOKEN_SOFT_TTL` + 10% jitter (≈ 5½ min), then the reload finds no row and removes the entry |
| Role demoted, tokens NOT revoked | until the access token expires (~30 min); the refresh re-stamps the new role |

If a demotion must bite faster than 30 minutes, revoke the person's tokens; that is what the devices UI and
`revokeAllFor` are for.

## Managing signed-in devices

The profile page (`medjugorje/webapp/account/person.xhtml`) gets a "Signed-in devices" fieldset beside the
passkey "Sign-in options" one, following that pattern exactly: rendered only for the feature setting AND
self-ownership (`viewScope.person.id == sessionScope.userId` — an admin viewing someone else's profile does
not see, and cannot manage, their devices), list JS-rendered from REST so nothing enters the serialized JSF
view state, rows built `createElement`/`textContent` (never `innerHTML` for data), revoke behind
`window.confirm`. Client helper `resources/trip-js/tokens.js`, modeled on `passkey.js` (same `X-Trip-Api` +
`credentials: 'same-origin'` fetch wrapper).

Backing endpoints on `AuthResource`, `@TripApi`-bound (session or token — a phone can manage itself):

- `GET /api/auth/sessions` — the caller's REMEMBER and REFRESH rows (`selector, kind, label, created,
  lastUsed`) via `AuthTokenDAO.listForUser` (a scan; same justification as `deleteAllForUser`). ACCESS rows
  are an implementation detail and are not listed. Browser rows carry no label; the UI names them by kind.
- `DELETE /api/auth/sessions/{selector}` — CSRF-checked (browser callers), owner-checked **inside the DAO**
  (the `PasskeyDAO.deletePasskey` pattern), 404 for missing and not-owned alike ("whether someone ELSE has
  this token is not an answerable question"), cascades through `parentSelector`, audited.

## Settings

Declared in `KnownSettings`, "Login & security" section; the admin Settings page renders them automatically.

| Setting | Type / default | Meaning |
|---|---|---|
| `api.token.enabled` | BOOLEAN / `false` | Kill switch. Off = no issuance, no refresh, AND no acceptance of existing bearer tokens (checked outside the cache read path). Default flips to `true` when a client ships. |
| `api.token.access.minutes` | INT / `30`, clamped 5–240 | Access-token lifetime; also the role-staleness bound. |
| `api.token.refresh.days` | INT / `60`, clamped 1–365 | Member refresh-token lifetime, absolute from issuance (rotation does not extend it — remember-me's rule). |
| `api.token.refresh.admin.days` | INT / `7`, clamped 1–30 | Admin-scoped refresh lifetime. Deliberately short: an admin token is the most valuable thing this table holds. |

`AUTH_TOKEN_SOFT_TTL` is NOT a setting — soft TTLs are `CacheKeys` constants because settings cannot be read
on a cache read path.

## Performance & cost

Bearer hot path: one Valkey GET on the foreground lane (an `auth:v1:` key is never near-cached — that is the
price of instant revocation, paid knowingly), one SHA-256 of 32 chars, one constant-time compare, two long
compares. The session path it replaces is a Redisson Valkey read plus Kryo deserialization of the whole
session blob — per request, plus write-backs. Parity or better, and the bearer path writes nothing per
request. Writes: issuance = 1 uncached creds read + 2 Dynamo puts + 1 cache put; refresh = the same, once
per access lifetime per device (~2/hour/device). Noise at this application's scale.

Cost: one new on-demand DynamoDB table whose rows TTL-delete themselves (pennies), a handful of small Valkey
keys in an existing namespace, and everything else — settings, audit, admin UI, profile UI — on surfaces that
already exist. No new infrastructure.

## Local-mode differences & testing

- `InMemoryCacheClient` ignores hard TTLs and `CacheSupport` forces `softRevalidate` off for it, so local
  mode exercises neither cache expiry nor background refresh. Correctness survives because expiry is judged
  inline from the row's `expires` — never from cache age — and unit tests cover expiry with a fake clock.
- Staleness and backstop tests build a `PointCache` directly with an injected `clock` and `ttlJitter`
  (builder fields exist for this) over a `ForwardingCacheClient`-wrapped in-memory client — the wrapper is
  not an `instanceof InMemoryCacheClient`, so revalidation turns ON deterministically.
- `InMemoryPersistence` registers `auth_tokens` (and, until the fallback dies, `remember_me`), so the DAO,
  the lazy migration, and the scans all run in the ordinary local-mode suite.
- The audit-attribution regression test is non-negotiable: a token-authenticated mutation writes an actor
  with email AND id.

## Implementation phases

Each phase ships independently; browser paths are untouched until phase 3, and even then only additively.

**Phase 1 — generalize the store (no behavior change).** `auth_tokens` CDK construct
(`medjugorje/infra/`) + `setup-instructions.txt` entry; rename `RememberToken`→`AuthToken` (+ `kind`,
`scope`, `role`, `label`, `parentSelector`, `@NoArgsConstructor` for the cache's Jackson round-trip; stays in
`ModelSerializationTest`'s guard) and `RememberMeDAO`→`AuthTokenDAO` (new table, lazy `remember_me`
fallback, `deleteAllForUser` returns rows, new `listForUser`); extract `SelectorTokens`; touch up `DAO`
delegates, `RememberMeService`, `RememberMeFilter`. Tests: `AuthTokenDAOTest` (round-trip incl. legacy
absent-field rows; fallback copies forward and deletes), `SelectorTokensTest` (fake-clock judgments), and
`RememberMeServiceTest` passing **unmodified** — the behavior-preservation proof.

**Phase 2 — issuance, refresh, revoke (tokens exist; nothing accepts them).** `security/TokenService`
(plain singleton, same non-CDI rationale as `RememberMeService`); the three `AuthResource` endpoints; the
four `KnownSettings`; `AuditAction.TOKEN_ISSUE/TOKEN_REFRESH/TOKEN_REVOKE`. Tests via `ResourceTestSupport`:
indistinguishable failures, feature off, rotation + grace + theft alarm, **no session created**,
admin scope refused for member credentials.

**Phase 3 — dual acceptance + cached validation (the flip).** `security/BearerTokens` + `TokenPrincipal`;
`CacheKeys.AUTH_TOKEN_SOFT_TTL` + `authTokenKey`; `SessionRecoveryFilter` resolution + principal-aware
`RequestContext`; `TripAuthFilter` dual accept; `BaseResource` preference order + `csrfMissing` to instance;
`Caller.of(TokenPrincipal)`; `PointCache` wiring in `TokenService` (write-through on issue, `removeKey` on
every revocation path including the `revokeAllFor` sweep and the cascade). Tests: full audit attribution,
scope cap (member-scoped admin refused by an ops resource), revocation immediacy, staleness/backstop via the
local-mode recipe above; webtest `TokenAuthPwIT` (issue→call→refresh→revoke via Playwright's
APIRequestContext, feature flag flipped per the `PasskeyPwIT` idiom).

**Phase 4 — devices UI.** `GET/DELETE /api/auth/sessions` on `AuthResource`; the `person.xhtml` fieldset +
`trip-js/tokens.js`. Tests: `PasskeyResourceTest`-shaped unit tests; `DevicesPwIT` webtest.

**Follow-up release.** Drop the `remember_me` fallback; disable the old table's deletion protection and
delete it in CDK; strike the fallback note from `setup-instructions.txt`.
