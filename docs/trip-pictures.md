# Trip pictures, destination, country and tags

Issue 154 (2026-11-16): Phase 1 is the data, upload and API; Phase 2 is the Trip Listings section as picture cards
and the trip page's hero and gallery (both below). The apps follow in issue 155.

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
- `Trip.destination` (free text), `Trip.countries` (ISO 3166-1 alpha-2 codes, `IsoCountries`), `Trip.tags` (ids:
  each label's lowercase slug, `TripTags.idOf`, so a capitalization fix in the setting orphans nothing).
- **A trip may visit several countries (2026-11-17, owner).** `Trip.countries` is an ordered list and the AUTHORITY.
  `Trip.country`, the one code every trip stored before that date holds, is kept as its FIRST entry, in step both ways
  (`setCountries` writes it, `setCountry` folds an old row into a list of one), so no stored row is rewritten, an older
  server reading a new row still finds a country, and apps already shipped keep reading `TripDto.country`. Never read or
  write the two independently. `normalizePlace` keeps real codes only, each once, in the order given. The editor's
  Countries row is a filterable `p:selectCheckboxMenu`, so from the page the order is the menu's (by English name); the
  API keeps the caller's order. A trip has NO "main" country (owner, 2026-11-17), so nothing treats the first as more
  important than the rest: Discover filters by ANY of them, counts a trip under EACH, and its country order sorts by the
  alphabetically first name (`DiscoverIndex.Entry.countryKey`). One consequence of keeping the old field: during a
  deploy's overlap, a server on the previous build that saves a multi-country trip writes back only the first country.
- Country SEARCH also matches the English names people type that the JDK does not spell ("Turkey", "UK",
  "Ivory Coast"; owner, 2026-11-17), and reads `&` as "and": `IsoCountries.aliasesOf` is the one list, used by
  the editor's Countries filter (`trip-js/countryFilter.js`, reading it from `trip.countryAliasesJson`), by
  Discover's `q` and by both apps' Discover search, which keep copies of it. Shown names never change.
- Every field is nullable, lazy lists read empty, so no migration: every older trip reads as no pictures, no place.

## Storage

- Key `tripPictures/{tripId}/{millis}-{width}.jpg` in the managed-media bucket, CDN-served, public by URL, cached for a
  day. `{millis}` is the clock's, pushed forward by one when two uploads would share a millisecond
  (`TripPictureCommands.nextStamp`), so parallel uploads to one trip never share a key. NOT under `org/{orgId}/...` as
  the issue first said: that prefix is the org media library's own namespace (its media admins can write any key there),
  and a trip can move to another organization, so a key there could be overwritten by the wrong tenant. `tripPictures/`
  is reserved (`MediaCommands.isReservedKey`).
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

An add decodes, cuts and stores its three renditions FIRST, and only then reads the row it saves, checking the
limit and the caller again against that fresh row (on a refusal the stored renditions are removed). Its first read
only refuses early; saving that read would put back any trip write that landed during the second or two of
processing (a registration approval, another upload). Each write's read-modify-write (add's re-read and save, move,
describe, remove) holds a per-trip `ReentrantLock` (striped by trip id, so a made-up id cannot grow a map), which
serializes concurrent uploads to one trip: a JVM lock is enough because the app runs as ONE task, and it is not
`synchronized` because requests run on virtual threads. The image work and the S3 deletes stay outside the lock.

The REST `POST` checks that the trip exists and that the caller may edit it BEFORE it reads the body (up to 16 MB),
like `PeopleResource.uploadPhoto`; `add` asks again.

- **Editor:** Trip Info's "Pictures:" row (not while creating) opens `picsDlg` (in `uploadDialogs`, beside the shared
  upload+crop dialog at `cropAspect` 1.5): Add Picture with an optional description, Make Cover, move earlier or
  later, edit description, remove. Every button carries the picture key in an `f:param`. "Destination:", "Country:"
  (ISO menu with a filter) and "Tags:" (checkbox menu) are ordinary rows, saved by Save and normalized there
  (`TripCommands.normalizePlace`).
