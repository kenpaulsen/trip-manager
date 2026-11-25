---
paths:
  - "trip/src/main/java/**"
---

# Threading model

Blocking-first on virtual threads (the model `../medjugorje/.claude/rules/docker.md` points at). Requests run one
virtual thread each (Tomcat's `StandardVirtualThreadExecutor`, thread prefix `peace-`; the webtest `EmbeddedApp` wires
the same executor), and every DAO, cache and AWS call **blocks its calling thread** -- sync AWS clients, no reactive
layer. The ONLY `CompletableFuture` in the main tree is Lettuce's, consumed inside `ValkeyCacheClient`'s single
interruptible await point; `ArchitectureTest` greps these invariants into the build (no CF import elsewhere, no future
`join()`, no cached platform pools). There is deliberately no `maxThreads`-style cap: admission control lives where load
lands -- the DynamoDB connection pool (200), the two Lettuce request queues (5000 foreground / 500 background via
`CacheLane`), `RefreshPermits`, and per-feature gates (e.g. `BackgroundRemover.GATE`).

- Fan-out uses `StructuredTaskScope` (a preview API -- `--enable-preview` is set in the compiler args, the
  surefire argLine, the container's `CATALINA_OPTS` AND `ci/buildspec-integtest.yml`): trip-event resolve,
  mail-merge sends, trip-reminder sends (`ReminderSender`, the mail merge's bound), password migration
  (semaphore-bounded), the people search's row resolve (`PersonDAO.resolvePeople`: forks per id so Lettuce
  pipelines the cache reads, DynamoDB misses bounded at 16). Forks inherit ScopedValues. A fan-out that hits a
  pooled client (SES, the SDK's connection pool) needs a Semaphore (`MailCommands.MERGE_PARALLELISM`): unbounded
  forks fail as nulls = silently skipped recipients.
- Fire-and-forget background work spawns via `TripThreads.start`/`startAs` (named virtual threads, bound to
  the background cache lane). Plain spawns do NOT inherit ScopedValues -- work that outlives its request
  re-binds identity via `startAs` (`RequestContext`/`AuditActor`); schedulers bind `RequestContext.system()`.
- Never run caller code on a Lettuce/Netty event loop (pub/sub listeners hand off to a fresh virtual thread)
  and never block a request on delivery backends: the audit sinks queue to dedicated daemon writer threads.
- The few remaining platform threads are ones we cannot make virtual: Lettuce's Netty event loops, the AWS
  SDK's Apache client internals, the dedicated `bg-remover-inference` thread, and the shutdown hook. Native (JNI)
  or long CPU-bound work never runs on a request virtual thread: JNI pins its carrier, the scheduler does not
  compensate, and a 1-vCPU container may have ONE carrier, so one inference froze every request. The audit-sink
  writers and the `ChatDigestScheduler` and `ReminderScheduler` (`trip-reminders`) timer threads are virtual, as is
  the metrics publisher (`TripMetrics`, our own minute-aligned scheduler on `TripThreads.factory()`, so its
  once-a-minute Valkey writes ride the BACKGROUND lane; Micrometer's own `start()` is never called; `docs/metrics.md`). The AWS SDK's `PoolMetricPublisher` runs inside every
  DynamoDB call on the CALLER's thread: it never reads a setting, touches the cache or throws.
- `AuditActor.current()` reads the `RequestContext` ScopedValue, bound per request by `SessionRecoveryFilter`
  and inherited by `StructuredTaskScope` forks. A plain `TripThreads.start` spawn does NOT inherit it -- use
  `startAs(actor, ...)` for work that outlives its request (`CompletableFuture` is banned by `ArchitectureTest`).
- A `java.net.http.HttpClient` is built ONCE and shared, with
  `.executor(Executors.newVirtualThreadPerTaskExecutor())`: the default executor is a platform cached pool, and
  each client owns a SelectorManager platform thread (a per-call client leaked one per click).
