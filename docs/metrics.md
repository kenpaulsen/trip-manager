# Metrics: a week of per-minute numbers, kept in Valkey, shown on an Editable Page

The app keeps a rolling week of a few numbers about each server (ECS task), one sample a minute, and shows them
on a dashboard. Nothing goes to CloudWatch and nothing is written to the log: the numbers live in the Valkey
cache the app already pays for. Owner decisions (2026-11-24): Micrometer for collection, a starter set of
technical metrics (no business metrics yet), one combined line per graph with a per-server toggle, and the
dashboard as an Editable Page on every organization's site, site admins only for now.

## What is measured (`metrics/MetricCatalog`)

| Metric | Fields | Source |
|---|---|---|
| `ddb.pool` | `max` connections in use (peak), `pending` calls waiting (peak), `calls`, `limit` (pool size) | `PoolMetricPublisher`, the AWS SDK's own per-call `MetricPublisher` on the DynamoDB client |
| `jvm.heap` | `used`, `committed`, `max` (MB, at the minute's end) | Micrometer `JvmMemoryMetrics` |
| `jvm.gc` | `pauses`, `pauseMs` (in the minute) | Micrometer `JvmGcMetrics` |
| `http.requests` | `count`, `errors` (5xx or a throw), `meanMs`, `maxMs` (slowest), `active` (most in flight) | `RequestMetricsFilter`, declared last in the live `web.xml` |

The catalog is the one list: the writer stores exactly these, the graph template offers exactly these, and a
Micrometer `MeterFilter` denies every other meter at registration (`TripMetrics.KEPT`). Each field says how its
minutes fold into a graph's bucket (`Rollup`: a count always sums, a peak keeps the highest) and how servers
combine (`Combine`: counts add up, peaks take the highest server). A field may name a ceiling in the same record
(`limit`), which a graph can draw as its own line.

### Why our own peaks

Micrometer's `max` is a decaying window, not one step. Measured on 1.18.0 with a mock clock: a timer that recorded
500 ms in one minute still reported 500 ms at the end of the NEXT minute, in which nothing was recorded. A burst
that comes and goes between two samples is what these metrics exist to catch, so every stored peak is a
`MinuteMax` (an atomic max that resets when read). Micrometer's step COUNTS are exact per minute and are used as is.

### Why a filter, not the Tomcat binder

Requests run on virtual threads (`StandardVirtualThreadExecutor`), so the connector has no busy-thread count worth
reporting; requests in flight is the number that still means something, and a filter measures it. A suspended
async request (a chat long-poll) is timed until it suspends.

### The DynamoDB publisher runs inside every call

`PoolMetricPublisher.publish` runs on the calling thread of every DynamoDB request. It must never read a setting
(the settings DAO sits on the same path and would recurse), touch the cache, block or throw; a report it cannot read
is counted (`dropped()`) and dropped. Proven against DynamoDB Local (`PoolMetricsDynamoLocalTest`): every call
reports a leased connection and the pool's size. Local mode never builds this client, so locally (and in the
webtests) the pool graphs show "no numbers yet".

## Storage (`MetricsWriter`, keys in `CacheKeys`)

- `metrics:v1:<metric>:<taskId>`: a hash. Field = the minute of the week (Sunday 00:00 UTC is 0, Saturday 23:59
  is 10079); value = that minute's record, `{"t": <epoch second>, <field>: <number>, ...}`. A week later the same
  field is overwritten, so a metric's storage is bounded by construction. `t` tells this week's minute from last
  week's in the same field; a missing field is a minute with no sample.
- `metrics:v1:tasks`: a sorted set of task ids scored by their last write, trimmed to the newest 64.
- Both expire 8 days after their last write (pushed out hourly, not every minute), so a retired server's data ages
  out by itself.
- Outside `t1:` on purpose: `DAO.clearAllCaches()` clears `t1:` only, and the minutes right after a cache clear are
  among the ones worth seeing. No braces in the keys (a `{...}` is a cluster hash tag).
- `taskId` is the LAST segment of the ECS task ARN, read once from `ECS_CONTAINER_METADATA_URI_V4` (`TaskIdentity`),
  never the whole ARN: the ARN carries the account id and this repo is public. Elsewhere it is `local-<HOSTNAME>`.

