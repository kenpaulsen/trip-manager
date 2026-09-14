# Push notifications — direct APNs + Web Push

Read this before touching anything under `push/`, `action/PushCommands`, `api/PushResource`, the
`pushMode` field of `ChatNotifyPref`, the recipient policy in `chat/ChatNotifications`, or the three
event hooks (`RegistrationCommands.approvePending`, `PaymentCommands.recordNow`,
`SupportChatCommands.notifyAdmins`). It is the backend half of the 2026-09-13 push plan; the website
(service worker, manifest, profile fieldset, `push-secret.sh`) lives in `../medjugorje/`, the app in
`../UniteTrip/`.

Ships dark: `push.enabled` (Settings → Push notifications) is the master switch and is `false` until the
secret is deployed and the App Store build is out.

## Shape

```
ChatCommands.send ─► ChatNotifications ─► CompositeChatNotifier ─► EmailChatNotifier
                                                                 └► PushChatNotifier ─┐
RegistrationCommands.approvePending ─► PushNotifications.registrationApproved ────────┤
PaymentCommands.recordNow (RECORDED, !sandbox) ─► PushNotifications.paymentRecorded ──┤
SupportChatCommands.notifyAdmins ─► PushNotifications.supportRequest ─────────────────┤
                                                                                       ▼
                     PushSender (push.enabled → person switch → devices → dedupe claim → quiet hours → badge)
                              │                                     │                        │
              PushDevices (person_data "push-devices")      ApnsClient (kind ios)     WebPushClient (kind web)
                                                                    │                        │
                                              api[.sandbox].push.apple.com     each browser's push service
```

Everything downstream of a request runs on a fresh virtual thread (`TripThreads.startAs(AuditActor.system(),
…)`), synchronous inside: `HttpClient.send`, never a `CompletableFuture` (`ArchitectureTest`). Nothing in the
push path can fail the request that triggered it; every gateway answers a `PushOutcome`, never throws.

## Model

**One `PersonDataValue` per person** under the reserved `DataId` `push-devices` (the `BlockListCommands`
idiom): no table, no CDK, no PITR step; deleted with the account (`AccountDeletionCommands` removes every
person-data row). Content is a `PushPrefs`:

```json
{"enabled": true, "quietHoursStart": "22:00", "quietHoursEnd": "07:00", "timeZone": "America/Los_Angeles",
 "devices": [
   {"kind": "ios", "token": "<hex>", "environment": "production|sandbox", "selector": "<refresh selector>",
    "label": "Ken's iPhone (iPhone17,1)", "appVersion": "1.0.0 (19)", "registeredAt": 1757800000, "lastSeenAt": 1757800000},
   {"kind": "web", "endpoint": "https://web.push.apple.com/…", "p256dh": "<b64url>", "auth": "<b64url>",
    "label": "Safari on Mac", "origin": "https://acme.unitetrip.com", "registeredAt": 1757800000, "lastSeenAt": 1757800000}
 ]}
```

- `enabled` defaults to **true**: registering a device is the opt-in. Quiet hours need both ends
  (`HH:mm`, in `timeZone`, UTC when unset; may cross midnight; start inclusive, end exclusive).
- **Cap 20 devices**, oldest `lastSeenAt` evicted. iOS upsert by token (re-registration keeps
  `registeredAt`), web upsert by endpoint.
- **Validation at the door:** iOS token = 32–512 lower-case hex, environment `production|sandbox`,
  `selector` must be a REFRESH/REMEMBER row of the caller's (`getAuthToken(selector, Cached.NO)`, never
  ACCESS) — 403 otherwise. Web endpoint = absolute `https://` URL with a host (or `http://localhost` for
  the local recipe), `p256dh` = 65-byte uncompressed P-256 point, `auth` ≥ 16 bytes, both base64url. Shape
  only, never reachability.
- **Listing id** (`PushDevice.id()`): iOS = last 8 hex of the token; web = first 16 hex of SHA-256(endpoint).
  A token or endpoint never leaves the server; removal accepts the id or (iOS) the full token.
- **Per-channel choice** lives on the chat membership row: `ChatNotifyPref.pushMode` = `OFF | MENTIONS |
  ALL`, absent → `MENTIONS` (the getter normalises too, for Java-serialized sessions written before the
  field). The OLD `push` field (`DeliveryMode`) is read-only-legacy: `defaults()`/`withEmail` wrote
  `"push":"OFF"` into every row while push was inert, so it is never interpreted.

### Pruning — who removes a device

