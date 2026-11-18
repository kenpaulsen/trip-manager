# Choose from Albums

Every place that takes a picture has two sources: a file from the person's device, and **Choose from Albums**, a
photo already shared in a trip album. A trip's album is the photos shared in its chat (media rows in slot
`tripChat-{tripId}`, the page `trip/tripMedia.xhtml`). Built 2026-11-16 for the trip editor's pictures and extended
to seven more places 2026-11-17 (owner). The owner picked the dialog from a prototype of three directions:
**Library** (trips down the left, the chosen trip's photos on the right).

## Owner decisions

- **Everywhere but chat, a pick is COPIED**: the place keeps its own picture, cut to its own shape, so hiding or
  removing the photo in its album later changes nothing there.
- **Chat attaches a REFERENCE to the original**, not a copy ("A chat reference", below).
- **A hotel's photos may come from any album**: becoming public is the point of adding a photo there.
- **Each place keeps its own shape**: the crop it already had, or the photo whole where nothing sets a shape.

## One dialog, eight uses

`medjugorje/webapp/WEB-INF/albumPickerDialog.xhtml` is mounted ONCE, by `template.xhtml`, on every signed-in page
(a build-time `c:if` on a session read, so a visitor's page never builds it). It has fixed ids, so a view can hold
it once, and several pages take album photos for more than one purpose. A page opens it from JS for a named USE:

```js
TripAlbumPicker.open({ use: 'badge', mode: 'one', aspect: 1, afterPick: TripAlbumPicker.toCropDialog });
```

The use is all the server takes from the page. `AlbumChooser` (`#{albumChooser}`) derives from it which bean gets
the picks and in which scope the albums are listed and every pick is checked again. How the dialog LOOKS (the mode,
the tile shape, the noun on the Add button) is the opener's, because it can do no harm.

| Use (`AlbumChooser.Use`) | Where | Mode | What a pick becomes | Albums offered (scope) |
|---|---|---|---|---|
| `tripPictures` | trip editor, Pictures | cut, several | a trip picture, cut to 3:2 at the chosen position | the trip's organization |
| `badge` | itinerary badge, Custom Badge Images | one | the square crop step, then a badge image | the trip's organization |
| `profile` | profile, pictures | one | the square crop step, then a profile picture | every album the person may open |
| `linkPreview` | org Appearance, link preview image | one | the 1.91:1 crop step, then the branding image | that organization |
| `lodging` | hotel Photos tab and a room type's photo | one | the free crop step, then a gallery photo | every album the person may open |
| `library` | media library page | whole, several | a library item, the photo whole | the trips this site serves |
| `url` | a content section's picture field; the rich-text editor's image button | one | a library item, and its address in the field | the trips this site serves |
| `chat` | chat composer | choose, several | a reference attached to the message | the trip's organization |

Modes: **cut** shows each tile the way it will be cut and offers "Adjust crop" (a frame dragged over the whole
photo; a photo already of the shape has none); **whole** and **choose** use photos whole; **one** takes the photo on
the press. A floor plan, a logo and a favicon are never album photos, so those have no album button. The Trip Page
background and the Appearance background already pick from the media library, which is how album photos reach them.

## What is offered (`AlbumPhotoSource`, reached only through `#{albumChooser}`)

Three rules, each asked AGAIN when a pick is used (`offered`), because a pick arrives from the browser as a bare
media id:

1. **The scope's organization.** `tripScope(tripId)` and `orgScope(orgId)` offer only that organization's albums:
   a photo picked for a trip or an organization lands on its public pages, so another organization's album, even
   one this person belongs to, would move one tenant's photo onto another tenant's page. No exception for a site
   admin. `SCOPE_SITE` offers the trips the site of the request serves (`ListingScope.reachable`: an organization's
   own on its site, the organizations a shared site hosts on that site). `SCOPE_MINE` offers every album the person
   may open, and is used only by the two hosts that belong to no organization: a person's profile and a hotel.
2. **An album the person may open** (`mayOpen`): on the trip's roster, or holding `tripView` for it (the album page's
   rule); or someone who already manages the whole album (`MediaCommands.managesAlbum`: a media admin globally or of
   the trip's organization, or the trip's manager); or an admin of the trip's organization. The trip being edited
   is always listed, and its own album is always usable by whoever may edit it.
3. **Not kept out of public view.** A photo whose sharer or a moderator hid it (`MediaItem.hidden`) is never
   offered. An album says how many were held back, so a missing photo is explained.

Only images are offered. The trips looked through are the caller's own (the roster's reverse index, any age) plus
the store's 400 most recent; there is no index of trips by organization.

