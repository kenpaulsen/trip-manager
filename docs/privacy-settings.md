# Privacy settings

Per-user visibility choices for profile fields, decided 2026-08-12. Read this before touching Person field
visibility, `PersonDto` redaction, `tripContacts.xhtml`, or `account/person.xhtml`.

## The model

`PrivacySettings` lives directly ON `Person` (no separate table; setter-populated like `familyId`, never in
the 17-arg builder, never null; old rows deserialize to the defaults). Exactly four knobs, each
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

`isStreetVisible()` is false whenever city is private, regardless of the stored street knob; the rule is
enforced in the model, not just the UI (the profile page additionally disables the street selector).

Everything else is fixed policy, no knob:

- **Always private**: birthdate, passport, TSA, emergency contacts, notes.
- **Always visible to signed-in users**: name, sex. (Sex was deliberately WIDENED out of
  `seesTravelDocuments()`, user-confirmed. `tripContacts.xhtml` still renders it only in the admin
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
  `AccessLevel.seesNotes()`: a manager does not get them (hidden on pages, absent over REST).
- **Trip admins (`tripMgr`) and site admins**: may get the real values, but withheld fields render
  **masked**: the value is in the page, hidden behind a "private value" placeholder, and a click shows it in
  the browser with no server round trip. No audit of reveals. (Owner decision 2026-11-13. The 2026-11-12
  round kept a masked value out of the page until an ajax click fetched it; a round trip per value was a
  usability problem, and these viewers are entitled to the value anyway. A viewer who may NOT see a value
  still never gets it in the page: that is `HIDE`, below.) `tripFinView` keeps
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
  exempt: real data, no masking, no audit. Operational admin tools (rooms, registrations) likewise.

## Where it is enforced

- **REST**: `PersonDto.redactedFor(AccessLevel)`, the single choke point. The DTO carries `privacy`
  (visible to SELF/MANAGER/SITE_ADMIN only, who may also PUT it; the merge is per-knob,
  absent-means-unchanged, garbage ignored). `PersonDtoRedactionTest` pins the matrix.
- **Pages**: `PrivacyView.of(viewer, subject, adminView)` (`#{people.privacyView(person, adminBool)}`)
  answers `SHOW | MASK | HIDE` per field group; `adminView` is the page's own privilege verdict.
  `PrivacyViewTest` pins the page matrix. Every masked value goes through ONE template,
  `WEB-INF/privacyMask.xhtml` (`ui:decorate`, the value as its body, one `verdict` param): SHOW renders the
  body; HIDE, or no verdict at all, renders nothing; MASK renders the body inside a hidden span next to a
  `privMaskedText` placeholder, and the placeholder's click adds `privRevealed` to the wrapper in the browser
  (`trip.css` swaps which one shows). The wrapper is a span, so masked values sit inline. A masked inplace
  editor is always rendered, so Save submits its value unchanged unless it was revealed and edited.
  An EMPTY value on a read-only display is HIDE, so nobody is offered a "private value" that reveals
  nothing. The rule lives in the template: a read-only host passes the optional `blank` param, its own
  emptiness test for what the body shows (`#{empty person.cell}`; trip Contacts' "City, State" is blank only
  when both are), and the template turns a MASK into HIDE (a SHOW still renders, so a disabled editor keeps
  "Not given"). Every read-only mask passes it: trip
  Contacts, People, Org People, the admin person page (Notes too), Manage People's person 2 column and the
  profile's read-only email. An editor the viewer can use leaves it unset and keeps its verdict, so staff can
  still fill an empty field. An editor that is DISABLED for some viewers is read-only for them, so it passes
  `blank` conditioned on the same right as its `disabled=`, with the same polarity: every profile inplace is
  disabled for a trip's staff (they may read the profile, not save it), so each passes
  `#{not viewScope.canSaveProfile and empty person.tsa}`; Manage People's person 1 email passes
  `#{not requestScope.p1MayChangeEmail and empty person1.email}`. The profile's "Show private values" links
  are offered on the same terms, so a fieldset of empty values offers no link either.
  `PrivacyMaskPolicyIT` scans every page for the three shapes (read-only: `blank`; usable editor: none;
  disabled editor: `blank` naming the editor's right) and proves the scan both ways on hand-written masks.
  (The param is not called `empty`, which is an EL operator.)
  `WEB-INF/privacyRevealAll.xhtml` is a "Show private values" link that reveals every mask in its scope: the
  nearest `privScope` element, else its fieldset (the profile's Address, Travel Info and Contact Info; Manage
  People's two-person compare grid carries `privScope` and the link in its header). A list item cannot be
  masked: Manage People's person pickers put an email or cell in the label only where the verdict is not HIDE
  (`PersonCommands.adminPickerLabel`).
- **Derived leaks handled in Java**: chat roster name-collision labels never use a private email
  (`ChatCommands.labelFor` falls to the id rung); the "via manager" contact fallback skips managers whose
  own email is private (`PersonCommands.primaryManagerOf`); gravatars are suppressed for viewers who may
  not see the email at all (the gravatar URL is a hash OF the address, fetched by the viewer's browser);
  they get initials or an icon avatar instead. A MASK viewer may see the address, so a gravatar may use it while it is
  masked (owner decision 2026-11-13; the profile page's own gravatar is shown that way). People SEARCHES look at cell and email only where the viewer may see
  them (`PersonMatch.Reach`, `PrivacyView.searchReach`), since a row that stays as digits are typed would
  reveal a hidden number one digit at a time.

## Surfaces touched

`account/person.xhtml` (Privacy fieldset + per-fieldset visibility notes + masked inplaces; notes row
hidden from non-admin managers), `trip/tripContacts.xhtml` (knob-aware cells; an opted-in street now shows
to co-travelers where it used to be tripAdmin-only), `admin/people.xhtml`, `admin/person.xhtml`.

Webtest: `PrivacyPwIT` (hide → peer loses it and it is not in the page, admin gets it masked and a click
shows it with no request, "Show private values" reveals a fieldset, street knob locks to city, View As from
`admin/person.xhtml` hides the subject's sign-in fieldsets, an empty private value offers nothing to reveal,
a trip manager opening an empty traveler's profile with `?trip=` gets no mask and no "Show private values"
while a site admin keeps the editors to fill, and a profile saved with every mask closed keeps its birthdate,
passport dates and cell exactly as stored);
`AdminManagePersonPwIT` proves the same save rule for Save Person 1 and the compare grid's one-click reveal.
