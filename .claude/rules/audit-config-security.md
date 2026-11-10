---
paths:
  - "trip/src/main/java/org/paulsens/trip/audit/**"
  - "trip/src/main/java/org/paulsens/trip/config/**"
  - "trip/src/main/java/org/paulsens/trip/security/**"
---

# `audit/`, `config/`, `security/`

- `audit/` -- `Audit` writes every event to CloudWatch Logs (`TRIP_AUDIT_LOG_GROUP`) AND the `audit` DynamoDB
  table when deployed, stdout otherwise. Append-only in IAM *and* in code -- no update/delete paths.
- `config/KnownSettings` -- every runtime setting is declared ONCE here (`SettingDef` constants); the admin
  Settings page and the reading code both consume it. Never introduce a literal settings key + default.
- `security/` -- `PasswordHasher` (BCrypt over a peppered pre-hash) + `Pepper` (versioned key from Secrets
  Manager; unset ⇒ logs a warning, hashes unpeppered). Local mode uses `Pepper.none()`.
