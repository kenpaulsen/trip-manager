# Photo comments and reactions

Per-photo comment threads and emoji reactions on chat photos, shown in a panel beside the image wherever a
photo is viewed full-size: the trip album (`trip/tripMedia.xhtml`), the chat message lightbox
(`trip/chat.xhtml`), and the public landing-page galleria (`trip/index.xhtml`, PhotoAlbums ptype). Photos
with at least one comment show a `💬 N` badge before the viewer ever opens. Built 2026-08-10.

## The shape: a photo's thread is a chat channel

A photo's thread is an ordinary chat channel whose id is **`photo:{s3Key}`** (`ChatChannel.Kind.PHOTO`,
`ChatChannel.Id.forPhoto`). The s3Key is the one identity a `ChatAttachment` and its `MediaItem` album row
share — there is no other join — and it embeds the trip (`chat/{tripId}/…`), which is what authorization
scopes on. Reusing the channel machinery means **no new DynamoDB tables and no CDK change**: the four chat
tables, `ChatDAO`, `ChatVisibility`, reactions, version counters and caches all work on the opaque channel
id. Existing endpoints are structurally blind to photo channels (nothing scans `chat_channels`;
`myChats`/digest resolve channels per trip; `ChatResource.tripIdOf` 400s non-`trip:` ids).

- **Comments** are ordinary `ChatMessage`s in the photo channel (TEXT only, no attachments).
- **Image reactions** are `ChatReaction` rows targeting the synthetic root id
  `PhotoChatMeta.PHOTO_ROOT` = `"0000000000000"` — 13 chars, sorts below every real millis id, no `#`, so
  the reaction sort-key/range helpers work unchanged.
- **Retention**: photo-channel settings are built with null retention fields — comments live as long as the
  photo (album semantics), and are **never** frozen by trip archive. `allowMedia=false` is stored via the
  hand-written builder so the `v1ReservedMedia` fingerprint (false/0/0) cannot "upgrade" it back on.
- **Parents**: the channel row stores `parentChannelId`/`parentMsgId` — the trip-chat message that carried
  the photo. Filled eagerly at send (`ChatCommands.send` → `PhotoChatCommands.ensureChannelsForMessage`);
  for photos that predate the feature, a lazy one-time newest-first scan of the trip channel resolves it at
  first comment/reaction (`resolveParent`), persisting null when the message is gone. Null parent = nothing
  to roll up into.

## Authorization: comments follow the photo (user decisions 2026-08-09)

| Operation | Anonymous / signed-in non-member | Member / tripView / site admin | Author | chatMgr(trip) / tripMgr(trip) / chatAdmin / mediaAdmin |
|---|---|---|---|---|
| Read thread + reaction counts, batch meta | ✔ iff a media row exists AND not `hidden` | ✔ always (incl. hidden) | — | ✔ |
| Reactor identities (`who`) | ✖ counts only | ✔ | — | ✔ |
| Comment / react | signed-in ✔ (login is the only bar); anonymous ✖ 401 | ✔ | — | ✔ |
| Delete a comment (tombstone) | ✖ | ✖ | ✔ own | ✔ |

`hidden` is a listing flag everywhere else, but here it IS the read gate for non-members: the photo bytes
are public-by-URL on the CDN regardless, so the thread follows the photo's page visibility. NOT_FOUND is
deliberately indistinguishable from hidden for anonymous callers. Trip-channel mutes do not gate photo
posts (future hardening if needed). Enforcement lives in `action/PhotoChatCommands` (`readDenialFor`,
`canSeeIdentities`, `canModerate`); the REST resource only shapes statuses.

## Reaction roll-up (SUM semantics, user decision 2026-08-09)

A reaction on a photo also counts toward the chat message that carried it. **SUM**, not union: the same
person reacting 👍 on the message AND on two of its photos shows 👍 3 on the message chip. The message
chip's *mine* highlight and click-toggle stay bound to the DIRECT message reaction only.

Mechanics (`ChatDAO`):
- Write side: `PhotoChatCommands.react` → reaction row in the photo channel → `invalidatePhotoMeta(key)`
  FIRST, then `rollupToParent(channel)` = drop the parent message's `rsum` field → bump `rver:trip` →
  nudge the trip channel. The order matters: a parent rebuild reads pmeta, which must already be dropped.