- **REST:** `POST/PUT/DELETE /api/trips/{id}/pictures`, wrapping the same methods (`docs/api-contract.md`, "Trips").
- **Delete:** `docs/trip-delete.md`, step 6.

## From a trip album ("Choose from Albums", 2026-11-16)

The Pictures dialog has two sources: **Upload** (above) and **Choose from Albums**, which copies photos out of trip
albums instead. The dialog, the rules for which albums and photos are offered, and the seven other places that use
it are `docs/album-picker.md`; this is the trip-picture side.

- **The use** is `tripPictures`: several photos, each drawn at 3:2 the way it will be cut, "Adjust crop" on any that
  is not already that shape. Albums are those of the trip's own organization (`TripPictureCommands.albumScope`).
- **The copy.** `TripPictureCommands.addFromAlbum(caller, tripId, picks, alt)`: each pick is the album photo's
  FULL-size bytes (`ChatPhotos.currentBytes`) cut at a POSITION (`PhotoProcessor.processPictureAt`: the largest 3:2
  region, slid to two fractions of the room it has, 0.5 = centered), stored as an upload is. The dialog never
  learns the full image's pixel size, which is why this is a position and not a rectangle. The trip keeps its own
  copies: removing the photo from the album later, or deleting the album's trip, changes nothing here.
- **One save for the batch.** All the cutting and storing happens first, then every picture is appended in ONE
  read-modify-write under the trip's lock (`appendToStored` takes a list; a single upload is a list of one). One
  append per picture would re-read the row moments after this request's own save of it. What the row has no room
  for (another upload landed meanwhile) is deleted again.
- The description typed above the buttons goes to a SINGLE pick only; with several there is no telling which one
  it describes. A trip picture does not remember which album photo it came from, so nothing stops the same photo
  being added twice.

## The listing cards (Phase 2)

The owner picked the design from a prototype of four directions (2026-11-16): **Immersive** (the photo is the card,
text on a scrim over it) with the **Hero** from the Showcase direction (the section's next departure as one large
card above the grid). Nothing moves by itself; hover and press feedback only, off under `prefers-reduced-motion`,
which keeps a picture's fade-in (opacity is not movement) and swaps a gallery picture in place.

- `TripCardCommands` (`#{tripCards}`) wraps `TripCommands.getPublicTripsFor(instance)`, so a section's language, max
  count, `includeOrgs` and the org-site isolation rule are untouched. Each `Card` carries the trip, where the whole
  card links (`publicTripUrl`), ONE state, the cover's `src` and `srcset`, the fallback image and whether it is
  featured. **A card's `srcset` stops at the 960 rendition** (`CARD_MAX_WIDTH`, `srcsetUpTo`; owner, 2026-11-17):
  a dense phone screen otherwise picks the 1920 copy, several times the bytes, for a picture a few inches wide.
  Performance before sharpness on a listing; the trip page's hero and gallery still offer all three widths. The
  cards below the first row are `loading="lazy"` in the markup. Once the page has loaded, `tripCards.js` fetches
  the pictures of the cards within two screens of the window at low priority (an `IntersectionObserver`, margin
  `200%`), and keeps that lead as the visitor scrolls, so a phone's first scroll does not meet empty cards and a
  long listing is never fetched whole. The list is memoized per request (`RequestMemo`), and a signed-in viewer's
  registration is looked up once per card.