| Trigger | Path |
|---|---|
| Sign-out / devices UI revoke | `TokenService.revokeFamily` → `PushDevices.removeBySelector` (the phone's refresh selector) |
| Password change, credential deletion, account deletion | `TokenService.revokeAllFor` → `removeAll` (every kind) |
| Site logout hook / Disable button | `POST /api/push/webpush/unsubscribe` → `removeWebByEndpoint` |
| Remove button (site or app) | `DELETE /api/push/devices/{id}` → `remove` |
| Push service says gone (`DROP_DEVICE`) | `PushSender.deliver` → `prune` (system actor) |
| Row full | `upsert` evicts the least recently seen |

Every write is a read-merge-write under `pushDeviceLockKey(personId)` (`push:v1:devlock:{id}`, 10 s TTL,
up to 5 × 50 ms waits, then proceed with a WARN). Audit: `PUSH_DEVICE_REGISTER` / `PUSH_DEVICE_REMOVE`,
target = the person, actor = the caller when known, else `System`.

## Sending (`PushSender`)

`sendAlert(personId, payload, dedupeKey)`, in order:

1. `push.enabled` (else `skipped=disabled`) → the person's `enabled` (`off`) → devices (`no-devices`; web
   devices only when `push.web.enabled`).
2. **Dedupe claim** `tryAcquireLock(chatNotifySentKey("{eventKey}|{personId}|PUSH"), 24 h)` — the same
   marker shape and TTL as chat email, taken BEFORE any send so a retry or a second task cannot alert the
   same person twice for one event. Event keys: the chat message id; `reg:{tripId}:{personId}`;
   `pay:{paymentId}`; `sup:{msgId}`. A null key (the test endpoint) skips the claim.
3. **Quiet hours** → the alert stays an alert with `interruption-level: passive` and no `sound`.
4. **Badge** = unread chats (`ChatCommands.myChats`), computed once per recipient per alert, iOS only; a
   failing count sends without a badge.
5. One gateway send per device; `DROP_DEVICE` prunes the device under the lock. After any delivered alert
   the silent marker is claimed too (the alert already woke the app).

`sendSilent(personId)`: one `{"aps":{"content-available":1}}` per iOS device (never web — a service worker
that shows nothing loses its permission), coalesced by `tryAcquireLock(pushSilentKey(personId),
push.silent.intervalMinutes)` — the marker's TTL IS the schedule, no scheduler thread. `0` turns it off.

`NoopCacheClient` grants every lock (documented, not fought): production has Valkey and local mode
`InMemoryCacheClient`, the two modes that matter.

## Events

| Event | Where | Recipients | Kind |
|---|---|---|---|
| Mention / `@all` | `ChatNotifications.mentionsFor` | named people whose `pushMode ≠ OFF` | `chat.mention` |
| Reply to a quoted author | `replyFor` | the quoted author | `chat.reply` |
| Photo comment / photo mention | `photoMentionsFor` | uploader / named; preference read from the TRIP channel | `chat.photoComment` / `chat.mention` |
| Every message | `everyMessageFor` (ALL_MESSAGES) | explicit JOINED rows with `pushMode == ALL`, minus author and anyone already alerted | `chat.message` |
| Silent refresh | after every ALL_MESSAGES on a trip channel | `everyoneIn(trip)` minus author minus alerted | background |
| Registration approved | `RegistrationCommands.approvePending` | the person + family managers (`approvalRecipientIds`) | `registration.approved` |
| Payment recorded | `PaymentCommands.recordNow`, `!isSandbox` only | the payer | `payment.recorded` |
| Support request | `SupportChatCommands.notifyAdmins` (rides `push.enabled` only, not the mail switch) | joined admins minus requester | `support.request` |
| Test | `POST /api/push/test` | the caller | `test` |

**Recipient policy split (2026-09-13):** `ChatNotifications.eligible(channelId, author, person)` answers
membership only (a real person, not the author, not LEFT/REMOVED; an implicit member is a candidate).
Notifications carry route-neutral candidates; each route applies its own preference at delivery — the
email route `mentionEmail` + a usable address (`EmailChatNotifier.deliver`), the push route `pushMode` +
devices. A person with a phone and no mailbox is still told, and vice versa.

`ChatNotification.imageUrl` carries the display rendition of a media message's first attachment (or the
commented photo's full key) for the rich push — null for a content-free (short-retention) channel, like the
snippet. Copy: snippet; `Open the chat to read it` when content-free; `📷 Photo` for a bare media message.

## Wire contract

REST (`api/PushResource`, `@Path("push")`, `@TripApi`, media type `application/vnd.trip.push.v1+json`,
`X-Trip-Api: 1` on cookie mutations, bearer callers exempt):

