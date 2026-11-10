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
  requires the sysprop-only `trip.cache.local.useConfigured` guard.
- New tests go in `trip/src/test/java/`; useful harnesses: `dynamo/DynamoLocal` (real DynamoDB engine),
  `cache/LocalRedisCluster`, `api/ResourceTestSupport`.
