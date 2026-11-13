# Privacy settings

Per-user visibility choices for profile fields, decided 2026-08-12. Read this before touching Person field
visibility, `PersonDto` redaction, `tripContacts.xhtml`, or `account/person.xhtml`.

## The model

`PrivacySettings` lives directly ON `Person` (no separate table; setter-populated like `familyId`, never in
the 17-arg builder, never null — old rows deserialize to the defaults). Exactly four knobs, each
`PRIVATE | LOGGED_IN`:

| Knob | Governs | Default |
|------|---------|---------|
| `email` | email address | `LOGGED_IN` |
| `cell` | cell phone | `LOGGED_IN` |
| `city` | city AND state | `LOGGED_IN` |
| `street` | street AND zip | `PRIVATE` |

A fifth choice, `shareItinerary` (2026-11-01), is NOT a `Visibility`: it is a boolean about the people on the
person's TRIPS (not every signed-in user), it defaults to shared, and it applies to every trip at once. It is
stored inverted as a primitive (`itineraryHidden`) so its zero value is the default under Kryo. See
`docs/shared-itineraries.md`.

`isStreetVisible()` is false whenever city is private, regardless of the stored street knob — the rule is
enforced in the model, not just the UI (the profile page additionally disables the street selector).

Everything else is fixed policy, no knob:

- **Always private**: birthdate, passport, TSA, emergency contacts, notes.
- **Always visible to signed-in users**: name, sex. (Sex was deliberately WIDENED out of
  `seesTravelDocuments()` — user-confirmed. `tripContacts.xhtml` still renders it only in the admin
  columns, but that is a space/clutter choice, not privacy.)

"Visible" always means **any authenticated user** (the user anticipates a future profile page; names are
discoverable through chat). Nothing here publishes anything to anonymous visitors.

**The one exception is a content page's Person card** (`person` content type, e.g. the public board page
`/board/index.jsf`; see `docs/content-templates.md`, "Named pages"). Listing someone there is a content
editor's deliberate act of publication, so the card shows the account's cell to everyone and the account
email to signed-in viewers -- unless the person set that field PRIVATE, which withholds it on the card too
(user decision 2026-10-07; before then the board page ignored privacy settings entirely).

## Who sees what

- **Self and family managers**: privacy never hides anything from them. Exception: staff **notes** follow
  `AccessLevel.seesNotes()` — a manager does not get them (hidden on pages, absent over REST).
- **Trip admins (`tripMgr`) and site admins**: may get the real values, but withheld fields render
  **masked**: a placeholder, with the value NOT in the page. A click is a server round trip that records the
  reveal in the view and re-renders that one value (owner decision 2026-11-12, reversing the earlier UI-only
  blur, whose real value sat in the HTML source for anyone to read). No audit of reveals. `tripFinView` keeps
  its `account/person.xhtml?trip=` access (they mail checks / validate identity).
  A trip admin is the admin of a trip the PERSON is on: over REST (`GET /api/people/{id}?trip=`) the role
  counts only when the person is on that trip's roster or holds a registration there that is not
  NOT_REGISTERED (`TripCommands.isOnTrip`, 2026-11-12); naming a trip of one's own that the person has
  nothing to do with yields the peer view. The profile page applies the same bound
  (`trip.personIsOnTrip` beside the role check in `account/person.xhtml`).
  **Opening a profile is not permission to change it** (2026-11-12): `people.saveProfile` saves only for the
  person, whoever manages them, a site admin, or a people admin of one of their organizations
  (`PersonCommands.mayEditProfile`); a trip's staff get no Save button. Until then the save checked nothing.
- **Everyone else signed-in** (peers, `tripView` holders): exactly what the knobs share. `tripView` is
  roster access and does NOT override a privacy choice.
- **Admin reports and exports** (`admin/reports/*`, the emergency list, the emails page, TSV/XLSX):
  exempt — real data, no masking, no audit. Operational admin tools (rooms, registrations) likewise.

## Where it is enforced

- **REST**: `PersonDto.redactedFor(AccessLevel)` — the single choke point. The DTO carries `privacy`
  (visible to SELF/MANAGER/SITE_ADMIN only, who may also PUT it; the merge is per-knob,
  absent-means-unchanged, garbage ignored). `PersonDtoRedactionTest` pins the matrix.
- **Pages**: `PrivacyView.of(viewer, subject, adminView)` (`#{people.privacyView(person, adminBool)}`)
  answers `SHOW | MASK | HIDE` per field group; `adminView` is the page's own privilege verdict.
  `PrivacyViewTest` pins the page matrix. Every masked value goes through ONE template,
  `WEB-INF/privacyMask.xhtml` (`ui:decorate`, the value as its body): SHOW renders the body, HIDE nothing,
  MASK a `privMaskedText` placeholder whose ajax click adds `personId:field` to `viewScope.privReveals` (a
  scalar, `people.withReveal`, bounded) and re-renders. The body renders only for MASK plus a recorded reveal,
  so a forged reveal of a HIDE value shows nothing. A revealed inplace editor stays rendered for the life of
  the view, so its edit is applied at Save; an unrevealed one is not rendered at all, so Save cannot
  clobber it.
- **Derived leaks handled in Java**: chat roster name-collision labels never use a private email
  (`ChatCommands.labelFor` falls to the id rung); the "via manager" contact fallback skips managers whose
  own email is private (`PersonCommands.primaryManagerOf`); gravatars are suppressed for viewers who may
  not see the email (the gravatar URL is a hash OF the address, fetched by the viewer's browser) —
  initials/icon avatar instead; and people SEARCHES look at cell and email only where the viewer may see
  them (`PersonMatch.Reach`, `PrivacyView.searchReach`), since a row that stays as digits are typed would
  reveal a hidden number one digit at a time.

## Surfaces touched

`account/person.xhtml` (Privacy fieldset + per-fieldset visibility notes + masked inplaces; notes row
hidden from non-admin managers), `trip/tripContacts.xhtml` (knob-aware cells; an opted-in street now shows
to co-travelers where it used to be tripAdmin-only), `admin/people.xhtml`, `admin/person.xhtml`.

Webtest: `PrivacyPwIT` (hide → peer loses it, admin masks it, reveal toggles, street knob locks to city).
