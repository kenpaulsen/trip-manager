---
paths:
  - "trip/src/main/java/org/paulsens/trip/chat/**"
  - "trip/src/main/java/org/paulsens/trip/model/chat/**"
  - "trip/src/main/java/org/paulsens/trip/moderation/**"
  - "trip/src/main/java/org/paulsens/trip/action/*Chat*.java"
  - "trip/src/main/java/org/paulsens/trip/api/*Chat*.java"
  - "trip/src/test/java/org/paulsens/trip/chat/**"
---

# Chat (`chat/`, `model/chat/`)

- `chat/` -- per-trip chat runtime: digest scheduler (Valkey-coordinated so N tasks send once), notifier
  chain, rate limiter, long-poll nudge registry. Design doc: `chat-design.md` at the workspace root. Several
  chat decisions deliberately reverse the obvious approach -- read the design doc before changing behavior.
  Non-members participate via whole-family access or invite links (QR code + `chat_invites` table; guests
  are explicit guest-marked membership rows) -- read `docs/chat-invites.md` before touching chat
  authorization (`canParticipate`/`readDenial`/`rejoin`) or `ChatMembership` copy methods.
  Channels are no longer only per-trip: each chat photo gets a `photo:{s3Key}` channel for its comment
  thread and image reactions, which roll up (SUM) into the carrying message's chips -- read
  `docs/photo-comments.md` before touching photo threads, the summary fold, or `purgeChannel`.
  Notification recipients are route-neutral candidates (`ChatNotifications.eligible`, membership only);
  each route applies its own preference -- email `mentionEmail`, push `ChatNotifyPref.pushMode`.
  Bodies carry a small formatting markup (`**bold**`, `*italic*`, `__underline__`, `~~strike~~`, `- ` and
  `1. ` list lines), parsed by `model/chat/ChatMarkup` and rendered per surface (`chat/ChatBodyHtml` for
  mail, `ChatMarkup.plain` for push and reply snippets, `chatFormat.js` on the site, `ChatMarkup.swift` in
  the app) -- read `docs/chat-formatting.md` before touching any body renderer; the three parsers share one
  conformance table.
- `ChatPhotos.getChatPhotos()` is ONE static instance on purpose -- never give it ChatCommands'
  FacesContext/application-map lookup: the upload servlet has no FacesContext and the JSF send does, so a
  context-sensitive lookup splits the staging registry across two instances and every photo "is no longer
  available" at send.