| Route | Body → Response |
|---|---|
| `PUT /api/push/devices` | `{token, environment, selector, label, appVersion}` → `{devices:[…]}`; 400 bad hex/env; 403 selector not the caller's |
| `DELETE /api/push/devices/{id}` | id = listing id or full iOS token → `{removed:true}` (idempotent) |
| `PUT /api/push/webpush` | `{endpoint, keys:{p256dh, auth}, label}` → `{devices:[…]}` (origin = request host) |
| `POST /api/push/webpush/unsubscribe` | `{endpoint}` → `{removed:true}` (idempotent) |
| `GET /api/push/webpush/key` | → `{publicKey}` (VAPID, base64url); 404 until configured (local mode: a per-JVM throwaway pair) |
| `GET /api/push/prefs` | → `{enabled, quietHoursStart, quietHoursEnd, timeZone, devices:[{kind, id, label, environment (ios) \| origin (web), registeredAt, lastSeenAt}]}` — unset strings are `""` |
| `PUT /api/push/prefs` | `{enabled, quietHoursStart, quietHoursEnd, timeZone}` absent = unchanged, `""` clears; 400 `VALIDATION_FAILED` for a bad time/zone; answers the GET shape |
| `POST /api/push/test` | → `{sent, outcomes:[{kind,label,outcome}], skipped?}` |
| `GET/PUT /api/chat/channels/{id}/prefs` | gains `pushMode`: `OFF \| MENTIONS \| ALL` (PUT absent = unchanged; 400 for anything else) |

JSF bean `#{push}` (`PushCommands`): `isEnabledFor(id)`, `quietStart(id)`, `quietEnd(id)`, `quietZone(id)`
(`HH:mm` / zone id or `""`), `zoneChoices()` (`America/*`, `Europe/*`, `Pacific/*`, `Australia/*`,
`Asia/*`, `UTC`, sorted), `savePrefsFromUi(me, enabled, start, end, zone)` (growls only on refusal — the
page growls success), `removeDeviceFromUi(me, id)`. `ChatCommands.saveChatPrefsFromUi(mentionEmail,
dailyDigest, pushMode, bgColor, bgImage)` and `pushModeForTrip(tripId, personId)` (enum name) serve the
chat prefs dialog. No overloads: EL picks overloads by runtime type.

APNs alert:

```json
{"aps": {"alert": {"title": "Autumn Pilgrimage", "subtitle": "Maria mentioned you", "body": "…"},
         "badge": 2, "sound": "default", "thread-id": "trip:<id>", "interruption-level": "active",
         "mutable-content": 1},
 "kind": "chat.mention", "link": "unitetrip://chat/<tripId>", "url": "https://…/trip/chat.jsf?trip=…",
 "channelId": "trip:<id>", "messageId": "…", "image": "https://files…/…-small.jpg"}
```

`image` + `mutable-content` only with an image; quiet hours drop `sound` and set `passive`. Background push:
`{"aps":{"content-available":1}}`, `apns-push-type: background`, priority 5, `apns-expiration: 0`,
`apns-collapse-id: refresh`. Alerts: priority 10, expiration now + 24 h. Kinds: `chat.mention`,
`chat.reply`, `chat.photoComment`, `chat.announcement`, `chat.message`, `registration.approved`,
`payment.recorded`, `support.request`, `test`. Deep links: `unitetrip://chat/<tripId>`,
`unitetrip://trip/<id>/photos`, `unitetrip://trip/<id>/payments` (approval and payment both);
`support.request` has no `link`.

Web push (JSON inside aes128gcm): `{title, body, icon, image?, url, tag, kind}` — `body` = `subtitle: body`
(a web notification has no subtitle line), `icon` = the org's logo (`BrandCommands.lookOf`) else
`/resources/images/UniteTripLogo.png` on the trip's site, `url` absolute via `SiteUrls.baseUrlForTrip`,
`tag` = the thread id. Headers: `Content-Encoding: aes128gcm`, `TTL: 86400`, `Urgency: normal` (`low`
when passive), `Topic` = first 32 chars of base64url(SHA-256(tag)) — the header allows 32 URL-safe chars
and a channel id is neither.

## Gateways and outcomes

`PushGateway.send(device, payload)` → `PushOutcome`: `DELIVERED`, `DROP_DEVICE` (prune), `RETRY`
(transient), `AUTH` (our credentials), `FAILED`. Both clients sit on `PushTransport` (one synchronous
`HttpRequest → HttpResponse<String>`; the JDK HTTP/2 client in production, scripted in tests).

| | APNs (`ApnsClient`) | Web Push (`WebPushClient`) |
|---|---|---|
| Auth | `authorization: bearer <ES256 JWT {iss: team, iat}>`, `kid` header; reused 50 min | `Authorization: vapid t=<JWT {aud: origin, exp: +12h, sub}>,k=<public point>`; reused 11 h |
| Host | `api.push.apple.com` / `api.sandbox.push.apple.com` by device `environment` | the subscription's endpoint |
| DELIVERED | 200 | 200 / 201 / 202 |
| DROP_DEVICE | 410; 400 `BadDeviceToken` / `DeviceTokenNotForTopic` | 404 / 410 |
| AUTH | 403 → re-mint + one retry, then AUTH | 401 / 403 → re-mint + one retry, then AUTH |
| RETRY | 429 / 5xx / IOException → one retry after 1 s, then RETRY | 429 / 5xx / IOException |
| FAILED | any other 4xx | 400 / 413 / any other 4xx; unencryptable subscription |

