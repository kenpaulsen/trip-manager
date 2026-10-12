# API contract (Trip Manager REST, `/api/*`)

Canonical copy, moved here from `UniteTrip/docs/API.md` on 2026-10-09 so a server change can update the
contract in the same commit. Both apps (`UniteTrip` iOS, `UniteTripAndroid`) are written against this file;
keep the sections and sample shapes when editing.


Verified against `trip/trip/src/main/java/org/paulsens/trip/api` on 2026-09-12. Versioning is by Accept
media type (`application/vnd.trip.<area>.v1+json`); errors are `{"error": CODE, "message": text}`.
JSON is camelCase; absent means redacted/unknown, never null. Dates: instants `2026-09-12T17:31:30.025Z`,
LocalDateTime `2026-09-12T17:31:30.025` (no zone), LocalDate `2026-09-12`.

## Auth (`/api/auth`)

| Route | Body | Response |
|---|---|---|
| `POST token` | `{email, password\|code, scope: member\|admin, label}` | `{accessToken, accessExpiresIn, refreshToken, scope}`; 403 when admin scope refused; 404 when `api.token.enabled` is off |
| `POST token/refresh` | `{refreshToken}` | grant; `refreshToken` omitted = keep the presented one |
| `POST token/revoke` | `{refreshToken}` | `{revoked:true}` |
| `POST code/request` | `{email}` | `{sent:true}` (local code `424242`) |
| `GET me` | | `{person: PersonDto, siteAdmin, privileges:{privilegeAdmin, configAdmin, auditAdmin, mediaAdmin, siteDeployer}}` |
| `GET sessions` / `DELETE sessions/{selector}` | | signed-in devices |

`Authorization: Bearer <selector>:<validator>`. Bearer requests need no CSRF header. 401 ⇒ refresh once.

### Sign-in flow additions (2026-09-13)

- `POST lookup` `{email}` → `{email, known}` (token feature must be on; 404 otherwise).
- `POST register` `{email, first, last, sex, password, nickname?, middle?, cell?, birthdate? (yyyy-MM-dd), scope?, label?}`
  → the same grant as `token`. 400 `VALIDATION_FAILED` for missing fields or a password below the server's
  rule (8+ characters with a letter and a digit; the app checks the same rule first), 409 `CONFLICT` when the address
  already has an account. Joins the organization of an org host on sign-up; the office is notified.
- `GET me` also carries `defaultOrgId` (top level, nullable).
- `PUT /api/people/{id}/default-org` `{orgId}` (self only) → `{id, defaultOrgId}`; joins the organization.
- `GET /api/orgs/directory?q=` → `[{id, name, abbreviation, slug, siteUrl}]` for any signed-in person.
- `GET /api/trips?filter=open` → the host's publicly listed, joinable trips without a membership check.

## Trips

- `GET /api/trips?filter=mine&limit=[&personId=]` → `[TripDto]` (no events), filtered by request host.
- `GET /api/trips/{id}[?personId=]` → `TripDto` with `tripEvents` (subject-relative `participating`, `privNote`).
- `GET /api/trips/{id}/events[?personId=]`, `GET .../events/{eid}[?personId=]`,
  `GET .../events/{eid}/lodging[?personId=]` → `{arrival, departure, nights}`.
- `PUT /api/trips/{id}/events/participation?personId=` `{eventIds:[...]}` → `[TripEventDto]` — **replaces** the set.
- `PUT /api/trips/{id}/events/{eid}/note?personId=` `{note}` → `TripEventDto`.

**Acting as a family member (2026-10-10).** `personId` on the reads above names the SUBJECT: yourself (omit it),
or someone you manage (`Person.managedUsers`, the family switcher's reach; trip staff on the per-trip reads; a
site admin on the list), else 403 `FORBIDDEN`. `participating`, `privNote` and the lodging row are then the
subject's own, as the website shows them under "Viewing: X". Identity never changes: there is no act-as header
and no token change, the bearer stays the manager. A manager of someone on the roster may read the trip (and its
roster, events, lodging) without a seat of their own -- the website's `canSeeTrip` rule, on the API since this
date. Chat posts while acting carry `onBehalfOf` (Chat section).

