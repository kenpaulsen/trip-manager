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
per-row "Add role" menu (`addableTripRoles`), and an Add Manager dialog (person autocomplete + role).

`registrationAdmin@trip` (display name "Registration Admin") opens the trip's Registrations tab and the
whole `admin/tripRegistrations.jsf` page — approve, move, rooms, approval mail — for non-site-admins
(user decision 2026-08-24: visibility means full page use). The tab gate (`tripTabs.xhtml`), the page's
`reqPriv` door, and the REST roster (`RegistrationsResource.isTripStaff`) all honor it; it deliberately
does NOT light up the other trip tabs, the way an invited chat guest gets exactly the Chat tab.

**Adding members** (org People page): site admins keep the whole-directory autocomplete; org admins add
by EXACT email address instead (`OrgCommands.addMemberByEmail`) — a name typeahead across every account
leaked other tenants' people. An address with no account offers an invite dialog: `sendOrgInvite` mails
the `org-invite` MAIL template (create-account link; From/base-URL from the `reg.mail.*` settings,
Reply-To the org's contact email). Invites are STATELESS by design — nothing is recorded, no membership
is pre-granted; the admin adds the address again once the account exists, and an account that appears
between check and send folds into a plain add.

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