Measured on a local server (2026-11-24): a full week of one metric (10,080 fields, about 60-byte records) is 1.08 MB;
reading it costs 0.9 ms of server time in one HGETALL and 0.17 to 0.31 ms per 1,440-field HMGET. The reader uses
day-sized chunks, far under the 10 ms slowlog threshold. Four metrics are about 4.5 MB per server-week: inside the
cache's paid minimum.

## The publisher (`ValkeyMeterRegistry`, `TripMetrics`, `MetricsLifecycleListener`)

- A Micrometer `StepMeterRegistry` with a one-minute step. `publish()` samples every catalog metric
  (`MetricsSampler`, which drains each `MinuteMax` whether or not the minute is then written) and writes the records
  under the minute that just ended.
- It runs on OUR schedule, one second past each wall-clock minute (`TripMetrics.delayToNextMinute`), never
  Micrometer's `start()`: Micrometer publishes at a random point in the first 80% of each step
  (`PushMeterRegistry.calculateInitialDelay`, read from the 1.18.0 bytecode). Its step counts stay exact either
  way, but every reset-on-read peak would be a 60-second window shifted by up to 48 seconds from the minute it is
  filed under.
- The scheduler's one thread comes from `TripThreads.factory()`, so every write rides the BACKGROUND cache lane and
  can never crowd out a request's. A write the cache refuses, or a meter that cannot be read, is dropped: counted in
  the registry's `dropped()`, never retried, never thrown into the scheduler. A refused write is logged by the
  cache client itself (`ValkeyCacheClient` logs every refused command), not by the metrics code.
- `MetricsLifecycleListener` (declared in the live `web.xml` after `TripBootstrapListener`) starts it, binds the JVM
  meters, and publishes one sample right away under the current minute, so a fresh server has something to show at
  once; the first regular publish overwrites that partial minute with the whole one. At shutdown the last publish
  holds the current partial minute, so it never overwrites the complete minute before it. A failure to start is
  logged and never stops the site.
- Kill switch: the `metrics.enabled` setting (on by default, Settings, "Performance"), read once a minute by the
  publisher alone. Off, the minute is still sampled (and its peaks drained) but not written.

## Reading it back (`MetricsStore`)

`read(metric, field, range, bucket, rollup, perTask)` lists the servers seen in the range, reads their minutes in
day-sized chunks, skips records whose `t` falls outside the range, folds minutes into buckets, and answers every
bucket from the range's first to its last, null where there was no sample (a graph draws a gap, never a zero).
`combinedLine` folds the per-server lines into one by the field's `Combine`, so one read serves both views.
A server's records of a metric are read and parsed ONCE per request (the dashboard passes its `RequestMemo` as the
store's `Memo`) and kept as columns, so every graph of that metric, and a graph's limit line, share the read: the
starter dashboard's nine graphs read four hashes per server, not twelve.
"Automatic" resolution is the finest of 1, 5, 10 or 60 minutes that keeps a line under about 1,100 points (7 days at
10 minutes).

## The dashboard

- **An Editable Page**, `metrics`, on every organization's site (`/page.jsf?name=metrics`), out of the menu
  (HIDDEN) and gated to site admins (`SitePage.Access.SITE_ADMINS`, `docs/org-admin.md`, "Who can see a page").
  It is a starter page (`content/starter-site-pages.json`, `everySite`, so the platform org's site gets it too;
  in local mode only Acme's and Beta's sites have one, because `FakeData` seeds the platform site's marketing pages
  and marks it seeded);
  sites seeded before it existed get it from `scripts/seed-metrics-page.sh` in the private repo, after
  `install-starter-templates.sh` has added the `metric-graph` template.
- **ONE graph template composes every layout** (owner, 2026-11-24, after a prototype round of four directions):
  a graph has a size on the `graph-grid` container's four-column grid (Width 1 to 4 columns, Height 1 to 4 rows of
  9.5rem; the grid cell spans them through CSS `:has()`, since a container's row cannot read a child's values; two
  columns, then one, on narrow screens). A one-row graph is a sparkline tile: no axes, legend, tooltip or zoom.
  A Headline number shows the latest COMPLETE bucket's value large, with the peak in range under it (highlighted
  past the warning level). Controls are the graph's own: range, servers and zoom; the range only; or none. A
  graph may have a Graph name, and another graph's "Click shows it in" lists the names its click shows its metric
  in (0 or more): that is the whole of the overview-and-focus design, with no special tile or focus code. Names,
  not item ids, because a template's choice lists cannot see the other items of a page, and a name survives a
  graph being re-added or moved.
