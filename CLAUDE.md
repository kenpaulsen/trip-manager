# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

This is the **public** Trip Manager application repo. The deployable pages, the live `WEB-INF/web.xml`, the
container image, and all infrastructure live in the private sibling repo (`../medjugorje/`) — keep anything
account- or deployment-specific out of this repo.

## Build & test

```sh
mvn clean install                                  # full build -> trip/target/ROOT (expanded WAR) + ROOT.war
mvn test -pl trip                                  # all unit tests for the main module
mvn test -pl trip -Dtest=PersonTest                # one test class
mvn test -pl trip -Dtest=TripCommandsTest#method   # one method
```

- Coverage gate: JaCoCo runs with `test` — bundle ≥ 90% line AND ≥ 90% per class. It only halts the build in
  CI (`-Djacoco.check.halt=true`); locally it just reports (`trip/target/site/jacoco/index.html`). Excluding a
  class from the gate requires the owner's approval first.
- Style gate: checkstyle (adapted Oracle/Sun rules, `checkstyle.xml` at the repo root — 120-column lines, no
  tabs, no mandatory Javadoc; the file's header comment lists every adaptation) runs at `validate` on the
  `trip` module, main AND test sources, and any violation fails the build immediately, locally and in CI
  alike. Escape hatch for a genuine exception: `@SuppressWarnings("checkstyle:RuleName")`, used sparingly and
  with a comment saying why. Emergency skip: `-Dcheckstyle.skip=true`.
- Browser/integration tests are NOT here — they are in `../medjugorje/webtest/` (Playwright).

## Modules & stack

| Module | Purpose |
|--------|---------|
| `jsft/` | JSF Templating helper library (`com.sun.jsftemplating`, `com.sun.jsft.*`) |
| `trip/` | Main WAR (finalName `ROOT`): models, DAOs, cache, CDI beans, REST API |

Java 25 · Jakarta EE 10 (Servlet 6.1, Faces 4.1 / Mojarra, CDI 4.1 / Weld) · PrimeFaces 15 + extensions
(`jakarta` classifier) · Jersey 3.1 (REST) · MapStruct · Lombok · Jackson · AWS SDK v2 async (DynamoDB, SES,
Secrets Manager, CloudWatch Logs, S3, CloudFront, CodePipeline) · Lettuce (**must stay 6.x** — the build pins
Netty 4.1; Lettuce 7 needs Netty 4.2) · BCrypt (`at.favre.lib`) · PayPal server SDK · Apache POI · TestNG ·
NightMonkeys `imageio-heif` (chat-photo HEIC decode over the SYSTEM libheif via FFM — needs
`--enable-native-access` plus an unversioned `libheif.so` on `java.library.path`; without it HEIC uploads get
a clean "convert to JPEG" rejection and everything else works) · metadata-extractor (EXIF orientation).

## Architecture

### Persistence (`org.paulsens.trip.dynamo`)

- `Persistence` interface — abstracts the store. `DynamoPersistence` is the real one (async client);
  `InMemoryPersistence` is a real in-memory table store used in local mode and tests (`FakeData` seeds it).
- `LocalMode` — the single authority on local vs production. Explicit-only, defaults to production; see the
  workspace root CLAUDE.md. `TripBootstrapListener` (in `web/`) resolves it at startup and **must stay first**
  in web.xml's listener order (`ChatLifecycleListener` touches the DAO in its own `contextInitialized`).
- `DAO` — singleton composing the domain DAOs: Person, Family, Trip, TripEvent, Registration, Transaction,
  Credentials, Todo, PersonDataValue, Privileges, Binding, Config, Media, Template, Content, Audit, Chat.
  Payments add: Organization/OrgMember (org_members is the membership source of truth, Person.orgIds the derived edge), PaymentProcessorConfig (org-partitioned; secrets live in Secrets Manager via `security/ProcessorSecrets`, never in rows), and Payment (UNCACHED state machine CREATED/CAPTURED/RECORDED with conditional-put transitions) — read `docs/payments.md` before touching any of them.
  The `family` row is the source of truth for household membership (optimistic-version conditional puts);
  managers' `Person.managedUsers` lists are DERIVED from it — see `docs/family-accounts.md` before touching
  anything family-related.
- **Caching** (`org.paulsens.trip.cache`) — full architecture doc: `docs/caching.md` (read it before
  touching any cache or DAO read path). All caching goes through the `CacheClient` abstraction —
  `ValkeyCacheClient` (production, shared across instances; TWO connections: foreground queue 5000,
  background queue 500 routed by `CacheLane` — background work sheds early and can never starve the
  request path), `InMemoryCacheClient` (local), `NoopCacheClient` (off), with `NearCacheClient` (in-JVM
  heap for `Cached.YES` reads) decorating the production client. Each DAO uses a typed cache on top
  (`PointCache`, `PartitionCache`, `PartitionScanCache`, `AdjacencyCache`, plus `SearchIndex`/`TripIndex`).
  Freshness: every cached value carries its loaded-at stamp (`PointCache` envelope `"<epoch>|<json>"`,
  in-hash `__loaded_at__`, index markers) — staleness is decided INLINE, and only a stale hit schedules a
  background refresh through the shared `Revalidator` (dedup → `RefreshPermits` → lock → reload; gates
  before spawn, always — the 2026-08-18 incident was a per-hit probe ahead of the gates). Out-of-band
  writes are made visible by `DAO.invalidate(CacheScope)` (clears + broadcasts on `sys:v1:cache_inval`;
  REST: `POST /api/cache/invalidate`; scripts: `scripts/lib/cache-invalidate.sh`). `AuditDAO` and
  `CredentialsDAO` are deliberately uncached. `DAO.clearAllCaches()` clears the data namespace only —
  never a Valkey FLUSH, never sessions. NB: `NoopCacheClient.tryAcquireLock` grants every lock — never
  use a cache lock for exclusion without probing the cache mode first.

Persistence gotchas (each has caused a real bug):

- DAO reads return **copies**: mutating an object you read earlier and saving its *parent* writes nothing.
- `Trip` stores event **ids** (`tripEventIds`); `TripEvent`s resolve lazily on the first `getTripEvents()`
  call per instance and are memoized (same mutable list every call — mutate-then-`saveTrip` still works).
  A trip whose events are never touched never fans out, and `saveTrip` skips event rows for unresolved
  instances. The id list is authoritative: a failed event read never shrinks it on save.
- A read immediately after a write can be stale in production (async invalidation) — pass along the object
  you just saved instead of re-reading.
- `queryAll` ignores `limit` (it paginates the whole partition); use `query()` for unbounded partitions.
- Lombok `@Builder` zeroes primitive defaults — hand-write builders (see `TripBuilder`) and assert
  `builder().build().equals(new Whatever())` in the model test.
- A DAO on `PartitionScanCache` must delete rows via `removeOne`, never `invalidate()`: in local mode the
  cache client IS the datastore (soft revalidate off), so a full invalidation silently deletes every OTHER
  row too — one removed chat photo emptied the whole trip album before this rule existed.

### Page state and row identity — `docs/page-state-and-identity.md` (required reading)

Two rules that outrank convenience everywhere, and that a new page must follow from its first line:

1. **Domain objects live in `requestScope`, resolved from the DAO caches every request.** A view or a
   session may hold ids and scalars only — `STATE_SAVING_METHOD=server` serializes viewScope INTO the
   session, so an object in a view is an object in the session, and a later change to that class's shape
   500s every returning visitor (the 2026-08-14 outage). The DAO caches already ARE the app-scoped layer —
   `people.getPerson(id)` is the hashmap lookup — so per-request resolve is nearly free and a higher-level
   cache earns nothing. Beans obey this too: a bean reading `getViewMap("theTrip")` starves the moment its
   page is converted.
2. **Row commands act on an identity baked in at RENDER**, never on a row position: `f:param` for commands
   that act on a record, a frozen key list for editors that bind INTO the row, a scalar row model for
   tables that sort. Tables resolve per request now, so the decode-time list is not the rendered list.
   NB an `f:param` does NOT ride a plain `ajax="false"` button submit — such buttons must be ajax.

Guarded by `../medjugorje/webtest/.../SessionScopePolicyIT` (source-scan ratchet, three families of
violation) and by a webtest per row command. The doc carries the patterns, the sanctioned exceptions, and
the new-page checklist.

### Domain model (`org.paulsens.trip.model`)

Lombok-annotated, Jackson-serialized to/from DynamoDB. Core types: `Person` (nested `Person.Id`), `Family`, `Trip`,
`TripEvent`, `Registration`, `RegistrationOption`, `Transaction`, `Creds`, `TodoItem`/`TodoStatus`,
`PersonDataValue`, `Privilege`, `BindingType`, `Config`/`SettingDef`/`SettingSection`, `MediaItem`,
`AuditEvent`/`AuditQuery`/`AuditPage`, plus `model/chat/*` (15 chat types) and `model/deploy/*`.
**Every type that can land in viewScope must be `Serializable`** — a non-serializable one breaks the session
save and 500s every later request (looks like a site-wide outage). `ModelSerializationTest` is the guard.

`Person.privacy` (`PrivacySettings`) holds the owner's field-visibility choices; `PersonDto.redactedFor`
(REST) and `PrivacyView` (pages) are the two enforcement points — read `docs/privacy-settings.md` before
touching Person field visibility, redaction, or the profile/contacts pages.

### CDI action beans (`org.paulsens.trip.action`)

`@Named @ApplicationScoped` beans exposed to JSF EL. Current names: `trip` (TripCommands), `people`
(PersonCommands), `reg` (RegistrationCommands), `txCmds` (TransactionsCommands), `todo`, `bind`, `priv`,
`pdv`, `pass`, `mail`, `chat`, `audit`, `auditView`, `config` (ConfigCommands — admin Settings page),
`media`, `profilePhotos`, `chatPhotos` (ChatPhotos — chat photo storage/staging/album), `photoChat`
(PhotoChatCommands — per-photo comment threads/reactions; see `docs/photo-comments.md` before touching),
`content` (ContentCommands — template-driven page sections) and `contentTemplate` (TemplateCommands — the
template manager; see `docs/content-templates.md` before touching either — MAIL-kind templates are
runtime-editable email copy rendered by `MailCommands.sendManagedTemplate`, tokens filled by Java only),
`family` (FamilyCommands — family accounts; the create-and-link security boundary, see
`docs/family-accounts.md`), `support` (SupportChatCommands — the support:main channel; requests post
without membership), `org` (OrgCommands — organizations, THE tenancy boundary: membership, org-scoped privileges + the per-org
allow-list, processor configs, the payment-config ladder; see `docs/org-admin.md` and `docs/payments.md`
before touching), `payment` (PaymentCommands — the whole payment flow in Java: quote/start/complete/cancel + reconciliation + paymentsAdmin sandbox; see `docs/payments.md`), `deploy`, `json`, `tripUtil`.

- `ChatPhotos.getChatPhotos()` is ONE static instance on purpose — never give it ChatCommands'
  FacesContext/application-map lookup: the upload servlet has no FacesContext and the JSF send does, so a
  context-sensitive lookup splits the staging registry across two instances and every photo "is no longer
  available" at send.

- Bean `get*` methods (e.g. `getPerson`, `getTrip`) **never return null** — they answer a blank object with a
  fresh id, so null checks don't fire and a careless PUT saves a junk row. REST code must use the
  `BaseResource.find*` helpers instead.
- `AuditActor.current()` reads a ThreadLocal: inside any `CompletableFuture` callback it records NO actor.
  Capture it on the request thread and pass it across async boundaries.

### REST API (`org.paulsens.trip.api`, served at `/api/*`)

Jersey servlet (declared in the live web.xml, sibling repo) running `TripApiApplication` — resources are
registered **explicitly** in `getClasses()`, no package scanning; a new resource must be added there. 15
resources (auth, people, trips, registrations, transactions, todos, privileges, chat, chat-admin,
photo-chat, audit, config, mail, payments, deploy) + `TripAuthFilter`, `JsonExceptionMapper`,
`ObjectMapperProvider`. DTOs in `api/dto`, MapStruct mappers in `api/mapper`. Versioning is via the
`Accept` media type, not a URL segment.

- `PhotoChatResource` is deliberately NOT `@TripApi`: the auth filter is name-bound, so its GETs serve
  anonymous readers (comments follow the photo — `docs/photo-comments.md`); mutations enforce the session
  themselves and answer 401 JSON.

- Redaction is authorization: a DTO field the caller may not see is redacted by the mapper, not blocked by a
  route. `AccessLevel` is unranked — do not compare its constants with ordinal logic.
- `Beans.get` must select with the `Any` qualifier: `@FacesConfig` (on `PassCommands`) suppresses `@Default`,
  so a bare `CDI.current().select(type)` 500s at runtime and no unit test catches it.

### Other packages

- `chat/` — per-trip chat runtime: digest scheduler (Valkey-coordinated so N tasks send once), notifier
  chain, rate limiter, long-poll nudge registry. Design doc: `chat-design.md` at the workspace root. Several
  chat decisions deliberately reverse the obvious approach — read the design doc before changing behavior.
  Non-members participate via whole-family access or invite links (QR code + `chat_invites` table; guests
  are explicit guest-marked membership rows) — read `docs/chat-invites.md` before touching chat
  authorization (`canParticipate`/`readDenial`/`rejoin`) or `ChatMembership` copy methods.
  Channels are no longer only per-trip: each chat photo gets a `photo:{s3Key}` channel for its comment
  thread and image reactions, which roll up (SUM) into the carrying message's chips — read
  `docs/photo-comments.md` before touching photo threads, the summary fold, or `purgeChannel`.
- `media/` — the chat-photo pipeline (P4 media, landed 2026-08-07): `PhotoProcessor` (two renditions per
  upload — untouched original + ≤800px display copy; HEIC transcodes to full-res JPEG; animated GIF passes
  through), `ImageFormat` (magic-byte sniffing), `ChatPhotoStaging` (upload→send authorization). Uploads go
  through `web/ChatUploadServlet` (`/chat-photos/*`, own multipart-config in the live web.xml — the chat
  page's JSF form must NEVER go multipart, Tomcat's `maxPartCount` counts ordinary fields). Album rows land
  in the media table under slot `tripChat-{tripId}`; message removal deletes photos everywhere (album
  semantics: retention expiry does NOT).
- `audit/` — `Audit` writes every event to CloudWatch Logs (`TRIP_AUDIT_LOG_GROUP`) AND the `audit` DynamoDB
  table when deployed, stdout otherwise. Append-only in IAM *and* in code — no update/delete paths.
- `config/KnownSettings` — every runtime setting is declared ONCE here (`SettingDef` constants); the admin
  Settings page and the reading code both consume it. Never introduce a literal settings key + default.
- `security/` — `PasswordHasher` (BCrypt over a peppered pre-hash) + `Pepper` (versioned key from Secrets
  Manager; unset ⇒ logs a warning, hashes unpeppered). Local mode uses `Pepper.none()`.

## Scripts & data migrations

`scripts/`: `backfill-people-email.sh` (email-index GSI backfill), `migrate-group-tx-membership.sh`,
`rotate-password-pepper.sh` (pepper rotation and plaintext-password migration). One-time migration docs are in
`docs/migrations/`. The family-accounts migration scripts (report/migrate/consistency) live in the private
repo's `scripts/` — see `docs/family-accounts.md`.

## Testing notes

- **TestNG only** (no JUnit). The surefire `surefire-testng` provider is explicitly pinned — auto-detection
  once silently switched to the JUnit platform and reported "Tests run: 0 … SUCCESS". For the same reason,
  never add `jersey-test-framework` (it drags in junit-jupiter).
- The entire suite runs in local mode (surefire sets `trip.local.mode=true`): fake persistence, in-memory
  cache, `Pepper.none()`. This means **no unit test can catch a production-only mode/wiring bug** — verify
  those against the container image.
- Valkey integration tests auto-skip unless `TRIP_VALKEY_URI` is set (see the Testing section of
  `../medjugorje/setup-instructions.txt`). Pointing tests at a *configured* cache in local mode additionally
  requires the sysprop-only `trip.cache.local.useConfigured` guard.
- New tests go in `trip/src/test/java/`; useful harnesses: `dynamo/DynamoLocal` (real DynamoDB engine),
  `cache/LocalRedisCluster`, `api/ResourceTestSupport`.
