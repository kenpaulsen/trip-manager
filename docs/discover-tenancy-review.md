# Discover: tenancy review

Discover (medjugorje#155) is the apps' signed-out trip list and search: `GET /api/discover/trips` and
`GET /api/discover/trips/{id}` (`api/DiscoverResource`). It is **the one sanctioned ANONYMOUS read that
crosses organizations**: it answers callers with no account. (One signed-in listing crosses them too, says
more, and is accepted as such; see "The second crossing" below.) Organization is the tenancy boundary everywhere else
(`docs/org-admin.md`), so this page records exactly what crosses, on whose say, and what holds it there. Read it
before adding a field, a filter or a second public endpoint.

## The rule: what is listed

One method decides, `ListingScope.discoverable(trip, owner, today)`. A trip is listed when ALL of these hold:

| Condition | Who controls it | Where |
|---|---|---|
| "Show on homepage" is on (`Trip.openToPublic`) | the trip's managers | trip editor |
| the trip belongs to an organization that can be read | (data) | `Trip.orgId` |
| that organization's "Shared sites" is on (`Organization.allowsSharedSites()`) | the organization's admins | org Profile tab |
| it has not ended: its end date, else its start date, is today or later | (time) | |

Consequences that were decided, not defaulted:

- **A never-touched "Shared sites" counts as ON** (owner, 2026-11-17). The stored value is `null` for every
  organization that never opened the checkbox, and the checkbox shows it ticked. So turning Discover on lists the
  public trips of every such organization. Before the first deploy the owner gets a read-only report of which
  organizations and trips that is (and how many public trips have no organization), so any can be unticked first.
- **A trip with no organization, or naming one that cannot be read, is NOT listed.** There is nobody to consent,
  to name on the card, or to join. This is the reverse of the shared sites, where org-less legacy trips keep
  listing (`ListingScope.shows`).
- **No host widens the rule.** `discoverable` is static and never looks at the request's site; in particular it
  never consults `SiteContext.reachesEverything()`. On an organization's OWN host Discover is narrower still:
  only that organization's listed trips (`SiteContext.admits`).
- **A trip that is not listed does not exist here.** In the list, in search and by id it is the same miss as an
  id nobody ever used: `404 {"error":"NOT_FOUND","message":"No such trip."}`, byte for byte, with no validator.
  `DiscoverResource` cannot tell the cases apart, because it reads only `DiscoverIndex`, which holds listed
  trips and nothing else.
- **Being signed in changes nothing.** The resource is not `@TripApi`, never asks who is calling, and gives a
  site administrator the anonymous answer. That is also what lets the response be `Cache-Control: public`.

## The shape: what is said about a listed trip

`DiscoverTripDto` is an allow-list, built by the hand-written `DiscoverTripMapper` from values named one by one.
It is never derived from `TripDto` or `TripMapper`, so a field added to `Trip` cannot ride along.
`DiscoverTripDtoAllowListTest` pins the component names of every record in a response, and serializes a trip
holding a sentinel in every other field to prove none comes out.

| Field | From | Already public where |
|---|---|---|
| `id` | `Trip.id` | every public trip link (`/trip/tripDetails.jsf?trip=...`) |
| `title` | `Trip.getShortTitle()` | the trip card and the trip page heading |
| `startDate`, `endDate` | the dates, WITHOUT the time of day | the trip card and trip page |
| `destination` | `Trip.destination` | the trip card's place line |
| `priceText` | `Trip.estimatedPrice`, free text | the trip card and the registration block |
| `kind` | `Trip.kind` | the trip page (a conference has its own page and button label) |
| `language` | `Trip.language` (English when unset) | the home page's language sections |
| `pictures` | cover and gallery, CDN URLs | the trip card and the trip page gallery |
| `orgName` | `Organization.name` | the trip card's organization line; the org's site |
| `orgAbbreviation` | `Organization.abbreviation`, trimmed; absent when blank | see below |
| `orgId` | `Organization.id` | the org's public media and logo URLs (`org/{id}/...`) |
| `orgLogoUrl` | the logo, ONLY when the org uploaded it here | the org site's header |
| `detailsUrl` | the trip page on the org's own site | it is that public page's address |
| `registration` | `open`, `full`, `closed` or `notOpen` (a priced checkout's window not open yet; `closed` also after it closes) | the card's Full / Closed / Opens chip, the registration block |
| `registrationOpens` | the day a `notOpen` trip's window opens (`CheckoutConfig.opensAt`, day only), absent otherwise | the registration block ("Registration opens Monday, December 1") and the card chip ("Opens Dec 1") |
| `registersOnWeb` | whether the trip registers on another site | the card links there instead of here |
| `countries` | `Trip.countries`, ISO codes | see below |
| `tags`, `tagLabels` | the trip's tags still on the platform list | see below |

Two fields are NOT printed on an organization's site today, and cross on the owner's decision rather than on
precedent: #155 lists them in the public shape, and the owner confirmed both on 2026-11-17 when this review
pointed out that no page shows them.

- **`countries`** are codes the trip's manager picks to classify the destination (issue 154; several per trip
  since 2026-11-17). The destination text they classify is public; the codes themselves are on no page. They
  were added to be filtered on.
- **`tags`** are chosen by the trip's manager from ONE platform-wide list (`KnownSettings.TRIP_TAGS`): never
  free text, and only tags the list still offers are sent. No page shows them yet; they were added in issue 154
  to be searched on, and #155 lists them in the public shape. Until Discover they reached signed-in callers
  only (`TripDto.tags`).

A third, **`orgAbbreviation`** (the organization's short label, "CFPW"), crosses on the **owner's approval,
2026-11-17**, given knowing what it widens: until now only SIGNED-IN people saw it, though those of any
organization. No anonymous page prints it (the website shows it in the shared site's Org chip in the top bar
and on the admin pages, both signed in); across organizations it reached every signed-in caller through
`GET /api/orgs/directory` (the apps' "choose your organization" list) and `GET /api/orgs/{orgId}/site`. It was
added so the apps' public organization chip matches the signed-in one. It is the organization's own choice of
a name for itself, typed on its Profile tab next to the name Discover already sends, so it says nothing the
name does not.

Deliberately absent, each for a reason:

- `people`, staff ids and names (`director*`, `facilitator*`, `localGuide`): Discover never says who travels.
- `regLimit`, seats taken, seats left, `nonHostedRegNumber`: only the PHASE crosses. `full` does reveal that a
  limited trip reached its limit, exactly as the public card's chip does.
- `regOptions`, `paymentConfig`, `checkout`, `waivers`, `page`: registration and money belong to members. Of the
  checkout policy only two facts cross: the phase its window produces, and for a window not open yet its opening
  DAY (`registrationOpens`, owner's decision 2026-11-17), which the trip's own page shows anyone ("Registration
  opens Monday, December 1"). Never the closing time, the time of day, prices, bands or codes.
- `flyerUrl`, `nonHostedTripUrl`: typed by a manager; a phone must not call a host one organization chose on
  behalf of people who never chose that organization. The same reason `orgLogoUrl` is uploaded-only
  (`BrandingPhotos.isUploadOf`, the trip cards' rule). `registersOnWeb` is the one fact taken from the URL.
- `description`: rich HTML written for the organization's own page. The apps fetch that page (`detailsUrl`).
- the organization's contact email, admins, settings, mail domains: an organization is named, not described.

Search (`q`) reads only text derived from the DTO (`DiscoverIndex.Entry.of`): title, destination, every
country's code and name, organization name, tag labels. A word cannot match on something a response would not
show. Facets are counts of listed trips by those same public values.

## The edges

- **No session, no cookie.** `web/SessionlessPaths` names `/api/discover/`, judged on the path the request is
  routed by (so `/api/discover;x/trips` and percent-escaped spellings count too), and `DiscoverResource` answers
  404 to any request that rule did not recognize, so the two cannot disagree; `RememberMeFilter` stands aside
  there before it looks at a session (a remember-me cookie on any other `/api/` path becomes a new session), and
  `SessionRecoveryFilter` neither loads the session nor resolves a bearer token. `DiscoverAnonymousPwIT`
  (browser tests) checks the real container: no `JSESSIONID`, with and without a remember-me cookie.
- **GET only**, pinned by `ApiSurfaceTest`. Joining is the existing signed-in registration, which is where
  `OrgCommands.joinOnRegistration` writes the membership; browsing joins nothing.
- **Rate limit** per client address (`api/PublicRateLimiter`, settings `discover.rate.limit` /
  `discover.rate.windowSeconds`, default 120 per 60 s; IPv6 counted by /64). It fails open: a cache outage does
  not take the list down. A refusal writes no audit row, so an anonymous script cannot fill the trail.
- **Cost.** Every request, a 304 included, costs ONE shared-cache command: the rate limiter's counter increment
  (`PublicRateLimiter.check`), made before anything else so a refusal is cheap. After that a request reads
  `DiscoverIndex`'s in-JVM snapshot: no table, no scan, no further cache read. The snapshot is rebuilt when a
  request finds it a minute old, OR on the first request after any trip or organization save on this server
  (and after `DAO.invalidate`), whatever its age; registrations save their trip, so while people are
  registering that is once per save. A rebuild reads, all through the cache: the active trips, the
  organizations, each LISTED trip's registrations (for its open/full/closed phase), the offered tags, and each
  listing organization's logo setting. It runs under the index's lock, so concurrent requests wait for one
  rebuild rather than starting their own.

## Staleness: how long after an opt-out a trip can still show

Turning "Shared sites" off, taking a trip off its homepage, or deleting it:

| Where | Still listed for at most |
|---|---|
| the server that took the save | nothing: `DAO.saveTrip` / `deleteTrip` / `saveOrganization` mark the index stale, the next request rebuilds |
| a client or cache holding a response | 60 s (`Cache-Control: public, max-age=60`), then it revalidates |
| another server (only during a deploy's overlap; the service runs one task) | 60 s (the index's refresh) on top of that server's near-cache lag for a write made elsewhere, by default up to 5 min (`cache.near.checkSeconds`, `docs/caching.md`) |
| an app's SAVED copy of the list (shown while the phone is offline or before the first answer arrives) | until the app next reaches the server, which replaces the copy; with no connection, a trip from the saved copy is shown only while now < max(the time the copy was saved, the trip's end) + 7 days (owner decision 2026-11-17: "Until 7 days after the end of the pilgrimage, or 7 days -- whichever is longer.") |

The registration phase and "not ended" are time-dependent and are recomputed at every rebuild, so a trip that
starts or ends drops its state within a minute.

The saved-copy row is the apps' to keep (the server cannot reach into a phone): it is the one place an opt-out
can outlive the minute above, and only on a device that has not been online since. It is bounded so that a
phone left offline cannot show an organization's trips indefinitely after that organization withdrew.

## The leak this change closed

`GET /api/trips?filter=open` on the product's own host (unitetrip.com, the apps' home) is an authenticated
listing that reaches every organization (`SiteContext.reachesEverything`). Since 2026-09-14 it returned EVERY
public trip to any signed-in account, including those of an organization with Shared sites off; the apps
narrowed the list to the person's organizations, the server did not. `ListingScope.listedAcrossOrgs` is now the
one rule for that host: a trip is kept only when it is `discoverable`, or it is not crossing an organization for
this caller (a trip of an organization they belong to, or a legacy trip with no organization);
`TripsResource` adds a trip the caller may read anyway (`canReadTrip`: on its roster, managing someone on it,
holding a role on it, a site administrator). So a member of an opted-out organization still finds its open
trips in the app, and a stranger no longer can. The album of an open trip on that host
(`MediaResource.mayBrowseSlot`) had the same hole, any signed-in account could browse it, and is gated by the
sibling rule `ListingScope.albumAcrossOrgs`: the same question with the CONSENT half of `discoverable` only
(`sharedAcrossOrgs`: "Show on homepage" on, the organization allows shared sites), and the same `canReadTrip`
exception. The dates are left out on purpose: a list of trips to join drops a trip the day after it ends, but
that is when its album fills, and the website shows ended trips' albums too. Other hosts are unchanged: they
were already narrowed by `TripCommands.getPublicTrips`.

## The second crossing: what the open listing still sends (accepted by the owner, 2026-11-17)

The fix above decides WHICH trips of other organizations a signed-in caller is handed on the product's host:
only the discoverable ones. It does not change WHAT is sent about them. An entry there is the signed-in listing
shape (`TripDto` without events, its roster already narrowed to the caller's own people), not `DiscoverTripDto`.
So for a discoverable trip of an organization the caller has no tie to, any account still receives `regLimit`,
`nonHostedRegNumber`, `regOptions`, `description`, `flyerUrl`, `nonHostedTripUrl` and the staff names
(`director`, `localGuide`, `facilitators`): the fields this review lists as deliberately absent from Discover.
Discover is therefore the one ANONYMOUS read across organizations; this listing is a second, signed-in one, and
it says more.

It was left as it is because the shipped apps read those fields from this listing: Find a Trip shows an
organization chosen in Settings, and registration takes its questions (`regOptions`) from the cached entry of a
trip the person is not yet on. Narrowing the entry to Discover's fields would need the apps to fetch the
registration questions another way first. Raised by the pre-push review (trip-manager `ecd32611cc27.md` F7) and
put to the owner as "narrow it, or keep it as recorded here": the owner chose to KEEP it (2026-11-17). So this
is a sanctioned crossing, for signed-in callers only, of exactly the fields named above. Adding a field to
`TripDto` therefore sends it across organizations here for any discoverable trip: decide that when adding one.

## Before changing any of this

- A new field: add it to `DiscoverTripDto`, to the allow-list in `DiscoverTripDtoAllowListTest`, and to the
  table above with where it is already public. If it is not public anywhere, that is the owner's call.
- A new filter or sort: it may read only `DiscoverIndex.Entry`, which is built from the DTO.
- A second public endpoint: it reads `DiscoverIndex`, never the DAO, or it needs this review again.
