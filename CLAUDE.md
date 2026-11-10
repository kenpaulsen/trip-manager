# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

This is the **public** Trip Manager application repo. The deployable pages, the live `WEB-INF/web.xml`, the
container image, and all infrastructure live in the private sibling repo (`../medjugorje/`) -- keep anything
account- or deployment-specific out of this repo.

## How this guidance is organized

This file holds only what applies to nearly every task here. Everything else loads when it is needed:

- **Path-scoped rules** in `.claude/rules/*.md` load by themselves the first time a file matching their `paths:`
  globs is opened with the Read tool. A grep or a shell `cat` does NOT trigger them, so before changing an area
  you have not Read a file in, open its rule file by hand ("Rules by area" below).
- **Docs** in `docs/` never load by themselves. Read the one "Docs by area" names BEFORE touching that area;
  several are marked required because they record decisions that reverse the obvious approach.

Paths in the rule files and docs are relative to this repo's root unless they start with `../`. A new rule file
MUST carry `paths:` frontmatter: one without it loads on every read in this repo, exactly like this file.

## Build & test

```sh
mvn clean install                                  # full build -> trip/target/ROOT (expanded WAR) + ROOT.war
mvn test -pl trip                                  # all unit tests for the main module
mvn test -pl trip -Dtest=PersonTest                # one test class
mvn test -pl trip -Dtest=TripCommandsTest#method   # one method
```

- Coverage gate: JaCoCo runs with `test` -- bundle ≥ 90% line AND ≥ 90% per class. It only halts the build in
  CI (`-Djacoco.check.halt=true`); locally it just reports (`trip/target/site/jacoco/index.html`). Excluding a
  class from the gate requires the owner's approval first.