`Es256Jwt` needs no library: `Signature.getInstance("SHA256withECDSAinP1363Format")` yields the JOSE
`r‖s` directly. `EcKeys` handles PEM (PKCS#8), raw scalar and uncompressed point (SunEC is in `java.base`
since JDK 22 — no jlink module).

### Web push crypto notes (RFC 8291 / 8188, `WebPushCrypto`)

ephemeral P-256 pair → ECDH with the subscription's `p256dh` → `IKM = HKDF(auth, ecdh, "WebPush: info" ‖
0x00 ‖ ua_public ‖ as_public, 32)` → `CEK = HKDF(salt, IKM, "Content-Encoding: aes128gcm" ‖ 0x00, 16)`,
`NONCE = HKDF(salt, IKM, "Content-Encoding: nonce" ‖ 0x00, 12)` → AES-128-GCM over `plaintext ‖ 0x02` (one
record, no extra padding) → body `salt(16) ‖ rs=4096(4) ‖ idlen=65(1) ‖ as_public(65) ‖ ciphertext‖tag`.
HKDF is hand-rolled over `HmacSHA256` (one block suffices). `WebPushClientTest` reproduces the RFC 8291
Appendix A vector byte for byte with the appendix's keys and salt injected, and decrypts a random-key
message with an independent receiver written from the RFCs.

## Secrets and runtime

`PushSecrets` (the `ProcessorSecrets` shape): sysprop `trip.push.secret` / env `TRIP_PUSH_SECRET` names the
Secrets Manager secret `trip/push` = `{"apns":{"keyId","teamId","key":"<PEM>"},"vapid":{"publicKey",
"privateKey","subject"}}` (VAPID `publicKey` = base64url of the 65-byte point, `privateKey` = base64url of
the 32-byte scalar). Read-only from the app, cached 60 s (a rotation needs no restart), a broken half is
logged and absent without taking the other down. Written by `medjugorje/scripts/push-secret.sh`.

- **Local mode** mints a throwaway VAPID pair once per JVM (`isEphemeral()`), so `GET /api/push/webpush/key`
  answers and a browser can `subscribe()` locally; `PushRuntime` then hands out the recording
  `LoggingPushGateway` for EVERY kind, so no local send can reach a real push service.
- **Dev opt-in for real keys**: sysprop-only `-Dtrip.push.secretFile=<path to the same JSON>` (like
  `trip.cache.local.useConfigured`) — real APNs sandbox / Safari pushes from a laptop.
- **Production without a secret**: a `LoggingPushGateway` answering `FAILED`, one WARN per JVM.
- `PushRuntime.setGateway(kind, gateway)` is the test seam; `PushRuntime.recorder()` is the local-mode
  recorder (`sent()` holds the last 200 sends; bodies are never logged, only kind and label).

Settings (`KnownSettings`, section "Push notifications"): `push.enabled` (false), `push.apns.topic`
(`org.paulsens.unitetrip`), `push.silent.intervalMinutes` (15, 0–240, 0 = off), `push.web.enabled` (true).

## Local recipe

```sh
# container up (medjugorje/scripts/local-backend.sh), API tokens enabled
# 1. a web device (any https endpoint; nothing is ever sent to it locally)
curl -s -X PUT localhost:8080/api/push/webpush -H 'X-Trip-Api: 1' -H 'Content-Type: application/json' \
     -b cookies -d '{"endpoint":"https://example.invalid/s/1","keys":{"p256dh":"BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4","auth":"BTBZMqHH6r4Tts7J_aSIgg"},"label":"curl"}'
# 2. flip push.enabled on the admin Settings page, mention user2 from admin in a trip chat
# 3. the container log shows: push[DELIVERED] chat.mention -> web 'curl' (…); a re-send is deduped
# 4. POST /api/push/test answers {sent, outcomes}; GET/PUT /api/chat/channels/trip:<id>/prefs round-trips pushMode
```

Unit tests: `Push*Test`, `EcKeysTest`, `Es256JwtTest`, `ApnsClientTest`, `WebPushClientTest`,
`GatewayRuntimeTest`, `ApprovalRecipientIdsTest`, plus the push cases in `ChatMentionDispatchTest`,
`ChatNotifierTest`, `ChatNotifyPrefTest`, `ChatMembershipAndPrefsTest`, `ChatResourceTest`,
`TokenServiceTest`, `RegistrationStatusChangeTest`, `PaymentCommandsTest`, `SupportChatCommandsTest`.