- Read side: `summarize(...)` folds each photo's root reactions into the carrying message's summary
  (`ChatReactionSummary.foldCounts`) — counts go into `overflowCount`, `lastReactedAt` max-merges, and the
  emoji key is **seeded into `byEmoji` with an empty list** because the deployed chip JS renders only
  emojis present there. Person-id lists are never touched, so `mine()` and the who-tooltip answer for the
  message alone. The same fold runs in the window refetch (`summariesForWindow` → `foldWindow`, which reads
  the window's message rows in bounded pages). Because `cacheSummaries` stores the folded result, the
  existing drop-field/bump/nudge contract delivers photo reactions to live chat clients with no protocol
  change.
- Comments do NOT roll up — only reactions.

**Two long-poll fixes this exposed**, both of which fix real-time reactions/edits/tombstones for plain trip
chat as well:

1. `ChatResource.awaitNudge`: a woken poll whose final read had no new messages returned an empty page with
   ZEROED version counters, so a reaction on a quiet channel never reached other parked clients — the exact
   failure chat-design.md warns about ("an empty page must still carry the version"). `emptyPageAt` now
   carries the woken read's `reactionsVersion`/`mutationsVersion`.
2. **The client now sends the counters it holds** (`&rver=&mver=`), and a read whose counters have moved past
   them answers at once instead of parking. Without this, a reaction landing in the ~1s gap between two polls
   published its nudge while nobody was parked, and the next read — already holding the newer counter — had
   no message to return, so the client sat parked for the full 25s timeout. Measured on the roll-up webtest:
   **25.8s before, 0.7s after**. Photo reactions made this routine, because the person reacting is looking
   straight at the chip that should change.

   ABSENT and zero are different answers there, hence the boxed `Long` params: absent is an older client and
   keeps the old behaviour, while a sent zero is a current client saying "I have seen no reactions here" —
   the ordinary state of a channel whose FIRST reaction is the one being waited for. A zero from the SERVER
   still means "not reported" and never counts as a change, so a client with nothing to catch up to cannot be
   spun into a hot loop.

## Per-photo meta (badges)

`PhotoChatMeta` = `{commentCount, rootReactions}` per s3Key, cached in the Valkey hash
`CacheKeys.chatPhotoMetaKey()` (`chat:…:pmeta`, field = s3Key), rsum-like: dropped on every write to the
photo's thread, rebuilt on read (`ChatDAO.photoMeta`, batch). `commentCount` counts non-tombstoned rows.
The batch-meta endpoint answers up to 200 keys; keys the caller may not read are simply absent.

## REST surface — `api/PhotoChatResource`, `/api/photo-chat`

Deliberately **not** `@TripApi` (the auth filter is name-bound), so GETs serve anonymous readers; mutations
call `personId()` and answer 401 JSON themselves. CSRF sentinel `X-Trip-Api` on mutations. Media type
`application/vnd.trip.photochat.v1+json` (+ plain JSON), `Vary: Accept`. **The s3Key travels as a query
parameter or body field, never a path segment** — keys contain `/` and Tomcat rejects `%2F` in paths.

| Endpoint | Auth | Notes |
|---|---|---|
| `POST meta` `{keys:[…]}` | open | badge batch → `{photos:{key:{commentCount, reactions, lastReactedAt, myReacted}}}` (a read; POST only for URL length) |
| `GET thread?key=&before=&limit=` | open | messages + displayNames + cursor/hasMore + rver/mver + `photo` (root reactions; `who` names member-only) |
| `GET emoji` | open | the shared `ChatEmoji` palette |
| `POST comments` `{key, body, clientMessageId?}` | session+CSRF | 400/401/403 `PHOTO_COMMENTS_DISABLED`/404/429+`Retry-After` |
| `DELETE comments/{msgId}?key=` | session+CSRF | tombstone; author or moderation |
| `PUT/DELETE reactions/{emoji}?key=` | session+CSRF | idempotent (photo, person, emoji); returns fresh counts |
| `POST login-return` `{target}` | open+CSRF | stashes `Sessions.AFTER_LOGIN_URL` after `LocalRedirect.sanitizeLocalTarget` (same-site path only) |
| `GET mention-search?q=` | session | all-users typeahead: ≥2 chars, ≤8 results, 30/min brake, masked-email disambiguation |

## Mentions (user requirement 2026-08-09)

The stored token is always `@{personId}` (`ChatMentions`), rendered as a name at display time. The
*autocomplete scope* depends on the surface: the chat lightbox hands the viewer the trip roster
(`ChatCommands.rosterJsonForTrip` labels); the album and landing surfaces use the all-users
`mention-search` endpoint. Guardrails on the global scope: signed-in only + 2-char minimum + 8-result cap +
per-person rate brake (bulk directory enumeration), and colliding display names are disambiguated with
MASKED addresses only (`j•••h@e…`, `PhotoChatCommands.maskedEmail`) — the full address never leaves the
server, and the picked label is swapped for the id token at send anyway.

**Mention email: the sender-trust gate.** A mention mails the mentioned person only when the COMMENTER has
joined at least one trip (`isKnownTraveler` = appears in some `Trip.people` list; mere account registration
does not count, because registration is open to anyone). An untrusted sender's mention still renders
highlighted — it just sends nothing. Recipient side honors the `mentionEmail` pref on the photo's TRIP
channel when a row exists (default send). Delivery rides the existing chat-mail machinery
(`ChatNotifications.photoMentionsFor` → `EmailChatNotifier` with the `photo-mention` template and a
`tripMedia.jsf?…&photo=` deep link) behind the same `chat.mail.enabled` master switch. `@all` is inert in
photo comments. No digest for photo threads.

## Frontend

- **`webapp/resources/trip-js/photoViewer.js`** (+ `resources/css/photoViewer.css`), the shared
  `TripPhotoViewer` — one lightbox for all three surfaces (it replaced the two duplicated inline copies in
  chat.xhtml and tripMedia.xhtml). Pages call `init({contextPath, signedIn, meId, returnUrl, badgeRefresh,
  mentionRoster?})` and `open(photos, startAt)` with `[{s3Key, viewUrl, fullUrl, title}]`. Everything is
  `textContent`/`createTextNode` — this DOM bypasses JSF escaping.
- **Reactions live in the IMAGE column** (`.pv-reactions`, under the title, above the meta row), not in the
  comments panel: the panel collapses, and while the row lived inside it, hiding comments took away the only
  way to react to a photo. The row is chips for whatever has a count, then up to `QUICK_PICKS` (3) one-click
  palette emoji, then a labelled `+ React` / `+ More` button opening the full picker. The label matters — as
  a bare 28px "+" this was read as decoration and people did not find that photos could be reacted to at all.
  The palette is fetched once per viewer open so the quick picks are there on first paint.
- Panel: comment list (server-resolved names; posting reloads the thread rather than echoing locally, or
  authors render as raw ids on a first comment), composer with the `@`-typeahead, delete for own comments.
  Hide toggle persists in localStorage (`tripPhotoComments`); layout is the flex-wrap idiom, so the panel
  wraps under the image on phones with no media queries.
- Badges: one `POST meta` batch per page view; album grid top-right, chat photo block top-left (totals
  across ALL of the message's photos), galleria slide top-right.
- Multi-photo chat messages render a tile GRID (2 side-by-side; 3 = wide hero over two squares; 4+ = 2x2,
  the 4th tile carrying a `+N` scrim when photos were cut — the count includes the dimmed tile). Tile *i*
  opens the viewer at photo *i*. The viewer itself shows a clickable thumbnail filmstrip (`.pv-strip`)
  whenever it holds more than one photo — built once per open, on every surface; thumb clicks ride the same
  `showAt` path as the arrows, so the comment-thread debounce still coalesces rapid navigation.
- Anonymous flow: the composer area shows "Log in to comment"; a plain-DOM modal offers the round-trip —
  `POST login-return` with `returnUrl(key)` (a `?photo=` deep link that auto-opens the viewer), then the
  login page. Any mutation 401 (expired session) reuses the same modal. chat.xhtml's old 401 hard-bounce
  now stashes the return URL the same way.
- `onStatus` contract in the viewer's `http()` helper: it fires ONLY for failures — unlike chat.xhtml's
  `httpJson`, where a handler must return false for statuses it does not consume.

## Delete cascade

`ChatLifecycleListener` registers `MediaEvents.onPrefix("chat/", PhotoChatCommands::onMediaChange)`: any
REMOVED media event purges the photo's whole thread (`purgePhotoThread` → invalidate pmeta → roll up to the
parent so folded counts leave the chip → `ChatDAO.purgeChannel` = rows in all four tables + every cache
key). That covers both the message-removal cascade and the admin media delete, since both fire the event;
`ChatPhotos.deleteEverywhere` also purges per attachment key directly (belt-and-braces for a photo whose
media row was never recorded). Purging is idempotent.

## Settings

`chat.photoComments.enabled` (default true — refusing new posts, keeping existing threads) and
`chat.photoComments.maxChars` (default 1000), in `KnownSettings` under "Photo comments". Palette and rate
limits reuse the chat settings; the photo-channel burst limiter never auto-mutes (a 429 is the whole
remedy — photo posting has no membership row to mute).

## Tests

Unit (all TestNG): `PhotoChatModelTest` (id shape, PHOTO_ROOT, foldCounts SUM invariants, wire compat),
`ChatDAOPhotoTest` (pmeta, fold incl. window path, purge, lastActivity exclusion), `PhotoChatCommandsTest`
(authorization matrix, parents, mentions incl. sender-trust email via a captured notifier, masked labels),
`PhotoChatResourceTest` (status mapping, login-return validation, mention-search guards),
`ChatFeedLongPollTest.aWokenEmptyPageStillCarriesTheVersionCounters` (the long-poll fix). Webtests:
`TripPhotoCommentsPwIT`, `PhotoCommentsPublicPwIT` (anonymous + login round-trip + XSS), and
`ChatPhotoCommentsPwIT` (roll-up end-to-end in the browser, badge totals, roster mentions — runs against
trip `Fake2` so it cannot photograph `ChatPhotoPwIT`'s chat).

## Notes

- Rolling deploy: an old task's `parseChannel` logs unknown-enum for `Kind.PHOTO` rows during the
  blue/green overlap; only new endpoints read those rows — harmless log noise.
- `CHAT_LAST_ACTIVITY` deliberately skips photo channels ("My Chats" reads the whole hash per request).
- v1 exclusions: no reactions on individual comments, no long-poll in the lightbox, no digest, curated
  non-`chat/` media out of scope, comment counts don't roll up (only reactions).
