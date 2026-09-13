# Moderation, blocking and account deletion

Added 2026-09-13 for the App Store review of the native app (guidelines 1.2 "user-generated content" and
5.1.1(v) "account deletion"). Everything here is exposed over the REST API and used by the UniteTrip iOS app;
the website keeps its existing moderator tools (mute, remove, delete) and gains nothing new in v1.

## The four things guideline 1.2 asks for

| Requirement | Where it lives |
|---|---|
| A method for filtering objectionable material | `chat.blockedTerms` + `moderation/ContentFilter` — a whole-word, case-insensitive list applied at send/edit time to chat messages and photo comments; refused with `BLOCKED_CONTENT` (400). Site admins edit the list on `/admin/settings.jsf` → Chat moderation. Empty = off. |
| A mechanism to report offensive content, with timely responses | `ModerationCommands.report` behind three endpoints (below). A report is a `REPORT` audit record plus an email to `moderation.report.email` (default `facilitators` → org contact → Site email) with a BCC to `moderation.platform.email`. The Terms promise a response within 24 hours (`ModerationCommands.RESPONSE_PROMISE`). Nothing is auto-hidden on a report. |
| The ability to block abusive users | `BlockListCommands` + `BlocksResource` (`/api/me/blocks`). One reserved person-data row per person (`DataId "blocked-people"`, type `BlockList`). The app hides a blocked author's messages, photos and comments; the server refuses an `@mention` of the blocker by the blocked person. |
| Published contact information | `support.jsf` on every host (info@unitetrip.com + the organization's contact card) and the Settings → About screen in the app. |

Moderator tools that already existed and are cited in the store notes: the auto-mute ladder, manual mute/unmute,
remove-from-channel (no self-rejoin), admin delete of any message/photo/comment, the MODERATED channel mode.

### Endpoints

```
POST /api/chat/channels/{channelId}/messages/{msgId}/report   {"reason": "..."}   -> 202 {reported, id, response}
POST /api/photo-chat/comments/{msgId}/report?key=<s3Key>      {"reason": "..."}   -> 202
POST /api/photo-chat/report?key=<s3Key>                       {"reason": "..."}   -> 202
```
Refusals: 404 (the reporter cannot see the content, or it is gone), 429 + `Retry-After` (10 reports per person
per hour — `UploadRateLimiter` reused), 403 (signed out), 400 (nothing to report). The `reason` is free text up to
1000 characters; the app sends a category word followed by the user's note.

```
GET    /api/me/blocks                 -> {personIds: [...]}
PUT    /api/me/blocks/{personId}      -> {personIds}   404 unknown person, 400 self, 409 list full (500 max)
DELETE /api/me/blocks/{personId}      -> {personIds}   idempotent
```

## Account deletion

```
GET  /api/account/deletion-preview  -> {blocked, settleRequired, reasons[], amountOwedCents, amountCreditCents,
                                        contactEmail, trips[], erased[], retained[]}
POST /api/account/delete            {"confirm": "DELETE"}
      200 {deleted: true}  |  409 ACCOUNT_DELETE_BLOCKED {preview}  |  400 wrong confirmation  |  500 incomplete
```
`POST account/delete` rather than `DELETE account` because the body (the typed literal) is what makes it
deliberate, and a DELETE with a body is unreliable through intermediaries.

### What "delete" means (decided 2026-09-13)

**Gone, immediately and permanently:** password, passkeys, every bearer token and remember-me cookie; name,
email, phone, address, birthdate, passport, TSA number, emergency contact, notes, privacy choices, profile
pictures; every chat message and photo the person posted and every photo comment; drafts; chat memberships (left);
person-data rows (block list, todo status, registration answers); privileges (global, per-trip, per-org);
organization memberships and admin seats; family links.

**Kept, under an anonymized record:** registration rows and every transaction. Money rows are never destroyed
(user-locked payments rule) and the trip's accounting must still add up. The `Person` row stays with
`first = "Deleted"`, `last = "Account"`, everything else null, and the existing soft-delete marker set, which the
DAO already honours (invisible to lookups, email index and search). Rosters and ledgers show "Deleted Account".

**Dependents:** family members the person alone manages (created members with no login and no other manager)
are deleted with the account — they exist only as that person's household. Members with a login, or with another
manager, stay; the leaver is removed from the family and from every remaining member's `managedUsers`.

### Settle first

With `account.delete.requireSettled` on (default), deletion is refused while the person — or a dependent — is
registered (status other than Not Registered) for a trip that has not ended, or owes a balance (negative sum of
their per-row shares, per organization). The preview names the trips and amounts and gives the contact email
(the blocking trip's organization, else the Site email). Legal basis for the US-only launch: no US federal law
obliges deletion; CCPA and GDPR both carve out data needed to complete a transaction or collect a debt.

The switch exists so the rule can be relaxed **without a deploy** if App Review objects: flip it off on
`/admin/settings.jsf` → Account deletion, and deletion proceeds (the notice still shows what is owed).

Two refusals ignore the switch because they would leave the data model broken: the sole admin of an organization
(`SOLE_ORG_ADMIN` — transfer admin first) and a dependent who is themselves unsettled (`DEPENDENT_UNSETTLED`).

### The notice email

Composed **before** anything is erased (afterwards the name and email no longer exist) and sent once per
organization the person had memberships, trips or money with, each limited to that organization's rows
(tenancy rule). Recipient slot `account.delete.notify.email` (default `org` → the organization's contact, else
the Site email); From `account.delete.notify.from`; BCC `moderation.platform.email`. Subject
`Account deleted: <name> (<email>)`. Body: who and when, dependents deleted with them, every trip (dates,
registration status, upcoming/past), the complete transaction list (date, type, description, the person's share),
and a bold **AMOUNT OWED BY TRAVELER** / **AMOUNT OWED TO TRAVELER** / **Balance: settled** line.

If SES fails, the same text is written to the audit trail as an `ALARM` and the deletion proceeds: a mail outage
must not block a person's right to leave, and the record must never be lost.

### Audit

`PERSON` / `ACCOUNT DELETED (self-service): …` on success (target = the original email + id), `ALARM` on an
incomplete run (the client sees 500 and is told to contact support; re-running is safe — every step is idempotent).

## Settings added

`chat.blockedTerms`, `moderation.report.email`, `moderation.report.from`, `moderation.platform.email`,
`account.delete.requireSettled`, `account.delete.notify.email`, `account.delete.notify.from` — all in
`KnownSettings`, the mail slots also in `MailAddressCommands.SLOTS`.

## Tests

`ContentFilterTest`, `ChatContentFilterTest`, `ModerationCommandsTest`, `BlockListCommandsTest`,
`AccountDeletionCommandsTest` (real fake-persistence DAO; mail and profile pictures mocked), `AccountResourceTest`,
`BlocksResourceTest`, report cases in `ChatResourceTest` / `PhotoChatResourceTest`, `ApiSurfaceTest`.

## Curl recipes (local container, `scripts/enable-api-tokens.sh` run first)

```sh
TOKEN=$(curl -s -X POST localhost:8080/api/auth/token -H 'Content-Type: application/json' \
  -d '{"email":"user2","password":"user"}' | jq -r .accessToken)
curl -s localhost:8080/api/account/deletion-preview -H "Authorization: Bearer $TOKEN" | jq
curl -s -X PUT "localhost:8080/api/me/blocks/<personId>" -H "Authorization: Bearer $TOKEN" | jq
curl -s -X POST "localhost:8080/api/chat/channels/trip:<tripId>/messages/<msgId>/report" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"reason":"Spam"}' -i
curl -s -X POST localhost:8080/api/account/delete -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"confirm":"DELETE"}' -i
```
