# Chat guests: family access + invite links

Non-trip-members can participate in a trip's chat two ways (2026-08-12):

1. **Family**: anyone in a family (`Person.familyId` → `Family.memberIds`) with someone on the trip roster
   is a full chat member — not just managers, as before. Implemented in `ChatCommands.isTripMember`; the
   legacy `managedUsers` loop remains for persons not yet migrated into a family.
2. **Invite link / QR code**: anyone who can post in the chat (plus chatMgr/chatAdmin) can mint a multi-use
   invite link from the chat page's Invite dialog, which shows it as a QR code (`p:barcode`, okapibarcode
   dependency) and a copyable URL. Redeeming requires an account; the landing page
   (`/trip/chatInvite.jsf?trip={id}&token={selector}.{validator}`) rides the normal `afterLoginURL` /
   `?to=` login round-trip, then writes a guest membership row and forwards into the chat.

## REST (2026-09-14, for the UniteTrip app)

Both on `ChatResource`, the channel path, so they share `tripIdOf`/CSRF/auth with every other channel call:

- `GET /api/chat/channels/trip:{tripId}/invite` → `{"canInvite": bool, "enabled": bool}`. Read-only by
  design: a phone asks this when a thread opens, and opening a chat must never write an invite row (the
  website mints on the Invite button, never at render, for the same reason).
- `POST /api/chat/channels/trip:{tripId}/invite` → `{"url": "..."}` from `ChatCommands.createInvite`
  (made public for this; the JSF path's `createInviteFromUi` keeps its per-session reuse, which a
  sessionless REST caller cannot share — the app caches the URL for the life of its thread screen instead).
  403 `FORBIDDEN` when `canInvite` is false, refused BEFORE the bean is asked so it costs no row; 409
  `CONFLICT` when the person may invite but the chat refuses right now (archived, or
  `chat.invites.maxPerChannel` reached). Cookie sessions send `X-Trip-Api: 1`; bearer tokens are exempt.
  Audited as `CHAT_INVITE` like the website's mint.

## Authorization model — read before touching

- `ChatCommands.canParticipate(tripId, me)` is THE definition of chat access:
  `isTripMember || guestJoined(row)`. **Only a guest-marked JOINED row grants access** — a plain JOINED
  row (rejoin, roster backfill) never does, so no path that materialises ordinary rows can become a back
  door.
- `readDenial` reads the membership row FIRST and refuses LEFT/REMOVED **before** any grant: an admin
  REMOVE ousts members and guests alike, and an invite cannot bypass it (`redeemInvite` refuses a REMOVED
  caller). `postDenial` accepts `isTripMember || guestJoined(row)`.
- **Every redemption and every first post leaves a JOINED row** (`ChatJoin`, 2026-09-20). Before that, a
  redeem by anyone who could already participate was a silent no-op: no row, no `uses` increment, no
  audit, so a family member never appeared on the roster, in `@all`, in the digest or in the mention
  list, and the link's Uses stayed 0. The row is BOOKKEEPING, not a grant — it is guest-marked only for
  someone with no standing of their own (`!isTripMember`), so the locked rule above is untouched and a
  link can never outlive the family membership or privilege it was clicked with. A participant's row
  carries `invitedVia` (shown as a "via invite" tag beside their name) and takes `joinedAt` from the
  CHANNEL's creation, the same floor `materialize` uses, so materialising it shrinks nobody's history.
  `ChatJoin.byPosting` does the same on a first post, after the message is durable and never failing the
  send; roster members are skipped, since the trip itself already lists them everywhere.
- `rejoin` refuses anyone who is neither a trip member nor an existing guest — before this, any session
  could write itself a JOINED row (harmless then, a hole once rows mean access). A LEFT guest may rejoin;
  `ChatMembership.with*` all carry `guest`/`invitedVia`, and dropping them in a new copy method would
  silently lock guests out (`ChatMembershipTest` pins every one).
