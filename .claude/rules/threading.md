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
lands -- the DynamoDB connection pool (100), the two Lettuce request queues (5000 foreground / 500 background via
`CacheLane`), `RefreshPermits`, and per-feature gates (e.g. `BackgroundRemover.GATE`).

- Fan-out uses `StructuredTaskScope` (a preview API -- `--enable-preview` is set in the compiler args, the
  surefire argLine, the container's `CATALINA_OPTS` AND `ci/buildspec-integtest.yml`): trip-event resolve,
  mail-merge sends, password migration (semaphore-bounded). Forks inherit ScopedValues.
- Fire-and-forget background work spawns via `TripThreads.start`/`startAs` (named virtual threads, bound to
  the background cache lane). Plain spawns do NOT inherit ScopedValues -- work that outlives its request
  re-binds identity via `startAs` (`RequestContext`/`AuditActor`); schedulers bind `RequestContext.system()`.
- Never run caller code on a Lettuce/Netty event loop (pub/sub listeners hand off to a fresh virtual thread)
  and never block a request on delivery backends: the audit sinks queue to dedicated daemon writer threads.
- The few remaining platform threads are ones we cannot make virtual: Lettuce's Netty event loops, the AWS
  SDK's Apache client internals, the dedicated `bg-remover-inference` thread (ONNX inference pins its carrier,
  so it runs on one platform thread), and the shutdown hook. The audit-sink writers and the
  `ChatDigestScheduler` timer thread are virtual.
- `AuditActor.current()` reads the `RequestContext` ScopedValue, bound per request by `SessionRecoveryFilter`
  and inherited by `StructuredTaskScope` forks. A plain `TripThreads.start` spawn does NOT inherit it -- use
  `startAs(actor, ...)` for work that outlives its request (`CompletableFuture` is banned by `ArchitectureTest`).