- **Links are screened.** An external trip's stored URL reaches a link only through `TripCommands.externalUrlOf` (a web
  address starting with http:// or https://, else the card links to the trip's own page), and the flyer only through
  `flyerUrlOf` (the same, or a path on this site). The screen is by SCHEME (`TripCommands.webAddress`), not by
  `java.net.URI`'s grammar: a browser opens an address with a space or a `|` in it, and such values are stored. A save
  refuses anything else when the link is being CHANGED (`linkProblems`; REST answers 400): the value is typed by a trip
  manager and a `javascript:` URL in an href would run as whoever clicks it, on a listing organizations share. An
  unchanged stored value is never judged, since every save of a trip (a paid checkout's roster write, an approval)
  passes the same gate and reading is already screened; nor is any link when the pre-read of the stored row failed
  (`TripCommands.preRead`).
- **State:** the viewer's own registration first: Registered (with a check) once it is confirmed, Pending (a clock,
  a neutral chip) while it waits for approval, as the old listing's chip said (owner, 2026-11-17: showing a
  pending registration as Registered was a regression). Without one, the trip page's own
  phase (`TripPageCommands.registrationInfo`): Closed once it has started, or (priced checkout on) from the moment
  its registration window closes; "Opens Dec 1" (a calendar icon, neutral like Closed, `Card.stateLabel`) before
  that window opens; Full when every place is taken, else Register.
  An external trip is Full by its typed count against its limit, inside that same rule
  (`TripPageCommands.buildInfo`). A card and the page it opens cannot disagree.