Each host derives its scope from the SAME seam its authorization uses (`PhotoUploadBean.albumScope()`: the trip a
dialog is open on, the role being uploaded), never from the page, so a use the page did not really open finds no
target and is refused there. A target alone does not LIST albums: the trip a view remembers (`viewScope.theTripId`)
is also set by read-only pages any signed-in person can open (a trip's public page, its join page), so a host names
its scope only to a caller its own authorization admits (`PhotoUploadBean.getAlbumScope` asks `authorization()`,
the "who may" rule each host writes; the base's `uploadGate()` is that rule followed by the host's `limit()`, its
counts and caps, which the listing never asks: chat's rate limit is not spent on a listing, and a full gallery
still lists and then says there is no room; the library and URL uses ask `AlbumLibrary.mayAdd` in
`AlbumLibrary.getAlbumScope`). The chooser works a use's scope out once per request (`AlbumChooser.scopeOf`), and
every album listing reads the media table ONCE (`albumRows`), however many trips it looks through.

## The copy paths

- **A crop host** (`badge`, `profile`, `linkPreview`, `lodging`): `PhotoUploadBean.pickFromAlbum(mediaId)` reads
  the album photo's full-size bytes (`ChatPhotos.currentBytes`) and PARKS them exactly as an upload's are
  (`PendingUploads`), so everything after it (the cropper at that feature's shape, `confirm`, the authorization
  asked again there) is the upload flow's own, unchanged. `AlbumChooser.pick` then re-renders the crop dialog's two
  step panels, and the page's `afterPick` (`TripAlbumPicker.toCropDialog`) shows that dialog on its cropper.
- **Trip pictures** (`tripPictures`): `TripPictureCommands.addFromAlbum` cuts each pick at a POSITION
  (`PhotoProcessor.processPictureAt`: the largest 3:2 region, slid to two fractions of the room it has) and appends
  the whole batch in one read-modify-write. See `docs/trip-pictures.md`.
- **The media library** (`library`, `url`): `AlbumLibrary` (`#{albumLibrary}`) re-encodes the photo whole as a JPEG no
  wider than 2400 pixels (a fresh encode, so no camera or GPS metadata rides along), stores it under the owner's
  `album-photos/` folder (`org/{orgId}/album-photos/{number}.jpg`; no prefix on a shared site) and records an ordinary
  library row titled "Album photo" and described "Copied from a trip album": never the album row's title (the caption
  the sender typed for the trip's members) or description (who shared it, in which trip's chat), because a library row
  is public. An admin may rename it; the audit line keeps the source key. Who may: this site's media admins, an editor
  of its content (`contentAdmin` here) or an admin of its organization (`mayAdd`). The `url` use hands the copy's
  address back (`albumUrl`), made absolute in local mode because a picture field takes only a full web address. Local
  mode has no bucket: the copies sit in a capped in-memory store behind `web/AlbumLibraryServlet` at `/album-library/*`
  (`MediaCommands.localServedPath`), the trip stylesheets' arrangement.

## A chat reference

The chat's "Choose from Albums" button attaches album photos to a message WITHOUT copying them. The page's tray
holds `album:{mediaId}` where a staged upload holds its key (`ChatPhotos.ALBUM_REF_PREFIX`), drafts included. At send
`ChatPhotos.resolveStaged` asks `AlbumPhotoSource.offered` in the trip's scope and builds a `ChatAttachment` with
the ORIGINAL's keys and `ref = true`. Nothing is staged: the staging registry's sweep deletes the objects of
whatever it holds.

A photo's key is its identity (its comment thread, reactions and album row all join on it), so a reference shares
all of those with the original. What a reference must never do, and the code that holds each line:

