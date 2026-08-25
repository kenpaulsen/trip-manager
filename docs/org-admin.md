# The Org Admin Area (org migration, 2026-08)

Organizations are the tenancy boundary; this doc covers the self-service surface an org's own staff use and
the privilege model behind it. Payments-side org details (processor configs, the payment-config ladder) are
in `payments.md`; family-admin gating is in `family-accounts.md`.

## The hub and its pages

`admin/orgSettings.jsf?orgId=…` is a dashboard of cards; each area is its own page, all sharing the
`WEB-INF/orgTabs.xhtml` nav strip and the self-gating pattern (org authority has no role or privilege row,
so these pages gate themselves in `initPage`, NOT via defaultAuth):

| Page | View gate (`OrgCommands`) | Mutations |
|------|---------------------------|-----------|
| `orgSettings.jsf` (hub) | `canViewOrgHub` — admin OR any org-scoped privilege here | — (cards render per-permission) |
| `orgProfile.jsf` | `canManageOrg` | `saveOrgEdits` (rename stays site-admin) |
| `orgTrips.jsf` | `canViewOrgTrips` — admin OR `addTrip@org` | New Trip: `canCreateTripFor` |
| `orgPeople.jsf` | `canViewOrgPeople` — admin OR `peopleAdmin@org` | **org admins only** (all of them) |
| `orgProcessors.jsf` | `canManageOrg` | `canManageOrg` |

The menu's per-org entries come from `org.visibleOrgs()` (admins ∪ holders of any org-scoped privilege), so
reachability and the hub gate agree. Site admins pass everything via the usual short-circuits.

## Org-scoped privileges

`Privilege` scope suffixes are opaque UUIDs; the base name decides whether a row scopes to a trip or an
org (`PrivilegeCommands.TRIP_SCOPED_BASES` / `ORG_SCOPED_BASES` are the authority). Migrated from global
to org scope — the global variants grant NOTHING anymore; inert global rows can be deleted from the Global
Privileges page once verified:

| Base | Grants (scoped to one org) |
|------|----------------------------|
| `peopleAdmin` | People pages + Manage People for that org's people only; family admin ops over them |
| `addTrip` | Creating trips belonging to that org (page and `POST /api/trips` use the same rule) |
| `emailAdmin` | Mail-merge bounded to that org's members + its trips' rosters (`OrgCommands.sendMerge`) |
| `paymentsAdmin` | The payment page's sandbox toggle for that org's trips |

`addTx` was deleted outright (its one button now uses `tripFinAdmin@trip`, like its destination always
did). `privilegeAdmin`, `configAdmin`, `auditAdmin`, `siteDeployer`, `contentAdmin`, `mediaAdmin`, and
`eventAdmin` remain global.

**Granting**: org admins grant/revoke org-scoped privileges to their MEMBERS on the org People page
(grantee need not be an org admin), through `OrgCommands.grantOrgPrivilege`/`revokeOrgPrivilege` — the
server-side enforcement point. Leaving an org revokes its org-scoped privileges (`removeMember`), and a
member on one of the org's trips cannot be removed at all. Trip roles (Editor Admin, Finance Admin,
Finance Viewer, Viewer, Chat Admin, Registration Admin) are granted on `trip/edit.jsf` by site admins OR
the owning org's admins, through `OrgCommands.setTripRole`. Since 2026-08-24 that page's Trip Managers
section is a chip roster: one row per role holder, a chip per held role with a remove control (rendered
only when `setTripRole` would accept the revoke — `OrgCommands.heldTripRoles`' `grantable` flag), a
per-row "+ Add role" chip opening a shared role-picker overlay (`addableTripRoles`/`addableRolesFor`),
and an Add Manager dialog (person autocomplete + role).

**Tenancy on the manager surfaces** (2026-08-24): the Add Manager autocomplete is
`OrgCommands.completeTripManagerCandidates` — the trip's org MEMBERS only, resolved from the page's
pinned `viewScope.theTripId`, and empty for callers who may not manage the trip's roles (the completion
endpoint is POSTable without the dialog, so authorization lives on the data source). Never wire a
people picker on an org-owned page to the global `people.searchPeople`. `setTripRole` enforces the same
boundary server-side: grants require membership in the trip's org (site admins included, the
`grantOrgPrivilege` stance); revokes stay open so a departed member's stale role remains removable.
Org-less trips are site-admin-only and keep the global search.

`registrationAdmin@trip` (display name "Registration Admin") opens the trip's Registrations tab and the
whole `admin/tripRegistrations.jsf` page — approve, move, rooms, approval mail — for non-site-admins
(user decision 2026-08-24: visibility means full page use). The tab gate (`tripTabs.xhtml`), the page's
`reqPriv` door, and the REST roster (`RegistrationsResource.isTripStaff`) all honor it; it deliberately
does NOT light up the other trip tabs, the way an invited chat guest gets exactly the Chat tab.

**Creating a trip** stamps `openToPublic=false` (never public until chosen) and auto-grants the creator
their standard roles on the new trip -- `tripMgr` (Editor Admin), `tripView` (Viewer), and
`registrationAdmin` -- via `OrgCommands.grantCreatorTripRoles`, called only from the create paths (page
save, REST POST). It deliberately skips the org-admin gate (an `addTrip@org` creator is usually not an org
admin) but the org's allow-list still bounds it: withheld roles surface in a warning dialog on the create
page, and a `[support:trip-roles]` notice (no duplicate guard -- one per trip) goes to the support channel
so a site admin can grant them or adjust the allow-list. A create-save then continues to
`/trip/edit.jsf?id=` (or the itinerary when Editor Admin itself was withheld). On that editor, the
settings rows answer to per-trip roles rather than `showAll`: "Show on homepage?" (`tripMgr`), Edit
Registration Options (`registrationAdmin`), Payment Settings (`tripFinAdmin`), "Enable Trip Chat?"
(`chatMgr`) -- the dialogs themselves render only for their gate's holders, which is also the decode-time
refusal for forged submits.