- Style gate: checkstyle (adapted Oracle/Sun rules, `checkstyle.xml` at the repo root -- 120-column lines, no
  tabs, no mandatory Javadoc; the file's header comment lists every adaptation) runs at `validate` on the
  `trip` module, main AND test sources, and any violation fails the build immediately, locally and in CI
  alike. Escape hatch for a genuine exception: `@SuppressWarnings("checkstyle:RuleName")`, used sparingly and
  with a comment saying why. Emergency skip: `-Dcheckstyle.skip=true`.
- Browser/integration tests are NOT here -- they are in `../medjugorje/webtest/` (Playwright).

## Modules & stack

| Module | Purpose |
|--------|---------|
| `jsft/` | JSF Templating helper library (`com.sun.jsftemplating`, `com.sun.jsft.*`) |
| `trip/` | Main WAR (finalName `ROOT`): models, DAOs, cache, CDI beans, REST API |

Java 25 · Jakarta EE 10 (Servlet 6.1, Faces 4.1 / Mojarra, CDI 4.1 / Weld) · PrimeFaces 15 + extensions · Jersey 3.1
(REST) · MapStruct · Lombok · Jackson · AWS SDK v2, sync clients blocking on virtual threads · Lettuce (**must stay
6.x**) · PayPal server SDK · Stripe Java SDK · Apache POI · TestNG. The full list, with every pin and native-library
caveat, is `.claude/rules/dependencies.md` (it loads with any `pom.xml`).

## Rules that apply everywhere

- `LocalMode` -- the single authority on local vs production. Explicit-only, defaults to production; see the
  workspace root CLAUDE.md. `TripBootstrapListener` (in `web/`) resolves it at startup and **must stay first**
  in web.xml's listener order (`ChatLifecycleListener` touches the DAO in its own `contextInitialized`).
- **Blocking-first on virtual threads.** Every DAO, cache and AWS call blocks its calling thread: no
  `CompletableFuture`, no future `join()`, no cached platform pools (`ArchitectureTest` greps these into the build).
  Fire-and-forget work spawns via `TripThreads.start`/`startAs`, fan-out uses `StructuredTaskScope`. The whole model:
  `.claude/rules/threading.md`.
- **Page state and row identity** (`docs/page-state-and-identity.md`, required reading): domain objects live in
  `requestScope`, resolved from the DAO caches every request, and a view or a session may hold ids and scalars only; row
  commands act on an identity baked in at RENDER, never on a row position. In full: `.claude/rules/page-state.md`.
- **Organization is THE tenancy boundary** (`docs/org-admin.md`, `docs/payments.md`). Accommodations and `Person` are
  the deliberate global exceptions (`docs/lodging.md`, "Roles").
- Bean `get*` methods (e.g. `getPerson`, `getTrip`) **never return null** -- they answer a blank object with a
  fresh id, so null checks don't fire and a careless PUT saves a junk row. REST code must use the
  `BaseResource.find*` helpers instead.
- A `Person` comes into being through exactly five paths: sign-up, a family manager's add-member flow, the
  REST people API, a lodging contact auto-created by `LodgingCommands.findOrCreateContact` (no
  credentials, no mail; email-code activation on first sign-in), and the owner-approved, script-only conference
  import (`migrate/`, `docs/migrations/conference-import.md`; reachable from no page). Never add a sixth without
  the owner.
- `config/KnownSettings` -- every runtime setting is declared ONCE here (`SettingDef` constants); the admin
  Settings page and the reading code both consume it. Never introduce a literal settings key + default.
- Lombok `@Builder` zeroes primitive defaults -- hand-write builders (see `TripBuilder`). This and seven more
  persistence gotchas, each from a real bug: `.claude/rules/persistence-gotchas.md` (read it before writing DAO-calling
  code).
- A new REST resource is registered **explicitly** in `TripApiApplication.getClasses()` (and in
  `ApiSurfaceTest.RESOURCES`); the REST rules are `.claude/rules/api.md`.

## Testing

- **TestNG only** (no JUnit). The surefire `surefire-testng` provider is explicitly pinned -- auto-detection
  once silently switched to the JUnit platform and reported "Tests run: 0 … SUCCESS". For the same reason,
  never add `jersey-test-framework` (it drags in junit-jupiter).
- The entire suite runs in local mode (surefire sets `trip.local.mode=true`): fake persistence, in-memory
  cache, `Pepper.none()`. This means **no unit test can catch a production-only mode/wiring bug** -- verify
  those against the container image.
- Fixtures, the Valkey integration tests and the harnesses: `.claude/rules/testing.md`.

## Rules by area (`.claude/rules/`, loaded on a matching Read)

| Rule file | Loads for | Holds |
|-----------|-----------|-------|
| `threading.md` | any main Java source | virtual threads, `TripThreads`, `StructuredTaskScope`, `AuditActor` |
| `persistence-gotchas.md` | any Java source, main or test | eight DAO/model gotchas, each from a real bug |
| `persistence.md` | `dynamo/`, `cache/` | `Persistence`, the DAO inventory, the cache architecture |
| `page-state.md` | `action/`, `web/`, `jsf/` | the two page rules in full, and what guards them |
| `model.md` | `model/` | domain types, lodging price vs cost, the `Serializable` rule, privacy |
| `api.md` | `api/` | resource registration, subjects, uploads, URLs, auth, redaction |
| `chat.md` | `chat/`, `model/chat/`, `moderation/`, chat beans | chat runtime, invites, photo channels, markup |
| `push.md` | `push/`, `PushResource` | APNs + Web Push, devices, gates |
| `media.md` | `media/`, `ChatUploadServlet`, chat-photo beans | the chat-photo pipeline and rotation |
| `security-headers.md` | the three header classes in `web/` | the security headers and CSP reports |
| `audit-config-security.md` | `audit/`, `config/`, `security/` | audit sinks, `KnownSettings`, hashing and the pepper |
| `scripts-migrations.md` | `scripts/`, `docs/migrations/`, `migrate/` | the scripts and the conference import CLI |
| `testing.md` | `trip/src/test/`, any `pom.xml` | seeded ids, Valkey tests, harnesses |
| `dependencies.md` | any `pom.xml` | the full stack with its pins and native-library caveats |

## Docs by area (`docs/`, read BEFORE touching the area)

| Doc | Read it before touching |
|-----|-------------------------|
| `page-state-and-identity.md` (required) | a new page, a scope variable, a row command, a bean reading view state |
| `caching.md` | any cache or DAO read path |
| `payments.md` | organizations and org members, processor configs, `Payment`, `PaymentCommands`, the Stripe webhook |
| `org-admin.md` | `OrgCommands`, org-scoped privileges, `SiteCommands` and org sites, branding and its uploads |
| `lodging.md` | anything lodging; "Price vs cost" before any money field; "Roles"; "Rate plans" |
| `family-accounts.md` | anything family-related |
| `budget.md`, `expense-report.md`, `reports.md` | the trip budget, the expense report, the report pages |
| `waivers.md` | trip documents ("waivers") |
| `conference.md` (required) | conferences, typed registration questions, trip pages and their look |
| `checkout.md` (required) | pricing or a checkout payment |
| `checkin.md` (required) | tickets and check-in |
| `privacy-settings.md` | Person field visibility, redaction, the profile/contacts pages |
| `content-templates.md` | `ContentCommands`, `TemplateCommands`, MAIL-kind templates |
| `photo-comments.md` | photo threads, the summary fold, `purgeChannel`, `PhotoChatCommands` |
| `chat-invites.md` | chat authorization (`canParticipate`/`readDenial`/`rejoin`), `ChatMembership` copy methods |
| `chat-formatting.md` | any chat body renderer |
| `../chat-design.md` (workspace root) | chat behavior: several decisions deliberately reverse the obvious |
| `push-notifications.md` | `push/`, the `pushMode` field, the three event hooks |
| `api-tokens.md` | `TripAuthFilter`, `AuthResource`, anything that mints or validates a credential |
| `api-contract.md` | the wire contract both native apps are written against |
| `security-headers.md` | adding a foreign script, frame, font or style source to any page |
| `migrations/` | one-time data migrations (`conference-import.md` holds the owner's rules) |
| `code-map.md` | finding a bean by EL name; the conference / trip page / checkout / check-in overview |
| `moderation.md`, `shared-itineraries.md`, `trip-delete.md` | those features |
