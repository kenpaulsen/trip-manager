---
paths:
  - "trip/src/main/java/org/paulsens/trip/push/**"
  - "trip/src/main/java/org/paulsens/trip/api/PushResource.java"
  - "trip/src/test/java/org/paulsens/trip/push/**"
---

# Push notifications (`push/`)

- `push/` -- push notifications: direct APNs (HTTP/2 + ES256 token auth) and standard Web Push (RFC 8291
  aes128gcm + VAPID), all JDK, behind one `PushGateway` seam; devices in ONE reserved person-data row per
  person (`push-devices`), pruned by every credential revocation; `PushSender` gates + dedupe + quiet hours +
  badge; `PushChatNotifier` in the notifier chain, `PushNotifications` for approvals/payments/support; REST
  `api/PushResource`, bean `#{push}`. Ships dark behind `push.enabled`. Read `docs/push-notifications.md`
  before touching any of it, the `pushMode` field, or the three event hooks.
