---
paths:
  - "trip/src/main/java/**"
  - "trip/src/test/java/**"
---

# Persistence gotchas (each has caused a real bug)

- DAO reads return **copies**: mutating an object you read earlier and saving its *parent* writes nothing. A JSF
  converter that loads by id (`TripEventConverter`) hands back a DETACHED copy: re-resolve inside the parent
  (`Trip.getTripEvent(id)`) before mutating, and never bind an input to a method-call result. `@Data` equality
  hides it (`contains()` is true, the write lands elsewhere): assert after a fresh page load, not on the ajax reply.
- `Trip` stores event **ids** (`tripEventIds`); `TripEvent`s resolve lazily on the first `getTripEvents()`
  call per instance and are memoized (same mutable list every call -- mutate-then-`saveTrip` still works).
  A trip whose events are never touched never fans out, and `saveTrip` skips event rows for unresolved
  instances. The id list is authoritative: a failed event read never shrinks it on save.
- A read immediately after a write can be stale in production (async invalidation) -- pass along the object
  you just saved instead of re-reading.
- `queryAll` ignores `limit` (it paginates the whole partition); use `query()` for unbounded partitions.
- A key condition may name the sort key ONCE: a two-sided range is `BETWEEN :lo AND :hi`, never
  `> :after AND < :before` (DynamoDB answers 400; a chat reaction window shipped that shape with green tests).
  Any production `ValidationException` the fake never raised: teach `InMemoryPersistence` the rule in the fix's
  commit. Local mode fakes only what has a stand-in (the DAO, the cache, `MailCommands.send`); a raw SDK client
  stays LIVE in tests, so give every new AWS client a local-mode stand-in or refusal (`MailLocalModeTest`; media
  uploads refuse without `TRIP_MEDIA_BUCKET`).
- Lombok `@Builder` zeroes primitive defaults -- hand-write builders (see `TripBuilder`) and assert
  `builder().build().equals(new Whatever())` in the model test.
- `Registration` is immutable and every field is `@With`: change one field with `withX`, NEVER rebuild through a
  constructor -- the 5-arg one predates the ticket, payment, price and check-in fields and silently erases them
  on the next save (two REST paths did; `docs/conference.md`).
- A DAO on `PartitionScanCache` must delete rows via `removeOne`, never `invalidate()`: in local mode the
  cache client IS the datastore (soft revalidate off), so a full invalidation silently deletes every OTHER
  row too -- one removed chat photo emptied the whole trip album before this rule existed.
