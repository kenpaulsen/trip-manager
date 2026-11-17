---
paths:
  - "trip/src/main/java/org/paulsens/trip/audit/**"
  - "trip/src/main/java/org/paulsens/trip/config/**"
  - "trip/src/main/java/org/paulsens/trip/security/**"
---

# `audit/`, `config/`, `security/`

- `audit/` -- `Audit` writes every event to CloudWatch Logs (`TRIP_AUDIT_LOG_GROUP`) AND the `audit` DynamoDB
  table when deployed, stdout otherwise. Append-only in IAM *and* in code -- no update/delete paths. CloudWatch is
  the ledger, the table the index: when they disagree, believe CloudWatch.
- `CloudWatchAuditSink` only `offer()`s to a bounded queue: it never blocks or throws on a request thread (call
  sites are mid-login, mid-mail); queue-full, delivery and startup failures print to stdout, never silently.
  Batches are sorted by timestamp (CloudWatch rejects out-of-order events). A TRANSIENT `createLogStream` failure
  at startup (`SdkClientException`, throttling, 429/5xx) brings the sink up pending (`AUDIT-STREAM-PENDING`) and
  `ensureStream()` retries before each batch (`AUDIT-STREAM-READY`), pending batches going to stdout; only a
  non-transient failure throws, and `Audit` (which picks its sinks ONCE) then falls back to stdout loudly. Keep
  that split: before it, one cold-call timeout cost a server its stream for life.
- The `audit` table shape is owner-decided (do not "optimize" it): PK `day` (UTC `yyyy-MM-dd`, a DynamoDB reserved
  word), SK 13-digit zero-padded epoch millis, NO GSIs (reads walk day partitions backwards,
  `AuditDAO.MAX_DAYS_PER_PAGE`); a collision retries 1 ms later (`withNextMilli`), keeping arrival order. Writes
  carry `attribute_not_exists` (PutItem overwrites, and IAM cannot say "only if absent"); the private repo's infra
  test `AuditTableGrantTest` keeps Delete/Update/BatchWrite out of the grant.
- `AuditActor` ids: `Person.Id` has `@JsonValue` but Lombok's `toString()` (`Person.Id(value=abc)`), so unwrap the
  id explicitly. Older audit rows hold that mangled form, so a query by actor id must match both shapes. Tests
  assert `email()` AND `id()` (`api/AuditActorRestTest`): `isKnown()` passes on the mangled value.
- `config/KnownSettings` -- every runtime setting is declared ONCE here (`SettingDef` constants); the admin
  Settings page and the reading code both consume it. Never introduce a literal settings key + default.
  Per-trip/per-channel settings live on that entity's settings object (`ChatSettings`), not here. Blank on the
  Settings page = UNSET (`saveKnown` deletes the row); booleans render as a three-way menu, never a checkbox (a
  checkbox cannot say unset). Undeclared stored keys show under "Other settings"; `KnownSettingsTest` parses
  every default.
- Mail-address settings are mode SLOTS (`MailAddressCommands`): a value may be the sentinel `site` or `org`, so
  NEVER read one with `config.getString`; resolve through `from`/`recipient`/`replyTo` (pages: `fromFor`/
  `recipientFor`/`replyToFor`). A From is never `org` and its domain must be SES-verified
  (`MailCommands.verifiedSendingDomains()`); recipients and Reply-To take any valid address.
- `security/` -- `PasswordHasher` (BCrypt over a peppered pre-hash) + `Pepper` (versioned key from Secrets
  Manager; unset ⇒ logs a warning, hashes unpeppered). Local mode uses `Pepper.none()`. Rotation keeps EVERY old
  key: a `p<ver>:` hash is re-peppered only on that user's next successful login (no plaintext is stored), so
  dropping a key locks out everyone still on it; drop one only when no `pass` row carries its prefix. Only
  genuinely plaintext rows can be bulk-migrated.