`TripDto`: id, title, description, openToPublic, chatEnabled, startDate, endDate, regLimit, provider,
orgId, language, estimatedPrice, director, localGuide, facilitators, flyerUrl, nonHostedTripUrl,
nonHostedRegNumber, regOptions[{id, shortDesc, longDesc, show}], people[ids], tripEvents[].
`TripEventDto`: id, type (EVENT|FLIGHT|GROUND|LODGING), title, notes, start, end, participants[ids],
participating, privNote, details{from,to,flightNumber,duration,carrier} (backend phase).

Sample:

```json
{"id":"30692184-e927-4398-b8f9-a6e1eda1ea7b","title":"Spring Demo Trip","chatEnabled":true,
 "startDate":"2026-10-30T09:00:00","endDate":"2026-11-11T18:00:00","provider":"CFPW",
 "orgId":"5f0c2a4e-9d31-4a8e-8f34-6d2f19c7b0a1","director":"Ken Paulsen",
 "regOptions":[{"id":0,"shortDesc":"Room preference","show":true}],"people":["…"]}
```

## Registrations

`GET /api/trips/{tripId}/registrations/{personId}` → `{tripId, userId, created, status:
NOT_REGISTERED|PENDING|CONFIRMED, options{"<optId>":"answer"}}` (never 404s for self).
`PUT` writes `options` for the traveler; `status` is staff-only.

Self-registration: `POST /api/trips/{tripId}/registrations/party`
`{travelers:[{personId, options{"<optId>":"answer"}, dailyDigest?}]}` → 200
`{registered:[RegistrationDto], updated:[RegistrationDto], refused:{personId: NOT_ALLOWED|ALREADY_REGISTERED|
CANNOT_JOIN}}`. Every traveler named is filed PENDING, or CONFIRMED when already on the trip's roster
(`people`), with one party stamp; `registered[].status` echoes which. Already-registered ones get their
answers updated when the server's `reg.allowEdits` allows, and a refusal is per traveler — mixed outcomes
are a 200. Only the trip's own `regOptions[].id` keys are taken from `options`; `dailyDigest` absent means
the form's default (on). 400 for an empty party, a blank or duplicate `personId`; 404 unknown trip/person.
The server sends the office note and the registrant's receipt itself.

## People

`GET /api/people/{id}[?trip=]` → `PersonDto` (id, nickname, first, middle, last, preferredName, sex,
birthdate, cell, email, tsa, address{street,street2,city,state,zip,country}, passport{number,country,
expires,issued,placeOfBirth}, notes, managedUsers[], emergencyContactName, emergencyContactPhone,
deleted, privacy{email,cell,city,street: PRIVATE|LOGGED_IN}). `PUT` absent = unchanged, `""` = clear.
`PUT` also writes `address` (contact-gated), `passport` (travel-gated) and `sex` (`Male|Female`; anything
else is 400 `VALIDATION_FAILED`). `GET /api/people?q=` needs `peopleAdmin`.

Profile photos (all URLs absolute): `GET /api/people/{id}/photo` → `{hasPhoto, url, selectedSlot,
slots:[{slot, url}]}`; `POST /api/people/{id}/photo?slot=&cropX=&cropY=&cropW=&cropH=` with a raw
`image/*` (or `application/octet-stream`) body ≤16 MB, crop in source pixels (all four or none), `slot`
1–4 or absent for the next free one → the same shape plus `storedSlot`; 409 `CONFLICT` when all four slots
are taken, 422 `VALIDATION_FAILED` when the bytes are refused, 413 `PAYLOAD_TOO_LARGE`. `DELETE
/api/people/{id}/photo/{slot}` (404 when empty); `PUT /api/people/{id}/photo/selected {slot}` (404 when the
slot holds nothing). Yourself, someone you manage, or a site admin.

Roster: `GET /api/trips/{id}/roster` → `{tripId, people:[{id, first, last, nickname, preferredName,
photoUrl, roles:[MEMBER|DIRECTOR|FACILITATOR]}], directorIds, facilitatorIds, localGuide}` for anyone who
can read the trip (403 else). Directors and facilitators are listed even when not on `people`.

