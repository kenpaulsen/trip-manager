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

`isStreetVisible()` is false whenever city is private, regardless of the stored street knob — the rule is
enforced in the model, not just the UI (the profile page additionally disables the street selector).

Everything else is fixed policy, no knob:

- **Always private**: birthdate, passport, TSA, emergency contacts, notes.
- **Always visible to signed-in users**: name, sex. (Sex was deliberately WIDENED out of
  `seesTravelDocuments()` — user-confirmed. `tripContacts.xhtml` still renders it only in the admin
  columns, but that is a space/clutter choice, not privacy.)

"Visible" always means **any authenticated user** (the user anticipates a future profile page; names are
discoverable through chat). Nothing here publishes anything to anonymous visitors, and the public board
page (`board/index.xhtml`) is explicitly out of scope.

## Who sees what

- **Self and family managers**: privacy never hides anything from them. Exception: staff **notes** follow
  `AccessLevel.seesNotes()` — a manager does not get them (hidden on pages, absent over REST).
- **Trip admins (`tripMgr`) and site admins**: always get the real values, but withheld fields render
  **masked** (blurred, click-to-reveal). The mask is **UI-only** by user decision — no server-side reveal
  endpoint, no audit of reveals. `tripFinView` keeps its `account/person.xhtml?trip=` access (they mail
  checks / validate identity); the mask covers casual reading.
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
  `PrivacyViewTest` pins the page matrix. Masking uses `privMasked` (inplace) / `privMaskedText` (plain
  text) in `trip.css`, with the click toggle in `resources/trip-js/privacy.js` (included by
  `template.xhtml`).
- **Derived leaks handled in Java**: chat roster name-collision labels never use a private email
  (`ChatCommands.labelFor` falls to the id rung); the "via manager" contact fallback skips managers whose
  own email is private (`PersonCommands.primaryManagerOf`); gravatars are suppressed for viewers who may
  not see the email (the gravatar URL is a hash OF the address, fetched by the viewer's browser) —
  initials/icon avatar instead.

## Surfaces touched

`account/person.xhtml` (Privacy fieldset + per-fieldset visibility notes + masked inplaces; notes row
hidden from non-admin managers), `trip/tripContacts.xhtml` (knob-aware cells; an opted-in street now shows
to co-travelers where it used to be tripAdmin-only), `admin/people.xhtml`, `admin/person.xhtml`.

Webtest: `PrivacyPwIT` (hide → peer loses it, admin masks it, reveal toggles, street knob locks to city).