**Trip staff are people** (2026-08-24): `Trip.facilitatorIds`/`directorIds` (lists of `Person.Id`)
supersede the deprecated free-form `facilitators`/`director` strings. The hand-written
`getFacilitators()`/`getDirector()` resolve ids to comma-joined names (legacy string only when no ids),
and are `@JsonIgnore` so the stored legacy string never gets overwritten by resolved names on save. The
registration popup (`joinTrip.xhtml`) renders the facilitators' name/phone/email as its contact block --
no hardcoded tenant contact -- and its OK returns to the trip. No editor UI for the id lists yet
(provisioned via data/JSON by decision).

**Adding members** (org People page): site admins keep the whole-directory autocomplete; org admins add
by EXACT email address instead (`OrgCommands.addMemberByEmail`) — a name typeahead across every account
leaked other tenants' people. An address with no account offers an invite dialog: `sendOrgInvite` mails
the `org-invite` MAIL template (create-account link; From/base-URL from the `reg.mail.*` settings,
Reply-To the org's contact email). Invites are STATELESS by design — nothing is recorded, no membership
is pre-granted; the admin adds the address again once the account exists, and an account that appears
between check and send folds into a plain add.

## Email addresses (the Settings section, 2026-08-24)

Every address the application sends **with** (From) or **to** (recipient / Reply-To) is a
`KnownSettings` slot resolved by `MailAddressCommands` (`#{mailAddr}`). A slot's stored value is either
the sentinel `site` (the Site email setting), the sentinel `org` (the owning organization's contact
email from its org Profile page, **falling back to the Site email**; only slots with a trip in hand),
or a literal address. The admin Settings page renders the "Email addresses" section with a mode widget
instead of raw text; its From composer is display-name + local-part + a domain dropdown fed by
`MailCommands.verifiedSendingDomains()` (live from SES, ten-minute memo, fixed fake list in local
mode; needs `ses:ListIdentities` + `ses:GetIdentityVerificationAttributes` on the task role).

Rules that outlast the UI:

- **From is never `org`**: SES only sends from verified identities, so an arbitrary org domain as From
  would fail every send. From slots resolve `org` as `site` defensively.
- **Recipients/Reply-To take any well-formed address** — an org's contact email is often external.
- Senders resolve through `MailAddressCommands.from(def)` / `.recipient(def, trip)` /
  `.replyTo(def, trip)` (pages: `mailAddr.fromFor/recipientFor/replyToFor` with the setting key) —
  **never read these settings raw**: the value may be a sentinel, not an address.
- Slot registry: `MailAddressCommands.SLOTS`. Adding a sendable address = a `SettingDef` in the
  Email addresses section + one `Slot` entry; the page renders it automatically.

## The per-org allow-list

`Organization.grantablePrivileges` bounds what an org may grant (both trip roles and org-scoped bases):

- `null` (never set) = **everything allowed** — existing orgs needed no migration; restriction is an
  explicit site-admin act on `admin/organizations.jsf` ("Grantable Privileges" fieldset).
- An EMPTY list = nothing grantable. The getter deliberately never lazy-inits (unlike `paymentDefaults`),
  because that distinction is load-bearing.
- Enforced server-side in `grantOrgPrivilege` and `setTripRole` and reflected in the same
  `grantableOrgPrivileges`/`grantableTripBases` lists the pages render from, so UI and enforcement cannot
  drift. Site admins bypass the allow-list; revocation is never allow-list-checked (stale grants must stay
  removable).

## The privilege editors

`admin/editPrivs.jsf` (Global) / `editTripPrivs.jsf` / `editOrgPrivs.jsf` — three structurally identical
pages (shared `WEB-INF/privEditor.xhtml`, nav via `WEB-INF/privTabs.xhtml`), all site-admin/privilegeAdmin
gated. Names come from a canonical dropdown per scope kind (+ an "Other…" escape for content-container
`editorPrivileges` names); picking a canonical name pre-fills its one-line description
(`PrivilegeCommands.baseDescription`, also defaulted at save when left blank); a picked row's name+scope
are locked (renaming used to orphan rows silently); **Delete** hard-deletes a row, auditing its holder
list.

## REST changes (deliberate breaks)

- `POST /api/trips` requires `orgId` in the body; authorized by site admin / org admin / `addTrip@org`.
- `POST /api/people` requires `?org=` for non-site-admins holding `peopleAdmin@org`; the new person is
  membered into that org. People search results are bounded per-subject (`canAdminPerson`), so counts can
  shrink for pre-migration clients.
- `AuthResource` no longer reports `peopleAdmin`/`emailAdmin`/`addTrip` flags (absent reads as false).

## Local-mode fixtures (`FakeData`)

Acme trip `3f7a9c15…` (Kevin on the roster — demonstrates the removal guard), `emailAdmin@CFPW` → user2,
`peopleAdmin@CFPW` → user4, and Acme's allow-list excludes `paymentsAdmin` (Kevin cannot grant it).