## Family (`/api/family`, Accept `application/vnd.trip.family.v1+json`)

`GET /api/family[?personId=]` → `{id, version, managerIds, memberIds, maxMembers, atLimit, canManage,
members:[{id, preferredName, first, last, manager, missingProfileFields[], deleteBlockReason?, person:
PersonDto}]}`; `id` is null while the subject has no family (`members` = the subject alone). `personId`
must be someone the caller manages (or the caller is a site admin): 403 else, 404 unknown.
`POST /api/family/members {first, last, birthdate, sex, email?, manager, forPersonId?}` → `{member, family}`.
`PUT /api/family/members/{id}/manager {manager}` → family. `DELETE /api/family/members/{id}` →
`{deleted, family}`. Writes are online-only in the app. Refusal codes on the wire: `NOT_MANAGER` (403),
`EMAIL_IN_USE`/`FAMILY_CHANGED` (409), and 422 `NAME_REQUIRED, SEX_REQUIRED, BIRTHDATE_REQUIRED,
BIRTHDATE_FUTURE, MANAGER_NEEDS_EMAIL, FAMILY_FULL, LAST_MANAGER, CANNOT_DELETE_SELF, HAS_HISTORY`; an
unparseable `sex` is 422 `VALIDATION_FAILED`.

## Orgs

`GET /api/orgs/mine[?all=true]` → `[{id, name, abbreviation, slug, siteUrl, isMember, isAdmin,
allowsSharedSites, contactEmail, branding{palette, dark, layout, themeName, logoUrl, faviconUrl,
ogImageUrl, backgroundUrl, backgroundColor, footerTitle, footerText, contactName, contactPhone, donateUrl}}]`
— the caller's memberships (`all=true` is site-admin only, 403 else). `siteUrl` is built from the host the
request came in on (`http://acme.localhost:8080` locally, `https://acme.unitetrip.com` in production) and
is null when the org has no slug; `branding.palette` is null when the org never chose a look.
`GET /api/orgs/{orgId}/site` → the same summary for one org (404 unknown). An older server answers 404 on
`mine`; the app then uses the home site only.

## Transactions, payments, chat, photo-chat, media, audit

- `TransactionDto.tripIds` (added 2026-09-14): the trip(s) a transaction is bound to (its TRANSACTION→TRIP
  binding), normally one. The app fetches the person's whole ledger once (`GET /api/transactions/people/{id}`
  without `trip`) and filters by this on the device. `POST`/`PUT /api/transactions/people/{id}?trip=` now
  write that binding too (they saved the row without one before), as the website's transaction page does.