- `tripForChatPage` overrides `TripCommands.getTripForUser` for chat: that method serves page-level
  visibility, so it answers null for a guest or a non-manager family member, and the wrapper fills that
  gap for anyone `canParticipate` admits. (Until 2026-09-20 the resolver also silently fell back to "any
  trip you can see" for a REFUSED `?trip=`, which showed a non-member somebody else's chat; an explicit id
  is now that trip or null everywhere, and `chat.xhtml` redirects to the profile page with
  `TripCommands.NO_TRIP_ACCESS_MESSAGE` as a growl.)
- tripTabs: the strip's outer gate is `canParticipate`, but Details/Itinerary/Contacts/To-do's are gated on
  the narrower `isTripMember` — a guest sees exactly the Chat tab.

## Storage

- `chat_invites` table: PK `channelId`, SK `selector`; attrs `validatorHash` (SHA-256 — the table never
  holds a working link; the full URL exists only in the minting session), `createdBy`, `created`,
  `expires` (epoch sec, doubles as TTL; redemption re-checks it because TTL lags), `uses` (best-effort).
  `ChatInviteDAO` is deliberately UNCACHED (rows authorize; a revoked link must die immediately).
- Guest reverse lookup: synthetic rows in `chat_members` under PK `person:{personId}`, SK = channel id —
  one partition query feeds `myChats`, keeping the no-GSI/no-scan design. These rows are NOT
  `ChatMembership` JSON; only `ChatDAO.addGuestChannel`/`listGuestChannelIds` touch them. `purgeChannel`
  orphans them harmlessly (`myChats` skips missing channels); re-clicking an invite self-heals a lost one.
- Redeem writes membership row first, reverse row second, the `uses` increment third, non-transactionally:
  losing a later write only hides the chat from that person's own list or under-counts a link, and
  re-clicking heals both. The idempotent branch is an existing JOINED row that already says they are in
  (guest-marked, or any row for a participant): reverse row only, no count, no audit.

## Knobs, audit, free behavior

- `KnownSettings`: `chat.invites.enabled` (off = no new links AND no redemptions; existing guests stay),
  `chat.invites.expiryDays` (default 30, capped at the channel's archive time),
  `chat.invites.maxPerChannel` (expired rows are pruned when counting).
- Audit: `CHAT_INVITE` (mint/revoke), `CHAT_JOIN` (redeem, incl. FAILURE for bad/expired tokens and
  refused rejoins).
- These surfaces read the trip roster unioned with explicit JOINED rows, so anyone OFF the roster needs a
  row to appear in them at all: digests (`ChatDigestSender.collectForTrip`), mention roster
  (`rosterJsonForTrip`), `@all` (`ChatNotifications.everyoneIn`), moderation (mute/remove), roster listing
  on `admin/chatSettings.jsf` (badged "guest" or "via invite"; it also lists/revokes outstanding links).
  Redeeming an invite or posting once is what creates that row for a family member, a `tripView` holder or
  a site admin — note that means an admin who posts in any trip's chat gains a row and a My Chats entry
  there, which is the same "privilege holder" rule.

## Tests

`ChatGuestAccessTest` (behavior, incl. the participant/LEFT/removed redeem cases and joining by posting),
`ChatMembershipTest` (marker survival + `withProvenance`), `ChatCommandsTest` (a `tripView` holder's first
post), `ChatInviteDAOTest` (rows), `ChatInvitePwIT` (browser end-to-end on the Summer Demo seed: mint w/ QR,
logged-out login round-trip, guest posts, guest-only Chat tab, admin badge + revoke) and
`ChatFamilyJoinPwIT` (two owned trips: a family member posting, and one redeeming, each landing on the
admin roster without a guest tag; the second also asserts Uses = 1).

## Ops

`cdk deploy TripApp` creates `chat_invites` + the task-role grant BEFORE the app deploy; enable PITR on
the table by hand afterwards (the monthly backup wildcard picks it up automatically).
