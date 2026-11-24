---
paths:
  - "trip/src/test/**"
  - "pom.xml"
  - "trip/pom.xml"
  - "jsft/pom.xml"
---

# Testing notes

`CLAUDE.md` carries the two that apply everywhere (TestNG only, and the whole suite runs in local mode). The rest:

- **Seeded fixtures carry fixed, canonical UUIDs** -- `FakeData` exposes one constant per seed
  (`FAKE_TRIP_ID`, `PUB_EN_1_TRIP_ID`, `ADMIN_PERSON_ID`, …); reference those, never a literal id. Trips,
  people and events are UUIDs in production, and a privilege scoped to anything else parses back as GLOBAL,
  so `Privilege.requireStorableScope` refuses it: the readable ids the seeds once used ("faketrip") made
  every trip-role write on a demo trip throw. The browser tests keep a mirrored copy in
  `../medjugorje/webtest/.../pw/SeededIds.java`, guarded against drift by `SeededFixturesPwIT`.
- Valkey integration tests auto-skip unless `TRIP_VALKEY_URI` is set (see the Testing section of
  `../medjugorje/setup-instructions.txt`). Pointing tests at a *configured* cache in local mode additionally
  requires `trip.cache.local.useConfigured`, a system property with NO env fallback on purpose: inferring it from
  `TRIP_VALKEY_URI` would point `mvn test` at a live cache for anyone who sourced a production shell environment.
- Seeded demo dates are fixed: the demo stay is `FakeData.fakeStayStart()`/`fakeStayEnd()` (day 48 15:00 to day
  60 10:00), and the lodging event, option and flights derive from it. A `TripEvent` with a null end lands three
  hours after its start, so an arrival flight must take off ~2h+ before check-in to arrive with the group.
- **A test that fails only on CI is usually TestNG CLASS ORDER**: surefire's `runOrder=filesystem` is readdir
  order (APFS vs Linux). Reproduce with `mvn -B test -pl trip -Dsurefire.runOrder=reversealphabetical
  -Djacoco.skip=true`. The suite shares ONE `DAO` and cache, and the fake has no people table, so after any
  `clearAllCaches()` every seeded person is unresolvable until `FakeData.addFakeData()` runs again: a class that
  needs the seeds re-seeds in `@BeforeClass`. `-Dtest='A,B'` re-alphabetizes, so it cannot force an order; drive
  the victim after `clearAllCaches()` in a scratch test.
- A test that flips `LocalMode`'s `resolved` off (`MailSendSesPathTest`) MUST call `DAO.getInstance()` BEFORE the
  flip: the singleton picks its `Persistence` once, so the first touch afterwards (the audit sink's writer) builds
  the REAL DynamoDB persistence for the rest of the JVM -- order-dependent, so it bites when the class runs alone.
  Assert the persistence type before and after. Do not "fix" it with `trip.audit.dynamo=false`: the audit sink
  being on is how the audit page is tested.
- Coverage figures depend on `lombok.config`'s `addLombokGeneratedAnnotation` (without it every `@Data`/`@Builder`
  method counts as uncovered). Per-class gate exemptions live in `trip/pom.xml`'s `coverage-gate` execution, each
  with its reason.
- New tests go in `trip/src/test/java/`; useful harnesses: `dynamo/DynamoLocal` (real DynamoDB engine),
  `cache/LocalRedisCluster`, `api/ResourceTestSupport`.
- Shared fixtures, public so any package can use them; reach for these before writing a private helper:
  `util/TestPeople` (saved people; refuses an `admin*`/`user*` tag, a local-mode sign-in persona's address),
  `util/TestWaits` (`awaitTrue`, `captureStdout`), `action/TestCallers` (site admin, plain person, scoped grant),
  `action/LodgingFixtures` (hotels, offers, `savedGuest`; ONE JVM-wide counter, because hotel discovery refuses
  a duplicate detail), `action/TestMail` (accepting mail mock), `action/ChatTrips` (a saved chat trip),
  `web/CapturedResponse` (servlet status and body), `web/FakeSessions` (map-backed `HttpSession`).