See the plan and the backend docs; wired in the next phases. Chat: `GET /api/chat/my-channels` (`unread` is a boolean);
`/api/chat/channels/trip:{tripId}/messages?since&before&order=newest&wait≤25&limit&rver&mver`;
`POST messages` with `Content-Type: application/vnd.trip.chat.v1+json` `{body, clientMessageId, replyTo,
attachments[{key,title,hidden}], onBehalfOf?}`. `onBehalfOf` (2026-10-10) is a family manager acting as a
member: the message is still authored by the caller (membership, mute, rate limit, photos, adminship are all
theirs) and comes back with `onBehalfOfId` set; every client renders the byline "Author on behalf of Member"
from the two ids and the page's `displayNames`. Allowed only for someone the caller manages who is on the
trip's roster; else 403 `ON_BEHALF_NOT_ALLOWED` (drop the stamp and tell the sender, the text is not refused).
Edits and deletes keep the stamp; reply quotes name the author only. A page carries `displayNames` AND
`avatars`, both keyed by personId and covering the same people (authors, on-behalf members, quote authors,
mentions, reactors): `avatars` holds an absolute profile
picture URL, and someone with no picture is ABSENT rather than mapped to null or "" — a named person with no
entry is the signal to draw their initials (and to forget a picture that person has since deleted). The photo
thread (`GET /api/photo-chat/thread`) carries the same two maps. Chat photos: `POST /api/chat/channels/trip:{tripId}/photos` with a raw
`image/*` (or `application/octet-stream`) body, optional `cropX,cropY,cropW,cropH` (source pixels, all or
none) → `{key, smallKey, contentType, size, width, height, url, smallUrl}`; the keys go into a send's
`attachments`. The apps' trip Photos page adds photos the same way (2026-10-12): stage each, then ONE send
carrying every key (the channel's per-message cap, default 10), since the album (`/api/media/slots/tripChat-{tripId}`)
is filled only from chat; there is no separate album upload. A posted photo's public-site flag may be revisited
at any time by the uploader or a chat admin (2026-10-12): `PATCH /api/chat/channels/trip:{tripId}/photos?key=`
`{hidden, messageId?}` (`messageId` is only a hint; the server finds the message by key) moves BOTH copies, the
attachment in the message (the `mutationsVersion` bump makes polling clients refetch it) and the album row; 403
`FORBIDDEN` for anyone else, 404 `NOT_FOUND` for an unknown or deleted photo. For that, a trip's own members get
the whole `tripChat-` album from `GET /api/media/slots/{slot}`, hidden rows included (the website's trip media
page shows them too); other viewers still get the public filter. `GET .../invite` also answers `canAdminister`,
the chat-admin signal the apps use for moderator-only items. Refusals: 429 `RATE_LIMITED` + `Retry-After` (40 uploads / 10 min per person, shared with
the website), 403 with the send codes (`MUTED`, `FORBIDDEN`, `CHANNEL_ARCHIVED`), 400 `BAD_ATTACHMENT`
(photos off, bad crop, empty body), 413 `ATTACHMENT_TOO_LARGE` (the channel's `maxAttachmentBytes`), 422
`BAD_ATTACHMENT` (not an image). URL bases: `GET /api/media/bases` → `{chatPhotos, profilePhotos, remote}`
(absolute, end in `/`; a stored `s3Key` resolves as `chatPhotos + key`). Payments: `POST
/api/payments/quote` (answers `sandboxAllowed`), `POST /api/payments` → `{approvalUrl}`,
`POST /api/payments/{id}/complete {token}`, `POST /api/payments/{id}/cancel`.
Audit: `GET /api/audit?org&before=<ISO instant>&actor&action&outcome&text&limit` →
`{events[], searchedBackTo, complete, degraded, nextCursor}`. A `body` may carry the formatting markup (`**bold**`, `*italic*`, `__underline__`, `~~strike~~`, `- `/`1. ` list lines; the grammar is `trip/docs/chat-formatting.md`) beside `@{personId}` tokens; it is text, never HTML, and the app renders it with `ChatMarkup.swift`.

## Push notifications (`/api/push`, Accept `application/vnd.trip.push.v1+json`; 2026-09-13)

All person-level, so the app calls the home host. Device tokens are tied to the refresh-token selector
(`AuthSession.currentSelector`): every sign-out and session revocation prunes the device server-side, so the
app never unregisters explicitly.

| Route | Body → Response |
|---|---|
| `PUT devices` | iOS: `{token: hex, environment: sandbox\|production, selector, label, appVersion: "1.0.0 (19)"}`; Android (2026-10-10): `{kind: "android", token: <FCM registration token>, selector, label, appVersion}` → `{devices:[…]}`; 400 bad token/environment; 403 selector not the caller's (the app forgets its record and re-registers on the next foreground) |
| `DELETE devices/{id}` | `id` = listing id or full token → `{removed:true}` |
| `GET prefs` | `{enabled, quietHoursStart: "HH:mm"\|null, quietHoursEnd, timeZone, devices:[{kind: ios\|android\|web, id, label, environment? (ios), origin? (web), registeredAt: epochSeconds, lastSeenAt: epochSeconds}]}` |
| `PUT prefs` | `{enabled?, quietHoursStart?, quietHoursEnd?, timeZone?}` absent = unchanged, `""` clears → same as GET |
| `POST test` | → `{sent, outcomes:[{kind, label, outcome}]}` to the caller's own devices (Debug row in Settings) |
| `GET/PUT /api/chat/channels/{id}/prefs` | `{mentionEmail, dailyDigest, pushMode: OFF\|MENTIONS\|ALL}`; PUT sends only the changed fields; absent `pushMode` reads as `MENTIONS` |
| `GET /api/chat/channels/{id}/invite` | → `{canInvite, enabled}`; read-only, never mints. `enabled` is the site switch (`chat.invites.enabled`), `canInvite` this caller in this chat (anyone who can post, plus chat managers). The thread asks once when it opens and shows the Invite button only on `true` |
| `POST /api/chat/channels/{id}/invite` | → `{url}`, a fresh multi-use link (`/trip/chatInvite.jsf?trip=..&token=..` on the trip's site) every call — each one is a seat against the chat's outstanding-links cap, so the app mints once per thread screen and keeps it. 403 `FORBIDDEN` when `canInvite` is false; 409 `CONFLICT` when the chat refuses right now (archived, or at the cap); cookie sessions need the CSRF header, bearer tokens do not (2026-09-14) |

The app registers after sign-in (`enableAfterSignIn`: the permission prompt once, then
`registerForRemoteNotifications` every time) and re-sends the registration when the token, selector or app
version changes or the record is a day old.

APNs payload the app parses (`PushPayload`): `aps` (`alert{title,subtitle,body}`, `badge`, `sound`,
`thread-id`, `interruption-level`, `mutable-content`), plus top-level `kind` (`chat.mention`, `chat.reply`,
`chat.photoComment`, `chat.announcement`, `chat.message`, `registration.approved`, `payment.recorded`,
`support.request`, `test`), `link` (`unitetrip://…`, routed in-app), `url` (https fallback, opened in the
browser), `channelId`, `messageId`, and `image` (https JPEG on `files.visitqueenofpeace.com` or
`*.unitetrip.com`, only with `mutable-content: 1`; the notification service extension attaches it). A
background push is `{"aps":{"content-available":1}}` alone: the app refreshes channels and trips and sets
the icon badge to the number of chats with unread messages.

FCM (Android) is data-only: the same keys as strings (`kind`, `title`, `subtitle`, `body`, `threadId`, `link`,
`url`, `channelId`, `messageId`, `image`, `badge`, `passive`), the silent refresh being `{kind: "silent"}` alone;
the app builds the notification (a channel per kind, big picture from `image`, `setNumber(badge)`) and routes a
tap through `link`. Full shape: `docs/push-notifications.md`, "Wire contract".

Simulator recipe (no server needed): save a payload as `push.apns` with a top-level
`"Simulator Target Bundle": "org.paulsens.unitetrip"` and run `xcrun simctl push booted push.apns`.

## Errors

`NOT_AUTHENTICATED`(401) `FORBIDDEN`(403) `NOT_FOUND`(404) `BAD_REQUEST`/`VALIDATION_FAILED`(400/422)
`CONFLICT`(409) `RATE_LIMITED`(429 + Retry-After) `STORE_FAILED`/`INTERNAL`(500). Chat adds
`NOT_A_TRIP_MEMBER, LEFT_CHANNEL, REMOVED_FROM_CHANNEL, CHANNEL_ARCHIVED, CHAT_DISABLED, MUTED, SLOW_MODE,
BAD_CHANNEL, MESSAGE_EMPTY, MESSAGE_TOO_LONG, BAD_ATTACHMENT, EDIT_WINDOW_CLOSED, EDIT_DISABLED,
ON_BEHALF_NOT_ALLOWED`.

## Curl recipes (local container)

```sh
TOKEN=$(curl -s -H 'Content-Type: application/json' \
  -d '{"email":"user2","password":"user","scope":"member"}' http://localhost:8080/api/auth/token | jq -r .accessToken)
H="Authorization: Bearer $TOKEN"; T=30692184-e927-4398-b8f9-a6e1eda1ea7b
curl -s -H "$H" http://localhost:8080/api/trips | jq '.[].title'
curl -s -H "$H" http://localhost:8080/api/trips/$T/events | jq '.[] | {title, participating}'
curl -s -H "$H" -X PUT -H 'Content-Type: application/json' -d '{"eventIds":[]}' \
  http://localhost:8080/api/trips/$T/events/participation
# org host (bearer is host-independent; the trip list is host-filtered)
curl -s --resolve acme.localhost:8080:127.0.0.1 -H "$H" http://acme.localhost:8080/api/trips
# phase-3 endpoints
curl -s -H "$H" http://localhost:8080/api/trips/$T/roster | jq '.people[] | {preferredName, roles}'
curl -s -H "$H" http://localhost:8080/api/orgs/mine | jq '.[] | {name, slug, siteUrl}'
curl -s -H "$H" http://localhost:8080/api/media/bases
curl -s -H "$H" http://localhost:8080/api/family | jq '{id, canManage, members: [.members[].preferredName]}'
ME=$(curl -s -H "$H" http://localhost:8080/api/auth/me | jq -r .person.id)
curl -s -H "$H" -X POST -H 'Content-Type: image/jpeg' --data-binary @pic.jpg \
  "http://localhost:8080/api/people/$ME/photo?cropX=0&cropY=0&cropW=400&cropH=400"
curl -s -H "$H" -X POST -H 'Content-Type: image/jpeg' --data-binary @pic.jpg \
  http://localhost:8080/api/chat/channels/trip:$T/photos
curl -s -H "$H" -X POST -H 'Content-Type: application/json' \
  -d "{\"travelers\":[{\"personId\":\"$ME\",\"options\":{}}]}" \
  http://localhost:8080/api/trips/$T/registrations/party
# acting as a family member (user2 = Ken manages Lucy, who is on the Spring Demo roster)
LUCY=$(curl -s -H "$H" http://localhost:8080/api/family | jq -r '.members[]|select(.first=="Lucy").id')
curl -s -H "$H" -X POST -H 'Content-Type: application/vnd.trip.chat.v1+json' \
  -d "{\"body\":\"Lucy will be late\",\"onBehalfOf\":\"$LUCY\"}" \
  http://localhost:8080/api/chat/channels/trip:$T/messages | jq '{authorId, onBehalfOfId}'
```

## Moderation, blocking, account deletion (2026-09-13; backend `docs/moderation.md`)

Report (202 `{reported, id, response}` where `response` is "within 24 hours"; 404 when the reporter cannot
see the content; 429 + `Retry-After` after 10 reports/hour): `POST /api/chat/channels/trip:{tripId}/messages/{msgId}/report`
`{reason}`, `POST /api/photo-chat/comments/{msgId}/report?key=` `{reason}`, `POST /api/photo-chat/report?key=`
`{reason}`. Sends and edits are refused with 400 `BLOCKED_CONTENT` when the body hits the site's blocked-words list.

Blocks (`/api/me/blocks`, media type `vnd.trip.blocks.v1`): `GET` → `{personIds}`; `PUT /{personId}` (404
unknown, 400 self, 409 list full) and `DELETE /{personId}` (idempotent), both answering the whole list. The
app hides a blocked author's messages, photos and comments (`ChatRowsBuilder`, the album); the server refuses
an `@mention` of the blocker by the blocked person (403 `FORBIDDEN`).

Account (`/api/account`, `vnd.trip.account.v1`): `GET deletion-preview` → `{blocked, settleRequired,
reasons[{code, tripId, title, amountOwedCents, personName}], amountOwedCents, amountCreditCents, contactEmail,
trips[{tripId, title, status, ended}], erased[], retained[]}`; `POST delete` `{"confirm":"DELETE"}` → 200
`{deleted: true}` (every token is dead afterwards; the app signs out locally), 409 `ACCOUNT_DELETE_BLOCKED`
`{preview}` (settle-first: an open registration or a balance owed, the sole admin of an org, an unsettled
dependent), 400 wrong confirmation. Reason codes: `UPCOMING_TRIP`, `BALANCE_OWED`, `SOLE_ORG_ADMIN`,
`DEPENDENT_UNSETTLED`.
