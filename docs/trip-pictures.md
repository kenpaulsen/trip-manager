# Trip pictures, destination, country and tags

Issue 154, Phase 1 (2026-11-16). Phase 2, the card redesign of the Trip Listings section, builds on this; the apps
follow in issue 155.

## Owner decisions (locked 2026-11-16)

- **Cover plus optional gallery.** One ordered list, `Trip.pictures`, at most 9: position 0 is the cover every
  listing card shows, and it never moves by itself; the other eight appear only on the trip's own page.
- **Cards never auto-advance or cross-fade.** A manual swipe may come later and needs no data change.
- **Placeholder is the average color**, not a ThumbHash: no new dependency, and nothing for the apps to decode.
- **Tags come from ONE platform list**, the "Trip tags" setting (`KnownSettings.TRIP_TAGS`, Settings page, section
  Trips): Pilgrimage, Retreat, Conference, Mission, Youth, Family, Study, Service, Tour.

## Model

- `TripPicture` `{key, alt, width, height, placeholder}`: a plain `Serializable` POJO (the `BadgeImage` rule: Kryo
  rebuilds without constructors). `key` is a STEM; the three renditions are `{stem}-480.jpg`, `{stem}-960.jpg` and
  `{stem}-1920.jpg` (`TripPicture.WIDTHS`, `keyAt`). `width`/`height` are the largest rendition's.
- `Trip.destination` (free text), `Trip.country` (ISO 3166-1 alpha-2, `IsoCountries`), `Trip.tags` (ids: each
  label's lowercase slug, `TripTags.idOf`, so a capitalization fix in the setting orphans nothing).
- Every field is nullable, lazy lists read empty, so no migration: every older trip reads as no pictures, no place.

## Storage

- Key `tripPictures/{tripId}/{millis}-{width}.jpg` in the managed-media bucket, CDN-served, public by URL, cached
  for a day. NOT under `org/{orgId}/...` as the issue first said: that prefix is the org media library's own
  namespace (its media admins can write any key there), and a trip can move to another organization, so a key there
  could be overwritten by the wrong tenant. `tripPictures/` is reserved (`MediaCommands.isReservedKey`).
- `PhotoProcessor.processPicture`: decode inside `DecodeBudget`, apply EXIF orientation, cut to 3:2 (the crop is
  shrunk to that shape, never stretched; no crop takes the largest centered region), then one JPEG per width.
  Nothing is enlarged: a cut narrower than 1920 is stored at its own width, with a warning below 1600x1067. Every
  rendition is a fresh encode, so no EXIF (camera, GPS) survives; HEIC is transcoded like everywhere else.
- Local mode: `LocalObjectStore` (64 MB cap) behind `web/TripPictureServlet` at `/trip-pictures/*`.
- No rotate: a quarter turn of a 3:2 picture is a portrait no card can show. Orientation is applied at upload; a
  picture still wrong is removed and uploaded again.
- Cost: a picture's three renditions are about 0.6 MB, so 100 trips with nine pictures each is about 0.5 GB of S3
  (about $0.01 a month); card traffic sits inside CloudFront's free monthly allowance.

## Who writes, and how

`TripPictureCommands` (`#{tripPictures}`) is the only writer. Allowed: a site admin, a holder of `tripMgr` for THE
trip, or an admin of the trip's organization (the three the trip editor admits), asked on every call. Every write
reads the stored trip FRESH and saves it straight through the DAO (the Required Documents pattern), and
`TripCommands.saveTrip` replaces the caller's list with the stored one on every other save
(`keepStoredPictures`), so the editor's working copy, opened before an upload, can never undo it, and a REST body
cannot plant one. Objects are stored before the row names them and removed after it stops; a failure leaves at most
an unreferenced object, which the trip delete sweeps.

- **Editor:** Trip Info's "Pictures:" row (not while creating) opens `picsDlg` (in `uploadDialogs`, beside the shared
  upload+crop dialog at `cropAspect` 1.5): Add Picture with an optional description, Make Cover, move earlier or
  later, edit description, remove. Every button carries the picture key in an `f:param`. "Destination:", "Country:"
  (ISO menu with a filter) and "Tags:" (checkbox menu) are ordinary rows, saved by Save and normalized there
  (`TripCommands.normalizePlace`).
- **REST:** `POST/PUT/DELETE /api/trips/{id}/pictures`, wrapping the same methods (`docs/api-contract.md`, "Trips").
- **Delete:** `docs/trip-delete.md`, step 6.

## Tests

`TripPictureCommandsTest`, `TripPictureServletTest`, `PhotoProcessorPictureTest`, `TripTagsTest`,
`IsoCountriesTest`, `TripCommandsTest.aSaveKeepsTheStoredPicturesAndNormalizesThePlace`, the picture tests in
`TripsResourceTest`; browser: `TripPicturesPwIT` (medjugorje webtest).
