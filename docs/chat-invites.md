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

## Authorization model — read before touching

- `ChatCommands.canParticipate(tripId, me)` is THE definition of chat access:
  `isTripMember || guestJoined(row)`. **Only a guest-marked JOINED row grants access** — a plain JOINED
  row (rejoin, roster backfill) never does, so no path that materialises ordinary rows can become a back
  door.
- `readDenial` reads the membership row FIRST and refuses LEFT/REMOVED **before** any grant: an admin
  REMOVE ousts members and guests alike, and an invite cannot bypass it (`redeemInvite` refuses a REMOVED
  caller). `postDenial` accepts `isTripMember || guestJoined(row)`.
- `rejoin` refuses anyone who is neither a trip member nor an existing guest — before this, any session
  could write itself a JOINED row (harmless then, a hole once rows mean access). A LEFT guest may rejoin;
  `ChatMembership.with*` all carry `guest`/`invitedVia`, and dropping them in a new copy method would
  silently lock guests out (`ChatMembershipTest` pins every one).
- `tripForChatPage` overrides `TripCommands.getTripForUser` for chat: that method serves page-level
  visibility and silently falls back to "any trip you can see", which showed a guest a DIFFERENT trip than
  the invite URL named. For chat participants the requested trip wins.
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
- Redeem writes membership row first, reverse row second, non-transactionally: losing the second write
  only hides the chat from the guest's own list.

## Knobs, audit, free behavior

- `KnownSettings`: `chat.invites.enabled` (off = no new links AND no redemptions; existing guests stay),
  `chat.invites.expiryDays` (default 30, capped at the channel's archive time),
  `chat.invites.maxPerChannel` (expired rows are pruned when counting).
- Audit: `CHAT_INVITE` (mint/revoke), `CHAT_JOIN` (redeem, incl. FAILURE for bad/expired tokens and
  refused rejoins).
- Guests ride along free wherever explicit membership rows are read: digests
  (`ChatDigestSender.collectForTrip`), mention roster (`rosterJsonForTrip`), `@all`
  (`ChatNotifications.everyoneIn`), moderation (mute/remove), roster listing (badged "guest" on
  `admin/chatSettings.jsf`, which also lists/revokes outstanding links).

## Tests

`ChatGuestAccessTest` (behavior), `ChatMembershipTest` (marker survival), `ChatInviteDAOTest` (rows),
`ChatInvitePwIT` (browser end-to-end on Fake2: mint w/ QR, logged-out login round-trip, guest posts,
guest-only Chat tab, admin badge + revoke).

## Ops

`cdk deploy TripApp` creates `chat_invites` + the task-role grant BEFORE the app deploy; enable PITR on
the table by hand afterwards (the monthly backup wildcard picks it up automatically).