- **Featured:** the first listed trip that has not started by the server's CALENDAR day
  (`TripCommands.notStartedBy`, the sidebar countdown's rule; never an instant cut, which on a UTC server ends a
  trip's "upcoming" the afternoon before for the Americas). Its "in N days" is filled in by the browser
  (`countdown.js`, class `tripCountdownText`), like the sidebar's countdown: the server does not know the viewer's
  calendar day. The Closed chip is still the trip page's instant (`TripPageCommands.phaseOf`), so on the eve of a
  midnight departure a featured card can read Closed, as its page does.
- **No picture:** the TRIP's organization's link-preview image, else its background image, each only when it is an
  image the organization UPLOADED here (`BrandingPhotos.isUploadOf`; both settings also take a typed URL, and a card
  is drawn on listings other organizations' visitors see, so a typed one is never loaded), else a generated cover in
  the site's accent. A featured trip with no picture is a short banner, not a hero. The apps get the same image as
  `TripDto.fallbackPicture` (`TripCardCommands.orgPictureOf`, asked once per organization per request by
  `TripsResource`), sent only for a trip with no picture.
- **One home per picture rule** (`TripPictureCommands`, so no page restates one): `altOf(picture, trip)` is the
  alternative text (the picture's description, else the trip's short title; `Card.getAlt` answers the same static),
  `wideSizes(fullWidth)` is the `sizes` of a picture drawn at the page's full content width and
  `gridSizes(fullWidth)` that of a card in the listing's grid (the layout's measured widths live in those two
  methods' javadoc and nowhere in a page), and
  `getGalleryResponsive()` is the trip page gallery's one breakpoint (three thumbnails from 480px down).
- **Scrim:** white text on black over the photo is the one pair of literal colors (`tripCards.css`,
  `ColorLiteralPolicyIT`): the photo, not the palette, is the background. The scrim belongs to the text block, so it
  grows with the text and every line sits on at least two-thirds black (7:1 or better on any photo).
- **Pages:** `medjugorje/webapp/WEB-INF/ptypes/pilgrimages.xhtml` (the section; its stored id is not renamed),
  `WEB-INF/tripCard.xhtml` (one card, one link, named by its title), `resources/css/tripCards.css`,
  `resources/trip-js/tripCards.js` (fades a picture in over its placeholder color). Staff names and the flyer link
  moved to the trip's own page.

## The trip page

One picture is a hero image, two or more are a `p:galleria` with autoplay off (its thumbnail strip is the swipe surface
on a phone; medjugorje's `pfPatches.js` makes a tap open the tapped thumbnail), and a facts line under it shows the
destination, spiritual director, guide, coordinators, estimated price and flyer, each only when set. The two halves
are `WEB-INF/tripTopPictures.xhtml` and `WEB-INF/tripTopFacts.xhtml`; nothing of them renders on the app embed, which
draws its own from the API.

### On the trip page (2026-11-25)

The top block is two of the page's own sections, so an editor can move, hide, clone or drop them like any other:

- **Trip Detail Pictures** (`TripPicturesType`, `trip-pictures`, `ptypes/tripPictures.xhtml`): the gallery, or with
  its "Show" choice the cover alone (`TripPageCommands.coverOnly`). Its edit panel in the content drawer manages the
  TRIP's pictures through the same dialog the trip editor's Pictures row opens (`WEB-INF/tripPicturesDialog.xhtml`,
  mounted by both hosts in `uploadDialogs`; on `tripDetails.xhtml` behind a build-time `c:if` on the signed-in
  user): every change is saved to the trip at once, as there, and closing the dialog redraws the in-page preview.
  The pictures are never stored in the section.
- **Trip Details** (`TripFactsType`, `trip-details`, `ptypes/tripFacts.xhtml`): the facts line, with a pick of which
  facts show (`TripPageCommands.FACTS`: dates, destination, spiritual director, guide, coordinators, price, flyer;
  blank is the line as it always was, every fact but the dates, which the opening banner carries).

Both are offered on a trip's own page only (`ProgrammaticContentTemplate.onlyTripPage`), read the trip the page key
names (`tripPage.tripFor`), and in edit mode say so when they have nothing to show; visitors then see nothing.

**The transition.** `WEB-INF/tripPictureHero.xhtml` is the FIXED block above the sections, drawn from the same two
inner fragments, and a page draws it until its trip is stamped `Trip.pageTopSectionsAt`: by the seed of a new page
(`OrgPageBootstrap.topRows`, the sections first, then the starters; a retried seed whose rows an earlier attempt
already wrote is stamped too, from the two rows' fixed ids, `topRowIds`), by a legacy page's conversion, by a page
copied from a stamped trip (`docs/conference.md`, "Copying a page"), and for pages seeded earlier by the one-time
backfill `medjugorje/scripts/add-trip-top-sections.sh`. Keyed on the stamp, never on whether the rows are there, so an
editor who deletes the two sections does not get the fixed block back; only the page's own code writes it, and every
other save keeps the stored value (`TripCommands.keepStoredPictures`). A legacy page always draws the fixed block.

## Local fixtures

`FakeData` seeds seven pictured public trips (three CFPW, four Acme; Beta keeps none, since the browser tests rely
on its site having no trip; owner request 2026-11-16) plus a cover
on the existing Holy Angels Demo: a nine-picture trip, a long title, one with no picture, one already started
(Closed) and one whose two places are taken (Full). Their pictures are bundled in
`src/main/resources/demo/trip-pictures` (sources only, cut to 3:2, no metadata) and listed in `DemoTripPictures`;
`TripPictureCommands.localGet` makes a demo picture's three renditions through the real pipeline on first request,
in local mode only and only for a key that is exactly one of that picture's three renditions (the servlet is
anonymous, so any other miss costs a store read and nothing else). The repo is public and the owner chose to keep
the files here; they are the owner's own photographs (`demo/trip-pictures/README.md`).

## Tests

`TripPictureCommandsTest`, `AlbumPhotoSourceTest`, `TripPictureServletTest`, `PhotoProcessorPictureTest`,
`TripTagsTest`, `IsoCountriesTest`, `TripCommandsTest.aSaveKeepsTheStoredPicturesAndNormalizesThePlace`, the picture
tests in `TripsResourceTest`, `TripCardCommandsTest`, `DemoTripPicturesTest`; browser (medjugorje webtest):
`TripPicturesPwIT` (the editor dialog), `AlbumPickerPwIT` (Choose from Albums: own-organization albums only, the
held-back photo, several picks, a moved cut) and `TripCardsPwIT` (the cards, org isolation, the phone layout, the
gallery).
