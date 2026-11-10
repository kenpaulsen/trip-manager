---
paths:
  - "trip/src/main/java/org/paulsens/trip/media/**"
  - "trip/src/main/java/org/paulsens/trip/web/ChatUploadServlet.java"
  - "trip/src/main/java/org/paulsens/trip/action/ChatPhoto*.java"
  - "trip/src/test/java/org/paulsens/trip/media/**"
---

# Chat-photo pipeline (`media/`)

- `media/` -- the chat-photo pipeline (P4 media, landed 2026-08-07): `PhotoProcessor` (two renditions per
  upload -- untouched original + ≤800px display copy; HEIC transcodes to full-res JPEG; animated GIF passes
  through), `ImageFormat` (magic-byte sniffing), `ChatPhotoStaging` (upload→send authorization). Uploads go
  through `web/ChatUploadServlet` (`/chat-photos/*`, own multipart-config in the live web.xml -- the chat
  page's JSF form must NEVER go multipart, Tomcat's `maxPartCount` counts ordinary fields). Album rows land
  in the media table under slot `tripChat-{tripId}`; message removal deletes photos everywhere (album
  semantics: retention expiry does NOT). Rotation (`PhotoProcessor.rotate`, `QuarterTurn`, `RotationGuard`):
  chat photos and receipts turn IN PLACE and bump `rev` (clients append `?r=N`; never fire REMOVED for a
  rotated chat key), while profile, badge, lodging and branding images get a NEW key.