- **The default dashboard** (the starter, laid out as the owner's chosen "Overview" direction): an intro (with a
  link back to the organization hub, the starter's `{orgId}` token), then a `graph-grid` of eight 1x1 tiles with
  headline numbers (DynamoDB connections in use, calls waiting, requests, server errors, slowest request, requests
  in flight, heap used, GC pause time), each targeting `focus`, and one 4x3 graph named `focus` with all its
  controls, starting on the connection pool (limit line, warning at 160). The page hides the sidebar (its own
  choice, `SitePage.sidebar`). Graphs are the same on every organization's page for now: they are the servers'
  numbers, not the organization's.
- **The graph template**, `metric-graph` (`content/MetricGraphType`, fragment `WEB-INF/ptypes/metricGraph.xhtml`):
  one graph per item, so a site admin adds, removes, resizes and tunes graphs in the ordinary edit mode. Besides
  the layout options above, its options follow what monitoring tools offer a panel: the metric (every catalog
  field, labeled with its metric and unit), a note under the title, one combined line or one per server, the range
  (6 hours to 7 days), the resolution, how a bucket folds (the field's own, max, average, min), line, area or bars,
  the axis minimum and maximum, a warning and an alert line, and whether to draw the field's own limit. The Add
  dialog offers the type only to a site admin (`ProgrammaticContentTemplate.offeredTo`), and it fits no trip page.
- **`MetricsCommands`** (`#{metrics}`): `canView` (site admins, for now), `hubUrl` (the organization hub's Metrics
  card, `admin/orgSettings.xhtml`, "Run this organization"; null when the org has no site or no dashboard), and
  `graph(instance)`, which answers null before reading anything for anyone who may not see metrics and otherwise
  the graph's heading, its size and its data as JSON: both the combined line and every server's line (each a first
  bucket, a step and one value per bucket, a fifth of the size of a timestamp per point), the limit line, the
  threshold values, the axis choices, and the view (`metric`, `title`, width, height, controls, headline, name,
  targets). `GraphView.noData` is named so because `empty` is an EL operator: a page
  cannot call `graph.empty()`, and since every content page builds every type's fragment, that typo took every
  content page down in the first local run.
- **Drawing** is the browser's (`resources/trip-js/metricGraph.js`, one controller per graph; a click on a graph
  with targets calls `show` on each named graph, which redraws with the clicked graph's data and title under its
  own size, controls and range): Chart.js from PrimeFaces' own bundles
  (`moment/moment.js`, then `chart/chart.js` with its moment time adapter and zoom plugin), loaded once and only on a
  page that has a graph. Not `p:chart`: every content type's fragment is built on EVERY content page, and `p:chart`'s
  resource dependencies would load Chart.js and moment on all of them. Colors are the theme's variables, read at
  draw time. Measured 2026-11-24 in the container: a page holding a `p:chart` with `rendered="false"` still loads
  `moment/moment.js` and `chart/chart.js` (its `@ResourceDependency`s attach when the component is built, rendered
  or not). Every viewer control (combined or per server, a shorter range, drag to zoom and its reset) is
  client-side, so the fragment stays render-time only and holds nothing in the view.

## Cost

About 1.1 MB of Valkey per metric per server-week (measured), so about 4.5 MB for the catalog; the cache's paid
minimum covers it, and past it the rate is about $0.06 to $0.09 a month per metric. About 6 commands a minute per
server. Under the owner's $1 a month rule.

## Adding a metric

1. Collect it: a Micrometer meter (name it in `TripMetrics.KEPT`) or a `MinuteMax` for a peak.
2. Declare it in `MetricCatalog` (fields, units, rollup, combine, limit) and read it in `MetricsSampler`.
3. Add its words (`server.metrics.<metric>.label`, one per field, any new unit) in English, Spanish and Croatian;
   `MetricGraphTypeTest` fails on a missing one.
4. It appears in the graph template's metric list at once; add a graph for it on the dashboard in edit mode.
