---
paths:
  - "trip/src/main/java/org/paulsens/trip/media/**"
  - "trip/src/main/java/org/paulsens/trip/web/ChatUploadServlet.java"
  - "trip/src/main/java/org/paulsens/trip/action/ChatPhoto*.java"
  - "trip/src/test/java/org/paulsens/trip/media/**"
---

# Chat-photo pipeline (`media/`)

- **Memory**: every `PhotoProcessor` entry point runs inside `DecodeBudget` (one process-wide budget of 1024 MB;
  an operation reserves three working copies of the raster its header promises, waits up to 20 s, and is
  otherwise refused as "busy"), and one image may decode to at most `DecodeBudget.MAX_DECODED_BYTES` (320 MB:
  the pixel cap alone let a 16-bit RGBA PNG through at 800 MB). A new entry point that decodes MUST go through
  `budgeted(...)`; a nested call shares the outer reservation.
- **Library uploads** (`MediaCommands.upload`, `/media-upload`): the stored Content-Type comes from the bytes
  (`UploadType`), never from the browser; anything not an image, PDF, video or audio is a download-only
  attachment (`docs/security-headers.md`, "nosniff").

- `media/` -- the chat-photo pipeline (P4 media, landed 2026-08-07): `PhotoProcessor` (two renditions per
  upload -- untouched original + ≤800px display copy; HEIC transcodes to full-res JPEG; animated GIF passes
  through), `ImageFormat` (magic-byte sniffing), `ChatPhotoStaging` (upload→send authorization). Uploads go
  through `web/ChatUploadServlet` (`/chat-photos/*`, own multipart-config in the live web.xml -- the chat
  page's JSF form must NEVER go multipart, Tomcat's `maxPartCount` counts ordinary fields). Album rows land
  in the media table under slot `tripChat-{tripId}`; message removal deletes photos everywhere (album
  semantics: retention expiry does NOT). Rotation (`PhotoProcessor.rotate`, `QuarterTurn`, `RotationGuard`):
  chat photos and receipts turn IN PLACE and bump `rev` (clients append `?r=N`; never fire REMOVED for a
  rotated chat key), while profile, badge, lodging and branding images get a NEW key.
