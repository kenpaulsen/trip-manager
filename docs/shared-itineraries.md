# Shared itineraries -- read before touching who may see another person's itinerary

The people on a trip can see one another's itineraries: which events each person is on, as the TRIP describes
them. Never a person's private note, room, stay dates or registration. Backend and website 2026-11-01; the iOS
and Android apps build on the same REST contract.

## Two switches, both required

| Switch | Where | Default |
|---|---|---|
| `Trip.itinerarySharing` | `trip/edit.xhtml` "Share itineraries?" (gate: Editor Admin, org admin, site admin) | **on** for a new trip, **off** for a new conference, **off** for every trip that existed before 2026-11-01 |
| `PrivacySettings.shareItinerary` | `account/person.xhtml` Privacy fieldset; `PUT /api/people/{id}` `privacy.shareItinerary`; the apps' Profile "Who can see" section | **shared**, for every person |

- The trip flag is a nullable `Boolean` where **null means OFF** (the reverse of `chatEnabled`), so every
  existing trip stays private with no migration. A new trip gets an explicit value from
  `TripCommands.stampCreationDefaults`, called by BOTH create paths (`createTripFor` and `POST /api/trips`).
  Changing the kind later never flips it.
- The person's choice is GLOBAL: one switch for all their trips, now and later, which the help text says.
  It is stored inverted as a primitive (`itineraryHidden`): Kryo runs no constructor, so session state written
  before the field existed comes back `false`, which must read as shared, never as opted out.

## The rule: `ItineraryAccess.of(viewer, viewerPerson, trip, subject, orgAdmin)`

THE one rule. The website (`#{sharedItinerary}`, `SharedItineraryCommands`) and the REST roster and endpoint
all call it. It answers `FULL | SHARED | NONE`, first match wins:

1. **FULL**: the person themselves, their family manager (`managedUsers`), a Trip Manager (`tripMgr`) of the
   trip, a site admin. Neither switch matters.
2. **NONE**: the subject is not on `Trip.people` (a pending registrant is never seen), the trip does not share
   itineraries, or the subject opted out.
3. **SHARED**: the viewer is on the roster, holds `tripView`, or administers the trip's org.
4. **NONE** otherwise.

A Trip Viewer and an org admin are deliberately fellow travelers, not admins (owner decision 2026-11-01). Before
this, `tripView` opened anyone's full itinerary page. `ItineraryAccessTest` pins the matrix.

**Not covered:** every trip member still receives each event's `participants` list over REST, so a determined
client could reconstruct anyone's itinerary. The owner chose to leave that as it is (2026-11-01). The switches
govern the itinerary VIEW.

## Why a peer gets a separate row type

A shared itinerary is never "the full page with parts hidden". The full rows (`LodgingViews.ItineraryRow`) carry
the private note, reservation, room and stay dates. A peer page that reused them would leak the next
person-specific field someone adds without a matching `rendered=`. Peers get
`SharedItineraryCommands.SharedItineraryRow` (web) and `TripsResource.sharedEventDto` (REST), which have no field
for any of it. This is the same reasoning as `PersonDto.redactedFor`'s whole-record rebuild.

## Surfaces

- **Web**:
  - `trip/sharedItinerary.xhtml?trip=&id=` is the read-only page. A FULL viewer is redirected to
    `itinerary.jsf`; NONE goes home with one message, whatever the reason.
  - `trip/itinerary.xhtml` and `itinerary-print.xhtml` gate on `tripMgr`; `defaultAuth` adds self, manager and
    site admin. A SHARED viewer landing on `itinerary.jsf` is redirected to the shared page instead of being
    refused.
  - `trip/tripContacts.xhtml` links each row through `sharedItinerary.itineraryUrl`. It shows the full page, the
    shared page, or no link, never a link that then refuses.
- **REST**:
  - `GET /api/trips/{id}/roster` gives each person `itinerary` (`FULL`/`SHARED`/absent) and `contact` for THIS
    viewer.
  - `GET /api/trips/{id}/people/{pid}/itinerary` returns the shared events, or 403 `ITINERARY_NOT_SHARED`.
  - FULL viewers use the existing `?personId=` reads.
  - See `docs/api-contract.md`.
- **Apps**:
  - A People card on the trip's Overview opens the People list: the trip team first, then travelers.
  - A person opens a person screen with their contact details shown directly. The details are left out when
    `contact` is absent. An "Itinerary" row appears when `itinerary` is present: FULL opens the regular
    itinerary as that subject, SHARED opens the read-only shared one.

## Contact details

The roster's `contact` follows the web contacts page exactly (`PrivacyView`, through `api/RosterContact`):
- each person's shared fields reach a fellow traveler;
- city carries state, and street carries zip;
- an admin (Trip Manager, org admin, site admin) also gets the withheld fields, named in `masked`, which the apps
  show behind a tap like the website's mask;
- a family manager sees everything.

The block is absent when there is nothing to show, so the app offers no contact details for that person.