| A reference never... | Held by |
|---|---|
| deletes the photo, its album row or its thread when it (or its message, or its author's account) is removed | `ChatPhotos.deleteEverywhere` and `deleteRenditions` act only on attachments that own their photo |
| adds a second album row for the same key | `ChatPhotos.recordAlbumRows` skips references |
| re-parents the photo's comment thread to itself | `PhotoChatCommands.ensureChannelsForMessage` skips references |
| turns the stored photo | `ChatPhotoRotation.rotate` refuses (FORBIDDEN, with its own message) |
| changes the public-site flag | `ChatPhotoEdits.setVisibility` refuses |
| becomes the parent of the photo's thread when the thread is made late | `PhotoChatCommands.messageCarrying` matches owners only |

A reference copies the photo's size, small rendition and `rev` once, at send. When the photo is later turned where
it was first shared, the reference is not rewritten (nothing indexes references); instead
`ChatPhotos.withCurrentReferences` brings every reference on a page up to date from its album row as the chat feed
and history go out (`ChatCommands.feed`/`history`): every turn is a quarter that moves `rev` on by one, so an odd
number of turns since the send swaps the dimensions (`ChatAttachment.followingAlbum`), and only a rev that moved
FORWARD counts (so every rebuild of an album row must carry its `rev`, as `MediaCommands.update` and `assignToSlot`
do). A page with no reference costs nothing; one with any reads the media table once (`ChatPhotos.mediaRowsByKey`).
Nothing signals the referencing chat when the original turns, so a chat already open keeps a reference's old shape
until its messages are read again (a reload, or a history read), like the reaction-chip lag above.

When the ORIGINAL is removed (its message, or its whole trip), a reference has nothing left to show. There is no
index from a photo to the messages that reference it, so the stored reference is not rewritten; instead, every read
of the chat (`ChatPhotos.withCurrentReferences`) sends a reference whose photo has no media row left as a tombstone
that is STILL a reference (`ChatAttachment.gone`: keys and facts cleared, `deletedAt` the epoch, `ref: true`, no
`deletedBy`). A client that cached the photo therefore drops it (a privacy point: a removed photo must not stay on
phones through a reference), and the web chat writes "This photo is no longer available." in its place, as
`noteIfGone` already does for an image that fails to load before the next read. A photo moved out of its trip album
to a library slot keeps its row, so its references keep showing it. A photo HIDDEN after it was referenced (its
sharer or a moderator kept it off the public site, `MediaItem.hidden`) goes out as the same tombstone: Choose from
Albums no longer offers a hidden photo (`AlbumPhotoSource.isOfferable`, since a reference shows it to another
audience), and that rule holds for references made before the hiding too. Nothing is rewritten, so showing the
photo again brings its references back. A failed read of the media table (an empty
`Optional`, never an empty map) leaves every reference as it was sent, so it can never look like every photo
removed. Owner, 2026-11-17. A reference's reaction chip folds in the photo's reactions like any carrier's, but only
the owning message's cached summary is refreshed when someone reacts, so a reference's chip can lag.

Wire contract: `docs/api-contract.md`, chat photos (`ref` on an attachment; `album:{mediaId}` as a send key).

**The apps** (2026-11-17) list the same albums and photos the dialog does, through `ChatResource`:
`GET chat/channels/trip:{id}/album-sources` is `AlbumPhotoSource.albumsFor` in the chat trip's scope, and
`.../album-sources/{albumTripId}/photos` is `AlbumPhotoSource.offeredRowsFor` (the listing's rules, each row one
`offered` accepts), both behind `checkAttach`, the gate `ChatPhotoUpload.authorization` asks for the dialog. The
second exists because the album's own slot read (`GET media/slots/tripChat-{id}`) admits the roster and the album's
managers only, so an organization admin who is not on a trip could list its album but not open it. Before these,
the apps offered only the same-organization trips they held (the roster's), which is still their fallback against
an older server.

## Tests

Unit: `AlbumPhotoSourceTest` (the three rules in every scope, and `offeredRowsFor`), `AlbumChooserTest` (the
dispatch), `ChatResourceTest.albumSources...`/`albumPhotos...` (the REST reads and their gate),
`AlbumLibraryTest`, `AlbumLibraryServletTest`, `ChatPhotoReferenceTest` and
`ChatPhotoRotationTest.aMessageThatOnlyReferencesAPhoto...` (the table above, the second on the real send path),
`BadgePhotoCommandsTest` (a pick parked into the crop step and every refusal), one scope test in each of
`ProfilePhotoCommandsTest`, `BrandingUploadCommandsTest`, `LodgingUploadCommandsTest`, `ChatPhotoUploadTest` and
`TripPictureCommandsTest`, `PhotoProcessorPictureTest` (the positioned cut).

Browser (medjugorje webtest): `AlbumPickerPwIT` (trip pictures) and `AlbumPlacesPwIT` (badge, profile, hotel, link
preview, library, the rich-text image button, a content section's picture field, and a chat reference whose message
is then removed).
